package dev.spola.factory

import dev.spola.AgentRunObserver
import dev.spola.SpolaConfig
import dev.spola.metrics.SpolaMetrics
import dev.spola.SpolaTracer
import dev.spola.workflow.SpolaState
import dev.spola.workflow.WorkflowChatService
import dev.spola.workflow.SpolaWorkflowObserver
import dev.spola.workflow.SpolaWorkflowStateCodec
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
        checkpointDir: String = System.getProperty("java.io.tmpdir") + "/spola-workflows",
        stateCodec: WorkflowStateCodec<SpolaState> = SpolaWorkflowStateCodec(),
        leaseStore: WorkflowLeaseStore? = null,
        leasePolicy: WorkflowLeasePolicy? = null,
        delayWakeupScheduler: WorkflowDelayWakeupScheduler? = null,
        deleteCheckpointOnCompletion: Boolean = true,
    ): WorkflowPersistence<SpolaState> {
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
        noinline workflow: WorkflowBuilder<SpolaState>.() -> Unit,
        noinline resultSelector: (SpolaState) -> R,
    ): Workflow<SpolaState, R> {
        return workflow<SpolaState>(name, definitionVersion) {
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
        config: SpolaConfig,
        goal: String,
    ): Workflow<SpolaState, String> = template.build(config, goal, "{}")

    /**
     * Build and execute a workflow in a single call.
     */
    suspend inline fun <reified R> runWorkflow(
        name: String,
        initialState: SpolaState,
        agentObserver: AgentRunObserver? = null,
        config: SpolaConfig = SpolaConfig(),
        stopPolicy: StopPolicy = StopPolicy(),
        persistence: WorkflowPersistence<SpolaState>? = null,
        externalStepExecutorResolver: ExternalStepExecutorResolver = NoOpExternalStepExecutorResolver,
        noinline workflow: WorkflowBuilder<SpolaState>.() -> Unit,
        noinline resultSelector: (SpolaState) -> R,
    ): R {
        val wf = createWorkflow(
            name = name,
            stopPolicy = stopPolicy,
            externalStepExecutorResolver = externalStepExecutorResolver,
            workflow = workflow,
            resultSelector = resultSelector,
        )
        val metrics = SpolaMetrics(isEnabled = config.metrics.metricsEnabled)
        val tracer = SpolaTracer(
            otelEnabled = config.metrics.otelEnabled,
            otelEndpoint = config.metrics.otelEndpoint,
            otelServiceName = config.otelServiceName,
        )
        val observer = SpolaWorkflowObserver(
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
        workflow: Workflow<SpolaState, String>,
        initialState: SpolaState,
        agentObserver: AgentRunObserver? = null,
        config: SpolaConfig = SpolaConfig(),
        persistence: WorkflowPersistence<SpolaState>? = null,
        chatService: WorkflowChatService? = null,
        executionId: String? = null,
        sessionId: String? = null,
    ): String {
        val metrics = SpolaMetrics(isEnabled = config.metrics.metricsEnabled)
        val tracer = SpolaTracer(
            otelEnabled = config.metrics.otelEnabled,
            otelEndpoint = config.metrics.otelEndpoint,
            otelServiceName = config.otelServiceName,
        )
        val observer = SpolaWorkflowObserver(
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
