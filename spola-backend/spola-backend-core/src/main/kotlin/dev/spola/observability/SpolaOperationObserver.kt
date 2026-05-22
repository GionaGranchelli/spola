package dev.spola.observability

import dev.spola.metrics.SpolaMetrics
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver

/**
 * Bridges TramAI's [OperationObserver] SPI to [SpolaMetrics].
 *
 * Each [onCallStarted] invocation creates a per-call [OperationObservation] that
 * records LLM calls, token usage, and provider failures into the Spola metrics
 * system. This enables TramAI engine-level observability through the same
 * Prometheus counters that [dev.spola.metrics.MetricsObserver] uses for the
 * ReAct loop.
 *
 * @param metrics the [SpolaMetrics] instance to record into
 */
class SpolaOperationObserver(
    private val metrics: SpolaMetrics,
) : OperationObserver {

    override fun onCallStarted(context: OperationCallContext): OperationObservation {
        metrics.recordLlmCall(
            provider = context.providerId,
            model = context.requestedModel,
        )

        return SpolaOperationObservation(metrics)
    }
}

/**
 * Per-call observation that records metrics events for a single provider attempt.
 *
 * Delegates token recording to [SpolaMetrics.recordLlmTokens] on provider
 * response and silently drops events that have no corresponding SpolaMetrics
 * counter (structured parse failures, engine events, call completion).
 */
private class SpolaOperationObservation(
    private val metrics: SpolaMetrics,
) : OperationObservation {

    override fun onProviderResponse(response: ModelResponse) {
        val inputTokens = response.inputTokens ?: return
        val outputTokens = response.outputTokens ?: return
        metrics.recordLlmTokens(inputTokens = inputTokens, outputTokens = outputTokens)
    }

    override fun onProviderFailure(error: Throwable) {
        // SpolaMetrics has no dedicated failure counter at this level;
        // failure tracking is handled by the AgentRunObserver chain (MetricsObserver).
        // Future enhancement: add a spola_llm_failures_total counter to SpolaMetrics.
    }

    override fun onStructuredParseFailure(
        rawResponse: String,
        errorSummary: String,
    ) {
        // Structured parse failures are not currently tracked in SpolaMetrics.
    }

    override fun onEngineEvent(
        name: String,
        attributes: Map<String, Any?>,
    ) {
        // Engine events (circuit breaker, retries, routing) are not currently
        // surfaced in SpolaMetrics. Tracked via OpenTelemetry if configured.
    }

    override fun onCallCompleted(parseSuccess: Boolean?) {
        // Metrics are recorded incrementally as events fire.
        // No aggregate counter needed at call completion.
    }
}
