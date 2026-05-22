package dev.spola.config

import com.fasterxml.jackson.annotation.JsonProperty

data class CustomProviderConfig(
    val name: String,
    val type: String,
    val baseUrl: String,
    val apiKey: String? = null,
    val model: String? = null,
)

data class ProviderConfig(
    val apiKey: String = "",
    val defaultProvider: String = "openai",
    val defaultModel: String = "gpt-4o",
    @param:JsonProperty("custom_providers")
    val customProviders: List<CustomProviderConfig> = emptyList(),
)
