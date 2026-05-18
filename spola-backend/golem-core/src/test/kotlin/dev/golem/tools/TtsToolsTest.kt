package dev.spola.tools

import dev.spola.GolemConfig
import dev.spola.ToolRegistry
import dev.spola.tts.EdgeTtsProvider
import dev.spola.tts.ElevenLabsTtsProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TtsToolsTest {

    @Test
    fun `tts_say tool is registered with correct structure`() {
        val registry = ToolRegistry()
        registerTtsTool(registry, GolemConfig())

        val tool = registry.get("tts_say")
        assertNotNull(tool, "tts_say tool should be registered")
        assertEquals("tts_say", tool.name)
        assertTrue(tool.description.contains("speech", ignoreCase = true))

        val paramNames = tool.parameters.map { it.name }
        assertTrue("text" in paramNames, "Should have text parameter")
        assertTrue("voice" in paramNames, "Should have voice parameter")
        assertTrue("provider" in paramNames, "Should have provider parameter")
        assertTrue("output_path" in paramNames, "Should have output_path parameter")

        val textParam = tool.parameters.find { it.name == "text" }
        assertNotNull(textParam)
        assertTrue(textParam.required, "text should be required")

        val voiceParam = tool.parameters.find { it.name == "voice" }
        assertNotNull(voiceParam)
        assertFalse(voiceParam.required, "voice should not be required")

        val providerParam = tool.parameters.find { it.name == "provider" }
        assertNotNull(providerParam)
        assertFalse(providerParam.required, "provider should not be required")

        val outputPathParam = tool.parameters.find { it.name == "output_path" }
        assertNotNull(outputPathParam)
        assertFalse(outputPathParam.required, "output_path should not be required")
    }

    @Test
    fun `tts_say returns error when text is missing`() = runTest {
        val registry = ToolRegistry()
        registerTtsTool(registry, GolemConfig())

        val tool = registry.get("tts_say")!!
        val result = tool.execute(mapOf())

        assertFalse(result.success)
        assertTrue(result.output.contains("Missing required", ignoreCase = true))
    }

    @Test
    fun `tts_say returns error when text is empty`() = runTest {
        val registry = ToolRegistry()
        registerTtsTool(registry, GolemConfig())

        val tool = registry.get("tts_say")!!
        val result = tool.execute(mapOf("text" to ""))

        assertFalse(result.success)
        assertTrue(result.output.contains("empty", ignoreCase = true))
    }

    @Test
    fun `tts_say with edge provider creates audio file`(@TempDir tempDir: Path) = runTest {
        val registry = ToolRegistry()
        registerTtsTool(registry, GolemConfig())

        val tool = registry.get("tts_say")!!

        val outputPath = tempDir.resolve("test_output.wav")
        val result = tool.execute(
            mapOf(
                "text" to "Hello world",
                "provider" to "edge",
                "output_path" to outputPath.toString(),
            ),
        )

        assertTrue(result.success, "Expected success, got: ${result.error}")
        assertTrue(result.output.contains(outputPath.toString()), "Output should contain file path")
        assertTrue(result.output.contains("bytes", ignoreCase = true), "Output should mention file size")

        // Verify file was created
        assertTrue(outputPath.toFile().exists(), "Audio file should exist")
        assertTrue(outputPath.toFile().length() > 0, "Audio file should not be empty")
    }

    @Test
    fun `tts_say with elevenlabs provider returns error when no api key`() = runTest {
        val registry = ToolRegistry()
        registerTtsTool(registry, GolemConfig()) // No elevenlabs API key

        val tool = registry.get("tts_say")!!
        val result = tool.execute(
            mapOf(
                "text" to "Hello",
                "provider" to "elevenlabs",
            ),
        )

        assertFalse(result.success)
        assertTrue(
            result.output.contains("api key", ignoreCase = true) ||
                result.output.contains("not configured", ignoreCase = true),
        )
    }

    @Test
    fun `tts_say with invalid provider returns error`() = runTest {
        val registry = ToolRegistry()
        registerTtsTool(registry, GolemConfig())

        val tool = registry.get("tts_say")!!
        val result = tool.execute(
            mapOf(
                "text" to "Hello",
                "provider" to "invalid_provider",
            ),
        )

        assertFalse(result.success)
        assertTrue(result.output.contains("Unknown provider", ignoreCase = true))
    }

    @Test
    fun `tts_say is included via registerTools with config`() {
        val registry = ToolRegistry()
        registerTools(registry, GolemConfig())

        assertNotNull(registry.get("tts_say"), "tts_say should be registered via registerTools with config")
    }

    @Test
    fun `tts_say is NOT included via registerTools without config`() {
        val registry = ToolRegistry()
        registerTools(registry)

        assertEquals(null, registry.get("tts_say"), "tts_say should NOT be registered without config")
    }

    @Test
    fun `createTtsProvider returns EdgeTtsProvider when no api key`() {
        val config = GolemConfig(elevenlabsApiKey = null)
        val provider = createTtsProvider(config)

        assertTrue(provider is EdgeTtsProvider, "Should create EdgeTtsProvider when no ElevenLabs API key")
    }

    @Test
    fun `createTtsProvider returns ElevenLabsTtsProvider when api key is set`() {
        val config = GolemConfig(
            elevenlabsApiKey = "test-key",
            elevenlabsVoiceId = "custom-voice",
        )
        val provider = createTtsProvider(config)

        assertTrue(provider is ElevenLabsTtsProvider, "Should create ElevenLabsTtsProvider when API key is set")
        assertEquals("elevenlabs", provider.name)
    }
}
