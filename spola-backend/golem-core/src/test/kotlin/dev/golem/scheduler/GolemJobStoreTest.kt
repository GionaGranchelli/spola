package dev.spola.scheduler

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GolemJobStoreTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-05-11T10:02:30Z"), ZoneOffset.UTC)

    @Test
    fun `add stores job and computes next run`(@TempDir tempDir: Path) = runTest {
        val store = SqliteGolemJobStore(tempDir.resolve("scheduler.db").toString(), fixedClock)

        val job = store.add(
            name = "every five minutes",
            goal = "check backups",
            cronExpression = "*/5 * * * *",
        )

        assertEquals("every five minutes", job.name)
        assertEquals(Instant.parse("2026-05-11T10:05:00Z"), job.nextRunAt)
        assertNotNull(store.get(job.id))
    }

    @Test
    fun `list returns jobs ordered by next run`(@TempDir tempDir: Path) = runTest {
        val store = SqliteGolemJobStore(tempDir.resolve("scheduler.db").toString(), fixedClock)

        val later = store.add("later", "goal-1", "15 * * * *")
        val sooner = store.add("sooner", "goal-2", "5 * * * *")

        val jobs = store.list()

        assertEquals(listOf(sooner.id, later.id), jobs.map { it.id })
    }

    @Test
    fun `getDueJobs returns only enabled due jobs`(@TempDir tempDir: Path) = runTest {
        val store = SqliteGolemJobStore(tempDir.resolve("scheduler.db").toString(), fixedClock)
        val dueJob = store.add("due", "goal-1", "*/5 * * * *")
        store.add("disabled", "goal-2", "*/5 * * * *", enabled = false)
        store.add("future", "goal-3", "10 11 * * *")

        val dueJobs = store.getDueJobs(Instant.parse("2026-05-11T10:05:00Z"))

        assertEquals(listOf(dueJob.id), dueJobs.map { it.id })
    }

    @Test
    fun `updateNextRun records the last run timestamp`(@TempDir tempDir: Path) = runTest {
        val store = SqliteGolemJobStore(tempDir.resolve("scheduler.db").toString(), fixedClock)
        val job = store.add("job", "goal", "*/5 * * * *")
        val lastRunAt = Instant.parse("2026-05-11T10:05:00Z")
        val nextRunAt = Instant.parse("2026-05-11T10:10:00Z")

        assertTrue(store.updateNextRun(job.id, nextRunAt, lastRunAt))

        val updated = store.get(job.id)
        assertNotNull(updated)
        assertEquals(lastRunAt, updated.lastRunAt)
        assertEquals(nextRunAt, updated.nextRunAt)
    }

    @Test
    fun `remove deletes the job`(@TempDir tempDir: Path) = runTest {
        val store = SqliteGolemJobStore(tempDir.resolve("scheduler.db").toString(), fixedClock)
        val job = store.add("job", "goal", "*/5 * * * *")

        assertTrue(store.remove(job.id))
        assertFalse(store.remove(job.id))
        assertNull(store.get(job.id))
    }

    @Test
    fun `add persists optional workflow definition id`(@TempDir tempDir: Path) = runTest {
        val store = SqliteGolemJobStore(tempDir.resolve("scheduler.db").toString(), fixedClock)

        val job = store.add(
            name = "nightly review",
            goal = "review nightly changes",
            cronExpression = "0 2 * * *",
            workflowDefinitionId = "nightly-review",
        )

        val stored = store.get(job.id)
        assertNotNull(stored)
        assertEquals("nightly-review", stored.workflowDefinitionId)
    }
}
