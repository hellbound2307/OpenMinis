package com.openminis.app.tools.ask

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-android-ask-user-tool] Mid-turn user question.
 *
 * When the agent hits a genuine decision point it can surface a question to
 * the user WITHOUT abandoning its work:
 *  1. `ask_user` tool call → [execute] posts a high-priority notification
 *     with the question, an INLINE REPLY action (RemoteInput), and a
 *     deep-link content intent into the session.
 *  2. The tool result tells the agent to END ITS TURN (the answer arrives
 *     later as a new user turn — there is no way to truly "pause" a stream).
 *  3. Two answer paths:
 *     a. The user replies inline on the notification → [AskUserReplyReceiver]
 *        forwards "User answered: …" into the session via HeadlessChatRunner.
 *     b. The user simply types in the chat — a normal user turn; the
 *        conversation context (question visible in the tool call) carries.
 *
 * Both paths converge on the same thing: the agent wakes with the question
 * and answer adjacent in history.
 *
 * No timeout in v1: a question that sits unanswered is a feature (the user
 * answers whenever they pick the phone up). If the agent needs a deadline it
 * can pair ask_user with wait_and_resume.
 */
object AskUserTools {

    private const val TAG = "AskUser"
    const val CHANNEL_ID = "ask_user"
    const val NOTIF_TAG = "ask_user"
    const val KEY_REPLY = "ask_user_reply"
    const val EXTRA_QID = "ask_user_qid"
    const val EXTRA_SESSION = "ask_user_session"

    /** questionId → question text (for the receiver to reference). */
    private val pending = ConcurrentHashMap<String, PendingQuestion>()

    data class PendingQuestion(
        val id: String,
        val sessionId: String,
        val question: String,
        val askedAtMs: Long,
    )

    fun execute(argsJson: String, sessionId: String, context: Context): com.openminis.app.tools.ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val question = args.optString("question", "").trim()
        if (question.isBlank()) {
            return com.openminis.app.tools.ToolExecutionResult("Error: 'question' is required", false)
        }
        if (sessionId.isBlank()) {
            return com.openminis.app.tools.ToolExecutionResult("No active session to ask in.", false)
        }

        val id = UUID.randomUUID().toString().take(8)
        pending[id] = PendingQuestion(id, sessionId, question, System.currentTimeMillis())
        postNotification(context, id, sessionId, question)

        return com.openminis.app.tools.ToolExecutionResult(
            "Question posted to the user (id $id): \"$question\"\n" +
                "The user can reply inline on the notification or by typing in the chat. " +
                "Their answer will arrive as the next user message in this session. " +
                "End your turn now — do not repeat the question or poll for the answer.",
            true,
        )
    }

    private fun postNotification(context: Context, id: String, sessionId: String, question: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.warning(TAG, "POST_NOTIFICATIONS not granted — question only visible in chat")
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManagerCompat
        // High-importance channel for heads-up display.
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID, "Agent questions", android.app.NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Questions the agent asks while working" }
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("minis://session/$sessionId")).apply {
            setPackage(context.packageName)
        }
        val contentPi = PendingIntent.getActivity(
            context, id.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Inline reply — RemoteInput requires a MUTABLE PendingIntent.
        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("Your answer").build()
        val replyIntent = Intent(context, AskUserReplyReceiver::class.java)
            .putExtra(EXTRA_QID, id)
            .putExtra(EXTRA_SESSION, sessionId)
        val replyPi = PendingIntent.getBroadcast(
            context, id.hashCode(), replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Reply", replyPi,
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Minis has a question")
            .setContentText(question.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentPi)
            .addAction(replyAction)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIF_TAG, id.hashCode(), notification) }
    }

    /** Called by the receiver after an answer arrives. */
    fun consume(id: String): PendingQuestion? = pending.remove(id)
}

/**
 * Receives the inline-reply broadcast, forwards the answer into the session
 * as a user turn (fire-and-forget on an app-scoped coroutine — broadcasts
 * must return fast; the agent loop runs on HeadlessChatRunner's own scope).
 */
class AskUserReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val qid = intent.getStringExtra(AskUserTools.EXTRA_QID) ?: return
        val sessionId = intent.getStringExtra(AskUserTools.EXTRA_SESSION) ?: return
        val answer = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AskUserTools.KEY_REPLY)?.toString()?.trim()
        if (answer.isNullOrBlank()) return

        // Clear the notification.
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManagerCompat
        manager.cancel(AskUserTools.NOTIF_TAG, qid.hashCode())

        val question = AskUserTools.consume(qid)?.question ?: "(question)"
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            launchAnswer(appContext, sessionId, question, answer)
            // wait=false — the prompt is dispatched, the agent loop runs on its
            // own scope. Safe to release the broadcast immediately.
            pendingResult.finish()
        }
    }

    private object CoroutineHolder {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
    }

    private fun launchAnswer(appContext: Context, sessionId: String, question: String, answer: String) {
        CoroutineHolder.scope.launch {
            try {
                HeadlessChatRunner.prompt(
                    context = appContext,
                    sessionId = sessionId,
                    text = "You asked: \"$question\"\nUser replied: \"$answer\"",
                    attachments = emptyList(),
                    thinkingLevel = null,
                    wait = false,
                    timeoutMs = 60_000L,
                )
            } catch (e: Exception) {
                AppLogger.error("AskUserReply", "failed to forward answer: ${e.message}")
            }
        }
    }
}
