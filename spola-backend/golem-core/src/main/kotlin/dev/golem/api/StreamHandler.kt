package dev.spola.api

import dev.spola.AgentRunObserver
import dev.spola.AssistantMessage
import dev.spola.ToolCall
import dev.spola.ToolResult
import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class StreamHandler(
    private val agentRunHandler: AgentRunHandler,
    private val json: Json = Json {
        explicitNulls = false
    },
) {
    suspend fun stream(
        session: ServerSSESession,
        request: AgentRunRequest,
        onComplete: (suspend (AgentRunHandler.CompletedRun) -> Unit)? = null,
    ) {
        agentRunHandler.trackRun {
            val instance = agentRunHandler.createInstance(request)
            try {
                send(session, "status", StatusEventPayload(status = "started", message = "Creating agent instance"))
                val persona = request.persona ?: instance.persona
                val observer = object : AgentRunObserver {
                    override suspend fun onStatus(status: String, message: String?) {
                        send(session, "status", StatusEventPayload(status = status, message = message))
                    }

                    override suspend fun onToken(text: String) {
                        send(session, "token", TokenEventPayload(text = text))
                    }

                    override suspend fun onToolCall(toolCall: ToolCall) {
                        send(
                            session,
                            "tool_call",
                            ToolCallEventPayload(
                                id = toolCall.id,
                                name = toolCall.name,
                                arguments = toolCall.arguments.mapValues { JsonPrimitive(it.value.toString()) },
                            ),
                        )
                    }

                    override suspend fun onToolResult(toolCall: ToolCall, result: ToolResult) {
                        send(session, "tool_result", toolResultEventPayload(toolCall, result))
                    }

                    override suspend fun onError(error: Throwable) {
                        send(
                            session,
                            "error",
                            ErrorEventPayload(error.message ?: error::class.simpleName ?: "unknown error"),
                        )
                    }
                }

                val result = agentRunHandler.runAgent(
                    agent = instance.agent,
                    persona = persona,
                    goal = request.goal,
                    observer = observer,
                )
                val conversation = instance.agent.getConversation()
                val turns = conversation.filterIsInstance<AssistantMessage>().size
                onComplete?.invoke(
                    AgentRunHandler.CompletedRun(
                        result = result,
                        turns = turns,
                        conversation = conversation,
                    ),
                )
                send(session, "complete", CompleteEventPayload(result = result, turns = turns))
            } finally {
                instance.close()
            }
        }
    }

    private suspend fun send(session: ServerSSESession, event: String, payload: Any) {
        val data = when (payload) {
            is StatusEventPayload -> json.encodeToString(payload)
            is TokenEventPayload -> json.encodeToString(payload)
            is ToolCallEventPayload -> json.encodeToString(payload)
            is ToolResultEventPayload -> json.encodeToString(payload)
            is ErrorEventPayload -> json.encodeToString(payload)
            is CompleteEventPayload -> json.encodeToString(payload)
            else -> error("Unsupported SSE payload: ${payload::class.qualifiedName}")
        }
        session.send(ServerSentEvent(data = data, event = event))
    }
}
