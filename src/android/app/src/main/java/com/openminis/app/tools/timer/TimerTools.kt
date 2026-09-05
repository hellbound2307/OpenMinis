package com.openminis.app.tools.timer

import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject
import java.util.Calendar

/**
 * [T-android-timer-tool] Timer tools — wait_and_resume, timer_list, timer_cancel.
 *
 * ## wait_and_resume
 * Sets an alarm that, after N seconds, appends a wake message into the current
 * session as a user turn, re-invoking the agent. This lets the agent say "I'll
 * check back in 5 minutes" and has the concersation naturally resume.
 *
 * How it works:
 * 1. The tool creates a [ScheduledTask] with repeatMode=ONCE and
 *    targetMode=AppendToSession(currentSessionId).
 * 2. The task's time-of-day is computed from now + delay_seconds.
 * 3. The [ScheduledTaskAlarmReceiver] fires → [ScheduledAgentRunner] runs →
 *    HeadlessChatRunner.prompt sends the wake message as a user turn.
 * 4. The agent sees the wake message and continues the conversation.
 *
 * The task is labeled with "[timer]" prefix so timer_list/timer_cancel can
 * find them, and user-visible scheduled-task UI also shows them.
 */
object TimerTools {

    private const val TAG = "TimerTools"
    private const val MAX_DELAY_SEC = 7 * 24 * 3600  // 7 days
    private const val TIMER_PREFIX = "[timer]"

    /**
     * Create a timer that resumes the current session after [delaySec].
     *
     * @param argsJson: {tool_title, delay_seconds, note?}
     * @param sessionId: current session id (must be real, not ephemeral)
     * @param context: android.content.Context
     * @return ToolExecutionResult with the timer id and fire time.
     */
    fun executeSet(
        argsJson: String,
        sessionId: String,
        context: android.content.Context,
    ): ToolExecutionResult {
        if (sessionId.isBlank()) {
            return ToolExecutionResult("No active session. This tool must be called from within a chat.", false)
        }
        val args = try { JSONObject(argsJson) } catch (e: Exception) {
            return ToolExecutionResult("Invalid arguments JSON.", false)
        }
        val delaySec = args.optInt("delay_seconds", 60).coerceIn(5, MAX_DELAY_SEC)
        val note = args.optString("note", "Timer").trim().take(200).ifBlank { "Timer" }

        val now = System.currentTimeMillis()
        val fireAt = now + delaySec * 1000L

        // Compute time-of-day for the fire moment.
        val cal = Calendar.getInstance().apply { timeInMillis = fireAt }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)

        // Start-of-day for the fire date (startDateMs = floor(nextTriggerMs) starts
        // at this day's start). Without this, nextTriggerMs would compute today's
        // hh:mm and then roll forward if it's already past (i.e., if fireAt is
        // tomorrow, today's hh:mm would be in the past, and it'd roll to tomorrow).
        val startOfFireDay = Calendar.getInstance().apply {
            timeInMillis = fireAt
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val wakeMessage = "⏰ Timer fired (${humanDuration(delaySec.toLong())} ago): $note"
        val label = "$TIMER_PREFIX $note"

        val task = ScheduledTask(
            label = label,
            timeOfDayHour = hour,
            timeOfDayMinute = minute,
            repeatMode = ScheduledRepeatMode.ONCE,
            prompt = wakeMessage,
            targetMode = ScheduledTargetMode.AppendToSession(sessionId),
            startDateMs = startOfFireDay,
            enabled = true,
        )
        val manager = ScheduledTaskManager(context)
        val saved = manager.create(task)

        AppLogger.info(TAG, "timer set: id=${saved.id} delay=${delaySec}s fireAt=$fireAt session=$sessionId")
        return ToolExecutionResult(
            output = "Timer set. Requested delay: ${humanDuration(delaySec.toLong())} — " +
                "actual fire is minute-granular (rounds to the next whole minute, so it may " +
                "fire up to ~1 min early/late). " +
                "Timer id: ${saved.id}. " +
                "The agent will be re-invoked when the timer fires. " +
                "End your turn now.",
            success = true,
        )
    }

    /** List active timers for this session. */
    fun executeList(context: android.content.Context): ToolExecutionResult {
        val manager = ScheduledTaskManager(context)
        val all = manager.list()
        val timers = all.filter { it.label.startsWith(TIMER_PREFIX) && it.enabled }
        if (timers.isEmpty()) return ToolExecutionResult("No active timers.", true)

        val lines = timers.map { t ->
            val next = t.nextTriggerMs()
            val timeStr = if (next != null) {
                // BUG-2 display fix: round UP to the next minute — the same
                // delay that set the timer reports the same duration here.
                // (Rounding down made a 125s timer show "1m 0s".)
                val diff = (next - System.currentTimeMillis() + 59_999) / 1000
                if (diff > 0) "${humanDuration(diff)} from now" else "any moment"
            } else "unknown"
            val snippet = t.label.removePrefix(TIMER_PREFIX).trim().take(40)
            "  ${t.id} — $snippet ($timeStr)"
        }
        return ToolExecutionResult(
            "Active timers (full ids — pass one to timer_cancel):\n${lines.joinToString("\n")}",
            true,
        )
    }

    /** Cancel a timer by id (full UUID or unambiguous prefix). */
    fun executeCancel(id: String, context: android.content.Context): ToolExecutionResult {
        if (id.isBlank()) return ToolExecutionResult("Error: 'id' is required", false)
        val manager = ScheduledTaskManager(context)
        // BUG-2 fix: accept unambiguous id prefixes — timer_list previously
        // showed truncated ids that timer_cancel then rejected.
        val task = manager.get(id)
            ?: manager.list().firstOrNull { it.id.startsWith(id) && it.label.startsWith(TIMER_PREFIX) }
            ?: manager.list().firstOrNull { it.id.startsWith(id) }
        if (task == null) return ToolExecutionResult("Timer not found: $id", false)
        if (!task.label.startsWith(TIMER_PREFIX)) {
            return ToolExecutionResult("Task ${task.id} is not a timer (it is a regular scheduled task).", false)
        }
        manager.delete(task.id)
        return ToolExecutionResult("Timer cancelled: ${task.label.removePrefix(TIMER_PREFIX).trim()}", true)
    }

    private fun humanDuration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
    }
}