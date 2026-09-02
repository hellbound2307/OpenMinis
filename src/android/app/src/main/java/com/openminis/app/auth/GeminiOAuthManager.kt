package com.openminis.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

class GeminiOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {
    companion object {
        private const val TAG = "GeminiOAuth"
        private const val USER_AGENT = "GeminiCLI/0.30.0 (android; aarch64)"

        /**
         * Proactive refresh window — mirrors [ClaudeOAuthManager] for consistent
         * behavior across Anthropic + Google OAuth. iOS Gemini uses a 0-second
         * buffer (only refreshes when actually expired); keeping 5 minutes here
         * avoids a race on Google's 1-hour access tokens.
         */
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L

        /** Per-instance refresh mutex — serializes concurrent refresh calls. */
        private val refreshMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        private fun mutexFor(instanceId: String): Mutex =
            refreshMutexes.getOrPut(instanceId) { Mutex() }

        /**
         * Static helper mirroring [ClaudeOAuthManager.login] /
         * [OpenAIOAuthManager.login] — full sign-in flow, token persisted
         * through [com.openminis.app.data.repository.ProviderRepository.saveApiKey],
         * access token returned.
         */
        suspend fun login(
            context: Context,
            instanceId: String,
            providerRepository: com.openminis.app.data.repository.ProviderRepository,
        ): String {
            val manager = GeminiOAuthManager(context, instanceId)
            val token = manager.performLogin(context)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    /**
     * Refresh outcome classification. Mirrors [ClaudeOAuthManager.RefreshOutcome]
     * for uniform dispatch at call sites that work with either provider.
     */
    enum class RefreshOutcome { SUCCESS, INVALID_GRANT, TRANSIENT, NO_TOKEN }

    override val authURL = "https://accounts.google.com/o/oauth2/v2/auth"
    override val tokenURL = "https://oauth2.googleapis.com/token"

    /**
     * Google OAuth client. Defaults to the Gemini CLI's public installed-app
     * client (the same one iOS ships), overridable at build time via
     * provider-customization.properties → GOOGLE_OAUTH_CLIENT_ID/SECRET for
     * a dedicated GCP client. Installed-app clients are not confidential:
     * the secret ships in the binary by design, exactly like Gemini CLI.
     */
    override val clientId: String = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
    override val clientSecret: String? =
        BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET.ifEmpty { null }
    override val callbackPort = 8085
    override val redirectPath = "/oauth2callback"
    override val scopes = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile"

    override fun buildAuthorizationUrl(): String {
        val (_, challenge) = generateHexPKCE()
        val state = generateState()
        lastLoginState = state
        return "$authURL?" + listOf(
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopes)}",
            "state=$state",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
            "access_type=offline",
            "prompt=consent",
        ).joinToString("&")
    }

    /** State issued by the most recent [buildAuthorizationUrl] call. */
    @Volatile
    private var lastLoginState: String? = null

    private var loginCallbackServer: OAuthCallbackServer? = null

