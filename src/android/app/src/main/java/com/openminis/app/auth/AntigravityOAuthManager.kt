package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.BuildConfig
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Antigravity OAuth manager — mirrors iOS `AntigravityOAuthManager`.
 *
 * Antigravity is Google's agentic platform that succeeded "Gemini Code Assist
 * for individuals" (the Gemini CLI OAuth surface was shut down in June 2026 —
 * accounts now get "This client is no longer supported … migrate to the
 * Antigravity suite of products"). Antigravity reuses Google's OAuth
 * endpoints but with its own client_id + client_secret and two extra scopes
 * (`cclog`, `experimentsandconfigs`), and runs a local HTTP callback on port
 * 8086 (distinct from Gemini's 8085 so the two can coexist).
 *
 * Wire details cross-checked against the maintained open-source
 * opencode-antigravity-auth plugin (NoeFabris) and the iOS implementation.
 */
class AntigravityOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {
    companion object {
        private const val TAG = "AntigravityOAuth"
        private const val USER_AGENT = "antigravity/1.107.0 android/aarch64"

        /** Identity metadata the Antigravity surface expects alongside setup calls. */
        private const val CLIENT_METADATA =
            """{"ideType":"ANTIGRAVITY","platform":"MACOS","pluginType":"GEMINI"}"""

        /**
         * Candidate Cloud Code Assist base URLs, probed in order on login.
         * `daily-cloudcode-pa` is the preview / daily release channel,
         * `autopush-cloudcode-pa` the canary; the public `cloudcode-pa` is
         * the production fallback (and the endpoint where loadCodeAssist is
         * best supported for managed projects, per the opencode plugin).
         * Matches iOS `AntigravityOAuthManager.cloudCodeBaseURLs` plus the
         * autopush hop the community tooling uses.
         */
        val cloudCodeBaseURLs = listOf(
            "https://daily-cloudcode-pa.sandbox.googleapis.com",
            "https://autopush-cloudcode-pa.sandbox.googleapis.com",
            "https://cloudcode-pa.googleapis.com",
        )

        /**
         * Community-standard fallback project. Antigravity's loadCodeAssist /
         * onboardUser return no project for some account shapes (notably
         * Workspace accounts); the opencode-antigravity-auth plugin falls back
         * to this hardcoded project id in exactly that case, and inference
         * accepts it. Last-resort only — discovery runs first.
         */
        const val FALLBACK_PROJECT_ID = "rising-fact-p41fc"

        /**
         * Static helper mirroring [GeminiOAuthManager.login] — full sign-in
         * flow, token persisted through
         * [com.openminis.app.data.repository.ProviderRepository.saveApiKey],
         * access token returned.
         */
        suspend fun login(
            context: Context,
            instanceId: String,
            providerRepository: ProviderRepository,
        ): String {
            val manager = AntigravityOAuthManager(context, instanceId)
            val token = manager.performLogin(context)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    override val authURL = "https://accounts.google.com/o/oauth2/v2/auth"
    override val tokenURL = "https://oauth2.googleapis.com/token"

    /**
     * Google OAuth client for the Antigravity surface. Defaults to the
     * Antigravity IDE's installed-app client (the same one iOS ships and the
     * community tooling uses); both values are overridable at build time via
     * provider-customization.properties → ANTIGRAVITY_OAUTH_CLIENT_ID/SECRET.
     * Installed-app clients are not confidential: the secret ships in the
     * IDE binary by design.
     */
    override val clientId: String = BuildConfig.ANTIGRAVITY_OAUTH_CLIENT_ID
    override val clientSecret: String? =
        BuildConfig.ANTIGRAVITY_OAUTH_CLIENT_SECRET.ifEmpty { null }
    override val callbackPort = 8086
    override val redirectPath = "/oauth2callback"
    override val scopes = listOf(
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
        "https://www.googleapis.com/auth/cclog",
        "https://www.googleapis.com/auth/experimentsandconfigs",
    ).joinToString(" ")

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
     * [callbackPort] (8086 — Gemini's 8085 stays free), browser sign-in via
     * Custom Tab, code exchange, then email fetch + Cloud Code project
     * discovery. Returns the access token. Throws on failure.
     */
    suspend fun performLogin(context: Context): String {
        Log.i(TAG, "=== Antigravity OAuth login started (instance=$instanceId) ===")

        loginCallbackServer?.stop()
        loginCallbackServer = null

        val authUrl = buildAuthorizationUrl()
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
                Log.w(TAG, "state mismatch during Antigravity OAuth — proceeding (mirrors Gemini path)")
            }

            // Base-class exchange: form-urlencoded grant with client_secret +
            // PKCE verifier — the shape Google's token endpoint requires.
            val ok = exchangeCode(code)
            if (!ok) throw Exception("Antigravity sign-in failed at the token exchange step. Check the network and try again.")
            loadStoredTokens()?.optString("access_token", "")?.takeIf { it.isNotEmpty() }
                ?: throw Exception("Antigravity sign-in returned no access token.")
        }

        AppLogger.info(TAG, "=== Antigravity OAuth login complete (instance=$instanceId tokenLen=${accessToken.length}) ===")
        return accessToken
    }

    /** Cached user email from Google userinfo. */
    var email: String?
        get() = loadOAuthString("email")
        private set(value) { value?.let { saveOAuthString("email", it) } }

    /** GCP project ID discovered via Cloud Code Assist loadCodeAssist. */
    var gcpProjectId: String?
        get() = loadOAuthString("gcp_project")
        private set(value) { value?.let { saveOAuthString("gcp_project", it) } }

    /**
     * Cloud Code Assist base URL that successfully returned the project on
     * login. Subsequent requests pin to this URL so the provider doesn't
     * re-probe the daily/autopush/prod trio on every call.
     */
    var activeBaseURL: String?
        get() = loadOAuthString("base_url")
        private set(value) { value?.let { saveOAuthString("base_url", it) } }

    override suspend fun onTokensReceived(json: JSONObject) {
        fetchUserEmail()
        discoverProjectIfNeeded()
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
     * Resolve the Cloud Code Assist project for this account, mirroring iOS
     * `discoverCloudCodeProject`:
     *   1. `loadCodeAssist` on every candidate base URL (production first —
     *      it is the endpoint best supported for managed project resolution).
     *   2. `onboardUser` (FREE tier) on every base URL for accounts that have
     *      no Code Assist project yet — polls the long-running operation.
     *   3. Community fallback project for account shapes (Workspace) where
     *      Google returns nothing but inference still works.
     *
     * Returns true when [gcpProjectId] + [activeBaseURL] are usable afterwards.
     */
    suspend fun discoverProjectIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (!gcpProjectId.isNullOrEmpty() && !activeBaseURL.isNullOrEmpty()) return@withContext true
        val token = validAccessToken() ?: return@withContext false

        // 1. loadCodeAssist — prod first, then sandbox channels.
        for (baseURL in cloudCodeBaseURLs.reversed()) {
            val projectId = loadCodeAssist(token, baseURL)
            if (!projectId.isNullOrEmpty()) {
                gcpProjectId = projectId
                activeBaseURL = baseURL
                Log.i(TAG, "Antigravity project discovered via $baseURL")
                return@withContext true
            }
        }

        // 2. onboardUser (FREE tier) + operation polling.
        for (baseURL in cloudCodeBaseURLs.reversed()) {
            val projectId = onboardUser(token, baseURL)
            if (!projectId.isNullOrEmpty()) {
                gcpProjectId = projectId
                activeBaseURL = baseURL
                Log.i(TAG, "Antigravity project onboarded via $baseURL")
                return@withContext true
            }
        }

        // 3. Last-resort fallback project (see companion doc).
        Log.w(TAG, "Antigravity project discovery failed on all base URLs — using fallback project")
        gcpProjectId = FALLBACK_PROJECT_ID
        activeBaseURL = "https://cloudcode-pa.googleapis.com"
        true
    }

    /** Shared setup headers for the loadCodeAssist / onboardUser / poll trio. */
    private fun applySetupHeaders(conn: HttpURLConnection, token: String) {
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("X-Client-Name", "antigravity")
        conn.setRequestProperty("X-Client-Version", "1.107.0")
        conn.setRequestProperty("x-goog-api-client", "gl-node/18.18.2 fire/0.8.6 grpc/1.10.x")
        conn.setRequestProperty("Client-Metadata", CLIENT_METADATA)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
    }

    /** POST the given JSON body, returning the response body on 2xx only. */
    private fun postForBody(conn: HttpURLConnection, body: String): String? {
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            val responseCode = conn.responseCode
            if (responseCode in 200..299) conn.inputStream.bufferedReader().readText() else null
        } catch (e: Exception) {
            Log.d(TAG, "setup call failed: ${e.message}")
            null
        }
    }

    private fun loadCodeAssist(token: String, baseURL: String): String? {
        val conn = URL("$baseURL/v1internal:loadCodeAssist").openConnection() as HttpURLConnection
        applySetupHeaders(conn, token)
        val body = postForBody(conn, """{"metadata":$CLIENT_METADATA}""")
        conn.disconnect()
        if (body == null) return null
        return try {
            val json = JSONObject(body)
            // Observed shapes: cloudaicompanionProject ("projects/123" or bare),
            // project / projectId / gcpProjectId — accept all (iOS parity).
            json.optString("cloudaicompanionProject").ifEmpty { null }
                ?: json.optString("project").ifEmpty { null }?.substringAfterLast('/')
                ?: json.optString("projectId").ifEmpty { null }
                ?: json.optString("gcpProjectId").ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun onboardUser(token: String, baseURL: String): String? {
        val conn = URL("$baseURL/v1internal:onboardUser").openConnection() as HttpURLConnection
        applySetupHeaders(conn, token)
        val body = postForBody(conn, """{"tierId":"FREE","metadata":$CLIENT_METADATA}""")
        conn.disconnect()
        if (body == null) return null

        val json = try { JSONObject(body) } catch (_: Exception) { return null }

        // Direct result shapes.
        json.optString("cloudaicompanionProject").ifEmpty { null }?.let { return it }
        json.optString("project").ifEmpty { null }?.let { return it.substringAfterLast('/') }
        json.optString("projectId").ifEmpty { null }?.let { return it }
        json.optJSONObject("response")?.optString("cloudaicompanionProject")?.ifEmpty { null }?.let { return it }

        // Long-running operation: poll v1internal/<name> until done (iOS parity:
        // 24 attempts × 5s).
        val operationName = json.optString("name").ifEmpty { null } ?: return null
        if (!operationName.contains("operations/")) return null
        return pollOperation(token, baseURL, operationName)
    }

    private suspend fun pollOperation(token: String, baseURL: String, operationName: String): String? {
        for (attempt in 1..24) {
            delay(5000)
            try {
                val conn = URL("$baseURL/v1internal/$operationName").openConnection() as HttpURLConnection
                applySetupHeaders(conn, token)
                conn.requestMethod = "GET"
                val responseCode = conn.responseCode
                val body = if (responseCode in 200..299) conn.inputStream.bufferedReader().readText() else null
                conn.disconnect()
                if (body == null) continue
                val json = JSONObject(body)
                if (!json.optBoolean("done", false)) {
                    Log.d(TAG, "pollOperation attempt $attempt/24 pending")
                    continue
                }
                val result = json.optJSONObject("response") ?: return null
                result.optJSONObject("cloudaicompanionProject")?.optString("id")?.ifEmpty { null }?.let { return it }
                result.optString("project").ifEmpty { null }?.let { return it.substringAfterLast('/') }
                result.optString("projectId").ifEmpty { null }?.let { return it }
                return null
            } catch (_: Exception) {
                // keep polling
            }
        }
        Log.w(TAG, "pollOperation timed out after 24 attempts")
        return null
    }
}
