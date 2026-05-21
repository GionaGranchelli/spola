package dev.spola

import dev.spola.agent.ProviderStore
import dev.spola.factory.ProviderResolver
import dev.spola.jvm.JvmIndexCoordinator
import dev.spola.memory.MemoryStore
import dev.spola.metrics.SpolaMetrics
import dev.spola.scheduler.SpolaJobStore
import dev.spola.plugin.PluginLoader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A fully-configured Spola agent instance, ready to run.
 */
data class SpolaInstance(
    val agent: SpolaAgent,
    val memoryStore: MemoryStore,
    val toolRegistry: ToolRegistry,
    val persona: String,
    var config: SpolaConfig,
    val schedulerStore: SpolaJobStore? = null,
    val observer: AgentRunObserver? = null,
    val spolaTracer: SpolaTracer? = null,
    val spolaMetrics: SpolaMetrics? = null,
    val jvmIndexCoordinator: JvmIndexCoordinator? = null,
) {
    /** Run the agent with a user goal. */
    suspend fun run(goal: String): String = agent.run(persona, goal, observer, config.sessionId)

    suspend fun reconfigure(providerName: String, modelName: String) {
        val providerStore = ProviderStore.fromEnvironment()
        val providerConfig = providerStore.get(providerName)
        val (newProvider, resolvedModel) = ProviderResolver.resolveNamed(providerConfig, modelName)

        agent.reconfigure(newProvider, resolvedModel)
        val existingCm = agent.getCheckpointManager()
        if (existingCm != null) {
            toolRegistry.rebuildModelDependentTools(resolvedModel, existingCm, spolaMetrics)
        }
        config = config.copy(
            provider = config.provider.copy(
                defaultProvider = providerName,
                defaultModel = resolvedModel,
            ),
        )
    }

    /** Close resources. */
    fun close() {
        runBlocking {
            try {
                withTimeout(5000) {
                    PluginLoader.shutdownPlugins()
                }
            } catch (_: Exception) {
                // Timeout or error - continue with best-effort resource cleanup
            }
        }
        memoryStore.close()
        schedulerStore?.close()
        jvmIndexCoordinator?.close()
        spolaTracer?.close()
    }
}
