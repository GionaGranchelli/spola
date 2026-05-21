package dev.spola.plugin

import dev.spola.ToolRegistry

interface SpolaPlugin {
    val pluginName: String
    val pluginVersion: String

    suspend fun register(registry: ToolRegistry)

    suspend fun onShutdown() {}
}
