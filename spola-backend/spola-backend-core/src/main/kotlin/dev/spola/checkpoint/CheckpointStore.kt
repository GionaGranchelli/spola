package dev.spola.checkpoint

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A single checkpoint snapshot saved to the database.
 */
@Serializable
data class Checkpoint(
    val id: Long,
    val sessionId: String,
    val turnNumber: Int,
    val conversationJson: String,
    val createdAt: String,
    val diff: String? = null,
)

/**
 * SQLite-backed checkpoint store using Exposed ORM.
 * Stores ReAct loop state snapshots for crash recovery and session resumption.
 */
class CheckpointStore(dbPath: String) : AutoCloseable {
    private val database = Database.connect(
        url = "jdbc:sqlite:$dbPath",
        driver = "org.sqlite.JDBC",
    )

    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    init {
        val path = java.nio.file.Paths.get(dbPath).toAbsolutePath()
        val parent = path.parent
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent)
        }

        transaction(database) {
            SchemaUtils.create(CheckpointsTable)
            // Schema migration: add diff column if missing (existing databases)
            try {
                exec("ALTER TABLE checkpoints ADD COLUMN diff TEXT")
            } catch (_: Exception) {
                // Column already exists, safe to ignore
            }
        }
    }

    object CheckpointsTable : Table("checkpoints") {
        val id = long("id").autoIncrement()
        val sessionId = varchar("session_id", 256)
        val turnNumber = integer("turn_number")
        val conversationJson = text("conversation_json")
        val createdAt = varchar("created_at", 32)
        val diff = text("diff").nullable()

        override val primaryKey = PrimaryKey(id)
        init {
            index("idx_checkpoints_session_id", false, sessionId)
            index("idx_checkpoints_created_at", false, createdAt)
        }
    }

    /**
     * Save a new checkpoint for the given session and turn.
     * Returns the generated checkpoint id.
     */
    fun save(sessionId: String, turnNumber: Int, conversationJson: String, diff: String? = null): Long {
        val now = LocalDateTime.now().format(formatter)
        return transaction(database) {
            CheckpointsTable.insert {
                it[CheckpointsTable.sessionId] = sessionId
                it[CheckpointsTable.turnNumber] = turnNumber
                it[CheckpointsTable.conversationJson] = conversationJson
                it[CheckpointsTable.createdAt] = now
                it[CheckpointsTable.diff] = diff
            } get CheckpointsTable.id
        }
    }

    /**
     * Load the most recent checkpoint for a session.
     * Returns null if no checkpoint exists for that session.
     */
    fun load(sessionId: String): Checkpoint? {
        return transaction(database) {
            CheckpointsTable.selectAll()
                .where { CheckpointsTable.sessionId eq sessionId }
                .orderBy(CheckpointsTable.turnNumber, SortOrder.DESC)
                .orderBy(CheckpointsTable.id, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.let(::rowToCheckpoint)
        }
    }

    /**
     * Load a specific checkpoint by its id.
     * Returns null if no checkpoint with that id exists.
     */
    fun loadById(id: Long): Checkpoint? {
        return transaction(database) {
            CheckpointsTable.selectAll()
                .where { CheckpointsTable.id eq id }
                .singleOrNull()
                ?.let(::rowToCheckpoint)
        }
    }

    /**
     * List all checkpoints, ordered by most recent first.
     */
    fun list(): List<Checkpoint> {
        return transaction(database) {
            CheckpointsTable.selectAll()
                .orderBy(CheckpointsTable.createdAt, SortOrder.DESC)
                .map(::rowToCheckpoint)
        }
    }

    /**
     * List checkpoints for a specific session, ordered by turn descending.
     */
    fun listForSession(sessionId: String): List<Checkpoint> {
        return transaction(database) {
            CheckpointsTable.selectAll()
                .where { CheckpointsTable.sessionId eq sessionId }
                .orderBy(CheckpointsTable.turnNumber, SortOrder.DESC)
                .map(::rowToCheckpoint)
        }
    }

    /**
     * Delete all checkpoints older than the given timestamp.
     * Returns the number of deleted checkpoints.
     */
    fun deleteOlderThan(olderThan: String): Int {
        return transaction(database) {
            CheckpointsTable.deleteWhere {
                CheckpointsTable.createdAt less olderThan
            }
        }
    }

    /**
     * Delete all checkpoints for a given session.
     */
    fun deleteForSession(sessionId: String): Int {
        return transaction(database) {
            CheckpointsTable.deleteWhere {
                CheckpointsTable.sessionId eq sessionId
            }
        }
    }

    /**
     * Get total checkpoint count.
     */
    fun count(): Long {
        return transaction(database) {
            CheckpointsTable.selectAll().count()
        }
    }

    private fun rowToCheckpoint(row: ResultRow): Checkpoint = Checkpoint(
        id = row[CheckpointsTable.id],
        sessionId = row[CheckpointsTable.sessionId],
        turnNumber = row[CheckpointsTable.turnNumber],
        conversationJson = row[CheckpointsTable.conversationJson],
        createdAt = row[CheckpointsTable.createdAt],
        diff = row[CheckpointsTable.diff],
    )

    override fun close() {
        // SQLite connection pool managed by Exposed
    }
}
