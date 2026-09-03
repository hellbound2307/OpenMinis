package com.openminis.app.telegram

import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [T-android-telegram-remote] Minimal Telegram Bot API client for the
 * remote-agent service.
 *
 * Deliberately separate from [com.openminis.app.backup.remote.TelegramClient]
 * (which is shaped for the backup flow: upload/download of .minisbak parts).
 * This one only needs the long-poll ingress (getUpdates) and tiny outbound
 * messages (sendMessage / sendChatAction), plus on-demand media download for
 * inbound photos/documents. It uses the same `bot<token>/` REST prefix, so it
 * can drive the SAME bot the user already uses for backup — polling and
 * sending coexist fine with backup's uploads.
 */
internal object TelegramRemoteClient {

    private const val API_BASE = "https://api.telegram.org"
    private const val MAX_TEXT_CHARS = 4096

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(70, TimeUnit.SECONDS) // covers the long-poll window
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Thrown for HTTP errors with a human-friendly message. */
    class RemoteException(message: String, val canRetry: Boolean = true) : Exception(message)

    fun normalizeBotToken(raw: String): String =
        com.openminis.app.backup.remote.TelegramClient.normalizeBotToken(raw)

    /**
     * Long-poll getUpdates. Returns a list of update objects whose `message`
     * may be null (non-message updates are skipped). Blocks up to [timeoutSec]
     * on Telegram's side when there is nothing new.
     */
    fun getUpdates(token: String, offset: Long, timeoutSec: Int = 25): List<JSONObject> {
        val url = "$API_BASE/bot$token/getUpdates" +
            "?timeout=$timeoutSec&offset=$offset" +
            "&allowed_updates=" + java.net.URLEncoder.encode("""["message"]""", "UTF-8")
        val body = httpGet(url)
        val updates = body.optJSONArray("result") ?: JSONArray()
        val out = ArrayList<JSONObject>(updates.length())
        for (i in 0 until updates.length()) {
            val update = updates.optJSONObject(i) ?: continue
            if (!update.has("message")) continue
            out.add(update)
        }
        return out
    }

    fun sendChatAction(token: String, chatId: String, action: String = "typing") {
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("action", action)
        httpPost("$API_BASE/bot$token/sendChatAction", payload)
    }

    /**
     * Send a text reply, splitting into <=4096-char messages (Telegram hard
     * cap). [replyTo] is the update/message id to reply to when available.
     * Returns count of messages actually sent (0 on failure is not raised —
     * used for fire-and-forget notes).
     */
    fun sendText(token: String, chatId: String, text: String, replyTo: Int? = null): Int {
        if (text.isBlank()) return 0
        val chunks = splitText(text)
        var sent = 0
        for ((i, chunk) in chunks.withIndex()) {
            val payload = JSONObject()
                .put("chat_id", chatId)
                .put("text", chunk)
            if (replyTo != null && i == 0) payload.put("reply_to_message_id", replyTo)
            httpPost("$API_BASE/bot$token/sendMessage", payload)
            sent++
        }
        return sent
    }

    /** Inbound photo/document file_id → local cache file. Returns null on failure. */
    fun downloadFile(token: String, fileId: String, dest: File): Boolean {
        return try {
            val path = resolveFilePath(token, fileId) ?: return false
            val req = Request.Builder()
                .url("$API_BASE/file/bot$token/$path")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                dest.parentFile?.mkdirs()
                resp.body?.byteStream()?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.length() > 0
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveFilePath(token: String, fileId: String): String? {
        val body = httpGet("$API_BASE/bot$token/getFile?file_id=${java.net.URLEncoder.encode(fileId, "UTF-8")}")
        return body.optJSONObject("result")?.optString("file_path", null)?.takeIf { it.isNotBlank() }
    }

    /** Best single media attachment from an inbound message, if any. */
    fun mediaFromMessage(message: JSONObject): MediaRef? {
        // Photo: a list of PhotoSize ordered small→large; pick the last.
        message.optJSONArray("photo")?.let { arr ->
            val last = arr.optJSONObject(arr.length() - 1)
            val id = last?.optString("file_id", null)
            if (id != null) return MediaRef(id, last.optString("mime_type", "image/jpeg"), isImage = true)
            return null
        }
        // Document / video / audio / voice: single file_id object.
        message.optJSONObject("document")?.let {
            val id = it.optString("file_id", null)
            if (id != null) {
                val name = it.optString("file_name", "").takeIf { n -> n.isNotBlank() }
                    ?: "file"
                return MediaRef(id, it.optString("mime_type", "application/octet-stream"), isImage = false, name = name)
            }
            return null
        }
        message.optJSONObject("video")?.let {
            val id = it.optString("file_id", null)
            if (id != null) return MediaRef(id, "video/mp4", isImage = false, name = "video.mp4")
            return null
        }
        message.optJSONObject("audio")?.let {
            val id = it.optString("file_id", null)
            if (id != null) return MediaRef(id, "audio/mpeg", isImage = false, name = "audio.mp3")
            return null
        }
        message.optJSONObject("voice")?.let {
            val id = it.optString("file_id", null)
            if (id != null) return MediaRef(id, "audio/ogg", isImage = false, name = "voice.ogg")
            return null
        }
        return null
    }

    data class MediaRef(
        val fileId: String,
        val mimeType: String,
        val isImage: Boolean,
        val name: String = "",
    )

    private fun splitText(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= MAX_TEXT_CHARS) return listOf(trimmed)
        val out = ArrayList<String>()
        var start = 0
        while (start < trimmed.length) {
            var end = (start + MAX_TEXT_CHARS).coerceAtMost(trimmed.length)
            if (end < trimmed.length) {
                // Break at the last newline within the window to keep code blocks sane.
                val nl = trimmed.lastIndexOf('\n', end - 1)
                if (nl > start + MAX_TEXT_CHARS / 2) end = nl + 1
            }
            out.add(trimmed.substring(start, end).trim())
            start = end
        }
        return out
    }

    private fun httpGet(url: String): JSONObject {
        val req = Request.Builder().url(url).get().build()
        return client.newCall(req).execute().use { resp ->
            val bodyText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val desc = parseError(bodyText) ?: "HTTP ${resp.code}"
                throw RemoteException(desc, canRetry = resp.code == 429 || resp.code >= 500)
            }
            parseJson(bodyText)
        }
    }

    private fun httpPost(url: String, payload: JSONObject): JSONObject {
        val body = payload.toString().toRequestBody(MEDIA_JSON)
        val req = Request.Builder().url(url).post(body)
            .header("Content-Type", "application/json")
            .build()
        return client.newCall(req).execute().use { resp ->
            val bodyText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val desc = parseError(bodyText) ?: "HTTP ${resp.code}"
                throw RemoteException(desc, canRetry = resp.code == 429 || resp.code >= 500)
            }
            parseJson(bodyText)
        }
    }

    private fun parseJson(text: String): JSONObject {
        val obj = JSONObject(text)
        if (obj.optBoolean("ok", false) == false) {
            val desc = obj.optJSONObject("description")?.toString()
                ?: obj.optString("description", "unknown error")
            throw RemoteException(desc)
        }
        return obj
    }

    private fun parseError(text: String): String? {
        return runCatching {
            val obj = JSONObject(text)
            val desc = obj.optString("description", null)
            val retry = obj.optJSONObject("parameters")?.optInt("retry_after", 0)
            if (desc != null) desc + (if (retry != null && retry > 0) " (retry in ${retry}s)" else "") else null
        }.getOrNull()
    }

    private val MEDIA_JSON = "application/json; charset=utf-8".toMediaType()
}
