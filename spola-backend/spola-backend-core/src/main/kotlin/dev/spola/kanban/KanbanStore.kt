package dev.spola.kanban

interface KanbanStore : AutoCloseable {
    suspend fun create(
        title: String,
        description: String? = null,
        status: String = "todo",
        priority: String? = null,
        labels: String? = null,
    ): KanbanTask

    suspend fun update(
        id: String,
        title: String? = null,
        description: String? = null,
        status: String? = null,
        priority: String? = null,
        labels: String? = null,
    ): KanbanTask?

    suspend fun list(status: String? = null): List<KanbanTask>

    suspend fun remove(id: String): Boolean

    suspend fun get(id: String): KanbanTask?
}
