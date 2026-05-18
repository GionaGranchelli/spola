package dev.spola.tools

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import java.nio.file.Files
import java.nio.file.StandardOpenOption

fun registerEditTool(registry: ToolRegistry) {
    registry.register(
        Tool(
            name = "edit_file",
            description = "Edit a file by replacing a block of text. This is for targeted changes, not for overwriting the whole file.",
            parameters = listOf(
                ToolParameter("path", "Path to the file to edit", ToolParameterType.STRING),
                ToolParameter("oldText", "The exact text to find and replace", ToolParameterType.STRING),
                ToolParameter("newText", "The new text to replace the old text with", ToolParameterType.STRING),
                ToolParameter(
                    "replaceAll",
                    "If true, replace all occurrences of oldText (default: false)",
                    ToolParameterType.BOOLEAN,
                    required = false,
                    defaultValue = false
                )
            ),
            execute = { args ->
                try {
                    val path = (args["path"] as? String) ?: return@Tool ToolResult.fail("Missing required argument: path")
                    val oldText = (args["oldText"] as? String) ?: return@Tool ToolResult.fail("Missing required argument: oldText")
                    val newText = (args["newText"] as? String) ?: return@Tool ToolResult.fail("Missing required argument: newText")
                    val replaceAll = (args["replaceAll"] as? Boolean) ?: false

                    val resolved = resolvePath(path)
                    checkAllowed(resolved)

                    if (!Files.exists(resolved)) {
                        return@Tool ToolResult.fail("File not found: $path")
                    }

                    if (!Files.isRegularFile(resolved)) {
                        return@Tool ToolResult.fail("Not a regular file: $path")
                    }

                    val originalContent = Files.readString(resolved)

                    val newContent: String
                    // Exact match
                    val exactIndices = findIndices(originalContent, oldText)
                    if (exactIndices.isNotEmpty()) {
                        if (!replaceAll && exactIndices.size > 1) {
                            return@Tool ToolResult.fail("Found ${exactIndices.size} occurrences of oldText. Use replaceAll=true to replace all or be more specific.")
                        }
                        newContent = if (replaceAll) {
                            originalContent.replace(oldText, newText)
                        } else {
                            originalContent.replaceFirst(oldText, newText)
                        }
                    } else {
                        // Whitespace normalized match
                        val normalizedOldText = oldText.replace("\\s+".toRegex(), "\\s+")
                        val regex = Regex(normalizedOldText)
                        val matches = regex.findAll(originalContent).toList()

                        if (matches.isEmpty()) {
                            return@Tool ToolResult.fail("Text not found: oldText was not found in the file.")
                        }

                        if (!replaceAll && matches.size > 1) {
                            return@Tool ToolResult.fail("Found ${matches.size} occurrences of oldText. Use replaceAll=true to replace all or be more specific.")
                        }

                        newContent = if (replaceAll) {
                            regex.replace(originalContent, newText)
                        } else {
                            regex.replaceFirst(originalContent, newText)
                        }
                    }

                    Files.writeString(resolved, newContent, StandardOpenOption.TRUNCATE_EXISTING)

                    val diff = createDiff(originalContent, newContent)
                    ToolResult.ok("OK, file edited.\n$diff")
                } catch (e: SecurityException) {
                    ToolResult.fail(e.message ?: "Access denied")
                } catch (e: Exception) {
                    ToolResult.fail("Error editing file: ${e.message}")
                }
            },
        )
    )
}

private fun findIndices(text: String, pattern: String): List<Int> {
    val indices = mutableListOf<Int>()
    var index = text.indexOf(pattern)
    while (index != -1) {
        indices.add(index)
        index = text.indexOf(pattern, index + 1)
    }
    return indices
}

private fun createDiff(old: String, new: String): String {
    val oldLines = old.split('\n')
    val newLines = new.split('\n')
    val diff = mutableListOf<String>()

    val common = mutableSetOf<String>()
    val added = mutableListOf<String>()
    val removed = mutableListOf<String>()

    val newLinesSet = newLines.toSet()
    for (line in oldLines) {
        if (line in newLinesSet) {
            common.add(line)
        } else {
            removed.add("- $line")
        }
    }

    val oldLinesSet = oldLines.toSet()
    for (line in newLines) {
        if (line !in oldLinesSet) {
            added.add("+ $line")
        }
    }

    val result = removed + added
    if (result.size > 100) {
        return result.take(50).joinToString("\n") + "\n...\n" + result.takeLast(50).joinToString("\n")
    }

    return result.joinToString("\n")
}
