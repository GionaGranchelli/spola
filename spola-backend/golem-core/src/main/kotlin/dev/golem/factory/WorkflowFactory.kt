package dev.spola.factory

import dev.spola.AgentRunObserver
import dev.spola.GolemConfig
import dev.spola.metrics.GolemMetrics
import dev.spola.GolemTracer
import dev.spola.workflow.GolemState
import dev.spola.workflow.WorkflowChatService
import dev.spola.workflow.GolemWorkflowObserver
import dev.spola.workflow.GolemWorkflowStateCodec
import dev.spola.workflow.WorkflowTemplate
import dev.tramai.orchestration.ExternalStepExecutorResolver
import dev.tramai.orchestration.FileWorkflowCheckpointStore
import dev.tramai.orchestration.NoOpExternalStepExecutorResolver
import dev.tramai.orchestration.StopPolicy
import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.WorkflowBuilder
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowDelayWakeupScheduler
import dev.tramai.orchestration.WorkflowLeasePolicy
import dev.tramai.orchestration.WorkflowLeaseStore
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.orchestration.workflow
import java.nio.file.Paths
import java.time.Clock

/**
 * Creates and runs TramAI workflows.
 */
object WorkflowFactory {

    /**
     * Configure [WorkflowPersistence] for checkpointing workflow execution.
     */
    fun configurePersistence(
        checkpointDir: String = System.getProperty("java.io.tmpdir") + "/golem-workflows",
        stateCodec: WorkflowStateCodec<GolemState> = GolemWorkflowStateCodec(),
        leaseStore: WorkflowLeaseStore? = null,
        leasePolicy: WorkflowLeasePolicy? = null,
        delayWakeupScheduler: WorkflowDelayWakeupScheduler? = null,
        deleteCheckpointOnCompletion: Boolean = true,
    ): WorkflowPersistence<GolemState> {
        val checkpointStore = FileWorkflowCheckpointStore(Paths.get(checkpointDir))
        return WorkflowPersistence(
            checkpointStore = checkpointStore,
            stateCodec = stateCodec,
            leaseStore = leaseStore,
            leasePolicy = leasePolicy,
            delayWakeupScheduler = delayWakeupScheduler,
            deleteCheckpointOnCompletion = deleteCheckpointOnCompletion,
        )
    }

    /**
     * Build a type-safe, runnable [Workflow] from a builder block.
     */
    inline fun <reified R> createWorkflow(
        name: String,
        definitionVersion: String = "1",
        stopPolicy: StopPolicy = StopPolicy(),
        externalStepExecutorResolver: ExternalStepExecutorResolver = NoOpExternalStepExecutorResolver,
        noinline workflow: WorkflowBuilder<GolemState>.() -> Unit,
        noinline resultSelector: (GolemState) -> R,
    ): Workflow<GolemState, R> {
        return workflow<GolemState>(name, definitionVersion) {
            workflow()
        }.build(
            stopPolicy = stopPolicy,
            clock = Clock.systemUTC(),
            externalStepExecutorResolver = externalStepExecutorResolver,
            resultSelector = resultSelector,
        )
    }

    fun createWorkflowFromTemplate(
        template: WorkflowTemplate,
        config: GolemConfig,
        goal: String,
    ): Workflow<GolemState, String> = template.build(config, goal, "{}")

    /**
     * Build and execute a workflow in a single call.
     */
    suspend inline fun <reified R> runWorkflow(
        name: String,
        initialState: GolemState,
        agentObserver: AgentRunObserver? = null,
        config: GolemConfig = GolemConfig(),
        stopPolicy: StopPolicy = StopPolicy(),
        persistence: WorkflowPersistence<GolemState>? = null,
        externalStepExecutorResolver: ExternalStepExecutorResolver = NoOpExternalStepExecutorResolver,
        noinline workflow: WorkflowBuilder<GolemState>.() -> Unit,
        noinline resultSelector: (GolemState) -> R,
    ): R {
        val wf = createWorkflow(
            name = name,
            stopPolicy = stopPolicy,
            externalStepExecutorResolver = externalStepExecutorResolver,
            workflow = workflow,
            resultSelector = resultSelector,
        )
        val metrics = GolemMetrics(isEnabled = config.metricsEnabled)
        val tracer = GolemTracer(
            otelEnabled = config.otelEnabled,
            otelEndpoint = config.otelEndpoint,
            otelServiceName = config.otelServiceName,
        )
        val observer = GolemWorkflowObserver(
            agentObserver = agentObserver,
            metrics = metrics,
            tracer = tracer,
        )
        return wf.run(
            initialState = initialState,
            context = WorkflowContext(),
            observer = observer,
            persistence = persistence,
        )
    }

    suspend fun runWorkflow(
        workflow: Workflow<GolemState, String>,
        initialState: GolemState,
        agentObserver: AgentRunObserver? = null,
        config: GolemConfig = GolemConfig(),
        persistence: WorkflowPersistence<GolemState>? = null,
        chatService: WorkflowChatService? = null,
        executionId: String? = null,
        sessionId: String? = null,
    ): String {
        val metrics = GolemMetrics(isEnabled = config.metricsEnabled)
        val tracer = GolemTracer(
            otelEnabled = config.otelEnabled,
            otelEndpoint = config.otelEndpoint,
            otelServiceName = config.otelServiceName,
        )
        val observer = GolemWorkflowObserver(
            agentObserver = agentObserver,
            metrics = metrics,
            tracer = tracer,
            chatService = chatService,
            executionId = executionId,
            sessionId = sessionId,
        )
        return workflow.run(
            initialState = initialState,
            context = WorkflowContext(),
            observer = observer,
            persistence = persistence,
        )
    }
}
