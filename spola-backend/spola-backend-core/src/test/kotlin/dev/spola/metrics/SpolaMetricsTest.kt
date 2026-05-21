package dev.spola.metrics

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpolaMetricsTest {

    @Test
    fun `counter increments correctly`() {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = SpolaMetrics(registry = registry)

        metrics.recordAgentRun(status = "success", durationSeconds = 2.5)

        // Prometheus 0.16 strips _total suffix from counter family names
        val families = collectFamilies(registry)
        val agentRuns = families.firstOrNull { it.name == "spola_agent_runs" }
        assertTrue(agentRuns != null, "Should find spola_agent_runs. Found: ${families.map { it.name }}")
        val nonCreated = agentRuns.samples.filter { !it.name.endsWith("_created") }
        assertEquals(1, nonCreated.size, "Should have 1 non-created sample")
        assertEquals(1.0, nonCreated.first().value, 0.001)
        assertEquals("success", nonCreated.first().labelValues.first())
    }

    @Test
    fun `counter increments with different labels`() {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = SpolaMetrics(registry = registry)

        metrics.recordAgentRun(status = "success", durationSeconds = 1.0)
        metrics.recordAgentRun(status = "fail", durationSeconds = 0.5)

        val families = collectFamilies(registry)
        val agentRuns = families.first { it.name == "spola_agent_runs" }
        val nonCreated = agentRuns.samples.filter { !it.name.endsWith("_created") }
        assertEquals(2, nonCreated.size)
        val successSample = nonCreated.first { it.labelValues.contains("success") }
        val failSample = nonCreated.first { it.labelValues.contains("fail") }
        assertEquals(1.0, successSample.value, 0.001)
        assertEquals(1.0, failSample.value, 0.001)
    }

    @Test
    fun `histogram records observations`() {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = SpolaMetrics(registry = registry)

        metrics.recordAgentRun(status = "success", durationSeconds = 2.5)
        metrics.recordAgentRun(status = "success", durationSeconds = 10.0)

        val families = collectFamilies(registry)
        val agentDuration = families.first { it.name == "spola_agent_run_duration_seconds" }
        assertTrue(agentDuration.samples.size > 2)
        val countSample = agentDuration.samples.first { it.name.endsWith("_count") }
        assertEquals(2.0, countSample.value, 0.001)
    }

    @Test
    fun `tool calls are recorded`() {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = SpolaMetrics(registry = registry)

        metrics.recordToolCall(tool = "read_file", status = "success", durationSeconds = 0.5)
        metrics.recordToolCall(tool = "read_file", status = "fail", durationSeconds = 0.3)
        metrics.recordToolCall(tool = "write_file", status = "success", durationSeconds = 1.2)

        val families = collectFamilies(registry)
        val toolCalls = families.first { it.name == "spola_tool_calls" }
        val nonCreated = toolCalls.samples.filter { !it.name.endsWith("_created") }
        assertEquals(3, nonCreated.size)
    }

    @Test
    fun `llm calls are recorded`() {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = SpolaMetrics(registry = registry)

        metrics.recordLlmCall(provider = "openai", model = "gpt-4o")
        metrics.recordLlmCall(provider = "anthropic", model = "claude-3")

        val families = collectFamilies(registry)
        val llmCalls = families.first { it.name == "spola_llm_calls" }
        val nonCreated = llmCalls.samples.filter { !it.name.endsWith("_created") }
        assertEquals(2, nonCreated.size)
    }

    @Test
    fun `tokens are recorded`() {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = SpolaMetrics(registry = registry)

        metrics.recordLlmTokens(inputTokens = 100, outputTokens = 50)

        val families = collectFamilies(registry)
        val tokens = families.first { it.name == "spola_llm_tokens" }
        val nonCreated = tokens.samples.filter { !it.name.endsWith("_created") }
        assertEquals(2, nonCreated.size)
        val inputSample = nonCreated.first { it.labelValues.contains("input") }
        val outputSample = nonCreated.first { it.labelValues.contains("output") }
        assertEquals(100.0, inputSample.value, 0.001)
        assertEquals(50.0, outputSample.value, 0.001)
    }

    @Test
    fun `gauge tracks active sessions`() {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = SpolaMetrics(registry = registry)

        metrics.setActiveSessions(3)
        metrics.setActiveSessions(5)

        val families = collectFamilies(registry)
        val gauge = families.first { it.name == "spola_active_sessions" }
        assertEquals(1, gauge.samples.size)
        assertEquals(5.0, gauge.samples.first().value, 0.001)
    }

    @Test
    fun `gauge increments and decrements`() {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = SpolaMetrics(registry = registry)

        metrics.incActiveSessions()
        metrics.incActiveSessions()
        metrics.decActiveSessions()

        val families = collectFamilies(registry)
        val gauge = families.first { it.name == "spola_active_sessions" }
        assertEquals(1.0, gauge.samples.first().value, 0.001)
    }

    @Test
    fun `scheduler jobs are recorded`() {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = SpolaMetrics(registry = registry)

        metrics.recordSchedulerJob()
        metrics.recordSchedulerJob()
        metrics.recordSchedulerJob()

        val families = collectFamilies(registry)
        val jobs = families.first { it.name == "spola_scheduler_jobs_executed" }
        assertEquals(3.0, jobs.samples.first().value, 0.001)
    }

    @Test
    fun `disabled metrics are no-ops`() {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = SpolaMetrics(registry = registry, isEnabled = false)

        metrics.recordAgentRun(status = "success", durationSeconds = 1.0)
        metrics.recordTurn()
        metrics.recordLlmCall(provider = "openai", model = "gpt-4o")
        metrics.setActiveSessions(5)
        metrics.incActiveSessions()

        val families = collectFamilies(registry)
        assertTrue(families.isEmpty(), "No metrics should be registered when disabled")
    }

    @Test
    fun `renderPrometheusText returns valid format`() {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = SpolaMetrics(registry = registry)

        metrics.recordAgentRun(status = "success", durationSeconds = 1.0)
        metrics.recordTurn()
        metrics.recordLlmCall(provider = "openai", model = "gpt-4o")

        val text = metrics.renderPrometheusText()

        assertTrue(text.contains("spola_agent_runs"))
        assertTrue(text.contains("spola_agent_turns"))
        assertTrue(text.contains("spola_llm_calls"))
        assertTrue(text.contains("HELP"))
        assertTrue(text.contains("TYPE"))
    }

    @Test
    fun `renderPrometheusText minimal when no metrics recorded`() {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = SpolaMetrics(registry = registry)

        val text = metrics.renderPrometheusText()

        assertTrue(text.contains("HELP"))
        assertTrue(text.contains("TYPE"))
    }

    @Test
    fun `turn counter increments`() {
        val registry = io.prometheus.client.CollectorRegistry()
        val metrics = SpolaMetrics(registry = registry)

        metrics.recordTurn()
        metrics.recordTurn()
        metrics.recordTurn()

        val families = collectFamilies(registry)
        val turns = families.first { it.name == "spola_agent_turns" }
        assertEquals(3.0, turns.samples.first().value, 0.001)
    }

    // Helper: Collect all metric families from registry via while-loop Enumeration iteration
    private fun collectFamilies(registry: io.prometheus.client.CollectorRegistry): List<io.prometheus.client.Collector.MetricFamilySamples> {
        val families = mutableListOf<io.prometheus.client.Collector.MetricFamilySamples>()
        val samples = registry.metricFamilySamples()
        while (samples.hasMoreElements()) {
            families.add(samples.nextElement())
        }
        return families
    }
}
