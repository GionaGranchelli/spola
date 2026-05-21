package dev.spola.plugin

import dev.tramai.orchestration.ExternalStepExecutor
import dev.tramai.orchestration.ExternalStepExecutorFactory

/**
 * Factory that adapts a [SpolaPlugin] into TramAI's [ExternalStepExecutorFactory]
 * so it can be registered in an [dev.tramai.orchestration.ExternalStepExecutorRegistry]
 * and used as a plugin workflow step.
 */
class SpolaPluginStepExecutorFactory(
    private val plugin: SpolaPlugin,
) : ExternalStepExecutorFactory {
    override val typeId: String = plugin.pluginName

    override fun create(): ExternalStepExecutor = SpolaPluginStepExecutor(plugin)
}

/**
 * [ExternalStepExecutor] that wraps a [SpolaPlugin] for execution as a
 * workflow plugin step. Currently returns a success indicator; future
 * versions may delegate to the plugin's registered tools.
 */
class SpolaPluginStepExecutor(
    private val plugin: SpolaPlugin,
) : ExternalStepExecutor {
    override suspend fun execute(spec: Map<String, Any?>): Map<String, Any?> {
        return mapOf("plugin" to plugin.pluginName, "status" to "executed")
    }
}
