package com.openminis.app.provider.antigravity

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.failOnSilentEmptyCompletion
import com.openminis.app.provider.safeOptString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * LLMProvider implementation for Google Antigravity (Cloud Code Assist).
 *
 * Port of iOS `AntigravityProvider`. Antigravity is Google's agentic platform
 * that replaced the shut-down "Gemini Code Assist for individuals" OAuth
 * surface (June 2026): it routes Gemini / Claude / GPT-OSS models through the
 * daily-cloudcode-pa / cloudcode-pa endpoints using a Gemini-format request
 * envelope with `functionDeclarations` (camelCase) + explicit `toolConfig`,
 * wrapped in an Antigravity-specific outer envelope:
 *
 * ```
 * { model, userAgent: "antigravity", requestType: "agent", project,
 *   requestId: "agent-<uuid>", request: { ...inner, sessionId } }
 * ```
 *
 * Responses unwrap `{"response": {...}}` exactly like the Gemini Cloud Code
 * path. All requests are OAuth-only (Bearer token from
 * [com.openminis.app.auth.AntigravityOAuthManager]); there is no API-key mode.
 */
class AntigravityProvider(
    private val oauthTokenProvider: suspend () -> String?,
    private val gcpProjectProvider: suspend () -> String?,
    private val baseURLProvider: suspend () -> String,
    override var model: LLMModel = LLMModel.gemini25Flash,
) : LLMProvider {
    override val name = "Antigravity"

    /** Stable per-provider session id, carried inside every request envelope. */
    private val sessionId = UUID.randomUUID().toString()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Shared pool — see NetworkMonitor (parity with the other providers).
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .build()

    /** Resolve (token, project) at request time, mirroring GeminiProvider. */
    private suspend fun requestContext(): Pair<String, String> {
        val project = gcpProjectProvider() ?: throw LLMError.InvalidApiKey()
        val token = oauthTokenProvider() ?: throw LLMError.InvalidApiKey()
        return token to project
    }

    /** Wrap the inner Gemini-format body in the Antigravity Cloud Code envelope. */
    private fun wrapEnvelope(inner: JSONObject, project: String): JSONObject =
        JSONObject()
            .put("model", model.id)
            .put("userAgent", "antigravity")
            .put("requestType", "agent")
            .put("project", project)
            .put("requestId", "agent-${UUID.randomUUID()}")
            .put("request", inner.put("sessionId", sessionId))

    private suspend fun buildRequest(
        path: String,
        payload: JSONObject,
        thinkingEnabled: Boolean,
        token: String,
    ): Request {
        val base = baseURLProvider().trimEnd('/')
        val builder = Request.Builder()
            .url("$base/v1internal:$path")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "antigravity/1.107.0 android/aarch64")
            .header("X-Client-Name", "antigravity")
            .header("X-Client-Version", "1.107.0")
            .header("x-goog-api-client", "gl-node/18.18.2 fire/0.8.6 grpc/1.10.x")
            .header("Client-Metadata", """{"ideType":"ANTIGRAVITY","platform":"MACOS","pluginType":"GEMINI"}""")
        if (path.contains("stream")) {
            builder.header("Accept", "text/event-stream")
        } else {
            builder.header("Accept", "application/json")
        }
        // Claude thinking models need this header (either -thinking suffix or
        // user-enabled thinking) — iOS AntigravityProvider parity.
        val id = model.id.lowercase()
        if (id.contains("claude") && (id.contains("thinking") || thinkingEnabled)) {
            builder.header("anthropic-beta", "interleaved-thinking-2025-05-14")
        }
        return builder
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val inner = buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
        val (token, project) = requestContext()
        val payload = wrapEnvelope(inner, project)
        val request = buildRequest("generateContent", payload, thinkingLevel.isEnabled, token)
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw mapHttpError(response.code, responseBody)
        }

        val json = JSONObject(responseBody)
        val innerResponse = json.optJSONObject("response") ?: json
        val text = extractText(innerResponse)
        val finishReason = extractFinishReason(innerResponse)
        val usage = extractUsage(innerResponse)
        LLMResponse(text, finishReason ?: "end_turn", usage, emptyList())
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
    ).failOnSilentEmptyCompletion(name)

    private fun rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val inner = buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
        val (token, project) = try {
            requestContext()
        } catch (e: Throwable) {
            if (e is LLMError) throw e else throw LLMError.Unknown(e)
        }
        val payload = wrapEnvelope(inner, project)
        val request = buildRequest("streamGenerateContent?alt=sse", payload, thinkingLevel.isEnabled, token)

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            response.close()
            throw mapHttpError(response.code, errorBody)
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        try {
            var started = false
            var lastFinishReason: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val payloadLine = l.removePrefix("data: ")

                val json = try { JSONObject(payloadLine) } catch (_: Exception) { continue }
                // Antigravity SSE chunks are wrapped: {"response": {...}} —
                // unwrap so the extractors see the standard shape.
                val innerResponse = json.optJSONObject("response") ?: json

                if (!started) {
                    send(LLMStreamChunk.Started)
                    started = true
                }

                // Separate thought parts from text parts
                val (text, thinking) = extractTextAndThinking(innerResponse)
                if (thinking.isNotEmpty()) {
                    send(LLMStreamChunk.ThinkingDelta(thinking))
                }
                if (text.isNotEmpty()) {
                    send(LLMStreamChunk.Text(text))
                }

                // Extract function calls from streaming response
                val functionCalls = extractFunctionCalls(innerResponse)
                for ((fcName, fcArgs, fcSig) in functionCalls) {
                    val toolId = "antigravity_${System.nanoTime()}"
                    send(LLMStreamChunk.ToolUseStart(toolId, fcName))
                    // thoughtSignature rides on the part, not the functionCall —
                    // gemini-3.x requires it replayed on historical calls.
                    send(LLMStreamChunk.ToolCallComplete(toolId, fcName, fcArgs, thoughtSignature = fcSig))
                }

                extractUsage(innerResponse)?.let { usage ->
                    send(LLMStreamChunk.Usage(usage))
                }

                extractFinishReason(innerResponse)?.let { reason ->
                    lastFinishReason = reason
                }
            }
            send(LLMStreamChunk.Finished(lastFinishReason ?: "end_turn"))
        } catch (e: Exception) {
            cancel("Stream error", mapError(e))
        } finally {
            reader.close()
            response.close()
        }
        channel.close()
        awaitClose()
    }

    // ── Request body (Gemini format + Antigravity thinking contract) ──────

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        val body = JSONObject()

        // Gemini 3.x REQUIRES a thoughtSignature on every historical
        // functionCall part; unsigned calls (old sessions) are downgraded to
        // text summaries together with their paired functionResponse. Mirrors
        // GeminiProvider / iOS convertMessages.
        val requiresSig = model.id.lowercase().contains("gemini-3")
        val unsignedToolCallIds: Set<String> = if (!requiresSig) emptySet() else buildSet {
            for (msg in messages) {
                for (part in msg.contentParts) {
                    if (part is AgentContentPart.ToolUse && part.thoughtSignature.isNullOrEmpty()) {
                        add(part.id)
                    }
                }
            }
        }

        val contents = JSONArray()
        val lastUserIndex = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, msg) in messages.withIndex()) {
            val role = if (msg.role == LLMMessage.Role.USER) "user" else "model"
            val content = JSONObject()
            content.put("role", role)

            val parts = JSONArray()

            if (msg.contentParts.isNotEmpty()) {
                for (part in msg.contentParts) {
                    when (part) {
                        is AgentContentPart.Text -> {
                            // Gemini rejects {"text": ""} with oneof 400; skip empty text parts.
                            if (part.text.isNotEmpty()) {
                                parts.put(JSONObject().put("text", part.text))
                            }
                        }
                        is AgentContentPart.ToolUse -> {
                            if (part.id in unsignedToolCallIds) {
                                val argsDesc = part.input.toString().let {
                                    if (it.length > 500) it.take(500) + "…" else it
                                }
                                parts.put(JSONObject().put("text", "[Called ${part.name} with: $argsDesc]"))
                            } else {
                                parts.put(JSONObject().apply {
                                    if (requiresSig && !part.thoughtSignature.isNullOrEmpty()) {
                                        put("thoughtSignature", part.thoughtSignature)
                                    }
                                    put("functionCall", JSONObject().apply {
                                        put("name", part.name)
                                        put("args", part.input)
                                    })
                                })
                            }
                        }
                        is AgentContentPart.ToolResult -> {
                            if (part.id in unsignedToolCallIds) {
                                val safe = part.content.ifEmpty { " " }.let {
                                    if (it.length > 1000) it.take(1000) + "…" else it
                                }
                                val prefix = if (part.isError) "Error from" else "Result of"
                                parts.put(JSONObject().put("text", "[$prefix ${part.name}: $safe]"))
                            } else {
                                val responseObj = JSONObject()
                                responseObj.put("name", part.name)
                                val responseContent = JSONObject()
                                val safeContent = part.content.ifEmpty { " " }
                                responseContent.put("result", safeContent)
                                if (part.isError) responseContent.put("error", true)
                                responseObj.put("response", responseContent)
                                parts.put(JSONObject().put("functionResponse", responseObj))
                                // read_image bytes ride inlineData right after
                                // the functionResponse (Gemini takes
                                // heterogeneous parts in one turn).
                                val trBytes = part.imageData
                                if (trBytes != null && trBytes.isNotEmpty()) {
                                    val safeBytes = ImageBudget.compressUnderBudget(trBytes)
                                    val safeMime = if (safeBytes === trBytes) {
                                        part.imageMimeType ?: "image/jpeg"
                                    } else "image/jpeg"
                                    parts.put(JSONObject().put("inlineData", JSONObject().apply {
                                        put("mimeType", safeMime)
                                        put("data", Base64.encodeToString(safeBytes, Base64.NO_WRAP))
                                    }))
                                }
                            }
                        }
                        is AgentContentPart.ImageData -> {
                            parts.put(JSONObject().put("inlineData", JSONObject().apply {
                                put("mimeType", part.mimeType)
                                put("data", Base64.encodeToString(part.data, Base64.NO_WRAP))
                            }))
                        }
                    }
                }
            } else {
                // Legacy: plain text with optional images
                if (index == lastUserIndex && imageParts.isNotEmpty()) {
                    for (part in imageParts) {
                        val inlineData = JSONObject()
                        inlineData.put("mimeType", part.mimeType)
                        inlineData.put("data", Base64.encodeToString(part.data, Base64.NO_WRAP))
                        parts.put(JSONObject().put("inlineData", inlineData))
                    }
                }
                val legacyText = msg.content.ifEmpty { " " }
                parts.put(JSONObject().put("text", legacyText))
            }
            // A turn whose only content was an empty .Text would otherwise
            // emit an empty parts[] → 400. Fall back to a placeholder.
            if (parts.length() == 0) {
                parts.put(JSONObject().put("text", "(empty)"))
            }
            content.put("parts", parts)
            contents.put(content)
        }
        body.put("contents", contents)

        if (systemPrompt != null) {
            body.put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", systemPrompt))
            ))
        }

        // Tools — Antigravity wire format uses camelCase functionDeclarations
        // plus an explicit AUTO toolConfig (iOS AntigravityProvider parity;
        // the plain Gemini API's snake_case function_declarations is NOT what
        // this surface expects).
        if (tools.isNotEmpty()) {
            val funcDecls = JSONArray()
            for (tool in tools) {
                funcDecls.put(tool.toGeminiJson())
            }
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", funcDecls)))
            body.put("toolConfig", JSONObject().put(
                "functionCallingConfig", JSONObject().put("mode", "AUTO")
            ))
        }

        val config = JSONObject()
        config.put("maxOutputTokens", maxTokens)
        if (temperature != null) {
            config.put("temperature", temperature)
        }

        thinkingConfigFor(thinkingLevel)?.let { thinkingConfig ->
            config.put("thinkingConfig", thinkingConfig)
        }

        body.put("generationConfig", config)

        return body
    }

    /**
     * Antigravity thinking contract (iOS AntigravityProvider parity — this is
     * NOT the same as the plain Gemini catalog's):
     * - Claude models: no thinkingConfig (Cloud Code rejects it; thinking
     *   models use server-side defaults).
     * - Gemini 3.x: string `thinkingLevel` (flash: minimal, pro: low).
     * - 2.5 Pro: thinkingBudget 128. 2.5 Flash Lite: none. Others: budget 0.
     */
    private fun minimalThinkingConfig(): JSONObject? {
        val id = model.id.lowercase()
        if (id.contains("claude")) return null
        if (id.contains("gemini-3")) {
            return JSONObject().put("thinkingLevel", if (id.contains("flash")) "minimal" else "low")
        }
        if (id.contains("2.5-pro")) return JSONObject().put("thinkingBudget", 128)
        if (id.contains("2.5-flash-lite")) return null
        return JSONObject().put("thinkingBudget", 0)
    }

    /** Thinking config when the user explicitly enables thinking mode. */
    private fun elevatedThinkingConfig(level: ThinkingLevel): JSONObject? {
        val id = model.id.lowercase()
        if (id.contains("claude")) return null
        if (id.contains("gemini-3")) {
            val geminiLevel = when (level) {
                ThinkingLevel.OFF -> "minimal"
                ThinkingLevel.LOW -> "low"
                ThinkingLevel.MEDIUM -> "medium"
                else -> "high"
            }
            return JSONObject().put("thinkingLevel", geminiLevel).put("includeThoughts", true)
        }
        if (id.contains("2.5-pro")) {
            val budget = when (level) {
                ThinkingLevel.OFF -> 128
                ThinkingLevel.LOW -> 2048
                ThinkingLevel.MEDIUM -> 8192
                ThinkingLevel.HIGH -> 16384
                else -> 32768
            }
            return JSONObject().put("thinkingBudget", budget).put("includeThoughts", true)
        }
        if (id.contains("2.5-flash") && !id.contains("lite")) {
            val budget = when (level) {
                ThinkingLevel.OFF -> 0
                ThinkingLevel.LOW -> 1024
                ThinkingLevel.MEDIUM -> 4096
                ThinkingLevel.HIGH -> 8192
                else -> 16384
            }
            return JSONObject().put("thinkingBudget", budget).put("includeThoughts", true)
        }
        return minimalThinkingConfig()
    }

    private fun thinkingConfigFor(level: ThinkingLevel): JSONObject? =
        if (level.isEnabled) elevatedThinkingConfig(level) else minimalThinkingConfig()

    // ── Response extraction ({"response": {...}}-unwrapped shapes) ────────

    /** Separate thought parts (thought=true) from regular text parts. */
    private fun extractTextAndThinking(json: JSONObject): Pair<String, String> {
        val candidates = json.optJSONArray("candidates") ?: return "" to ""
        val first = candidates.optJSONObject(0) ?: return "" to ""
        val content = first.optJSONObject("content") ?: return "" to ""
        val parts = content.optJSONArray("parts") ?: return "" to ""

        val textBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val text = part.safeOptString("text", "")
            if (text.isEmpty()) continue
            if (part.optBoolean("thought", false)) {
                thinkingBuilder.append(text)
            } else {
                textBuilder.append(text)
            }
        }
        return textBuilder.toString() to thinkingBuilder.toString()
    }

    private fun extractText(json: JSONObject): String = extractTextAndThinking(json).first

    /**
     * Returns (name, args, thoughtSignature) per functionCall part. The
     * signature lives as a sibling `thoughtSignature` field on the SAME part
     * object as `functionCall`, per the Gemini wire format.
     */
    private fun extractFunctionCalls(json: JSONObject): List<Triple<String, JSONObject, String?>> {
        val candidates = json.optJSONArray("candidates") ?: return emptyList()
        val first = candidates.optJSONObject(0) ?: return emptyList()
        val content = first.optJSONObject("content") ?: return emptyList()
        val parts = content.optJSONArray("parts") ?: return emptyList()

        val calls = mutableListOf<Triple<String, JSONObject, String?>>()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val fc = part.optJSONObject("functionCall") ?: continue
            val name = fc.safeOptString("name", "")
            val args = fc.optJSONObject("args") ?: JSONObject()
            val sig = part.safeOptString("thoughtSignature", "").ifEmpty { null }
            if (name.isNotEmpty()) calls.add(Triple(name, args, sig))
        }
        return calls
    }

    private fun extractFinishReason(json: JSONObject): String? {
        val candidates = json.optJSONArray("candidates") ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val reason = first.safeOptString("finishReason", "").ifEmpty { return null }
        return when (reason) {
            "STOP" -> "end_turn"
            "MAX_TOKENS" -> "max_tokens"
            else -> reason.lowercase()
        }
    }

    private fun extractUsage(json: JSONObject): LLMUsage? {
        val usage = json.optJSONObject("usageMetadata") ?: return null
        return LLMUsage(
            inputTokens = usage.optInt("promptTokenCount", 0),
            outputTokens = usage.optInt("candidatesTokenCount", 0),
        )
    }

    // ── Error mapping ──────────────────────────────────────────────────────

    private fun mapHttpError(statusCode: Int, body: String): LLMError {
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
        if (statusCode == 429) return LLMError.RateLimited()
        val message = "Antigravity API error $statusCode: ${body.take(200)}"
        val transientCodes = setOf(500, 502, 503, 504, 529)
        if (statusCode in transientCodes) return LLMError.TransientError(message)
        return LLMError.ProviderError(message)
    }

    private fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }
}
