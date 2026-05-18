package dev.spola.tools

import dev.spola.GolemConfig
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import dev.spola.agent.PermissionEnforcer

fun registerTools(registry: ToolRegistry, permissionEnforcer: PermissionEnforcer? = null) {
    registerFileTools(registry)
    registerShellTool(registry, permissionEnforcer)
    registerGitTools(registry)
    registerWebTools(registry)
    registerEditTool(registry)
}

fun registerTools(registry: ToolRegistry, config: GolemConfig, permissionEnforcer: PermissionEnforcer? = null) {
    registerTools(registry, permissionEnforcer)
    registerFileTools(registry, config)
    registerDeliveryTools(registry, config)
    registerTtsTool(registry, config)
}
