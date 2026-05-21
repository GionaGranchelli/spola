package dev.spola.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.spola.SpolaConfig
import java.nio.file.Files
import java.nio.file.Path

class SpolaConfigFileStore(
    val configPath: Path = Path.of(System.getProperty("user.home"), ".spola", "config.yaml"),
) {
    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(): SpolaConfig {
        if (!Files.exists(configPath)) {
            return SpolaConfig()
        }
        return fromRaw(loadRaw())
    }

    fun save(config: SpolaConfig) {
        Files.createDirectories(configPath.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config)
    }

    fun loadRaw(): MutableMap<String, Any?> {
        if (!Files.exists(configPath)) {
            return linkedMapOf()
        }
        val loaded = mapper.readValue(configPath.toFile(), MutableMap::class.java)
        @Suppress("UNCHECKED_CAST")
        return (loaded as? MutableMap<String, Any?>) ?: linkedMapOf()
    }

    fun saveRaw(raw: Map<String, Any?>) {
        Files.createDirectories(configPath.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), raw)
    }

    fun toYaml(config: SpolaConfig): String {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config)
    }

    fun fromRaw(raw: Map<String, Any?>): SpolaConfig {
        return mapper.convertValue(normalizeLegacyConfig(raw), SpolaConfig::class.java)
    }

    private fun normalizeLegacyConfig(raw: Map<String, Any?>): Map<String, Any?> {
        val normalized = raw.toMutableMap()

        normalized.nested("database").putLegacyAll(
            raw,
            "dbPath" to "dbPath",
            "memoryDbPath" to "memoryDbPath",
            "checkpointDbPath" to "checkpointDbPath",
            "schedulerDbPath" to "schedulerDbPath",
            "kanbanDbPath" to "kanbanDbPath",
            "workflowDbPath" to "workflowsDbPath",
            "workflowsDbPath" to "workflowsDbPath",
            "jvmIndexDbPath" to "jvmIndexDbPath",
            "sessionsDbPath" to "sessionsDbPath",
            "agentsDbPath" to "agentsDbPath",
            "skillsDbPath" to "skillsDbPath",
        )
        normalized.nested("delivery").putLegacyAll(
            raw,
            "emailEnabled" to "emailEnabled",
            "emailSmtpHost" to "smtpHost",
            "emailSmtpPort" to "smtpPort",
            "emailUsername" to "smtpUser",
            "emailPassword" to "smtpPass",
            "emailFrom" to "fromEmail",
            "telegramBotToken" to "telegramToken",
            "telegramChatId" to "telegramChatId",
        )
        normalized.nested("provider").putLegacyAll(
            raw,
            "apiKey" to "apiKey",
            "provider" to "defaultProvider",
            "model" to "defaultModel",
            "custom_providers" to "custom_providers",
            "customProviders" to "custom_providers",
        )
        normalized.nested("security").putLegacyAll(
            raw,
            "apiKey" to "apiKey",
            "insecure" to "insecure",
            "allowedDirs" to "allowedDirs",
            "unsafe" to "unsafe",
            "sessionTimeoutMinutes" to "sessionTimeoutMinutes",
        )
        normalized.nested("server").putLegacyAll(
            raw,
            "host" to "host",
            "port" to "port",
            "tlsCertPath" to "tlsCertPath",
            "tlsKeyPath" to "tlsKeyPath",
            "mcpPort" to "mcpPort",
            "apiHost" to "apiHost",
        )
        normalized.nested("agent").putLegacyAll(
            raw,
            "sessionId" to "sessionId",
            "personaPath" to "personaPath",
            "maxTurns" to "maxTurns",
            "memoryEnabled" to "memoryEnabled",
        )
        normalized.nested("metrics").putLegacyAll(
            raw,
            "metricsEnabled" to "metricsEnabled",
            "otelEnabled" to "otelEnabled",
            "otelEndpoint" to "otelEndpoint",
        )
        normalized.nested("tts").putLegacyAll(
            raw,
            "ttsEnabled" to "ttsEnabled",
            "ttsProvider" to "ttsProvider",
            "elevenlabsApiKey" to "elevenlabsApiKey",
            "elevenlabsVoiceId" to "elevenlabsVoiceId",
        )

        return normalized
    }

    private fun MutableMap<String, Any?>.nested(key: String): MutableMap<String, Any?> {
        val existing = this[key]
        val nested = if (existing is Map<*, *>) {
            existing.entries.associate { (k, v) -> k.toString() to v }.toMutableMap()
        } else {
            linkedMapOf()
        }
        this[key] = nested
        return nested
    }

    private fun MutableMap<String, Any?>.putLegacyAll(raw: Map<String, Any?>, vararg aliases: Pair<String, String>) {
        for ((legacyKey, nestedKey) in aliases) {
            if (!containsKey(nestedKey) && raw.containsKey(legacyKey)) {
                this[nestedKey] = raw[legacyKey]
            }
        }
    }
}
