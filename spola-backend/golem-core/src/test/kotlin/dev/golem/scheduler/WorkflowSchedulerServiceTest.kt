package dev.spola.scheduler

import dev.spola.GolemConfig
import dev.spola.workflow.SqliteWorkflowExecutionStore
import dev.spola.workflow.WorkflowExecutionService
import dev.spola.workflow.WorkflowExecutionStatus
import dev.spola.workflow.WorkflowSchedulerService
import dev.spola.workflow.WorkflowTemplateRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorkflowSchedulerServiceTest {

    @Test
    fun `scheduler enqueues workflow executions for workflow jobs`(@TempDir tempDir: Path) = runTest {
        val storeClock = Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC)
        val schedulerClock = Clock.fixed(Instant.parse("2026-05-11T10:05:00Z"), ZoneOffset.UTC)
        val jobStore = SqliteGolemJobStore(tempDir.resolve("scheduler.db").toString(), storeClock)
        val workflowStore = SqliteWorkflowExecutionStore(tempDir.resolve("workflows.db").toString())
        val config = GolemConfig(workflowDbPath = tempDir.resolve("workflows.db").toString())
        val executionService = WorkflowExecutionService(
            config = config,
            executionStore = workflowStore,
            workflowRegistry = WorkflowTemplateRegistry(),
        )
        val workflowSchedulerService = WorkflowSchedulerService(
            executionService = executionService,
            config = config,
        )
        val job = jobStore.add(
            name = "nightly review",
            goal = "review nightly changes",
            cronExpression = "*/5 * * * *",
            workflowDefinitionId = "nightly-review",
        )
        var legacyRunnerCalled = false

        val scheduler = GolemScheduler(
            jobStore = jobStore,
            config = config,
            scope = backgroundScope,
            clock = schedulerClock,
            workflowSchedulerService = workflowSchedulerService,
            jobRunner = { _, _ -> legacyRunnerCalled = true },
        )

        scheduler.pollOnce()

        val executions = workflowStore.listByStatus(setOf(WorkflowExecutionStatus.QUEUED))
        assertFalse(legacyRunnerCalled)
        assertEquals(1, executions.size)
        assertEquals(job.id, executions.single().triggerRef)
        assertEquals("scheduler", executions.single().triggerSource)
        assertEquals("nightly-review", executions.single().definitionId)
    }
}
