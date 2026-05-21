package dev.spola.factory

import dev.spola.SpolaConfig
import dev.spola.agent.ProviderConfig
import dev.spola.agent.ProviderStore
import dev.tramai.anthropic.AnthropicProvider
import dev.tramai.core.provider.ModelProvider
import dev.tramai.openai.OpenAiProvider

/**
 * Resolves LLM providers from configuration and environment.
 *
 * Provides a single source of truth for provider resolution,
 * eliminating duplication with [ArchitectMode][dev.spola.ArchitectRunner].
 */
object ProviderResolver {

    /**
     * Resolve a provider from [SpolaConfig] using the configured provider name and model.
     */
    fun resolveFromConfig(
        config: SpolaConfig,
        providerStore: ProviderStore = ProviderStore.fromEnvironment(),
    ): Pair<ModelProvider, String> {
        val providerConfig = providerStore.get(config.provider)
        return resolveNamed(
            providerConfig = providerConfig,
            modelName = config.model,
        )
    }

    /**
     * Resolve a specific named provider from [ProviderConfig].
     *
     * Supported providers: openai, anthropic, openai-compat, ollama, google
     */
    fun resolveNamed(
        providerConfig: ProviderConfig,
        modelName: String,
    ): Pair<ModelProvider, String> {
        when (providerConfig.provider.lowercase()) {
            "openai" -> {
                val baseUrl = providerConfig.baseUrl
                val provider = if (baseUrl != null && baseUrl.isNotBlank()) {
                    OpenAiProvider(apiKey = providerConfig.apiKey, baseUrl = baseUrl)
                } else {
                    OpenAiProvider(apiKey = providerConfig.apiKey)
                }
                return provider to modelName
            }
            "anthropic" -> {
                return AnthropicProvider(apiKey = providerConfig.apiKey) to modelName
            }
            "openai-compat" -> {
                val baseUrl = providerConfig.baseUrl ?: "http://localhost:8090/v1"
                return OpenAiProvider(apiKey = providerConfig.apiKey, baseUrl = baseUrl) to modelName
            }
            "ollama" -> {
                val baseUrl = providerConfig.baseUrl ?: "http://localhost:11434/v1"
                return OpenAiProvider(apiKey = providerConfig.apiKey, baseUrl = baseUrl) to modelName
            }
            "google" -> {
                val baseUrl = providerConfig.baseUrl ?: "https://generativelanguage.googleapis.com/v1beta/openai"
                return OpenAiProvider(apiKey = providerConfig.apiKey, baseUrl = baseUrl) to modelName
            }
            else -> throw IllegalStateException("Unsupported provider: ${providerConfig.provider}")
        }
    }
}
