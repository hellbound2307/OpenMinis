package com.openminis.app.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openminis.app.MinisApp
import com.openminis.app.data.MemoryGlobalPrefs
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.InputAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * [T-android-telegram-remote] The remote-agent service.
 *
 * Long-polls the Telegram Bot API (getUpdates with a 25s timeout) and, for
 * every authorized message from the paired chat, runs a full agent turn via
 * [HeadlessChatRunner] and replies with the assistant's text. This turns the
 * user's Minis fork into an agent you can talk to from ANY device, even when
 * the phone is locked in a pocket.
 *
 * ## Lifecycle
 *  - Started by [TelegramRemoteStore.saveEnabled] (Settings toggle), on app
 *    startup when enabled, and after BOOT_COMPLETED via the boot receiver.
 *  - Foreground service with its own notification + channel (type
 *    `mediaPlayback`, mirroring AgentForegroundService's trick to dodge the
 *    6h dataSync cap).
 *  - START_STICKY: if the system kills it, it restarts and resumes polling.
 *
 * ## Authorization
 *  Only [TelegramRemoteStore.chatId] is allowed to drive the agent. The bot
 *  token is stored in EncryptedSharedPreferences. There is deliberately no
 *  /start handshake — pairing happens on the Settings screen (verify +
 *  detect the chat the user messaged from). Updates from any other chat are
 *  ignored entirely.
 *
 * ## Turn semantics
 *  A single agent turn runs at a time (serialized by a mutex). Inbound text
 *  (and photos/documents, downloaded into the session's workspace) is sent
 *  as a user turn into a dedicated, persistent session — so the remote agent
 *  has conversation continuity across messages. `/new` starts a fresh
 *  session, `/stop` cancels the in-flight turn, `/status` reports what is
 *  running. Reply text is chunked to Telegram's 4096-char limit.
 */
class TelegramRemoteService : Service() {

    companion object {
        private const val TAG = "TelegramRemote"
        private const val CHANNEL_ID = "telegram_remote"
        private const val NOTIF_ID = 4242
        private const val RUN_TIMEOUT_MS = 15 * 60 * 1000L
        private const val MAX_RETRY_DELAY_MS = 60_000L

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, TelegramRemoteService::class.java)
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // FGS start can be denied by background-start restrictions; the
                // boot/app-start path will try again next time the process runs.
                AppLogger.warning(TAG, "failed to start remote service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TelegramRemoteService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val turnMutex = Mutex()

    private var app: MinisApp? = null
    private var lastBackoffMs = 1_000L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        app = applicationContext as? MinisApp
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Telegram remote active"))
        if (app?.subsystemsReady() == true) {
            scope.launch { pollLoop() }
        } else {
            AppLogger.warning(TAG, "subsystems not ready — waiting for start command")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart loop if it died (e.g. START_STICKY restart, or subsystems
        // came up after our onCreate early-return).
        if (app?.subsystemsReady() == true && !isLoopActive) {
            scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    private var isLoopActive = false

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun pollLoop() {
        if (isLoopActive) return
        isLoopActive = true
        val mApp = app ?: run { isLoopActive = false; return }
        AppLogger.info(TAG, "poll loop started")
        try {
            while (scope.isActive) {
                if (!TelegramRemoteStore.load(this).enabled) break
                val cfg = TelegramRemoteStore.load(this)
                if (!cfg.isConfigured) {
                    // Nothing to talk to — back off and wait for a config change
                    // (Settings screen restarts the service on save).
                    delay(15_000)
                    continue
                }
                try {
                    val updates = withContext(Dispatchers.IO) {
                        TelegramRemoteClient.getUpdates(cfg.botToken, cfg.updateOffset)
                    }
                    lastBackoffMs = 1_000L
                    for (u in updates) {
                        if (!scope.isActive) break
                        val updateId = u.optLong("update_id", 0L)
                        TelegramRemoteStore.saveOffset(this, updateId + 1)
                        val message = u.optJSONObject("message") ?: continue
                        handleInbound(cfg, message)
                    }
                } catch (e: TelegramRemoteClient.RemoteException) {
                    handlePollError(e, cfg)
                    delay(lastBackoffMs)
                } catch (e: Exception) {
                    AppLogger.warning(TAG, "poll error: ${e.message}")
                    delay(lastBackoffMs)
                }
            }
        } finally {
            isLoopActive = false
        }
    }

    private suspend fun handlePollError(e: Exception, cfg: TelegramRemoteStore.Config) {
        val retryable = (e as? TelegramRemoteClient.RemoteException)?.canRetry ?: true
        AppLogger.warning(TAG, "telegram poll error: ${e.message}")
        lastBackoffMs = (lastBackoffMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        if (!retryable && cfg.isConfigured) {
            // Auth/config problems — surface to the phone user.
            updateNotification("Telegram remote: ${e.message}")
        }
    }

    private suspend fun handleInbound(cfg: TelegramRemoteStore.Config, message: JSONObject) {
        val chat = message.optJSONObject("chat")
        val chatId = chat?.optString("id", null) ?: return
        if (chatId != TelegramRemoteStore.chatId(this)) {
            // Unauthorized sender — ignore completely (also avoids noisy logs).
            AppLogger.info(TAG, "ignoring message from unauthorized chat $chatId")
            return
        }
        val msgId = message.optInt("message_id", 0)
        val text = message.optString("text", null).takeIf { it != null }

        // Local control commands never consume an agent turn.
        val cmd = text?.trim()?.takeIf { it.startsWith("/") }
        when (cmd) {
            "/new" -> {
                TelegramRemoteStore.clearSessionId(this)
                TelegramRemoteClient.sendText(cfg.botToken, chatId, "New session started.")
                return
            }
            "/stop" -> {
                HeadlessChatRunner.stop(this, TelegramRemoteStore.sessionId(this))
                TelegramRemoteClient.sendText(cfg.botToken, chatId, "Stopped.")
                return
            }
            "/status" -> {
                val running = turnBusy
                TelegramRemoteClient.sendText(cfg.botToken, chatId,
                    if (running) "Busy — a turn is running." else "Idle. Send me anything.")
                return
            }
        }

        // Serialize agent turns: while one runs, later messages queue.
        turnMutex.withLock {
            turnBusy = true
            updateNotification("Telegram remote: working…")
            try {
                val sessionId = ensureOrCreateSession()
                if (sessionId == null) {
                    TelegramRemoteClient.sendText(
                        cfg.botToken, chatId,
                        "No provider configured — add one in Minis first.",
                        msgId,
                    )
                    return
                }
                // Typing indicator so the user knows it's live.
                TelegramRemoteClient.sendChatAction(cfg.botToken, chatId)

                val attachments = downloadAttachments(cfg, message, sessionId)
                val prompt = buildPrompt(text)
                val result = HeadlessChatRunner.prompt(
                    context = this,
                    sessionId = sessionId,
                    text = prompt,
                    attachments = attachments,
                    thinkingLevel = null,
                    wait = true,
                    timeoutMs = RUN_TIMEOUT_MS,
                )
                val reply = when {
                    result.status == "Error" -> "⚠️ ${result.responseText ?: "Error running agent."}"
                    result.status == "Timeout" -> "⏱ Timed out after ${RUN_TIMEOUT_MS / 60000} min."
                    result.responseText.isNullOrBlank() -> "(no text response)"
                    else -> result.responseText
                }
                TelegramRemoteClient.sendText(cfg.botToken, chatId, reply, msgId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "agent turn failed: ${e.message}")
                runCatching {
                    TelegramRemoteClient.sendText(cfg.botToken, chatId, "⚠️ ${e.message}", msgId)
                }
            } finally {
                turnBusy = false
                updateNotification("Telegram remote active")
            }
        }
    }

    @Volatile
    private var turnBusy = false

    private fun buildPrompt(text: String?): String {
        val t = text?.trim().orEmpty()
        return if (t.isBlank()) "(voice/image message)" else t
    }

    /**
     * Reuse the RcloneRemoteStore telegram backend config when one exists, so
     * the user can drive backup via the same remote the service polls. If the
     * store's configured chat id is empty, fall back to the store.
     */
    private fun downloadAttachments(
        cfg: TelegramRemoteStore.Config,
        message: JSONObject,
        sessionId: String,
    ): List<InputAttachment> {
        val media = TelegramRemoteClient.mediaFromMessage(message) ?: return emptyList()
        val dir = File(cacheDir, "telegram_inbound/$sessionId").apply { mkdirs() }
        val safeName = media.name.ifBlank { "file" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(dir, "${UUID.randomUUID().toString().take(8)}-$safeName")
        if (!TelegramRemoteClient.downloadFile(cfg.botToken, media.fileId, dest)) {
            AppLogger.warning(TAG, "failed to download telegram media file_id=${media.fileId}")
            return emptyList()
        }
        val uri = Uri.fromFile(dest)
        val kind = if (media.isImage) InputAttachment.Kind.IMAGE else InputAttachment.Kind.DOCUMENT
        return listOf(
            InputAttachment(
                fileName = dest.name,
                uri = uri,
                mimeType = media.mimeType.ifBlank { "application/octet-stream" },
                kind = kind,
            ),
        )
    }

    /**
     * Get-or-create the persistent remote session. Session resolution mirrors
     * ScheduledAgentRunner's default-group priority so the remote agent boots
     * through the user's default model group.
     */
    private suspend fun ensureOrCreateSession(): String? = withContext(Dispatchers.IO) {
        val existing = TelegramRemoteStore.sessionId(this@TelegramRemoteService)
        if (existing.isNotEmpty()) {
            val found = app?.chatRepository?.getSession(existing)
            if (found != null) return@withContext existing
        }
        val mApp = app ?: return@withContext null
        val repo = mApp.chatRepository
        val defaultGroupId = mApp.providerRepository.defaultPrimaryGroupId
        val seedModelId: String? = defaultGroupId
            ?.let { gid -> mApp.providerRepository.group(gid) }
            ?.let { g -> mApp.providerRepository.availableMemberEntries(g).firstOrNull()?.model?.id }
            ?: mApp.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
        if (seedModelId == null) return@withContext null

        val session = repo.createSession(
            modelId = seedModelId,
            title = "Telegram Remote",
            memoryEnabled = MemoryGlobalPrefs.isGlobalEnabled(this@TelegramRemoteService),
        )
        if (defaultGroupId != null) {
            repo.updateSessionBinding(session.id, """{"type":"group","groupId":"$defaultGroupId"}""", seedModelId)
        }
        repo.dao.updateSource(session.id, "telegram")
        TelegramRemoteStore.saveSessionId(this@TelegramRemoteService, session.id)
        session.id
    }

    // MARK: - Notification

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Telegram Remote", NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Keeps the Telegram remote agent polling in the background."
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, com.openminis.app.MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Telegram Remote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(text))
    }
}
