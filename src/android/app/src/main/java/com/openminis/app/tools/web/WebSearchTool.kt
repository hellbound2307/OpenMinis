package com.openminis.app.tools.web

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

/**
 * [T-android-web-tools] web_search — real web results without a browser.
 *
 * Provider resolution (first configured wins, checked per call):
 *  1. Brave Search API      — env var BRAVE_API_KEY  (X-Subscription-Token)
 *  2. Serper.dev (Google)   — env var SERPER_API_KEY (POST /search)
 *  3. DuckDuckGo Lite       — keyless fallback (lite.duckduckgo.com HTML
 *     scrape). Decent for quick lookups; results are fewer and unranked by
 *     our own heuristics.
 *
 * Env vars come from the app's Environment Variables store (Settings →
 * Environment Variables), so the user adds a key once and every session —
 * including the sandbox shell, which shares the same store — sees it.
 *
 * Output: numbered results with title, URL, snippet; then a "sources" block.
 * Pair with web_fetch to read the promising ones.
 */
object WebSearchTool {

    private const val NAME = "web_search"
    private const val MAX_RESULTS = 10

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the web. Returns ranked results (title, URL, snippet). " +
            "Provider is automatic: Brave API (if BRAVE_API_KEY env var is set), " +
            "Serper/Google (if SERPER_API_KEY is set), otherwise keyless DuckDuckGo. " +
            "Follow up on promising results with web_fetch to read the full page.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "query" to AgentToolParam("string", "The search query. Use specific keywords; quotes for exact phrases; site: filters work on Brave/Serper."),
            "count" to AgentToolParam("integer", "Max results to return (default 8, max $MAX_RESULTS)."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "count"),
    )

    data class SearchResult(val title: String, val url: String, val snippet: String)

    suspend fun execute(argsJson: String, envVars: Map<String, String>): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val args = org.json.JSONObject(argsJson)
            val query = args.optString("query", "").trim()
            val count = args.optInt("count", 8).coerceIn(1, MAX_RESULTS)
            if (query.isBlank()) return@withContext ToolExecutionResult("Error: 'query' is required", false)

            val results = when {
                envVars["BRAVE_API_KEY"]?.isNotBlank() == true ->
                    braveSearch(query, count, envVars["BRAVE_API_KEY"]!!)
                envVars["SERPER_API_KEY"]?.isNotBlank() == true ->
                    serperSearch(query, count, envVars["SERPER_API_KEY"]!!)
                else -> duckDuckGoSearch(query, count)
            }
            if (results.isNullOrEmpty()) {
                return@withContext ToolExecutionResult(
                    "No results for \"$query\". Try different keywords.", true,
                )
            }
            val provider = when {
                envVars["BRAVE_API_KEY"]?.isNotBlank() == true -> "Brave"
                envVars["SERPER_API_KEY"]?.isNotBlank() == true -> "Serper"
                else -> "DuckDuckGo"
            }
            val body = StringBuilder()
            results.forEachIndexed { i, r ->
                body.append("${i + 1}. ${r.title}\n   ${r.url}\n")
                if (r.snippet.isNotBlank()) body.append("   ${r.snippet.take(220)}\n")
            }
            ToolExecutionResult("Search results ($provider):\n\n$body", true)
        } catch (e: Exception) {
            ToolExecutionResult("web_search failed: ${e.message}", false)
        }
    }

    // MARK: - Brave

    private fun braveSearch(query: String, count: Int, apiKey: String): List<SearchResult> {
        val url = "https://api.search.brave.com/res/v1/web/search?q=" +
            java.net.URLEncoder.encode(query, "UTF-8") + "&count=$count"
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", apiKey)
            .get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Brave API HTTP ${resp.code}")
            val json = org.json.JSONObject(resp.body?.string().orEmpty())
            val web = json.optJSONObject("web") ?: return emptyList()
            val arr = web.optJSONArray("results") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONObject(i) ?: return@mapNotNull null
                SearchResult(
                    title = r.optString("title", ""),
                    url = r.optString("url", ""),
                    snippet = r.optString("description", ""),
                )
            }
        }
    }

    // MARK: - Serper

    private fun serperSearch(query: String, count: Int, apiKey: String): List<SearchResult> {
        val payload = org.json.JSONObject().put("q", query).put("num", count)
        val request = Request.Builder().url("https://google.serper.dev/search")
            .header("X-API-KEY", apiKey)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Serper API HTTP ${resp.code}")
            val json = org.json.JSONObject(resp.body?.string().orEmpty())
            val arr = json.optJSONArray("organic") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONObject(i) ?: return@mapNotNull null
                SearchResult(
                    title = r.optString("title", ""),
                    url = r.optString("link", ""),
                    snippet = r.optString("snippet", ""),
                )
            }
        }
    }

    // MARK: - DuckDuckGo Lite (keyless fallback)

    private fun duckDuckGoSearch(query: String, count: Int): List<SearchResult> {
        val response = Jsoup.connect("https://lite.duckduckgo.com/lite/")
            .data("q", query)
            .userAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
            .referrer("https://lite.duckduckgo.com/")
            .timeout(15_000)
            .post()

        // DDG Lite layout: a flat table where result links and snippets
        // alternate between rows. Collect links first, snippets after.
        val results = ArrayList<SearchResult>()
        val links = ArrayList<Pair<String, String>>() // url, title
        for (a in response.select("a.result-link")) {
            val url = cleanDdgUrl(a.attr("href"))
            val title = a.text().trim()
            if (url.isNotBlank() && title.isNotBlank()) links.add(url to title)
        }
        val snippets = response.select("td.result-snippet").map { it.text().trim() }
        for (i in links.indices) {
            if (results.size >= count) break
            results.add(
                SearchResult(
                    title = links[i].second,
                    url = links[i].first,
                    snippet = snippets.getOrNull(i).orEmpty(),
                ),
            )
        }
        // Fallback layout variant: any external link + following sibling text.
        if (results.isEmpty()) {
            for (a in response.select("a[href*='uddg=']")) {
                if (results.size >= count) break
                val url = cleanDdgUrl(a.attr("href"))
                if (url.isBlank()) continue
                results.add(SearchResult(title = a.text().trim(), url = url, snippet = ""))
            }
        }
        return results
    }

    /** DDG wraps target URLs as /l/?uddg=<encoded> redirects — unwrap them. */
    private fun cleanDdgUrl(href: String): String {
        return try {
            if (href.contains("uddg=")) {
                val idx = href.indexOf("uddg=") + 5
                val end = href.indexOf('&', idx).let { if (it == -1) href.length else it }
                java.net.URLDecoder.decode(href.substring(idx, end), "UTF-8")
            } else href
        } catch (_: Exception) {
            href
        }
    }
}
