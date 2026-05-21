package dev.spola.config

data class MetricsConfig(
    val metricsEnabled: Boolean = true,
    val otelEnabled: Boolean = false,
    val otelEndpoint: String = "",
)
