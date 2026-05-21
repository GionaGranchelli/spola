package dev.spola.tools

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import dev.spola.agent.PermissionEnforcer

fun registerShellTool(registry: ToolRegistry, permissionEnforcer: PermissionEnforcer? = null) {
    registry.register(Tool(
        name = "shell",
        description = "Execute a shell command. Uses argv mode (no shell injection). Returns stdout on success, stderr on failure.",
        parameters = listOf(
            ToolParameter("command", "The command to execute as a single string (split into argv by shell)", ToolParameterType.STRING),
            ToolParameter("workdir", "Working directory for the command (default: current directory)", ToolParameterType.STRING, required = false, defaultValue = "."),
            ToolParameter("timeout", "Maximum execution time in seconds (default: 30, max: 300)", ToolParameterType.INTEGER, required = false, defaultValue = 30),
        ),
        execute = { args ->
            try {
                val commandStr = (args["command"] as? String) ?: return@Tool ToolResult.fail("Missing required argument: command")
                val workdirStr = (args["workdir"] as? String) ?: "."
                val timeoutSec = ((args["timeout"] as? Int) ?: 30).coerceIn(1, 300)
                executeShellCommand(
                    commandStr = commandStr,
                    workdirStr = workdirStr,
                    timeoutSec = timeoutSec,
                    permissionEnforcer = permissionEnforcer,
                    maxOutputSize = MAX_SHELL_OUTPUT_SIZE,
                )
            } catch (e: Exception) {
                ToolResult.fail("Shell command failed: ${e.message}")
            }
        },
    ))
}
