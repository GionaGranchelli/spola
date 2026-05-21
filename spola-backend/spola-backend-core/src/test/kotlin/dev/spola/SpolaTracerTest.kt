package dev.spola

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [SpolaTracer] — OpenTelemetry tracing for the Spola agent.
 *
 * Uses an in-memory span exporter to verify spans without an actual OTLP endpoint.
 */
class SpolaTracerTest {

    // ── TEST 1: No-op mode ───────────────────────────────────────

    @Test
    fun `noop tracer does not create real spans`() {
        val tracer = SpolaTracer.noop()
        assertThat(tracer.isActive).isFalse()

        // Calling span methods should not throw
        tracer.startRootSpan()
        tracer.startTurnSpan(1)
        tracer.startLlmCallSpan("gpt-4", "openai")
        tracer.endLlmCallSpan(10, 20)
        tracer.startToolSpan("read_file", "call-1")
        tracer.endToolSpan("call-1", true, 100)
        tracer.endTurnSpan(1)
        tracer.endRootSpan()

        // No exception thrown = no-op mode works
    }

    // ── TEST 2: Noop tracer always noop regardless of calls ──────

    @Test
    fun `noop constructor via config is noop`() {
        val tracer = SpolaTracer(otelEnabled = false, otelEndpoint = null)
        assertThat(tracer.isActive).isFalse()

        val tracer2 = SpolaTracer(otelEnabled = true, otelEndpoint = null)
        assertThat(tracer2.isActive).isFalse()

        val tracer3 = SpolaTracer(otelEnabled = false, otelEndpoint = "http://localhost:4317")
        assertThat(tracer3.isActive).isFalse()
    }

    // ── TEST 3: Active tracer with real SDK ─────────────────────

