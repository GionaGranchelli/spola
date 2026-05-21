package dev.spola.workflow

import dev.spola.AgentRunObserver
import dev.spola.SpolaTracer
import dev.spola.metrics.SpolaMetrics
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Bridges TramAI's WorkflowObserver to Spola's existing observability:
 * - AgentRunObserver (real-time SSE events)
 * - Prometheus metrics (SpolaMetrics)
 * - OpenTelemetry tracing (SpolaTracer)
 */
class SpolaWorkflowObserver(
    private val agentObserver: AgentRunObserver? = null,
    private val metrics: SpolaMetrics? = null,
    private val tracer: SpolaTracer? = null,
    private val chatService: WorkflowChatService? = null,
    private val executionId: String? = null,
    private val sessionId: String? = null,
) : WorkflowObserver {
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statusChannel = Channel<Pair<String, String?>>(Channel.UNLIMITED)
    private val errorChannel = Channel<Throwable>(Channel.UNLIMITED)

    private val workflowStartTimes = ConcurrentHashMap<String, Instant>()
    private val stepStartTimes = ConcurrentHashMap<String, Instant>()

    private fun stepKey(name: String, context: WorkflowContext): String = "${context.workflowId}:$name"

    init {
        observerScope.launch {
            while (true) {
                select<Unit> {
                    statusChannel.onReceive { (status, message) ->
                        agentObserver?.onStatus(status, message)
                    }
                    errorChannel.onReceive { error ->
                        agentObserver?.onError(error)
                    }
                }
            }
        }
    }

    override fun onWorkflowStarted(workflowName: String, context: WorkflowContext) {
        workflowStartTimes[context.workflowId] = Instant.now()
        tracer?.startRootSpan()
        statusChannel.trySend("workflow_started" to "Workflow '$workflowName' started (id=${context.workflowId})")
        if (chatService != null && executionId != null && sessionId != null) {
            observerScope.launch {
                chatService.onWorkflowStarted(executionId, workflowName, sessionId)
            }
        }
    }

    override fun onWorkflowEvent(
        workflowName: String,
        name: String,
        attributes: Map<String, Any?>,
        context: WorkflowContext,
    ) {
        statusChannel.trySend(name to (attributes["message"] as? String))
    }

    override fun onStepStarted(workflowName: String, stepName: String, context: WorkflowContext) {
        stepStartTimes[stepKey(stepName, context)] = Instant.now()
        statusChannel.trySend("step_started" to "Step '$stepName' started")
    }

    override fun onStepCompleted(workflowName: String, stepName: String, context: WorkflowContext) {
        val durationMs = durationFor(stepName, context)
        metrics?.recordToolCall(
            tool = "workflow:$workflowName:$stepName",
            status = "success",
            durationSeconds = durationMs / 1000.0,
        )
        statusChannel.trySend("step_completed" to "Step '$stepName' completed (${durationMs}ms)")
    }

    override fun onStepFailed(workflowName: String, stepName: String, error: Throwable, context: WorkflowContext) {
        val durationMs = durationFor(stepName, context)
        metrics?.recordToolCall(
            tool = "workflow:$workflowName:$stepName",
            status = "error",
            durationSeconds = durationMs / 1000.0,
        )
        errorChannel.trySend(error)
    }

    override fun onWorkflowCompleted(workflowName: String, context: WorkflowContext) {
        val durationSeconds = workflowDurationSeconds(context)
        metrics?.recordAgentRun(status = "success", durationSeconds = durationSeconds)
        statusChannel.trySend("workflow_completed" to "Workflow completed in ${(durationSeconds * 1000).toLong()}ms")
        tracer?.endRootSpan()
    }

    override fun onWorkflowFailed(workflowName: String, error: Throwable, context: WorkflowContext) {
        val durationSeconds = workflowDurationSeconds(context)
        metrics?.recordAgentRun(status = "error", durationSeconds = durationSeconds)
        errorChannel.trySend(error)
        if (chatService != null && executionId != null && sessionId != null) {
            observerScope.launch {
                chatService.onWorkflowFailed(
                    executionId = executionId,
                    workflowName = workflowName,
                    sessionId = sessionId,
                    error = error.message ?: error::class.simpleName ?: "Workflow execution failed",
                )
            }
        }
        tracer?.failRootSpan(error)
    }

    private fun durationFor(stepName: String, context: WorkflowContext): Long {
        val start = stepStartTimes.remove(stepKey(stepName, context)) ?: return 0L
        return Duration.between(start, Instant.now()).toMillis()
    }

    private fun workflowDurationSeconds(context: WorkflowContext): Double {
        val start = workflowStartTimes.remove(context.workflowId) ?: return 0.0
        return Duration.between(start, Instant.now()).toMillis() / 1000.0
    }
}
