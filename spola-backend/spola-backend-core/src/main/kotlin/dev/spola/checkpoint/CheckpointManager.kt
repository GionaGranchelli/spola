package dev.spola.checkpoint

import dev.spola.ChatMessage
import dev.spola.SpolaConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Serialized representation of agent conversation state for checkpoint storage.
 */
@Serializable
data class CheckpointState(
    val sessionId: String,
    val turnNumber: Int,
    val conversationJson: String,
    val createdAt: String,
)

/**
 * Service class that manages checkpoint save/load/resume operations.
 * Handles serialization of conversation state to/from SQLite via [CheckpointStore].
 * Optionally captures git diff at each checkpoint for diff viewing.
 */
class CheckpointManager(
    @PublishedApi internal val store: CheckpointStore,
    /** Working directory for git diff computation. Defaults to current directory. */
    private val workingDirectory: String = ".",
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Generate a unique session id for checkpoint tracking.
     */
    fun generateSessionId(): String {
        return java.util.UUID.randomUUID().toString().take(16)
    }

    /**
     * Save a checkpoint for the given session.
     * Serializes the conversation list to JSON and stores it.
     * Also computes and stores a git diff (since HEAD) in the working directory.
     *
     * @return the checkpoint id
     */
    fun save(sessionId: String, turn: Int, conversation: List<ChatMessage>): Long {
        val conversationJson = serializeConversation(conversation)
        val diff = computeGitDiff()
        return store.save(sessionId, turn, conversationJson, diff)
    }

    /**
     * Load the most recent checkpoint for a session.
     * Returns null if no checkpoint exists.
     */
    fun load(sessionId: String): CheckpointState? {
        val cp = store.load(sessionId) ?: return null
        return CheckpointState(
            sessionId = cp.sessionId,
            turnNumber = cp.turnNumber,
            conversationJson = cp.conversationJson,
            createdAt = cp.createdAt,
        )
    }

    /**
     * Load and deserialize the conversation from the most recent checkpoint.
     */
    fun loadConversation(sessionId: String): List<ChatMessage>? {
        val cp = store.load(sessionId) ?: return null
        return deserializeConversation(cp.conversationJson)
    }

    /**
     * List all available checkpoints.
     */
    fun list(): List<CheckpointData> {
        return store.list().map { cp ->
            CheckpointData(
                id = cp.id,
                sessionId = cp.sessionId,
                turnNumber = cp.turnNumber,
                createdAt = cp.createdAt,
            )
        }
    }

    /**
     * Delete checkpoints older than the given ISO timestamp.
     */
    fun deleteOlderThan(olderThan: String): Int {
        return store.deleteOlderThan(olderThan)
    }

    /**
     * Delete all checkpoints for a session.
     */
    fun deleteForSession(sessionId: String): Int {
        return store.deleteForSession(sessionId)
    }

    /**
     * Save a raw JSON string as a checkpoint (used by the checkpoint_save tool).
     * Validates that the JSON is parseable before saving.
     */
    fun saveRaw(sessionId: String, turn: Int, conversationJson: String): Long {
        // Validate JSON before saving
        try {
            json.decodeFromString<List<SerializableMessage>>(conversationJson)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid conversation JSON: ${e.message}")
        }
        val diff = computeGitDiff()
        return store.save(sessionId, turn, conversationJson, diff)
    }

    /**
     * Get total checkpoint count.
     */
    fun count(): Long = store.count()

    /**
     * Load the diff for a specific checkpoint by its id.
     * Returns null if the checkpoint doesn't exist or has no diff.
     */
    fun loadDiff(id: Long): String? {
        return store.loadById(id)?.diff
    }

    /**
     * Load a full checkpoint by its id.
     * Returns null if no checkpoint with that id exists.
     */
    fun loadCheckpoint(id: Long): Checkpoint? {
        return store.loadById(id)
    }

    /**
     * List checkpoints for a specific session, ordered by turn descending.
     * Includes the diff field for each checkpoint.
     */
    fun listForSession(sessionId: String): List<Checkpoint> {
        return store.listForSession(sessionId)
    }

    /**
     * Compute git diff against HEAD in the working directory.
     * Returns null if git is not available, the directory is not a git repo,
     * or any other error occurs (non-blocking).
     * Truncated to 50KB max to avoid storing huge diffs.
     */
    internal fun computeGitDiff(): String? {
        return try {
            val process = ProcessBuilder("git", "diff", "HEAD")
                .directory(java.io.File(workingDirectory))
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                // git failed (e.g., not a git repo, no commits yet)
                null
            } else if (stdout.isBlank()) {
                // No changes — store empty string or null? Store as empty.
                ""
            } else {
                stdout.take(MAX_DIFF_LENGTH)
            }
        } catch (e: Exception) {
            // Non-blocking: any failure just means no diff
            null
        }
    }

    internal fun serializeConversation(conversation: List<ChatMessage>): String {
        val serializable = conversation.map { msg -> msg.toSerializable() }
        return json.encodeToString(serializable)
    }

    internal fun deserializeConversation(jsonStr: String): List<ChatMessage> {
        val list = json.decodeFromString<List<SerializableMessage>>(jsonStr)
        return list.map { it.toChatMessage() }
    }

    internal companion object {
        /** Maximum diff size to store: 50 KB */
        private const val MAX_DIFF_LENGTH = 50 * 1024

        fun fromConfig(config: SpolaConfig): CheckpointManager {
            val store = CheckpointStore(config.checkpointDbPath)
            return CheckpointManager(store, workingDirectory = config.workingDirectory)
        }
    }
}

