package dev.spola.tts

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Edge TTS provider that tries to use the `edge-tts` CLI tool via ProcessBuilder.
 * Falls back to generating a simple beep WAV if `edge-tts` is not available.
 */
class EdgeTtsProvider : TtsProvider {

    override val name: String = "edge"

    override suspend fun synthesize(text: String, voice: String?): ByteArray {
        // Try edge-tts CLI first
        try {
            return synthesizeWithEdgeTts(text, voice)
        } catch (e: Exception) {
            // Fall back to beep
            return generateBeepWav()
        }
    }

    /**
     * Try to synthesize using the edge-tts Python CLI tool.
     */
    private fun synthesizeWithEdgeTts(text: String, voice: String?): ByteArray {
        // Check if edge-tts is available
        val checkProcess = ProcessBuilder("which", "edge-tts")
            .redirectErrorStream(true)
            .start()
        val checkExit = checkProcess.waitFor()
        if (checkExit != 0) {
            throw TtsException("edge-tts CLI not found on PATH")
        }

        // Build the command
        val cmd = mutableListOf("edge-tts")
        cmd.add("--text")
        cmd.add(text)
        if (voice != null) {
            cmd.add("--voice")
            cmd.add(voice)
        }
        cmd.add("--write-media")
        cmd.add("-") // Write to stdout

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .start()

        // Read stdout (audio data)
        val audioBytes = process.inputStream.readAllBytes()

        // Read stderr (logs)
        val errorBytes = process.errorStream.readAllBytes()

        val exitCode = process.waitFor()

        if (exitCode != 0) {
            val errorMsg = if (errorBytes.isNotEmpty()) {
                String(errorBytes, Charsets.UTF_8).take(500)
            } else {
                "Exit code: $exitCode"
            }
            throw TtsException("edge-tts failed: $errorMsg")
        }

        if (audioBytes.isEmpty()) {
            throw TtsException("edge-tts produced no audio output")
        }

        return audioBytes
    }

    /**
     * Generate a simple beep WAV file as a last-resort fallback.
     * A short 440Hz sine wave tone.
     */
    private fun generateBeepWav(): ByteArray {
        val sampleRate = 44100
        val durationMs = 200
        val frequency = 440.0
        val numSamples = sampleRate * durationMs / 1000

        // Generate sine wave samples (16-bit PCM)
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * i / (sampleRate / frequency)
            val rawValue = (Math.sin(angle) * Short.MAX_VALUE * 0.5).toInt()
            samples[i] = rawValue.toShort()
        }

        // Write WAV file
        val baos = ByteArrayOutputStream()

        // WAV header
        val dataSize = numSamples * 2 // 16-bit = 2 bytes per sample
        val fileSize = 36 + dataSize

        writeString(baos, "RIFF")
        writeInt(baos, fileSize)
        writeString(baos, "WAVE")

        // Format chunk
        writeString(baos, "fmt ")
        writeInt(baos, 16) // Chunk size
        writeShort(baos, 1) // Audio format: PCM
        writeShort(baos, 1) // Number of channels: mono
        writeInt(baos, sampleRate) // Sample rate
        writeInt(baos, sampleRate * 2) // Byte rate
        writeShort(baos, 2) // Block align
        writeShort(baos, 16) // Bits per sample

        // Data chunk
        writeString(baos, "data")
        writeInt(baos, dataSize)
        for (sample in samples) {
            writeShort(baos, sample.toInt())
        }

        return baos.toByteArray()
    }

    private fun writeString(stream: ByteArrayOutputStream, s: String) {
        stream.write(s.toByteArray(Charsets.US_ASCII))
    }

    private fun writeInt(stream: ByteArrayOutputStream, value: Int) {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        stream.write(buffer.array())
    }

    private fun writeShort(stream: ByteArrayOutputStream, value: Int) {
        val buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
        stream.write(buffer.array())
    }
}
