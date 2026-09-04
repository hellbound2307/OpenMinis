package com.openminis.app.tools.jobs

import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-android-job-tools] Background job control for the sandbox.
 *
 * The agent's shell_execute is blocking: a long-running process (server,
 * watcher, big build) holds the turn hostage until timeout. JobTools lets
 * the agent start a command DETACHED (setsid, output → files), keep working,
 * and poll/kill it later — all through the same per-session persistent shell
 * the normal tools use, so no native/PRoot changes are needed.
 *
 * ## How a job runs
 * Each job is a POSIX process group started via `setsid` inside the session's
 * persistent shell:
 *   setsid sh -c '<command> > log 2>&1; echo $? > code' < /dev/null &
 *   echo $! > pid
 * The child outlives the wrapper (new session/pgid), its output lands in
 * `…/.jobs/<id>.log`, and its exit code in `…/.jobs/<id>.code` when done.
 *
 * ## Lifecycle
 * Jobs live as long as the app process + PRoot sandbox live (they die with
 * the process — that is inherent to the on-device sandbox, not a bug).
 * Metadata is session-agnostic in [registry]; log/pid/code files live in the
 * session's /var/minis/workspace/.jobs (session-scoped by design, so a job
 * started in session A is polled/killable from that same session's shell).
 *
 * Poll/kill run as tiny shell round-trips through [ExecutionCoordinator], so
 * they serialize correctly with other shell work in the same session.
 */
object JobTools {

    private const val TAG = "JobTools"
    private const val JOBS_DIR = "/var/minis/workspace/.jobs"

    /** jobId → metadata (survives within the app process). */
    private val registry = ConcurrentHashMap<String, Job>()

    data class Job(
        val id: String,
        val command: String,
        val sessionId: String,
        val startedAtMs: Long,
        val label: String,
    )

    // MARK: - start

    suspend fun executeStart(
        argsJson: String,
        sessionId: String,
    ): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val command = args.optString("command", "").trim()
        val label = args.optString("label", "").take(40).ifBlank {
            command.take(32).replace("\n", " ")
        }
        if (command.isBlank()) return ToolExecutionResult("Error: 'command' is required", false)

