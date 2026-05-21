package dev.spola.metrics

import dev.spola.AgentRunObserver
import dev.spola.ToolCall
import dev.spola.ToolResult

/**
 * An [AgentRunObserver] implementation that records Prometheus metrics
 * via [SpolaMetrics]. Designed to be composed into the observer chain,
 * wrapping [next] like a decorator.
 */
class MetricsObserver(
    private val metrics: SpolaMetrics,
    private val next: AgentRunObserver? = null,
) : AgentRunObserver {

    private var runStartTime = 0L
    private var toolCallTimestamps = mutableMapOf<String, Long>()

    override suspend fun onStatus(status: String, message: String?) {
        when (status) {
            "started" -> {
                runStartTime = System.nanoTime()
                metrics.incActiveSessions()
            }
            "thinking" -> {
                metrics.recordTurn()
            }
            "complete" -> {
                val duration = (System.nanoTime() - runStartTime) / 1_000_000_000.0
                metrics.recordAgentRun(status = "success", durationSeconds = duration)
                metrics.decActiveSessions()
            }
        }
        next?.onStatus(status, message)
    }

    override suspend fun onToken(text: String) {
        next?.onToken(text)
    }

    override suspend fun onToolCall(toolCall: ToolCall) {
        toolCallTimestamps[toolCall.id] = System.nanoTime()
        next?.onToolCall(toolCall)
    }

    override suspend fun onToolResult(toolCall: ToolCall, result: ToolResult) {
        val startNanos = toolCallTimestamps.remove(toolCall.id)
        val duration = startNanos?.let {
            (System.nanoTime() - it) / 1_000_000_000.0
        } ?: 0.0
        val status = if (result.success) "success" else "fail"
        metrics.recordToolCall(tool = toolCall.name, status = status, durationSeconds = duration)
        next?.onToolResult(toolCall, result)
    }

    override suspend fun onError(error: Throwable) {
        val duration = if (runStartTime > 0) {
            (System.nanoTime() - runStartTime) / 1_000_000_000.0
        } else {
            0.0
        }
        metrics.recordAgentRun(status = "fail", durationSeconds = duration)
        metrics.decActiveSessions()
        next?.onError(error)
    }

    override suspend fun onLlmCall(model: String, provider: String) {
        metrics.recordLlmCall(provider = provider, model = model)
        next?.onLlmCall(model, provider)
    }

    override suspend fun onLlmResult(model: String, provider: String, inputTokens: Int?, outputTokens: Int?) {
        if (inputTokens != null && outputTokens != null) {
            metrics.recordLlmTokens(inputTokens = inputTokens, outputTokens = outputTokens)
        }
        next?.onLlmResult(model, provider, inputTokens, outputTokens)
    }
}
