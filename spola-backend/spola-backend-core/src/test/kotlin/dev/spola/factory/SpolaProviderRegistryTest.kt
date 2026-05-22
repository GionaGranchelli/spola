package dev.spola.factory

import dev.spola.SpolaConfig
import dev.spola.config.CustomProviderConfig
import dev.tramai.anthropic.AnthropicProvider
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.openai.OpenAiProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpolaProviderRegistryTest {

    // -----------------------------------------------------------------------
    // createProvider — type mapping
    // -----------------------------------------------------------------------

    @Test
    fun `createProvider for openai returns OpenAiProvider with default base URL`() {
        val provider = SpolaProviderRegistry.createProvider("openai", "sk-test", null)
        assertIs<OpenAiProvider>(provider)
        assertEquals("openai", provider.providerId())
    }

    @Test
    fun `createProvider for openai with custom base URL`() {
        val provider = SpolaProviderRegistry.createProvider("openai", "sk-test", "https://custom.openai.com/v1")
        assertIs<OpenAiProvider>(provider)
        assertEquals("openai", provider.providerId())
    }

    @Test
    fun `createProvider for anthropic returns AnthropicProvider`() {
        val provider = SpolaProviderRegistry.createProvider("anthropic", "sk-ant-test", null)
        assertIs<AnthropicProvider>(provider)
        assertEquals("anthropic", provider.providerId())
    }

    @Test
    fun `createProvider for anthropic with custom base URL`() {
        val provider = SpolaProviderRegistry.createProvider("anthropic", "sk-ant-test", "https://custom.anthropic.com")
        assertIs<AnthropicProvider>(provider)
    }

    @Test
    fun `createProvider for openai-compat returns OpenAiProvider`() {
        val provider = SpolaProviderRegistry.createProvider("openai-compat", "sk-test", null)
        assertIs<OpenAiProvider>(provider)
    }

    @Test
    fun `createProvider for ollama returns OpenAiProvider`() {
        val provider = SpolaProviderRegistry.createProvider("ollama", "ollama", null)
        assertIs<OpenAiProvider>(provider)
    }

    @Test
    fun `createProvider for google returns OpenAiProvider`() {
        val provider = SpolaProviderRegistry.createProvider("google", "ai-test-key", null)
        assertIs<OpenAiProvider>(provider)
    }

    @Test
    fun `createProvider for deepseek returns OpenAiProvider`() {
        val provider = SpolaProviderRegistry.createProvider("deepseek", "ds-test", null)
        assertIs<OpenAiProvider>(provider)
    }

    @Test
    fun `createProvider for unknown type throws`() {
        val ex = assertThrows<IllegalArgumentException> {
            SpolaProviderRegistry.createProvider("nonexistent", "key", null)
        }
        assertContains(ex.message!!, "Unsupported provider type")
    }

    // -----------------------------------------------------------------------
    // ProviderRegistry building with custom providers (no env vars needed)
    // -----------------------------------------------------------------------

    @Test
    fun `build registers custom providers from config`() {
        val config = SpolaConfig(
            provider = "openai",
            model = "gpt-4o",
            customProviders = listOf(
                CustomProviderConfig(
                    name = "my-ollama",
                    type = "ollama",
                    baseUrl = "http://ollama:11434/v1",
                    apiKey = "ollama",
                    model = "llama3",
                ),
            ),
        )

        // Build with empty fallback chain — custom providers only since no env vars are set
        val registry = SpolaProviderRegistry.build(
            config = config,
            fallbackChain = emptyMap(),
        )

        // The default provider ("openai") might not be in the registry if it's not
        // configured via env vars — that's fine. Let's verify the custom provider works.
        val customOperation = Operation(
            model = "llama3",
        )
        val resolved = registry.resolve(customOperation)
        assertNotNull(resolved)
        // OpenAiProvider's providerId() returns "openai-compatible" for plain
        // OpenAiCompatibleProvider, but "openai" for OpenAiProvider
        assertTrue(resolved is OpenAiProvider || resolved is AnthropicProvider)
    }

    @Test
    fun `build with empty env registers openai-compat as noop fallback`() {
        // ProviderStore.resolve("openai-compat") always succeeds with a "noop"
        // API key when no env vars are set. This test verifies that at least
        // that provider is registered so the registry is never empty.
        val config = SpolaConfig(
            provider = "openai",
            model = "gpt-4o",
            customProviders = emptyList(),
        )
        val emptyStore = dev.spola.agent.ProviderStore.fromEnvironment(emptyMap())

        val registry = SpolaProviderRegistry.build(
            config = config,
            providerStore = emptyStore,
            fallbackChain = emptyMap(),
        )

        // The openai-compat provider should be registered as a noop fallback.
        // Resolving any model should work because openai-compat is the default.
        val resolved = SpolaProviderRegistry.resolveDefault(registry, "gpt-4o")
        assertNotNull(resolved)
    }

    @Test
    fun `resolve returns correct provider for registered model`() {
        val registry = buildTestRegistryWithMockProviders()

        val operation = Operation(model = "gpt-4o")
        val provider = registry.resolve(operation)
        assertEquals("mock-openai", provider.providerId())
    }

    @Test
    fun `resolve uses default provider for unregistered model`() {
        val registry = buildTestRegistryWithMockProviders()

        val operation = Operation(model = "unknown-model")
        val provider = registry.resolve(operation)
        // Default provider is "mock-openai"
        assertEquals("mock-openai", provider.providerId())
    }

    @Test
    fun `resolve throws ConfigurationException for unknown model without default`() {
        val registry = buildTestRegistryWithoutDefault()

        val operation = Operation(model = "unknown-model")
        val ex = assertThrows<ConfigurationException> {
            registry.resolve(operation)
        }
        assertContains(ex.message!!, "No provider is registered")
    }

    // -----------------------------------------------------------------------
    // Fallback chain tests
    // -----------------------------------------------------------------------

    @Test
    fun `resolveCandidates returns fallback chain in order`() {
        val registry = buildTestRegistryWithFallback()

        val operation = Operation(model = "gpt-4o")
        val candidates = registry.resolveCandidates(operation)

        assertEquals(2, candidates.size)
        assertEquals("mock-openai", candidates[0].providerName)
        assertEquals("mock-anthropic", candidates[1].providerName)
    }

    @Test
    fun `resolveCandidates with no fallback returns single candidate`() {
        val registry = buildTestRegistryWithMockProviders()

        val operation = Operation(model = "gpt-4o")
        val candidates = registry.resolveCandidates(operation)

        assertEquals(1, candidates.size)
        assertEquals("mock-openai", candidates[0].providerName)
    }

    @Test
    fun `resolveCandidates falls back to default when model has no explicit mapping`() {
        val registry = buildTestRegistryWithMockProviders()

        val operation = Operation(model = "claude-3-opus")
        val candidates = registry.resolveCandidates(operation)

        // No explicit mapping for claude-3-opus, so it falls back to default
        assertEquals(1, candidates.size)
        assertEquals("mock-openai", candidates[0].providerName)
    }

    @Test
    fun `fallback chain from SpolaProviderRegistry build is respected`() {
        // Build a config that has custom providers, then apply a fallback chain
        val config = SpolaConfig(
            provider = "my-openai",
            model = "gpt-4o",
            customProviders = listOf(
                CustomProviderConfig(
                    name = "my-openai",
                    type = "openai",
                    baseUrl = "http://localhost:8080/v1",
                    apiKey = "sk-test",
                    model = "gpt-4o",
                ),
                CustomProviderConfig(
                    name = "my-anthropic",
                    type = "anthropic",
                    baseUrl = "",
                    apiKey = "sk-ant-test",
                    model = "claude-3-opus",
                ),
            ),
        )

        val registry = SpolaProviderRegistry.build(
            config = config,
            fallbackChain = mapOf("gpt-4o" to "my-anthropic"),
        )

        val candidates = registry.resolveCandidates(Operation(model = "gpt-4o"))

        assertEquals(2, candidates.size)
        assertEquals("my-openai", candidates[0].providerName)
        assertEquals("my-anthropic", candidates[1].providerName)
    }

    // -----------------------------------------------------------------------
    // resolveDefault / resolveCandidates convenience methods
    // -----------------------------------------------------------------------

    @Test
    fun `resolveDefault returns the same as registry resolve`() {
        val registry = buildTestRegistryWithMockProviders()

        val operation = Operation(model = "gpt-4o")
        val direct = registry.resolve(operation)
        val convenience = SpolaProviderRegistry.resolveDefault(registry, "gpt-4o")

        assertEquals(direct.providerId(), convenience.providerId())
    }

    @Test
    fun `resolveCandidates convenience delegates to registry`() {
        val registry = buildTestRegistryWithFallback()

        val operation = Operation(model = "gpt-4o")
        val direct = registry.resolveCandidates(operation)
        val convenience = SpolaProviderRegistry.resolveCandidates(registry, "gpt-4o")

        assertEquals(direct.size, convenience.size)
        assertEquals(direct[0].providerName, convenience[0].providerName)
        assertEquals(direct[1].providerName, convenience[1].providerName)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a [ProviderRegistry] with two mock providers and a fallback chain
     * from "gpt-4o" → mock-openai (primary) → mock-anthropic (fallback).
     */
    private fun buildTestRegistryWithFallback(): ProviderRegistry {
        return ProviderRegistry.builder()
            .provider("mock-openai", MockProvider("mock-openai"), default = true)
            .provider("mock-anthropic", MockProvider("mock-anthropic"))
            .model("gpt-4o", "mock-openai")
            .fallbackProvider("gpt-4o", "mock-anthropic")
            .defaultProvider("mock-openai")
            .build()
    }

    /**
     * Builds a [ProviderRegistry] with two mock providers, no fallback.
     * Default provider: mock-openai.
     */
    private fun buildTestRegistryWithMockProviders(): ProviderRegistry {
        return ProviderRegistry.builder()
            .provider("mock-openai", MockProvider("mock-openai"), default = true)
            .provider("mock-anthropic", MockProvider("mock-anthropic"))
            .model("gpt-4o", "mock-openai")
            .defaultProvider("mock-openai")
            .build()
    }

    /**
     * Builds a [ProviderRegistry] with two mock providers but NO default.
     */
    private fun buildTestRegistryWithoutDefault(): ProviderRegistry {
        return ProviderRegistry.builder()
            .provider("mock-openai", MockProvider("mock-openai"))
            .provider("mock-anthropic", MockProvider("mock-anthropic"))
            .model("claude-3-opus", "mock-anthropic")
            .build()
    }

    /**
     * Minimal [ModelProvider] stub for unit-test routing.
     */
    private class MockProvider(
        private val id: String,
    ) : ModelProvider {
        override suspend fun complete(
            request: dev.tramai.core.model.ModelRequest,
        ): dev.tramai.core.model.ModelResponse {
            throw UnsupportedOperationException("Mock provider should not be called in routing tests")
        }

        override fun providerId(): String = id

        override fun supportsCapability(
            capability: dev.tramai.core.provider.ProviderCapability,
        ): Boolean = true
    }
}
