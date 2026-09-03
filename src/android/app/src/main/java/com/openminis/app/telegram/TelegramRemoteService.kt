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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
 *  The poll loop NEVER blocks on agent work: control commands (/new /stop
 *  /status) are answered inline so they stay live while a turn runs, and
 *  agent messages are fed through [inbound] to a single worker that runs
 *  turns one at a time under [turnMutex]. Messages sent while a turn runs
 *  queue (in order) instead of being lost.
 *
 * ## Cold-start drain
 *  Bot API keeps updates for 24h and the service persists its offset. On a
 *  fresh start (offset 0 — first run after pairing, or after the queue was
 *  never advanced) the pending queue holds every message ever sent to the
 *  bot, including the pairing message and old tests. Running those as agent
 *  turns would serialize the service for many minutes — but DRAINING them
 *  wholesale silently ate the user's just-typed test messages too (the
 *  "bot ignores me" report on a fresh install). The drain is now cutoff-
 *  aware: updates timestamped BEFORE the "Connect bot" moment (minus a
 *  60 s grace) are skipped; everything sent AFTER pairing is processed
 *  normally. Messages sent while the service is live are always processed.
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

        // ── Device-side diagnostics (read by the Settings screen) ──────────
        // A remote agent that silently dies looks identical to one that was
        // never enabled — the user just sees "the bot ignores me". These
        // volatile fields are the service's heartbeat, surfaced on the
        // Telegram Remote screen so a dead poll loop, a wrong pairing or a
        // persistent API error are VISIBLE without logcat.

        /** Last time the poll loop completed a getUpdates round (any result). */
        @Volatile
        var lastPollAtMs: Long = 0L
            private set

        /** Last poll/send error text, cleared on the next clean cycle. */
        @Volatile
        var lastPollError: String? = null
            private set

        /** Chat id of the last message ignored as unauthorized (pairing check). */
        @Volatile
        var lastIgnoredChat: String? = null

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

    /** Serializes agent turns; control commands bypass it entirely. */
    private val turnMutex = Mutex()

    /** Guard so only one poll loop runs even after rapid START_STICKY restarts. */
    private val loopActive = AtomicBoolean(false)

    /** Agent messages waiting for the worker (control commands never queue). */
    private val inbound =
        Channel<Pair<TelegramRemoteStore.Config, JSONObject>>(Channel.UNLIMITED)
    private val queueDepth = AtomicInteger(0)

    @Volatile
    private var turnBusy = false

    private var app: MinisApp? = null
    private var lastBackoffMs = 1_000L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        app = applicationContext as? MinisApp
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Telegram remote active"))
        lastNotifText = "Telegram remote active"
        // The worker owns agent turns; the poll loop only fetches updates,
        // answers control commands inline and feeds the channel — so /stop
        // and /status stay live even while a long turn runs.
        scope.launch { turnWorker() }
        // Start the poll loop UNCONDITIONALLY: pollLoop itself waits for
        // subsystem readiness, so a service started at boot (or by
        // START_STICKY before Application init finished) self-starts the
        // moment the repositories come up — instead of sitting as a live
        // process with no loop until the user happened to re-toggle.
        scope.launch { pollLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart loop if it died (e.g. after !cfg.enabled broke it and the
        // user re-enabled). pollLoop guards itself with a compareAndSet, so
        // rapid restarts cannot double-launch it.
        scope.launch { pollLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Poll loop ─────────────────────────────────────────────────────────

    /**
     * The loop NEVER exits on its own while the service lives. Every earlier
     * terminal condition is now a WAIT instead:
     *  - disabled    → wait for the toggle (a START_STICKY restart or an app
     *                  start before the user re-enables used to leave a live
     *                  foreground service with a DEAD loop — the notification
     *                  said "active" and the bot ignored everyone forever).
     *  - unconfigured→ wait for pairing (unchanged, but now also survives
     *                  transient errors inside the wait itself).
     * Any exception is caught PER ITERATION — a single failed getUpdates,
     * a storage hiccup or a drain error can no longer kill the coroutine and
     * silently strand the service. This loop is the heartbeat; it must be
     * unkillable for the lifetime of the process.
     */
    private suspend fun pollLoop() {
        if (!loopActive.compareAndSet(false, true)) return
        AppLogger.info(TAG, "poll loop started")
        try {
            // Boot/START_STICKY starts can land before Application init has
            // finished; wait here (bounded) rather than never starting.
            var waited = 0
            while (app?.subsystemsReady() != true && waited < 60_000) {
                delay(1_000)
                waited += 1_000
            }
            while (scope.isActive) {
                try {
                    val cfg = TelegramRemoteStore.load(this)
                    when {
                        !cfg.enabled -> {
                            // WAIT, don't break. Surface the truth on the
                            // notification instead of silently dying behind
                            // an "active" banner (updateNotification dedupes
                            // same-text re-posts).
                            updateNotification("Telegram remote disabled — re-enable it in Minis settings")
                            delay(15_000)
                        }
                        !cfg.isConfigured -> {
                            updateNotification("Telegram remote: waiting for setup — pair a bot in Minis settings")
                            delay(15_000)
                        }
                        else -> {
                            lastBackoffMs = 1_000L
                            // Cold start with a fresh offset: skip updates
                            // that predate pairing (stale /start tests, the
                            // pairing message itself) but KEEP everything
                            // sent after "Connect bot" was tapped — that is
                            // real user input and used to be eaten whole.
                            if (cfg.updateOffset == 0L) {
                                val drained = drainStaleUpdates(cfg)
                                if (drained > 0) {
                                    AppLogger.info(TAG, "drained $drained stale update(s) predating pairing")
                                }
                            }
                            val updates = withContext(Dispatchers.IO) {
                                TelegramRemoteClient.getUpdates(cfg.botToken, cfg.updateOffset)
                            }
                            lastPollAtMs = System.currentTimeMillis()
                            lastPollError = null
                            if (!turnBusy) updateNotification("Telegram remote active")
                            for (u in updates) {
                                if (!scope.isActive) break
                                val updateId = u.optLong("update_id", 0L)
                                TelegramRemoteStore.saveOffset(this, updateId + 1)
                                val message = u.optJSONObject("message") ?: continue
                                dispatch(cfg, message)
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handlePollError(e, TelegramRemoteStore.load(this))
                    delay(lastBackoffMs)
                }
            }
        } finally {
            loopActive.set(false)
        }
    }

    /**
     * Advance the update offset past updates that PREDATE pairing, WITHOUT
     * processing them, and stop at the first update newer than the cutoff so
     * everything the user sent after "Connect bot" survives for the normal
     * loop. The old behavior drained EVERYTHING pending on a fresh offset —
     * the user's just-typed test messages included — which read exactly like
     * "the bot ignores me" on a fresh install.
     *
     * Cutoff = pairing moment minus a 60 s grace window (messages sent while
     * the Connect round was still verifying the token are treated as stale —
     * the user was still setting up, not yet talking to the agent).
     * When no pairing timestamp exists (token saved without a Connect round),
     * fall back to draining everything: there is no cutoff to protect.
     */
    private suspend fun drainStaleUpdates(cfg: TelegramRemoteStore.Config): Int {
        val cutoffMs = if (cfg.pairedAtMs > 0L) cfg.pairedAtMs - 60_000L else Long.MAX_VALUE
        var drained = 0
        var offset = 0L
        // getUpdates returns at most 100 updates per call; loop until empty,
        // with a hard round cap so a pathological queue can't spin forever.
        for (round in 0 until 50) {
            val updates = withContext(Dispatchers.IO) {
                TelegramRemoteClient.getUpdates(cfg.botToken, offset, timeoutSec = 0)
            }
            if (updates.isEmpty()) break
            for (u in updates) {
                val id = u.optLong("update_id", 0L)
                val msgDateMs = (u.optJSONObject("message")?.optLong("date", 0L) ?: 0L) * 1000L
                if (msgDateMs >= 1L && msgDateMs < cutoffMs) {
                    // Stale: pre-pairing traffic. Confirm past it.
                    if (id >= offset) offset = id + 1
                    drained++
                } else {
                    // Fresh (or undatable) update: STOP here. Do not advance
                    // the offset past it — the main loop owns it.
                    if (offset > 0L) TelegramRemoteStore.saveOffset(this, offset)
                    return drained
                }
            }
            if (offset > 0L) TelegramRemoteStore.saveOffset(this, offset)
            delay(200)
        }
        return drained
    }

    private suspend fun handlePollError(e: Exception, cfg: TelegramRemoteStore.Config) {
        val retryable = (e as? TelegramRemoteClient.RemoteException)?.canRetry ?: true
        AppLogger.warning(TAG, "telegram poll error: ${e.message}")
        lastPollAtMs = System.currentTimeMillis()
        lastPollError = e.message ?: e::class.java.simpleName
        lastBackoffMs = (lastBackoffMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        if (!retryable && cfg.isConfigured) {
            // Auth/config problems — surface to the phone user.
            updateNotification("Telegram remote: ${e.message}")
        }
    }

    // ── Inbound routing ───────────────────────────────────────────────────

    /**
     * Route one inbound message. Control commands are answered HERE, inline
     * in the poll loop, so they work while a turn is running — the previous
     * design funneled everything through the turn mutex, which made /stop
     * unreachable exactly when it was needed. Agent messages go to [inbound].
     */
    private suspend fun dispatch(cfg: TelegramRemoteStore.Config, message: JSONObject) {
        val chat = message.optJSONObject("chat") ?: return
        val chatId = chat.optString("id", null) ?: return
        if (chatId != TelegramRemoteStore.chatId(this)) {
            // Unauthorized sender — ignore (no reply, no turn). Recorded so a
            // PAIRING MISMATCH is diagnosable from the Settings screen: the
            // user sees "messages from chat 42 are being ignored" and knows
            // the stored chat id doesn't match the one they write from.
            AppLogger.info(TAG, "ignoring message from unauthorized chat $chatId")
            lastIgnoredChat = chatId
            return
        }
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
                val dropped = dropQueued()
                val sid = TelegramRemoteStore.sessionId(this)
                if (sid.isNotEmpty()) HeadlessChatRunner.stop(this, sid)
                TelegramRemoteClient.sendText(
                    cfg.botToken, chatId,
                    if (dropped > 0) {
                        "Stopped — current turn cancelled, $dropped queued message(s) dropped."
                    } else {
                        "Stopped."
                    },
                )
                return
            }
            "/status" -> {
                val queued = queueDepth.get()
                val state = when {
                    turnBusy && queued > 0 -> "Busy — a turn is running, $queued message(s) queued."
                    turnBusy -> "Busy — a turn is running."
                    queued > 0 -> "Idle — $queued message(s) queued."
                    else -> "Idle. Send me anything."
                }
                TelegramRemoteClient.sendText(cfg.botToken, chatId, state)
                return
            }
        }

        queueDepth.incrementAndGet()
        inbound.trySend(cfg to message)
    }

    /** Drop every queued (not yet started) agent message. */
    private fun dropQueued(): Int {
        var dropped = 0
        while (inbound.tryReceive().isSuccess) {
            queueDepth.decrementAndGet()
            dropped++
        }
        return dropped
    }

    /** Consumes [inbound] and runs agent turns strictly one at a time. */
    private suspend fun turnWorker() {
        for ((cfg, message) in inbound) {
            if (!scope.isActive) break
            queueDepth.decrementAndGet()
            runTurn(cfg, message)
        }
    }

    private suspend fun runTurn(cfg: TelegramRemoteStore.Config, message: JSONObject) {
        val chatId = message.optJSONObject("chat")?.optString("id") ?: return
        val msgId = message.optInt("message_id", 0)
        val text = message.optString("text", null).takeIf { it != null }
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
                    TelegramRemoteClient.sendText(
                        cfg.botToken, chatId,
                        "⚠️ ${e.message ?: e::class.java.simpleName}",
                        msgId,
                    )
                }
            } finally {
                turnBusy = false
                updateNotification("Telegram remote active")
            }
        }
    }

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

    @Volatile
    private var lastNotifText: String? = null

    private fun updateNotification(text: String) {
        // Same-text re-posts are skipped: the poll loop and the turn worker
        // both touch the notification, and re-posting the identical banner
        // every 25s would keep the notification drawer churning.
        if (text == lastNotifText) return
        lastNotifText = text
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(text))
    }
}
