package dev.spola.tools

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.spola.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.assertTrue

class WebToolsTest {

    @Test
    fun `web_search returns results`() = runTest {
        withServer { baseUrl ->
            duckDuckGoBaseUrl = "$baseUrl/search"
            server.createContext("/search") { exchange ->
                exchange.respondJson(
                    """
                    {
                      "Results": [
                        {
                          "Text": "Kotlin - Kotlin language site",
                          "FirstURL": "https://kotlinlang.org"
                        }
                      ],
                      "RelatedTopics": []
                    }
                    """.trimIndent(),
                )
            }

            val registry = ToolRegistry()
            registerWebTools(registry)
            val result = registry.get("web_search")!!.execute(mapOf("query" to "kotlin"))

            assertTrue(result.success, result.output)
            assertTrue(result.output.contains("Kotlin"), result.output)
            assertTrue(result.output.contains("https://kotlinlang.org"), result.output)
        }
    }

    @Test
    fun `web_search handles no results`() = runTest {
        withServer { baseUrl ->
            duckDuckGoBaseUrl = "$baseUrl/search"
            server.createContext("/search") { exchange ->
                exchange.respondJson("""{"Results":[],"RelatedTopics":[]}""")
            }

            val registry = ToolRegistry()
            registerWebTools(registry)
            val result = registry.get("web_search")!!.execute(mapOf("query" to "nothing"))

            assertTrue(result.success, result.output)
            assertTrue(result.output.contains("No results"), result.output)
        }
    }

    @Test
    fun `web_fetch returns stripped content`() = runTest {
        withServer { baseUrl ->
            server.createContext("/page") { exchange ->
                exchange.respondHtml(
                    """
                    <html><body><h1>Title</h1><p>Hello <b>world</b>.</p></body></html>
                    """.trimIndent(),
                )
            }

            val registry = ToolRegistry()
            registerWebTools(registry)
            val result = registry.get("web_fetch")!!.execute(mapOf("url" to "$baseUrl/page"))

            assertTrue(result.success, result.output)
            assertTrue(result.output.contains("Title"), result.output)
            assertTrue(result.output.contains("Hello world"), result.output)
        }
    }

    @Test
    fun `web_fetch enforces timeout`() = runTest {
        withServer { baseUrl ->
            server.createContext("/slow") { exchange ->
                Thread.sleep(1500)
                exchange.respondHtml("<html><body>slow</body></html>")
            }

            val registry = ToolRegistry()
            registerWebTools(registry)
            val result = registry.get("web_fetch")!!.execute(
                mapOf(
                    "url" to "$baseUrl/slow",
                    "timeout" to 1,
                ),
            )

            assertTrue(!result.success, "Expected timeout failure")
            assertTrue(result.output.contains("timed out", ignoreCase = true), result.output)
        }
    }

    private lateinit var server: HttpServer

    private suspend fun withServer(block: suspend (String) -> Unit) {
        val previousBaseUrl = duckDuckGoBaseUrl
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        try {
            block(baseUrl)
        } finally {
            duckDuckGoBaseUrl = previousBaseUrl
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    private fun HttpExchange.respondJson(body: String) {
        responseHeaders.add("Content-Type", "application/json")
        respond(body)
    }

    private fun HttpExchange.respondHtml(body: String) {
        responseHeaders.add("Content-Type", "text/html")
        respond(body)
    }

    private fun HttpExchange.respond(body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
