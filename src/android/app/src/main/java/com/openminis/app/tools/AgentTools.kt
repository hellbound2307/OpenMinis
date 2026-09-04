package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * Central registry of all agent tool definitions.
 * Returns provider-agnostic AgentToolDefinition list used by the agent loop.
 * Tool definitions aligned with iOS AIChatViewModel.makeAgentTools().
 */
object AgentTools {

    fun makeAgentTools(
        supportsImageInput: Boolean = true,
        // [T-android-vision-group / GH#182] When the main model can't natively
        // see images but the user has bound a Vision Group, still expose
        // read_image: ReadImageTool routes the image through a vision-capable
        // group member and returns a text description. Mirrors iOS makeAgentTools
        // visionGroupConfigured. Neither native vision nor a Vision Group → tool
        // stays absent (current behaviour).
        visionGroupConfigured: Boolean = false,
        // [T-memory-toggle-gates-injection-and-tools-android] When the
        // user has turned memory off (via /memory or
        // Settings/SessionMemorySheet), drop both memory_write and
        // memory_get from the schema entirely so the model can't even
        // attempt those calls. Mirrors the iOS gate at
        // AIChatViewModel.makeAgentTools(memoryEnabled:).
        memoryEnabled: Boolean = true,
    ): List<AgentToolDefinition> = buildList {
        add(shellExecuteDefinition())
        add(FileReadTool.definition())
        add(FileWriteTool.definition())
        add(FileEditTool.definition())
        if (supportsImageInput || visionGroupConfigured) {
            add(ReadImageTool.definition())
        }
        add(browserUseDefinition())
        if (memoryEnabled) {
            add(memoryWriteDefinition())
            add(memoryGetDefinition())
        }
        // [T-android-subagent-tool] Always expose spawn_agent + agent_status.
        // Depth / concurrency limits are enforced at runtime by SubagentRunner.
        add(spawnAgentDefinition())
        add(agentStatusDefinition())
        // [T-android-timer-tool] Always expose wait_and_resume / timer_list / timer_cancel.
        add(waitAndResumeDefinition())
        add(timerListDefinition())
        add(timerCancelDefinition())
        // [T-android-job-tools] Background job control for the sandbox shell.
        add(jobStartDefinition())
        add(jobPollDefinition())
        add(jobKillDefinition())
        // [T-android-ask-user-tool] Mid-turn question to the user.
        add(askUserDefinition())
    }

