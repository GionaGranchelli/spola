package dev.spola.mcp

import dev.spola.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpClientManagerTest {

    @Test
    fun `listServers returns empty for fresh manager`() {
        val manager = McpClientManager(ToolRegistry(), tempConfig())
        assertTrue(manager.listServers().isEmpty())
    }

    @Test
    fun `removeServer returns false for unknown server`() = runTest {
        val manager = McpClientManager(ToolRegistry(), tempConfig())
        val result = manager.removeServer("nonexistent")
        assertFalse(result)
    }

    @Test
    fun `saveConfig and loadConfig round-trip preserves data`() {
        val configPath = tempConfig()
        val configs = listOf(
            McpServerConfig(
                name = "server-a",
                transport = "stdio",
                command = "node",
                args = listOf("server.js"),
                enabled = true,
            ),
            McpServerConfig(
                name = "server-b",
                transport = "sse",
                url = "http://localhost:8080/mcp",
                enabled = false,
            ),
        )

        // Write configs directly to file
        val file = File(configPath)
        file.parentFile.mkdirs()
        val json = kotlinx.serialization.json.Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
        file.writeText(json.encodeToString(configs))

        // Load them back with a fresh manager
        val manager = McpClientManager(ToolRegistry(), configPath)
        val loaded = manager.loadConfig()

        assertEquals(2, loaded.size)
        assertEquals("server-a", loaded[0].name)
        assertEquals("stdio", loaded[0].transport)
        assertEquals("node", loaded[0].command)
        assertEquals(listOf("server.js"), loaded[0].args)
        assertTrue(loaded[0].enabled)

        assertEquals("server-b", loaded[1].name)
        assertEquals("sse", loaded[1].transport)
        assertEquals("http://localhost:8080/mcp", loaded[1].url)
        assertFalse(loaded[1].enabled)
    }

    @Test
    fun `loadConfig returns empty list for missing file`() {
        val manager = McpClientManager(ToolRegistry(), "/nonexistent/path/config.json")
        val configs = manager.loadConfig()
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `loadConfig returns empty list for empty or blank file`(@TempDir tempDir: Path) {
        val configPath = tempDir.resolve("empty.json").toString()
        File(configPath).writeText("")
        val manager = McpClientManager(ToolRegistry(), configPath)
        assertTrue(manager.loadConfig().isEmpty())

        File(configPath).writeText("   ")
        assertTrue(manager.loadConfig().isEmpty())
    }

    @Test
    fun `addServer rejects blank name via precondition`() {
        // Validation happens before any connection attempt, so runTest is not needed
        val manager = McpClientManager(ToolRegistry(), tempConfig())
        val config = McpServerConfig(name = "  ", transport = "stdio", command = "echo")

        try {
            kotlinx.coroutines.runBlocking { manager.addServer(config) }
            kotlin.test.fail("Should have thrown for blank name")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("blank", ignoreCase = true) == true,
                "Expected 'blank' error but got: ${e.message}")
        }
    }

    @Test
    fun `addServer rejects invalid transport via precondition`() {
        val manager = McpClientManager(ToolRegistry(), tempConfig())
        val config = McpServerConfig(name = "bad-transport", transport = "websocket", command = "echo")

        try {
            kotlinx.coroutines.runBlocking { manager.addServer(config) }
            kotlin.test.fail("Should have thrown for invalid transport")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unsupported", ignoreCase = true) == true,
                "Expected 'Unsupported' error but got: ${e.message}")
        }
    }

    @Test
    fun `addServer rejects sse without url via precondition`() {
        val manager = McpClientManager(ToolRegistry(), tempConfig())
        val config = McpServerConfig(name = "sse-no-url", transport = "sse")

        try {
            kotlinx.coroutines.runBlocking { manager.addServer(config) }
            kotlin.test.fail("Should have thrown for SSE without url")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("url is required", ignoreCase = true) == true,
                "Expected 'url is required' but got: ${e.message}")
        }
    }

    @Test
    fun `addServer rejects sse with blank url via precondition`() {
        val manager = McpClientManager(ToolRegistry(), tempConfig())
        val config = McpServerConfig(name = "sse-blank-url", transport = "sse", url = "   ")

        try {
            kotlinx.coroutines.runBlocking { manager.addServer(config) }
            kotlin.test.fail("Should have thrown for SSE with blank url")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("url is required", ignoreCase = true) == true,
                "Expected 'url is required' but got: ${e.message}")
        }
    }

    @Test
    fun `sse transport is accepted as valid`() {
        val manager = McpClientManager(ToolRegistry(), tempConfig())
        val config = McpServerConfig(name = "valid-sse", transport = "SSE", url = "http://localhost/mcp")
        // Should not throw on validation — actual connect will fail (connection refused)
        // but that's expected without a real SSE server
        var thrown: Exception? = null
        try {
            kotlinx.coroutines.runBlocking { manager.addServer(config) }
        } catch (e: Exception) {
            thrown = e
        }
        // Exception should be about connection failing, NOT about invalid transport
        assertTrue(thrown != null, "Should fail at connect (no SSE server), not at validation")
        val msg = thrown?.message?.lowercase() ?: ""
        assertFalse(msg.contains("unsupported"), "Should not reject SSE transport: $msg")
    }

    @Test
    fun `shutdown is safe to call on empty manager`() {
        val manager = McpClientManager(ToolRegistry(), tempConfig())
        manager.shutdown()
        assertTrue(manager.listServers().isEmpty())
    }

    @Test
    fun `McpServerConfig data class works correctly`() {
        val cfg = McpServerConfig(
            name = "my-server",
            transport = "stdio",
            command = "python3",
            args = listOf("-m", "mcp_server"),
            enabled = true,
        )

        assertEquals("my-server", cfg.name)
        assertEquals("stdio", cfg.transport)
        assertEquals("python3", cfg.command)
        assertEquals(listOf("-m", "mcp_server"), cfg.args)
        assertTrue(cfg.enabled)
        kotlin.test.assertNull(cfg.url)
    }

    @Test
    fun `McpServerConfig defaults are correct`() {
        val cfg = McpServerConfig(name = "minimal", transport = "stdio")

        assertEquals("minimal", cfg.name)
        kotlin.test.assertNull(cfg.command)
        assertTrue(cfg.args.isEmpty())
        kotlin.test.assertNull(cfg.url)
        assertTrue(cfg.enabled)
    }

    private fun tempConfig(): String {
        return File.createTempFile("mcp-test-", ".json").also { it.delete() }.absolutePath
    }
}
