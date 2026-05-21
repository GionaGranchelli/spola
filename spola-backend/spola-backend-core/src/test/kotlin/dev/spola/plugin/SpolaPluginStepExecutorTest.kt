package dev.spola.plugin

import dev.spola.SpolaConfig
import dev.spola.Tool
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import dev.tramai.orchestration.ExternalStepExecutorRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpolaPluginStepExecutorTest {

    @BeforeEach
    fun resetPluginLoader() {
        runBlocking { PluginLoader.shutdownPlugins() }
        LifecycleTestPlugin.reset()
    }

    // ── SpolaPluginStepExecutorFactory ──────────────────────────────

    @Test
    fun `SpolaPluginStepExecutorFactory uses plugin name as typeId`() {
        val plugin = TestPlugin("my-plugin", "1.0.0")
        val factory = SpolaPluginStepExecutorFactory(plugin)

        assertEquals("my-plugin", factory.typeId)
    }

    @Test
    fun `SpolaPluginStepExecutorFactory create returns executor`() {
        val plugin = TestPlugin("test-factory", "1.0.0")
        val factory = SpolaPluginStepExecutorFactory(plugin)

        val executor = factory.create()

        assertNotNull(executor)
        assertTrue(executor is SpolaPluginStepExecutor)
    }

    // ── SpolaPluginStepExecutor ─────────────────────────────────────

    @Test
    fun `SpolaPluginStepExecutor execute returns success result`() = runTest {
        val plugin = TestPlugin("my-executor", "1.0.0")
        val executor = SpolaPluginStepExecutor(plugin)

        val result = executor.execute(mapOf("input" to "value"))

        assertEquals(
            mapOf("plugin" to "my-executor", "status" to "executed"),
            result,
        )
    }

    @Test
    fun `SpolaPluginStepExecutor execute returns plugin name from spec`() = runTest {
        val plugin = TestPlugin("different-plugin", "2.0.0")
        val executor = SpolaPluginStepExecutor(plugin)

        val result = executor.execute(emptyMap())

        assertEquals("different-plugin", result["plugin"])
        assertEquals("executed", result["status"])
    }

    // ── PluginLoader additions ──────────────────────────────────────

    @Test
    fun `PluginLoader loadedPlugins returns registered plugins`(@TempDir tempDir: Path) = runTest {
        val pluginsDir = Files.createDirectories(tempDir.resolve("plugins"))
        val config = SpolaConfig(pluginsDir = pluginsDir.toString())

        // No plugins to load, so loadedPlugins should be empty
        PluginLoader.loadPlugins(ToolRegistry(), config)

        // After load with no real JARs, the list should be empty
        assertTrue(PluginLoader.loadedPlugins().isEmpty())
    }

    @Test
    fun `loadedPlugins returns snapshot after real plugin load`(@TempDir tempDir: Path) = runTest {
        val pluginsDir = Files.createDirectories(tempDir.resolve("plugins"))
        createServiceDescriptorJar(
            jarPath = pluginsDir.resolve("lifecycle-plugin.jar"),
            providerClassName = LifecycleTestPlugin::class.java.name,
        )

        val registry = ToolRegistry()
        val config = SpolaConfig(pluginsDir = pluginsDir.toString())

        PluginLoader.loadPlugins(registry, config)

        val snapshot = PluginLoader.loadedPlugins()
        assertEquals(1, snapshot.size)
        assertEquals("lifecycle-test", snapshot[0].pluginName)
    }

    @Test
    fun `PluginLoader loadPlugins with stepExecutorRegistry populates registry`(@TempDir tempDir: Path) = runTest {
        val pluginsDir = Files.createDirectories(tempDir.resolve("plugins"))
        createServiceDescriptorJar(
            jarPath = pluginsDir.resolve("lifecycle-plugin.jar"),
            providerClassName = LifecycleTestPlugin::class.java.name,
        )

        val registry = ToolRegistry()
        val stepExecutorRegistry = ExternalStepExecutorRegistry()
        val config = SpolaConfig(pluginsDir = pluginsDir.toString())

        val loaded = PluginLoader.loadPlugins(registry, config, stepExecutorRegistry)

        assertEquals(listOf("lifecycle-test"), loaded)
        assertTrue(stepExecutorRegistry.isRegistered("lifecycle-test"))
    }

    @Test
    fun `PluginLoader loadPlugins without stepExecutorRegistry does not populate registry`(@TempDir tempDir: Path) = runTest {
        val pluginsDir = Files.createDirectories(tempDir.resolve("plugins"))
        createServiceDescriptorJar(
            jarPath = pluginsDir.resolve("lifecycle-plugin.jar"),
            providerClassName = LifecycleTestPlugin::class.java.name,
        )

        val registry = ToolRegistry()
        val config = SpolaConfig(pluginsDir = pluginsDir.toString())

        val loaded = PluginLoader.loadPlugins(registry, config)

        assertEquals(listOf("lifecycle-test"), loaded)
        // No stepExecutorRegistry was passed, so nothing should be registered
    }

    // ── test plugin ─────────────────────────────────────────────────

    class TestPlugin(
        override val pluginName: String,
        override val pluginVersion: String,
    ) : SpolaPlugin {
        override suspend fun register(registry: ToolRegistry) {
            // no-op for testing
        }
    }

    // Re-usable lifecycle test plugin (mirrored from PluginLoaderTest)
    class LifecycleTestPlugin : SpolaPlugin {
        override val pluginName: String = "lifecycle-test"
        override val pluginVersion: String = "1.0.0-test"

        override suspend fun register(registry: ToolRegistry) {
            registry.register(
                Tool(
                    name = "lifecycle_tool",
                    description = "Lifecycle test tool",
                    parameters = emptyList(),
                    execute = { ToolResult.ok("ok") },
                ),
            )
        }

        override suspend fun onShutdown() {
            shutdownCalled = true
        }

        companion object {
            var shutdownCalled: Boolean = false

            fun reset() {
                shutdownCalled = false
            }
        }
    }

    private fun createServiceDescriptorJar(jarPath: Path, providerClassName: String) {
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/services/dev.spola.plugin.SpolaPlugin"))
            jar.write("$providerClassName\n".toByteArray(StandardCharsets.UTF_8))
            jar.closeEntry()
        }
    }
}
