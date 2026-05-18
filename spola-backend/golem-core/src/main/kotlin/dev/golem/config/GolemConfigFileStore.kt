package dev.spola.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.spola.GolemConfig
import java.nio.file.Files
import java.nio.file.Path

class GolemConfigFileStore(
    val configPath: Path = Path.of(System.getProperty("user.home"), ".golem", "config.yaml"),
) {
    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(): GolemConfig {
        if (!Files.exists(configPath)) {
            return GolemConfig()
        }
        return mapper.readValue(configPath.toFile())
    }

    fun save(config: GolemConfig) {
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

    fun toYaml(config: GolemConfig): String {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config)
    }

    fun fromRaw(raw: Map<String, Any?>): GolemConfig {
        return mapper.convertValue(raw, GolemConfig::class.java)
    }
}
