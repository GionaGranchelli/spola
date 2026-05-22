package dev.spola.observability

import dev.spola.metrics.SpolaMetrics
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObserver
import io.prometheus.client.CollectorRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SpolaOperationObserverTest {

    private lateinit var registry: CollectorRegistry
    private lateinit var metrics: SpolaMetrics
    private lateinit var observer: SpolaOperationObserver

    @BeforeEach
    fun setUp() {
        registry = CollectorRegistry()
        metrics = SpolaMetrics(registry = registry, isEnabled = true)
        observer = SpolaOperationObserver(metrics)
    }

    @AfterEach
    fun tearDown() {
        // No cleanup needed for per-test registries
    }

    // -----------------------------------------------------------------------
    // Interface contract
    // -----------------------------------------------------------------------

    @Test
    fun `implements OperationObserver`() {
        assertIs<OperationObserver>(observer)
    }

    @Test
    fun `onCallStarted returns non-null observation`() {
        val ctx = OperationCallContext(
            serviceInterface = "dev.test.TestService",
            methodName = "execute",
            providerId = "openai",
            requestedModel = "gpt-4o",
            attempt = 0,
        )
        val observation = observer.onCallStarted(ctx)
        assertNotNull(observation)
    }

    // -----------------------------------------------------------------------
    // LLM call recording
    // -----------------------------------------------------------------------

    @Test
    fun `onCallStarted records LLM call with provider and model labels`() {
        val ctx = OperationCallContext(
            serviceInterface = "dev.test.TestService",
            methodName = "execute",
            providerId = "test-provider",
            requestedModel = "test-model",
            attempt = 0,
        )

        observer.onCallStarted(ctx)

        val llmCalls = registry.getSampleValue(
            "spola_llm_calls_total",
            arrayOf("provider", "model"),
            arrayOf("test-provider", "test-model"),
        )
        assertEquals(1.0, llmCalls, 0.001)
    }

    @Test
    fun `multiple onCallStarted increments LLM counter`() {
        val ctx = OperationCallContext(
            serviceInterface = "dev.test.TestService",
            methodName = "execute",
            providerId = "openai",
            requestedModel = "gpt-4o",
            attempt = 0,
        )

        observer.onCallStarted(ctx)
        observer.onCallStarted(ctx)
        observer.onCallStarted(ctx)

        val llmCalls = registry.getSampleValue(
            "spola_llm_calls_total",
            arrayOf("provider", "model"),
            arrayOf("openai", "gpt-4o"),
        )
        assertEquals(3.0, llmCalls, 0.001)
    }

    @Test
    fun `different provider labels produce separate counters`() {
        observer.onCallStarted(
            OperationCallContext(
                serviceInterface = "test",
                methodName = "m1",
                providerId = "provider-a",
                requestedModel = "model-a",
                attempt = 0,
            ),
        )
        observer.onCallStarted(
            OperationCallContext(
                serviceInterface = "test",
                methodName = "m2",
                providerId = "provider-b",
                requestedModel = "model-b",
                attempt = 0,
            ),
        )

        val a = registry.getSampleValue(
            "spola_llm_calls_total",
            arrayOf("provider", "model"),
            arrayOf("provider-a", "model-a"),
        )
        val b = registry.getSampleValue(
            "spola_llm_calls_total",
            arrayOf("provider", "model"),
            arrayOf("provider-b", "model-b"),
        )
        assertEquals(1.0, a, 0.001)
        assertEquals(1.0, b, 0.001)
    }

    // -----------------------------------------------------------------------
    // Token recording
    // -----------------------------------------------------------------------

    @Test
    fun `onProviderResponse records input and output tokens`() {
        val observation = observer.onCallStarted(
            OperationCallContext(
                serviceInterface = "test",
                methodName = "test",
                providerId = "openai",
                requestedModel = "gpt-4o",
                attempt = 0,
            ),
        )

        val response = ModelResponse(
            content = "Hello world",
            inputTokens = 50,
            outputTokens = 150,
        )
        observation.onProviderResponse(response)

        val inputTokens = registry.getSampleValue(
            "spola_llm_tokens_total",
            arrayOf("type"),
            arrayOf("input"),
        )
        val outputTokens = registry.getSampleValue(
            "spola_llm_tokens_total",
            arrayOf("type"),
            arrayOf("output"),
        )
        assertEquals(50.0, inputTokens, 0.001)
        assertEquals(150.0, outputTokens, 0.001)
    }

    @Test
    fun `onProviderResponse accumulates tokens across multiple calls`() {
        val observation = observer.onCallStarted(
            OperationCallContext(
                serviceInterface = "test",
                methodName = "test",
                providerId = "openai",
                requestedModel = "gpt-4o",
                attempt = 0,
            ),
        )

        observation.onProviderResponse(
            ModelResponse(content = "A", inputTokens = 10, outputTokens = 20),
        )
        observation.onProviderResponse(
            ModelResponse(content = "B", inputTokens = 30, outputTokens = 40),
        )

        val inputTokens = registry.getSampleValue(
            "spola_llm_tokens_total",
            arrayOf("type"),
            arrayOf("input"),
        )
        val outputTokens = registry.getSampleValue(
            "spola_llm_tokens_total",
            arrayOf("type"),
            arrayOf("output"),
        )
        assertEquals(40.0, inputTokens, 0.001)
        assertEquals(60.0, outputTokens, 0.001)
    }

    @Test
    fun `onProviderResponse with null tokens does not break`() {
        val observation = observer.onCallStarted(
            OperationCallContext(
                serviceInterface = "test",
                methodName = "test",
                providerId = "openai",
                requestedModel = "gpt-4o",
                attempt = 0,
            ),
        )

        // Should not throw
        observation.onProviderResponse(ModelResponse(content = "No usage"))
    }

    // -----------------------------------------------------------------------
    // Failure recording
    // -----------------------------------------------------------------------

    @Test
    fun `onProviderFailure does not throw`() {
        val observation = observer.onCallStarted(
            OperationCallContext(
                serviceInterface = "test",
                methodName = "test",
                providerId = "openai",
                requestedModel = "gpt-4o",
                attempt = 0,
            ),
        )

        // Should not throw - failures are a no-op in SpolaMetrics for now
        observation.onProviderFailure(RuntimeException("API error"))
        observation.onProviderFailure(IllegalStateException("Rate limited"))
    }

    // -----------------------------------------------------------------------
    // Other observation callbacks
    // -----------------------------------------------------------------------

    @Test
    fun `onStructuredParseFailure does not throw`() {
        val observation = observer.onCallStarted(
            OperationCallContext(
                serviceInterface = "test",
                methodName = "test",
                providerId = "openai",
                requestedModel = "gpt-4o",
                attempt = 0,
            ),
        )

        observation.onStructuredParseFailure(
            rawResponse = "{ invalid json }",
            errorSummary = "Failed to parse: unexpected token",
        )
    }

    @Test
    fun `onEngineEvent does not throw`() {
        val observation = observer.onCallStarted(
            OperationCallContext(
                serviceInterface = "test",
                methodName = "test",
                providerId = "openai",
                requestedModel = "gpt-4o",
                attempt = 0,
            ),
        )

        observation.onEngineEvent(
            name = "tramai.circuit.opened",
            attributes = mapOf("provider_id" to "openai"),
        )
    }

    @Test
    fun `onCallCompleted does not throw`() {
        val observation = observer.onCallStarted(
            OperationCallContext(
                serviceInterface = "test",
                methodName = "test",
                providerId = "openai",
                requestedModel = "gpt-4o",
                attempt = 0,
            ),
        )

        observation.onCallCompleted(parseSuccess = true)
        observation.onCallCompleted(parseSuccess = false)
        observation.onCallCompleted(parseSuccess = null)
    }

    // -----------------------------------------------------------------------
    // Disabled metrics
    // -----------------------------------------------------------------------

    @Test
    fun `all methods are no-ops when metrics is disabled`() {
        val disabledMetrics = SpolaMetrics(registry = registry, isEnabled = false)
        val disabledObserver = SpolaOperationObserver(disabledMetrics)

        val observation = disabledObserver.onCallStarted(
            OperationCallContext(
                serviceInterface = "test",
                methodName = "test",
                providerId = "openai",
                requestedModel = "gpt-4o",
                attempt = 0,
            ),
        )

        // Should not throw despite disabled metrics
        observation.onProviderResponse(ModelResponse(content = "hi", inputTokens = 10, outputTokens = 20))
        observation.onProviderFailure(RuntimeException("fail"))
        observation.onStructuredParseFailure("raw", "err")
        observation.onEngineEvent("evt", emptyMap())
        observation.onCallCompleted(parseSuccess = null)

        // Verify no counters were incremented
        val llmCalls = registry.getSampleValue(
            "spola_llm_calls_total",
            arrayOf("provider", "model"),
            arrayOf("openai", "gpt-4o"),
        )
        assertEquals(null, llmCalls, "No metrics should exist when disabled")
    }
}