    /**
     * Perform the full OAuth login flow: loopback callback server on
     * [callbackPort], browser sign-in, code exchange. Returns the access
     * token. Throws on failure.
     *
     * Uses a Custom Tab (not a cold ACTION_VIEW) like the other managers:
     * MainActivity is singleTask, and a new-task browser makes the IME
     * dismiss whenever the Google page focuses an input.
     */
    suspend fun performLogin(context: Context): String {
        AppLogger.info(TAG, "=== Gemini OAuth login started (instance=$instanceId) ===")

        loginCallbackServer?.stop()
        loginCallbackServer = null

        val authUrl = buildAuthorizationUrl()
        val redacted = authUrl.replace(Regex("code_challenge=[^&]+"), "code_challenge=<redacted>")
        AppLogger.info(TAG, "authorize URL: $redacted")

        val accessToken = withContext(Dispatchers.IO) {
            val (code, state) = suspendCancellableCoroutine<Pair<String, String?>> { cont ->
                val server = OAuthCallbackServer(callbackPort) { receivedCode, receivedState ->
                    if (cont.isActive) cont.resume(receivedCode to receivedState)
                }
                loginCallbackServer = server
                server.start()
                cont.invokeOnCancellation {
                    server.stop()
                    loginCallbackServer = null
                }
                val customTabsIntent = CustomTabsIntent.Builder().setShowTitle(true).build()
                customTabsIntent.launchUrl(context, Uri.parse(authUrl))
            }
            loginCallbackServer?.stop()
            loginCallbackServer = null

            if (state != null && lastLoginState != null && state != lastLoginState) {
                AppLogger.warning(TAG, "state mismatch during Gemini OAuth — proceeding (mirrors OpenAI path)")
            }

            // Base-class exchange: form-urlencoded grant with client_secret +
            // PKCE verifier — the shape Google's token endpoint requires.
            val ok = exchangeCode(code)
            if (!ok) throw Exception("Google sign-in failed at the token exchange step. Check the network and try again.")
            loadStoredTokens()?.optString("access_token", "")?.takeIf { it.isNotEmpty() }
                ?: throw Exception("Google sign-in returned no access token.")
        }

        AppLogger.info(TAG, "=== Gemini OAuth login complete (instance=$instanceId tokenLen=${accessToken.length}) ===")
        return accessToken
    }

    var email: String?
        get() = loadOAuthString("email")
        private set(value) { value?.let { saveOAuthString("email", it) } }

    var gcpProjectId: String?
        get() = loadOAuthString("gcp_project")
        private set(value) { value?.let { saveOAuthString("gcp_project", it) } }

    override suspend fun onTokensReceived(json: JSONObject) {
        fetchUserEmail()
    }

    private suspend fun fetchUserEmail() {
        val token = validAccessToken() ?: return
        try {
            val conn = URL("https://www.googleapis.com/oauth2/v1/userinfo").openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                email = JSONObject(body).optString("email")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch email", e)
        }
    }

    /**
     * Detailed, user-facing Code Assist provisioning failure. Carries Google's
     * actual reason (HTTP status + error message / ineligibility text) so the
     * chat error bubble says WHY instead of a generic "could not provision".
     */
    class CodeAssistProvisioningException(message: String) : Exception(message)

    /**
     * Resolve (and cache) the Code Assist project id, throwing
     * [CodeAssistProvisioningException] with Google's real reason on failure.
     *
     * Wire format mirrors gemini-cli `_doSetupUser` (setup.ts) exactly:
     *   1. `loadCodeAssist` — paid tiers return `cloudaicompanionProject`
     *      directly; free accounts get tier info without a project.
     *   2. `ineligibleTiers` with `VALIDATION_REQUIRED` → surface the
     *      validation URL; any other ineligibility → surface the reasons
     *      (region, account type, age…).
     *   3. `currentTier` without a project → workspace-style account that
     *      requires a user-defined Cloud project.
     *   4. Otherwise `onboardUser` with the default advertised tier — the
     *      free tier body OMITS `cloudaicompanionProject` (sending one makes
     *      Google answer `Precondition Failed`) and carries no licenseState.
     *      Poll the returned LRO operation until it yields the project.
     */
    suspend fun resolveProject(): String = withContext(Dispatchers.IO) {
        gcpProjectId?.let { return@withContext it }
        val token = validAccessToken()
            ?: throw CodeAssistProvisioningException(
                "Google sign-in has expired. Open the provider settings and sign in again.",
            )

        // 1. loadCodeAssist
        val load = loadCodeAssist(token)
        load.projectId?.let {
            gcpProjectId = it
            return@withContext it
        }

        // 2. Eligibility problems — Google told us exactly why this account
        //    can't get a Code Assist project. Say so instead of "try again".
        load.ineligible.firstOrNull {
            it.reasonCode == "VALIDATION_REQUIRED" && !it.validationUrl.isNullOrEmpty()
        }?.let { v ->
            throw CodeAssistProvisioningException(
                "Google requires account validation before Gemini Code Assist can be " +
                    "provisioned (${v.reasonMessage ?: "verify your account"}). Open " +
                    "${v.validationUrl} in a browser, complete the check, then sign in again.",
            )
        }
        if (load.ineligible.isNotEmpty()) {
            val reasons = load.ineligible
                .mapNotNull { it.reasonMessage?.takeIf(String::isNotEmpty) ?: it.reasonCode }
                .distinct()
                .joinToString("; ")
                .ifEmpty { "unknown reason" }
            throw CodeAssistProvisioningException(
                "This Google account is not eligible for Gemini Code Assist: $reasons. " +
                    "Configure the provider with an AI Studio API key instead.",
            )
        }

        // 3. currentTier without a project — workspace / Cloud-identity accounts
        //    (gemini-cli throws ProjectIdRequiredError here; an Android app has
        //    no env var to point at a project, so say what's missing).
        load.currentTierId?.let { tier ->
            throw CodeAssistProvisioningException(
                "Google sign-in succeeded, but this account's tier ($tier) requires a " +
                    "Google Cloud project that could not be determined. Personal accounts " +
                    "can use an AI Studio API key instead.",
            )
        }

        // 4. Onboard with the tier Google advertises as default.
        val tier = load.defaultTierId ?: "free-tier"
        val project = onboardUser(token, tier)
            ?: throw CodeAssistProvisioningException(
                "Gemini Code Assist onboarding did not return a project (tier '$tier'). " +
                    "Try signing in again, or configure the provider with an AI Studio " +
                    "API key instead.",
            )
        gcpProjectId = project
        project
    }

