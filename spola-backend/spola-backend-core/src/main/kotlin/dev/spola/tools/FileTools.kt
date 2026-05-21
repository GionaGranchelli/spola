package dev.spola.tools

import dev.spola.SpolaConfig
import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

internal val allowedDirs: List<Path> by lazy {
    val envAllowed = System.getenv("SPOLA_ALLOWED_DIRS")
        ?: System.getenv("GOLEM_ALLOWED_DIRS")
    if (envAllowed != null && envAllowed.isNotBlank()) {
        envAllowed.split(":").map { Paths.get(it).toRealPath() }
    } else {
        emptyList() // No restriction — all paths allowed
    }
}

internal fun resolvePath(path: String, config: SpolaConfig? = null): Path {
    val p = Paths.get(path)
    val baseDir = if (config != null && config.workingDirectory != ".") {
        Paths.get(config.workingDirectory).toAbsolutePath().normalize()
    } else {
        Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
    }
    val abs = if (p.isAbsolute) p else baseDir.resolve(p)
    return abs.normalize()
}

internal fun checkAllowed(path: Path) {
    if (allowedDirs.isEmpty()) return // No restriction
    // Resolve symlinks to prevent symlink-based escape
    val realPath = try {
        path.toRealPath()
    } catch (_: Exception) {
        path.toAbsolutePath().normalize()
    }
    val allowed = allowedDirs.any { realPath.startsWith(it) }
    if (!allowed) {
        throw SecurityException("Access denied: $path (path outside allowed directories)")
    }
}

fun registerFileTools(registry: ToolRegistry, config: SpolaConfig? = null) {
    registry.register(Tool(
        name = "read_file",
        description = "Read the contents of a file with line numbers. Use this when you need to examine existing code, configuration, or documentation.",
        parameters = listOf(
            ToolParameter("path", "Path to the file to read", ToolParameterType.STRING),
            ToolParameter("offset", "Starting line number (1-indexed, default: 1)", ToolParameterType.INTEGER, required = false, defaultValue = 1),
            ToolParameter("limit", "Maximum number of lines to read (default: 500, max: 2000)", ToolParameterType.INTEGER, required = false, defaultValue = 500),
        ),
        execute = { args ->
            try {
                val path = (args["path"] as? String) ?: return@Tool ToolResult.fail("Missing required argument: path")
                val offset = (args["offset"] as? Int)?.coerceAtLeast(1) ?: 1
                val limit = ((args["limit"] as? Int) ?: 500).coerceIn(1, 2000)

                val resolved = resolvePath(path, config)
                checkAllowed(resolved)

                if (!Files.exists(resolved)) {
                    return@Tool ToolResult.fail("File not found: $path")
                }

                if (!Files.isRegularFile(resolved)) {
                    return@Tool ToolResult.fail("Not a regular file: $path")
                }

                val lines = Files.readAllLines(resolved)
                val totalLines = lines.size
                val start = (offset - 1).coerceIn(0, totalLines)
                val end = (start + limit).coerceAtMost(totalLines)

                val content = lines.subList(start, end).mapIndexed { idx, line ->
                    "${start + idx + 1}|$line"
                }.joinToString("\n")

                val summary = "(${end - start} of $totalLines lines)"
                ToolResult.ok("$summary\n$content")
            } catch (e: SecurityException) {
                ToolResult.fail(e.message ?: "Access denied")
            } catch (e: Exception) {
                ToolResult.fail("Error reading file: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "write_file",
        description = "Write content to a file, creating parent directories if they don't exist. Overwrites existing files completely.",
        parameters = listOf(
            ToolParameter("path", "Path where to write the file", ToolParameterType.STRING),
            ToolParameter("content", "Complete content to write to the file", ToolParameterType.STRING),
        ),
        execute = { args ->
            try {
                val path = (args["path"] as? String) ?: return@Tool ToolResult.fail("Missing required argument: path")
                val content = (args["content"] as? String) ?: return@Tool ToolResult.fail("Missing required argument: content")

                val resolved = resolvePath(path, config)
                checkAllowed(resolved)

                Files.createDirectories(resolved.parent)
                Files.writeString(resolved, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

                val bytes = content.toByteArray().size
                ToolResult.ok("OK ($bytes bytes written)")
            } catch (e: SecurityException) {
                ToolResult.fail(e.message ?: "Access denied")
            } catch (e: Exception) {
                ToolResult.fail("Error writing file: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "search_files",
        description = "Search file contents using a regex pattern. Returns matching files with line numbers, max 50 results.",
        parameters = listOf(
            ToolParameter("pattern", "Regex pattern to search for", ToolParameterType.STRING),
            ToolParameter("path", "Directory to search in (default: current directory)", ToolParameterType.STRING, required = false, defaultValue = "."),
            ToolParameter("file_glob", "Optional file glob pattern to filter (e.g., '*.kt', '*.md')", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            try {
                val pattern = (args["pattern"] as? String) ?: return@Tool ToolResult.fail("Missing required argument: pattern")
                val searchPath = (args["path"] as? String) ?: "."
                val fileGlob = args["file_glob"] as? String

                val resolved = resolvePath(searchPath, config)
                checkAllowed(resolved)

                val regex = try {
                    Regex(pattern)
                } catch (e: Exception) {
                    return@Tool ToolResult.fail("Invalid regex pattern: ${e.message}")
                }

                val results = mutableListOf<String>()
                var count = 0
                val maxResults = 50

                Files.walk(resolved).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .filter { file ->
                            if (fileGlob == null) true
                            else {
                                try {
                                    java.nio.file.FileSystems.getDefault()
                                        .getPathMatcher("glob:$fileGlob")
                                        .matches(file.fileName)
                                } catch (_: Exception) {
                                    val globPattern = fileGlob.replace(".", "\\.")
                                        .replace("?", ".").replace("*", ".*")
                                    file.fileName.toString()
                                        .matches(Regex(globPattern, RegexOption.IGNORE_CASE))
                                }
                            }
                        }
                        .forEach { file ->
                            if (count >= maxResults) return@forEach
                            try {
                                if (Files.size(file) > 10 * 1024 * 1024) return@forEach // Skip files > 10MB
                                Files.lines(file).use { stream ->
                                    var lineNumber = 0
                                    stream.forEach { line ->
                                        lineNumber++
                                        if (count >= maxResults) return@forEach
                                        if (regex.containsMatchIn(line)) {
                                            val relative = try {
                                                resolved.relativize(file).toString()
                                            } catch (_: Exception) {
                                                file.toString()
                                            }
                                            results.add("$relative:$lineNumber: ${line.trim()}")
                                            count++
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                // Skip binary files
                            }
                        }
                }

                if (results.isEmpty()) {
                    ToolResult.ok("No matches found for pattern: $pattern")
                } else {
                    ToolResult.ok("Found ${results.size} matches:\n${results.joinToString("\n")}")
                }
            } catch (e: SecurityException) {
                ToolResult.fail(e.message ?: "Access denied")
            } catch (e: Exception) {
                ToolResult.fail("Error searching files: ${e.message}")
            }
        },
    ))
}
