package dev.spola.agent

/**
 * Explicit provider configuration — no env var dependency.
 * Resolved once at agent creation time, safe for concurrent use.
 */
data class ProviderConfig(
    val provider: String,
    val model: String,
    val apiKey: String,
    val baseUrl: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

