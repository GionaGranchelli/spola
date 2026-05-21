package dev.spola.kanban

import dev.spola.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KanbanToolsTest {

    @Test
    fun `task_create creates task with default status todo`(@TempDir tempDir: Path) = runTest {
        val store = SqliteKanbanStore(tempDir.resolve("kanban.db").toString())
        val registry = ToolRegistry()
        registerKanbanTools(registry, store)

        val result = registry.get("task_create")!!.execute(
            mapOf("title" to "Write docs"),
        )

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("Write docs"))
        assertTrue(result.output.contains("todo"))
        val tasks = store.list()
        assertEquals(1, tasks.size)
        assertEquals("Write docs", tasks.single().title)
        assertEquals("todo", tasks.single().status)
        store.close()
    }

    @Test
    fun `task_create with custom status and priority`(@TempDir tempDir: Path) = runTest {
        val store = SqliteKanbanStore(tempDir.resolve("kanban.db").toString())
        val registry = ToolRegistry()
        registerKanbanTools(registry, store)

        val result = registry.get("task_create")!!.execute(
            mapOf(
                "title" to "Fix bug",
                "description" to "Critical production issue",
                "status" to "in_progress",
                "priority" to "critical",
                "labels" to "bug,urgent",
            ),
        )

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("Fix bug"))
        val task = store.list().single()
        assertEquals("in_progress", task.status)
        assertEquals("critical", task.priority)
        assertEquals("bug,urgent", task.labels)
        store.close()
    }

    @Test
    fun `task_list returns all tasks`(@TempDir tempDir: Path) = runTest {
        val store = SqliteKanbanStore(tempDir.resolve("kanban.db").toString())
        store.create("Task A")
        store.create("Task B", priority = "high", status = "in_progress")
        val registry = ToolRegistry()
        registerKanbanTools(registry, store)

        val result = registry.get("task_list")!!.execute(emptyMap())

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("Task A"))
        assertTrue(result.output.contains("Task B"))
        store.close()
    }

    @Test
    fun `task_list filters by status`(@TempDir tempDir: Path) = runTest {
        val store = SqliteKanbanStore(tempDir.resolve("kanban.db").toString())
        store.create("Active task")
        store.create("Done task", status = "done")
        val registry = ToolRegistry()
        registerKanbanTools(registry, store)

        val result = registry.get("task_list")!!.execute(mapOf("status" to "done"))

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("Done task"), result.output)
        assertFalse(result.output.contains("Active task"), result.output)
        store.close()
    }

    @Test
    fun `task_update modifies task fields`(@TempDir tempDir: Path) = runTest {
        val store = SqliteKanbanStore(tempDir.resolve("kanban.db").toString())
        val task = store.create("Old title", status = "todo")
        val registry = ToolRegistry()
        registerKanbanTools(registry, store)

        val result = registry.get("task_update")!!.execute(
            mapOf("id" to task.id, "title" to "New title", "status" to "in_progress"),
        )

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains("New title"))
        val updated = store.get(task.id)!!
        assertEquals("New title", updated.title)
        assertEquals("in_progress", updated.status)
        store.close()
    }

    @Test
    fun `task_delete removes task`(@TempDir tempDir: Path) = runTest {
        val store = SqliteKanbanStore(tempDir.resolve("kanban.db").toString())
        val task = store.create("Delete me")
        val registry = ToolRegistry()
        registerKanbanTools(registry, store)

        val result = registry.get("task_delete")!!.execute(mapOf("id" to task.id))

        assertTrue(result.success, result.output)
        assertTrue(result.output.contains(task.id))
        assertEquals(emptyList(), store.list())
        store.close()
    }

    @Test
    fun `task_delete returns not found for missing id`(@TempDir tempDir: Path) = runTest {
        val store = SqliteKanbanStore(tempDir.resolve("kanban.db").toString())
        val registry = ToolRegistry()
        registerKanbanTools(registry, store)

        val result = registry.get("task_delete")!!.execute(mapOf("id" to "nonexistent"))

        assertFalse(result.success)
        assertTrue(result.output.contains("not found"))
        store.close()
    }
}
