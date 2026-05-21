package dev.spola.kanban

data class KanbanTask(
    val id: String,
    val title: String,
    val description: String? = null,
    val status: String, // "todo", "in_progress", "blocked", "done"
    val priority: String? = null, // "low", "medium", "high", "critical"
    val labels: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
