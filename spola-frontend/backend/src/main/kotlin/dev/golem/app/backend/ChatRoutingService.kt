package dev.spola.app.backend

import dev.spola.app.db.OpenClawDb
import dev.spola.app.models.Message
import dev.spola.app.models.MessageRole
import dev.spola.app.models.FileMetadata
import dev.spola.app.state.AppStateStore
import dev.spola.app.backend.repo.MessageRepository
import dev.spola.app.backend.repo.FileRepository
import java.util.UUID

class ChatRoutingService(
    private val db: OpenClawDb,
    private val stateStore: AppStateStore,
    private val chatProviders: Map<String, ChatProvider>,
    private val messageRepository: MessageRepository,
    private val fileRepository: FileRepository
) {
    fun persistUserMessage(sessionId: String, content: String): Message {
        // Extract file IDs from content [file:id]
        val fileRegex = Regex("\\[file:([a-zA-Z0-9-]+)\\]")
        val fileIds = fileRegex.findAll(content).map { it.groupValues[1] }.toList()
        val attachments = fileIds.mapNotNull { fileRepository.getById(it) }.takeIf { it.isNotEmpty() }

        val message = Message(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = MessageRole.USER,
            content = content,
            timestamp = System.currentTimeMillis(),
            attachments = attachments
        )
        messageRepository.create(message)
        return message
    }

    suspend fun generateAssistantReply(
        sessionId: String,
        prompt: String,
        onStatus: suspend (String) -> Unit,
        onToken: suspend (String) -> Unit,
    ): Message {
        val session = db.openClawDbQueries.getSessionById(sessionId).executeAsOneOrNull()
            ?: error("Session not found: $sessionId")
        val modelId = session.modelId
        val providerId = normalizeProviderId(stateStore.loadSessionProvider(sessionId))
        val sessionSettings = stateStore.loadSessionOpenClawSettings(sessionId)
        val provider = chatProviders[providerId] ?: chatProviders.getValue(PROVIDER_OLLAMA)

        // Fetch history window (e.g., last 10 messages)
        val history = messageRepository.getBySessionId(sessionId).takeLast(10)

        onStatus("Thinking (${provider.id})...")
        val fullResponse = provider.generate(sessionId, modelId, history, sessionSettings, onToken)

        val assistantMessage = Message(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = fullResponse,
            timestamp = System.currentTimeMillis(),
        )
        messageRepository.create(assistantMessage)
        onStatus("Ready")
        return assistantMessage
    }
}
