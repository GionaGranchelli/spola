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
import dev.spola.metrics.SpolaMetrics
import dev.spola.scheduler.SpolaJobStore
import dev.spola.scheduler.SqliteSpolaJobStore
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
object SpolaFactory {

    /**
     * Build and configure a Spola agent.
     */
    suspend fun create(
        config: SpolaConfig = SpolaConfig(),
        memoryStore: MemoryStore? = null,
        provider: ModelProvider? = null,
        effectiveModel: String? = null,
        observer: AgentRunObserver? = null,
    ): SpolaInstance {
        val store = memoryStore ?: SqliteMemoryStore(config.database.memoryDbPath)

        // Set up checkpoint store
        val checkpointStore = CheckpointStore(config.database.checkpointDbPath)
        val checkpointManager = CheckpointManager(checkpointStore)

        // Set up scheduler
        val schedulerStore = config.database.schedulerDbPath
            .takeIf { it.isNotBlank() }
            ?.let(::SqliteSpolaJobStore)

        // Build tool registry via factory
        val jvmIndex = SqliteJvmProjectIndex(config.database.jvmIndexDbPath)
        val jvmIndexCoordinator = JvmIndexCoordinator(autoRefresh = config.jvmIndexAutoRefresh) {
            config.workingDirectory
        }
        val spolaMetrics = SpolaMetrics(isEnabled = config.metrics.metricsEnabled)
        val toolRegistry = ToolRegistryFactory.buildDefaultToolRegistry(
            config = config,
            memoryStore = store,
            schedulerStore = schedulerStore,
            checkpointManager = checkpointManager,
            jvmIndex = jvmIndex,
            jvmIndexCoordinator = jvmIndexCoordinator,
            spolaMetrics = spolaMetrics,
            model = effectiveModel ?: config.provider.defaultModel,
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
     * Create a Spola agent from an [AgentDefinition] rather than raw config.
     */
    suspend fun createFromAgentDefinition(
        agentDef: AgentDefinition,
        config: SpolaConfig = SpolaConfig(),
        memoryStore: MemoryStore? = null,
        observer: AgentRunObserver? = null,
    ): SpolaInstance {
        val store = memoryStore ?: SqliteMemoryStore(config.database.memoryDbPath)
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
        checkpointDir: String = System.getProperty("java.io.tmpdir") + "/spola-workflows",
        stateCodec: dev.tramai.orchestration.WorkflowStateCodec<dev.spola.workflow.SpolaState> = dev.spola.workflow.SpolaWorkflowStateCodec(),
        leaseStore: dev.tramai.orchestration.WorkflowLeaseStore? = null,
        leasePolicy: dev.tramai.orchestration.WorkflowLeasePolicy? = null,
        delayWakeupScheduler: dev.tramai.orchestration.WorkflowDelayWakeupScheduler? = null,
        deleteCheckpointOnCompletion: Boolean = true,
    ): WorkflowPersistence<dev.spola.workflow.SpolaState> {
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
        noinline workflow: WorkflowBuilder<dev.spola.workflow.SpolaState>.() -> Unit,
        noinline resultSelector: (dev.spola.workflow.SpolaState) -> R,
    ): Workflow<dev.spola.workflow.SpolaState, R> {
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
        initialState: dev.spola.workflow.SpolaState,
        agentObserver: AgentRunObserver? = null,
        config: SpolaConfig = SpolaConfig(),
        stopPolicy: StopPolicy = StopPolicy(),
        persistence: WorkflowPersistence<dev.spola.workflow.SpolaState>? = null,
        externalStepExecutorResolver: ExternalStepExecutorResolver = NoOpExternalStepExecutorResolver,
        noinline workflow: WorkflowBuilder<dev.spola.workflow.SpolaState>.() -> Unit,
        noinline resultSelector: (dev.spola.workflow.SpolaState) -> R,
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
