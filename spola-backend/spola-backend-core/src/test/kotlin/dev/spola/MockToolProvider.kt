package dev.spola

import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ToolCall as TramaiToolCall
import dev.tramai.core.provider.ModelProvider

/**
 * Mock LLM provider that simulates tool-calling and text responses.
 * Used to test the ReAct loop deterministically without an actual LLM.
 */
class MockToolProvider(
    private val responses: MutableList<ModelResponse> = mutableListOf(),
) : ModelProvider {
    private var callCount = 0
    var lastRequest: dev.tramai.core.model.ModelRequest? = null

    fun addTextResponse(text: String) {
        responses.add(ModelResponse(content = text, finishReason = FinishReason.STOP))
    }

    fun addToolResponse(vararg toolCalls: TramaiToolCall) {
        responses.add(ModelResponse(
            content = "",
            toolCalls = toolCalls.toList(),
            finishReason = FinishReason.STOP,
        ))
    }

    override suspend fun complete(request: dev.tramai.core.model.ModelRequest): ModelResponse {
        lastRequest = request
        val idx = callCount
        callCount++
        return if (idx < responses.size) {
            responses[idx]
        } else {
            ModelResponse(content = "Fallback: no more responses defined", finishReason = FinishReason.STOP)
        }
    }

    override fun providerId(): String = "mock-tool-provider"
}
