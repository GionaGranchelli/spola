package dev.spola

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolRegistryTest {

    @Test
    fun `register and retrieve tool`() {
        val registry = ToolRegistry()
        val tool = Tool(
            name = "test_tool",
            description = "A test tool",
            parameters = listOf(
                ToolParameter("input", "Input value", ToolParameterType.STRING),
            ),
            execute = { ToolResult.ok("done") },
        )
        registry.register(tool)

        assertEquals(tool, registry.get("test_tool"))
        assertNull(registry.get("nonexistent"))
    }

    @Test
    fun `list returns all registered tools`() {
        val registry = ToolRegistry()
        registry.register(Tool("a", "desc", emptyList()) { ToolResult.ok("") })
        registry.register(Tool("b", "desc", emptyList()) { ToolResult.ok("") })

        assertEquals(2, registry.list().size)
        assertTrue(registry.list().any { it.name == "a" })
        assertTrue(registry.list().any { it.name == "b" })
    }

    @Test
    fun `schemas generate valid JSON schema for parameters`() {
        val registry = ToolRegistry()
        registry.register(Tool(
            name = "test_tool",
            description = "Test description",
            parameters = listOf(
                ToolParameter("required_str", "A required string", ToolParameterType.STRING),
                ToolParameter("optional_int", "An optional int", ToolParameterType.INTEGER, required = false, defaultValue = 42),
                ToolParameter("flag", "A boolean flag", ToolParameterType.BOOLEAN, required = false),
            ),
        ) { ToolResult.ok("") })

        val schemas = registry.schemas()
        assertEquals(1, schemas.size)

        val schema = schemas[0]
        assertEquals("test_tool", schema["name"])
        assertEquals("Test description", schema["description"])

        @Suppress("UNCHECKED_CAST")
        val params = schema["parameters"] as Map<String, Any?>
        assertEquals("object", params["type"])

        @Suppress("UNCHECKED_CAST")
        val required = params["required"] as List<String>
        assertTrue("required_str" in required)
        assertTrue("optional_int" !in required)
        assertTrue("flag" !in required)

        @Suppress("UNCHECKED_CAST")
        val properties = params["properties"] as Map<String, Map<String, Any?>>
        assertEquals("string", properties["required_str"]!!["type"])
        assertEquals("integer", properties["optional_int"]!!["type"])
        assertEquals(42, properties["optional_int"]!!["default"])
        assertEquals("boolean", properties["flag"]!!["type"])
    }

    @Test
    fun `tool execution returns correct result`() = runTest {
        val registry = ToolRegistry()
        registry.register(Tool(
            name = "echo",
            description = "Echo input back",
            parameters = listOf(
                ToolParameter("msg", "Message to echo", ToolParameterType.STRING),
            ),
            execute = { args ->
                val msg = args["msg"] as? String ?: ""
                ToolResult.ok("Echo: $msg")
            },
        ))

        val tool = registry.get("echo")!!
        val result = tool.execute(mapOf("msg" to "hello"))
        assertTrue(result.success)
        assertEquals("Echo: hello", result.output)
    }
}
