package dev.spola.metrics

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Histogram
import java.io.StringWriter
import java.io.Writer
import java.util.LinkedList

/**
 * Prometheus-compatible metrics for Spola agent monitoring.
 *
 * Wraps [io.prometheus:simpleclient] to track performance indicators:
 * agent runs, turns, tool calls, LLM calls, tokens, scheduler jobs,
 * and active sessions.
 *
 * When [isEnabled] is false, all recording methods are no-ops and
 * nothing is registered with the registry.
 */
class SpolaMetrics(
    private val registry: CollectorRegistry = CollectorRegistry.defaultRegistry,
    val isEnabled: Boolean = true,
    private val maxHistoryPoints: Int = 120,
) : AutoCloseable {

    // ── Counters (nullable — null when disabled) ───────────────

    private var agentRunsTotal: Counter? = null
    private var agentTurnsTotal: Counter? = null
    private var toolCallsTotal: Counter? = null
    private var llmCallsTotal: Counter? = null
    private var llmTokensTotal: Counter? = null
    private var schedulerJobsExecutedTotal: Counter? = null

    // ── Histograms ───────────────────────────────────────────

    private var agentRunDurationSeconds: Histogram? = null
    private var toolCallDurationSeconds: Histogram? = null

    // ── Gauges ───────────────────────────────────────────────

    private var activeSessions: Gauge? = null

    // ── Time-series history buffer ──────────────────────────

    private val historyBuffer = LinkedList<MetricsSnapshot>()
    private var lastSnapshotTime = 0L

    /**
     * A point-in-time snapshot of key counter values.
     */
    data class MetricsSnapshot(
        val timestamp: Long,
        val agentRunsTotal: Double,
        val agentTurnsTotal: Double,
        val toolCallsTotal: Double,
        val llmCallsTotal: Double,
        val llmTokensTotal: Double,
    )

    init {
        if (isEnabled) {
            try {
                agentRunsTotal = Counter.build()
                    .name("spola_agent_runs_total")
                    .help("Total number of agent runs.")
                    .labelNames("status")
                    .register(registry)
                agentTurnsTotal = Counter.build()
                    .name("spola_agent_turns_total")
                    .help("Total number of ReAct loop turns across all runs.")
                    .register(registry)
                toolCallsTotal = Counter.build()
                    .name("spola_tool_calls_total")
                    .help("Total number of tool calls.")
                    .labelNames("tool", "status")
                    .register(registry)
                llmCallsTotal = Counter.build()
                    .name("spola_llm_calls_total")
                    .help("Total number of LLM calls.")
                    .labelNames("provider", "model")
                    .register(registry)
                llmTokensTotal = Counter.build()
                    .name("spola_llm_tokens_total")
                    .help("Total number of LLM tokens processed.")
                    .labelNames("type")
                    .register(registry)
                schedulerJobsExecutedTotal = Counter.build()
                    .name("spola_scheduler_jobs_executed_total")
                    .help("Total number of scheduler job executions.")
                    .register(registry)
                agentRunDurationSeconds = Histogram.build()
                    .name("spola_agent_run_duration_seconds")
                    .help("Duration of agent runs in seconds.")
                    .buckets(0.1, 1.0, 5.0, 15.0, 60.0)
                    .register(registry)
                toolCallDurationSeconds = Histogram.build()
                    .name("spola_tool_call_duration_seconds")
                    .help("Duration of tool calls in seconds.")
                    .labelNames("tool")
                    .register(registry)
                activeSessions = Gauge.build()
                    .name("spola_active_sessions")
                    .help("Currently active agent sessions.")
                    .register(registry)
            } catch (_: IllegalArgumentException) {
                // Already registered by a prior SpolaMetrics instance (e.g., across agent runs)
                // First registration remains active in the default registry.
                agentRunsTotal = null
                agentTurnsTotal = null
                toolCallsTotal = null
                llmCallsTotal = null
                llmTokensTotal = null
                schedulerJobsExecutedTotal = null
                agentRunDurationSeconds = null
                toolCallDurationSeconds = null
                activeSessions = null
            }
        } else {
            agentRunsTotal = null
            agentTurnsTotal = null
            toolCallsTotal = null
            llmCallsTotal = null
            llmTokensTotal = null
            schedulerJobsExecutedTotal = null
            agentRunDurationSeconds = null
            toolCallDurationSeconds = null
            activeSessions = null
        }
    }

    // ── Recording methods ────────────────────────────────────

    /** Record a completed agent run. */
    fun recordAgentRun(status: String, durationSeconds: Double) {
        val c = agentRunsTotal ?: return
        val h = agentRunDurationSeconds ?: return
        c.labels(status).inc()
        h.observe(durationSeconds)
    }

    /** Record one ReAct loop turn. */
    fun recordTurn() {
        agentTurnsTotal?.inc()
    }

    /** Record a tool call with its outcome and duration. */
    fun recordToolCall(tool: String, status: String, durationSeconds: Double) {
        val c = toolCallsTotal ?: return
        val h = toolCallDurationSeconds ?: return
        c.labels(tool, status).inc()
        h.labels(tool).observe(durationSeconds)
    }

    /** Record an LLM call. */
    fun recordLlmCall(provider: String, model: String) {
        llmCallsTotal?.labels(provider, model)?.inc()
    }

    /** Record input/output/thinking token counts from an LLM response. */
    fun recordLlmTokens(inputTokens: Int = 0, outputTokens: Int = 0, thinkingTokens: Int = 0) {
        val c = llmTokensTotal ?: return
        c.labels("input").inc(inputTokens.toDouble())
        c.labels("output").inc(outputTokens.toDouble())
        c.labels("thinking").inc(thinkingTokens.toDouble())
    }

    /** Record a scheduler job execution. */
    fun recordSchedulerJob() {
        schedulerJobsExecutedTotal?.inc()
    }

    /** Set the number of active sessions. */
    fun setActiveSessions(count: Int) {
        activeSessions?.set(count.toDouble())
    }

    // ── Gauge helpers ────────────────────────────────────────

    /** Increment active sessions by one. */
    fun incActiveSessions() {
        activeSessions?.inc()
    }

    /** Decrement active sessions by one. */
    fun decActiveSessions() {
        activeSessions?.dec()
    }

    /**
     * Record a snapshot of current counter values into the history buffer.
     * Call this periodically (e.g., every 30s) to build time-series data.
     * Keeps at most [maxHistoryPoints] snapshots, discarding oldest first.
     */
    fun recordSnapshot() {
        if (!isEnabled) return
        val now = System.currentTimeMillis()
        // Avoid duplicate snapshots within the same second
        if (now - lastSnapshotTime < 1000) return
        lastSnapshotTime = now

        val snapshot = MetricsSnapshot(
            timestamp = now,
            agentRunsTotal = agentRunsTotal?.get() ?: 0.0,
            agentTurnsTotal = agentTurnsTotal?.get() ?: 0.0,
            toolCallsTotal = toolCallsTotal?.get() ?: 0.0,
            llmCallsTotal = llmCallsTotal?.get() ?: 0.0,
            llmTokensTotal = llmTokensTotal?.get() ?: 0.0,
        )
        synchronized(historyBuffer) {
            if (historyBuffer.size >= maxHistoryPoints) {
                historyBuffer.removeFirst()
            }
            historyBuffer.addLast(snapshot)
        }
    }

    /**
     * Return all stored history snapshots, ordered oldest-first.
     */
    fun getHistory(): List<MetricsSnapshot> {
        synchronized(historyBuffer) {
            return historyBuffer.toList()
        }
    }

    // ── Export ────────────────────────────────────────────────

    /**
     * Render all registered metrics in Prometheus text format.
     *
     * Uses [CollectorRegistry.metricFamilySamples] to produce
     * `text/plain; version=0.0.4` compatible output.
     */
    fun renderPrometheusText(): String {
        val writer = StringWriter()
        writeMetrics(writer, registry)
        return writer.toString()
    }

    /** No-op cleanup for default registry (not owned by us). */
    override fun close() {
        // defaultRegistry is shared; do not clear
    }

    companion object {
        /**
         * Render metric family samples in Prometheus text format.
         * Compatible with `text/plain; version=0.0.4`.
         */
        fun writeMetrics(writer: Writer, registry: CollectorRegistry) {
            val families = registry.metricFamilySamples()
            while (families.hasMoreElements()) {
                val family = families.nextElement()
                writer.write("# HELP ${family.name} ${family.help}\n")
                writer.write("# TYPE ${family.name} ${family.type.name.lowercase()}\n")
                for (sample in family.samples) {
                    val labelStr = if (sample.labelNames.isEmpty()) {
                        ""
                    } else {
                        val labels = sample.labelNames.indices.joinToString(",") { i ->
                            "${sample.labelNames[i]}=\"${escapeLabelValue(sample.labelValues[i])}\""
                        }
                        "{$labels}"
                    }
                    // Use full name (includes _bucket, _sum, _count suffixes for histograms)
                    writer.write("${sample.name}$labelStr ${formatSampleValue(sample.value)}\n")
                }
            }
        }

        private fun escapeLabelValue(value: String): String {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
        }

        private fun formatSampleValue(value: Double): String {
            return if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                value.toString()
            }
        }
    }
}
