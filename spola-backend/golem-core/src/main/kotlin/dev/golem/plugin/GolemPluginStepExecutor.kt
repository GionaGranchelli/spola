package dev.spola.plugin

import dev.tramai.orchestration.ExternalStepExecutor
import dev.tramai.orchestration.ExternalStepExecutorFactory

/**
 * Factory that adapts a [GolemPlugin] into TramAI's [ExternalStepExecutorFactory]
 * so it can be registered in an [dev.tramai.orchestration.ExternalStepExecutorRegistry]
 * and used as a plugin workflow step.
 */
class GolemPluginStepExecutorFactory(
    private val plugin: GolemPlugin,
) : ExternalStepExecutorFactory {
    override val typeId: String = plugin.pluginName

    override fun create(): ExternalStepExecutor = GolemPluginStepExecutor(plugin)
}

/**
 * [ExternalStepExecutor] that wraps a [GolemPlugin] for execution as a
 * workflow plugin step. Currently returns a success indicator; future
 * versions may delegate to the plugin's registered tools.
 */
class GolemPluginStepExecutor(
    private val plugin: GolemPlugin,
) : ExternalStepExecutor {
    override suspend fun execute(spec: Map<String, Any?>): Map<String, Any?> {
        return mapOf("plugin" to plugin.pluginName, "status" to "executed")
    }
}
