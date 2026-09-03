package com.openminis.app.tools.subagent

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.ui.chat.ChatViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-android-subagent-tool] Subagent runs INSIDE the parent conversation.
 *
 * ## Why not a child session
 *
 * The first implementation created a separate `[subagent] …` session per run
 * (source="subagent") and pointed a headless runner at it. The mechanism
 * worked, but the user experience was wrong in exactly the way it was
 * reported: every spawn_agent call MATERIALIZED A NEW CHAT in the session
 * list, disconnected from the conversation the user was having — a subagent
 * "started a new chat session instead of running within the same session".
 *
 * The fix is structural: the subagent's turn now runs through a PRIVATE
 * [ChatViewModel] bound to the PARENT session id. Its user message (the
 * task), every tool block it exercises, and its final assistant reply are
 * ordinary rows in the parent session's transcript — the SAME conversation,
 * visible when the chat is reopened, no new session row ever created. The
 * parent's in-flight turn is untouched (its ViewModel is a different
 * instance; its stream continues around the tool call), and the subagent's
 * final text returns to the parent as the tool result, so the parent can act
 * on it in its next loop iteration.
 *
 * ## Context
 *
 * Full inheritance comes for free: the private VM loads the parent session's
 * real history, so the subagent sees the same conversation the user sees.
 * The old bounded-transcript prefix (context="inherit") is obsolete — the
 * `context` argument is still ACCEPTED for tool-call compatibility but is a
 * no-op: there is no separate context window anymore.
 *
 * ## Concurrency
 *
 *  - Max [MAX_ACTIVE] concurrent runs across all sessions.
 *  - Max depth [MAX_DEPTH] (= 1): depth is tracked PER PARENT SESSION and
 *    incremented for the duration of each run, so a subagent running in the
 *    same session cannot nest another spawn past the limit.
 *  - Parent cancelled → child VM's stream is cancelled via [SubagentRun.vm].
 *  - Runs are tracked process-scoped; [pruneStale] releases finished runs on
 *    every spawn/status call (this process now stays alive 24/7 for the
 *    Telegram remote agent).
 */
object SubagentRunner {

