package dev.spola.workflow

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WorkflowRunToolInput(
    val workflowName: String,
    val goal: String,
    val definitionId: String? = null,
    val sessionId: String? = null,
    val inputJson: String = "{}",
)

fun registerWorkflowTools(registry: ToolRegistry, service: WorkflowExecutionService) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    registry.register(
        Tool(
            name = "workflow_run",
            description = "Queue a workflow execution and return its execution id.",
            parameters = listOf(
                ToolParameter(
                    name = "inputJson",
                    description = "JSON-serialized WorkflowRunToolInput payload.",
                    type = ToolParameterType.STRING,
                ),
            ),
            execute = { args ->
                val raw = args["inputJson"] as? String
                    ?: return@Tool ToolResult.fail("Missing inputJson")
                val input = json.decodeFromString<WorkflowRunToolInput>(raw)
                try {
                    val execution = service.enqueue(
                        NewWorkflowExecution(
                            definitionId = input.definitionId,
                            workflowName = input.workflowName,
                            sessionId = input.sessionId,
                            triggerSource = "tool",
                            inputJson = json.encodeToString(
                                WorkflowExecutionInput(
                                    goal = input.goal,
                                    parametersJson = input.inputJson,
                                ),
                            ),
                        ),
                        parentNestingDepth = 0,
                    )
                    ToolResult.ok(execution.id)
                } catch (e: IllegalStateException) {
                    ToolResult.fail(e.message ?: "Workflow execution rejected")
                }
            },
        ),
    )
}
