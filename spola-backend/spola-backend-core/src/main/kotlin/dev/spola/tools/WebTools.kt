package dev.spola.tools

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

private const val DEFAULT_SEARCH_URL = "https://api.duckduckgo.com/"
private const val MAX_FETCH_LENGTH = 5000
private val json = Json { ignoreUnknownKeys = true }

internal var duckDuckGoBaseUrl: String = DEFAULT_SEARCH_URL

fun registerWebTools(registry: ToolRegistry) {
    registry.register(Tool(
        name = "web_search",
        description = "Search the web using DuckDuckGo instant answers and related results.",
        parameters = listOf(
            ToolParameter("query", "Search query", ToolParameterType.STRING),
            ToolParameter("maxResults", "Maximum number of results to return (default: 5)", ToolParameterType.INTEGER, required = false, defaultValue = 5),
        ),
        execute = { args ->
            try {
                val query = (args["query"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: query")
                if (query.isEmpty()) {
                    return@Tool ToolResult.fail("Search query must not be empty")
                }
                val maxResults = ((args["maxResults"] as? Int) ?: 5).coerceIn(1, 20)
                val uri = buildSearchUri(query)
                val body = httpClientFor(Duration.ofSeconds(10)).sendText(uri, 10)
                val results = parseSearchResults(body).take(maxResults)

                if (results.isEmpty()) {
                    ToolResult.ok("No results found for query: $query")
                } else {
                    ToolResult.ok(results.joinToString("\n\n") { formatSearchResult(it) })
                }
            } catch (e: Exception) {
                ToolResult.fail("Web search failed: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "web_fetch",
        description = "Fetch a web page, strip HTML tags, and return the first 5000 characters of readable text.",
        parameters = listOf(
            ToolParameter("url", "URL to fetch", ToolParameterType.STRING),
            ToolParameter("timeout", "Request timeout in seconds (default: 10)", ToolParameterType.INTEGER, required = false, defaultValue = 10),
        ),
        execute = { args ->
            try {
                val url = (args["url"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: url")
                if (url.isEmpty()) {
                    return@Tool ToolResult.fail("URL must not be empty")
                }
                val timeout = ((args["timeout"] as? Int) ?: 10).coerceIn(1, 60)
                val body = httpClientFor(Duration.ofSeconds(timeout.toLong())).sendText(URI.create(url), timeout)
                val text = stripHtml(body)
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_FETCH_LENGTH)

                ToolResult.ok(text.ifBlank { "(no text content)" })
            } catch (e: Exception) {
                ToolResult.fail("Web fetch failed: ${e.message}")
            }
        },
    ))
}

private fun httpClientFor(timeout: Duration): HttpClient = HttpClient.newBuilder()
    .connectTimeout(timeout)
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

private fun HttpClient.sendText(uri: URI, timeoutSeconds: Int): String {
    val request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
        .GET()
        .build()
    val response = send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) {
        throw IllegalStateException("HTTP ${response.statusCode()}")
    }
    return response.body()
}

private fun buildSearchUri(query: String): URI {
    val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
    return URI.create("${duckDuckGoBaseUrl.trimEnd('/')}?q=$encoded&format=json&no_html=1")
}

private fun parseSearchResults(body: String): List<SearchResult> {
    val root = json.parseToJsonElement(body).jsonObject
    val results = mutableListOf<SearchResult>()

    val abstractText = root.string("AbstractText")
    val abstractUrl = root.string("AbstractURL")
    val heading = root.string("Heading")
    if (!abstractText.isNullOrBlank() && !abstractUrl.isNullOrBlank()) {
        results += SearchResult(
            title = (heading?.ifBlank { null } ?: abstractUrl) ?: abstractUrl,
            url = abstractUrl,
            snippet = abstractText,
        )
    }

    root.jsonArrayOrEmpty("Results")
        .mapNotNull { parseSearchItem(it.jsonObject) }
        .forEach(results::add)

    root.jsonArrayOrEmpty("RelatedTopics")
        .flatMap { related ->
            val obj = related.jsonObject
            when {
                "Topics" in obj -> obj.jsonArrayOrEmpty("Topics").mapNotNull { parseSearchItem(it.jsonObject) }
                else -> listOfNotNull(parseSearchItem(obj))
            }
        }
        .forEach(results::add)

    return results.distinctBy { it.url }
}

private fun parseSearchItem(obj: JsonObject): SearchResult? {
    val text = obj.string("Text") ?: return null
    val url = obj.string("FirstURL") ?: return null
    val title = text.substringBefore(" - ").substringBefore(" – ").ifBlank { url }
    return SearchResult(title = title, url = url, snippet = text)
}

private fun formatSearchResult(result: SearchResult): String = buildString {
    appendLine(result.title)
    appendLine(result.url)
    append(result.snippet)
}.trim()

private fun stripHtml(html: String): String {
    return html
        .replace(Regex("(?is)<script.*?>.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?>.*?</style>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
}

private data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

private fun JsonObject.jsonArrayOrEmpty(key: String): JsonArray {
    val element = this[key] ?: return JsonArray(emptyList())
    return element as? JsonArray ?: element.jsonArray
}
