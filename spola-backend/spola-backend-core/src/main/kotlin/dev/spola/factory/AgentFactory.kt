package dev.spola.factory

import dev.spola.AgentRunObserver
import dev.spola.SpolaAgent
import dev.spola.SpolaConfig
import dev.spola.SpolaInstance
import dev.spola.SpolaTracer
import dev.spola.SpolaTracerObserver
import dev.spola.ToolRegistry
import dev.spola.agent.AgentDefinition
import dev.spola.checkpoint.CheckpointManager
import dev.spola.memory.MemoryStore
import dev.spola.metrics.SpolaMetrics
import dev.spola.metrics.MetricsObserver
import dev.spola.persona.PersonaLoader
import dev.spola.plugin.PluginLoader
import dev.spola.scheduler.SpolaJobStore
import dev.spola.scheduler.SqliteSpolaJobStore
import dev.spola.skill.SkillCatalog
import dev.spola.skill.SkillIndexer
import dev.spola.skill.SkillRepository
import dev.tramai.core.provider.ModelProvider
import java.nio.file.Path

/**
 * Creates fully-configured [SpolaAgent] and [SpolaInstance] objects.
 */
object AgentFactory {

    /**
     * Create a [SpolaInstance] from a [SpolaConfig], delegating tool registry
     * building to [ToolRegistryFactory] and provider resolution to [ProviderResolver].
     */
    suspend fun create(
        config: SpolaConfig,
        memoryStore: MemoryStore,
        provider: ModelProvider?,
        effectiveModel: String?,
        observer: AgentRunObserver?,
        toolRegistry: ToolRegistry,
        checkpointManager: CheckpointManager,
        schedulerStore: dev.spola.scheduler.SpolaJobStore?,
    ): SpolaInstance {
        // Set up OpenTelemetry tracer
        val spolaTracer = SpolaTracer(
            otelEnabled = config.metrics.otelEnabled,
            otelEndpoint = config.metrics.otelEndpoint,
            otelServiceName = config.otelServiceName,
        )

        // Set up Prometheus metrics
        val spolaMetrics = SpolaMetrics(
            isEnabled = config.metrics.metricsEnabled,
        )

        // Wrap observer with tracing
        var combinedObserver = observer

        // Apply metrics observer if enabled
        if (config.metrics.metricsEnabled) {
            combinedObserver = MetricsObserver(
                metrics = spolaMetrics,
                next = combinedObserver,
            )
        }

        // Apply tracing observer (outermost, wraps everything)
        if (spolaTracer.isActive || config.metrics.otelEnabled) {
            combinedObserver = SpolaTracerObserver(tracer = spolaTracer, config = config, next = combinedObserver)
        }

        // Resolve LLM provider
        val (llmProvider, modelName) = provider?.let { it to (effectiveModel ?: config.provider.defaultModel) }
            ?: ProviderResolver.resolveFromConfig(config)

        // Load persona
        var persona = PersonaLoader.load(
            explicitPath = config.agent.personaPath,
            workingDirectory = config.workingDirectory,
        )

        // Inject skill catalog if enabled
        if (config.skillsEnabled) {
            val skillsDir = Path.of(config.skillsDir)
            SkillRepository(config.database.skillsDbPath).use { repository ->
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
        val agent = SpolaAgent(
            provider = llmProvider,
            effectiveModel = modelName,
            toolRegistry = toolRegistry,
            config = config,
            checkpointManager = checkpointManager,
        )

        return SpolaInstance(
            agent = agent,
            memoryStore = memoryStore,
            toolRegistry = toolRegistry,
            persona = persona,
            config = config,
            schedulerStore = schedulerStore,
            observer = combinedObserver,
            spolaTracer = spolaTracer,
            spolaMetrics = spolaMetrics,
            jvmIndexCoordinator = null, // set by caller if needed
        )
    }

    /**
     * Create a [SpolaInstance] from an [AgentDefinition], delegating to
     * [ToolRegistryFactory] and [ProviderResolver].
     */
    suspend fun createFromAgentDefinition(
        agentDef: AgentDefinition,
        config: SpolaConfig,
        memoryStore: MemoryStore,
        observer: AgentRunObserver?,
        toolRegistry: ToolRegistry,
        effectiveProviderName: String,
        effectiveModel: String,
    ): SpolaInstance {
        val permissionEnforcer = dev.spola.agent.PermissionEnforcer(agentDef)

        val checkpointStore = dev.spola.checkpoint.CheckpointStore(config.database.checkpointDbPath)
        val checkpointManager = CheckpointManager(checkpointStore)

        // Set up observability
        val spolaTracer = SpolaTracer(
            otelEnabled = config.metrics.otelEnabled,
            otelEndpoint = config.metrics.otelEndpoint,
            otelServiceName = config.otelServiceName,
        )
        val spolaMetrics = SpolaMetrics(isEnabled = config.metrics.metricsEnabled)

        // Wrap observer
        var combinedObserver = observer
        if (config.metrics.metricsEnabled) {
            combinedObserver = MetricsObserver(
                metrics = spolaMetrics,
                next = combinedObserver,
            )
        }
        if (spolaTracer.isActive || config.metrics.otelEnabled) {
            combinedObserver = SpolaTracerObserver(tracer = spolaTracer, config = config, next = combinedObserver)
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
            agent = config.agent.copy(maxTurns = agentDef.maxTurnsOverride ?: config.agent.maxTurns),
            temperature = agentDef.temperature ?: config.temperature,
            maxTokens = agentDef.maxTokens ?: config.maxTokens,
        )

        // Create agent with overridden persona (system prompt)
        val agent = SpolaAgent(
            provider = llmProvider,
            effectiveModel = modelName,
            toolRegistry = toolRegistry,
            config = agentConfig,
            checkpointManager = checkpointManager,
        )

        val schedulerStore = config.database.schedulerDbPath
            .takeIf { it.isNotBlank() }
            ?.let(::SqliteSpolaJobStore)

        return SpolaInstance(
            agent = agent,
            memoryStore = memoryStore,
            toolRegistry = toolRegistry,
            persona = agentDef.systemPrompt,
            config = agentConfig,
            schedulerStore = schedulerStore,
            observer = combinedObserver,
            spolaTracer = spolaTracer,
            spolaMetrics = spolaMetrics,
            jvmIndexCoordinator = null,
        )
    }
}
