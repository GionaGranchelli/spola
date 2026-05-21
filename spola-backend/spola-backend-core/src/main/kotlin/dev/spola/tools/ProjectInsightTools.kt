package dev.spola.tools

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import dev.spola.jvm.ProjectInsightStore

fun registerProjectInsightTools(registry: ToolRegistry, store: ProjectInsightStore) {
    registry.register(Tool(
        name = "project_insight_save",
        description = "Save a project-specific convention or fact for later retrieval.",
        parameters = listOf(
            ToolParameter("module", "Optional Gradle module name", ToolParameterType.STRING, required = false),
            ToolParameter("symbol", "Optional symbol or file-specific target", ToolParameterType.STRING, required = false),
            ToolParameter("key", "Insight key", ToolParameterType.STRING),
            ToolParameter("value", "Insight value", ToolParameterType.STRING),
        ),
        execute = { args ->
            val key = (args["key"] as? String)?.trim()
                ?: return@Tool ToolResult.fail("Missing required argument: key")
            val value = (args["value"] as? String)?.trim()
                ?: return@Tool ToolResult.fail("Missing required argument: value")
            val module = (args["module"] as? String)?.trim()?.ifBlank { null }
            val symbol = (args["symbol"] as? String)?.trim()?.ifBlank { null }
            store.save(module, symbol, key, value)
            ToolResult.ok("Saved insight '$key'.")
        },
    ))

    registry.register(Tool(
        name = "project_insight_search",
        description = "Search stored project conventions, module notes, and symbol-specific guidance.",
        parameters = listOf(
            ToolParameter("module", "Optional Gradle module name", ToolParameterType.STRING, required = false),
            ToolParameter("symbol", "Optional symbol or file-specific target", ToolParameterType.STRING, required = false),
            ToolParameter("key", "Optional insight key", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            val module = (args["module"] as? String)?.trim()?.ifBlank { null }
            val symbol = (args["symbol"] as? String)?.trim()?.ifBlank { null }
            val key = (args["key"] as? String)?.trim()?.ifBlank { null }
            val results = store.search(module, symbol, key)
            if (results.isEmpty()) {
                ToolResult.ok("No project insights found.")
            } else {
                ToolResult.ok(results.joinToString("\n") {
                    "module=${it.module ?: "*"} symbol=${it.symbol ?: "*"} key=${it.key} value=${it.value}"
                })
            }
        },
    ))

    registry.register(Tool(
        name = "project_insight_delete",
        description = "Delete a stored project insight.",
        parameters = listOf(
            ToolParameter("module", "Optional Gradle module name", ToolParameterType.STRING, required = false),
            ToolParameter("symbol", "Optional symbol or file-specific target", ToolParameterType.STRING, required = false),
            ToolParameter("key", "Insight key", ToolParameterType.STRING),
        ),
        execute = { args ->
            val key = (args["key"] as? String)?.trim()
                ?: return@Tool ToolResult.fail("Missing required argument: key")
            val module = (args["module"] as? String)?.trim()?.ifBlank { null }
            val symbol = (args["symbol"] as? String)?.trim()?.ifBlank { null }
            val deleted = store.delete(module, symbol, key)
            if (deleted == 0) ToolResult.fail("Insight not found.")
            else ToolResult.ok("Deleted insight '$key'.")
        },
    ))
}
