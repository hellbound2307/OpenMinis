package com.openminis.app.provider.antigravity

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Antigravity models API — mirrors iOS `AntigravityModelsAPI`.
 *
 * Antigravity exposes its model catalog via a POST to
 * `<baseURL>/v1internal:fetchAvailableModels`. The response has shown up in
 * two shapes across versions:
 *   - **Dict**: `{"models": {"gemini-3-pro-high": {"displayName": ...}, ...}}`
 *   - **Array**: `{"models": [{"name": "gemini-3-pro-high", "displayName": ...}, ...]}`
 *
 * Both are accepted. On total failure across all candidate base URLs (and
 * empty cache) the caller is returned an empty list — provider wiring layer
 * should fall back to a curated list if one gets added to `LLMModel`.
 *
 * **Scaffolding only**: there's no Antigravity `ProviderType` in Android yet,
 * so this API has no runtime consumer. It lands here so the OAuth + provider
 * code that will wire it later has a stable surface to call.
 */
object AntigravityModelsApi {
    private const val USER_AGENT = "antigravity/1.107.0 android/aarch64"
    private val client = OkHttpClient()
    private val cache = ProviderModelsCache("antigravity")

    /**
     * Fetch Antigravity's model list from [baseURL]. Returns empty list on
     * hard failure. When [context] is provided, results are cached for 7
     * days at `cacheDir/models-cache/antigravity/<sha256>.json`.
     */
    suspend fun fetchModels(
        accessToken: String,
        baseURL: String,
        context: Context? = null,
        forceRefresh: Boolean = false,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val cacheKey = baseURL + "|" + accessToken
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val request = Request.Builder()
            .url("${baseURL.trimEnd('/')}/v1internal:fetchAvailableModels")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", USER_AGENT)
            .header("X-Client-Name", "antigravity")
            .header("X-Client-Version", "1.107.0")
            .header("Accept", "application/json")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        val body = response.body?.string() ?: run {
            response.close()
            return@withContext emptyList()
        }

        if (!response.isSuccessful) {
            if (context != null && (response.code == 401 || response.code == 403)) {
                cache.invalidate(context, cacheKey)
            }
            response.close()
            return@withContext emptyList()
        }
        response.close()

        val models = try {
            parseModels(JSONObject(body))
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        if (models.isEmpty()) return@withContext emptyList()
        val enriched = ModelsDevApi.enrichModels(models)
        if (context != null) cache.save(context, cacheKey, enriched)
        enriched
    }

    /**
     * Curated stand-in catalog used when the live
     * `v1internal:fetchAvailableModels` call fails entirely, so a freshly
     * signed-in instance is never model-less. Ids mirror the catalog the
     * opencode-antigravity-auth tooling observes on this surface; the live
     * fetch (or a manual Refresh) replaces them with authoritative entries.
     */
    fun fallbackModels(): List<LLMModel> = listOf(
        "gemini-3-pro-high",
        "gemini-3-pro-low",
        "gemini-3-flash",
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "claude-sonnet-4-5",
        "claude-opus-4-6-thinking",
        "gpt-oss-120b-medium",
    ).map { id ->
        LLMModel(
            id = id,
            displayName = id,
            provider = "Antigravity",
        )
    }

    private fun parseModels(json: JSONObject): List<LLMModel> {
        val modelsField = json.opt("models") ?: return emptyList()
        val out = mutableListOf<LLMModel>()

        when (modelsField) {
            is JSONObject -> {
                // Dict format: key = model id, value = metadata object.
                val keys = modelsField.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val meta = modelsField.optJSONObject(id)
                    val displayName = meta?.optString("displayName", id) ?: id
                    out.add(LLMModel(id = id, displayName = displayName, provider = "Antigravity"))
                }
            }
            is JSONArray -> {
                // Array format: each entry has `name` (id) + `displayName`.
                for (i in 0 until modelsField.length()) {
                    val obj = modelsField.optJSONObject(i) ?: continue
                    val id = obj.optString("name").ifEmpty { obj.optString("id") }
                    if (id.isEmpty()) continue
                    val displayName = obj.optString("displayName", id)
                    out.add(LLMModel(id = id, displayName = displayName, provider = "Antigravity"))
                }
            }
        }
        return out
    }
}
