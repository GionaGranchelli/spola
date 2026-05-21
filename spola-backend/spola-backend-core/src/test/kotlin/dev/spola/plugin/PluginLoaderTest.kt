package dev.spola.plugin

import dev.spola.SpolaConfig
import dev.spola.Tool
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginLoaderTest {

    @Test
    fun `loadPlugins returns empty when plugins dir is missing`(@TempDir tempDir: Path) = runTest {
        val config = SpolaConfig(
            pluginsDir = tempDir.resolve("missing-plugins").toString(),
        )

        val loaded = PluginLoader.loadPlugins(ToolRegistry(), config)

        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `loadPlugins returns empty when plugins dir has no jars`(@TempDir tempDir: Path) = runTest {
        val pluginsDir = Files.createDirectories(tempDir.resolve("plugins"))
        Files.writeString(pluginsDir.resolve("README.txt"), "not a plugin")
        val config = SpolaConfig(pluginsDir = pluginsDir.toString())

        val loaded = PluginLoader.loadPlugins(ToolRegistry(), config)

        assertEquals(emptyList(), loaded)
    }

    @Test
    fun `loadPlugins ignores malformed jar and continues`(@TempDir tempDir: Path) = runTest {
        val pluginsDir = Files.createDirectories(tempDir.resolve("plugins"))
        Files.writeString(pluginsDir.resolve("broken-plugin.jar"), "not really a jar")
        val config = SpolaConfig(pluginsDir = pluginsDir.toString())

        val loaded = PluginLoader.loadPlugins(ToolRegistry(), config)

        assertEquals(emptyList(), loaded)
    }

    @Test
    fun `loadPlugins registers plugin and shutdown calls hook`(@TempDir tempDir: Path) = runTest {
        LifecycleTestPlugin.reset()
        PluginLoader.shutdownPlugins()

        val pluginsDir = Files.createDirectories(tempDir.resolve("plugins"))
        createServiceDescriptorJar(
            jarPath = pluginsDir.resolve("lifecycle-plugin.jar"),
            providerClassName = LifecycleTestPlugin::class.java.name,
        )
        val registry = ToolRegistry()
        val config = SpolaConfig(pluginsDir = pluginsDir.toString())

        val loaded = PluginLoader.loadPlugins(registry, config)

        assertEquals(listOf("lifecycle-test"), loaded)
        assertTrue(registry.get("lifecycle_tool") != null)
        assertFalse(LifecycleTestPlugin.shutdownCalled)

        PluginLoader.shutdownPlugins()

        assertTrue(LifecycleTestPlugin.shutdownCalled)
    }

    private fun createServiceDescriptorJar(jarPath: Path, providerClassName: String) {
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/services/dev.spola.plugin.SpolaPlugin"))
            jar.write("$providerClassName\n".toByteArray(StandardCharsets.UTF_8))
            jar.closeEntry()
        }
    }

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
}
