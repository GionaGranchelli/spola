package dev.spola.app.models

import kotlinx.serialization.Serializable

@Serializable
data class SessionModelUpdateRequest(
    val modelId: String
)

@Serializable
data class SessionProviderUpdateRequest(
    val providerId: String
)

@Serializable
data class PairingInfo(
    val host: String,
    val port: Int,
    val token: String,
    val trustId: String? = null
)

@Serializable
data class PairingInfoResponse(
    val host: String,
    val port: Int,
    val token: String,
    val trustId: String,
    val version: String,
)

@Serializable
data class SpolaAgentInfo(
    val id: String,
    val name: String? = null,
    val model: String? = null,
    val isDefault: Boolean = false
)

@Serializable
data class SpolaOptions(
    val agents: List<SpolaAgentInfo> = emptyList(),
    val models: List<String> = emptyList(),
    val thinkingLevels: List<String> = listOf("off", "minimal", "low", "medium", "high", "xhigh")
)

@Serializable
data class SpolaSessionSettings(
    val agentId: String? = null,
    val modelId: String? = null,
    val mode: String? = null,
    val thinking: String? = null
)

@Serializable
data class TranscriptionResponse(
    val text: String,
    val confidence: Float? = null
)

@Serializable
data class SynthesizeRequest(
    val text: String,
    val voice: String? = null,
)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val description: String? = null
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val modelId: String,
    val providerId: String = "ollama"
)

@Serializable
data class Message(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val attachments: List<FileMetadata>? = null
)

@Serializable
enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

@Serializable
data class BashCommandRequest(
    val command: String,
    val sessionId: String? = null,
    val approvalId: String? = null
)

@Serializable
data class BashCommandPreview(
    val approvalId: String,
    val command: String,
    val sessionId: String,
    val workingDirectory: String? = null,
    val status: CommandStatus = CommandStatus.PENDING
)

@Serializable
data class BashCommandResponse(
    val output: String,
    val exitCode: Int,
    val isError: Boolean = false,
    val approvalId: String? = null
)

@Serializable
data class StreamEvent(
    val type: StreamEventType,
    val content: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val thinkingTokens: Int? = null,
    val cumulativeInput: Long? = null,
    val cumulativeOutput: Long? = null,
    val cumulativeThinking: Long? = null,
)

@Serializable
data class CommandStreamEvent(
    val approvalId: String,
    val sessionId: String,
    val type: CommandStreamType,
    val content: String? = null,
    val exitCode: Int? = null
)

@Serializable
enum class CommandStreamType {
    STARTED, STDOUT, STDERR, COMPLETED, FAILED
}

@Serializable
data class SelectedSessionState(
    val sessionId: String? = null,
    val modelId: String? = null,
    val providerId: String? = null
)

@Serializable
data class ProviderInfo(
    val id: String,
    val name: String,
    val description: String? = null
)

@Serializable
data class BackendMeta(
    val version: String,
    val buildTime: String,
    val pid: Long,
)

@Serializable
data class FileMetadata(
    val id: String,
    val sessionId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val timestamp: Long
)

/**
 * Backend message response — returned by GET /api/session/{id}/messages.
 * The backend stores messages as (role: String, content: String) without
 * frontend fields like id, sessionId, timestamp.
 */
@Serializable
data class BackendMessage(
    val role: String,
    val content: String,
)

/** Convert a BackendMessage (from API) into a fully-formed Message for display. */
fun BackendMessage.toMessage(sessionId: String, id: String, timestamp: Long): Message {
    val messageRole = when (role.lowercase()) {
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        "system" -> MessageRole.SYSTEM
        else -> MessageRole.USER
    }
    return Message(
        id = id,
        sessionId = sessionId,
        role = messageRole,
        content = content,
        timestamp = timestamp,
    )
}

@Serializable
data class KanbanCard(
    val id: String,
    val text: String,
    val status: String,
    val createdAt: Long,
)

