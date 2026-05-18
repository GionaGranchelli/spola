package dev.spola.metrics

import dev.spola.GolemConfig
import dev.spola.api.AgentRunHandler
import dev.spola.api.golemApiModule
import dev.spola.scheduler.SqliteGolemJobStore
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsEndpointTest {

    @Test
    fun `metrics endpoint returns valid Prometheus format`() = testApplication {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = GolemMetrics(registry = registry)

        metrics.recordAgentRun(status = "success", durationSeconds = 2.5)
        metrics.recordToolCall(tool = "read_file", status = "success", durationSeconds = 0.3)

        val store = SqliteGolemJobStore(":memory:")
        application {
            golemApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                golemMetrics = metrics,
            )
        }

        val response = client.get("/api/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("golem_agent_runs_total"), "Should contain agent runs metric")
        assertTrue(body.contains("golem_tool_calls_total"), "Should contain tool calls metric")
        assertTrue(body.contains("HELP"), "Should contain HELP lines")
        assertTrue(body.contains("TYPE"), "Should contain TYPE lines")

        store.close()
    }

    @Test
    fun `metrics endpoint is accessible without auth`() = testApplication {
        val store = SqliteGolemJobStore(":memory:")
        val registry = io.prometheus.client.CollectorRegistry()
        application {
            golemApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                config = GolemConfig(apiKey = "secret-key"),
                golemMetrics = GolemMetrics(registry = registry),
            )
        }

        val response = client.get("/api/metrics")

        // Should be accessible without auth (200, not 401)
        assertEquals(HttpStatusCode.OK, response.status)
        store.close()
    }

    @Test
    fun `metrics endpoint returns all metric families when recorded`() = testApplication {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = GolemMetrics(registry = registry)

        metrics.recordAgentRun(status = "success", durationSeconds = 1.0)
        metrics.recordAgentRun(status = "fail", durationSeconds = 0.5)
        metrics.recordTurn()
        metrics.recordTurn()
        metrics.recordToolCall(tool = "echo", status = "success", durationSeconds = 0.1)
        metrics.recordLlmCall(provider = "openai", model = "gpt-4o")
        metrics.recordLlmTokens(inputTokens = 150, outputTokens = 75)
        metrics.setActiveSessions(2)
        metrics.recordSchedulerJob()

        val store = SqliteGolemJobStore(":memory:")
        application {
            golemApiModule(
                agentRunHandler = AgentRunHandler(),
                jobStore = store,
                golemMetrics = metrics,
            )
        }

        val response = client.get("/api/metrics")
        val body = response.bodyAsText()

        assertTrue(body.contains("golem_agent_runs_total"))
        assertTrue(body.contains("golem_agent_run_duration_seconds"))
        assertTrue(body.contains("golem_agent_turns_total"))
        assertTrue(body.contains("golem_tool_calls_total"))
        assertTrue(body.contains("golem_tool_call_duration_seconds"))
        assertTrue(body.contains("golem_llm_calls_total"))
        assertTrue(body.contains("golem_llm_tokens_total"))
        assertTrue(body.contains("golem_scheduler_jobs_executed_total"))
        assertTrue(body.contains("golem_active_sessions"))

        // Verify actual values
        assertTrue(body.contains("status=\"success\""))
        assertTrue(body.contains("status=\"fail\""))
        assertTrue(body.contains("golem_active_sessions"))

        store.close()
    }
}
