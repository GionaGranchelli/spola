package dev.spola.workflow

import dev.spola.SpolaConfig
import dev.spola.kanban.KanbanTask
import dev.spola.kanban.SqliteKanbanStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkflowKanbanServiceTest {

    @Test
    fun `kanban service deduplicates repeated transitions in the same cooldown window`(@TempDir tempDir: Path) = runTest {
        val workflowStore = SqliteWorkflowExecutionStore(tempDir.resolve("workflows.db").toString())
        val executionService = WorkflowExecutionService(
            config = SpolaConfig(workflowDbPath = tempDir.resolve("workflows.db").toString()),
            executionStore = workflowStore,
            workflowRegistry = WorkflowTemplateRegistry(),
        )
        val service = WorkflowKanbanService(
            executionService = executionService,
            cooldownSeconds = 30,
            currentEpochSeconds = { 60L },
        )
        val task = KanbanTask(
            id = "task-1",
            title = "Review PR",
            status = "done",
            createdAt = 1L,
            updatedAt = 2L,
        )

        val first = service.onTaskStatusChanged(task, "todo", "done")
        val second = service.onTaskStatusChanged(task, "todo", "done")

        assertNotNull(first)
        assertNull(second)
        assertEquals(1, workflowStore.listByStatus(setOf(WorkflowExecutionStatus.QUEUED)).size)
    }

    @Test
    fun `kanban store callback fires only when status changes`(@TempDir tempDir: Path) = runTest {
        val transitions = mutableListOf<String>()
        val store = SqliteKanbanStore(
            dbPath = tempDir.resolve("kanban.db").toString(),
            onStatusChanged = { _, oldStatus, newStatus ->
                transitions += "$oldStatus->$newStatus"
            },
        )
        val task = store.create("Review PR", status = "todo")

        store.update(id = task.id, title = "Review PR v2")
        store.update(id = task.id, status = "done")

        assertEquals(listOf("todo->done"), transitions)
    }
}
