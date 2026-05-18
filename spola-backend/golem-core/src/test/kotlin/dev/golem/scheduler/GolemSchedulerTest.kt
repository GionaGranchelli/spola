package dev.spola.scheduler

import dev.spola.GolemConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GolemSchedulerTest {

    @Test
    fun `pollOnce runs due jobs and updates next run`(@TempDir tempDir: Path) = runTest {
        val storeClock = Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC)
        val schedulerClock = Clock.fixed(Instant.parse("2026-05-11T10:05:00Z"), ZoneOffset.UTC)
        val store = SqliteGolemJobStore(tempDir.resolve("scheduler.db").toString(), storeClock)
        val job = store.add("job", "do work", "*/5 * * * *")
        val executed = mutableListOf<String>()

        val scheduler = GolemScheduler(
            jobStore = store,
            config = GolemConfig(),
            scope = backgroundScope,
            clock = schedulerClock,
            jobRunner = { scheduledJob, _ ->
                executed += scheduledJob.id
            },
        )

        scheduler.pollOnce()

        val updated = store.get(job.id)
        assertEquals(listOf(job.id), executed)
        assertNotNull(updated)
        assertEquals(Instant.parse("2026-05-11T10:05:00Z"), updated.lastRunAt)
        assertEquals(Instant.parse("2026-05-11T10:10:00Z"), updated.nextRunAt)
    }

    @Test
    fun `pollOnce ignores jobs that are not due yet`(@TempDir tempDir: Path) = runTest {
        val storeClock = Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC)
        val schedulerClock = Clock.fixed(Instant.parse("2026-05-11T10:05:00Z"), ZoneOffset.UTC)
        val store = SqliteGolemJobStore(tempDir.resolve("scheduler.db").toString(), storeClock)
        val job = store.add("job", "do work", "10 10 * * *")
        var ran = false

        val scheduler = GolemScheduler(
            jobStore = store,
            config = GolemConfig(),
            scope = backgroundScope,
            clock = schedulerClock,
            jobRunner = { _, _ -> ran = true },
        )

        scheduler.pollOnce()

        val unchanged = store.get(job.id)
        assertTrue(!ran)
        assertNotNull(unchanged)
        assertEquals(job.nextRunAt, unchanged.nextRunAt)
        assertEquals(null, unchanged.lastRunAt)
    }

    @Test
    fun `pollOnce advances failed jobs so they do not remain stuck due`(@TempDir tempDir: Path) = runTest {
        val storeClock = Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC)
        val schedulerClock = Clock.fixed(Instant.parse("2026-05-11T10:05:00Z"), ZoneOffset.UTC)
        val store = SqliteGolemJobStore(tempDir.resolve("scheduler.db").toString(), storeClock)
        val job = store.add("job", "do work", "*/5 * * * *")

        val scheduler = GolemScheduler(
            jobStore = store,
            config = GolemConfig(),
            scope = backgroundScope,
            clock = schedulerClock,
            jobRunner = { _, _ -> error("boom") },
        )

        scheduler.pollOnce()

        val updated = store.get(job.id)
        assertNotNull(updated)
        assertEquals(Instant.parse("2026-05-11T10:05:00Z"), updated.lastRunAt)
        assertEquals(Instant.parse("2026-05-11T10:10:00Z"), updated.nextRunAt)
    }

    @Test
    fun `pollOnce processes multiple due jobs`(@TempDir tempDir: Path) = runTest {
        val storeClock = Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC)
        val schedulerClock = Clock.fixed(Instant.parse("2026-05-11T10:05:00Z"), ZoneOffset.UTC)
        val store = SqliteGolemJobStore(tempDir.resolve("scheduler.db").toString(), storeClock)
        val first = store.add("first", "goal-1", "*/5 * * * *")
        val second = store.add("second", "goal-2", "*/5 * * * *")
        val executed = mutableListOf<String>()

        val scheduler = GolemScheduler(
            jobStore = store,
            config = GolemConfig(),
            scope = backgroundScope,
            clock = schedulerClock,
            jobRunner = { scheduledJob, _ -> executed += scheduledJob.id },
        )

        scheduler.pollOnce()

        assertEquals(setOf(first.id, second.id), executed.toSet())
    }
}
