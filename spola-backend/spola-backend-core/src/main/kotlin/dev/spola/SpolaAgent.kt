package dev.spola

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.spola.compression.TokenJuice
import dev.spola.checkpoint.CheckpointManager
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ToolCall as TramaiToolCall
import dev.tramai.core.model.ToolDefinition
import dev.tramai.core.provider.ModelProvider

/**
 * The core Spola agent: a ReAct loop that uses an LLM to drive tool execution.
 *
 * @param provider LLM provider to use for completions
 * @param effectiveModel Model name to pass to the provider
 * @param toolRegistry Spola's tool registry
 * @param config Agent configuration
 */
class SpolaAgent(
    private var provider: ModelProvider,
    private var effectiveModel: String,
    private val toolRegistry: ToolRegistry,
    private val config: SpolaConfig = SpolaConfig(),
    private val checkpointManager: CheckpointManager? = null,
) {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val conversation = mutableListOf<ChatMessage>()

    /**
     * Run the agent to completion with a given system persona and user goal.
     *
     * @param persona System persona (injected as system message)
     * @param goal User's objective
     * @return The agent's final text response
     */
    suspend fun run(persona: String, goal: String): String {
        return run(persona, goal, observer = null, sessionId = null)
    }

    /**
     * Run the agent with optional execution callbacks and optional session id.
     *
     * @param sessionId If provided, resume from (or save to) this checkpoint session.
     *   When null, a new random session id is generated.
     */
    suspend fun run(persona: String, goal: String, observer: AgentRunObserver?, sessionId: String? = null): String {
        val resolvedSessionId = sessionId ?: checkpointManager?.generateSessionId()
        conversation.clear()
        conversation.add(SystemMessage(persona))
        conversation.add(UserMessage(goal))

        // Resume from checkpoint only when an explicit session ID is provided
        if (sessionId != null && resolvedSessionId != null && config.autoCheckpoint) {
            val loaded = checkpointManager?.loadConversation(resolvedSessionId)
            if (loaded != null && loaded.size > 2) {
                conversation.clear()
                conversation.addAll(loaded)
                observer?.onStatus("resumed", "Resumed from checkpoint (session=$resolvedSessionId, ${conversation.size} messages)")
            }
        }

        return runLoop(observer, resolvedSessionId)
    }

    /**
     * Run the agent using a caller-owned transcript. The transcript is mutated
     * in-place throughout the ReAct loop, appending tool-call, tool-result,
     * and final assistant messages. For use by the REPL's ReplSession.
     *
     * Unlike [run], this does NOT clear the conversation — it appends the goal
     * to the provided transcript and lets the agent extend it.
     */
    suspend fun runFull(
        persona: String,
        goal: String,
        transcript: MutableList<ChatMessage>,
        observer: AgentRunObserver? = null,
    ): String {
        transcript.add(UserMessage(goal))
        return runLoop(observer, sessionId = null, transcript = transcript)
    }

    fun reconfigure(newProvider: ModelProvider, newModel: String) {
        provider = newProvider
        effectiveModel = newModel
    }

    fun getCheckpointManager(): CheckpointManager? = checkpointManager

    /**
     * Core ReAct loop. Shared between [run] and [runFull].
     *
     * @param transcript When provided, use this list as the conversation instead
     *   of the internal [conversation] field. The list is mutated in-place.
     */
    private suspend fun runLoop(
        observer: AgentRunObserver?,
        sessionId: String?,
        transcript: MutableList<ChatMessage>? = null,
    ): String {
        val messages = transcript ?: conversation
        val resolvedSessionId = sessionId ?: checkpointManager?.generateSessionId()
        observer?.onStatus("started", "Agent run started")
        try {
            for (turn in 1..config.maxTurns) {
                observer?.onStatus("thinking", "Running turn $turn")
                val response = callLlm(observer, messages)
                if (response.content.isNotBlank()) {
                    observer?.onToken(response.content)
                }

                val toolCalls = response.toolCalls
                if (toolCalls.isNullOrEmpty()) {
                    // LLM responded with text — final answer
                    val text = response.content
                    messages.add(AssistantMessage(content = text))
                    observer?.onStatus("complete", "Agent run completed")
                    return text
                }

                // LLM wants to call tools
                val parsedToolCalls = toolCalls.map { tc ->
                    dev.spola.ToolCall(
                        id = tc.id,
                        name = tc.name,
                        arguments = parseJsonArguments(tc.argumentsJson),
                    )
                }
                val assistantMsg = AssistantMessage(
                    content = response.content,
                    toolCalls = parsedToolCalls,
                )
                messages.add(assistantMsg)
                observer?.onStatus("tool_execution", "Executing ${toolCalls.size} tool call(s)")

                for ((index, tc) in toolCalls.withIndex()) {
                    observer?.onToolCall(parsedToolCalls[index])
                    val result = executeTool(tc.name, tc.argumentsJson)
                    observer?.onToolResult(parsedToolCalls[index], result)
                    messages.add(ToolResultMessage(
                        toolCallId = tc.id,
                        toolName = tc.name,
                        content = result.output,
                    ))
                }

                // Auto-save checkpoint after each turn
                if (resolvedSessionId != null && config.autoCheckpoint) {
                    try {
                        checkpointManager?.save(resolvedSessionId, turn, messages.toList())
                        observer?.onStatus("checkpoint", "Checkpoint saved at turn $turn")
                    } catch (e: Exception) {
                        observer?.onError(e)
                        // Checkpoint failure should not abort the agent run
                    }
                }
            }
            throw MaxTurnsExceededException(config.maxTurns)
        } catch (e: Exception) {
            observer?.onError(e)
            throw e
        }
    }

    private suspend fun callLlm(observer: AgentRunObserver?, messages: List<ChatMessage> = conversation): ModelResponse {
        val tramaiMessages = messages.map { msg ->
            when (msg) {
                is SystemMessage -> Message(
                    role = MessageRole.SYSTEM,
                    content = msg.content,
                )
                is UserMessage -> Message(
                    role = MessageRole.USER,
                    content = msg.content,
                )
                is AssistantMessage -> Message(
                    role = MessageRole.ASSISTANT,
                    content = msg.content,
                    toolCalls = msg.toolCalls.map { tc ->
                        TramaiToolCall(
                            id = tc.id,
                            name = tc.name,
                            argumentsJson = mapper.writeValueAsString(tc.arguments),
                        )
                    }.ifEmpty { null },
                )
                is ToolResultMessage -> Message(
                    role = MessageRole.TOOL,
                    content = msg.content,
                    toolCallId = msg.toolCallId,
                )
            }
        }

        val request = ModelRequest(
            model = effectiveModel,
            messages = tramaiMessages,
            tools = toolRegistry.schemas().map { schema ->
                val params = mapper.writeValueAsString(schema["parameters"])
                ToolDefinition(
                    name = schema["name"] as String,
                    description = schema["description"] as String,
                    inputSchemaJson = params,
                )
            }.ifEmpty { null },
            temperature = config.temperature,
            maxTokens = config.maxTokens,
        )

        observer?.onStatus(
            "llm_request",
            "model=$effectiveModel, messages=${tramaiMessages.size}, tools=${request.tools?.size ?: 0}",
        )
        // Notify observer about LLM call
        observer?.onLlmCall(effectiveModel, provider.providerId())
        val response = provider.complete(request)
        observer?.onLlmResult(effectiveModel, provider.providerId(), response.inputTokens, response.outputTokens)

        return response
    }

    /**
     * Execute a tool and return its result. Retries once on failure per ADR-002.
     */
    private suspend fun executeTool(name: String, argumentsJson: String): ToolResult {
        val tool = toolRegistry.get(name)
            ?: return ToolResult.fail("Unknown tool: $name")

        val args = parseJsonArguments(argumentsJson)
        // Attempt with one retry per ADR-002
        for (attempt in 1..2) {
            try {
                val result = tool.execute(args)
                val finalResult = maybeCompressToolResult(
                    toolName = name,
                    result = result,
                    compressionEnabled = config.compressionEnabled,
                )
                if (result.success || attempt == 2) return finalResult
                // Transient failure — retry
            } catch (e: Exception) {
                if (attempt == 2) {
                    return ToolResult.fail("Tool '$name' failed after 2 attempts: ${e.message}")
                }
            }
        }
        error("Unreachable: retry loop should always return") // Guard for compiler exhaustiveness
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonArguments(json: String): Map<String, Any> {
        if (json.isBlank()) return emptyMap()
        return try {
            val tree = mapper.readTree(json)
            tree.fields().asSequence().associate { (key, value) ->
                key to jsonNodeToValue(value)
            }
        } catch (e: Exception) {
            // Return a map with an error indicator so the tool can report it
            mapOf("__parse_error__" to true, "__raw_json__" to json)
        }
    }

    private fun jsonNodeToValue(node: JsonNode): Any = when {
        node.isTextual -> node.textValue()
        node.isInt -> node.intValue()
        node.isLong -> node.longValue()
        node.isDouble -> node.doubleValue()
        node.isBoolean -> node.booleanValue()
        node.isNull -> "null"
        else -> node.toString()
    }

    /** Returns the current conversation for inspection/testing. */
    fun getConversation(): List<ChatMessage> = conversation.toList()

    fun clearConversation() {
        conversation.clear()
    }
}

internal fun maybeCompressToolResult(
    toolName: String,
    result: ToolResult,
    compressionEnabled: Boolean,
): ToolResult {
    val compressed = TokenJuice.compact(toolName, result.output, compressionEnabled)
    if (compressed == result.output) return result

    val saved = result.output.length - compressed.length
    val finalOutput = "$compressed\n\n[TokenJuice: -${saved} chars]"
    // Only use compressed output if it's actually shorter (footer adds overhead)
    return if (finalOutput.length < result.output.length) {
        ToolResult(result.success, finalOutput)
    } else {
        result
    }
}

interface AgentRunObserver {
    suspend fun onStatus(status: String, message: String? = null) {}

    suspend fun onToken(text: String) {}

    suspend fun onToolCall(toolCall: ToolCall) {}

    suspend fun onToolResult(toolCall: ToolCall, result: ToolResult) {}

    suspend fun onLlmCall(model: String, provider: String) {}

    suspend fun onLlmResult(model: String, provider: String, inputTokens: Int? = null, outputTokens: Int? = null) {}

    suspend fun onError(error: Throwable) {}
}
