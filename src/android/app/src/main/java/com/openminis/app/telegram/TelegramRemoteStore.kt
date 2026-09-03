package com.openminis.app.telegram

import android.content.Context
import com.openminis.app.util.EncryptedPrefsFactory

/**
 * [T-android-telegram-remote] Persistent configuration for the Telegram
 * remote-agent service.
 *
 * Storage split mirrors [com.openminis.app.backup.remote.RcloneRemoteStore]:
 *  - plain SharedPreferences for everything non-secret (chat id, enabled
 *    flag, per-chat session binding, update offset)
 *  - EncryptedSharedPreferences (Keystore-backed) for the bot token, via
 *    the same [EncryptedPrefsFactory] the backup remotes use.
 *
 * The bot token is the ONLY secret here. The chat id is treated as an
 * authorization anchor: the service ignores every update that does not
 * originate from [chatId], so a stranger who discovers the bot cannot drive
 * the agent (they cannot even start a chat with it — no /start handshake
 * exists by design; pairing happens through the Settings screen, which
 * verifies the token and detects the chat the user messaged from).
 */
object TelegramRemoteStore {

    private const val PREFS_NAME = "telegram_remote_prefs"
    private const val SECRETS_PREFS_NAME = "telegram_remote_secrets"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_OFFSET = "update_offset"
    private const val KEY_TOKEN = "bot_token"
    private const val KEY_PAIRED_AT = "paired_at_ms"

    fun load(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val token = runCatching {
            EncryptedPrefsFactory.safeCreate(context, SECRETS_PREFS_NAME)
                .getString(KEY_TOKEN, null)
        }.getOrNull().orEmpty()
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            chatId = prefs.getString(KEY_CHAT_ID, null).orEmpty(),
            sessionId = prefs.getString(KEY_SESSION_ID, null).orEmpty(),
            updateOffset = prefs.getLong(KEY_OFFSET, 0L),
            botToken = token,
            pairedAtMs = prefs.getLong(KEY_PAIRED_AT, 0L),
        )
    }

    fun saveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun saveChatId(context: Context, chatId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CHAT_ID, chatId).apply()
    }

    /**
     * Wall-clock moment the user tapped "Connect bot". The service's
     * cold-start drain uses it as the cutoff: updates older than pairing are
     * stale test/pairing traffic and get skipped; anything sent AFTER pairing
     * is real user input and must NEVER be silently dropped. See
     * [TelegramRemoteService.drainStaleUpdates].
     */
    fun savePairedAt(context: Context, atMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_PAIRED_AT, atMs).apply()
    }

    fun chatId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CHAT_ID, null).orEmpty()

    fun saveSessionId(context: Context, sessionId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SESSION_ID, sessionId).apply()
    }

    fun sessionId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SESSION_ID, null).orEmpty()

    fun clearSessionId(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_SESSION_ID).apply()
    }

    fun saveOffset(context: Context, offset: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_OFFSET, offset).apply()
    }

    fun saveBotToken(context: Context, token: String) {
        runCatching {
            EncryptedPrefsFactory.safeCreate(context, SECRETS_PREFS_NAME)
                .edit().putString(KEY_TOKEN, token).apply()
        }
    }

    fun botToken(context: Context): String = load(context).botToken

    data class Config(
        val enabled: Boolean,
        val chatId: String,
        val sessionId: String,
        val updateOffset: Long,
        val botToken: String,
        val pairedAtMs: Long = 0L,
    ) {
        val isConfigured: Boolean get() = botToken.isNotBlank() && chatId.isNotBlank()
    }
}
