package dev.spola.app.backend

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import dev.spola.app.models.TranscriptionResponse
import kotlinx.serialization.json.*
import java.util.Base64

fun interface SpeechToText {
    suspend fun transcribe(audioBytes: ByteArray): TranscriptionResponse
}

interface TextToSpeech {
    suspend fun synthesize(text: String, voice: String? = null): ByteArray
}

class OllamaWhisperSTT(
    private val client: HttpClient,
    private val baseUrl: String = "http://localhost:11434"
) : SpeechToText {
    override suspend fun transcribe(audioBytes: ByteArray): TranscriptionResponse {
        val base64Audio = Base64.getEncoder().encodeToString(audioBytes)
        return try {
            val response = client.post("$baseUrl/api/generate") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("model", "whisper")
                    put("prompt", "") // Whisper model uses prompt for task info usually
                    put("stream", false)
                    put("images", buildJsonArray { add(JsonPrimitive(base64Audio)) }) // Some Whisper-Ollama hacks use images field
                })
            }
            // Note: Different Ollama-Whisper builds use different API shapes.
            // This is a common one. If yours differs, we'll adjust.
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val text = json["response"]?.jsonPrimitive?.content ?: ""
            TranscriptionResponse(text)
        } catch (e: Exception) {
            TranscriptionResponse("Transcription failed: ${e.message}")
        }
    }
}

class SystemTTS : TextToSpeech {
    override suspend fun synthesize(text: String, voice: String?): ByteArray {
        // Placeholder: Real local TTS usually requires platform libraries (like FreeTTS or MaryTTS on JVM)
        // or delegating back to the client's OS capabilities.
        // For now, we return empty to avoid crashes.
        return ByteArray(0)
    }
}

class SpeechService(
    val stt: SpeechToText,
    val tts: TextToSpeech
)
