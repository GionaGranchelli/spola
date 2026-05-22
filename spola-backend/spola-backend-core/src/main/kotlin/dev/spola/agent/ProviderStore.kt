package dev.spola.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves provider credentials from the environment once and reuses them.
 */
class ProviderStore private constructor(
    private val env: Map<String, String>,
) {
    private val cache = ConcurrentHashMap<String, ProviderConfig>()

    fun get(providerName: String): ProviderConfig {
        return cache.computeIfAbsent(providerName.lowercase()) { resolve(it) }
    }

    private fun resolve(providerName: String): ProviderConfig {
        return when (providerName) {
            "openai" -> {
                val apiKey = env["OPENAI_API_KEY"]
                    ?: throw IllegalStateException("OPENAI_API_KEY not set for provider 'openai'")
                ProviderConfig(
                    provider = "openai",
                    model = "gpt-4o",
                    apiKey = apiKey,
                    baseUrl = env["OPENAI_BASE_URL"]?.takeIf { it.isNotBlank() },
                )
            }
            "anthropic" -> {
                val apiKey = env["ANTHROPIC_API_KEY"]
                    ?: throw IllegalStateException("ANTHROPIC_API_KEY not set for provider 'anthropic'")
                ProviderConfig(
                    provider = "anthropic",
                    model = "claude-sonnet-4-20250514",
                    apiKey = apiKey,
                )
            }
            "openai-compat" -> {
                ProviderConfig(
                    provider = "openai-compat",
                    model = "gpt-4o",
                    apiKey = env["OPENAI_COMPAT_API_KEY"] ?: env["OPENAI_API_KEY"] ?: "noop",
                    baseUrl = env["OPENAI_BASE_URL"] ?: "http://localhost:8090/v1",
                )
            }
            "ollama" -> {
                ProviderConfig(
                    provider = "ollama",
                    model = "llama3",
                    apiKey = "ollama",
                    baseUrl = env["OLLAMA_BASE_URL"] ?: "http://localhost:11434/v1",
                )
            }
            "google" -> {
                val apiKey = env["GOOGLE_API_KEY"]
                    ?: throw IllegalStateException("GOOGLE_API_KEY not set for provider 'google'")
                ProviderConfig(
                    provider = "google",
                    model = "gemini-2.5-pro",
                    apiKey = apiKey,
                    baseUrl = env["GOOGLE_BASE_URL"] ?: "https://generativelanguage.googleapis.com/v1beta/openai",
                )
            }
            "deepseek" -> {
                val apiKey = env["DEEPSEEK_API_KEY"]
                    ?: throw IllegalStateException("DEEPSEEK_API_KEY not set for provider 'deepseek'")
                ProviderConfig(
                    provider = "deepseek",
                    model = "deepseek-chat",
                    apiKey = apiKey,
                    baseUrl = env["DEEPSEEK_BASE_URL"] ?: "https://api.deepseek.com/v1",
                )
            }
            else -> throw IllegalStateException("Unsupported or unconfigured provider: $providerName")
        }
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ProviderStore = ProviderStore(env)
    }
}
