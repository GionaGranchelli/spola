package dev.spola.cli

import dev.spola.SpolaConfig
import dev.spola.config.SpolaConfigFileStore
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "config",
    description = ["View and edit configuration"],
    subcommands = [
        ConfigShowCommand::class,
        ConfigPathCommand::class,
        ConfigSetCommand::class,
        ConfigEditCommand::class,
    ],
)
class ConfigCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: SpolaCli

    override fun call(): Int {
        CommandLine.usage(this, System.out)
        return 0
    }
}

@Command(name = "show", description = ["Print the effective configuration as YAML"])
class ConfigShowCommand : Callable<Int> {
    @ParentCommand
    lateinit var configCommand: ConfigCommand

    override fun call(): Int {
        print(CliConfigSupport.toYaml(buildConfig(configCommand.root)))
        return 0
    }
}

@Command(name = "path", description = ["Print the config file path"])
class ConfigPathCommand : Callable<Int> {
    override fun call(): Int {
        println(CliConfigSupport.configPath)
        return 0
    }
}

@Command(name = "set", description = ["Set a top-level config value in ~/.spola/config.yaml"])
class ConfigSetCommand : Callable<Int> {
    @Parameters(index = "0", description = ["Top-level config key"])
    lateinit var key: String

    @Parameters(index = "1", description = ["Config value"])
    lateinit var value: String

    override fun call(): Int {
        if ('.' in key) {
            System.err.println("${ANSI_RED}Only top-level config keys are supported: $key${ANSI_RESET}")
            return 1
        }

        if (key !in CliConfigSupport.KNOWN_CONFIG_KEYS) {
            System.err.println("${ANSI_RED}Unknown config key: '$key'. Known keys: ${CliConfigSupport.KNOWN_CONFIG_KEYS.joinToString(", ")}${ANSI_RESET}")
            return 1
        }

        return try {
            val expectedType = CliConfigSupport.KEY_TYPES[key]
            val parsed = CliConfigSupport.parseScalar(value)
            // Type-check against expected type for common mismatches
            if (expectedType != null && !isTypeCompatible(expectedType, parsed)) {
                System.err.println("${ANSI_RED}Expected type '${expectedType.simpleName}' for key '$key', but got '${parsed?.let { it::class.simpleName } ?: "null"}'${ANSI_RESET}")
                return 1
            }
            val raw = CliConfigSupport.loadRawConfigMap()
            raw[key] = parsed
            CliConfigSupport.store.saveRaw(raw)
            println("${ANSI_GREEN}Updated $key in ${CliConfigSupport.configPath}${ANSI_RESET}")
            0
        } catch (e: IllegalArgumentException) {
            System.err.println("${ANSI_RED}Invalid config key or value: ${e.message}${ANSI_RESET}")
            1
        } catch (e: Exception) {
            System.err.println("${ANSI_RED}Failed to update config: ${e.message}${ANSI_RESET}")
            1
        }
    }

    private fun isTypeCompatible(expected: Class<*>, value: Any?): Boolean {
        if (value == null) return true
        val actual = value::class.java
        return when (expected) {
            String::class.java -> actual == String::class.java
            Int::class.java -> actual == Int::class.java || actual == Long::class.java
            Long::class.java -> actual == Long::class.java || actual == Int::class.java
            Double::class.java -> actual == Double::class.java || actual == Int::class.java || actual == Long::class.java
            Boolean::class.java -> actual == Boolean::class.java
            else -> true // Enums, non-primitive — let Jackson validate
        }
    }
}

@Command(name = "edit", description = ["Open the config file in \$EDITOR"])
class ConfigEditCommand : Callable<Int> {
    override fun call(): Int {
        val path = CliConfigSupport.configPath
        ensureConfigFileExists(path)

        val editorRaw = System.getenv("EDITOR")
            ?.takeIf { it.isNotBlank() }
            ?: listOf("nano", "vi").firstOrNull(::isExecutableOnPath)
            ?: run {
                System.err.println("${ANSI_RED}No editor found. Set \$EDITOR.${ANSI_RESET}")
                return 1
            }

        return try {
            // Validate: only allow editors that exist on PATH or are absolute paths
            val resolvedEditor = resolveEditor(editorRaw)
            if (resolvedEditor == null) {
                System.err.println("${ANSI_RED}Editor not found on PATH: $editorRaw${ANSI_RESET}")
                return 1
            }
            val parts = resolvedEditor.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val process = ProcessBuilder(parts + path.toString())
                .inheritIO()
                .start()
            process.waitFor()
        } catch (e: Exception) {
            System.err.println("${ANSI_RED}Failed to open editor '$editorRaw': ${e.message}${ANSI_RESET}")
            1
        }
    }