    /** Boolean compatibility wrapper over [resolveProject]. */
    suspend fun discoverProjectIfNeeded(): Boolean =
        try {
            resolveProject(); true
        } catch (_: Exception) {
            false
        }

    private data class CodeAssistIneligible(
        val reasonCode: String?,
        val reasonMessage: String?,
        val validationUrl: String?,
    )

    private data class CodeAssistLoad(
        val projectId: String?,
        val currentTierId: String?,
        val defaultTierId: String?,
        val ineligible: List<CodeAssistIneligible>,
    )

    /**
     * POST a Code Assist call and return (HTTP code, body). Errors are NEVER
     * swallowed here — the caller turns them into a surfaced reason.
     */
    private fun postCodeAssist(token: String, url: String, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return code to text
    }

    /** Extract Google's `error.message` (or a truncated raw body) for display. */
    private fun describeFailure(action: String, code: Int, body: String): String {
        val detail = try {
            val err = JSONObject(body).optJSONObject("error")
            err?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: err?.optString("status")?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } ?: body.take(200).ifBlank { "no response body" }
        AppLogger.warning(TAG, "Code Assist $action failed: HTTP $code $detail")
        return "Google rejected $action (HTTP $code): $detail"
    }

    private fun loadCodeAssist(token: String): CodeAssistLoad {
        val (code, body) = postCodeAssist(
            token,
            "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist",
            """{"metadata":{"ideType":"IDE_UNSPECIFIED","platform":"PLATFORM_UNSPECIFIED","pluginType":"GEMINI"}}""",
        )
        if (code !in 200..299) {
            throw CodeAssistProvisioningException(describeFailure("loading your Gemini Code Assist profile", code, body))
        }
        val json = try { JSONObject(body) } catch (e: Exception) {
            throw CodeAssistProvisioningException("Google returned an unreadable Code Assist profile: ${body.take(200)}")
        }
        AppLogger.info(TAG, "loadCodeAssist ok: ${json.toString().take(400)}")

        // cloudaicompanionProject is a STRING project id on most tiers; some
        // responses wrap it as an object with an "id".
        val project = json.optString("cloudaicompanionProject").ifEmpty { null }
            ?: json.optJSONObject("cloudaicompanionProject")?.optString("id")?.ifEmpty { null }

        val currentTierId = json.optJSONObject("currentTier")?.optString("id")?.ifEmpty { null }

        // Default advertised tier → the onboarding tier gemini-cli picks.
        var defaultTier: String? = null
        val tiers = json.optJSONArray("allowedTiers")
        if (tiers != null) {
            for (i in 0 until tiers.length()) {
                val t = tiers.optJSONObject(i) ?: continue
                val id = t.optString("id")
                if (id.isNotEmpty() && t.optBoolean("isDefault")) {
                    defaultTier = id
                    break
                }
            }
        }

        val ineligible = mutableListOf<CodeAssistIneligible>()
        val bad = json.optJSONArray("ineligibleTiers")
        if (bad != null) {
            for (i in 0 until bad.length()) {
                val t = bad.optJSONObject(i) ?: continue
                ineligible.add(
                    CodeAssistIneligible(
                        reasonCode = t.optString("reasonCode").ifEmpty { null },
                        reasonMessage = t.optString("reasonMessage").ifEmpty { null },
                        validationUrl = t.optString("validationUrl").ifEmpty { null },
                    ),
                )
            }
        }
        return CodeAssistLoad(project, currentTierId, defaultTier, ineligible)
    }