/**
 * Public-facing checkpoint info returned by list operations.
 */
data class CheckpointData(
    val id: Long,
    val sessionId: String,
    val turnNumber: Int,
    val createdAt: String,
    val diff: String? = null,
)

/**
 * Serializable message used for JSON serialization of conversation state.
 */
@Serializable
internal data class SerializableMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<SerializableToolCall>? = null,
)

@Serializable
internal data class SerializableToolCall(
    val id: String,
    val name: String,
    /** JSON-encoded arguments map, preserves value types (not toString-lossy). */
    val argumentsJson: String = "{}",
)

internal fun ChatMessage.toSerializable(): SerializableMessage = when (this) {
    is dev.spola.SystemMessage -> SerializableMessage(
        role = "system",
        content = content,
    )
    is dev.spola.UserMessage -> SerializableMessage(
        role = "user",
        content = content,
    )
    is dev.spola.AssistantMessage -> SerializableMessage(
        role = "assistant",
        content = content,
        toolCalls = toolCalls.map { tc ->
            val argsJson = try {
                com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(tc.arguments)
            } catch (_: Exception) { "{}" }
            SerializableToolCall(
                id = tc.id,
                name = tc.name,
                argumentsJson = argsJson,
            )
        }.ifEmpty { null },
    )
    is dev.spola.ToolResultMessage -> SerializableMessage(
        role = "tool",
        content = content,
        toolCallId = toolCallId,
        toolName = toolName,
    )
}

internal fun SerializableMessage.toChatMessage(): ChatMessage = when (role) {
    "system" -> dev.spola.SystemMessage(content)
    "user" -> dev.spola.UserMessage(content)
    "assistant" -> dev.spola.AssistantMessage(
        content = content,
        toolCalls = toolCalls?.map { tc ->
            val args = try {
                val tree = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(tc.argumentsJson)
                tree.fields().asSequence().associate { (key, value) ->
                    key to when {
                        value.isTextual -> value.textValue()
                        value.isInt -> value.intValue()
                        value.isLong -> value.longValue()
                        value.isDouble -> value.doubleValue()
                        value.isBoolean -> value.booleanValue()
                        value.isNull -> "null"
                        else -> value.toString()
                    }
                }
            } catch (_: Exception) { emptyMap() }
            dev.spola.ToolCall(
                id = tc.id,
                name = tc.name,
                arguments = args,
            )
        } ?: emptyList(),
    )
    "tool" -> dev.spola.ToolResultMessage(
        toolCallId = toolCallId ?: "",
        toolName = toolName ?: "",
        content = content,
    )
    else -> dev.spola.UserMessage(content)
}