        val id = newJobId()
        val wrapper = buildWrapper(id, command)
        val result = ExecutionCoordinator.execute(
            sessionId = sessionId,
            command = wrapper,
            timeout = 15_000L,
        )
        if (result.exitCode != 0) {
            return ToolExecutionResult(
                "job_start failed (exit ${result.exitCode}): ${result.output.take(400)}",
                false,
            )
        }
        val job = Job(
            id = id,
            command = command,
            sessionId = sessionId,
            startedAtMs = System.currentTimeMillis(),
            label = label,
        )
        registry[id] = job
        AppLogger.info(TAG, "job $id started in session $sessionId: ${command.take(80)}")
        return ToolExecutionResult(
            "Job started. id: $id\n" +
                "Log: $JOBS_DIR/$id.log (in-sandbox path)\n" +
                "Use job_poll {\"id\":\"$id\"} to check progress, job_kill to stop it.",
            true,
        )
    }

    // MARK: - poll

    /**
     * Poll one job (by id) or all jobs (no id). Returns status, log tail,
     * exit code when finished.
     */
    suspend fun executePoll(
        argsJson: String,
        sessionId: String,
    ): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val id = args.optString("id", "").trim()

        if (id.isNotBlank()) {
            val job = registry[id]
                ?: return ToolExecutionResult("Unknown job id: $id (use job_poll with no id to list)", false)
            return pollOne(job, sessionId)
        }

        // List all jobs (prune entries whose session is gone — nothing to do
        // about stale process files; the sandbox cleans with the session).
        if (registry.isEmpty()) return ToolExecutionResult("No background jobs.", true)
        val lines = ArrayList<String>()
        for (job in registry.values.sortedBy { it.startedAtMs }) {
            lines.add(pollSummary(job, sessionId))
        }
        return ToolExecutionResult(lines.joinToString("\n\n"), true)
    }

    private suspend fun pollSummary(job: Job, sessionId: String): String {
        val status = statusOf(job, sessionId)
        val elapsed = (System.currentTimeMillis() - job.startedAtMs) / 1000
        return "${job.id} — ${job.label} — $status (${elapsed}s)"
    }

    private suspend fun pollOne(job: Job, sessionId: String): ToolExecutionResult {
        val status = statusOf(job, sessionId)
        val elapsed = (System.currentTimeMillis() - job.startedAtMs) / 1000
        val sb = StringBuilder()
        sb.append("Job ${job.id} — $status (${elapsed}s)")
        sb.append("\nCommand: ${job.command.take(200)}")
        if (status != "missing") {
            val probe = "tail -c 3000 $JOBS_DIR/${job.id}.log 2>/dev/null; " +
                "[ -f $JOBS_DIR/${job.id}.code ] && echo \"CODE:$(cat $JOBS_DIR/${job.id}.code)\""
            val r = ExecutionCoordinator.execute(sessionId = sessionId, command = probe, timeout = 10_000L)
            val text = r.output.trim()
            if (text.isNotEmpty()) {
                sb.append("\n--- log tail ---\n").append(text)
            }
        } else {
            sb.append("\n(log files not found — the session's sandbox may have been reset)")
        }
        return ToolExecutionResult(sb.toString(), true)
    }

    private suspend fun statusOf(job: Job, sessionId: String): String {
        // RUNNING when the pid exists and responds to kill -0; DONE when a
        // code file exists; missing when neither.
        val probe = "if [ -f $JOBS_DIR/${job.id}.pid ] && kill -0 -- \"$(cat $JOBS_DIR/${job.id}.pid)\" 2>/dev/null; then echo RUNNING; " +
            "elif [ -f $JOBS_DIR/${job.id}.code ]; then echo DONE; else echo MISSING; fi"
        val r = ExecutionCoordinator.execute(sessionId = sessionId, command = probe, timeout = 10_000L)
        return r.output.trim().ifEmpty { "unknown" }
    }

    // MARK: - kill

    suspend fun executeKill(argsJson: String, sessionId: String): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val id = args.optString("id", "").trim()
        val job = registry[id] ?: return ToolExecutionResult("Unknown job id: $id", false)

        // Kill the whole process group (setsid made pgid == child pid).
        val kill = "kill -- -\"$(cat $JOBS_DIR/${job.id}.pid)\" 2>/dev/null; " +
            "kill \"$(cat $JOBS_DIR/${job.id}.pid)\" 2>/dev/null; echo KILLED"
        val r = ExecutionCoordinator.execute(sessionId = sessionId, command = kill, timeout = 10_000L)
        registry.remove(id)
        val ok = r.output.contains("KILLED")
        return ToolExecutionResult(
            if (ok) "Job $id killed (process group)." else "Job $id not running (already finished or gone).",
            ok,
        )
    }

    // MARK: - internals

    private fun buildWrapper(id: String, command: String): String {
        // Single-quoted heredoc-style embedding: base64 the user command so
        // quoting is bulletproof regardless of what the agent passes.
        val b64 = android.util.Base64.encodeToString(
            command.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        return (
            "mkdir -p $JOBS_DIR && " +
                "echo $b64 | base64 -d > $JOBS_DIR/$id.cmd && " +
                "setsid sh -c 'sh $JOBS_DIR/$id.cmd > $JOBS_DIR/$id.log 2>&1; " +
                "echo $? > $JOBS_DIR/$id.code' < /dev/null > /dev/null 2>&1 & " +
                "echo $! > $JOBS_DIR/$id.pid && echo STARTED:$!"
            )
    }

    private fun newJobId(): String {
        val random = (1..3).map { ('a'..'z').random() }.joinToString("")
        return "j" + System.currentTimeMillis().toString(36).takeLast(6) + random
    }
}
