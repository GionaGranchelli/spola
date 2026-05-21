package dev.spola.tools

import dev.spola.SpolaConfig
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

fun registerTools(registry: ToolRegistry, config: SpolaConfig, permissionEnforcer: PermissionEnforcer? = null) {
    registerTools(registry, permissionEnforcer)
    registerFileTools(registry, config)
    registerDeliveryTools(registry, config)
    registerTtsTool(registry, config)
    registerIssueTools(registry, config)
    registerJiraIssueTools(registry, config)
}
