package dev.spola.memory

import dev.spola.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryToolsTest {

    @Test
    fun `memory_save and memory_search integration`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("memory.db").toString()
        val store = SqliteMemoryStore(dbPath)

        val registry = ToolRegistry()
        registerMemoryTools(registry, store)

        val saveTool = registry.get("memory_save")!!
        val searchTool = registry.get("memory_search")!!

        // Save a fact
        val saveResult = saveTool.execute(mapOf(
            "key" to "user_preference",
            "value" to "Prefers concise responses",
        ))
        assertTrue(saveResult.success)
        assertTrue(saveResult.output.contains("Saved"))

        // Search for it
        val searchResult = searchTool.execute(mapOf("query" to "concise"))
        assertTrue(searchResult.success)
        assertTrue(searchResult.output.contains("user_preference"))
        assertTrue(searchResult.output.contains("Prefers concise"))
    }

    @Test
    fun `memory_save overwrites existing key`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("memory.db").toString()
        val store = SqliteMemoryStore(dbPath)

        val registry = ToolRegistry()
        registerMemoryTools(registry, store)

        val saveTool = registry.get("memory_save")!!

        saveTool.execute(mapOf("key" to "key1", "value" to "old"))
        saveTool.execute(mapOf("key" to "key1", "value" to "new"))

        val results = store.search("key1")
        assertEquals("new", results[0].value)
    }

    @Test
    fun `memory_search returns no results for unknown query`(@TempDir tempDir: Path) = runTest {
        val dbPath = tempDir.resolve("memory.db").toString()
        val store = SqliteMemoryStore(dbPath)

        val registry = ToolRegistry()
        registerMemoryTools(registry, store)

        store.save("known", "value")

        val searchTool = registry.get("memory_search")!!
        val result = searchTool.execute(mapOf("query" to "nonexistent"))
        assertTrue(result.success)
        assertTrue(result.output.contains("No memory entries"))
    }
}
