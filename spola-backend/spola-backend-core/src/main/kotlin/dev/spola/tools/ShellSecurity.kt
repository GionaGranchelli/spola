package dev.spola.tools

import dev.spola.ToolResult
import dev.spola.agent.PermissionEnforcer
import dev.spola.agent.PermissionDeniedException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal const val MAX_SHELL_OUTPUT_SIZE = 50 * 1024 // 50KB

internal object ShellSecurityPolicy {
    val blockedCommands = setOf(
        "sudo", "su", "passwd", "chown", "chmod", "mount", "umount",
        "mkfs", "dd", "fdisk", "parted", "reboot", "shutdown", "halt",
        "poweroff", "init", "killall", "pkill",
    )

    val blockedInterpreters = setOf(
        "bash", "sh", "zsh", "dash", "ksh", "fish", "python", "python3",
        "perl", "ruby", "lua", "node", "nodejs", "deno", "php",
    )

    val networkCommands = setOf(
        "curl", "wget", "nc", "ncat", "ssh", "scp", "rsync",
        "sftp", "telnet", "ftp", "socat", "nmap",
    )

    val destructiveCommands = setOf(
        "rm", "dd", "mkfs", "format", "chmod", "chown", "mv",
        "truncate", "fallocate", "fdisk", "parted", "mount",
        "umount", "swapoff", "swapon",
    )

    val writeCommands = setOf(
        "tee", "touch", "install", "cp",
    )
}

internal data class ParsedShellCommand(
    val args: List<String>,
    val rawExecutable: String,
    val executableName: String,
    val resolvedExecutable: Path?,
    val resolvedExecutableName: String?,
)

internal data class ShellExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

internal fun parseShellCommand(input: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var inSingle = false
    var inDouble = false
    var escaping = false

    for (ch in input) {
        when {
            escaping -> {
                current.append(ch)
                escaping = false
            }
            ch == '\\' && !inSingle -> {
                escaping = true
            }
            ch == '\'' && !inDouble -> {
                inSingle = !inSingle
            }
            ch == '"' && !inSingle -> {
                inDouble = !inDouble
            }
            ch.isWhitespace() && !inSingle && !inDouble -> {
                if (current.isNotEmpty()) {
                    args += current.toString()
                    current.clear()
                }
            }
            else -> current.append(ch)
        }
    }

    if (escaping) {
        current.append('\\')
    }
    if (inSingle || inDouble) {
        throw IllegalArgumentException("Unterminated quoted string in command")
    }
    if (current.isNotEmpty()) {
        args += current.toString()
    }
    return args
}

internal fun inspectShellCommand(command: String, workdir: String? = null): ParsedShellCommand {
    val args = parseShellCommand(command.trim())
    if (args.isEmpty()) {
        throw IllegalArgumentException("Empty command")
    }

    val rawExecutable = args.first()
    val resolvedExecutable = resolveExecutable(rawExecutable, workdir)
    val executableName = File(rawExecutable).name.lowercase()
    val resolvedExecutableName = resolvedExecutable?.fileName?.toString()?.lowercase()

    return ParsedShellCommand(
        args = args,
        rawExecutable = rawExecutable,
        executableName = executableName,
        resolvedExecutable = resolvedExecutable,
        resolvedExecutableName = resolvedExecutableName,
    )
}

internal fun resolveExecutable(command: String, workdir: String? = null): Path? {
    val candidate = Path.of(command)
    val directPath = when {
        candidate.isAbsolute -> candidate
        command.contains(File.separator) -> {
            val baseDir = workdir?.let { Path.of(it) } ?: Path.of(System.getProperty("user.dir"))
            baseDir.resolve(candidate).normalize()
        }
        else -> null
    }

    if (directPath != null) {
        return directPath.takeIf { Files.exists(it) }?.toRealPathSafe()
    }

    val pathEnv = System.getenv("PATH").orEmpty()
    for (entry in pathEnv.split(File.pathSeparator).filter { it.isNotBlank() }) {
        val path = Path.of(entry).resolve(command)
        if (Files.isRegularFile(path) && Files.isExecutable(path)) {
            return path.toRealPathSafe()
        }
    }

    val usrBin = Path.of("/usr/bin", command)
    if (Files.isRegularFile(usrBin) && Files.isExecutable(usrBin)) {
        return usrBin.toRealPathSafe()
    }

    return null
}

internal fun enforceShellSecurity(command: ParsedShellCommand): String? {
    val commandNames = listOfNotNull(
        command.executableName,
        command.resolvedExecutableName,
    ).distinct()

    if ("rtk" in commandNames) {
        return "Direct 'rtk' command is not allowed (Spola injects it transparently)"
    }
    if (commandNames.any { it in ShellSecurityPolicy.blockedCommands }) {
        val name = commandNames.first { it in ShellSecurityPolicy.blockedCommands }
        return "Command blocked for security: $name"
    }
    if (commandNames.any { it in ShellSecurityPolicy.blockedInterpreters }) {
        val name = commandNames.first { it in ShellSecurityPolicy.blockedInterpreters }
        return "Interpreter execution blocked for security: $name (use direct commands instead)"
    }
    return null
}

