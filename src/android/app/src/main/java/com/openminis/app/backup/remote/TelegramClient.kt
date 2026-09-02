package com.openminis.app.backup.remote

import android.content.Context
import com.openminis.app.logging.AppLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Telegram as a remote backup destination.
 *
 * ## Why Telegram
 *
 * A Telegram chat is a free, zero-infrastructure off-device store that survives
 * the three loss modes the app's local backups do not: app deletion, a lost
 * phone, and a factory reset. The user supplies their OWN bot (two minutes in
 * @BotFather) so the data lives in their Telegram account, under their control
 * — the app never proxies anything through a Minis server, and there is no
 * server.
 *
 * ## How it fits the transport
 *
 * Telegram is not an rclone backend, so this class parallels
 * [RcloneChunkedUpload] rather than extending it: same call shapes, same
 * [RcloneChunkedUpload.Progress] / [RcloneChunkedUpload.CancelFlag] /
 * [RcloneChunkedUpload.RemotePackage] types, same exception taxonomy, so
 * [com.openminis.app.ui.settings.backup.BackupViewModel] can branch on
 * `remote.backend` without any UI type changes. Remotes are stored in the same
 * [RcloneRemoteStore] list with `backend = "telegram"`; the bot token rides in
 * the encrypted secret store (`bot_token` key) and the chat id in plain params.
 *
 * ## Wire design
 *
 * The Bot API caps uploads at 50 MB and — critically — `getFile` DOWNLOADS at
 * 20 MB. A restore on a new device can only fetch files it can download, so
 * packages are split into [PART_SIZE] (16 MB) parts: one `.minisbak` becomes
 * `sendDocument` parts whose `file_id`s are recorded in a small index document
 * (`minis-backup-index.json`). The index is re-uploaded and RE-PINNED after
 * every backup; the pinned message is the discovery anchor that makes listing
 * and restore work on a device that has never seen this bot before (Bot API
 * has no "list chat messages", but getChat returns the pinned message).
 *
 * Confidentiality is unchanged from any other destination: the package written
 * here is the ALREADY-ENCRYPTED `.minisbak` (BackupCrypto, AES-256-GCM) when
 * the user set a passphrase. Telegram sees ciphertext. Still, the bot token is
 * stored only in EncryptedSharedPreferences and never logged.
 */
class TelegramClient(@Suppress("unused") private val context: Context) {

    companion object {
        private const val TAG = "TelegramBackup"
        private const val API_BASE = "https://api.telegram.org"

        /**
         * Chunk size for part uploads. 16 MB sits safely under the 20 MB
         * `getFile` download ceiling (the restore path's real limit) with
         * margin for Telegram's byte counting; uploads via sendDocument may
         * go up to 50 MB, but an upload-only size would strand the part on a
         * restore.
         */
        private const val PART_SIZE = 16L * 1024 * 1024

        /** Index document name — matched against the pinned message. */
        const val INDEX_NAME = "minis-backup-index.json"

        /** Politeness gap between part uploads (bot ≈ 1 msg/s per chat). */
        private const val PART_GAP_MS = 1_100L

        private val JSON_MEDIA = "application/json".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }

    class TelegramException(message: String) : Exception(message)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    // ── Low-level Bot API ────────────────────────────────────────────────

    /** POST a JSON method call. Returns the `result` object, or throws. */
    private fun api(token: String, method: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("$API_BASE/bot$token/$method")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        val response = http.newCall(request).execute()
        val text = response.body?.string() ?: ""
        val code = response.code
        response.close()
        if (code == 429) {
            // Flood control: honour retry_after once, then let the caller's
            // own retry policy take over. Parts already sleep PART_GAP_MS, so
            // this only fires on unusually busy chats.
            val retryAfter = runCatching { JSONObject(text).optJSONObject("parameters")?.optInt("retry_after") }.getOrNull()
            if (retryAfter != null && retryAfter in 1..60) {
                Thread.sleep((retryAfter + 1) * 1000L)
                return api(token, method, body)
            }
        }
        val json = try { JSONObject(text) } catch (_: Exception) { null }
        if (code !in 200..299 || json?.optBoolean("ok") != true) {
            val desc = json?.optString("description")?.takeIf { it.isNotEmpty() } ?: "HTTP $code"
            throw TelegramException("Telegram $method failed: $desc")
        }
        return json.optJSONObject("result") ?: JSONObject()
    }

