package dev.spola

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Creates and manages OpenTelemetry tracing spans for Spola agent runs.
 *
 * When [otelEnabled] is true and [otelEndpoint] is non-null, creates an OTLP gRPC exporter.
 * Otherwise uses [OpenTelemetry.noop] — zero overhead.
 *
 * Spans are created for:
 * - Agent run (root span) — no user data in attributes (privacy)
 * - Each ReAct loop turn (child span with turn number)
 * - Each LLM call (child span with model, provider, token count)
 * - Each tool execution (child span with tool name, success/fail, duration)
 */
class SpolaTracer(
    otelEnabled: Boolean,
    otelEndpoint: String?,
    otelServiceName: String = "spola",
) : AutoCloseable {
    private val openTelemetry: OpenTelemetry
    private val tracer: Tracer
    private val sdkTracerProvider: SdkTracerProvider?
    val isActive: Boolean

    /** Active spans keyed by a logical scope name (e.g., "root", "tool:<callId>"). */
    private val activeSpans = ConcurrentHashMap<String, Span>()

    init {
        if (otelEnabled && !otelEndpoint.isNullOrBlank()) {
            val otlpExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(otelEndpoint)
                .setTimeout(30, TimeUnit.SECONDS)
                .build()

            val provider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(otlpExporter)
                    .setScheduleDelay(5, TimeUnit.SECONDS)
                    .build())
                .build()

            sdkTracerProvider = provider
            openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build()

            tracer = openTelemetry.getTracer(otelServiceName)
            isActive = true
        } else {
            sdkTracerProvider = null
            openTelemetry = OpenTelemetry.noop()
            tracer = openTelemetry.getTracer(otelServiceName)
            isActive = false
        }
    }

    companion object {
        /** Create a no-op tracer (zero overhead, no spans emitted). */
        fun noop(): SpolaTracer = SpolaTracer(
            otelEnabled = false,
            otelEndpoint = null,
        )
    }

    // ── Root span ────────────────────────────────────────────────

    /** Start the root span for an agent run. No user data in attributes. */
    fun startRootSpan() {
        val span = tracer.spanBuilder("spola.run")
            .setAttribute("spola.service.name", "spola")
            .startSpan()
        activeSpans["root"] = span
    }

    /** End the root span (marks a successful run). */
    fun endRootSpan() {
        activeSpans.remove("root")?.end()
    }

    /** Record an exception on the root span and mark it as error. */
    fun failRootSpan(error: Throwable) {
        val span = activeSpans["root"]
        if (span != null) {
            span.recordException(error)
            span.setStatus(StatusCode.ERROR, error.message ?: "Agent run failed")
        }
        activeSpans.remove("root")?.end()
    }

    // ── Turn span ────────────────────────────────────────────────

    /** Start a span for a ReAct loop turn. */
    fun startTurnSpan(turn: Int) {
        val root = activeSpans["root"] ?: return
        val span = tracer.spanBuilder("spola.turn")
            .setParent(Context.current().with(root))
            .setAttribute("spola.turn.number", turn.toLong())
            .startSpan()
        activeSpans["turn:$turn"] = span
    }

    /** End the span for a ReAct loop turn. */
    fun endTurnSpan(turn: Int) {
        activeSpans.remove("turn:$turn")?.end()
    }

    // ── LLM call span ────────────────────────────────────────────

    /** Start a span for an LLM model call. */
    fun startLlmCallSpan(model: String, provider: String) {
        val turnSpan = findCurrentTurnSpan()
        val parent = turnSpan ?: activeSpans["root"] ?: Span.getInvalid()
        val span = tracer.spanBuilder("spola.llm.call")
            .setParent(Context.current().with(parent))
            .setAttribute("gen_ai.system", provider)
            .setAttribute("gen_ai.request.model", model)
            .startSpan()
        activeSpans["llm:latest"] = span
    }

    /** Record token counts and end the LLM call span. */
    fun endLlmCallSpan(inputTokens: Int? = null, outputTokens: Int? = null) {
        val span = activeSpans.remove("llm:latest")
        if (span != null) {
            inputTokens?.let { span.setAttribute("gen_ai.usage.input_tokens", it.toLong()) }
            outputTokens?.let { span.setAttribute("gen_ai.usage.output_tokens", it.toLong()) }
            span.end()
        }
    }

    // ── Tool execution span ──────────────────────────────────────

    /** Start a span for a tool execution. */
    fun startToolSpan(toolName: String, toolCallId: String) {
        val turnSpan = findCurrentTurnSpan()
        val parent = turnSpan ?: activeSpans["root"] ?: Span.getInvalid()
        val span = tracer.spanBuilder("spola.tool.execution")
            .setParent(Context.current().with(parent))
            .setAttribute("spola.tool.name", toolName)
            .setAttribute("spola.tool.call_id", toolCallId)
            .startSpan()
        activeSpans["tool:$toolCallId"] = span
    }

    /** End a tool execution span with success/fail and duration. */
    fun endToolSpan(toolCallId: String, success: Boolean, durationMs: Long? = null) {
        val span = activeSpans.remove("tool:$toolCallId")
        if (span != null) {
            span.setAttribute("spola.tool.success", success)
            durationMs?.let { span.setAttribute("spola.tool.duration_ms", it) }
            if (!success) {
                span.setStatus(StatusCode.ERROR, "Tool execution failed")
            }
            span.end()
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────

    override fun close() {
        // Flush and shut down all pending spans
        try {
            sdkTracerProvider?.close()
        } catch (_: Exception) {
            // Best-effort shutdown
        }
        activeSpans.clear()
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** Find the most recent active turn span, or null. */
    private fun findCurrentTurnSpan(): Span? {
        return activeSpans.keys
            .filter { it.startsWith("turn:") }
            .mapNotNull { activeSpans[it] }
            .lastOrNull()
    }
}
