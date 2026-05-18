package dev.spola.kanban

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult

fun registerKanbanTools(
    registry: ToolRegistry,
    store: KanbanStore,
) {
    registry.register(Tool(
        name = "task_create",
        description = "Create a new kanban task with title, optional description, status, priority, and labels.",
        parameters = listOf(
            ToolParameter("title", "Task title.", ToolParameterType.STRING),
            ToolParameter("description", "Optional task description.", ToolParameterType.STRING, required = false),
            ToolParameter("status", "Task status: todo, in_progress, blocked, or done (default: todo).", ToolParameterType.STRING, required = false, defaultValue = "todo"),
            ToolParameter("priority", "Optional priority: low, medium, high, or critical.", ToolParameterType.STRING, required = false),
            ToolParameter("labels", "Optional comma-separated labels.", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            val title = (args["title"] as? String)?.trim()
                ?: return@Tool ToolResult.fail("Missing required argument: title")
            if (title.isEmpty()) return@Tool ToolResult.fail("Title must not be empty")

            val status = (args["status"] as? String)?.trim()?.lowercase() ?: "todo"
            val validStatuses = setOf("todo", "in_progress", "blocked", "done")
            if (status !in validStatuses) {
                return@Tool ToolResult.fail("Invalid status: $status. Valid values: ${validStatuses.joinToString(", ")}")
            }

            val priority = (args["priority"] as? String)?.trim()?.lowercase()
            if (priority != null && priority !in setOf("low", "medium", "high", "critical")) {
                return@Tool ToolResult.fail("Invalid priority: $priority. Valid values: low, medium, high, critical")
            }

            val task = store.create(
                title = title,
                description = (args["description"] as? String)?.trim(),
                status = status,
                priority = priority,
                labels = (args["labels"] as? String)?.trim(),
            )

            ToolResult.ok(
                """
                Task created:
                id: ${task.id}
                title: ${task.title}
                status: ${task.status}
                ${task.priority?.let { "priority: $it\n" } ?: ""}${task.labels?.let { "labels: $it\n" } ?: ""}createdAt: ${task.createdAt}
                """.trimIndent(),
            )
        },
    ))

    registry.register(Tool(
        name = "task_update",
        description = "Update an existing kanban task. Only provided fields are changed.",
        parameters = listOf(
            ToolParameter("id", "Task id to update.", ToolParameterType.STRING),
            ToolParameter("title", "New title.", ToolParameterType.STRING, required = false),
            ToolParameter("description", "New description.", ToolParameterType.STRING, required = false),
            ToolParameter("status", "New status: todo, in_progress, blocked, done.", ToolParameterType.STRING, required = false),
            ToolParameter("priority", "New priority: low, medium, high, critical.", ToolParameterType.STRING, required = false),
            ToolParameter("labels", "New comma-separated labels.", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            val id = (args["id"] as? String)?.trim()
                ?: return@Tool ToolResult.fail("Missing required argument: id")

            val status = (args["status"] as? String)?.trim()?.lowercase()
            if (status != null && status !in setOf("todo", "in_progress", "blocked", "done")) {
                return@Tool ToolResult.fail("Invalid status: $status. Valid values: todo, in_progress, blocked, done")
            }

            val priority = (args["priority"] as? String)?.trim()?.lowercase()
            if (priority != null && priority !in setOf("low", "medium", "high", "critical")) {
                return@Tool ToolResult.fail("Invalid priority: $priority. Valid values: low, medium, high, critical")
            }

            val updated = store.update(
                id = id,
                title = (args["title"] as? String)?.trim(),
                description = (args["description"] as? String)?.trim(),
                status = status,
                priority = priority,
                labels = (args["labels"] as? String)?.trim(),
            )

            if (updated != null) {
                ToolResult.ok(
                    """
                    Task updated:
                    id: ${updated.id}
                    title: ${updated.title}
                    status: ${updated.status}
                    ${updated.priority?.let { "priority: $it\n" } ?: ""}${updated.labels?.let { "labels: $it\n" } ?: ""}updatedAt: ${updated.updatedAt}
                    """.trimIndent(),
                )
            } else {
                ToolResult.fail("Task not found: $id")
            }
        },
    ))

    registry.register(Tool(
        name = "task_list",
        description = "List all kanban tasks, optionally filtered by status.",
        parameters = listOf(
            ToolParameter("status", "Filter by status: todo, in_progress, blocked, done. Omit to list all.", ToolParameterType.STRING, required = false),
        ),
        execute = { args ->
            val status = (args["status"] as? String)?.trim()?.lowercase()
            if (status != null && status !in setOf("todo", "in_progress", "blocked", "done")) {
                return@Tool ToolResult.fail("Invalid status: $status. Valid values: todo, in_progress, blocked, done")
            }

            val tasks = store.list(status = status)
            if (tasks.isEmpty()) {
                ToolResult.ok("No tasks found${status?.let { " with status $it" } ?: ""}.")
            } else {
                ToolResult.ok(
                    tasks.joinToString(separator = "\n\n") { task ->
                        buildString {
                            appendLine("id: ${task.id}")
                            appendLine("title: ${task.title}")
                            appendLine("status: ${task.status}")
                            if (task.priority != null) appendLine("priority: ${task.priority}")
                            if (task.labels != null) appendLine("labels: ${task.labels}")
                        }.trimEnd()
                    },
                )
            }
        },
    ))

    registry.register(Tool(
        name = "task_delete",
        description = "Delete a kanban task by id.",
        parameters = listOf(
            ToolParameter("id", "Task id to delete.", ToolParameterType.STRING),
        ),
        execute = { args ->
            val id = (args["id"] as? String)?.trim()
                ?: return@Tool ToolResult.fail("Missing required argument: id")

            if (store.remove(id)) {
                ToolResult.ok("Deleted task $id")
            } else {
                ToolResult.fail("Task not found: $id")
            }
        },
    ))
}
