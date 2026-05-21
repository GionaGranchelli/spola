package dev.spola.tools

import dev.spola.SpolaConfig
import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import dev.spola.tts.EdgeTtsProvider
import dev.spola.tts.ElevenLabsTtsProvider
import dev.spola.tts.TtsProvider
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Register the tts_say tool.
 * Uses the configured TTS provider (ElevenLabs if apiKey is set, otherwise Edge TTS).
 */
fun registerTtsTool(
    registry: ToolRegistry,
    config: SpolaConfig = SpolaConfig(),
) {
    val provider = createTtsProvider(config)

    registry.register(
        Tool(
            name = "tts_say",
            description = "Convert text to speech and save as an audio file (MP3 or WAV). " +
                "Uses ElevenLabs (if configured) or Edge TTS (CLI fallback to beep).",
            parameters = listOf(
                ToolParameter(
                    name = "text",
                    description = "Text to synthesize into speech",
                    type = ToolParameterType.STRING,
                    required = true,
                ),
                ToolParameter(
                    name = "voice",
                    description = "Voice identifier (provider-specific; e.g., ElevenLabs voice ID)",
                    type = ToolParameterType.STRING,
                    required = false,
                ),
                ToolParameter(
                    name = "provider",
                    description = "TTS provider override: 'elevenlabs' or 'edge'",
                    type = ToolParameterType.STRING,
                    required = false,
                ),
                ToolParameter(
                    name = "output_path",
                    description = "Custom output file path (default: auto-generated in ~/.spola/audio/)",
                    type = ToolParameterType.STRING,
                    required = false,
                ),
            ),
            execute = { args ->
                try {
                    val text = (args["text"] as? String)?.trim()
                        ?: return@Tool ToolResult.fail("Missing required argument: text")
                    if (text.isEmpty()) return@Tool ToolResult.fail("text must not be empty")

                    val voice = (args["voice"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    val providerOverride = (args["provider"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    val outputPathOverride = (args["output_path"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

                    // Resolve provider (allow override)
                    val effectiveProvider = when (providerOverride?.lowercase()) {
                        "elevenlabs" -> {
                            val key = config.elevenlabsApiKey
                            if (key.isNullOrBlank()) {
                                return@Tool ToolResult.fail(
                                    "ElevenLabs provider requested but elevenlabsApiKey is not configured"
                                )
                            }
                            ElevenLabsTtsProvider(key, config.elevenlabsVoiceId)
                        }
                        "edge" -> EdgeTtsProvider()
                        null -> provider
                        else -> return@Tool ToolResult.fail(
                            "Unknown provider: '$providerOverride'. Supported: 'elevenlabs', 'edge'"
                        )
                    }

                    // Determine output path
                    val audioDir = Path.of(
                        System.getProperty("user.home"),
                        ".spola",
                        "audio"
                    )
                    Files.createDirectories(audioDir)

                    val outputPath = outputPathOverride?.let { Path.of(it) }
                        ?: generateAudioPath(audioDir, effectiveProvider.name)

                    // Synthesize
                    val audioBytes = effectiveProvider.synthesize(text, voice)

                    // Write to file
                    Files.write(outputPath, audioBytes)

                    ToolResult.ok(
                        "Audio saved to $outputPath (${audioBytes.size} bytes, provider: ${effectiveProvider.name})"
                    )
                } catch (e: dev.spola.tts.TtsException) {
                    ToolResult.fail("TTS error: ${e.message}")
                } catch (e: Exception) {
                    ToolResult.fail("Failed to synthesize speech: ${e.message}")
                }
            },
        ),
    )
}

/**
 * Create a TTS provider based on configuration.
 * Uses ElevenLabs if API key is set, otherwise Edge TTS.
 */
internal fun createTtsProvider(config: SpolaConfig): TtsProvider {
    val key = config.elevenlabsApiKey
    if (!key.isNullOrBlank()) {
        return ElevenLabsTtsProvider(key, config.elevenlabsVoiceId)
    }
    return EdgeTtsProvider()
}

/**
 * Generate an audio file path with timestamp.
 */
private fun generateAudioPath(audioDir: Path, providerName: String): Path {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    val ext = if (providerName == "elevenlabs") ".mp3" else ".wav"
    return audioDir.resolve("tts_${timestamp}_${providerName}$ext")
}
