package dev.spola.workflow

import dev.spola.kanban.KanbanTask
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkflowKanbanService(
    private val executionService: WorkflowExecutionService,
    private val cooldownSeconds: Long = 30,
    private val workflowName: String = "code-review",
    private val currentEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val recentTransitions = ConcurrentHashMap<String, Long>()

    suspend fun onTaskStatusChanged(task: KanbanTask, oldStatus: String, newStatus: String): WorkflowExecutionRecord? {
        val transition = "$oldStatus->$newStatus"
        val now = currentEpochSeconds()
        val windowStart = (now / cooldownSeconds) * cooldownSeconds
        val dedupKey = "kanban:${task.id}:$transition"
        val existing = recentTransitions[dedupKey]
        if (existing != null && existing == windowStart) {
            return null
        }

        val result = executionService.enqueue(
            NewWorkflowExecution(
                definitionId = "kanban-transition",
                workflowName = workflowName,
                triggerSource = "kanban",
                triggerRef = task.id,
                inputJson = json.encodeToString(
                    WorkflowExecutionInput(
                        goal = "Review changes in card: ${task.title}",
                        parametersJson = json.encodeToString(
                            mapOf(
                                "taskId" to task.id,
                                "transition" to transition,
                            ),
                        ),
                    ),
                ),
            ),
        )

        recentTransitions[dedupKey] = windowStart
        if (recentTransitions.size > MAX_TRANSITION_CACHE_SIZE) {
            val cutoff = windowStart - cooldownSeconds * 2
            recentTransitions.entries.removeIf { it.value < cutoff }
        }

        return result
    }

    private companion object {
        const val MAX_TRANSITION_CACHE_SIZE = 1000
    }
}