    /** Multipart sendDocument of [file]. Returns (file_id, message_id). */
    private fun sendDocument(token: String, chatId: String, file: File, caption: String): Pair<String, Int> {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", caption)
            .addFormDataPart(
                "document", file.name,
                file.asRequestBody(OCTET_STREAM),
            )
            .build()
        val request = Request.Builder()
            .url("$API_BASE/bot$token/sendDocument")
            .post(requestBody)
            .build()
        val response = http.newCall(request).execute()
        val text = response.body?.string() ?: ""
        val code = response.code
        response.close()
        if (code == 429) {
            val retryAfter = runCatching { JSONObject(text).optJSONObject("parameters")?.optInt("retry_after") }.getOrNull()
            if (retryAfter != null && retryAfter in 1..60) {
                Thread.sleep((retryAfter + 1) * 1000L)
                return sendDocument(token, chatId, file, caption)
            }
        }
        val result = try { JSONObject(text).optJSONObject("result") } catch (_: Exception) { null }
        if (code !in 200..299 || result == null) {
            val desc = runCatching { JSONObject(text).optString("description") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "HTTP $code"
            throw TelegramException("Telegram upload failed: $desc")
        }
        val doc = result.optJSONObject("document")
        val fileId = doc?.optString("file_id")?.takeIf { it.isNotEmpty() }
            ?: throw TelegramException("Telegram upload returned no file id.")
        return fileId to result.optInt("message_id", -1)
    }

    /** Resolve a file_id to bytes streamed into [sink]. Returns bytes written. */
    private fun downloadById(token: String, fileId: String, sink: java.io.OutputStream): Long {
        val meta = api(token, "getFile", JSONObject().put("file_id", fileId))
        val path = meta.optString("file_path").takeIf { it.isNotEmpty() }
            ?: throw TelegramException("Telegram returned no file path.")
        val request = Request.Builder().url("$API_BASE/file/bot$token/$path").build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw TelegramException("Telegram download failed: HTTP $code")
        }
        var written = 0L
        response.body?.byteStream()?.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                written += n
            }
        } ?: throw TelegramException("Telegram download returned an empty body.")
        response.close()
        return written
    }

    // ── Index (pinned-message anchored) ──────────────────────────────────

    data class IndexEntry(
        val name: String,
        val size: Long,
        val sha256: String,
        val createdAt: Long,
        val parts: List<String>,
        val messageIds: List<Int> = emptyList(),
    )

    /** Fetch the pinned index, or null when none is pinned yet. */
    private fun loadIndex(token: String, chatId: String): Pair<Int, MutableList<IndexEntry>>? {
        val chat = api(token, "getChat", JSONObject().put("chat_id", chatId))
        val pinned = chat.optJSONObject("pinned_message") ?: return null
        val doc = pinned.optJSONObject("document") ?: return null
        if (doc.optString("file_name") != INDEX_NAME) return null
        val fileId = doc.optString("file_id").takeIf { it.isNotEmpty() } ?: return null
        val messageId = pinned.optInt("message_id", -1)
        val sink = java.io.ByteArrayOutputStream()
        downloadById(token, fileId, sink)
        val entries = mutableListOf<IndexEntry>()
        val parsed = runCatching { JSONObject(sink.toString("UTF-8")) }.getOrNull()
            ?: throw TelegramException("The Telegram backup index is unreadable.")
        val arr = parsed.optJSONArray("backups") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching {
                entries.add(
                    IndexEntry(
                        name = o.optString("name"),
                        size = o.optLong("size"),
                        sha256 = o.optString("sha256"),
                        createdAt = o.optLong("created_at"),
                        parts = o.optJSONArray("parts")?.let { ja ->
                            (0 until ja.length()).mapNotNull { ja.optString(it).takeIf { s -> s.isNotEmpty() } }
                        } ?: emptyList(),
                        messageIds = o.optJSONArray("message_ids")?.let { ja ->
                            (0 until ja.length()).map { ja.optInt(it) }.filter { it > 0 }
                        } ?: emptyList(),
                    ),
                )
            }
        }
        return messageId to entries
    }

    private fun indexJson(entries: List<IndexEntry>): JSONObject =
        JSONObject().put(
            "backups",
            JSONArray().apply {
                entries.forEach { e ->
                    put(
                        JSONObject()
                            .put("name", e.name)
                            .put("size", e.size)
                            .put("sha256", e.sha256)
                            .put("created_at", e.createdAt)
                            .put("parts", JSONArray(e.parts))
                            .put("message_ids", JSONArray(e.messageIds)),
                    )
                }
            },
        )

    private fun saveIndex(token: String, chatId: String, entries: List<IndexEntry>, previousMessageId: Int?) {
        val tmp = File.createTempFile("minis-index", ".json")
        try {
            tmp.writeText(indexJson(entries).toString())
            // Unpin ONLY our own previous index message, and only when it is
            // still the one we recorded. unpinAllChatMessages would silently
            // drop a message the USER pinned in that chat — losing their pin
            // is exactly the kind of side effect a backup must not have.
            if (previousMessageId != null && previousMessageId > 0) {
                runCatching {
                    api(
                        token, "unpinChatMessage",
                        JSONObject().put("chat_id", chatId).put("message_id", previousMessageId),
                    )
                }
            }
            val (_, messageId) = sendDocument(token, chatId, tmp, "minis backup index")
            runCatching {
                api(
                    token, "pinChatMessage",
                    JSONObject()
                        .put("chat_id", chatId)
                        .put("message_id", messageId)
                        .put("disable_notification", true),
                )
            }.onFailure {
                AppLogger.warning(TAG, "[Telegram] pin failed (${it.message}) — index still uploaded")
            }
        } finally {
            tmp.delete()
        }
    }

    // ── Public API (mirrors RcloneChunkedUpload) ─────────────────────────

    /**
     * Validate a bot token + chat id before saving the destination: getMe
     * proves the token, getChat proves the bot can actually SEE the chat —
     * the missing-invite failure is otherwise invisible until the first
     * backup silently fails.
     */
    fun verify(token: String, chatId: String) {
        val me = api(token, "getMe", JSONObject())
        val botName = me.optString("username").ifEmpty { "?" }
        val chat = api(token, "getChat", JSONObject().put("chat_id", chatId))
        val title = chat.optString("title").ifEmpty { chat.optString("first_name").ifEmpty { chatId } }
        AppLogger.info(TAG, "[Telegram] verified bot=@$botName chat=$title")
    }

    /**
     * Upload [packageFile] to the chat behind [remote], split into
     * [PART_SIZE] parts, then update + re-pin the index. Blocking; call off
     * the main thread.
     */
    fun upload(
        packageFile: File,
        remote: RcloneRemoteStore.Remote,
        backupId: String = "",
        isCancelled: () -> Boolean = { false },
        onProgress: ((RcloneChunkedUpload.Progress) -> Unit)? = null,
    ) {
        val token = secretFor(remote) ?: throw RcloneChunkedUpload.UploadException("Telegram bot token is missing for '${remote.name}'.")
        val chatId = remote.params["chat_id"]?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw RcloneChunkedUpload.UploadException("Telegram chat ID is missing for '${remote.name}'.")
        if (!packageFile.exists()) throw RcloneChunkedUpload.UploadException("Couldn't read the backup file.")

        // Whole-file digest BEFORE any network I/O: if the read fails, fail
        // before creating remote garbage.
        val digest = MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(packageFile).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }

        val total = packageFile.length()
        val partCount = ((total + PART_SIZE - 1) / PART_SIZE).toInt().coerceAtLeast(1)
        val parts = mutableListOf<String>()
        val messageIds = mutableListOf<Int>()

        val startNanos = System.nanoTime()
        var sentBytes = 0L
        val chunk = File(packageFile.parentFile, "${packageFile.name}.tgpart")
        try {
            RandomAccessFile(packageFile, "r").use { raf ->
                for (index in 0 until partCount) {
                    if (isCancelled()) throw RcloneChunkedUpload.CancelledException()
                    val offset = index.toLong() * PART_SIZE
                    val len = minOf(PART_SIZE, total - offset).toInt()
                    // Slice into a scratch file so sendDocument can hand
                    // Telegram a real File (streamed multipart) without
                    // holding a 16 MB heap copy.
                    RandomAccessFile(chunk, "rw").use { out ->
                        raf.seek(offset)
                        val buf = ByteArray(64 * 1024)
                        var remaining = len
                        while (remaining > 0) {
                            val n = raf.read(buf, 0, minOf(buf.size, remaining))
                            if (n < 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    val caption = buildString {
                        append("minis backup ").append(packageFile.name)
                        append(" part ").append(index + 1).append('/').append(partCount)
                        if (backupId.isNotEmpty()) append(" id=").append(backupId)
                    }
                    val (fileId, messageId) = sendDocument(token, chatId, chunk, caption)
                    parts.add(fileId)
                    if (messageId > 0) messageIds.add(messageId)
                    sentBytes += len
                    onProgress?.invoke(
                        RcloneChunkedUpload.Progress(
                            bytesSent = sentBytes.coerceAtMost(total),
                            totalBytes = total,
                            bytesPerSecond = run {
                                val secs = (System.nanoTime() - startNanos) / 1e9
                                if (secs > 0.5) sentBytes / secs else 0.0
                            },
                        ),
                    )
                    chunk.delete()
                    if (index < partCount - 1) Thread.sleep(PART_GAP_MS)
                }
            }
        } finally {
            chunk.delete()
        }

        // Merge into the pinned index: replace any entry with the same name
        // (a re-run of the same package) and append otherwise.
        val existing = loadIndex(token, chatId)
        val entries = existing?.second ?: mutableListOf()
        entries.removeAll { it.name == packageFile.name }
        entries.add(
            IndexEntry(
                name = packageFile.name,
                size = total,
                sha256 = sha256,
                createdAt = System.currentTimeMillis(),
                parts = parts,
                messageIds = messageIds,
            ),
        )
        // existing?.first = the previous index message we pinned (or null when
        // the chat had no Minis index pinned yet — or had an unrelated pin we
        // must not touch).
        saveIndex(token, chatId, entries, existing?.first)
        AppLogger.info(TAG, "[Telegram] uploaded '${packageFile.name}' ($total B, $partCount part(s)) to '${remote.name}'")
    }

    /** List `.minisbak` packages recorded in the pinned index. */
    fun listPackages(remote: RcloneRemoteStore.Remote): List<RcloneChunkedUpload.RemotePackage> {
        val token = secretFor(remote) ?: throw RcloneChunkedUpload.UploadException("Telegram bot token is missing for '${remote.name}'.")
        val chatId = remote.params["chat_id"]?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw RcloneChunkedUpload.UploadException("Telegram chat ID is missing for '${remote.name}'.")
        val entries = loadIndex(token, chatId)?.second ?: return emptyList()
        return entries.map { e ->
            RcloneChunkedUpload.RemotePackage(
                key = e.name,
                displayName = e.name,
                size = e.size,
                modified = e.createdAt,
                partCount = e.parts.size,
            )
        }
    }

    /**
     * Reassemble [pkg] from its indexed parts into [destination], verifying
     * the whole-file SHA-256 recorded at upload time.
     */
    fun download(
        pkg: RcloneChunkedUpload.RemotePackage,
        remote: RcloneRemoteStore.Remote,
        destination: File,
        cancelFlag: RcloneChunkedUpload.CancelFlag? = null,
        onProgress: ((RcloneChunkedUpload.Progress) -> Unit)? = null,
    ) {
        val token = secretFor(remote) ?: throw RcloneChunkedUpload.UploadException("Telegram bot token is missing for '${remote.name}'.")
        val chatId = remote.params["chat_id"]?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw RcloneChunkedUpload.UploadException("Telegram chat ID is missing for '${remote.name}'.")

        val entries = loadIndex(token, chatId)?.second
            ?: throw RcloneChunkedUpload.UploadException("No Telegram backup index found in that chat.")
        val entry = entries.firstOrNull { it.name == pkg.key }
            ?: throw RcloneChunkedUpload.UploadException("That backup is no longer in the Telegram index.")
        if (entry.parts.isEmpty()) throw RcloneChunkedUpload.UploadException("That backup has no downloadable parts.")

        val digest = MessageDigest.getInstance("SHA-256")
        val startNanos = System.nanoTime()
        var doneBytes = 0L
        destination.outputStream().use { out ->
            val tee = object : java.io.OutputStream() {
                override fun write(b: Int) {
                    out.write(b)
                    digest.update(b)
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                    digest.update(b, off, len)
                }
            }
            for (fileId in entry.parts) {
                if (cancelFlag?.isCancelled() == true) throw RcloneChunkedUpload.CancelledException()
                doneBytes += downloadById(token, fileId, tee)
                val secs = (System.nanoTime() - startNanos) / 1e9
                onProgress?.invoke(
                    RcloneChunkedUpload.Progress(
                        bytesSent = doneBytes.coerceAtMost(entry.size),
                        totalBytes = entry.size,
                        bytesPerSecond = if (secs > 0.5) doneBytes / secs else 0.0,
                    ),
                )
            }
        }
        if (entry.size > 0 && doneBytes != entry.size) {
            destination.delete()
            throw RcloneChunkedUpload.UploadException(
                "Downloaded size ($doneBytes B) does not match the recorded size (${entry.size} B).",
            )
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (entry.sha256.isNotEmpty() && actual != entry.sha256) {
            destination.delete()
            throw RcloneChunkedUpload.UploadException(
                "Integrity check failed after download — the package was corrupted in transit.",
            )
        }
    }

    /** Remove [packageName] from the index (and best-effort delete its parts). */
    fun deletePackage(remote: RcloneRemoteStore.Remote, packageName: String) {
        val token = secretFor(remote) ?: throw RcloneChunkedUpload.UploadException("Telegram bot token is missing for '${remote.name}'.")
        val chatId = remote.params["chat_id"]?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw RcloneChunkedUpload.UploadException("Telegram chat ID is missing for '${remote.name}'.")
        val existing = loadIndex(token, chatId) ?: return
        val victim = existing.second.firstOrNull { it.name == packageName } ?: return
        for (messageId in victim.messageIds) {
            runCatching {
                api(token, "deleteMessage", JSONObject().put("chat_id", chatId).put("message_id", messageId))
            }
        }
        existing.second.removeAll { it.name == packageName }
        saveIndex(token, chatId, existing.second, existing.first)
    }

    // ── Secret access ────────────────────────────────────────────────────

    /**
     * Read the bot token for [remote] from the encrypted secret store.
     *
     * [RcloneRemoteStore] keeps its secrets private to itself, so instead of
     * widening that class the store's own prefs file is read directly here
     * with the same EncryptedPrefsFactory the store uses — same file, same
     * key scheme (`secretKeyFor(backend)` keyed by remote name).
     */
    private fun secretFor(remote: RcloneRemoteStore.Remote): String? {
        if (remote.backend != "telegram") return null
        val prefs = com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "backup_rclone_secrets")
        return prefs.getString(remote.name, null)?.takeIf { it.isNotEmpty() }
    }
}
