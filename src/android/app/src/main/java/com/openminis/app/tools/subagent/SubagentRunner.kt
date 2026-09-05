package com.openminis.app.tools.subagent

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.ui.chat.ChatViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 *  - Max depth [MAX_DEPTH] (= 1): depth is a property of the CALLER — a VM
 *    registered as an active run's vm is itself a subagent, so its own
 *    spawn_agent calls are refused past the limit; the top-level VM's calls
 *    are always depth 1. Sibling spawns (second, third agent from the same
 *    conversation) are CONCURRENT, not nested — they are bounded by
 *    [MAX_ACTIVE], never by the depth limit.
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
     * with agent_status. [callerVm] is the ChatViewModel executing the tool
     * call — it decides NESTING depth (see below).
     */
    suspend fun executeSpawn(
        appContext: Context,
        parentSessionId: String,
        argsJson: String,
        callerVm: ChatViewModel? = null,
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

        // Depth is a property of the CALLER, not the session. Subagents run
        // INSIDE the parent session now, so the old session-keyed counter
        // treated every SECOND spawn from the same conversation as "recursion
        // depth 2" and refused it with "recursion limit reached" — the exact
        // "the second sub agent is broken" report (a wait=false run held its
        // depth for five minutes, blocking any sibling spawn in that window).
        // What MAX_DEPTH actually guards is NESTING: a subagent spawning
        // another subagent. Nesting is determined by WHO calls spawn_agent —
        // a VM registered as an active run's vm is itself a subagent (its
        // depth ≥ 1); any other VM is a top-level caller (depth 0). Siblings
        // are concurrent, not nested — they are bounded by MAX_ACTIVE below.
        val callerDepth = runs.values
            .firstOrNull { it.deferred.isActive && it.vm != null && it.vm === callerVm }
            ?.depth ?: 0
        val newDepth = callerDepth + 1
        if (newDepth > MAX_DEPTH) {
            return ToolExecutionResult(
                "Subagent recursion limit reached. A subagent cannot spawn further subagents.",
                false,
            )
        }

        val activeCount = runs.count { it.value.deferred.isActive }
        if (activeCount >= MAX_ACTIVE) {
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
     *
     * Completion detection is a two-phase waiter, not a single flag peek:
     *  1. START — wait (bounded) for the child's stream to actually claim
     *     `_isStreaming` (or the task row to land). The old code peeked the
     *     flag once right after send; on any send path that returns without
     *     claiming (compact pending, dropped send), it concluded the turn had
     *     ALREADY finished and returned the previous turn's text as this
     *     subagent's result.
     *  2. END — wait for `_isStreaming` to stay false across a 2 s grace, so
     *     a same-tick flicker cannot be read as completion.
     * The final text is taken from rows NEW since the pre-send snapshot —
     * never `lastOrNull(assistant)` over the whole transcript, which under
     * sibling runs (or a stale read) returns ANOTHER turn's answer.
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
                text = "no_provider_resolved_in_${PROVIDER_WAIT_MS / 1000}s",
                status = "error",
            )
        }

        // Snapshot the transcript BEFORE the child's task lands. The child's
        // turn is then identified by the rows that did not exist before —
        // immune to interleaved sibling runs and stale reads.
        val dao = app.chatRepository.dao
        val beforeIds = dao.loadMessages(run.sessionId).map { it.id }.toHashSet()

        // Headless-safe send: the pre-send compact dialog can never park the
        // task (a headless VM has nobody to answer it).
        //
        // BUG-4 fix (v116x3 verification): the child saw the full parent
        // transcript with no framing and got swept up in it (e.g. re-running
        // the parent's timer tests instead of its own task). Frame the task
        // explicitly: context-only history, exact task, no side effects.
        val framedTask = buildString {
            append("[SUBAGENT RUN — ${run.runId}] You are now executing as a SUBAGENT.\n")
            append("The conversation above is CONTEXT ONLY — it was written by other runs. ")
            append("Do NOT continue, verify, repeat, or 'clean up' anything from it.\n")
            append("Execute EXACTLY this task and nothing else:\n")
            append("=== TASK ===\n")
            append(task.trim())
            append("\n=== END TASK ===\n")
            append("Rules: do not start side effects (timers via wait_and_resume, background jobs, ")
            append("messages, or scheduled tasks) unless the task explicitly requires them; ")
            append("stay within the task's scope; when done, return the result directly.")
        }
        vm.sendMessageHeadless(framedTask)

        withContext(Dispatchers.Default) {
            // ── Phase 1: START ────────────────────────────────────────────
            val startDeadlineMs = 60_000L
            val t0 = System.currentTimeMillis()
            var started = vm.isStreaming.value
            while (!started && System.currentTimeMillis() - t0 < startDeadlineMs) {
                delay(200)
                if (vm.isStreaming.value) {
                    started = true
                } else if (dao.loadMessages(run.sessionId).any {
                        // Only THIS run's task row counts (a fresh USER row).
                        // Sibling runs sharing the session also append rows
                        // while we wait — but only assistant/tool rows; the
                        // parent is parked in our tool call, so a new user
                        // row can only be ours.
                        it.id !in beforeIds && it.role == "user"
                    }) {
                    // A row landed without the flag (instant error path) —
                    // treat the turn as begun and let phase 2 finish fast.
                    started = true
                }
                if (run.deferred.isCancelled) throw CancellationException("subagent cancelled")
            }
            if (!started) {
                return@withContext SubagentResult(
                    text = "subagent_turn_never_started (send was dropped — check the session's model/provider binding)",
                    status = "error",
                )
            }

            // ── Phase 2: END ──────────────────────────────────────────────
            // `_isStreaming` is claimed for the WHOLE agentic turn (tool calls
            // included) and cleared in the stream epilogue; require it false
            // across a 2 s stability grace so a transient flicker is not read
            // as completion.
            val endDeadlineMs = System.currentTimeMillis() + timeoutSec * 1000L
            var quietMs = 0L
            var finished = false
            while (System.currentTimeMillis() < endDeadlineMs) {
                delay(250)
                if (run.deferred.isCancelled) throw CancellationException("subagent cancelled")
                if (!vm.isStreaming.value) {
                    quietMs += 250
                    if (quietMs >= 2_000) {
                        finished = true
                        break
                    }
                } else {
                    quietMs = 0L
                }
            }
            if (!finished) {
                // Timed out — cancel the child stream so an orphan turn does
                // not keep running (and writing) after we hand back "timeout".
                runCatching { vm.cancelStream() }
            }

            // ── Result: THIS turn's rows only ────────────────────────────
            val newRows = dao.loadMessages(run.sessionId).filter { it.id !in beforeIds }
            // Anchor on our own task row: the reply is the last assistant row
            // AFTER it, so a sibling run's interleaved output (shared session)
            // cannot stand in for this run's answer.
            val taskRowIdx = newRows.indexOfFirst { it.role == "user" }
            val afterTask = if (taskRowIdx >= 0) newRows.drop(taskRowIdx + 1) else newRows
            val responseText = afterTask.lastOrNull { it.role == "assistant" }
                ?.let { extractText(it.partsJson) }
            SubagentResult(
                text = responseText
                    ?: if (finished) "(subagent turn finished without an assistant reply)" else "",
                status = when {
                    !finished -> "timeout"
                    responseText != null -> "completed"
                    else -> "error"
                },
                timedOut = !finished,
            )
        }
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
     * Drop the private VM + store. Runs exactly once per run — cancel(), the
     * wait=true finally and pruneStale can all reach a run.
     */
    private fun releaseRun(run: SubagentRun) {
        if (!run.released.compareAndSet(false, true)) return
        runCatching { run.store?.clear() }
        run.store = null
        run.vm = null
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