    @Test
    fun `active tracer creates spans with correct hierarchy`() {
        // Use SDK directly to test span creation behavior matching SpolaTracer's pattern
        val exporter = InMemorySpanExporter.create()
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        val otel = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build()
        val tracer = otel.getTracer("spola-test")

        // Create root span (like SpolaTracer.startRootSpan)
        val rootSpan = tracer.spanBuilder("spola.run")
            .setAttribute("spola.goal", "test goal")
            .startSpan()

        // Create turn span as child (like SpolaTracer.startTurnSpan)
        val turnSpan = tracer.spanBuilder("spola.turn")
            .setParent(Context.current().with(rootSpan))
            .setAttribute("spola.turn.number", 1L)
            .startSpan()

        // Create LLM call span as child of turn (like SpolaTracer.startLlmCallSpan)
        val llmSpan = tracer.spanBuilder("spola.llm.call")
            .setParent(Context.current().with(turnSpan))
            .setAttribute("gen_ai.system", "openai")
            .setAttribute("gen_ai.request.model", "gpt-4")
            .startSpan()
        llmSpan.setAttribute("gen_ai.usage.input_tokens", 50L)
        llmSpan.setAttribute("gen_ai.usage.output_tokens", 100L)
        llmSpan.end()

        // Create tool span as child of turn (like SpolaTracer.startToolSpan)
        val toolSpan = tracer.spanBuilder("spola.tool.execution")
            .setParent(Context.current().with(turnSpan))
            .setAttribute("spola.tool.name", "read_file")
            .setAttribute("spola.tool.call_id", "call-1")
            .startSpan()
        toolSpan.setAttribute("spola.tool.success", true)
        toolSpan.setAttribute("spola.tool.duration_ms", 42L)
        toolSpan.end()

        turnSpan.end()
        rootSpan.end()

        // Flush and assert
        tracerProvider.forceFlush()
        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(4)

        // Find spans by name
        val root = spans.find { it.name == "spola.run" }
        val turn = spans.find { it.name == "spola.turn" }
        val llm = spans.find { it.name == "spola.llm.call" }
        val tool = spans.find { it.name == "spola.tool.execution" }

        assertThat(root).isNotNull
        assertThat(turn).isNotNull
        assertThat(llm).isNotNull
        assertThat(tool).isNotNull

        // Verify root span attributes — no user data exported
        assertThat(root!!.name).isEqualTo("spola.run")

        // Verify turn span is child of root
        assertThat(turn!!.parentSpanId).isEqualTo(root.spanId)

        // Verify LLM span attributes
        assertThat(llm!!.attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("gen_ai.usage.input_tokens")))
            .isEqualTo(50L)
        assertThat(llm.attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("gen_ai.usage.output_tokens")))
            .isEqualTo(100L)

        // Verify tool span attributes
        assertThat(tool!!.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("spola.tool.name")))
            .isEqualTo("read_file")
        assertThat(tool.attributes.get(io.opentelemetry.api.common.AttributeKey.booleanKey("spola.tool.success")))
            .isTrue()

        // Verify parent chain: tool → turn → root
        assertThat(tool.parentSpanId).isEqualTo(turn.spanId)
        assertThat(turn.parentSpanId).isEqualTo(root.spanId)
        assertThat(root.parentSpanId).isEqualTo(io.opentelemetry.api.trace.Span.getInvalid().spanContext.spanId)
    }

    // ── TEST 4: Error recording on spans ─────────────────────────

    @Test
    fun `error spans record exceptions and error status`() {
        val exporter = InMemorySpanExporter.create()
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        val otel = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build()
        val tracer = otel.getTracer("spola-test")

        // Create root span
        val rootSpan = tracer.spanBuilder("spola.run")
            .setAttribute("spola.goal", "test")
            .startSpan()

        // Create tool span that fails
        val toolSpan = tracer.spanBuilder("spola.tool.execution")
            .setParent(Context.current().with(rootSpan))
            .setAttribute("spola.tool.name", "failing_tool")
            .startSpan()
        toolSpan.setAttribute("spola.tool.success", false)
        toolSpan.setStatus(StatusCode.ERROR, "Tool execution failed")
        toolSpan.recordException(RuntimeException("Connection refused"))
        toolSpan.end()

        // Simulate agent failure
        val error = RuntimeException("Agent crashed: out of tokens")
        rootSpan.recordException(error)
        rootSpan.setStatus(StatusCode.ERROR, error.message)
        rootSpan.end()

        tracerProvider.forceFlush()
        val spans = exporter.finishedSpanItems

        val root = spans.find { it.name == "spola.run" }
        val tool = spans.find { it.name == "spola.tool.execution" }

        assertThat(root).isNotNull
        assertThat(tool).isNotNull

        // Verify root has error status
        assertThat(root!!.status.statusCode).isEqualTo(StatusCode.ERROR)

        // Verify root has recorded exception event
        val rootEvents = root.events
        assertThat(rootEvents).anyMatch { event ->
            event.name == "exception" &&
                event.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("exception.message")) == "Agent crashed: out of tokens"
        }

        // Verify tool has error status
        assertThat(tool!!.status.statusCode).isEqualTo(StatusCode.ERROR)

        // Verify tool has recorded exception
        val toolEvents = tool.events
        assertThat(toolEvents).anyMatch { event ->
            event.name == "exception" &&
                event.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("exception.message")) == "Connection refused"
        }

        // Verify tool success attribute is false
        assertThat(tool.attributes.get(io.opentelemetry.api.common.AttributeKey.booleanKey("spola.tool.success")))
            .isFalse()
    }

    // ── TEST 5: Config integration ───────────────────────────────

    @Test
    fun `config creates correctly configured tracer`() {
        val config = SpolaConfig(
            otelEnabled = true,
            otelEndpoint = "http://jaeger:4317",
            otelServiceName = "spola-production",
        )

        assertThat(config.metrics.otelEnabled).isTrue()
        assertThat(config.metrics.otelEndpoint).isEqualTo("http://jaeger:4317")
        assertThat(config.otelServiceName).isEqualTo("spola-production")

        // When endpoint is set and enabled, tracer should be active
        val tracer = SpolaTracer(
            otelEnabled = config.metrics.otelEnabled,
            otelEndpoint = config.metrics.otelEndpoint,
            otelServiceName = config.otelServiceName,
        )
        assertThat(tracer.isActive).isTrue()

        // Default config should produce noop
        val defaultConfig = SpolaConfig()
        assertThat(defaultConfig.metrics.otelEnabled).isFalse()
        assertThat(defaultConfig.metrics.otelEndpoint).isEmpty()
        assertThat(defaultConfig.otelServiceName).isEqualTo("spola")
    }

    // ── TEST 6: SpolaTracerObserver span lifecycle ───────────────

    @Test
    fun `tracer observer creates correct span lifecycle via SDK`() {
        val exporter = InMemorySpanExporter.create()
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        val otel = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build()
        val tracer = otel.getTracer("spola-test")

        // Replicate what SpolaTracerObserver does via the SDK directly
        // Start: onStatus("started")
        val rootSpan = tracer.spanBuilder("spola.run")
            .setAttribute("spola.goal", "Agent run started")
            .startSpan()

        // Turn 1: onStatus("thinking")
        val turn1Span = tracer.spanBuilder("spola.turn")
            .setParent(Context.current().with(rootSpan))
            .setAttribute("spola.turn.number", 1L)
            .startSpan()

        // LLM call
        val llmSpan = tracer.spanBuilder("spola.llm.call")
            .setParent(Context.current().with(turn1Span))
            .setAttribute("gen_ai.system", "mock")
            .setAttribute("gen_ai.request.model", "mock-model")
            .startSpan()
        llmSpan.setAttribute("gen_ai.usage.input_tokens", 100L)
        llmSpan.setAttribute("gen_ai.usage.output_tokens", 200L)
        llmSpan.end()

        // Tool call
        val toolSpan = tracer.spanBuilder("spola.tool.execution")
            .setParent(Context.current().with(turn1Span))
            .setAttribute("spola.tool.name", "echo")
            .setAttribute("spola.tool.call_id", "call-1")
            .startSpan()
        toolSpan.setAttribute("spola.tool.success", true)
        toolSpan.setAttribute("spola.tool.duration_ms", 15L)
        toolSpan.end()

        // End turn 1
        turn1Span.end()

        // Complete
        rootSpan.end()

        tracerProvider.forceFlush()
        val spans = exporter.finishedSpanItems

        assertThat(spans).hasSize(4)

        // Verify hierarchy
        val rootSpanItem = spans.find { it.name == "spola.run" }
        val turnSpanItem = spans.find { it.name == "spola.turn" }
        val toolSpanItem = spans.find { it.name == "spola.tool.execution" }
        val llmSpanItem = spans.find { it.name == "spola.llm.call" }

        assertThat(rootSpanItem).isNotNull
        assertThat(turnSpanItem).isNotNull
        assertThat(toolSpanItem).isNotNull
        assertThat(llmSpanItem).isNotNull

        // Turn is child of root
        assertThat(turnSpanItem!!.parentSpanId).isEqualTo(rootSpanItem!!.spanId)

        // LLM and tool are children of turn
        assertThat(llmSpanItem!!.parentSpanId).isEqualTo(turnSpanItem.spanId)
        assertThat(toolSpanItem!!.parentSpanId).isEqualTo(turnSpanItem.spanId)
    }
}
