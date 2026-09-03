package com.openminis.app.tools.subagent

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.MemoryGlobalPrefs
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-android-subagent-tool] Context-isolated child agent runs.
 *
 * A subagent runs in its own session with its own context window, sharing
 * the same providers and tools (except spawn_agent itself — depth max 1).
 * The parent receives the final text result; the child session appears in
 * the session list for inspection.
 *
 * ## Concurrency
 *  - Max [MAX_ACTIVE] concurrent runs across all sessions.
 *  - Max depth [MAX_DEPTH] (= 1, so a subagent may NOT spawn further agents).
 *  - Parent was cancelled → child is cancelled too (via [HeadlessChatRunner.stop]).
 *  - Runs are tracked in a process-scoped map; agent_status queries them.
 *
 * ## Depth tracking
 *  [depthMap] maps sessionId → depth (0 = top-level UI/telegram/scheduled
 *  session). A session with no entry defaults to depth 0. Children of depth 0
 *  get depth 1; children of depth 1 are refused.
 */
object SubagentRunner {

    private const val TAG = "SubagentRunner"
    private const val MAX_ACTIVE = 3
    private const val MAX_DEPTH = 1
    private const val DEFAULT_TIMEOUT_MS = 600_000L
    private const val MAX_TIMEOUT_MS = 1_800_000L
    private const val RESULT_TRUNCATE = 12_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val depthMap = ConcurrentHashMap<String, Int>()
    private val runs = ConcurrentHashMap<String, SubagentRun>()

    data class SubagentRun(
        val runId: String,
        val sessionId: String,
        val label: String,
        val parentSessionId: String,
        val depth: Int,
        val startedAtMs: Long,
        val deferred: CompletableDeferred<SubagentResult>,
    )

    data class SubagentResult(
        val text: String,
        val status: String, // "running", "completed", "error", "cancelled", "timeout"
        val timedOut: Boolean = false,
    )

    /**
     * Spawn a subagent and return its result.
     * When [wait] is true, blocks until completion (up to [timeoutMs]).
     * When false, returns immediately with the runId; poll with agent_status.
     */
    suspend fun executeSpawn(
        appContext: Context,
        parentSessionId: String,
        argsJson: String,
    ): ToolExecutionResult {
        val app = appContext.applicationContext as? MinisApp
        if (app == null || !app.subsystemsReady()) {
            return ToolExecutionResult("Minis is not fully initialized.", false)
        }

        val args = try { JSONObject(argsJson) } catch (e: Exception) {
            return ToolExecutionResult("Invalid JSON arguments.", false)
        }
        val task = args.optString("task", "").trim()
        if (task.isBlank()) return ToolExecutionResult("Missing 'task' parameter.", false)
        val label = args.optString("label", "").take(40).ifBlank {
            task.take(32).replace("\n", " ")
        }
        val wait = args.optBoolean("wait", true)
        val timeoutSec = args.optInt("timeout_sec", 600).coerceIn(30, 1800)

        // Depth check
        val parentDepth = depthMap[parentSessionId] ?: 0
        if (parentDepth >= MAX_DEPTH) {
            return ToolExecutionResult(
                "Subagent recursion limit reached. A subagent cannot spawn further subagents.",
                false,
            )
        }

        // Active run count check
        val activeCount = runs.count { it.value.deferred.isActive }
        if (activeCount >= MAX_ACTIVE) {
            return ToolExecutionResult(
                "Too many active subagents ($activeCount/$MAX_ACTIVE). Wait for one to finish or cancel it.",
                false,
            )
        }

        // Resolve child session
        val childSessionId = runCatching {
            createChildSession(app, parentSessionId, task, label)
        }.getOrNull()
        if (childSessionId == null) {
            return ToolExecutionResult("Failed to create subagent session. Is a provider configured?", false)
        }

        val runId = UUID.randomUUID().toString().take(8)
        val deferred = CompletableDeferred<SubagentResult>()
        val run = SubagentRun(
            runId = runId,
            sessionId = childSessionId,
            label = label,
            parentSessionId = parentSessionId,
            depth = parentDepth + 1,
            startedAtMs = System.currentTimeMillis(),
            deferred = deferred,
        )
        runs[runId] = run
        depthMap[childSessionId] = parentDepth + 1

        // Launch the actual agent turn
        val job = scope.async {
            val result = runCatching {
                val promptResult = withTimeoutOrNull(timeoutSec * 1000L) {
                    HeadlessChatRunner.prompt(
                        context = appContext,
                        sessionId = childSessionId,
                        text = task,
                        attachments = emptyList(),
                        thinkingLevel = null,
                        wait = true,
                        timeoutMs = timeoutSec * 1000L,
                    )
                }
                when {
                    promptResult == null -> SubagentResult(text = "", status = "timeout", timedOut = true)
                    promptResult.status == "Error" -> SubagentResult(text = promptResult.responseText ?: "", status = "error")
                    promptResult.timedOut -> SubagentResult(text = promptResult.responseText ?: "", status = "timeout", timedOut = true)
                    else -> SubagentResult(text = promptResult.responseText ?: "", status = "completed")
                }
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) {
                    SubagentResult(text = "", status = "cancelled")
                } else {
                    AppLogger.error(TAG, "subagent run $runId failed: ${e.message}")
                    SubagentResult(text = "Error: ${e.message}", status = "error")
                }
            }
            deferred.complete(result)
            result
        }

