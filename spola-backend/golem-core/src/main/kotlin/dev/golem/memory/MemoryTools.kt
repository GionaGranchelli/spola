package dev.spola.memory

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult

/**
 * Register memory tools (memory_save, memory_search) into the tool registry.
 */
fun registerMemoryTools(registry: ToolRegistry, memoryStore: MemoryStore, namespace: String? = null) {
    val namespacePrefix = namespace?.let { "$it:" }
    fun namespacedKey(key: String): String = namespace?.let { "$it:$key" } ?: key
    fun displayKey(key: String): String = namespacePrefix?.let { key.removePrefix(it) } ?: key

    registry.register(Tool(
        name = "memory_save",
        description = "Save a fact to persistent memory. Facts are remembered across sessions. Use this for user preferences, project conventions, and environment details.",
        parameters = listOf(
            ToolParameter("key", "Unique key for the fact (e.g., 'user_prefers_tabs')", ToolParameterType.STRING),
            ToolParameter("value", "The fact content to store", ToolParameterType.STRING),
        ),
        execute = { args ->
            try {
                val key = (args["key"] as? String) ?: return@Tool ToolResult.fail("Missing required argument: key")
                val value = (args["value"] as? String) ?: return@Tool ToolResult.fail("Missing required argument: value")
                val effectiveKey = namespacedKey(key)
                memoryStore.save(effectiveKey, value)
                ToolResult.ok("Saved: ${displayKey(effectiveKey)}")
            } catch (e: Exception) {
                ToolResult.fail("Failed to save memory: ${e.message}")
            }
        },
    ))

    registry.register(Tool(
        name = "memory_search",
        description = "Search persistent memory for facts by key or content. Returns matching entries with their keys and values.",
        parameters = listOf(
            ToolParameter("query", "Search term to find in memory keys or values", ToolParameterType.STRING),
        ),
        execute = { args ->
            try {
                val query = (args["query"] as? String) ?: return@Tool ToolResult.fail("Missing required argument: query")
                val results = memoryStore.search(query)
                    .filter { entry -> namespacePrefix == null || entry.key.startsWith(namespacePrefix) }
                if (results.isEmpty()) {
                    ToolResult.ok("No memory entries found for: $query")
                } else {
                    val formatted = results.joinToString("\n---\n") { entry ->
                        "[${displayKey(entry.key)}]\n${entry.value}\n(created: ${entry.createdAt})"
                    }
                    ToolResult.ok("Found ${results.size} entries:\n$formatted")
                }
            } catch (e: Exception) {
                ToolResult.fail("Failed to search memory: ${e.message}")
            }
        },
    ))
}
