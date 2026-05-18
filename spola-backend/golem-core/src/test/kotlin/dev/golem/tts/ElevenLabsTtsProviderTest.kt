package dev.spola.tts

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ElevenLabsTtsProviderTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private var capturedApiKey: String? = null
    private var capturedRequestBody: String? = null

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    @Test
    fun `synthesize sends correct request and returns audio bytes`() = runTest {
        val expectedAudio = byteArrayOf(0x00, 0x01, 0x02, 0x03)

        server.createContext("/") { exchange ->
            capturedApiKey = exchange.requestHeaders.getFirst("xi-api-key")
            capturedRequestBody = String(exchange.requestBody.readAllBytes(), Charsets.UTF_8)

            exchange.sendResponseHeaders(200, expectedAudio.size.toLong())
            exchange.responseBody.write(expectedAudio)
            exchange.responseBody.close()
        }

        val provider = ElevenLabsTtsProvider(
            apiKey = "test-key-123",
            voiceId = "test-voice-id",
            baseUrl = baseUrl,
        )

        val result = provider.synthesize("Hello world")
        assertContentEquals(expectedAudio, result, "Should return the audio bytes from the API")
        assertEquals("test-key-123", capturedApiKey)
        assertNotNull(capturedRequestBody)
        assertTrue(capturedRequestBody!!.contains("Hello world"))
    }

    @Test
    fun `synthesize sends correct API key header`() = runTest {
        val expectedAudio = byteArrayOf(0x01, 0x02, 0x03)

        server.createContext("/") { exchange ->
            capturedApiKey = exchange.requestHeaders.getFirst("xi-api-key")

            exchange.sendResponseHeaders(200, expectedAudio.size.toLong())
            exchange.responseBody.write(expectedAudio)
            exchange.responseBody.close()
        }

        val provider = ElevenLabsTtsProvider(
            apiKey = "my-secret-key",
            voiceId = "test-voice",
            baseUrl = baseUrl,
        )

        provider.synthesize("Hello world")
        assertEquals("my-secret-key", capturedApiKey)
    }

    @Test
    fun `synthesize uses correct voice in URL`() = runTest {
        var capturedPath = ""

        server.createContext("/") { exchange ->
            capturedPath = exchange.requestURI.path
            val audio = byteArrayOf(0x01)
            exchange.sendResponseHeaders(200, audio.size.toLong())
            exchange.responseBody.write(audio)
            exchange.responseBody.close()
        }

        val provider = ElevenLabsTtsProvider(
            apiKey = "key",
            voiceId = "default-voice",
            baseUrl = baseUrl,
        )

        provider.synthesize("test")
        assertTrue(capturedPath.contains("default-voice"), "URL should contain default voice ID")

        // Test voice override
        provider.synthesize("test", voice = "custom-voice")
        assertTrue(capturedPath.contains("custom-voice"), "URL should contain overridden voice ID")
    }

    @Test
    fun `synthesize throws on 401 response`() = runTest {
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.responseBody.close()
        }

        val provider = ElevenLabsTtsProvider(
            apiKey = "bad-key",
            voiceId = "test-voice",
            baseUrl = baseUrl,
        )

        val exception = assertFailsWith<TtsException> {
            provider.synthesize("test")
        }
        assertTrue(exception.message!!.contains("401", ignoreCase = true))
    }

    @Test
    fun `synthesize throws on 422 response`() = runTest {
        server.createContext("/") { exchange ->
            val errBody = """{"detail":"text must not be empty"}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            val bytes = errBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(422, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }

        val provider = ElevenLabsTtsProvider(
            apiKey = "test-key",
            voiceId = "test-voice",
            baseUrl = baseUrl,
        )

        val exception = assertFailsWith<TtsException> {
            provider.synthesize("")
        }
        assertTrue(exception.message!!.contains("422", ignoreCase = true))
    }

    @Test
    fun `synthesize throws on 500 response`() = runTest {
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.responseBody.close()
        }

        val provider = ElevenLabsTtsProvider(
            apiKey = "test-key",
            voiceId = "test-voice",
            baseUrl = baseUrl,
        )

        val exception = assertFailsWith<TtsException> {
            provider.synthesize("test")
        }
        assertTrue(exception.message!!.contains("500", ignoreCase = true))
    }

    @Test
    fun `provider has correct name`() {
        val provider = ElevenLabsTtsProvider(apiKey = "key", voiceId = "voice")
        assertEquals("elevenlabs", provider.name)
    }

    @Test
    fun `synthesize sends JSON body with text and model_id`() = runTest {
        server.createContext("/") { exchange ->
            val body = String(exchange.requestBody.readAllBytes(), Charsets.UTF_8)
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.write(byteArrayOf(0x01))
            exchange.responseBody.close()

            assertTrue(body.contains("\"text\":\"Hello world\""), "Body should contain text")
            assertTrue(body.contains("\"model_id\":\"eleven_monolingual_v1\""), "Body should contain model_id")
        }

        val provider = ElevenLabsTtsProvider(
            apiKey = "key",
            voiceId = "voice",
            baseUrl = baseUrl,
        )

        provider.synthesize("Hello world")
    }
}
