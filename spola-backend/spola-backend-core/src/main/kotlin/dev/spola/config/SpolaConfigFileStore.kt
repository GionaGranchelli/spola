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
        return mapper.readValue(configPath.toFile())
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
        return mapper.convertValue(raw, SpolaConfig::class.java)
    }
}
