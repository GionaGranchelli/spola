package dev.spola.tools

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

private const val GIT_TIMEOUT_SECONDS = 30L

fun registerGitTools(registry: ToolRegistry) {
    registry.register(Tool(
        name = "git_diff",
        description = "Show git diff output for the current repository or a specific path.",
        parameters = listOf(
            ToolParameter("path", "File or directory path to diff (default: current directory)", ToolParameterType.STRING, required = false, defaultValue = "."),
            ToolParameter("cached", "Show staged changes only", ToolParameterType.BOOLEAN, required = false, defaultValue = false),
            ToolParameter("head", "Optional revision to diff against, for example HEAD~1", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            try {
                val workdir = resolvePath(".")
                checkAllowed(workdir)

                val pathArg = (args["path"] as? String) ?: "."
                val cached = args["cached"] as? Boolean ?: false
                val head = args["head"] as? String
                val gitPath = sanitizeGitPath(pathArg, workdir)

                val command = mutableListOf("diff")
                if (cached) command += "--cached"
                if (!head.isNullOrBlank()) command += head
                command += "--"
                command += gitPath

                runGitCommand(workdir, command)
            } catch (e: SecurityException) {
                ToolResult.fail(e.message ?: "Access denied")
            } catch (e: Exception) {
                ToolResult.fail("Git diff failed: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "git_commit",
        description = "Stage changes (all or specific files) and create a git commit with the provided message.",
        parameters = listOf(
            ToolParameter("message", "Commit message", ToolParameterType.STRING),
            ToolParameter("files", "Specific files to commit (space-separated). If empty, stages all changes.", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            try {
                val workdir = resolvePath(".")
                checkAllowed(workdir)

                val message = (args["message"] as? String)?.trim()
                    ?: return@Tool ToolResult.fail("Missing required argument: message")
                if (message.isEmpty()) {
                    return@Tool ToolResult.fail("Commit message must not be empty")
                }

                val filesArg = (args["files"] as? String)?.trim()
                val addArgs = if (!filesArg.isNullOrBlank()) {
                    listOf("add") + filesArg.split("\\s+".toRegex()).filter { it.isNotBlank() }
                } else {
                    listOf("add", "-A")
                }
                val addResult = runGitCommand(workdir, addArgs)
                if (!addResult.success) {
                    return@Tool addResult
                }

                val commitResult = runGitCommand(workdir, listOf("commit", "-m", message))
                if (!commitResult.success) {
                    return@Tool commitResult
                }

                val output = buildString {
                    if (addResult.output.isNotBlank() && addResult.output != "(no output)") {
                        appendLine(addResult.output)
                    }
                    append(commitResult.output)
                }.trim()

                ToolResult.ok(output.ifBlank { "Commit created" })
            } catch (e: SecurityException) {
                ToolResult.fail(e.message ?: "Access denied")
            } catch (e: Exception) {
                ToolResult.fail("Git commit failed: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "git_status",
        description = "Show compact git status output for the repository or a specific path.",
        parameters = listOf(
            ToolParameter("path", "File or directory path to filter status output (default: current directory)", ToolParameterType.STRING, required = false, defaultValue = "."),
        ),
        execute = { args ->
            try {
                val workdir = resolvePath(".")
                checkAllowed(workdir)

                val pathArg = (args["path"] as? String) ?: "."
                val gitPath = sanitizeGitPath(pathArg, workdir)

                runGitCommand(workdir, listOf("status", "--short", "--", gitPath))
            } catch (e: SecurityException) {
                ToolResult.fail(e.message ?: "Access denied")
            } catch (e: Exception) {
                ToolResult.fail("Git status failed: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "git_log",
        description = "Show recent git commit history in one-line format.",
        parameters = listOf(
            ToolParameter("limit", "Maximum number of commits to return (default: 10)", ToolParameterType.INTEGER, required = false, defaultValue = 10),
        ),
        execute = { args ->
            try {
                val workdir = resolvePath(".")
                checkAllowed(workdir)

                val limit = ((args["limit"] as? Int) ?: 10).coerceIn(1, 100)
                runGitCommand(workdir, listOf("log", "--oneline", "-n", limit.toString()))
            } catch (e: SecurityException) {
                ToolResult.fail(e.message ?: "Access denied")
            } catch (e: Exception) {
                ToolResult.fail("Git log failed: ${e.message}")
            }
        },
    ))
}

private fun sanitizeGitPath(pathArg: String, workdir: Path): String {
    val candidate = if (pathArg == ".") {
        workdir
    } else {
        val resolved = resolvePath(pathArg)
        if (Files.exists(resolved)) {
            resolved
        } else {
            workdir.resolve(pathArg).normalize()
        }
    }
    checkAllowed(candidate)
    return try {
        workdir.relativize(candidate).toString().ifBlank { "." }
    } catch (_: IllegalArgumentException) {
        candidate.toString()
    }
}

private fun runGitCommand(workdir: Path, arguments: List<String>): ToolResult {
    val process = ProcessBuilder(listOf("git") + arguments)
        .directory(workdir.toFile())
        .redirectErrorStream(true)
        .start()

    val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return ToolResult.fail("Git command timed out after ${GIT_TIMEOUT_SECONDS}s")
    }

    val output = process.inputStream.bufferedReader().readText().trim()
    return if (process.exitValue() == 0) {
        ToolResult.ok(output.ifBlank { "(no output)" })
    } else {
        ToolResult.fail(output.ifBlank { "git ${arguments.joinToString(" ")} failed with exit code ${process.exitValue()}" })
    }
}