@Serializable
data class KanbanCardCreateRequest(
    val text: String,
)

@Serializable
data class KanbanCardUpdateRequest(
    val text: String,
    val status: String,
)

@Serializable
data class WorkflowDefinition(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
)

@Serializable
data class WorkflowToggleRequest(
    val enabled: Boolean,
)

@Serializable
data class WorkflowCreateRequest(
    val name: String,
    val description: String,
)

@Serializable
data class WorkflowUpdateRequest(
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class WorkflowExecutionRecord(
    val id: String,
    val definitionId: String? = null,
    val workflowName: String,
    val status: String,
    val userId: String? = null,
    val sessionId: String? = null,
    val triggerSource: String? = null,
    val triggerRef: String? = null,
    val inputJson: String = "{}",
    val outputJson: String? = null,
    val result: String? = null,
    val error: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class WorkflowRunRequest(
    val workflowName: String,
    val goal: String,
    val definitionId: String? = null,
    val sessionId: String? = null,
    val inputJson: String = "{}",
)

@Serializable
data class WorkflowRunResponse(
    val executionId: String,
)

@Serializable
data class WorkflowCheckpointResponse(
    val id: String,
    val executionId: String,
    val stepName: String,
    val stepIndex: Int = 0,
    val timestamp: Long,
    val stateSummary: String? = null,
    val resumable: Boolean = false,
)

@Serializable
data class WorkflowStepMetrics(
    val stepName: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val thinkingTokens: Int = 0,
    val durationMs: Long = 0,
    val status: String = "unknown",
    val startedAt: Long? = null,
    val completedAt: Long? = null,
)

@Serializable
data class WorkflowMetricsResponse(
    val executionId: String,
    val steps: List<WorkflowStepMetrics> = emptyList(),
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val totalThinkingTokens: Int = 0,
    val totalDurationMs: Long = 0,
)

@Serializable
data class GateDecisionRequest(
    val executionId: String,
    val stepName: String,
    val approved: Boolean,
    val reason: String? = null,
)

@Serializable
data class WorkflowStreamEvent(
    val type: String,
    val step: String? = null,
    val status: String? = null,
    val workflow: String? = null,
    val workflowId: String? = null,
    val message: String? = null,
    val error: String? = null,
    val executionId: String? = null,
)

@Serializable
data class SystemEvent(
    val type: SystemEventType,
    val details: String? = null
)

@Serializable
enum class SystemEventType {
    SESSIONS_CHANGED, TRUST_CHANGED
}

@Serializable
enum class StreamEventType {
    status, tool_call, tool_result, token, error, complete, token_usage
}

data class TokenUsageData(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val thinkingTokens: Int = 0,
    val cumulativeInput: Long = 0,
    val cumulativeOutput: Long = 0,
    val cumulativeThinking: Long = 0,
)

@Serializable
data class FileTransferRequest(
    val sessionId: String,
    val path: String,
    val content: String? = null,
    val direction: FileTransferDirection
)

@Serializable
data class FileTransferResponse(
    val sessionId: String,
    val path: String,
    val success: Boolean,
    val content: String? = null,
    val error: String? = null
)

@Serializable
enum class FileTransferDirection {
    PULL, PUSH
}

@Serializable
enum class CommandStatus {
    PENDING, APPROVED, RUNNING, COMPLETED, FAILED, CANCELED
}

@Serializable
data class TrustState(
    val host: String,
    val port: Int,
    val token: String,
    val trustId: String,
    val active: Boolean = true,
    val revokedAt: Long? = null,
    val rotatedAt: Long? = null,
    val previousToken: String? = null
)

@Serializable
data class AuditEvent(
    val id: String,
    val kind: String,
    val sessionId: String? = null,
    val approvalId: String? = null,
    val path: String? = null,
    val command: String? = null,
    val timestamp: Long,
    val details: String? = null
)

@Serializable
data class TrustRotationResponse(
    val trust: TrustState,
    val newToken: String
)
