package dev.spola

import dev.spola.agent.AgentDefinition
import dev.spola.agent.ProviderStore
import dev.spola.checkpoint.CheckpointManager
import dev.spola.checkpoint.CheckpointStore
import dev.spola.factory.AgentFactory
import dev.spola.factory.ProviderResolver
import dev.spola.factory.ToolRegistryFactory
import dev.spola.factory.WorkflowFactory
import dev.spola.jvm.JvmIndexCoordinator
import dev.spola.jvm.SqliteJvmProjectIndex
import dev.spola.memory.MemoryStore
import dev.spola.memory.SqliteMemoryStore
import dev.spola.metrics.GolemMetrics
import dev.spola.scheduler.GolemJobStore
import dev.spola.scheduler.SqliteGolemJobStore
import dev.tramai.core.provider.ModelProvider
import dev.tramai.orchestration.ExternalStepExecutorResolver
import dev.tramai.orchestration.NoOpExternalStepExecutorResolver
import dev.tramai.orchestration.StopPolicy
import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.WorkflowBuilder
import dev.tramai.orchestration.WorkflowPersistence

/**
 * Thin orchestrator that delegates to focused factories in [dev.spola.factory].
 *
 * Public API is maintained for backward compatibility.
 */
object GolemFactory {

    /**
     * Build and configure a Golem agent.
     */
    suspend fun create(
        config: GolemConfig = GolemConfig(),
        memoryStore: MemoryStore? = null,
        provider: ModelProvider? = null,
        effectiveModel: String? = null,
        observer: AgentRunObserver? = null,
    ): GolemInstance {
        val store = memoryStore ?: SqliteMemoryStore(config.memoryDbPath)

        // Set up checkpoint store
        val checkpointStore = CheckpointStore(config.checkpointDbPath)
        val checkpointManager = CheckpointManager(checkpointStore)

        // Set up scheduler
        val schedulerStore = config.schedulerDbPath
            .takeIf { it.isNotBlank() }
            ?.let(::SqliteGolemJobStore)

        // Build tool registry via factory
        val jvmIndex = SqliteJvmProjectIndex(config.jvmIndexDbPath)
        val jvmIndexCoordinator = JvmIndexCoordinator(autoRefresh = config.jvmIndexAutoRefresh) {
            config.workingDirectory
        }
        val golemMetrics = GolemMetrics(isEnabled = config.metricsEnabled)
        val toolRegistry = ToolRegistryFactory.buildDefaultToolRegistry(
            config = config,
            memoryStore = store,
            schedulerStore = schedulerStore,
            checkpointManager = checkpointManager,
            jvmIndex = jvmIndex,
            jvmIndexCoordinator = jvmIndexCoordinator,
            golemMetrics = golemMetrics,
            model = effectiveModel ?: config.model,
        )

        // Delegate to AgentFactory
        return AgentFactory.create(
            config = config,
            memoryStore = store,
            provider = provider,
            effectiveModel = effectiveModel,
            observer = observer,
            toolRegistry = toolRegistry,
            checkpointManager = checkpointManager,
            schedulerStore = schedulerStore,
        ).copy(jvmIndexCoordinator = jvmIndexCoordinator)
    }

    /**
     * Create a Golem agent from an [AgentDefinition] rather than raw config.
     */
    suspend fun createFromAgentDefinition(
        agentDef: AgentDefinition,
        config: GolemConfig = GolemConfig(),
        memoryStore: MemoryStore? = null,
        observer: AgentRunObserver? = null,
    ): GolemInstance {
        val store = memoryStore ?: SqliteMemoryStore(config.memoryDbPath)
        val effectiveModel = agentDef.preferredModel
        val effectiveProviderName = agentDef.preferredProvider
        val effectiveNamespace = agentDef.memoryNamespace ?: if (agentDef.memoryScope == "agent") agentDef.id else null

        val effectiveMemoryStore = if (agentDef.memoryScope == "none") {
            dev.spola.memory.NoopMemoryStore()
        } else {
            store
        }

        // Build tool registry via factory
        val permissionEnforcer = dev.spola.agent.PermissionEnforcer(agentDef)
        val toolRegistry = ToolRegistryFactory.buildAgentToolRegistry(
            config = config,
            permissionEnforcer = permissionEnforcer,
            memoryStore = store,
            agentDef = agentDef,
        )

        // Delegate to AgentFactory
        return AgentFactory.createFromAgentDefinition(
            agentDef = agentDef,
            config = config,
            memoryStore = effectiveMemoryStore,
            observer = observer,
            toolRegistry = toolRegistry,
            effectiveProviderName = effectiveProviderName,
            effectiveModel = effectiveModel,
        )
    }

    /**
     * Configure [WorkflowPersistence] for checkpointing workflow execution.
     */
    fun configurePersistence(
        checkpointDir: String = System.getProperty("java.io.tmpdir") + "/golem-workflows",
        stateCodec: dev.tramai.orchestration.WorkflowStateCodec<dev.spola.workflow.GolemState> = dev.spola.workflow.GolemWorkflowStateCodec(),
        leaseStore: dev.tramai.orchestration.WorkflowLeaseStore? = null,
        leasePolicy: dev.tramai.orchestration.WorkflowLeasePolicy? = null,
        delayWakeupScheduler: dev.tramai.orchestration.WorkflowDelayWakeupScheduler? = null,
        deleteCheckpointOnCompletion: Boolean = true,
    ): WorkflowPersistence<dev.spola.workflow.GolemState> {
        return WorkflowFactory.configurePersistence(
            checkpointDir = checkpointDir,
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
        noinline workflow: WorkflowBuilder<dev.spola.workflow.GolemState>.() -> Unit,
        noinline resultSelector: (dev.spola.workflow.GolemState) -> R,
    ): Workflow<dev.spola.workflow.GolemState, R> {
        return WorkflowFactory.createWorkflow(
            name = name,
            definitionVersion = definitionVersion,
            stopPolicy = stopPolicy,
            externalStepExecutorResolver = externalStepExecutorResolver,
            workflow = workflow,
            resultSelector = resultSelector,
        )
    }

    /**
     * Build and execute a workflow in a single call.
     */
    suspend inline fun <reified R> runWorkflow(
        name: String,
        initialState: dev.spola.workflow.GolemState,
        agentObserver: AgentRunObserver? = null,
        config: GolemConfig = GolemConfig(),
        stopPolicy: StopPolicy = StopPolicy(),
        persistence: WorkflowPersistence<dev.spola.workflow.GolemState>? = null,
        externalStepExecutorResolver: ExternalStepExecutorResolver = NoOpExternalStepExecutorResolver,
        noinline workflow: WorkflowBuilder<dev.spola.workflow.GolemState>.() -> Unit,
        noinline resultSelector: (dev.spola.workflow.GolemState) -> R,
    ): R {
        return WorkflowFactory.runWorkflow(
            name = name,
            initialState = initialState,
            agentObserver = agentObserver,
            config = config,
            stopPolicy = stopPolicy,
            persistence = persistence,
            externalStepExecutorResolver = externalStepExecutorResolver,
            workflow = workflow,
            resultSelector = resultSelector,
        )
    }
}
