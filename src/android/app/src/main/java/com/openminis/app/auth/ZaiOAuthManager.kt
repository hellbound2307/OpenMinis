package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * [T-android-zai-glm-oauth] Z.ai / ZCode GLM Coding Plan OAuth manager.
 *
 * Wire format (verified against CLIProxyAPI's production Go implementation,
 * PR router-for-me/CLIProxyAPI#3928 — the ZCode CLI protocol, NOT RFC 8628):
 *
 *   1. Client generates a 64-char random-hex poll token.
 *   2. POST {OAUTH_BASE}/oauth/cli/init   body {"provider":"zai"}
 *      header  Authorization: Bearer <poll token>
 *      → envelope {code:0, data:{flow_id, poll_token, authorize_url,
 *        expires_at, poll_interval_sec}}.
 *   3. The user opens authorize_url in a browser and authorizes the CLI.
 *   4. GET {OAUTH_BASE}/oauth/cli/poll/{flow_id}  (Bearer poll token)
 *      → data:{status:"pending"|"ready"|"failed", token, user:{…},
 *        zai:{access_token}}.
 *   5. On "ready": exchange the Z.AI access token for a business token
 *      (POST api.z.ai/api/auth/z/login), resolve the account's default
 *      org/project, find-or-create the "zcode-api-key" coding-plan API key
 *      and copy its secret → final credential "<apiKey>.<secretKey>".
 *
 * The minted key is LONG-LIVED and works against the documented
 * Anthropic-compatible endpoint (https://api.z.ai/api/anthropic) with
 * x-api-key auth. No refresh token exists — if z.ai ever rejects it, the
 * user signs in again (same lifecycle as the official client).
 *
 * No loopback callback server is involved at all: the flow is
 * server-mediated polling, so it is immune to the loopback-port contention
 * that plagues redirect-based OAuth when several apps listen on the same
 * fixed port (see the OpenMinis side-by-side install story).
 */
class ZaiOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "ZaiOAuth"

        /** ZCode CLI OAuth base — serves both z.ai (international) and bigmodel. */
        const val OAUTH_BASE = "https://zcode.z.ai/api/v1"

        /** Identity provider value for Z.AI international (chat.z.ai). */
        const val PROVIDER_ZAI = "zai"

        /** Anthropic-compatible inference endpoint that accepts the minted key. */
        const val ANTHROPIC_API_BASE = "https://api.z.ai/api/anthropic"

        /** Name of the coding-plan API key the flow provisions. Matches the
         *  official ZCode client so a re-login reuses the same key instead of
         *  stacking duplicates in the user's z.ai console. */
        private const val MINT_KEY_NAME = "zcode-api-key"

        private const val MAX_POLL_MS = 10 * 60 * 1000L
        private const val DEFAULT_POLL_INTERVAL_MS = 2_000L
        private const val MAX_CONSECUTIVE_POLL_ERRORS = 5

        /** Recognize a z.ai / ZCode / bigmodel Anthropic-compatible base URL. */
        fun isZaiCompatBaseURL(url: String): Boolean {
            val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
            return host == "api.z.ai" || host.endsWith(".z.ai") ||
                host == "zcode.z.ai" || host.endsWith("bigmodel.cn")
        }

        /**
         * Full sign-in flow: init → browser authorize → poll → mint. Returns
         * the minted coding-plan API key and stores it as the instance api key
         * (mirrors [KimiOAuthManager.login] / [com.openminis.app.auth.GeminiOAuthManager.login]).
         */
        suspend fun login(
            context: Context,
            instanceId: String,
            providerRepository: ProviderRepository,
        ): String {
            val manager = ZaiOAuthManager(context, instanceId)
            val key = manager.performLogin()
            providerRepository.saveApiKey(instanceId, key)
            return key
        }
    }

    // Base-class abstract surface: the standard OAuth redirect machinery is
    // unused — only the token storage helpers, logout and manual-bearer are
    // shared (same posture as KimiOAuthManager).
    override val authURL = OAUTH_BASE
    override val tokenURL = OAUTH_BASE
    override val clientId = "zcode-cli"
    override val clientSecret: String? = null
    override val callbackPort = 0
    override val redirectPath = ""
    override val scopes = ""

    // ── Account metadata (shown in the provider detail screen) ───────────

    var email: String?
        get() = loadOAuthString("email")
        private set(value) { value?.let { saveOAuthString("email", it) } }

    /** The Z.AI OAuth access token the key was provisioned from (re-mint source). */
    private var zaiAccessToken: String?
        get() = loadOAuthString("zai_access_token")
        private set(value) { value?.let { saveOAuthString("zai_access_token", it) } }

    /** The minted long-lived coding-plan credential. */
    private var mintedKey: String?
        get() = loadOAuthString("minted_key")
        private set(value) { value?.let { saveOAuthString("minted_key", it) } }

    /**
     * Valid credential for request time: the minted key never expires, so
     * this is a plain lookup (no refresh machinery). A user-pasted manual
     * bearer still takes precedence, mirroring the other managers.
     */
    override suspend fun validAccessToken(): String? {
        loadManualBearerToken()?.takeIf { it.isNotEmpty() }?.let { return it }
        return mintedKey?.takeIf { it.isNotEmpty() }
    }

    // ── Flow ─────────────────────────────────────────────────────────────

    suspend fun performLogin(): String = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== z.ai GLM OAuth login started (instance=$instanceId) ===")

        // 1. Init
        val pollToken = newPollToken()
        val init = postEnvelope(
            "$OAUTH_BASE/oauth/cli/init",
            authorization = "Bearer $pollToken",
            body = JSONObject().put("provider", PROVIDER_ZAI),
        )
        val flowId = init.optString("flow_id").ifEmpty {
            throw Exception("z.ai login failed: the OAuth service returned no flow id. Try again in a moment.")
        }
        val authorizeUrl = init.optString("authorize_url").ifEmpty {
            throw Exception("z.ai login failed: the OAuth service returned no authorize URL. Try again in a moment.")
        }
        // The server returns the authoritative poll token; fall back to the
        // client-generated one when omitted (official-client parity).
        val effectivePollToken = init.optString("poll_token").ifEmpty { pollToken }
        val intervalMs = (init.optLong("poll_interval_sec", 2) * 1000)
            .coerceIn(DEFAULT_POLL_INTERVAL_MS, 15_000L)
        val expiresAtMs = init.optLong("expires_at", 0) * 1000
        Log.i(TAG, "init ok flow=$flowId interval=${intervalMs}ms expiresAt=$expiresAtMs")

        // 2. Authorize in a Custom Tab (same UX as the Google flow: keeps the
        //    IME / back-stack behavior sane on singleTask MainActivity).
        //    launchUrl is fire-and-forget — polling starts while the browser
        //    is open.
        withContext(Dispatchers.Main) {
            try {
                CustomTabsIntent.Builder().setShowTitle(true).build()
                    .launchUrl(context, Uri.parse(authorizeUrl))
            } catch (e: Exception) {
                Log.w(TAG, "browser launch failed (${e.message}) — user can still paste nothing; flow will time out")
            }
        }

        // 3. Poll until ready / failed / deadline.
        val deadline = buildList {
            add(System.currentTimeMillis() + MAX_POLL_MS)
            if (expiresAtMs > 0) add(expiresAtMs)
        }.min()
        var consecutiveErrors = 0
        var ready: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            try {
                val poll = getEnvelope(
                    "$OAUTH_BASE/oauth/cli/poll/$flowId",
                    authorization = "Bearer $effectivePollToken",
                )
                consecutiveErrors = 0
                when (poll.optString("status")) {
                    "pending", "" -> Unit // keep polling
                    "failed" -> throw Exception("z.ai sign-in was denied or failed in the browser. Start again and approve the request.")
                    "ready" -> { ready = poll; break }
                    else -> throw Exception("z.ai login returned an unknown status '${poll.optString("status")}'.")
                }
            } catch (e: Exception) {
                if (e.message?.startsWith("z.ai sign-in was denied") == true ||
                    e.message?.startsWith("z.ai login returned") == true
                ) throw e
                consecutiveErrors++
                Log.w(TAG, "poll error $consecutiveErrors/$MAX_CONSECUTIVE_POLL_ERRORS: ${e.message}")
                if (consecutiveErrors >= MAX_CONSECUTIVE_POLL_ERRORS) {
                    throw Exception("Lost contact with the z.ai login service while waiting for approval: ${e.message}")
                }
            }
        }
        val readyResult = ready
            ?: throw Exception("z.ai sign-in timed out — no approval arrived within 10 minutes. Start again.")

        // 4. Extract credentials
        val zaiAccess = readyResult.optJSONObject("zai")?.optString("access_token")?.ifEmpty { null }
        val user = readyResult.optJSONObject("user")
        user?.optString("email")?.takeIf { it.isNotEmpty() }?.let { email = it }
        Log.i(TAG, "authorized: user=${user?.optString("email") ?: "?"} zaiAccess=${zaiAccess != null}")

        // 5. Mint the standard coding-plan API key. The poll's own `token` is
        //    ZCode-plan-scoped (captcha-gated endpoint); the minted
        //    "<apiKey>.<secretKey>" works on the documented api.z.ai surface.
        val key = mintApiKey(zaiAccess)
            ?: throw Exception(
                "Signed in to z.ai, but no GLM Coding Plan API key could be provisioned for this account. " +
                    "Check that the account has an active GLM Coding Plan subscription, then sign in again.",
            )

        mintedKey = key
        zaiAccessToken = zaiAccess
        // Token blob for the shared export/import machinery: the minted key
        // is the access credential; no refresh token exists.
        saveOAuthString(
            "tokens",
            JSONObject()
                .put("access_token", key)
                .put("zai_access_token", zaiAccess ?: "")
                .put("last_refresh", System.currentTimeMillis())
                .toString(),
        )
        Log.i(TAG, "=== z.ai GLM OAuth login complete (instance=$instanceId keyLen=${key.length}) ===")
        key
    }

    /**
     * Provision the standard coding-plan API key from the OAuth result:
     * business login → org/project resolution → find-or-create key → copy
     * secret. Mirrors CLIProxyAPI MintAPIKey exactly. Returns null when the
     * account has no organization/project (no active coding plan).
     */
    private fun mintApiKey(zaiAccess: String?): String? {
        // The ZCode-poll token alone cannot reach the business API; the z.ai
        // access token is required for the international flow.
        if (zaiAccess.isNullOrEmpty()) return null
        val bizToken = bizLogin(zaiAccess)
        val auth = "Bearer $bizToken"

        // 1. Org/project resolution
        val info = bizRequest("https://api.z.ai/api/biz/customer/getCustomerInfo", auth, null)
            ?: throw Exception("Could not read the z.ai account profile (getCustomerInfo failed).")
        val orgs = info.optJSONArray("organizations") ?: JSONArray()
        if (orgs.length() == 0) return null
        var org = orgs.optJSONObject(0) ?: return null
        for (i in 0 until orgs.length()) {
            val candidate = orgs.optJSONObject(i) ?: continue
            val projects = candidate.optJSONArray("projects")
            if (projects == null || projects.length() == 0) continue
            val isDefault = candidate.optString("organizationName").contains("默认机构")
            val orgHasProjects = (org.optJSONArray("projects")?.length() ?: 0) > 0
            if (!orgHasProjects || isDefault) {
                org = candidate
                if (isDefault) break
            }
        }
        val projects = org.optJSONArray("projects") ?: return null
        if (projects.length() == 0) return null
        var project = projects.optJSONObject(0)
        for (i in 0 until projects.length()) {
            val p = projects.optJSONObject(i) ?: continue
            if (p.optString("projectName").contains("默认项目")) { project = p; break }
        }
        val orgId = org.optString("organizationId").ifEmpty { return null }
        val projId = project?.optString("projectId")?.ifEmpty { null } ?: return null

        // 2. Find-or-create the coding-plan key
        val keysURL = "https://api.z.ai/api/biz/v1/organization/$orgId/projects/$projId/api_keys"
        var apiKey: String? = null
        val keyList = bizRequest(keysURL, auth, null)
        val existing = firstArray(keyList)
        if (existing != null) {
            for (i in 0 until existing.length()) {
                val k = existing.optJSONObject(i) ?: continue
                if (k.optString("name") == MINT_KEY_NAME) {
                    apiKey = k.optString("apiKey").ifEmpty { null }
                    break
                }
            }
        }
        if (apiKey == null) {
            val created = bizRequest(keysURL, auth, JSONObject().put("name", MINT_KEY_NAME))
                ?: throw Exception("Creating the GLM coding-plan API key on z.ai failed.")
            apiKey = created.optString("apiKey").ifEmpty { null }
                ?: firstArray(created)?.optJSONObject(0)?.optString("apiKey")?.ifEmpty { null }
                ?: throw Exception("The z.ai key-creation response carried no apiKey field.")
        }

        // 3. Copy the secret → "<apiKey>.<secretKey>"
        var secretKey: String? = null
        bizRequest("$keysURL/copy/${Uri.encode(apiKey!!)}", auth, null)?.let { cp ->
            secretKey = cp.optString("secretKey").ifEmpty { null }
                ?: cp.optJSONObject("data")?.optString("secretKey")?.ifEmpty { null }
        }
        return if (!secretKey.isNullOrEmpty()) "$apiKey.$secretKey" else apiKey
    }

    /** First JSON array found among bare / "data" / "list" envelope shapes. */
    private fun firstArray(json: JSONObject?): JSONArray? {
        if (json == null) return null
        json.optJSONArray("data")?.let { return it }
        json.optJSONArray("list")?.let { return it }
        return null
    }

    /** Exchange the Z.AI OAuth access token for a business API token. */
    private fun bizLogin(accessToken: String): String? {
        val body = JSONObject().put("token", accessToken)
        val data = bizRequest("https://api.z.ai/api/auth/z/login", "", body)
            ?: throw Exception("z.ai business login failed — could not exchange the sign-in token.")
        val out = data.optString("access_token").ifEmpty { null }
            ?: throw Exception("z.ai business login returned no access token.")
        return out
    }

    /** Business API call with the {code,msg,data} envelope (code 0/200 = OK). */
    private fun bizRequest(endpoint: String, authorization: String, body: JSONObject?): JSONObject? {
        val builder = Request.Builder().url(endpoint)
            .header("Accept", "application/json")
        if (authorization.isNotEmpty()) builder.header("Authorization", authorization)
        if (body != null) {
            builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        }
        val response = OAuthManager.httpClient.newCall(builder.build()).execute()
        val text = response.body?.string() ?: ""
        val code = response.code
        response.close()
        if (code !in 200..299) {
            Log.w(TAG, "biz $endpoint HTTP $code: ${OAuthManager.sanitizeBody(text)}")
            throw Exception("z.ai request failed (HTTP $code): ${text.take(200)}")
        }
        val json = try { JSONObject(text) } catch (_: Exception) { return null }
        val bizCode = json.optInt("code", 0)
        if (bizCode != 0 && bizCode != 200) {
            val msg = json.optString("msg").ifEmpty { "business error $bizCode" }
            Log.w(TAG, "biz $endpoint error $bizCode: $msg")
            throw Exception("z.ai request failed: $msg")
        }
        return json.optJSONObject("data") ?: JSONObject()
    }

    /** OAuth service call with the {code,msg,data} envelope (code 0 = OK). */
    private fun postEnvelope(url: String, authorization: String, body: JSONObject): JSONObject =
        envelopeCall(
            Request.Builder()
                .url(url)
                .header("Authorization", authorization)
                .post(body.toString().toRequestBody("application/json".toMediaType())),
            url,
        )

    private fun getEnvelope(url: String, authorization: String): JSONObject =
        envelopeCall(Request.Builder().url(url).header("Authorization", authorization), url)

    private fun envelopeCall(builder: Request.Builder, url: String): JSONObject {
        val request = builder.header("Accept", "application/json").build()
        val response = OAuthManager.httpClient.newCall(request).execute()
        val text = response.body?.string() ?: ""
        val httpCode = response.code
        response.close()
        if (httpCode !in 200..299) {
            throw Exception("z.ai service returned HTTP $httpCode for ${url.substringBefore('?')}")
        }
        val json = try { JSONObject(text) } catch (_: Exception) {
            throw Exception("z.ai service returned an unreadable response.")
        }
        val code = json.optInt("code", 0)
        if (code != 0 && code != 200) {
            val msg = json.optString("msg").ifEmpty { "business error $code" }
            throw Exception("z.ai login failed: $msg")
        }
        return json.optJSONObject("data") ?: JSONObject()
    }

    private fun newPollToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
