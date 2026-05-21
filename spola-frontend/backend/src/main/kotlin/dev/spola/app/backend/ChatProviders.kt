package dev.spola.app.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import dev.spola.app.models.SpolaSessionSettings
import dev.spola.app.models.ProviderInfo
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable
data class OllamaGenerateRequest(val model: String, val prompt: String, val stream: Boolean = true)

const val PROVIDER_OLLAMA = "ollama"

val SUPPORTED_PROVIDERS: List<ProviderInfo> = listOf(
    ProviderInfo(
        id = PROVIDER_OLLAMA,
        name = "Ollama",
        description = "Local Ollama models",
    ),
    ProviderInfo(
        id = "spola-gateway",
        name = "Spola Client Gateway",
        description = "Spola Client local gateway via REST",
    ),
)

fun isSupportedProvider(providerId: String): Boolean = SUPPORTED_PROVIDERS.any { it.id == providerId }

fun normalizeProviderId(providerId: String?): String {
    return providerId?.takeIf(::isSupportedProvider) ?: PROVIDER_OLLAMA
}

interface ChatProvider {
    val id: String
    suspend fun generate(
        sessionId: String,
        modelId: String,
        messages: List<dev.spola.app.models.Message>,
        sessionSettings: SpolaSessionSettings? = null,
        onToken: suspend (String) -> Unit
    ): String
}

class OllamaChatProvider(
    private val client: HttpClient,
) : ChatProvider {
    override val id: String = PROVIDER_OLLAMA

    override suspend fun generate(
        sessionId: String,
        modelId: String,
        messages: List<dev.spola.app.models.Message>,
        sessionSettings: SpolaSessionSettings?,
        onToken: suspend (String) -> Unit
    ): String {
        // Simple prompt concatenation for legacy Ollama /api/generate
        // Ideally we should switch to /api/chat, but for now we format the history
        val fullPrompt = messages.joinToString("\n") { 
            "${it.role.name}: ${it.content}"
        } + "\nASSISTANT:"

        val response = client.post("http://localhost:11434/api/generate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(OllamaGenerateRequest(model = modelId, prompt = fullPrompt))
        }
        val channel = response.bodyAsChannel()
        val fullResponse = StringBuilder()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            val token = runCatching {
                Json.parseToJsonElement(line).jsonObject["response"]?.jsonPrimitive?.content.orEmpty()
            }.getOrDefault("")
            if (token.isNotEmpty()) {
                fullResponse.append(token)
                onToken(token)
            }
        }
        return fullResponse.toString()
    }
}
