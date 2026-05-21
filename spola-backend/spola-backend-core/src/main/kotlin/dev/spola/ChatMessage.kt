package dev.spola

/**
 * Messages in the agent's conversation history.
 */
sealed interface ChatMessage {
    val role: String
    val content: String
}

data class SystemMessage(override val content: String) : ChatMessage {
    override val role: String = "system"
}

data class UserMessage(override val content: String) : ChatMessage {
    override val role: String = "user"
}

data class AssistantMessage(
    override val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
) : ChatMessage {
    override val role: String = "assistant"
}

data class ToolResultMessage(
    val toolCallId: String,
    val toolName: String,
    override val content: String,
) : ChatMessage {
    override val role: String = "tool"
}

/**
 * A tool call returned by the LLM.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any>,
)
