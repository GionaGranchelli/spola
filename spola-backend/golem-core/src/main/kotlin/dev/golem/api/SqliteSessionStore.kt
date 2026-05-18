package dev.spola.api

import dev.spola.ChatMessage
import dev.spola.checkpoint.toChatMessage
import dev.spola.checkpoint.toSerializable
import dev.spola.sqlite.SqliteStoreSupport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.nio.file.Files
import java.nio.file.Paths

/**
 * SQLite-backed session store using Exposed.
 * Replaces the in-memory ConcurrentHashMap for session persistence.
 */
class SqliteSessionStore(dbPath: String) : AutoCloseable {
    private val json = Json {
        explicitNulls = false
    }

    private val database = SqliteStoreSupport.connectSqliteDatabase(dbPath)

    init {
        val path = Paths.get(dbPath).toAbsolutePath()
        val parent = path.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        runBlocking {
            SqliteStoreSupport.retryingTransaction(database) {
                SchemaUtils.create(Sessions, SessionMessages)
            }
        }
    }

    object Sessions : Table("sessions") {
        val id = varchar("id", 128)
        val title = varchar("title", 512)
        val createdAt = long("created_at")
        val lastActiveAt = long("last_active_at")
        val modelId = varchar("model_id", 256)
        val providerId = varchar("provider_id", 256).default("")

        override val primaryKey = PrimaryKey(id)
    }

    object SessionMessages : Table("session_messages") {
        val sessionId = varchar("session_id", 128)
        val ordinal = integer("ordinal")
        val messageJson = text("message_json")

        override val primaryKey = PrimaryKey(sessionId, ordinal)
    }

    fun create(session: SessionInfo): SessionInfo {
        runBlocking {
            SqliteStoreSupport.retryingTransaction(database) {
                Sessions.insert {
                    it[id] = session.id
                    it[title] = session.title
                    it[createdAt] = session.createdAt
                    it[lastActiveAt] = session.lastActiveAt
                    it[modelId] = session.modelId
                    it[providerId] = session.providerId
                }
            }
        }
        return session
    }

    fun get(id: String): SessionInfo? = runBlocking {
        SqliteStoreSupport.retryingTransaction(database) {
            Sessions.selectAll().where { Sessions.id eq id }
                .singleOrNull()
                ?.let { rowToSession(it) }
        }
    }

    fun list(): List<SessionInfo> = runBlocking {
        SqliteStoreSupport.retryingTransaction(database) {
            Sessions.selectAll()
                .orderBy(Sessions.lastActiveAt, SortOrder.DESC)
                .map { rowToSession(it) }
        }
    }

    fun update(session: SessionInfo): SessionInfo? = runBlocking {
        SqliteStoreSupport.retryingTransaction(database) {
            val existing = Sessions.selectAll().where { Sessions.id eq session.id }.singleOrNull()
                ?: return@retryingTransaction null
            Sessions.update({ Sessions.id eq session.id }) {
                it[title] = session.title
                it[lastActiveAt] = session.lastActiveAt
                it[modelId] = session.modelId
                it[providerId] = session.providerId
            }
            session
        }
    }

    fun delete(id: String): Boolean = runBlocking {
        SqliteStoreSupport.retryingTransaction(database) {
            SessionMessages.deleteWhere { SessionMessages.sessionId eq id }
            val count = Sessions.deleteWhere { Sessions.id eq id }
            count > 0
        }
    }

    fun updateLastActive(id: String, timestamp: Long): Boolean = runBlocking {
        SqliteStoreSupport.retryingTransaction(database) {
            val existing = Sessions.selectAll().where { Sessions.id eq id }.singleOrNull()
                ?: return@retryingTransaction false
            Sessions.update({ Sessions.id eq id }) {
                it[lastActiveAt] = timestamp
            }
            true
        }
    }

    fun getMessages(sessionId: String): List<ChatMessage> = runBlocking {
        SqliteStoreSupport.retryingTransaction(database) {
            SessionMessages.selectAll()
                .where { SessionMessages.sessionId eq sessionId }
                .orderBy(SessionMessages.ordinal, SortOrder.ASC)
                .map { row ->
                    json.decodeFromString<StoredSessionMessage>(row[SessionMessages.messageJson]).toChatMessage()
                }
        }
    }

    fun replaceMessages(sessionId: String, messages: List<ChatMessage>) {
        runBlocking {
            SqliteStoreSupport.retryingTransaction(database) {
                SessionMessages.deleteWhere { SessionMessages.sessionId eq sessionId }
                messages.forEachIndexed { index, message ->
                    SessionMessages.insert {
                        it[SessionMessages.sessionId] = sessionId
                        it[ordinal] = index
                        it[messageJson] = json.encodeToString(StoredSessionMessage.from(message))
                    }
                }
            }
        }
    }

    suspend fun addMessage(sessionId: String, message: ChatMessage) {
        SqliteStoreSupport.retryingTransaction(database) {
            val maxOrdinal = SessionMessages.selectAll()
                .where { SessionMessages.sessionId eq sessionId }
                .maxOfOrNull { it[SessionMessages.ordinal] } ?: -1
            val nextOrdinal = maxOrdinal + 1
            SessionMessages.insert {
                it[SessionMessages.sessionId] = sessionId
                it[ordinal] = nextOrdinal
                it[messageJson] = json.encodeToString(StoredSessionMessage.from(message))
            }
            Sessions.update({ Sessions.id eq sessionId }) {
                it[lastActiveAt] = System.currentTimeMillis()
            }
        }
    }

    private fun rowToSession(row: ResultRow) = SessionInfo(
        id = row[Sessions.id],
        title = row[Sessions.title],
        createdAt = row[Sessions.createdAt],
        lastActiveAt = row[Sessions.lastActiveAt],
        modelId = row[Sessions.modelId],
        providerId = row[Sessions.providerId],
    )

    override fun close() {
        // Exposed manages connection lifecycle
    }
}

@Serializable
private data class StoredSessionMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<StoredSessionToolCall>? = null,
) {
    fun toChatMessage(): ChatMessage = dev.spola.checkpoint.SerializableMessage(
        role = role,
        content = content,
        toolCallId = toolCallId,
        toolName = toolName,
        toolCalls = toolCalls?.map {
            dev.spola.checkpoint.SerializableToolCall(
                id = it.id,
                name = it.name,
                argumentsJson = it.argumentsJson,
            )
        },
    ).toChatMessage()

    companion object {
        fun from(message: ChatMessage): StoredSessionMessage {
            val serializable = message.toSerializable()
            return StoredSessionMessage(
                role = serializable.role,
                content = serializable.content,
                toolCallId = serializable.toolCallId,
                toolName = serializable.toolName,
                toolCalls = serializable.toolCalls?.map {
                    StoredSessionToolCall(
                        id = it.id,
                        name = it.name,
                        argumentsJson = it.argumentsJson,
                    )
                },
            )
        }
    }
}

@Serializable
private data class StoredSessionToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String = "{}",
)
