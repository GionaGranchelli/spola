package dev.spola.config

data class SecurityConfig(
    val apiKey: String? = null,
    val insecure: Boolean = false,
    val allowedDirs: List<String> = emptyList(),
    val unsafe: Boolean = false,
    val sessionTimeoutMinutes: Int = 60,
)
