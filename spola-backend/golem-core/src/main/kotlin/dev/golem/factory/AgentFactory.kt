package dev.spola.factory

import dev.spola.AgentRunObserver
import dev.spola.GolemAgent
import dev.spola.GolemConfig
import dev.spola.GolemInstance
import dev.spola.GolemTracer
import dev.spola.GolemTracerObserver
import dev.spola.ToolRegistry
import dev.spola.agent.AgentDefinition
import dev.spola.checkpoint.CheckpointManager
import dev.spola.memory.MemoryStore
import dev.spola.metrics.GolemMetrics
import dev.spola.metrics.MetricsObserver
import dev.spola.persona.PersonaLoader
import dev.spola.plugin.PluginLoader
import dev.spola.scheduler.GolemJobStore
import dev.spola.scheduler.SqliteGolemJobStore
import dev.spola.skill.SkillCatalog
import dev.spola.skill.SkillIndexer
import dev.spola.skill.SkillRepository
import dev.tramai.core.provider.ModelProvider
import java.nio.file.Path

/**
 * Creates fully-configured [GolemAgent] and [GolemInstance] objects.
 */
object AgentFactory {

    /**
     * Create a [GolemInstance] from a [GolemConfig], delegating tool registry
     * building to [ToolRegistryFactory] and provider resolution to [ProviderResolver].
     */
    suspend fun create(
        config: GolemConfig,
        memoryStore: MemoryStore,
        provider: ModelProvider?,
        effectiveModel: String?,
        observer: AgentRunObserver?,
        toolRegistry: ToolRegistry,
        checkpointManager: CheckpointManager,
        schedulerStore: dev.spola.scheduler.GolemJobStore?,
    ): GolemInstance {
        // Set up OpenTelemetry tracer
        val golemTracer = GolemTracer(
            otelEnabled = config.otelEnabled,
            otelEndpoint = config.otelEndpoint,
            otelServiceName = config.otelServiceName,
        )

        // Set up Prometheus metrics
        val golemMetrics = GolemMetrics(
            isEnabled = config.metricsEnabled,
        )

        // Wrap observer with tracing
        var combinedObserver = observer

        // Apply metrics observer if enabled
        if (config.metricsEnabled) {
            combinedObserver = MetricsObserver(
                metrics = golemMetrics,
                next = combinedObserver,
            )
        }

        // Apply tracing observer (outermost, wraps everything)
        if (golemTracer.isActive || config.otelEnabled) {
            combinedObserver = GolemTracerObserver(tracer = golemTracer, config = config, next = combinedObserver)
        }

        // Resolve LLM provider
        val (llmProvider, modelName) = provider?.let { it to (effectiveModel ?: config.model) }
            ?: ProviderResolver.resolveFromConfig(config)

        // Load persona
        var persona = PersonaLoader.load(
            explicitPath = config.personaPath,
            workingDirectory = config.workingDirectory,
        )

        // Inject skill catalog if enabled
        if (config.skillsEnabled) {
            val skillsDir = Path.of(config.skillsDir)
            SkillRepository(config.skillsDbPath).use { repository ->
                val indexer = SkillIndexer(skillsDir, repository)
                indexer.reindex()
                val catalog = SkillCatalog(skillsDir, repository)
                catalog.refresh()
                val skillBlock = catalog.formatCatalog()
                if (skillBlock.isNotBlank()) {
                    persona = "$persona\n\n$skillBlock"
                }
            }

            // Smart pre-injection requires the user goal, which is not available in this factory method.
        }

        // Create agent
        val agent = GolemAgent(
            provider = llmProvider,
            effectiveModel = modelName,
            toolRegistry = toolRegistry,
            config = config,
            checkpointManager = checkpointManager,
        )

        return GolemInstance(
            agent = agent,
            memoryStore = memoryStore,
            toolRegistry = toolRegistry,
            persona = persona,
            config = config,
            schedulerStore = schedulerStore,
            observer = combinedObserver,
            golemTracer = golemTracer,
            golemMetrics = golemMetrics,
            jvmIndexCoordinator = null, // set by caller if needed
        )
    }

    /**
     * Create a [GolemInstance] from an [AgentDefinition], delegating to
     * [ToolRegistryFactory] and [ProviderResolver].
     */
    suspend fun createFromAgentDefinition(
        agentDef: AgentDefinition,
        config: GolemConfig,
        memoryStore: MemoryStore,
        observer: AgentRunObserver?,
        toolRegistry: ToolRegistry,
        effectiveProviderName: String,
        effectiveModel: String,
    ): GolemInstance {
        val permissionEnforcer = dev.spola.agent.PermissionEnforcer(agentDef)

        val checkpointStore = dev.spola.checkpoint.CheckpointStore(config.checkpointDbPath)
        val checkpointManager = CheckpointManager(checkpointStore)

        // Set up observability
        val golemTracer = GolemTracer(
            otelEnabled = config.otelEnabled,
            otelEndpoint = config.otelEndpoint,
            otelServiceName = config.otelServiceName,
        )
        val golemMetrics = GolemMetrics(isEnabled = config.metricsEnabled)

        // Wrap observer
        var combinedObserver = observer
        if (config.metricsEnabled) {
            combinedObserver = MetricsObserver(
                metrics = golemMetrics,
                next = combinedObserver,
            )
        }
        if (golemTracer.isActive || config.otelEnabled) {
            combinedObserver = GolemTracerObserver(tracer = golemTracer, config = config, next = combinedObserver)
        }

        // Resolve LLM provider
        val providerStore = dev.spola.agent.ProviderStore.fromEnvironment()
        val (llmProvider, modelName) = try {
            ProviderResolver.resolveNamed(
                providerConfig = providerStore.get(effectiveProviderName),
                modelName = effectiveModel,
            )
        } catch (e: Exception) {
            if (agentDef.fallbackModel != null) {
                ProviderResolver.resolveNamed(
                    providerConfig = providerStore.get(agentDef.fallbackProvider ?: effectiveProviderName),
                    modelName = agentDef.fallbackModel,
                )
            } else {
                throw e
            }
        }

        // Build effective config with agent-specific overrides
        val agentConfig = config.copy(
            maxTurns = agentDef.maxTurnsOverride ?: config.maxTurns,
            temperature = agentDef.temperature ?: config.temperature,
            maxTokens = agentDef.maxTokens ?: config.maxTokens,
        )

        // Create agent with overridden persona (system prompt)
        val agent = GolemAgent(
            provider = llmProvider,
            effectiveModel = modelName,
            toolRegistry = toolRegistry,
            config = agentConfig,
            checkpointManager = checkpointManager,
        )

        val schedulerStore = config.schedulerDbPath
            .takeIf { it.isNotBlank() }
            ?.let(::SqliteGolemJobStore)

        return GolemInstance(
            agent = agent,
            memoryStore = memoryStore,
            toolRegistry = toolRegistry,
            persona = agentDef.systemPrompt,
            config = agentConfig,
            schedulerStore = schedulerStore,
            observer = combinedObserver,
            golemTracer = golemTracer,
            golemMetrics = golemMetrics,
            jvmIndexCoordinator = null,
        )
    }
}
