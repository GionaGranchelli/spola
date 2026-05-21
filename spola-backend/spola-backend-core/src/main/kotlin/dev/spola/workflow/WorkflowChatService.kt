package dev.spola.workflow

import dev.spola.SystemMessage
import dev.spola.api.SqliteSessionStore

class WorkflowChatService(
    private val sessionStore: SqliteSessionStore,
) {
    suspend fun onWorkflowStarted(executionId: String, workflowName: String, sessionId: String) {
        sessionStore.addMessage(
            sessionId,
            SystemMessage("🚀 Workflow **$workflowName** avviato (execution: $executionId)"),
        )
    }

    suspend fun onWorkflowCompleted(executionId: String, workflowName: String, sessionId: String, result: String) {
        sessionStore.addMessage(
            sessionId,
            SystemMessage("✅ Workflow **$workflowName** completato.\n\n$result"),
        )
    }

    suspend fun onWorkflowFailed(executionId: String, workflowName: String, sessionId: String, error: String) {
        sessionStore.addMessage(
            sessionId,
            SystemMessage("❌ Workflow **$workflowName** fallito: $error"),
        )
    }
}
