package dev.spola.cli

import dev.spola.SpolaConfig
import dev.spola.agent.ProviderStore
import dev.spola.factory.ProviderResolver
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import kotlinx.coroutines.runBlocking
import picocli.CommandLine.Command
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name = "doctor", description = ["Diagnose configuration and environment"])
class DoctorCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: SpolaCli

    override fun call(): Int = runBlocking {
        val results = mutableListOf<DoctorResult>()
        val configPath = CliConfigSupport.configPath
        val effectiveConfig = runCatching { buildConfig(root) }
        val config = effectiveConfig.getOrDefault(SpolaConfig())
        if (effectiveConfig.isFailure) {
            results += DoctorResult.warn("Effective config", "buildConfig failed, using defaults: ${effectiveConfig.exceptionOrNull()?.message}")
        }
        val configExists = Files.exists(configPath)

        val loadedConfig = when {
            !configExists -> {
                results += DoctorResult.warn("Config file", "Missing: $configPath")
                null
            }
            else -> runCatching { CliConfigSupport.store.load() }
                .onSuccess { results += DoctorResult.pass("Config file", "Loaded: $configPath") }
                .onFailure { results += DoctorResult.error("Config file", "Invalid YAML: ${it.message}") }
                .getOrNull()
        }
        val configUsable = !configExists || loadedConfig != null

        databaseChecks(config).forEach(results::add)
        environmentCheck(config).also(results::add)
        providerConnectivityCheck(config, configUsable).also(results::add)
        personaCheck(config).also(results::add)
        skillsDirectoryCheck(config).also(results::add)
        javaVersionCheck().also(results::add)

        // Verbose/debug-aware output
        if (root.debug) {
            results += DoctorResult.pass("Verbosity", "DEBUG mode — showing detailed diagnostics")
            results += DoctorResult.pass("Effective config", "provider=${config.provider}, model=${config.model}, turns=${config.maxTurns}")
            results += DoctorResult.pass("Working directory", config.workingDirectory)
        } else if (root.verbose) {
            results += DoctorResult.pass("Verbosity", "VERBOSE mode")
        }

        println("${ANSI_BOLD}Spola doctor${ANSI_RESET}")
        println("Config path: $configPath")
        println()
        results.forEach { result ->
            val color = when (result.level) {
                DoctorLevel.PASS -> ANSI_GREEN
                DoctorLevel.WARN -> ANSI_YELLOW
                DoctorLevel.ERROR -> ANSI_RED
            }
            if (result.level == DoctorLevel.ERROR) {
                System.err.println("${color}${result.icon} ${result.label}: ${result.message}${ANSI_RESET}")
            } else {
                println("${color}${result.icon} ${result.label}: ${result.message}${ANSI_RESET}")
            }
        }

        return@runBlocking if (results.any { it.level == DoctorLevel.ERROR }) 1 else 0
    }

    private fun databaseChecks(config: SpolaConfig): List<DoctorResult> {
        return listOf(
            "memory.db" to config.memoryDbPath,
            "scheduler.db" to config.schedulerDbPath,
            "kanban.db" to config.kanbanDbPath,
            "jvm-index.db" to config.jvmIndexDbPath,
            "checkpoint.db" to config.checkpointDbPath,
        ).map { (label, path) ->
            writablePathCheck("Database $label", path)
        }
    }

    private fun environmentCheck(config: SpolaConfig): DoctorResult {
        val provider = config.provider.lowercase()
        val message = when (provider) {
            "openai" -> singleEnvCheck("OPENAI_API_KEY")
            "anthropic" -> singleEnvCheck("ANTHROPIC_API_KEY")
            "google" -> singleEnvCheck("GOOGLE_API_KEY")
            "openai-compat" -> anyEnvCheck("OPENAI_COMPAT_API_KEY", "OPENAI_API_KEY")
            "ollama" -> "No API key required"
            else -> {
                // Check if it's a custom provider defined in config
                if (config.customProviders.any { it.name.lowercase() == provider }) {
                    anyEnvCheck("SPOLA_API_KEY", "GOLEM_API_KEY") +
                        " (custom provider '$provider' may use its own apiKey field)"
                } else {
                    return DoctorResult.warn("Environment", "Unsupported provider '$provider'")
                }
            }
        }
        val level = if (message.startsWith("Missing")) DoctorLevel.ERROR else DoctorLevel.PASS
        return DoctorResult(level, "Environment", message)
    }

    private suspend fun providerConnectivityCheck(config: SpolaConfig, configValid: Boolean): DoctorResult {
        if (!configValid) {
            return DoctorResult.warn("Provider connectivity", "Skipped — config file is missing or invalid")
        }

        return runCatching {
            val (provider, model) = ProviderResolver.resolveFromConfig(config, ProviderStore.fromEnvironment())
            try {
                val response = provider.complete(
                    ModelRequest(
                        model = model,
                        messages = listOf(
                            Message(
                                role = MessageRole.USER,
                                content = "Reply with pong.",
                            ),
                        ),
                        maxTokens = 8,
                        temperature = 0.0,
                        timeoutMillis = 15_000,
                    ),
                )
                val modelUsed = response.modelUsed ?: model
                DoctorResult.pass(
                    "Provider connectivity",
                    "Provider ${config.provider} responded with model $modelUsed",
                )
            } finally {
                runCatching { (provider as? AutoCloseable)?.close() }
            }
        }.getOrElse {
            DoctorResult.error("Provider connectivity", it.message ?: it::class.simpleName ?: "Unknown error")
        }
    }

    private fun personaCheck(config: SpolaConfig): DoctorResult {
        return try {
            val personaPath = config.personaPath ?: return DoctorResult.pass("Persona file", "Using default persona resolution")
            val path = Path.of(personaPath)
            if (Files.exists(path)) {
                DoctorResult.pass("Persona file", "Found: $path")
            } else {
                DoctorResult.error("Persona file", "Missing: $path")
            }
        } catch (e: InvalidPathException) {
            DoctorResult.error("Persona file", "Invalid path: ${e.message}")
        }
    }

    private fun skillsDirectoryCheck(config: SpolaConfig): DoctorResult {
        return try {
            val path = Path.of(config.skillsDir)
            if (Files.isDirectory(path)) {
                DoctorResult.pass("Skills directory", "Found: $path")
            } else {
                DoctorResult.warn("Skills directory", "Missing: $path")
            }
        } catch (e: InvalidPathException) {
            DoctorResult.warn("Skills directory", "Invalid path: ${e.message}")
        }
    }

    private fun javaVersionCheck(): DoctorResult {
        val version = System.getProperty("java.version") ?: "unknown"
        return DoctorResult.pass("Java version", version)
    }

    private fun singleEnvCheck(name: String): String {
        return if (System.getenv(name).isNullOrBlank()) "Missing $name" else "$name is set"
    }

    private fun anyEnvCheck(vararg names: String): String {
        val present = names.firstOrNull { !System.getenv(it).isNullOrBlank() }
        return if (present != null) "$present is set" else "Missing one of: ${names.joinToString(", ")}"
    }

    private fun writablePathCheck(label: String, rawPath: String): DoctorResult {
        return try {
            val path = Path.of(rawPath).toAbsolutePath().normalize()
            when {
                Files.exists(path) && Files.isDirectory(path) -> DoctorResult.error(label, "Is a directory, not a database file: $path")
                Files.exists(path) && Files.isWritable(path) -> DoctorResult.pass(label, "Writable: $path")
                Files.exists(path) -> DoctorResult.error(label, "Not writable: $path")
                else -> {
                    val parent = path.parent
                        val ancestor = generateSequence(parent) { it.parent }.firstOrNull { Files.exists(it) }
                    when {
                        parent == null -> DoctorResult.error(label, "No parent directory for $path")
                        ancestor == null -> DoctorResult.error(label, "No existing parent directory for $path")
                        Files.isWritable(ancestor) -> DoctorResult.pass(label, "Creatable under: $ancestor")
                        else -> DoctorResult.error(label, "Parent directory is not writable: $ancestor")
                    }
                }
            }
        } catch (e: Exception) {
            DoctorResult.error(label, e.message ?: "Invalid path")
        }
    }
}

private enum class DoctorLevel {
    PASS,
    WARN,
    ERROR,
}

private data class DoctorResult(
    val level: DoctorLevel,
    val label: String,
    val message: String,
) {
    val icon: String
        get() = when (level) {
            DoctorLevel.PASS -> "✅"
            DoctorLevel.WARN -> "⚠️"
            DoctorLevel.ERROR -> "✗"
        }

    companion object {
        fun pass(label: String, message: String) = DoctorResult(DoctorLevel.PASS, label, message)
        fun warn(label: String, message: String) = DoctorResult(DoctorLevel.WARN, label, message)
        fun error(label: String, message: String) = DoctorResult(DoctorLevel.ERROR, label, message)
    }
}