    // Aligned with iOS AIChatViewModel.swift:4982-4993
    private fun shellExecuteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "shell_execute",
        description = "Execute a command in an isolated Linux process (Alpine Linux via PRoot). " +
            "The command runs via /bin/sh -c with stdout and stderr merged. " +
            "Each invocation spawns a fresh process — there is no shared terminal session. " +
            "Default timeout is 15 minutes.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Install Python data analysis packages', 'List files in home directory'). Use the same language as the user."),
            "command" to AgentToolParam("string", "The shell command to execute. Supports multi-line commands directly — no special escaping needed. Keep under 4000 chars; for longer scripts, write to a file with file_write first, then run it."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds (default: 900). Use a larger value for long-running commands like package installs."),
            "delay" to AgentToolParam("integer", "Delay in seconds before execution begins. The tool blocks the agent flow during this wait WITHOUT occupying the shell, so other concurrent tasks can use it. Use this instead of sleep commands to avoid resource contention."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "timeout", "delay"),
    )

    // Aligned with iOS AIChatViewModel.swift browser_use definition
    private fun browserUseDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "browser_use",
        description = "Control a web browser with up to 3 tabs. " +
            "Do NOT use this tool for minis:// action URLs (open_terminal, views, settings) — those are app deep links, use Markdown links in chat instead. " +
            "The browser supports both web URLs and minis:// resource URLs. Use minis:// URLs to preview session files (e.g. navigate to minis://workspace/index.html). " +
            "Sub-resources (JS, CSS, images, fonts) referenced via minis:// absolute paths or relative paths within HTML pages resolve correctly. " +
            "Use navigate to open URLs, screenshot to see the page (returns an image), " +
            "click/type to interact with elements, get_text/get_readable to extract content, " +
            "scroll to navigate long pages, scroll_and_collect to scroll through infinite-scroll/virtual-rendered pages (like Twitter/X timelines) and accumulate unique content items across scroll positions in a single call, " +
            "find_elements to discover interactive elements, " +
            "get_page_info for page metadata, get_backbone to get a structural overview of the page DOM as a simplified tree, " +
            "fetch to download files/resources using the page's session (returns metadata and a minis:// URL), " +
            "new_tab to open an additional tab, close_tab to close a tab, and list_tabs to see all open tabs. " +
            "Use set_viewport with viewport_width + viewport_height to override the viewport for the current session (e.g. before screenshotting a 1920×1080 HTML composition that would otherwise be cropped to the phone viewport); pass reset=true to drop the session override and fall back to the global browser setting. " +
            "Use get_cookies to retrieve cookies for the current page URL / current site root domain only (including HttpOnly cookies). get_cookies supports optional 'keywords' (filter by cookie name) and 'fuzzy' (true=contains match, false=exact match, default true). It returns only a summary and an offload env file path — raw cookie values are NOT included in the tool response. To reuse cookies in shell commands: `. /var/minis/offloads/env_cookies_xxx.sh && command`. You may define alias variables when needed. " +
            "Use set_cookies to write cookies into the current page's cookie store via the native cookie store (so even HttpOnly cookies, which JS cannot set, land). Pass a 'cookies' array of objects, each with name + value (required) and optional domain (defaults to the current page host), path (defaults to '/'), secure, http_only, and expires (Unix timestamp in seconds; omit for a session cookie). " +
            "Use wait_for_dom_stable to wait until the page DOM stops changing (useful after navigation or interactions that trigger async data loading — polls every 0.5s, resolves when mutation rate gradient is stable for 3+ intervals, default timeout 10s). " +
            "Use tab_id to target a specific tab (defaults to the most recently used tab).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Open Wikipedia homepage', 'Take screenshot of current page'). Use the same language as the user."),
            "action" to AgentToolParam("string", "The browser action to perform",
                enumValues = BrowserAction.allValues),
            "url" to AgentToolParam("string", "URL to navigate to (for navigate action) or resource to download (for fetch action)"),
            "selector" to AgentToolParam("string", "CSS selector for targeting elements (click, type, get_text, scroll, hover, find_elements). For scroll: specify a scrollable container to scroll (e.g. 'div.timeline'); if omitted, auto-detects the best scrollable element."),
            "text" to AgentToolParam("string", "Text to type (for type action)"),
            "coordinate_x" to AgentToolParam("integer", "X coordinate for click (alternative to selector)"),
            "coordinate_y" to AgentToolParam("integer", "Y coordinate for click (alternative to selector)"),
            "direction" to AgentToolParam("string", "Scroll direction", enumValues = listOf("up", "down")),
            "amount" to AgentToolParam("integer", "Scroll amount in pixels (default: 500)"),
            "script" to AgentToolParam("string", "JavaScript code to execute (for execute_js action). The script runs inside an async function wrapper — `await` and top-level `return` are both supported (e.g. `var r = await fetch(url); return await r.json()`)."),
            "user_agent" to AgentToolParam("string", "User agent profile to switch to", enumValues = listOf("desktop_chrome", "mobile_chrome")),
            "max_depth" to AgentToolParam("integer", "Maximum tree depth for get_backbone (default: 5)"),
            "scroll_count" to AgentToolParam("integer", "Number of scroll steps for scroll_and_collect (default: 10, max: 20). Each step scrolls by 'amount' pixels and waits for new content."),
            "item_selector" to AgentToolParam("string", "CSS selector for individual content items in scroll_and_collect (e.g. 'article', '[data-testid=\"tweet\"]'). If omitted, auto-detects repeated elements."),
            "tab_id" to AgentToolParam("integer", "Target tab ID (optional, defaults to most recently used tab). Use list_tabs to see available tabs."),
            "keywords" to AgentToolParam("string", "Filter cookies by name (for get_cookies). A space-separated string or array of strings. With fuzzy=true (default), ALL keywords must appear in the cookie name (case-insensitive). With fuzzy=false, cookie name must exactly equal any one of the provided keywords (case-insensitive). Omit to return all cookies for the current site."),
            "fuzzy" to AgentToolParam("boolean", "Whether keyword matching is fuzzy (contains-all) or exact-any (for get_cookies, default: true)."),
            "cookies" to AgentToolParam("string", "For set_cookies: a JSON array of cookie objects to write. Pass it as a JSON array (a JSON-encoded string of the array is also accepted). Each object: {\"name\": str (required), \"value\": str (required), \"domain\": str (optional, defaults to current page host), \"path\": str (optional, defaults to \"/\"), \"secure\": bool (optional), \"http_only\": bool (optional — sets an HttpOnly cookie that JS cannot read/set), \"expires\": int (optional, Unix timestamp in seconds; omit for a session cookie)}. Field-name variants from common cookie exports are accepted: httpOnly (=http_only), expirationDate (=expires), sameSite, and case/camel variants — so you can paste cookies verbatim from browser extensions (EditThisCookie / Cookie-Editor) or Playwright/Puppeteer storage."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds for wait_for_dom_stable (default: 10). The action polls every 0.5s and resolves when DOM mutation rate stabilizes."),
            "viewport_width" to AgentToolParam("integer", "Viewport width in CSS pixels for set_viewport (e.g. 1920). Required together with viewport_height unless reset=true."),
            "viewport_height" to AgentToolParam("integer", "Viewport height in CSS pixels for set_viewport (e.g. 1080). Required together with viewport_width unless reset=true."),
            "reset" to AgentToolParam("boolean", "For set_viewport: when true, clear the session-level viewport override and fall back to the global browser setting."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount", "scroll_count", "item_selector", "script", "user_agent", "max_depth", "keywords", "fuzzy", "cookies", "timeout", "viewport_width", "viewport_height", "reset"),
    )

    // Aligned with iOS AIChatViewModel.swift:5059-5067
    private fun memoryWriteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_write",
        description = "Write a memory entry to today's daily log (YYYY-MM-DD.md). Memories persist across all sessions. " +
            "Each entry is prepended with a timestamp. " +
            "Save: user preferences, recurring patterns, key facts, project conventions, reusable knowledge. " +
            "Avoid saving passwords, API keys, tokens, or secrets unless the user explicitly confirms after being warned. " +
            "Keep entries concise and general-purpose. GLOBAL.md is read-only (user-maintained via Settings).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Save user preference for Python', 'Note today's project context'). Use the same language as the user."),
            "content" to AgentToolParam("string", "The memory content to write. Use concise Markdown with a short heading (## Topic) and context about what was done/learned."),
        ),
        required = listOf("tool_title", "content"),
        propertyOrdering = listOf("tool_title", "content"),
    )

    // Aligned with iOS AIChatViewModel.swift:5069-5078
    private fun memoryGetDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_get",
        description = "Retrieve memories from persistent storage. Supports keyword-based fuzzy search across memory files. " +
            "Returns matching lines with surrounding context. Use this to recall previous knowledge, user preferences, or past notes.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Recall user preferences', 'Search past notes'). Use the same language as the user."),
            "scope" to AgentToolParam("string", "Memory scope to search: 'daily' for daily logs only, 'all' for daily logs + GLOBAL.md.", enumValues = listOf("daily", "all")),
            "keywords" to AgentToolParam("string", "Space-separated keywords for fuzzy matching (e.g. 'python preference' or 'API key setup'). All keywords must appear in a line or its surrounding context for a match. Leave empty to return full memory files."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "scope", "keywords"),
    )

    // [T-android-subagent-tool] spawn_agent: a subagent that runs WITHIN the
    // current conversation. Its turn executes against this same session (a
    // private ViewModel bound to it), so the task, its tool calls and its
    // final answer all land in this session's transcript — no separate chat
    // session is created, and the full conversation history is inherited
    // automatically. The parent receives the final text as the tool result.
    private fun spawnAgentDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "spawn_agent",
        description = "Spawn a subagent that runs WITHIN this same conversation — its work " +
            "appears in this session's transcript (no new chat is created) and it sees the " +
            "full conversation history automatically. Use it for subtasks that benefit from a " +
            "focused pass with full context: deep research on a file or topic just discussed, " +
            "drafting a long document, multi-step investigation. The subagent has the same " +
            "provider access and most of the same tools (except spawn_agent itself — " +
            "subagents cannot spawn further subagents). When wait=true (default), the tool " +
            "blocks until the subagent finishes and returns the final text. When wait=false, " +
            "the tool returns a run_id immediately; poll with agent_status to check progress.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Research X topic', 'Draft Y document'). Use the same language as the user."),
            "task" to AgentToolParam("string", "The instruction for the subagent. It is added to THIS conversation as the subagent's task message, so the subagent continues the same discussion — be specific about what to do and what to return."),
            "label" to AgentToolParam("string", "Optional short label identifying the run in agent_status output. Defaults to the first words of task."),
            "wait" to AgentToolParam("boolean", "When true (default), block until the subagent finishes and return the result. When false, return a run_id immediately and let it run in the background."),
            "timeout_sec" to AgentToolParam("integer", "Maximum seconds to wait for the subagent (default 600, max 1800). Ignored when wait=false."),
        ),
        required = listOf("tool_title", "task"),
        propertyOrdering = listOf("tool_title", "task", "label", "wait", "timeout_sec"),
    )

    // [T-android-timer-tool] wait_and_resume: set a timer that resumes the
    // current session after the delay, with a wake message.
    private fun waitAndResumeDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "wait_and_resume",
        description = "End the current turn and set a timer that will re-invoke " +
            "the agent in the current session after the specified delay. " +
            "Use this when you need to wait for something to finish (a build, a " +
            "download, a time-based event) and then continue working. " +
            "The timer message will appear as a user message in the session. " +
            "End your turn after calling this: the agent will be woken up " +
            "when the timer fires. " +
            "Minute granularity: delays round to the nearest minute.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary (e.g. 'Wait for build to finish', 'Check back in 30 minutes')."),
            "delay_seconds" to AgentToolParam("integer", "Delay in seconds before the agent is re-invoked (default 60, max 604800 / 7 days). Minimum 5."),
            "note" to AgentToolParam("string", "Optional note to include in the wake message. Defaults to 'Timer'."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "delay_seconds", "note"),
    )

    // [T-android-timer-tool] timer_list: list active timers.
    private fun timerListDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "timer_list",
        description = "List all active timers set by wait_and_resume. Shows id, note, and time remaining.",
        parameters = mapOf("tool_title" to AgentToolParam("string", "A concise 5-10 word summary.")),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    // [T-android-timer-tool] timer_cancel: cancel a timer by id.
    private fun timerCancelDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "timer_cancel",
        description = "Cancel a timer set by wait_and_resume. Use the id from the timer_set response or timer_list.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary."),
            "id" to AgentToolParam("string", "The timer id to cancel."),
        ),
        required = listOf("tool_title", "id"),
        propertyOrdering = listOf("tool_title", "id"),
    )

    // [T-android-job-tools] job_start: run a command DETACHED in the sandbox.
    private fun jobStartDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "job_start",
        description = "Start a long-running background job in the sandbox WITHOUT " +
            "blocking the turn (a dev server, a watcher, a long build, a download). " +
            "The command runs detached (setsid), its output goes to a log file, and " +
            "you can check on it later with job_poll or stop it with job_kill. " +
            "Unlike shell_execute there is no timeout that kills the command. " +
            "Use case: start a server with job_start, keep answering the user, " +
            "later job_poll to see if it is ready, then curl it from shell_execute.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary (e.g. 'Start dev server in background')."),
            "command" to AgentToolParam("string", "The command to run detached. Multi-line is fine; it is written to a script file and executed with sh. Keep under 4000 chars."),
            "label" to AgentToolParam("string", "Optional short label shown in job_poll listings. Defaults to the first words of command."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "label"),
    )

    // [T-android-job-tools] job_poll: check background job status/log.
    private fun jobPollDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "job_poll",
        description = "Check the status of a background job started with job_start. " +
            "With id: returns RUNNING/DONE/MISSING plus the last ~3KB of the job's log " +
            "and its exit code when finished. Without id: lists all known jobs with status.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary."),
            "id" to AgentToolParam("string", "Optional job id from job_start. Omit to list all jobs."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "id"),
    )

    // [T-android-job-tools] job_kill: stop a background job.
    private fun jobKillDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "job_kill",
        description = "Kill a background job (its whole process group) started with job_start.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary."),
            "id" to AgentToolParam("string", "The job id to kill."),
        ),
        required = listOf("tool_title", "id"),
        propertyOrdering = listOf("tool_title", "id"),
    )

    // [T-android-ask-user-tool] ask_user: mid-turn decision point.
    private fun askUserDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "ask_user",
        description = "Ask the user a question when you hit a genuine decision " +
            "point that cannot be resolved from context (an ambiguity that changes " +
            "the outcome, a destructive action that needs confirmation, or a " +
            "preference only the user knows). The question is posted as a " +
            "high-priority notification with an inline reply field; the answer " +
            "arrives as the next user message in this session. " +
            "End your turn immediately after calling this tool. " +
            "Do NOT use this for trivial preferences — prefer reasonable defaults " +
            "and state your choice. Use sparingly.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary (e.g. 'Ask which database to use')."),
            "question" to AgentToolParam("string", "The question to ask. Be specific; include the options you are weighing if any. The user sees exactly this text."),
        ),
        required = listOf("tool_title", "question"),
        propertyOrdering = listOf("tool_title", "question"),
    )

    // [T-android-subagent-tool] agent_status: query subagent runs.
    private fun agentStatusDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "agent_status",
        description = "Query the status of a subagent run. When run_id is provided, " +
            "returns the status and result of that specific run. Without run_id, " +
            "lists all active and recent subagent runs.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary."),
            "run_id" to AgentToolParam("string", "Optional: the run id returned by spawn_agent. Omit to list all runs."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "run_id"),
    )
}
