package dev.spola.config

data class ServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8082,
    val tlsCertPath: String = "",
    val tlsKeyPath: String = "",
    val mcpPort: Int = 8091,
    val apiHost: String = "127.0.0.1",
)
