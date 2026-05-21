# Plugin System

Spola's plugin system lets you extend the agent with custom tools and workflow steps without modifying core code. Plugins are JAR files placed in `~/.spola/plugins/` that implement the `SpolaPlugin` interface.

## How It Works

1. **Discovery** — `PluginLoader` scans `~/.spola/plugins/` for `.jar` files
2. **ServiceLoader** — Each JAR is loaded with its own `URLClassLoader`; implementations of `SpolaPlugin` are discovered via `java.util.ServiceLoader`
3. **Registration** — Each plugin's `register()` method is called with the `ToolRegistry` so it can register its tools
4. **Workflow steps** — Each loaded plugin is also registered as a `SpolaPluginStepExecutorFactory` so TramAI workflows can use it as an external step executor

Plugin loading respects the `plugins-enabled` config flag. If `plugins-enabled: false`, no JARs are loaded.

## SpolaPlugin Interface

```kotlin
interface SpolaPlugin {
    val pluginName: String
    val pluginVersion: String
    suspend fun register(registry: ToolRegistry)
    suspend fun onShutdown() {}
}
```

- **`pluginName`** — Unique identifier used for display and workflow step type matching
- **`pluginVersion`** — Version string (display only)
- **`register(registry)`** — Called during plugin loading. Register tools, commands, or configure anything needed
- **`onShutdown()`** — Called when the plugin is unloaded. Clean up resources (threads, connections, file handles)

## PluginLoader

`PluginLoader` is a singleton object that manages the plugin lifecycle:

```kotlin
PluginLoader.loadPlugins(
    registry = toolRegistry,
    config = spolaConfig,
    stepExecutorRegistry = externalStepExecutorRegistry,  // optional
)
```

Key behaviors:
- If `pluginsDir` doesn't exist, loading is skipped silently
- JARs are loaded in sorted (alphabetical) order
- Each JAR gets its own `URLClassLoader` to prevent class conflicts
- If a JAR fails to load, it's skipped with a warning (other JARs still load)
- If `ServiceLoader` finds multiple `SpolaPlugin` implementations in one JAR, all are loaded

## SpolaPluginStepExecutor

Plugins are automatically registered as workflow step executors via `SpolaPluginStepExecutorFactory`. This adapts a plugin into TramAI's `ExternalStepExecutor` system, allowing workflows to invoke plugins as DAG steps.

```kotlin
class SpolaPluginStepExecutor(plugin: SpolaPlugin) : ExternalStepExecutor {
    override suspend fun execute(spec: Map<String, Any?>): Map<String, Any?> {
        return mapOf("plugin" to plugin.pluginName, "status" to "executed")
    }
}
```

The step executor receives the workflow step's specification map and returns results. Currently it returns a success indicator; future versions may delegate to the plugin's registered tools.

## Creating a Plugin

### Step 1: Create a Kotlin/Gradle project

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm")
    id("com.gradleup.shadow") version "8.3.5"  // for fat JAR
}

dependencies {
    implementation("dev.spola:spola-backend-core:0.1.0")
    // Your own dependencies here
}
```

### Step 2: Implement SpolaPlugin

```kotlin
package com.example.spolaplugin

import dev.spola.SpolaPlugin
import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry

class MyPlugin : SpolaPlugin {
    override val pluginName = "my-plugin"
    override val pluginVersion = "1.0.0"

    override suspend fun register(registry: ToolRegistry) {
        registry.register(
            Tool(
                name = "my_custom_tool",
                description = "Does something custom",
                parameters = listOf(
                    ToolParameter("input", "Input data", ToolParameterType.STRING)
                ),
                execute = { args ->
                    val input = args["input"] as? String ?: "nothing"
                    ToolResult.ok("Processed: $input")
                }
            )
        )
    }

    override suspend fun onShutdown() {
        // Clean up resources
    }
}
```

### Step 3: Create ServiceLoader configuration

Create `META-INF/services/dev.spola.plugin.SpolaPlugin` with the fully-qualified class name:

```
com.example.spolaplugin.MyPlugin
```

### Step 4: Build the JAR

```bash
./gradlew shadowJar
```

### Step 5: Install the plugin

```bash
cp build/libs/my-plugin-1.0.0-all.jar ~/.spola/plugins/
```

The plugin is loaded automatically on next Spola start.

## Plugin Capabilities

Plugins can:

- **Register tools** — Any number of tools with any parameters
- **Provide workflow steps** — Via `SpolaPluginStepExecutorFactory`, the plugin's name becomes a step type in process engine workflows
- **Bundle dependencies** — Use shadow/fat JAR to include any third-party libraries
- **Access the full ToolRegistry API** — Register, unregister, modify tool definitions

## Current Limitations

- No plugin-specific configuration API (plugins can read environment variables or system properties directly)
- Step executor returns a fixed success response (future: delegate to tools)
- No plugin dependency ordering (JARs loaded alphabetically)
- Hot-reload is not supported (plugins loaded once at startup)

## Complete Example Plugin

```kotlin
// src/main/kotlin/com/example/weather/WeatherPlugin.kt
package com.example.weather

import dev.spola.SpolaPlugin
import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class WeatherPlugin : SpolaPlugin {
    override val pluginName = "weather"
    override val pluginVersion = "1.0.0"

    private val client = HttpClient.newHttpClient()

    override suspend fun register(registry: ToolRegistry) {
        registry.register(
            Tool(
                name = "weather_current",
                description = "Get current weather for a city",
                parameters = listOf(
                    ToolParameter(
                        name = "city",
                        description = "City name",
                        type = ToolParameterType.STRING,
                        required = true
                    )
                ),
                execute = { args ->
                    val city = (args["city"] as? String)
                        ?: return@Tool ToolResult.fail("Missing city")
                    // Simulated — replace with real API call
                    ToolResult.ok("Weather in $city: 22°C, partly cloudy")
                }
            )
        )
    }

    override suspend fun onShutdown() {
        client.close()
    }
}
```

```
# META-INF/services/dev.spola.plugin.SpolaPlugin
com.example.weather.WeatherPlugin
```
