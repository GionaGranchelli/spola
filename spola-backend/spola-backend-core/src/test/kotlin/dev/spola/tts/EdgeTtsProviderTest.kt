package dev.spola.tts

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EdgeTtsProviderTest {

    @Test
    fun `provider has correct name`() {
        val provider = EdgeTtsProvider()
        assertEquals("edge", provider.name)
    }

    @Test
    fun `synthesize returns non-empty audio data`() = runTest {
        val provider = EdgeTtsProvider()
        val result = provider.synthesize("Hello world")

        assertNotNull(result)
        assertTrue(result.isNotEmpty(), "Audio bytes should not be empty")
    }

    @Test
    fun `synthesize with voice parameter still works`() = runTest {
        val provider = EdgeTtsProvider()
        val result = provider.synthesize("Test text", voice = "en-US-GuyNeural")

        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `synthesize handles empty text gracefully`() = runTest {
        val provider = EdgeTtsProvider()
        val result = provider.synthesize("")

        assertNotNull(result)
    }

    @Test
    fun `beep wav has correct PCM format when edge-tts is unavailable`() = runTest {
        val provider = EdgeTtsProvider()
        val result = provider.synthesize("test")

        // Detect format: RIFF = WAV (beep fallback), ID3/0xFF = MP3 (edge-tts)
        val firstFour = String(result.copyOfRange(0, 4), Charsets.US_ASCII)

        if (firstFour == "RIFF") {
            // WAV fallback path
            assertEquals("WAVE", String(result.copyOfRange(8, 12), Charsets.US_ASCII))

            val format = ((result[21].toInt() and 0xFF) shl 8) or (result[20].toInt() and 0xFF)
            assertEquals(1, format, "Audio format should be PCM (1)")

            val channels = ((result[23].toInt() and 0xFF) shl 8) or (result[22].toInt() and 0xFF)
            assertEquals(1, channels, "Should be mono")

            val sampleRate = ((result[27].toInt() and 0xFF) shl 24) or
                ((result[26].toInt() and 0xFF) shl 16) or
                ((result[25].toInt() and 0xFF) shl 8) or
                (result[24].toInt() and 0xFF)
            assertEquals(44100, sampleRate, "Sample rate should be 44100")
        } else {
            // edge-tts is installed — produces MP3, just verify it's audio
            assertTrue(result.size > 100, "MP3 audio should be substantial")
        }
    }
}