    private suspend fun onboardUser(token: String, tierId: String): String? {
        val (code, body) = postCodeAssist(
            token,
            "https://cloudcode-pa.googleapis.com/v1internal:onboardUser",
            """{"tierId":"$tierId","metadata":{"ideType":"IDE_UNSPECIFIED","platform":"PLATFORM_UNSPECIFIED","pluginType":"GEMINI"}}""",
        )
        if (code !in 200..299) {
            AppLogger.warning(TAG, "onboardUser failed: HTTP $code ${body.take(300)}")
            return null
        }
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val opName = json.optString("name")
        return if (opName.isNotEmpty()) {
            AppLogger.info(TAG, "onboardUser LRO: $opName")
            pollOperation(token, opName)
        } else {
            operationProject(json)
        }
    }

    /** Unwrap an LRO result: response.cloudaicompanionProject.{id|string} or response.gcpProjectId. */
    private fun operationProject(json: JSONObject): String? {
        val response = json.optJSONObject("response") ?: return json.optString("gcpProjectId").ifEmpty { null }
        val cap = response.optJSONObject("cloudaicompanionProject")
        return cap?.optString("id")?.ifEmpty { null }
            ?: cap?.optString("name")?.ifEmpty { null }
            ?: response.optString("cloudaicompanionProject").ifEmpty { null }
            ?: response.optString("gcpProjectId").ifEmpty { null }
    }

