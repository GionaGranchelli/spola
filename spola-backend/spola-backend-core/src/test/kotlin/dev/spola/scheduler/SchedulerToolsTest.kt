package dev.spola.scheduler

import dev.spola.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchedulerToolsTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-05-11T10:02:30Z"), ZoneOffset.UTC)

    @Test
    fun `scheduler_add creates a job and returns it`(@TempDir tempDir: Path) = runTest {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString(), fixedClock)
        val registry = ToolRegistry()
        registerSchedulerTools(registry, store)

        val result = registry.get("scheduler_add")!!.execute(
            mapOf(
                "name" to "sync repo",
                "cronExpression" to "*/5 * * * *",
                "goal" to "pull latest changes",
            ),
        )

        assertTrue(result.success)
        assertTrue(result.output.contains("Scheduled job created"))
        val jobs = store.list()
        assertEquals(1, jobs.size)
        assertEquals("sync repo", jobs.single().name)
        store.close()
    }

    @Test
    fun `scheduler_list returns all jobs`(@TempDir tempDir: Path) = runTest {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString(), fixedClock)
        store.add("alpha", "goal-1", "*/5 * * * *")
        store.add("beta", "goal-2", "10 * * * *", enabled = false)
        val registry = ToolRegistry()
        registerSchedulerTools(registry, store)

        val result = registry.get("scheduler_list")!!.execute(emptyMap())

        assertTrue(result.success)
        assertTrue(result.output.contains("name: alpha"))
        assertTrue(result.output.contains("name: beta"))
        assertTrue(result.output.contains("enabled: false"))
        store.close()
    }

    @Test
    fun `scheduler_remove deletes a job`(@TempDir tempDir: Path) = runTest {
        val store = SqliteSpolaJobStore(tempDir.resolve("scheduler.db").toString(), fixedClock)
        val job = store.add("cleanup", "goal", "*/5 * * * *")
        val registry = ToolRegistry()
        registerSchedulerTools(registry, store)

        val result = registry.get("scheduler_remove")!!.execute(mapOf("id" to job.id))

        assertTrue(result.success)
        assertTrue(result.output.contains(job.id))
        assertFalse(store.remove(job.id))
        assertEquals(emptyList(), store.list())
        store.close()
    }
}
