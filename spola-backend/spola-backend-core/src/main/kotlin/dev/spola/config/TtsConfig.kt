package dev.spola.config

data class TtsConfig(
    val ttsEnabled: Boolean = false,
    val ttsProvider: String = "edge",
    val elevenlabsApiKey: String? = null,
    val elevenlabsVoiceId: String = "21m00Tcm4TlvDq8ikWAM",
)
