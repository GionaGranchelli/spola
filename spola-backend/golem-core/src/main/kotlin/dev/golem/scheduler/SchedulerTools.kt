package dev.spola.scheduler

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult

fun registerSchedulerTools(
    registry: ToolRegistry,
    jobStore: GolemJobStore,
) {
    registry.register(Tool(
        name = "scheduler_add",
        description = "Create a scheduled job that runs a goal on a cron expression.",
        parameters = listOf(
            ToolParameter("name", "Human-friendly job name.", ToolParameterType.STRING),
            ToolParameter("cronExpression", "Five-field cron expression.", ToolParameterType.STRING),
            ToolParameter("goal", "Goal to run when the job fires.", ToolParameterType.STRING),
            ToolParameter("enabled", "Whether the job starts enabled.", ToolParameterType.BOOLEAN, required = false, defaultValue = true),
            ToolParameter("workflowDefinitionId", "Optional workflow definition ID to run", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            val name = args.requireString("name")
            val cronExpression = args.requireString("cronExpression")
            val goal = args.requireString("goal")
            val enabled = args["enabled"] as? Boolean ?: true
            val workflowDefinitionId = args["workflowDefinitionId"] as? String

            val job = jobStore.add(
                name = name,
                goal = goal,
                cronExpression = cronExpression,
                enabled = enabled,
                workflowDefinitionId = workflowDefinitionId,
            )

            ToolResult.ok(
                """
                Scheduled job created:
                id: ${job.id}
                name: ${job.name}
                cron: ${job.cronExpression}
                enabled: ${job.enabled}
                nextRunAt: ${job.nextRunAt}
                """.trimIndent(),
            )
        },
    ))

    registry.register(Tool(
        name = "scheduler_list",
        description = "List all scheduled jobs with their next and last run times.",
        parameters = emptyList(),
        execute = {
            val jobs = jobStore.list()
            if (jobs.isEmpty()) {
                ToolResult.ok("No scheduled jobs.")
            } else {
                ToolResult.ok(
                    jobs.joinToString(separator = "\n\n") { job ->
                        """
                        id: ${job.id}
                        name: ${job.name}
                        cron: ${job.cronExpression}
                        enabled: ${job.enabled}
                        nextRunAt: ${job.nextRunAt}
                        lastRunAt: ${job.lastRunAt ?: "never"}
                        """.trimIndent()
                    },
                )
            }
        },
    ))

    registry.register(Tool(
        name = "scheduler_remove",
        description = "Remove a scheduled job by id.",
        parameters = listOf(
            ToolParameter("id", "Scheduled job id.", ToolParameterType.STRING),
        ),
        execute = { args ->
            val id = args.requireString("id")
            if (jobStore.remove(id)) {
                ToolResult.ok("Removed scheduled job $id")
            } else {
                ToolResult.fail("Scheduled job not found: $id")
            }
        },
    ))
}

private fun Map<String, Any>.requireString(name: String): String {
    val value = this[name] as? String
    require(!value.isNullOrBlank()) { "$name must be provided" }
    return value
}
