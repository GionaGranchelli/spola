package dev.spola.plugin

import dev.spola.SpolaConfig
import dev.spola.ToolRegistry
import dev.tramai.orchestration.ExternalStepExecutorRegistry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import java.net.URLClassLoader
import java.util.concurrent.CopyOnWriteArrayList

object PluginLoader {
    private val logger = LoggerFactory.getLogger(PluginLoader::class.java)

    // Keep plugin classloaders reachable for the lifetime of the process.
    private val pluginClassLoaders = CopyOnWriteArrayList<ClassLoader>()
    private val loadedPluginInstances = CopyOnWriteArrayList<SpolaPlugin>()

    /**
     * Returns a snapshot of all currently loaded plugin instances.
     * The returned list is safe to iterate even if plugins are concurrently
     * being added (e.g. during [loadPlugins] or removed during [shutdownPlugins]).
     */
    fun loadedPlugins(): List<SpolaPlugin> = loadedPluginInstances.toList()

    /**
     * Discovers and loads all Spola plugins from the configured plugin directory.
     *
     * For each discovered JAR, plugins are located via [java.util.ServiceLoader],
     * instantiated, and registered with the given [registry]. If a
     * [stepExecutorRegistry] is provided, each successfully loaded plugin is also
     * registered as a [SpolaPluginStepExecutorFactory] so that TramAI workflows
     * can use it as an external step executor.
     *
     * @param registry the [ToolRegistry] into which each plugin registers its tools
     * @param config the [SpolaConfig] providing plugin settings (directory, enabled flag)
     * @param stepExecutorRegistry optional [ExternalStepExecutorRegistry] for workflow
     *   plugin-step registration; defaults to `null` (no workflow-step registration)
     * @return the list of successfully loaded plugin names
     */
    suspend fun loadPlugins(
        registry: ToolRegistry,
        config: SpolaConfig,
        stepExecutorRegistry: ExternalStepExecutorRegistry? = null,
    ): List<String> {
        if (!config.pluginsEnabled) {
            return emptyList()
        }

        val pluginsDir = Path.of(config.pluginsDir)
        if (!Files.isDirectory(pluginsDir)) {
            logger.debug("Plugin directory does not exist or is not a directory: {}", pluginsDir)
            return emptyList()
        }

        val jarPaths = Files.list(pluginsDir).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar", ignoreCase = true) }
                .sorted()
                .toList()
        }

        val loadedPlugins = mutableListOf<String>()
        for (jarPath in jarPaths) {
            loadPluginsFromJar(jarPath, registry, loadedPlugins)
        }

        // If a step executor registry was provided, register each loaded plugin
        // as an external step executor so TramAI workflows can use them as plugin steps.
        if (stepExecutorRegistry != null) {
            val loaded = loadedPluginInstances.toList()
            for (plugin in loaded) {
                stepExecutorRegistry.register(SpolaPluginStepExecutorFactory(plugin))
            }
        }

        return loadedPlugins
    }

    private suspend fun loadPluginsFromJar(
        jarPath: Path,
        registry: ToolRegistry,
        loadedPlugins: MutableList<String>,
    ) {
        val classLoader = try {
            URLClassLoader(
                arrayOf(jarPath.toUri().toURL()),
                SpolaPlugin::class.java.classLoader,
            )
        } catch (e: Exception) {
            logger.warn("Failed to prepare plugin JAR {}: {}", jarPath, e.message)
            return
        }

        pluginClassLoaders += classLoader

        val serviceLoader = try {
            ServiceLoader.load(SpolaPlugin::class.java, classLoader)
        } catch (e: ServiceConfigurationError) {
            logger.warn("Failed to discover plugins in {}: {}", jarPath, e.message)
            return
        }

        val iterator = serviceLoader.iterator()
        while (true) {
            val plugin = try {
                if (!iterator.hasNext()) {
                    break
                }
                iterator.next()
            } catch (e: ServiceConfigurationError) {
                logger.warn("Failed to load plugin implementation from {}: {}", jarPath, e.message)
                continue
            }

            try {
                plugin.register(registry)
                loadedPlugins += plugin.pluginName
                loadedPluginInstances += plugin
            } catch (e: Exception) {
                logger.warn("Plugin {} from {} failed during registration: {}", plugin.pluginName, jarPath, e.message)
            }
        }
    }

    suspend fun shutdownPlugins() {
        val pluginsToShutdown = loadedPluginInstances.toList()
        loadedPluginInstances.clear()
        for (plugin in pluginsToShutdown) {
            try {
                plugin.onShutdown()
            } catch (e: Exception) {
                logger.warn(
                    "Plugin {}@{} failed during shutdown: {}",
                    plugin.pluginName,
                    plugin.pluginVersion,
                    e.message,
                )
            }
        }
    }
}
