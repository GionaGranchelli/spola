package dev.spola.api

import dev.spola.AgentRunObserver
import dev.spola.AssistantMessage
import dev.spola.ChatMessage
import dev.spola.ToolCall
import dev.spola.ToolResult
import dev.spola.workflow.WorkflowExecutionService
import dev.spola.workflow.WorkflowExecutionStatus
import dev.spola.workflow.SpolaWorkflowObserver
import dev.tramai.orchestration.WorkflowObserver
import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.spola.util.jsonValueToElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class StreamHandler(
    private val agentRunHandler: AgentRunHandler,
    private val json: Json = Json {
        explicitNulls = false
    },
) {
    private val activeSessions = ConcurrentHashMap<ServerSSESession, Job>()
    private val connectionCount = AtomicInteger(0)

    /**
     * SSE heartbeat interval — 30 seconds as required.
     */
    private val keepaliveIntervalMs = 30_000L

    suspend fun stream(
        session: ServerSSESession,
        request: AgentRunRequest,
        preloadedConversation: List<ChatMessage>? = null,
        pinnedMessageIds: Set<Int> = emptySet(),
        onComplete: (suspend (AgentRunHandler.CompletedRun) -> Unit)? = null,
    ) {
        val connId = connectionCount.incrementAndGet()
        println("[SSE_$connId] Connection opened for goal=${request.goal.take(80)}")
        activeSessions[session] = launchKeepalive(session)
        try {
            agentRunHandler.trackRun {
                val instance = agentRunHandler.createInstance(request)
                println("[SSE_$connId] Agent instance created")
                try {
                    send(session, "status", StatusEventPayload(status = "started", message = "Creating agent instance"))
                    val persona = agentRunHandler.enrichPersonaWithMemory(instance, request.persona ?: instance.persona)
                    var llmTurn = 0
                    var cumulativeInputTokens = 0L
                    var cumulativeOutputTokens = 0L
                    var cumulativeThinkingTokens = 0L
                    val observer = object : AgentRunObserver {
                        override suspend fun onStatus(status: String, message: String?) {
                            println("[SSE_$connId] status=$status${if (message != null) " msg=$message" else ""}")
                            send(session, "status", StatusEventPayload(status = status, message = message))
                        }

                        override suspend fun onToken(text: String) {
                            send(session, "token", TokenEventPayload(text = text))
                        }

                        override suspend fun onToolCall(toolCall: ToolCall) {
                            println("[SSE_$connId] tool_call=${toolCall.name} id=${toolCall.id.take(16)}")
                            send(
                                session,
                                "tool_call",
                                ToolCallEventPayload(
                                    id = toolCall.id,
                                    name = toolCall.name,
                                    arguments = toolCall.arguments.mapValues { jsonValueToElement(it.value) },
                                ),
                            )
                        }

                        override suspend fun onToolResult(toolCall: ToolCall, result: ToolResult) {
                            println("[SSE_$connId] tool_result=${toolCall.name} success=${result.success} output_len=${result.output.length}")
                            send(session, "tool_result", toolResultEventPayload(toolCall, result))
                        }

                        override suspend fun onLlmCall(model: String, provider: String) {
                            llmTurn++
                        }

                        override suspend fun onLlmResult(
                            model: String,
                            provider: String,
                            inputTokens: Int?,
                            outputTokens: Int?,
                            thinkingTokens: Int?,
                        ) {
                            val input = inputTokens ?: 0
                            val output = outputTokens ?: 0
                            val thinking = thinkingTokens ?: 0
                            cumulativeInputTokens += input.toLong()
                            cumulativeOutputTokens += output.toLong()
                            cumulativeThinkingTokens += thinking.toLong()
                            send(
                                session,
                                "token_usage",
                                TokenUsageEventPayload(
                                    turn = llmTurn,
                                    input_tokens = input,
                                    output_tokens = output,
                                    thinking_tokens = thinking,
                                    cumulative_input = cumulativeInputTokens,
                                    cumulative_output = cumulativeOutputTokens,
                                    cumulative_thinking = cumulativeThinkingTokens,
                                ),
                            )
                        }

                        override suspend fun onError(error: Throwable) {
                            println("[SSE_$connId] ERROR: ${error.message ?: error::class.simpleName}")
                            send(
                                session,
                                "error",
                                ErrorEventPayload(error.message ?: error::class.simpleName ?: "unknown error"),
                            )
                        }
                    }

                    val transcript = preloadedConversation?.toMutableList()
                    val result = agentRunHandler.runAgent(
                        agent = instance.agent,
                        persona = persona,
                        goal = request.goal,
                        preloadedConversation = transcript,
                        pinnedMessageIds = pinnedMessageIds,
                        observer = observer,
                    )
                    val conversation = transcript ?: instance.agent.getConversation()
                    val turns = conversation.filterIsInstance<AssistantMessage>().size
                    println("[SSE_$connId] Agent run completed: result_len=${result.length} turns=$turns total_messages=${conversation.size}")
                    onComplete?.invoke(
                        AgentRunHandler.CompletedRun(
                            result = result,
                            turns = turns,
                            conversation = conversation,
                        ),
                    )
                    send(session, "complete", CompleteEventPayload(result = result, turns = turns))
                    println("[SSE_$connId] complete event sent, delaying 500ms before close")
                    // Give the client time to process the complete event before connection close
                    kotlinx.coroutines.delay(500)
                    println("[SSE_$connId] delay done, closing connection")
                } finally {
                    instance.close()
                    println("[SSE_$connId] instance closed")
                }
            }
        } finally {
            activeSessions.remove(session)?.cancel()
            println("[SSE_$connId] connection fully closed (active sessions remaining: ${activeSessions.size})")
        }
    }

    /**
     * Stream workflow execution events via SSE.
     *
     * Subscribes to [SpolaWorkflowObserver] events and maps them to structured
     * SSE messages: step_status, step_token, step_complete, gate_pending, error, complete.
     *
     * A 30-second keepalive ping keeps the connection alive, and disconnect
     * handling cleans up stale sessions.
     */
    suspend fun streamWorkflow(
        session: ServerSSESession,
        executionId: String,
        workflowExecutionService: WorkflowExecutionService,
    ) {
        activeSessions[session] = launchKeepalive(session)
        try {
            val record = workflowExecutionService.getExecution(executionId)
            if (record == null) {
                send(session, "error", ErrorEventPayload("Execution not found: $executionId"))
                return
            }

            if (record.status == WorkflowExecutionStatus.QUEUED) {
                send(
                    session,
                    "step_status",
                    WorkflowStreamEvent(
                        type = "step_status",
                        data = mapOf("message" to "Enqueued and starting execution"),
                    ),
                )
            }

            // Create a WorkflowObserver that bridges to SSE
            val workflowObserver = object : WorkflowObserver {
                override fun onWorkflowStarted(workflowName: String, context: dev.tramai.orchestration.WorkflowContext) {
                    sendBlocking(
                        session,
                        "step_status",
                        WorkflowStreamEvent(
                            type = "step_status",
                            data = mapOf(
                                "status" to "workflow_started",
                                "workflow" to workflowName,
                                "workflowId" to context.workflowId,
                            ),
                        ),
                    )
                }

                override fun onWorkflowEvent(
                    workflowName: String,
                    name: String,
                    attributes: Map<String, Any?>,
                    context: dev.tramai.orchestration.WorkflowContext,
                ) {
                    val message = attributes["message"] as? String ?: ""
                    sendBlocking(
                        session,
                        name,
                        WorkflowStreamEvent(
                            type = name,
                            data = mapOf(
                                "workflow" to workflowName,
                                "message" to message,
                            ),
                        ),
                    )
                }

                override fun onStepStarted(workflowName: String, stepName: String, context: dev.tramai.orchestration.WorkflowContext) {
                    sendBlocking(
                        session,
                        "step_status",
                        WorkflowStreamEvent(
                            type = "step_status",
                            data = mapOf(
                                "step" to stepName,
                                "status" to "started",
                                "workflow" to workflowName,
                                "workflowId" to context.workflowId,
                            ),
                        ),
                    )
                }

                override fun onStepCompleted(workflowName: String, stepName: String, context: dev.tramai.orchestration.WorkflowContext) {
                    sendBlocking(
                        session,
                        "step_complete",
                        WorkflowStreamEvent(
                            type = "step_complete",
                            data = mapOf(
                                "step" to stepName,
                                "workflow" to workflowName,
                                "workflowId" to context.workflowId,
                            ),
                        ),
                    )
                }

                override fun onStepFailed(workflowName: String, stepName: String, error: Throwable, context: dev.tramai.orchestration.WorkflowContext) {
                    sendBlocking(
                        session,
                        "error",
                        WorkflowStreamEvent(
                            type = "error",
                            data = mapOf(
                                "step" to stepName,
                                "error" to (error.message ?: error::class.simpleName ?: "unknown error"),
                                "workflow" to workflowName,
                            ),
                        ),
                    )
                }

                override fun onWorkflowCompleted(workflowName: String, context: dev.tramai.orchestration.WorkflowContext) {
                    sendBlocking(
                        session,
                        "complete",
                        WorkflowStreamEvent(
                            type = "complete",
                            data = mapOf(
                                "workflow" to workflowName,
                                "status" to "completed",
                                "workflowId" to context.workflowId,
                            ),
                        ),
                    )
                }

                override fun onWorkflowFailed(workflowName: String, error: Throwable, context: dev.tramai.orchestration.WorkflowContext) {
                    sendBlocking(
                        session,
                        "error",
                        WorkflowStreamEvent(
                            type = "error",
                            data = mapOf(
                                "error" to (error.message ?: error::class.simpleName ?: "unknown error"),
                                "workflow" to workflowName,
                            ),
                        ),
                    )
                }
            }

            send(
                session,
                "step_status",
                WorkflowStreamEvent(
                    type = "step_status",
                    data = mapOf("status" to "connected", "executionId" to executionId),
                ),
            )

            // If already RUNNING, just subscribe (observer won't receive past events).
            // If QUEUED, run it.
            if (record.status == WorkflowExecutionStatus.QUEUED) {
                try {
                    val result = workflowExecutionService.runExecution(record.copy(), sseObserver = workflowObserver)
                    send(
                        session,
                        "complete",
                        WorkflowStreamEvent(
                            type = "complete",
                            data = mapOf("result" to result, "executionId" to executionId),
                        ),
                    )
                } catch (e: dev.tramai.orchestration.WorkflowGateRejectedException) {
                    send(
                        session,
                        "gate_pending",
                        WorkflowStreamEvent(
                            type = "gate_pending",
                            data = mapOf(
                                "executionId" to executionId,
                                "message" to "Workflow is waiting for approval",
                            ),
                        ),
                    )
                } catch (t: Throwable) {
                    send(
                        session,
                        "error",
                        WorkflowStreamEvent(
                            type = "error",
                            data = mapOf(
                                "error" to (t.message ?: t::class.simpleName ?: "workflow execution failed"),
                                "executionId" to executionId,
                            ),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            send(session, "error", ErrorEventPayload(e.message ?: "workflow stream error"))
        } finally {
            activeSessions.remove(session)?.cancel()
        }
    }

    /**
     * Launches a coroutine that sends an SSE comment (keepalive) every 30 seconds.
     */
    private fun launchKeepalive(session: ServerSSESession): Job {
        return kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            while (isActive) {
                delay(keepaliveIntervalMs)
                try {
                    session.send(ServerSentEvent(event = "keepalive", data = """{"type":"keepalive"}"""))
                } catch (_: Exception) {
                    // Session closed — exit keepalive loop
                    break
                }
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
            is TokenUsageEventPayload -> json.encodeToString(payload)
            is WorkflowStreamEvent -> json.encodeToString(payload)
            else -> error("Unsupported SSE payload: ${payload::class.qualifiedName}")
        }
        try {
            session.send(ServerSentEvent(data = data, event = event))
        } catch (e: Exception) {
            println("[SSE] send failed for event=$event: ${e.message ?: e::class.simpleName}")
            // Socket closed or disconnect — clean up silently
            activeSessions.remove(session)?.cancel()
        }
    }

    /**
     * Non-suspend version for use inside WorkflowObserver callbacks (which are not suspend).
     * Runs the send in a new coroutine.
     */
    private fun sendBlocking(session: ServerSSESession, event: String, payload: Any) {
        val data = when (payload) {
            is WorkflowStreamEvent -> json.encodeToString(payload)
            is ErrorEventPayload -> json.encodeToString(payload)
            else -> error("Unsupported SSE payload for sendBlocking: ${payload::class.qualifiedName}")
        }
        try {
            kotlinx.coroutines.runBlocking {
                session.send(ServerSentEvent(data = data, event = event))
            }
        } catch (_: Exception) {
            activeSessions.remove(session)?.cancel()
        }
    }

    /**
     * Clean up all stale sessions. Called on server shutdown.
     */
    fun shutdown() {
        activeSessions.forEach { (_, job) -> job.cancel() }
        activeSessions.clear()
    }
}
