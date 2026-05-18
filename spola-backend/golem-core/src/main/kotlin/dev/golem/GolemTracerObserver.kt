package dev.spola

/**
 * An [AgentRunObserver] implementation that creates OpenTelemetry spans via [GolemTracer].
 *
 * Acts as a decorator: wraps [next] observer so tracing can coexist with other observers.
 */
class GolemTracerObserver(
    private val tracer: GolemTracer,
    private val config: GolemConfig,
    private val next: AgentRunObserver? = null,
) : AgentRunObserver {

    private var turnCounter = 0
    private var toolCallTimestamps = mutableMapOf<String, Long>()

    override suspend fun onStatus(status: String, message: String?) {
        when (status) {
            "started" -> {
                turnCounter = 0
                tracer.startRootSpan()
            }
            "thinking" -> {
                // A new turn is starting
                if (turnCounter > 0) {
                    tracer.endTurnSpan(turnCounter)
                }
                turnCounter++
                tracer.startTurnSpan(turnCounter)
            }
            "complete" -> {
                if (turnCounter > 0) {
                    tracer.endTurnSpan(turnCounter)
                    turnCounter = 0
                }
                tracer.endRootSpan()
            }
        }
        next?.onStatus(status, message)
    }

    override suspend fun onToken(text: String) {
        next?.onToken(text)
    }

    override suspend fun onToolCall(toolCall: ToolCall) {
        tracer.startToolSpan(
            toolName = toolCall.name,
            toolCallId = toolCall.id,
        )
        toolCallTimestamps[toolCall.id] = System.nanoTime()
        next?.onToolCall(toolCall)
    }

    override suspend fun onToolResult(toolCall: ToolCall, result: ToolResult) {
        val startNanos = toolCallTimestamps.remove(toolCall.id)
        val durationMs = startNanos?.let {
            (System.nanoTime() - it) / 1_000_000
        }
        tracer.endToolSpan(
            toolCallId = toolCall.id,
            success = result.success,
            durationMs = durationMs,
        )
        next?.onToolResult(toolCall, result)
    }

    override suspend fun onError(error: Throwable) {
        tracer.failRootSpan(error)
        next?.onError(error)
    }

    override suspend fun onLlmCall(model: String, provider: String) {
        tracer.startLlmCallSpan(model, provider)
        next?.onLlmCall(model, provider)
    }

    override suspend fun onLlmResult(model: String, provider: String, inputTokens: Int?, outputTokens: Int?) {
        tracer.endLlmCallSpan(inputTokens, outputTokens)
        next?.onLlmResult(model, provider, inputTokens, outputTokens)
    }
}
