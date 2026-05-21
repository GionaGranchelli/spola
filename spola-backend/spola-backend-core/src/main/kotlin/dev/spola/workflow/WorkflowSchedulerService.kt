package dev.spola.workflow

import dev.spola.SpolaConfig
import dev.spola.scheduler.ScheduledJob
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkflowSchedulerService(
    private val executionService: WorkflowExecutionService,
    private val config: SpolaConfig,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun executeScheduledJob(job: ScheduledJob): WorkflowExecutionRecord? {
        val definitionId = job.workflowDefinitionId ?: return null
        return executionService.enqueue(
            NewWorkflowExecution(
                definitionId = definitionId,
                workflowName = definitionId,
                userId = null,
                sessionId = null,
                triggerSource = "scheduler",
                triggerRef = job.id,
                inputJson = json.encodeToString(
                    WorkflowExecutionInput(
                        goal = job.goal,
                        parametersJson = job.parametersJson ?: "{}",
                    ),
                ),
            ),
        )
    }
}