internal fun executeShellCommand(
    commandStr: String,
    workdirStr: String = ".",
    timeoutSec: Int = 30,
    permissionEnforcer: PermissionEnforcer? = null,
    env: Map<String, String>? = null,
    maxOutputSize: Int = MAX_SHELL_OUTPUT_SIZE,
): ToolResult {
    val workdir = File(workdirStr)
    if (!workdir.exists() || !workdir.isDirectory) {
        return ToolResult.fail("Working directory not found: $workdirStr")
    }

    try {
        permissionEnforcer?.checkShell(commandStr, workdirStr)
    } catch (e: PermissionDeniedException) {
        return ToolResult.fail(e.message ?: "Permission denied")
    }

    val commandParts = try {
        inspectShellCommand(commandStr, workdirStr)
    } catch (e: IllegalArgumentException) {
        return ToolResult.fail(e.message ?: "Invalid command")
    }

    val securityError = enforceShellSecurity(commandParts)
    if (securityError != null) {
        return ToolResult.fail(securityError)
    }

    val finalCommand = maybeWrapWithRtk(commandParts.args)
    val process = try {
        ProcessBuilder(finalCommand)
            .directory(workdir)
            .redirectErrorStream(false)
            .apply {
                if (env != null) {
                    environment().putAll(env)
                }
            }
            .start()
    } catch (e: Exception) {
        return ToolResult.fail("Shell command failed: ${e.message}")
    }

    val stdoutFuture = CompletableFuture.supplyAsync {
        try { process.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
    }
    val stderrFuture = CompletableFuture.supplyAsync {
        try { process.errorStream.bufferedReader().readText() } catch (_: Exception) { "" }
    }

    val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return ToolResult.fail("Command timed out after ${timeoutSec}s")
    }

    val stdout = try { stdoutFuture.get(2, TimeUnit.SECONDS) } catch (_: Exception) { "" }
    val stderr = try { stderrFuture.get(2, TimeUnit.SECONDS) } catch (_: Exception) { "" }
    val exitCode = process.exitValue()

    val truncatedStdout = truncateOutput(stdout, maxOutputSize)
    val truncatedStderr = truncateOutput(stderr, maxOutputSize)

    return if (exitCode == 0) {
        ToolResult.ok(truncatedStdout.ifBlank { "(no output)" })
    } else {
        val output = buildString {
            appendLine("Exit code: $exitCode")
            if (truncatedStderr.isNotBlank()) appendLine(truncatedStderr)
            if (truncatedStdout.isNotBlank()) appendLine(truncatedStdout)
        }
        ToolResult.fail(output.trimEnd())
    }
}

internal fun toolResultToShellExecutionResult(result: ToolResult): ShellExecutionResult {
    return if (result.success) {
        ShellExecutionResult(
            stdout = result.output,
            stderr = "",
            exitCode = 0,
        )
    } else {
        val output = result.output
        val exitCode = Regex("""^Exit code: (\d+)""")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 1
        val body = output.lineSequence().drop(1).joinToString("\n").ifBlank { output }
        ShellExecutionResult(
            stdout = "",
            stderr = body,
            exitCode = exitCode,
        )
    }
}

private val rtkSupportedCommands = setOf(
    "git", "cargo", "npm", "npx", "yarn", "pnpm", "ls", "cat",
    "grep", "rg", "find", "diff", "gh", "jest", "vitest", "pytest",
    "go", "ruff", "docker", "tsc", "eslint", "prettier",
)

private val isRtkAvailable: Boolean by lazy {
    try {
        val process = ProcessBuilder("which", "rtk")
            .redirectErrorStream(true)
            .start()
        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }
}

private fun maybeWrapWithRtk(commandParts: List<String>): List<String> {
    val userBaseName = File(commandParts.first()).name.lowercase()
    return if (isRtkAvailable && userBaseName in rtkSupportedCommands) {
        buildList {
            add("rtk")
            addAll(commandParts)
        }
    } else {
        commandParts
    }
}

private fun Path.toRealPathSafe(): Path {
    return try {
        toRealPath()
    } catch (_: Exception) {
        toAbsolutePath().normalize()
    }
}

private fun truncateOutput(output: String, maxOutputSize: Int): String {
    return if (output.length > maxOutputSize) {
        output.take(maxOutputSize) + "\n... [output truncated at ${maxOutputSize / 1024}KB]"
    } else {
        output
    }
}
