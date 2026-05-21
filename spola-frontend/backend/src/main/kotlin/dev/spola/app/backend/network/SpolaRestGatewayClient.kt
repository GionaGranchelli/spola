package dev.spola.app.backend.network

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

@Serializable
data class OpenAiChatMessage(val role: String, val content: String)

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiChatMessage>,
    val stream: Boolean = false,
    val user: String? = null,
    val attachments: List<String>? = null
)

@Serializable
data class OpenAiModel(val id: String, @SerialName("owned_by") val ownedBy: String)

@Serializable
data class OpenAiModelsResponse(val data: List<OpenAiModel>)

open class SpolaRestGatewayClient(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    open suspend fun getModels(): List<OpenAiModel> {
        val response = client.get("$baseUrl/v1/models") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        if (response.status != HttpStatusCode.OK) return emptyList()
        val body = response.bodyAsText()
        return runCatching { json.decodeFromString<OpenAiModelsResponse>(body).data }.getOrElse { emptyList() }
    }

    fun chatCompletionsStream(
        model: String,
        messages: List<OpenAiChatMessage>,
        sessionKey: String? = null,
        agentId: String? = null,
        overrides: Map<String, String> = emptyMap(),
        attachments: List<String>? = null
    ): Flow<String> = flow {
        client.preparePost("$baseUrl/v1/chat/completions") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            sessionKey?.let { header("x-spola-session-key", it) }
            agentId?.let { header("x-spola-agent-id", it) }
            
            overrides.forEach { (k, v) ->
                header(k, v)
            }

            setBody(OpenAiChatRequest(
                model = model,
                messages = messages,
                stream = true,
                user = sessionKey,
                attachments = attachments
            ))
        }.execute { response ->
            if (response.status != HttpStatusCode.OK) {
                val error = response.bodyAsText()
                throw RuntimeException("Spola Client API Error (${response.status}): $error")
            }

            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    
                    runCatching {
                        val element = json.parseToJsonElement(data)
                        val choices = element.jsonObject["choices"]?.jsonArray
                        val delta = choices?.get(0)?.jsonObject?.get("delta")?.jsonObject
                        val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
                        if (content != null) {
                            emit(content)
                        }
                    }
                }
            }
        }
    }
}
