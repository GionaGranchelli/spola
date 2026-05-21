package dev.spola

import dev.tramai.core.model.ToolCall as TramaiToolCall
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpolaAgentTest {

    @Test
    fun `agent returns text response when LLM stops calling tools`() = runTest {
        val provider = MockToolProvider().apply {
            addTextResponse("Hello, I'm done!")
        }
        val toolRegistry = ToolRegistry()
        toolRegistry.register(Tool(
            name = "echo",
            description = "Echo input",
            parameters = listOf(ToolParameter("msg", "Message", ToolParameterType.STRING)),
            execute = { args -> ToolResult.ok("Echo: ${args["msg"]}") },
        ))

        val agent = SpolaAgent(
            provider = provider,
            effectiveModel = "mock-model",
            toolRegistry = toolRegistry,
            config = SpolaConfig(maxTurns = 5),
        )

        val result = agent.run("You are a test agent.", "say hello")
        assertEquals("Hello, I'm done!", result)
    }

    @Test
    fun `agent executes tool calls and continues loop`() = runTest {
        val provider = MockToolProvider().apply {
            addToolResponse(TramaiToolCall(id = "call-1", name = "echo", argumentsJson = """{"msg": "hello"}"""))
            addTextResponse("I called echo and got the result. Task complete!")
        }
        val toolRegistry = ToolRegistry()
        toolRegistry.register(Tool(
            name = "echo",
            description = "Echo input back",
            parameters = listOf(ToolParameter("msg", "Message", ToolParameterType.STRING)),
            execute = { args -> ToolResult.ok("Echo: ${args["msg"]}") },
        ))

        val agent = SpolaAgent(
            provider = provider,
            effectiveModel = "mock-model",
            toolRegistry = toolRegistry,
            config = SpolaConfig(maxTurns = 5),
        )

        val result = agent.run("You are a test agent.", "call echo and finish")
        assertEquals("I called echo and got the result. Task complete!", result)

        // Verify conversation has tool call + tool result messages
        val conversation = agent.getConversation()
        val assistantWithToolCall = conversation.filterIsInstance<AssistantMessage>()
            .firstOrNull { it.toolCalls.isNotEmpty() }
        assertNotNull(assistantWithToolCall, "Should have an assistant message with tool calls")
        assertEquals("echo", assistantWithToolCall.toolCalls[0].name)

        val toolResultMsg = conversation.filterIsInstance<ToolResultMessage>()
            .firstOrNull { it.toolName == "echo" }
        assertNotNull(toolResultMsg, "Should have a tool result message")
        assertTrue(toolResultMsg.content.contains("Echo: hello"))
    }

    @Test
    fun `agent enforces maxTurns and throws`() = runTest {
        val provider = MockToolProvider().apply {
            addToolResponse(TramaiToolCall(id = "c1", name = "echo", argumentsJson = """{"msg": "1"}"""))
            addToolResponse(TramaiToolCall(id = "c2", name = "echo", argumentsJson = """{"msg": "2"}"""))
        }
        val toolRegistry = ToolRegistry()
        toolRegistry.register(Tool(
            name = "echo",
            description = "Echo",
            parameters = listOf(ToolParameter("msg", "Msg", ToolParameterType.STRING)),
            execute = { ToolResult.ok("echo") },
        ))

        val agent = SpolaAgent(
            provider = provider,
            effectiveModel = "mock-model",
            toolRegistry = toolRegistry,
            config = SpolaConfig(maxTurns = 2),
        )

        try {
            agent.run("You are a test agent.", "keep calling tools forever")
            assertTrue(false, "Should have thrown MaxTurnsExceededException")
        } catch (e: MaxTurnsExceededException) {
            assertEquals(2, e.maxTurns)
        }
    }

    @Test
    fun `tool errors are returned to the LLM`() = runTest {
        val provider = MockToolProvider().apply {
            addToolResponse(TramaiToolCall(id = "call-1", name = "failing_tool", argumentsJson = "{}"))
            addTextResponse("I see the tool failed. Let me try something else.")
        }
        val toolRegistry = ToolRegistry()
        toolRegistry.register(Tool(
            name = "failing_tool",
            description = "Always fails",
            parameters = emptyList(),
            execute = { ToolResult.fail("Intentional failure for testing") },
        ))

        val agent = SpolaAgent(
            provider = provider,
            effectiveModel = "mock-model",
            toolRegistry = toolRegistry,
            config = SpolaConfig(maxTurns = 5),
        )

        val result = agent.run("You are a test agent.", "call the failing tool")
        assertEquals("I see the tool failed. Let me try something else.", result)

        val toolResultMsg = agent.getConversation().filterIsInstance<ToolResultMessage>()
            .firstOrNull { it.toolName == "failing_tool" }
        assertNotNull(toolResultMsg)
        assertTrue(toolResultMsg.content.contains("Intentional failure"))
    }

    @Test
    fun `multiple tool calls in one turn are all executed`() = runTest {
        val provider = MockToolProvider().apply {
            addToolResponse(
                TramaiToolCall(id = "c1", name = "echo", argumentsJson = """{"msg": "first"}"""),
                TramaiToolCall(id = "c2", name = "echo", argumentsJson = """{"msg": "second"}"""),
            )
            addTextResponse("Executed both tools. Done!")
        }
        val toolRegistry = ToolRegistry()
        toolRegistry.register(Tool(
            name = "echo",
            description = "Echo",
            parameters = listOf(ToolParameter("msg", "Msg", ToolParameterType.STRING)),
            execute = { args -> ToolResult.ok("Echo: ${args["msg"]}") },
        ))

        val agent = SpolaAgent(
            provider = provider,
            effectiveModel = "mock-model",
            toolRegistry = toolRegistry,
            config = SpolaConfig(maxTurns = 5),
        )

        val result = agent.run("You are a test agent.", "call echo twice in one turn")
        assertEquals("Executed both tools. Done!", result)

        val toolResults = agent.getConversation().filterIsInstance<ToolResultMessage>()
        assertEquals(2, toolResults.size, "Both tool calls should produce result messages")
    }

    @Test
    fun `agent passes temperature and maxTokens to ModelRequest`() = runTest {
        val provider = MockToolProvider().apply {
            addTextResponse("Done!")
        }
        val toolRegistry = ToolRegistry()

        val agent = SpolaAgent(
            provider = provider,
            effectiveModel = "mock-model",
            toolRegistry = toolRegistry,
            config = SpolaConfig(maxTurns = 5, temperature = 0.7, maxTokens = 2048),
        )

        agent.run("You are a test agent.", "say hello")
        val request = provider.lastRequest
        assertNotNull(request, "ModelRequest should have been captured")
        assertEquals(0.7, request?.temperature, "temperature should be 0.7")
        assertEquals(2048, request?.maxTokens, "maxTokens should be 2048")
    }
}
