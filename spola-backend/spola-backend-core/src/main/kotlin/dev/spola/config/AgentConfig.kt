package dev.spola.config

data class AgentConfig(
    val sessionId: String? = null,
    val personaPath: String? = null,
    val memoryEnabled: Boolean = true,
    val maxTurns: Int = 25,
)
