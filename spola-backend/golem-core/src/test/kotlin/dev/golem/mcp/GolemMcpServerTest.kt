package dev.spola.mcp

import dev.spola.GolemConfig
import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import dev.spola.api.ApiAuth
import dev.spola.api.InvalidApiKeyException
import dev.spola.api.MissingApiKeyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GolemMcpServerTest {

    @Test
    fun `buildServer returns a server`() {
        val registry = ToolRegistry()
        registry.register(testTool("read_file"))
        val server = GolemMcpServer(toolRegistry = registry)
        assertNotNull(server.buildServer())
    }

    @Test
    fun `no tools returns server without error`() {
        val registry = ToolRegistry()
        val server = GolemMcpServer(toolRegistry = registry)
        assertNotNull(server.buildServer())
    }

    @Test
    fun `multiple tools register without error`() {
        val registry = ToolRegistry()
        repeat(10) { i -> registry.register(testTool("tool_$i")) }
        val server = GolemMcpServer(toolRegistry = registry)
        assertNotNull(server.buildServer())
    }

    @Test
    fun `tool parameter types map to schema correctly`() {
        val registry = ToolRegistry()
        registry.register(Tool(
            name = "multi_type",
            description = "Tool with all parameter types",
            parameters = listOf(
                ToolParameter("text", "A string", ToolParameterType.STRING),
                ToolParameter("count", "An integer", ToolParameterType.INTEGER),
                ToolParameter("flag", "A boolean", ToolParameterType.BOOLEAN),
            ),
            execute = { ToolResult.ok("done") },
        ))
        assertNotNull(GolemMcpServer(toolRegistry = registry).buildServer())
    }

    @Test
    fun `tool with optional parameters and defaults`() {
        val registry = ToolRegistry()
        registry.register(Tool(
            name = "with_defaults",
            description = "Tool with optional params",
            parameters = listOf(
                ToolParameter("required", "Required param", ToolParameterType.STRING),
                ToolParameter("optional", "Optional param", ToolParameterType.STRING, required = false, defaultValue = "default"),
                ToolParameter("count", "Count param", ToolParameterType.INTEGER, required = false, defaultValue = 42),
            ),
            execute = { ToolResult.ok("done") },
        ))
        assertNotNull(GolemMcpServer(toolRegistry = registry).buildServer())
    }

    @Test
    fun `full tool registry builds server`() {
        val registry = ToolRegistry()
        dev.spola.tools.registerTools(registry)
        val store = dev.spola.memory.SqliteMemoryStore(":memory:")
        dev.spola.memory.registerMemoryTools(registry, store)

        val server = GolemMcpServer(toolRegistry = registry)
        assertNotNull(server.buildServer())
        store.close()
    }

    @Test
    fun `all built-in tools are registered`() {
        val registry = ToolRegistry()
        dev.spola.tools.registerTools(registry)
        val store = dev.spola.memory.SqliteMemoryStore(":memory:")
        dev.spola.memory.registerMemoryTools(registry, store)

        val toolNames = registry.list().map { it.name }
        assertEquals(13, toolNames.size)
        assertTrue(toolNames.contains("read_file"))
        assertTrue(toolNames.contains("write_file"))
        assertTrue(toolNames.contains("search_files"))
        assertTrue(toolNames.contains("shell"))
        assertTrue(toolNames.contains("git_diff"))
        assertTrue(toolNames.contains("git_commit"))
        assertTrue(toolNames.contains("git_status"))
        assertTrue(toolNames.contains("git_log"))
        assertTrue(toolNames.contains("web_search"))
        assertTrue(toolNames.contains("web_fetch"))
        assertTrue(toolNames.contains("edit_file"))
        assertTrue(toolNames.contains("memory_save"))
        assertTrue(toolNames.contains("memory_search"))
        store.close()
    }

    @Test
    fun `read_file tool executes on temp file`() {
        val tempDir = java.nio.file.Files.createTempDirectory("golem-mcp-test-")
        try {
            val testFile = tempDir.resolve("test.txt")
            java.nio.file.Files.writeString(testFile, "hello world")

            val registry = ToolRegistry()
            dev.spola.tools.registerFileTools(registry)

            val tool = registry.get("read_file")!!
            val result = kotlinx.coroutines.runBlocking {
                tool.execute(mapOf("path" to testFile.toString()))
            }

            assertTrue(result.success)
            assertTrue(result.output.contains("hello world"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mcp auth guard rejects missing api key`() {
        val config = GolemConfig(apiKey = "secret")

        assertFailsWith<MissingApiKeyException> {
            ApiAuth.validateApiKey(config.apiKey, null)
        }
    }

    @Test
    fun `mcp auth guard rejects invalid api key`() {
        val config = GolemConfig(apiKey = "secret")

        assertFailsWith<InvalidApiKeyException> {
            ApiAuth.validateApiKey(config.apiKey, "wrong")
        }
    }

    private fun testTool(name: String): Tool = Tool(
        name = name,
        description = "Tool $name",
        parameters = listOf(
            ToolParameter("path", "File path", ToolParameterType.STRING),
        ),
        execute = { ToolResult.ok("result from $name") },
    )
}
