package com.openminis.app.tools.web

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit
/**
 * [T-android-web-tools] web_fetch — URL → clean readable text/markdown.
 *
 * A lightweight alternative to spinning up a browser_use tab: fetches the
 * page with a desktop user agent, strips scripts/styles/nav/ads boilerplate
 * (readability heuristic), and returns the main content as markdown-ish text
 * (headings, links, lists, code preserved). Also follows the same
 * content-type rules browsers do — HTML, plain text, and JSON pass through;
 * binaries are refused with a size hint instead of dumped as mojibake.
 */
object WebFetchTool {

    private const val NAME = "web_fetch"
    private const val MAX_BYTES = 3_000_000L
    private const val MAX_OUTPUT_CHARS = 24_000

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Fetch a web page and return its main content as clean " +
            "readable text (markdown-ish: headings, links, lists, code preserved). " +
            "Use this for reading articles, docs, and pages when you do not need " +
            "JS interaction — it is much faster and cheaper than browser_use. " +
            "For pages that require JavaScript to render, log in, or interact " +
            "(buttons, scrolling), use browser_use instead.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "url" to AgentToolParam("string", "The http(s) URL to fetch."),
            "raw_html" to AgentToolParam("boolean", "When true, return the raw HTML instead of extracted text (default false). Useful when you need to inspect markup, meta tags or embedded JSON."),
        ),
        required = listOf("tool_title", "url"),
        propertyOrdering = listOf("tool_title", "url", "raw_html"),
    )

    fun execute(argsJson: String): ToolExecutionResult = try {
        val args = org.json.JSONObject(argsJson)
        val url = args.optString("url", "").trim()
        val rawHtml = args.optBoolean("raw_html", false)
        if (url.isBlank()) {
            ToolExecutionResult("Error: 'url' is required", false)
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            ToolExecutionResult("Error: url must start with http:// or https://", false)
        } else {
            kotlinx.coroutines.runBlocking { withContext(Dispatchers.IO) { fetch(url, rawHtml) } }
        }
    } catch (e: Exception) {
        ToolExecutionResult("web_fetch failed: ${e.message}", false)
    }

    private fun fetch(url: String, rawHtml: Boolean): ToolExecutionResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,text/plain;q=0.8,*/*;q=0.7")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return ToolExecutionResult("HTTP ${response.code} for $url", false)
            }
            val contentType = response.header("Content-Type") ?: "text/html"
            val body = response.body?.string().orEmpty()
            if (body.length > MAX_BYTES) {
                return ToolExecutionResult(
                    "Page too large (${body.length / 1024} KB, limit ${MAX_BYTES / 1024} KB). " +
                        "If it is an API/JSON endpoint, fetch a more specific URL.",
                    false,
                )
            }
            return when {
                contentType.contains("application/json") || contentType.contains("javascript") ->
                    ToolExecutionResult(formatJsonOrCode(body, contentType), true)
                contentType.contains("text/plain") ->
                    ToolExecutionResult(body.take(MAX_OUTPUT_CHARS), true)
                else -> {
                    val doc = Jsoup.parse(body, url)
                    if (rawHtml) {
                        ToolExecutionResult(doc.outerHtml().take(MAX_OUTPUT_CHARS), true)
                    } else {
                        ToolExecutionResult(extractReadable(doc), true)
                    }
                }
            }
        }
    }

    private fun formatJsonOrCode(body: String, contentType: String): String {
        val trimmed = body.trim()
        // Pretty-print JSON when it parses; otherwise pass through.
        val text = if (contentType.contains("json")) {
            runCatching {
                val value = if (trimmed.startsWith("[")) org.json.JSONArray(trimmed) else org.json.JSONObject(trimmed)
                value.toString(2)
            }.getOrElse { trimmed }
        } else trimmed
        return text.take(MAX_OUTPUT_CHARS)
    }

    /**
     * Readability heuristic: score container candidates by text length of
     * <p> descendants, pick the best, convert to markdown-ish text. Falls
     * back to whole-body text when nothing stands out.
     */
    internal fun extractReadable(doc: Document): String {
        val title = doc.title().trim()

        // Remove noise first.
        doc.select("script, style, noscript, nav, header > nav, footer, aside, " +
            "form, iframe, svg, button, [aria-hidden='true'], " +
            "[role='navigation'], [role='banner'], [role='complementary']").remove()

        // Candidate containers scored by paragraph text volume.
        var best: Element? = null
        var bestScore = 0
        for (el in doc.select("article, main, [role='main'], div, section")) {
            val paras = el.select("p")
            if (paras.isEmpty()) continue
            var score = 0
            for (p in paras) score += p.text().length
            // Slight bonus for semantic containers.
            val tag = el.tagName()
            if (tag == "article" || tag == "main") score = (score * 1.2).toInt()
            if (score > bestScore) {
                bestScore = score
                best = el
            }
        }
        val root: Element = best ?: doc.body() ?: return "(empty page)"

        val sb = StringBuilder()
        if (title.isNotEmpty()) sb.append("# $title\n\n")
        appendElement(root, sb, depth = 0)
        val text = sb.toString().trim()
            .replace(Regex("\n{3,}"), "\n\n")
        if (text.isBlank()) return "(no readable text extracted — page may be JS-rendered; try browser_use)"
        return text.take(MAX_OUTPUT_CHARS) +
            if (text.length > MAX_OUTPUT_CHARS) "\n\n… (truncated — fetch subsections or use raw_html=true for more)" else ""
    }

    private fun appendElement(el: Element, sb: StringBuilder, depth: Int) {
        if (depth > 30) return
        for (node in el.childNodes()) {
            when (node) {
                is org.jsoup.nodes.TextNode -> {
                    val t = node.text()
                    if (t.isNotBlank()) sb.append(t).append(' ')
                }
                is Element -> {
                    when (node.tagName()) {
                        "h1" -> sb.append("\n\n## ").append(node.text()).append("\n\n")
                        "h2" -> sb.append("\n\n### ").append(node.text()).append("\n\n")
                        "h3", "h4", "h5", "h6" -> sb.append("\n\n#### ").append(node.text()).append("\n\n")
                        "p" -> {
                            appendInline(node, sb)
                            sb.append("\n\n")
                        }
                        "li" -> {
                            sb.append("\n- ")
                            appendInline(node, sb)
                        }
                        "ul", "ol" -> {
                            appendElement(node, sb, depth + 1)
                            sb.append("\n")
                        }
                        "pre", "code" -> sb.append("\n```\n").append(node.wholeText()).append("\n```\n")
                        "blockquote" -> sb.append("\n> ").append(node.text()).append("\n\n")
                        "br" -> sb.append('\n')
                        "hr" -> sb.append("\n---\n")
                        "img" -> {
                            val alt = node.attr("alt")
                            val src = node.absUrl("src").ifEmpty { node.attr("src") }
                            if (src.isNotBlank()) sb.append("\n![${alt}](<$src>)\n")
                        }
                        "a" -> {
                            val href = node.absUrl("href").ifEmpty { node.attr("href") }
                            val text = node.text()
                            if (href.isNotBlank() && text.isNotBlank() &&
                                !href.startsWith("#") && !href.startsWith("javascript:")
                            ) {
                                sb.append('[').append(text).append("](<").append(href).append(">)")
                            } else if (text.isNotBlank()) {
                                sb.append(text)
                            }
                        }
                        "table" -> appendTable(node, sb)
                        // Container-ish elements: recurse; others: inline text.
                        else -> {
                            if (node.children().isNotEmpty()) appendElement(node, sb, depth + 1)
                            else if (node.text().isNotBlank()) sb.append(node.text()).append(' ')
                        }
                    }
                }
                else -> {}
            }
        }
    }

    private fun appendInline(el: Element, sb: StringBuilder) {
        for (node in el.childNodes()) {
            when (node) {
                is org.jsoup.nodes.TextNode -> sb.append(node.text()).append(' ')
                is Element -> when (node.tagName()) {
                    "b", "strong" -> sb.append("**").append(node.text()).append("** ")
                    "i", "em" -> sb.append('*').append(node.text()).append("* ")
                    "code" -> sb.append('`').append(node.text()).append('`')
                    "a" -> {
                        val href = node.absUrl("href").ifEmpty { node.attr("href") }
                        sb.append('[').append(node.text()).append("](<").append(href).append(">)")
                    }
                    else -> appendInline(node, sb)
                }
                else -> {}
            }
        }
    }

    private fun appendTable(table: Element, sb: StringBuilder) {
        val rows = table.select("tr")
        if (rows.isEmpty()) return
        sb.append('\n')
        rows.take(30).forEachIndexed { i, tr ->
            val cells = tr.select("th, td").map { it.text().replace('|', '\\').take(60) }
            sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
            if (i == 0) {
                sb.append("|").append(cells.joinToString("|") { "---" }).append("|\n")
            }
        }
        sb.append('\n')
    }
}
