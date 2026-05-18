package dev.spola.tools

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import dev.spola.agent.PermissionEnforcer
import dev.spola.agent.PermissionDeniedException
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val MAX_OUTPUT_SIZE = 50 * 1024 // 50KB

// Commands that are always blocked for security
private val blockedCommands = setOf(
    "sudo", "su", "passwd", "chown", "chmod", "mount", "umount",
    "mkfs", "dd", "fdisk", "parted", "reboot", "shutdown", "halt",
    "poweroff", "init", "killall", "pkill",
)

// Interpreters that can execute arbitrary commands and bypass blocklist
private val blockedInterpreters = setOf(
    "bash", "sh", "zsh", "dash", "ksh", "fish", "python", "python3",
    "perl", "ruby", "lua", "node", "nodejs", "deno", "php",
)

// Commands that support transparent RTK (Rust Token Killer) compression
private val rtkSupportedCommands = setOf(
    "git", "cargo", "npm", "npx", "yarn", "pnpm", "ls", "cat",
    "grep", "rg", "find", "diff", "gh", "jest", "vitest", "pytest",
    "go", "ruff", "docker", "tsc", "eslint", "prettier",
)
/** Thread-safe lazy check for rtk binary availability. */
private val isRtkAvailable: Boolean by lazy {
    try {
        val process = ProcessBuilder("which", "rtk")
            .redirectErrorStream(true)
            .start()
        process.waitFor() == 0
    } catch (_: Exception) { false }
}

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

                val workdir = File(workdirStr)
                if (!workdir.exists() || !workdir.isDirectory) {
                    return@Tool ToolResult.fail("Working directory not found: $workdirStr")
                }

                try {
                    permissionEnforcer?.checkShell(commandStr, workdirStr)
                } catch (e: PermissionDeniedException) {
                    return@Tool ToolResult.fail(e.message ?: "Permission denied")
                }

                // Parse argv properly, handling quoted strings
                val commandParts = parseArgs(commandStr.trim())
                if (commandParts.isEmpty()) {
                    return@Tool ToolResult.fail("Empty command")
                }

                // Block user-provided rtk (also via path) — prevents blocklist bypass
                val userBaseName = java.io.File(commandParts.first()).name.lowercase()
                if (userBaseName == "rtk") {
                    return@Tool ToolResult.fail("Direct 'rtk' command is not allowed (Golem injects it transparently)")
                }

                // RTK transparent wrapper
                val finalCommand = if (isRtkAvailable && userBaseName in rtkSupportedCommands) {
                    buildList { add("rtk"); addAll(commandParts) }
                } else commandParts

                // Security: check the REAL command, not the wrapper
                val securityCommand = if (finalCommand.first() == "rtk") {
                    finalCommand.getOrNull(1) ?: finalCommand.first()
                } else finalCommand.first()
                val baseCommand = java.io.File(securityCommand).name.lowercase()
                if (blockedCommands.contains(baseCommand)) {
                    return@Tool ToolResult.fail("Command blocked for security: $baseCommand")
                }
                if (blockedInterpreters.contains(baseCommand)) {
                    return@Tool ToolResult.fail("Interpreter execution blocked for security: $baseCommand (use direct commands instead)")
                }

                val process = ProcessBuilder(finalCommand)
                    .directory(workdir)
                    .redirectErrorStream(false)
                    .start()

                // Read streams in background threads
                val stdoutFuture = CompletableFuture.supplyAsync {
                    try { process.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
                }
                val stderrFuture = CompletableFuture.supplyAsync {
                    try { process.errorStream.bufferedReader().readText() } catch (_: Exception) { "" }
                }

                // Wait for process with timeout
                val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@Tool ToolResult.fail("Command timed out after ${timeoutSec}s")
                }

                val stdout = try { stdoutFuture.get(2, TimeUnit.SECONDS) } catch (_: Exception) { "" }
                val stderr = try { stderrFuture.get(2, TimeUnit.SECONDS) } catch (_: Exception) { "" }
                val exitCode = process.exitValue()

                val truncatedStdout = if (stdout.length > MAX_OUTPUT_SIZE) {
                    stdout.take(MAX_OUTPUT_SIZE) + "\n... [output truncated at ${MAX_OUTPUT_SIZE / 1024}KB]"
                } else stdout

                val truncatedStderr = if (stderr.length > MAX_OUTPUT_SIZE) {
                    stderr.take(MAX_OUTPUT_SIZE) + "\n... [output truncated at ${MAX_OUTPUT_SIZE / 1024}KB]"
                } else stderr

                if (exitCode == 0) {
                    ToolResult.ok(truncatedStdout.ifBlank { "(no output)" })
                } else {
                    val output = buildString {
                        appendLine("Exit code: $exitCode")
                        if (truncatedStderr.isNotBlank()) appendLine(truncatedStderr)
                        if (truncatedStdout.isNotBlank()) appendLine(truncatedStdout)
                    }
                    ToolResult.fail(output.trimEnd())
                }
            } catch (e: Exception) {
                ToolResult.fail("Shell command failed: ${e.message}")
            }
        },
    ))
}

/**
 * Parse a command string into argv parts, respecting single and double quotes.
 */
internal fun parseArgs(input: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var inSingle = false
    var inDouble = false

    for (ch in input) {
        when {
            ch == '\'' && !inDouble -> {
                inSingle = !inSingle
            }
            ch == '"' && !inSingle -> {
                inDouble = !inDouble
            }
            ch == ' ' && !inSingle && !inDouble -> {
                if (current.isNotEmpty()) {
                    args.add(current.toString())
                    current.clear()
                }
            }
            else -> current.append(ch)
        }
    }
    if (current.isNotEmpty()) {
        args.add(current.toString())
    }
    return args
}
