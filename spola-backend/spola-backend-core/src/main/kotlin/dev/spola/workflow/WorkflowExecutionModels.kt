package dev.spola.workflow

import kotlinx.serialization.Serializable

enum class WorkflowExecutionStatus {
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    CANCEL_REQUESTED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

@Serializable
data class WorkflowExecutionRecord(
    val id: String,
    val definitionId: String?,
    val workflowName: String,
    val status: WorkflowExecutionStatus,
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
    val checkpointKey: String? = null,
    val resumable: Boolean = false,
    val priority: Int = 0,
    val claimantId: String? = null,
    val claimedAt: Long? = null,
)

data class NewWorkflowExecution(
    val definitionId: String?,
    val workflowName: String,
    val userId: String? = null,
    val sessionId: String? = null,
    val triggerSource: String? = null,
    val triggerRef: String? = null,
    val inputJson: String = "{}",
    val priority: Int = 0,
)

data class WorkflowBootRecovery(
    val failedRunningIds: List<String>,
    val requeuedIds: List<String>,
)

@Serializable
data class WorkflowExecutionInput(
    val goal: String,
    val parametersJson: String = "{}",
    val workingDirectory: String? = null,
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
data class ResumeRequest(
    val checkpointKey: String? = null,
)