    private const val TAG = "SubagentRunner"
    private const val MAX_ACTIVE = 3
    private const val MAX_DEPTH = 1
    private const val RESULT_TRUNCATE = 12_000
    /** How long the child VM may take to resolve its provider entry. */
    private const val PROVIDER_WAIT_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** sessionId → CURRENT depth of agent nesting (0 = top-level session). */
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
        /** The private child VM — cancelled on /stop and parent cancellation. */
        @Volatile var vm: ChatViewModel? = null,
        /** Private store owning [vm]; cleared when the run ends. */
        @Volatile var store: ViewModelStore? = null,
        /** Guards [releaseRun] against the double-decrement of depth. */
        val released: AtomicBoolean = AtomicBoolean(false),
    )

    data class SubagentResult(
        val text: String,
        val status: String, // "running", "completed", "error", "cancelled", "timeout"
        val timedOut: Boolean = false,
    )

    /**
     * Spawn a subagent that runs in the caller's own session and return its
     * result. When [wait] is true, blocks until completion (up to
     * [timeoutSec]); when false, returns immediately with the runId — poll
     * with agent_status.
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
        // Accepted for compatibility with earlier tool definitions. A subagent
        // now runs IN the parent session, so it always sees the parent
        // conversation — there is no separate context to seed or isolate.
        val contextMode = args.optString("context", "inherit").trim().lowercase()

        // Lazy cleanup: release completed runs before accounting for the new one.
        pruneStale()

        // The child turn needs a real session row to bind to. By the time a
        // tool executes, the calling turn's session is always persisted.
        val parent = app.chatRepository.getSession(parentSessionId)
        if (parent == null) {
            return ToolExecutionResult("Parent session not found; cannot run a subagent in it.", false)
        }

        // Depth check, atomically: increment first, roll back if refused, so a
        // same-session child can never race its own depth accounting.
        val newDepth = depthMap.compute(parentSessionId) { _, d -> (d ?: 0) + 1 } ?: 1
        if (newDepth > MAX_DEPTH) {
            depthMap.compute(parentSessionId) { _, d -> (d ?: 1) - 1 }
            return ToolExecutionResult(
                "Subagent recursion limit reached. A subagent cannot spawn further subagents.",
                false,
            )
        }

        val activeCount = runs.count { it.value.deferred.isActive }
        if (activeCount >= MAX_ACTIVE) {
            depthMap.compute(parentSessionId) { _, d -> (d ?: 1) - 1 }
            return ToolExecutionResult(
                "Too many active subagents ($activeCount/$MAX_ACTIVE). Wait for one to finish or cancel it.",
                false,
            )
        }

        val runId = UUID.randomUUID().toString().take(8)
        val deferred = CompletableDeferred<SubagentResult>()
        val run = SubagentRun(
            runId = runId,
            sessionId = parentSessionId,
            label = label,
            parentSessionId = parentSessionId,
            depth = newDepth,
            startedAtMs = System.currentTimeMillis(),
            deferred = deferred,
        )
        runs[runId] = run

        val job = scope.async {
            val result = runCatching { runSameSessionTurn(app, run, task, timeoutSec) }
                .getOrElse { e ->
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
                "Subagent started. Run id: $runId (running within this session). " +
                    "Use agent_status with run_id to check progress.",
                true,
            )
        }

        val result = try {
            deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Parent turn cancelled — cancel the child stream too and return
            // immediately (the deferred is itself cancelled; awaiting again
            // would throw here as well).
            job.cancel()
            deferred.cancel()
            runCatching { run.vm?.cancelStream() }
            SubagentResult(text = "", status = "cancelled")
        } finally {
            releaseRun(run)
        }

        val preview = if (result.text.length > RESULT_TRUNCATE) {
            result.text.take(RESULT_TRUNCATE) + "\n\n… (truncated, ${result.text.length} characters total. " +
                "Full transcript is in this session's history.)"
        } else result.text

        val statusLine = when (result.status) {
            "completed" -> "Done. "
            "timeout" -> "Timed out after ${timeoutSec}s. "
            "cancelled" -> "Cancelled. "
            "error" -> "Error: "
            else -> "Status: ${result.status}. "
        }
        val output = "${statusLine}The subagent ran within this session — its task, tool calls and " +
            "final answer are part of the conversation transcript.\n\n$preview"
        return ToolExecutionResult(output.trim(), true)
    }

    /**
     * Run ONE full agent turn for [run] inside the parent session, through a
     * private ChatViewModel so it does not collide with the parent's
     * in-flight turn on the shared per-session VM (that one is streaming the
     * very tool call we are executing — reusing it would enqueue the task and
     * deadlock waiting for a stream that cannot start until the parent's
     * turn ends).
     */
    private suspend fun runSameSessionTurn(
        app: MinisApp,
        run: SubagentRun,
        task: String,
        timeoutSec: Int,
    ): SubagentResult = withContext(Dispatchers.Main) {
        // Private store → private VM instance, same session id. Held on the
        // run record so cancellation can reach it and cleanup can clear it.
        val store = ViewModelStore()
        run.store = store
        val provider = ViewModelProvider(
            store,
            ChatViewModel.factory(
                sessionId = run.sessionId,
                chatRepository = app.chatRepository,
                providerRepository = app.providerRepository,
                appContext = app.applicationContext,
                memoryRepository = app.memoryRepository,
                skillRepository = app.skillRepository,
                mcpRepository = app.mcpRepository,
            ),
        )
        val vm = provider[ChatViewModel::class.java]
        run.vm = vm

        // Provider readiness, resolved off-Main (the VM's resolver runs on
        // Main.immediate — waiting on Main here would deadlock it).
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(PROVIDER_WAIT_MS) {
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) {
            return@withContext SubagentResult(
                text = "no_provider_resolved_in_5s",
                status = "error",
            )
        }

        vm.sendMessage(task)
        val finished = withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutSec * 1000L) {
                if (vm.isStreaming.value) {
                    vm.isStreaming.first { !it }
                }
                true
            }
        } ?: false

        // The child's final answer is the last assistant row of the shared
        // transcript (the child's turn just completed; the parent's loop is
        // still parked in this tool call, so nothing newer exists).
        val msgs = app.chatRepository.dao.loadMessages(run.sessionId)
        val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
        val responseText = lastAssistant?.let { extractText(it.partsJson) }
        SubagentResult(
            text = responseText ?: "",
            status = if (finished) "completed" else "timeout",
            timedOut = !finished,
        )
    }

    /**
     * Query subagent status(es).
     * With run_id: returns status of that specific run.
     * Without: returns a summary of all active/recent runs.
     */
    fun executeStatus(argsJson: String): ToolExecutionResult {
        pruneStale()
        val args = try { JSONObject(argsJson) } catch (e: Exception) { JSONObject() }
        val runId = args.optString("run_id", "").trim()

        if (runId.isNotBlank()) {
            val run = runs[runId] ?: return ToolExecutionResult("Run $runId not found.", false)
            val result = runCatching { run.deferred.getCompleted() }.getOrNull()
            // Report the run's REAL terminal state — a finished-but-failed run
            // previously displayed as "completed" with an empty preview.
            val status = when {
                run.deferred.isCancelled -> "cancelled"
                result != null -> result.status
                else -> "running"
            }
            val preview = if (result != null) {
                "\n\n" + result.text.take(2000)
            } else ""
            val elapsed = (System.currentTimeMillis() - run.startedAtMs) / 1000
            return ToolExecutionResult(
                "Run $runId: $status\n" +
                    "Label: ${run.label}\n" +
                    "Running within session ${run.sessionId}\n" +
                    "Elapsed: ${elapsed}s" +
                    preview,
                true,
            )
        }

        val lines = runs.map { (id, run) ->
            val status = when {
                run.deferred.isCancelled -> "✕"
                run.deferred.isCompleted -> "✓"
                else -> "▶"
            }
            val elapsed = (System.currentTimeMillis() - run.startedAtMs) / 1000
            "$status $id — ${run.label} (${elapsed}s, in session ${run.sessionId.take(8)}…)"
        }.take(10)
        if (lines.isEmpty()) return ToolExecutionResult("No subagent runs.", true)
        return ToolExecutionResult(lines.joinToString("\n"), true)
    }

    /** Cancel a specific run and release its VM. */
    fun cancel(runId: String, appContext: Context) {
        val run = runs[runId] ?: return
        run.deferred.cancel()
        runCatching { run.vm?.cancelStream() }
        releaseRun(run)
    }

    /**
     * Drop the private VM + store and roll the session's depth back. Runs
     * exactly once per run — cancel(), the wait=true finally and pruneStale
     * can all reach a run, and a second depth decrement would leak a level
     * and eventually block legitimate spawns.
     */
    private fun releaseRun(run: SubagentRun) {
        if (!run.released.compareAndSet(false, true)) return
        runCatching { run.store?.clear() }
        run.store = null
        run.vm = null
        depthMap.compute(run.parentSessionId) { _, d -> (d ?: 1) - 1 }
    }

    /** Plain text of a message's parts JSON ([{type:text,value:...}]). */
    private fun extractText(partsJson: String): String? {
        return try {
            val arr = org.json.JSONArray(partsJson)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "text") sb.append(o.optString("value", ""))
            }
            sb.toString().ifEmpty { null }
        } catch (_: Exception) {
            partsJson.ifEmpty { null }
        }
    }

    /** Cleanup completed runs older than 5 minutes — called lazily. */
    fun pruneStale() {
        val cutoff = System.currentTimeMillis() - 300_000L
        runs.entries.removeAll { (_, run) ->
            val stale = run.deferred.isCompleted && run.startedAtMs < cutoff
            // Background (wait=false) runs end outside the spawn call, so
            // removal here is their only release point — drop the VM with them.
            if (stale) releaseRun(run)
            stale
        }
    }
}
