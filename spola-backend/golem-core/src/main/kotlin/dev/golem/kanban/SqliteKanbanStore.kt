package dev.spola.kanban

import dev.spola.sqlite.SqliteStoreSupport
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.util.UUID

class SqliteKanbanStore(
    dbPath: String,
    private val onStatusChanged: (suspend (KanbanTask, String, String) -> Unit)? = null,
) : KanbanStore {

    private val database = SqliteStoreSupport.connectSqliteDatabase(dbPath)

    init {
        runBlocking {
            SqliteStoreSupport.retryingTransaction(database) {
                SchemaUtils.create(Tasks)
            }
        }
    }

    object Tasks : Table("kanban_tasks") {
        val id = varchar("id", 64)
        val title = varchar("title", 512)
        val description = text("description").nullable()
        val status = varchar("status", 32)
        val priority = varchar("priority", 16).nullable()
        val labels = varchar("labels", 256).nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(id)
        init {
            index(false, status)
        }
    }

    override suspend fun create(
        title: String,
        description: String?,
        status: String,
        priority: String?,
        labels: String?,
    ): KanbanTask {
        val now = System.currentTimeMillis()
        val task = KanbanTask(
            id = UUID.randomUUID().toString(),
            title = title,
            description = description?.takeIf { it.isNotBlank() },
            status = status,
            priority = priority?.takeIf { it.isNotBlank() },
            labels = labels?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now,
        )
        SqliteStoreSupport.retryingTransaction(database) {
            Tasks.insert { row ->
                row[Tasks.id] = task.id
                row[Tasks.title] = task.title
                row[Tasks.description] = task.description
                row[Tasks.status] = task.status
                row[Tasks.priority] = task.priority
                row[Tasks.labels] = task.labels
                row[Tasks.createdAt] = task.createdAt
                row[Tasks.updatedAt] = task.updatedAt
            }
        }
        return task
    }

    override suspend fun update(
        id: String,
        title: String?,
        description: String?,
        status: String?,
        priority: String?,
        labels: String?,
    ): KanbanTask? {
        val now = System.currentTimeMillis()
        val change = SqliteStoreSupport.retryingTransaction(database) {
            val existing = Tasks.selectAll().where { Tasks.id eq id }.singleOrNull() ?: return@retryingTransaction null
            val oldStatus = existing[Tasks.status]
            Tasks.update({ Tasks.id eq id }) { row ->
                if (!title.isNullOrBlank()) row[Tasks.title] = title
                if (description != null) row[Tasks.description] = description.takeIf(String::isNotBlank)
                if (status != null) row[Tasks.status] = status
                if (priority != null) row[Tasks.priority] = priority.takeIf(String::isNotBlank)
                if (labels != null) row[Tasks.labels] = labels.takeIf(String::isNotBlank)
                row[Tasks.updatedAt] = now
            }
            val row = Tasks.selectAll().where { Tasks.id eq id }.single()
            val updatedTask = rowToTask(row)
            StatusChangeResult(
                task = updatedTask,
                oldStatus = oldStatus,
                newStatus = updatedTask.status,
            )
        }
        if (change != null && change.oldStatus != change.newStatus) {
            try {
                onStatusChanged?.invoke(change.task, change.oldStatus, change.newStatus)
            } catch (e: Exception) {
                System.err.println("[kanban] Status change callback failed for task ${change.task.id}: ${e.message}")
            }
        }
        return change?.task
    }

    override suspend fun list(status: String?): List<KanbanTask> = SqliteStoreSupport.retryingTransaction(database) {
        (if (status != null) {
            Tasks.selectAll().where { Tasks.status eq status }
        } else {
            Tasks.selectAll()
        })
            .orderBy(Tasks.updatedAt, SortOrder.DESC)
            .map(::rowToTask)
    }

    override suspend fun remove(id: String): Boolean = SqliteStoreSupport.retryingTransaction(database) {
        Tasks.deleteWhere { Tasks.id eq id } > 0
    }

    override suspend fun get(id: String): KanbanTask? = SqliteStoreSupport.retryingTransaction(database) {
        Tasks.selectAll().where { Tasks.id eq id }.singleOrNull()?.let(::rowToTask)
    }

    private fun rowToTask(row: ResultRow) = KanbanTask(
        id = row[Tasks.id],
        title = row[Tasks.title],
        description = row[Tasks.description],
        status = row[Tasks.status],
        priority = row[Tasks.priority],
        labels = row[Tasks.labels],
        createdAt = row[Tasks.createdAt],
        updatedAt = row[Tasks.updatedAt],
    )

    override fun close() {
        // SQLite resources managed by Exposed
    }

    private data class StatusChangeResult(
        val task: KanbanTask,
        val oldStatus: String,
        val newStatus: String,
    )
}
