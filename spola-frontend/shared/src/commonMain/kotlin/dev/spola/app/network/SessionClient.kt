package dev.spola.app.network

import dev.spola.app.models.ChatSession
import dev.spola.app.models.FileMetadata
import dev.spola.app.models.Message
import dev.spola.app.models.ModelInfo
import dev.spola.app.models.SessionModelUpdateRequest
import dev.spola.app.models.StreamEvent
import dev.spola.app.models.BackendMessage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class SessionClient(
    private val client: HttpClient,
    private val json: Json,
) {
    fun streamEvents(sessionId: String): Flow<StreamEvent> =
        streamWithRetry("api/session/$sessionId/stream") { _, data -> json.decodeFromString<StreamEvent>(data) }

    suspend fun getSessions(): List<ChatSession> = client.get("api/sessions").body()

    suspend fun getSession(sessionId: String): ChatSession = client.get("api/session/$sessionId").body()

    suspend fun getMessages(sessionId: String): List<Message> = client.get("api/session/$sessionId/messages").body()

    /**
     * Fetch messages in the raw backend format (role + content only).
     * The backend returns [BackendMessage] without frontend fields like id/sessionId/timestamp.
     */
    suspend fun getBackendMessages(sessionId: String): List<BackendMessage> =
        client.get("api/session/$sessionId/messages").body()

    suspend fun createSession(session: ChatSession): ChatSession =
        client.post("api/session") {
            contentType(ContentType.Application.Json)
            setBody(session)
        }.body()

    suspend fun deleteSession(id: String) {
        client.delete("api/session/$id")
    }

    suspend fun sendMessage(sessionId: String, content: String): Message =
        client.post("api/session/$sessionId/message") {
            contentType(ContentType.Text.Plain)
            setBody(content)
        }.body()

    suspend fun uploadSessionFile(sessionId: String, fileName: String, fileBytes: ByteArray): String =
        client.submitFormWithBinaryData(
            url = "api/session/$sessionId/upload",
            formData = formData {
                append(
                    key = "file",
                    value = fileBytes,
                    headers = Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                    },
                )
            },
        ).body<FileMetadata>().id

    suspend fun sendSessionMessage(sessionId: String, text: String, fileRef: String? = null) {
        val payload = if (fileRef.isNullOrBlank()) {
            text
        } else {
            buildString {
                append(text)
                if (text.isNotBlank()) {
                    append('\n')
                }
                append("[file:")
                append(fileRef)
                append(']')
            }
        }
        client.post("api/session/$sessionId/message") {
            contentType(ContentType.Text.Plain)
            setBody(payload)
        }
    }

    suspend fun getModels(): List<ModelInfo> = client.get("api/models").body()

    suspend fun updateSessionModel(sessionId: String, modelId: String): ChatSession =
        client.post("api/session/$sessionId/model") {
            contentType(ContentType.Application.Json)
            setBody(SessionModelUpdateRequest(modelId))
        }.body()

    internal fun <T> streamWithRetry(
        urlString: String,
        mapper: (String?, String) -> T,
    ): Flow<T> = flow {
        var shouldRetry = true
        while (shouldRetry) {
            try {
                client.sse(
                    urlString = urlString,
                    request = {
                        timeout {
                            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        }
                    },
                ) {
                    incoming.collect { event ->
                        val data = event.data
                        if (data != null) {
                            emit(mapper(event.event, data))
                        }
                    }
                }
                shouldRetry = false
            } catch (error: Throwable) {
                if (isRecoverableSseDisconnect(error)) {
                    delay(1000)
                } else {
                    throw error
                }
            }
        }
    }
}
