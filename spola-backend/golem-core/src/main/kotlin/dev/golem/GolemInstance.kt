package dev.spola

import dev.spola.agent.ProviderStore
import dev.spola.factory.ProviderResolver
import dev.spola.jvm.JvmIndexCoordinator
import dev.spola.memory.MemoryStore
import dev.spola.metrics.GolemMetrics
import dev.spola.scheduler.GolemJobStore
import dev.spola.plugin.PluginLoader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A fully-configured Golem agent instance, ready to run.
 */
data class GolemInstance(
    val agent: GolemAgent,
    val memoryStore: MemoryStore,
    val toolRegistry: ToolRegistry,
    val persona: String,
    var config: GolemConfig,
    val schedulerStore: GolemJobStore? = null,
    val observer: AgentRunObserver? = null,
    val golemTracer: GolemTracer? = null,
    val golemMetrics: GolemMetrics? = null,
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
            toolRegistry.rebuildModelDependentTools(resolvedModel, existingCm, golemMetrics)
        }
        config = config.copy(provider = providerName, model = resolvedModel)
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
        golemTracer?.close()
    }
}
