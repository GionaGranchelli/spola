package dev.spola.tts

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * ElevenLabs Text-to-Speech provider.
 * Uses the ElevenLabs API to synthesize speech from text.
 *
 * @param apiKey ElevenLabs API key.
 * @param voiceId The voice ID to use (default: "21m00Tcm4TlvDq8ikWAM" — Rachel).
 * @param baseUrl Base URL for the API (can be overridden for testing).
 * @param httpClient Custom HTTP client (can be injected for testing).
 */
class ElevenLabsTtsProvider(
    private val apiKey: String,
    private val voiceId: String = "21m00Tcm4TlvDq8ikWAM",
    private val baseUrl: String = "https://api.elevenlabs.io",
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : TtsProvider {

    override val name: String = "elevenlabs"

    override suspend fun synthesize(text: String, voice: String?): ByteArray {
        val effectiveVoice = voice ?: voiceId
        val url = "$baseUrl/v1/text-to-speech/$effectiveVoice"

        val jsonBody = buildJsonBody(text)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("xi-api-key", apiKey)
            .header("Accept", "audio/mpeg")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())

        return when {
            response.statusCode() in 200..299 -> response.body()
            response.statusCode() == 401 -> {
                throw TtsException("ElevenLabs API returned 401: Unauthorized — check API key")
            }
            response.statusCode() == 422 -> {
                val body = String(response.body(), Charsets.UTF_8).take(500)
                throw TtsException("ElevenLabs API returned 422: Unprocessable entity — $body")
            }
            else -> {
                val body = String(response.body(), Charsets.UTF_8).take(500)
                throw TtsException("ElevenLabs API returned HTTP ${response.statusCode()}: $body")
            }
        }
    }

    private fun buildJsonBody(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """{"text":"$escaped","model_id":"eleven_monolingual_v1"}"""
    }
}

/**
 * Exception thrown by TTS providers.
 */
class TtsException(message: String, cause: Throwable? = null) : Exception(message, cause)
