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
)
