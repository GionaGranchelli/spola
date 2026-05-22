package dev.spola.factory

import dev.spola.SpolaConfig
import dev.spola.agent.ProviderStore
import dev.tramai.anthropic.AnthropicProvider
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.openai.OpenAiProvider

private const val OPENAI_COMPAT = "openai-compat"
private val OPENAI_COMPAT_TYPES = setOf("openai", OPENAI_COMPAT, "ollama", "google", "deepseek")

/**
 * Wraps TramAI's [ProviderRegistry] to build it from [SpolaConfig], with
 * fallback-chain support, model routing, and support for all current Spola
 * provider types.
 *
 * This replaces the hardcoded per-provider construction in [ProviderResolver]
 * with TramAI's registry-based routing, enabling:
 * - Ordered fallback chains for a single model name
 * - Centralised provider resolution via [ProviderRegistry.resolve] /
 *   [ProviderRegistry.resolveCandidates]
 * - A single registry snapshot that is safe for concurrent use
 */
object SpolaProviderRegistry {

    /**
     * Builds a fully-configured [ProviderRegistry] from [SpolaConfig].
     *
     * @param config      the Spola configuration, must contain [SpolaConfig.provider]
     * @param providerStore the provider store to read environment-configured providers from
     * @param fallbackChain  optional map of model → fallback-provider-name; for each
     *                       entry, [ProviderRegistry.Builder.fallbackProvider] is called
     *                       so the fallback provider is tried when the primary fails.
     * @param operationObserver optional TramAI [OperationObserver] to bridge engine-level
     *                          callbacks into Spola's observability system. Currently
     *                          accepted for future wiring into [dev.tramai.engine.TramaiEngine]
     *                          or [dev.tramai.standalone.Tramai.Builder.observer]; the
     *                          [ProviderRegistry] itself has no observer support.
     * @return a sealed [ProviderRegistry] ready for [ProviderRegistry.resolve].
     * @throws IllegalStateException if no providers could be registered at all.
     */
    fun build(
        config: SpolaConfig,
        providerStore: ProviderStore = ProviderStore.fromEnvironment(),
        fallbackChain: Map<String, String> = emptyMap(),
        operationObserver: OperationObserver? = null,
    ): ProviderRegistry {
        val builder = ProviderRegistry.builder()
        val defaultProviderName = config.provider.defaultProvider
        var anyProviderRegistered = false

        // -----------------------------------------------------------------
        // 1. Register standard providers read from environment variables
        // -----------------------------------------------------------------
        for (providerName in STANDARD_PROVIDER_NAMES) {
            val providerConfig = try {
                providerStore.get(providerName)
            } catch (_: IllegalStateException) {
                // Not configured in environment — skip silently
                continue
            }

            val provider = createProvider(
                type = providerConfig.provider,
                apiKey = providerConfig.apiKey,
                baseUrl = providerConfig.baseUrl,
            )
            val isDefault = providerName == defaultProviderName
            builder.provider(providerName, provider, default = isDefault)
            anyProviderRegistered = true

            // Register the model that the provider config declares as default
            if (providerConfig.model.isNotBlank()) {
                builder.model(providerConfig.model, providerName)
            }
        }

        // -----------------------------------------------------------------
        // 2. Register custom providers declared in config.provider.customProviders
        // -----------------------------------------------------------------
        for (custom in config.provider.customProviders) {
            val provider = createProvider(
                type = custom.type,
                apiKey = custom.apiKey ?: "",
                baseUrl = custom.baseUrl,
            )
            val isDefault = custom.name == defaultProviderName
            builder.provider(custom.name, provider, default = isDefault)
            anyProviderRegistered = true

            if (!custom.model.isNullOrBlank()) {
                builder.model(custom.model, custom.name)
            }
        }

        // -----------------------------------------------------------------
        // 3. Apply fallback chains (model → fallback-provider)
        // -----------------------------------------------------------------
        for ((modelName, fallbackProviderName) in fallbackChain) {
            builder.fallbackProvider(modelName, fallbackProviderName)
        }

        // -----------------------------------------------------------------
        // 4. Ensure a default provider is wired
        // -----------------------------------------------------------------
        if (defaultProviderName.isNotBlank()) {
            builder.defaultProvider(defaultProviderName)
        }

        check(anyProviderRegistered) {
            "No LLM providers could be registered. Ensure at least one provider " +
                "(openai, anthropic, ollama, google, deepseek) is configured in the " +
                "environment or in config.provider.customProviders."
        }

        return builder.build()
    }

    /**
     * Convenience shortcut: call [ProviderRegistry.resolve] with a default
     * [Operation] for the given [modelName].
     *
     * @param registry  a built [ProviderRegistry]
     * @param modelName the logical model name to resolve
     * @return the resolved [ModelProvider]
     */
    fun resolveDefault(
        registry: ProviderRegistry,
        modelName: String,
    ): ModelProvider {
        val operation = dev.tramai.core.annotations.Operation(
            model = modelName,
        )
        return registry.resolve(operation)
    }

    /**
     * Convenience shortcut: call [ProviderRegistry.resolveCandidates] for the
     * given [modelName], returning the ordered list of fallbacks.
     */
    fun resolveCandidates(
        registry: ProviderRegistry,
        modelName: String,
    ): List<dev.tramai.core.provider.ResolvedProviderRoute> {
        val operation = dev.tramai.core.annotations.Operation(
            model = modelName,
        )
        return registry.resolveCandidates(operation)
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    /**
     * Constructs the appropriate TramAI [ModelProvider] for a given [type],
     * [apiKey], and optional [baseUrl].
     *
     * Supports the same type set as [ProviderResolver]:
     *  - `anthropic` → [AnthropicProvider]
     *  - `openai`, `openai-compat`, `ollama`, `google`, `deepseek` → [OpenAiProvider]
     *    (the base URL is adjusted per provider convention)
     */
    fun createProvider(
        type: String,
        apiKey: String,
        baseUrl: String?,
    ): ModelProvider {
        val lower = type.lowercase()
        return when {
            lower == "anthropic" -> AnthropicProvider(apiKey = apiKey)

            lower in OPENAI_COMPAT_TYPES -> {
                val effectiveBaseUrl = baseUrl.takeIf { !it.isNullOrBlank() }
                    ?: defaultBaseUrl(type)
                OpenAiProvider(apiKey = apiKey.ifBlank { "noop" }, baseUrl = effectiveBaseUrl)
            }

            else -> throw IllegalArgumentException(
                "Unsupported provider type '$type'. " +
                    "Supported types: openai, $OPENAI_COMPAT, ollama, google, deepseek",
            )
        }
    }

    private fun defaultBaseUrl(type: String): String = when (type.lowercase()) {
        "openai" -> "https://api.openai.com/v1"
        OPENAI_COMPAT -> "http://localhost:8090/v1"
        "ollama" -> "http://localhost:11434/v1"
        "google" -> "https://generativelanguage.googleapis.com/v1beta/openai"
        "deepseek" -> "https://api.deepseek.com/v1"
        else -> throw IllegalArgumentException("Unknown provider type: $type")
    }

    private val STANDARD_PROVIDER_NAMES = listOf(
        "openai", "anthropic", "openai-compat", "ollama", "google", "deepseek",
    )
}
