package dev.spola.app.backend.repo

import dev.spola.app.db.SpolaDb
import dev.spola.app.models.Message
import dev.spola.app.models.MessageRole
import dev.spola.app.models.FileMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

interface MessageRepository {
    fun getBySessionId(sessionId: String): List<Message>
    fun create(message: Message)
}

class SqlMessageRepository(private val db: SpolaDb) : MessageRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override fun getBySessionId(sessionId: String): List<Message> {
        return db.spolaDbQueries.getMessagesBySessionId(sessionId).executeAsList().map {
            val attachments = it.attachments?.let { raw ->
                runCatching { json.decodeFromString<List<FileMetadata>>(raw) }.getOrNull()
            }
            Message(it.id, it.sessionId, MessageRole.valueOf(it.role), it.content, it.timestamp, attachments)
        }
    }

    override fun create(message: Message) {
        val attachmentsJson = message.attachments?.let { json.encodeToString(it) }
        db.spolaDbQueries.insertMessage(
            message.id,
            message.sessionId,
            message.role.name,
            message.content,
            message.timestamp,
            attachmentsJson
        )
    }
}