    /**
     * Resolve an editor command. Extracts only the base executable name,
     * discarding any extra arguments — only the executable + config path
     * are passed to ProcessBuilder for security (prevents argument injection).
     */
    private fun resolveEditor(editor: String): String? {
        val cmd = editor.trim().split(Regex("\\s+")).first()
        return if (cmd.contains(File.separator)) {
            if (Files.isExecutable(Path.of(cmd))) cmd else null
        } else {
            if (isExecutableOnPath(cmd)) cmd else null
        }
    }

    private fun ensureConfigFileExists(path: Path) {
        if (Files.exists(path)) return
        CliConfigSupport.store.save(SpolaConfig())
    }

    private fun isExecutableOnPath(name: String): Boolean {
        val pathEntries = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        return pathEntries
            .asSequence()
            .map { Path.of(it, name) }
            .any { Files.isRegularFile(it) && Files.isExecutable(it) }
    }
}

internal object CliConfigSupport {
    val store = SpolaConfigFileStore()
    val configPath: Path = store.configPath

    val KNOWN_CONFIG_KEYS: Set<String> = setOf(
        "model", "provider", "verbosity", "maxTurns", "temperature", "maxTokens",
        "workingDirectory", "personaPath", "memoryDbPath", "schedulerDbPath",
        "kanbanDbPath", "compressionEnabled", "checkpointDbPath", "jvmIndexDbPath",
        "jvmIndexAutoRefresh", "autoCheckpoint", "apiKey", "pairingToken",
        "telegramBotToken", "emailSmtpHost", "emailSmtpPort", "emailUsername",
        "emailPassword", "emailFrom", "ttsProvider", "elevenlabsApiKey",
        "elevenlabsVoiceId", "otelEnabled", "otelEndpoint", "otelServiceName",
        "metricsEnabled", "pluginsEnabled", "pluginsDir", "skillsDir",
        "skillsDbPath", "skillsEnabled", "imageGenDefaultSize", "imageGenOutputDir",
        "agentsDir", "agentsDbPath", "defaultAgentId", "sessionsDbPath",
        "sessionId", "insecure",
        // custom_providers intentionally excluded — requires structured YAML, not scalar
    )

    /** Expected Java types for each known config key (for set-command validation). */
    val KEY_TYPES: Map<String, Class<*>> = mapOf(
        "model" to String::class.java,
        "provider" to String::class.java,
        "verbosity" to String::class.java,
        "maxTurns" to Int::class.java,
        "temperature" to Double::class.java,
        "maxTokens" to Int::class.java,
        "workingDirectory" to String::class.java,
        "personaPath" to String::class.java,
        "memoryDbPath" to String::class.java,
        "schedulerDbPath" to String::class.java,
        "kanbanDbPath" to String::class.java,
        "compressionEnabled" to Boolean::class.java,
        "checkpointDbPath" to String::class.java,
        "jvmIndexDbPath" to String::class.java,
        "jvmIndexAutoRefresh" to Boolean::class.java,
        "autoCheckpoint" to Boolean::class.java,
        "apiKey" to String::class.java,
        "pairingToken" to String::class.java,
        "telegramBotToken" to String::class.java,
        "emailSmtpHost" to String::class.java,
        "emailSmtpPort" to Int::class.java,
        "emailUsername" to String::class.java,
        "emailPassword" to String::class.java,
        "emailFrom" to String::class.java,
        "ttsProvider" to String::class.java,
        "elevenlabsApiKey" to String::class.java,
        "elevenlabsVoiceId" to String::class.java,
        "otelEnabled" to Boolean::class.java,
        "otelEndpoint" to String::class.java,
        "otelServiceName" to String::class.java,
        "metricsEnabled" to Boolean::class.java,
        "pluginsEnabled" to Boolean::class.java,
        "pluginsDir" to String::class.java,
        "skillsDir" to String::class.java,
        "skillsDbPath" to String::class.java,
        "skillsEnabled" to Boolean::class.java,
        "imageGenDefaultSize" to String::class.java,
        "imageGenOutputDir" to String::class.java,
        "agentsDir" to String::class.java,
        "agentsDbPath" to String::class.java,
        "defaultAgentId" to String::class.java,
        "sessionsDbPath" to String::class.java,
        "sessionId" to String::class.java,
        "insecure" to Boolean::class.java,
    )

    fun loadRawConfigMap(): MutableMap<String, Any?> {
        return store.loadRaw()
    }

    fun toYaml(config: SpolaConfig): String {
        return store.toYaml(config)
    }

    fun toConfig(raw: Map<String, Any?>): SpolaConfig {
        return store.fromRaw(raw)
    }

    fun parseScalar(raw: String): Any? {
        return when {
            raw.equals("null", ignoreCase = true) -> null
            raw.equals("true", ignoreCase = true) -> true
            raw.equals("false", ignoreCase = true) -> false
            raw.toIntOrNull() != null -> raw.toInt()
            raw.toLongOrNull() != null -> raw.toLong()
            raw.toDoubleOrNull() != null -> raw.toDouble()
            else -> raw
        }
    }
}
