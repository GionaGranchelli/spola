package dev.spola.tts

/**
 * Text-to-Speech provider interface.
 * Implementations synthesize text into audio bytes (WAV or MP3).
 */
interface TtsProvider {
    /**
     * Synthesize [text] into audio bytes.
     * @param text The text to speak.
     * @param voice Optional voice identifier (provider-specific).
     * @return ByteArray containing audio data (WAV or MP3 format).
     */
    suspend fun synthesize(text: String, voice: String? = null): ByteArray

    /**
     * Human-readable name for this provider.
     */
    val name: String
}