        if (!wait) {
            return ToolExecutionResult(
                "Subagent started. Run id: $runId. Session: $childSessionId. " +
                    "Use agent_status with run_id to check progress.",
                true,
            )
        }

        // Wait for completion
        val result = try {
            deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Parent turn was cancelled — cancel the child agent too and return
            // a "cancelled" result immediately (do NOT re-await: the deferred is
            // itself being cancelled, so awaiting it would throw again).
            job.cancel()
            deferred.cancel()
            HeadlessChatRunner.stop(appContext, childSessionId)
            SubagentResult(text = "", status = "cancelled")
        }

        // Build final output
        val preview = if (result.text.length > RESULT_TRUNCATE) {
            result.text.take(RESULT_TRUNCATE) + "\n\n… (truncated, ${result.text.length} characters total. " +
                "Full response in session $childSessionId)"
        } else result.text

        val statusLine = when (result.status) {
            "completed" -> "Done. "
            "timeout" -> "Timed out after ${timeoutSec}s. "
            "cancelled" -> "Cancelled. "
            "error" -> "Error: "
            else -> "Status: ${result.status}. "
        }
        val output = "${statusLine}Session: minis://session/$childSessionId\n\n$preview"
        return ToolExecutionResult(output.trim(), true)
    }

    /**
     * Query subagent status(es).
     * With run_id: returns status of that specific run.
     * Without: returns a summary of all active/recent runs.
     */
    fun executeStatus(argsJson: String): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (e: Exception) { JSONObject() }
        val runId = args.optString("run_id", "").trim()

        if (runId.isNotBlank()) {
            val run = runs[runId] ?: return ToolExecutionResult("Run $runId not found.", false)
            val status = when {
                run.deferred.isCancelled -> "cancelled"
                run.deferred.isCompleted -> "completed"
                else -> "running"
            }
            val result = runCatching { run.deferred.getCompleted() }.getOrNull()
            val preview = if (result != null) {
                "\n\n" + result.text.take(2000)
            } else ""
            val elapsed = (System.currentTimeMillis() - run.startedAtMs) / 1000
            return ToolExecutionResult(
                "Run $runId: $status\n" +
                    "Label: ${run.label}\n" +
                    "Session: ${run.sessionId}\n" +
                    "Elapsed: ${elapsed}s" +
                    preview,
                true,
            )
        }

        // List all active/recent runs
        val lines = runs.map { (id, run) ->
            val status = when {
                run.deferred.isCancelled -> "✕"
                run.deferred.isCompleted -> "✓"
                else -> "▶"
            }
            val elapsed = (System.currentTimeMillis() - run.startedAtMs) / 1000
            "$status $id — ${run.label} (${elapsed}s, session ${run.sessionId.take(8)}…)"
        }.take(10)
        if (lines.isEmpty()) return ToolExecutionResult("No subagent runs.", true)
        return ToolExecutionResult(lines.joinToString("\n"), true)
    }

    /** Cancel a specific run. */
    fun cancel(runId: String, appContext: Context) {
        val run = runs[runId] ?: return
        run.deferred.cancel()
        HeadlessChatRunner.stop(appContext, run.sessionId)
    }

    private suspend fun createChildSession(
        app: MinisApp,
        parentSessionId: String,
        task: String,
        label: String,
    ): String? {
        val parent = app.chatRepository.getSession(parentSessionId) ?: return null
        val repo = app.chatRepository
        val seedModelId = parent.modelId.ifBlank {
            app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id ?: return null
        }
        val session = repo.createSession(
            modelId = seedModelId,
            title = "[subagent] $label",
            memoryEnabled = MemoryGlobalPrefs.isGlobalEnabled(app),
        )
        // Copy parent's model binding for continuity.
        parent.modelBinding?.let { binding ->
            repo.updateSessionBinding(session.id, binding, session.modelId)
        }
        repo.dao.updateSource(session.id, "subagent")
        AppLogger.info(TAG, "created child session ${session.id} for parent $parentSessionId")
        return session.id
    }

    /** Cleanup completed runs older than 5 minutes — called lazily. */
    fun pruneStale() {
        val cutoff = System.currentTimeMillis() - 300_000L
        runs.entries.removeAll { (_, run) ->
            run.deferred.isCompleted && run.startedAtMs < cutoff
        }
    }
}