    private suspend fun pollOperation(token: String, operationName: String): String? {
        // The LRO poll URL mirrors gemini-cli getOperationUrl: `${base}/${name}`
        // where base already includes the `v1internal` segment.
        var consecutiveErrors = 0
        for (i in 0 until 24) {
            delay(5000)
            try {
                val conn = URL("https://cloudcode-pa.googleapis.com/v1internal/$operationName").openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("User-Agent", USER_AGENT)
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                if (code !in 200..299) {
                    consecutiveErrors++
                    AppLogger.warning(TAG, "LRO poll $i: HTTP $code ${body.take(200)}")
                    if (consecutiveErrors >= 4) {
                        throw CodeAssistProvisioningException(
                            "Lost track of the Gemini Code Assist onboarding (HTTP $code). " +
                                "Try signing in again, or use an AI Studio API key instead.",
                        )
                    }
                    continue
                }
                consecutiveErrors = 0
                val json = JSONObject(body)
                if (json.optBoolean("done")) {
                    return operationProject(json)
                }
            } catch (e: CodeAssistProvisioningException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "LRO poll $i transient: ${e.message}")
            }
        }
        return null
    }

    // ── Token refresh with classification + concurrency protection ──
    // Mirrors ClaudeOAuthManager's refresh machinery; see RefreshOutcome for
    // the error taxonomy.

    /** Boolean compatibility wrapper over [refreshTokenClassified]. */
    override suspend fun refreshToken(): Boolean =
        refreshTokenClassified() == RefreshOutcome.SUCCESS

    /**
     * Refresh with structured error classification. Serialized per-instance
     * so concurrent callers share a single network round-trip. Mirrors iOS
     * `GeminiOAuthManager.validAccessToken` error handling:
     *
     *  - `INVALID_GRANT` — Google returned 400/401/403 or `invalid_grant`/
     *    `invalid_token`: refresh token is revoked/expired. Stored credentials
     *    are cleared (`logout()`), user must re-run OAuth.
     *  - `TRANSIENT` — network/5xx: credentials kept; caller may retry.
     *  - `NO_TOKEN` — no stored refresh token (not logged in).
     *  - `SUCCESS` — new access token persisted.
     */
    suspend fun refreshTokenClassified(): RefreshOutcome = withContext(Dispatchers.IO) {
        val mutex = mutexFor(instanceId)
        mutex.withLock {
            val stored = loadStoredTokens() ?: return@withLock RefreshOutcome.NO_TOKEN
            val refreshTokenValue = stored.optString("refresh_token", "")
                .ifEmpty { return@withLock RefreshOutcome.NO_TOKEN }

            // Coalesce concurrent refreshers: if another coroutine refreshed
            // while we were queued on the mutex, short-circuit with SUCCESS.
            val priorExpireAt = stored.optLong("expire_at", 0)
            val freshCheck = loadStoredTokens()?.optLong("expire_at", 0) ?: 0
            if (freshCheck > priorExpireAt && freshCheck > System.currentTimeMillis()) {
                Log.d(TAG, "Refresh skipped — fresher token already present (concurrent refresh coalesced)")
                return@withLock RefreshOutcome.SUCCESS
            }

            try {
                // Google's token endpoint accepts form-urlencoded (NOT JSON like Anthropic).
                val params = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshTokenValue,
                    "client_id" to clientId,
                    "client_secret" to (clientSecret ?: ""),
                )
                val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
                val request = okhttp3.Request.Builder()
                    .url(tokenURL)
                    .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                val response = httpClient.newCall(request).execute()
                val responseCode = response.code
                val responseBody = response.body?.string() ?: ""
                response.close()

                if (responseCode in 200..299) {
                    val json = JSONObject(responseBody)
                    // Google may rotate or drop the refresh_token; preserve if absent.
                    if (!json.has("refresh_token")) {
                        json.put("refresh_token", refreshTokenValue)
                    }
                    val expiresIn = json.optLong("expires_in", 0)
                    if (expiresIn > 0) {
                        json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
                    }
                    saveOAuthString("tokens", json.toString())
                    Log.i(TAG, "Gemini token refresh successful. Expires in ${expiresIn}s")
                    return@withLock RefreshOutcome.SUCCESS
                }

                // Classify non-2xx response. Google's token endpoint returns 400
                // with `error=invalid_grant` for revoked/expired refresh tokens,
                // and `error=invalid_token` for truly malformed tokens. Both are
                // non-recoverable; user must re-auth.
                val bodyLower = responseBody.lowercase()
                val isInvalidGrant = responseCode == 400 || responseCode == 401 || responseCode == 403 ||
                    bodyLower.contains("invalid_grant") ||
                    bodyLower.contains("invalid_token")
                if (isInvalidGrant) {
                    Log.e(TAG, "Refresh token invalid ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — clearing credentials")
                    logout()
                    return@withLock RefreshOutcome.INVALID_GRANT
                }
                Log.w(TAG, "Gemini token refresh transient failure ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — keeping token")
                return@withLock RefreshOutcome.TRANSIENT
            } catch (e: Exception) {
                Log.w(TAG, "Gemini token refresh transient error — keeping token", e)
                return@withLock RefreshOutcome.TRANSIENT
            }
        }
    }

    /**
     * Return a valid access token, refreshing within the 5-minute pre-expiry
     * window. Returns null if credentials are missing or invalid. Mirrors iOS
     * `GeminiOAuthManager.validAccessToken` behavior (with Android's wider
     * pre-refresh window — iOS Gemini refreshes only on hard expiry).
     */
    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val stored = loadStoredTokens() ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        val needsRefresh = expireAt > 0 && (expireAt - now) <= REFRESH_BUFFER_MS
        if (!needsRefresh) return@withContext token

        when (refreshTokenClassified()) {
            RefreshOutcome.SUCCESS ->
                loadStoredTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT -> null
            RefreshOutcome.TRANSIENT ->
                if (expireAt > 0 && now >= expireAt) null else token
            RefreshOutcome.NO_TOKEN -> null
        }
    }
}
