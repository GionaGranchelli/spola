package dev.spola.skill

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

object SkillLoader {
    private val logger = LoggerFactory.getLogger(SkillLoader::class.java)

    private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())

    val defaultSkillsDir: Path = Path.of(System.getProperty("user.home"), ".spola", "skills")

    /** Walk directory tree looking for SKILL.md files. Returns parsed definitions. */
    fun loadFromDirectory(directory: Path = defaultSkillsDir): List<SkillDefinition> {
        if (!Files.isDirectory(directory)) {
            return emptyList()
        }

        return discoverSkillFiles(directory)
            .mapNotNull { loadFromFile(it, directory) }
            .sortedWith(compareBy<SkillDefinition> { it.category }.thenBy { it.name })
    }

    /** Parse a single SKILL.md file. Returns null on failure. */
    fun loadFromFile(filePath: Path): SkillDefinition? {
        return loadFromFile(filePath, null)
    }

    internal fun loadFromFile(filePath: Path, root: Path?): SkillDefinition? {
        return try {
            when {
                filePath.fileName.toString() == "SKILL.md" -> {
                    val context = SkillParser.ParseContext(
                        filePath = filePath,
                        dirName = filePath.parent?.fileName?.toString().orEmpty(),
                        category = deriveCategory(filePath, root),
                    )
                    SkillParser.parse(Files.readString(filePath), context)
                }
                filePath.toString().endsWith(".yaml") || filePath.toString().endsWith(".yml") -> {
                    loadLegacyYamlSkill(filePath)
                }
                else -> null
            }
        } catch (e: Exception) {
            logger.warn("Failed to load skill {}: {}", filePath, e.message)
            null
        }
    }

    private fun deriveCategory(filePath: Path, root: Path?): String? {
        val effectiveRoot = root ?: filePath.parent?.parent ?: return null
        val skillDir = filePath.parent ?: return null
        val relativeParent = try {
            effectiveRoot.relativize(skillDir)
        } catch (_: IllegalArgumentException) {
            return filePath.parent?.parent?.fileName?.toString()
        }

        return if (relativeParent.nameCount >= 2) {
            relativeParent.getName(0).toString()
        } else {
            null
        }
    }

    internal fun discoverSkillFiles(directory: Path = defaultSkillsDir): List<Path> {
        if (!Files.isDirectory(directory)) {
            return emptyList()
        }

        return Files.walk(directory).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { !isInHiddenDirectory(directory, it) }
                .filter { it.fileName.toString() == "SKILL.md" || it.toString().endsWith(".yaml") || it.toString().endsWith(".yml") }
                .sorted()
                .toList()
        }
    }

    private fun isInHiddenDirectory(root: Path, file: Path): Boolean {
        val relative = try {
            root.relativize(file)
        } catch (_: IllegalArgumentException) {
            return false
        }

        val parent = relative.parent ?: return false
        return parent.any { segment -> segment.fileName.toString().startsWith(".") }
    }

    private fun loadLegacyYamlSkill(filePath: Path): SkillDefinition? {
        return try {
            val raw = yamlMapper.readValue(
                Files.readString(filePath),
                object : TypeReference<Map<String, Any?>>() {},
            )

            val stem = filePath.fileName.toString()
                .removeSuffix(".yaml")
                .removeSuffix(".yml")

            val name = raw["name"]?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: stem
            val body = raw["prompt"]?.toString().orEmpty()
            val category = raw["category"]?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: ""

            SkillDefinition(
                name = name,
                description = raw["description"]?.toString()?.trim().orEmpty(),
                version = raw["version"]?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "1.0.0",
                category = category,
                tags = when (val tags = raw["tags"]) {
                    is List<*> -> tags.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                    null -> emptyList()
                    else -> listOf(tags.toString())
                },
                workflows = when (val workflows = raw["workflows"]) {
                    is List<*> -> workflows.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                    null -> emptyList()
                    else -> listOf(workflows.toString())
                },
                body = body,
            )
        } catch (e: Exception) {
            logger.warn("Failed to load legacy skill {}: {}", filePath, e.message)
            null
        }
    }
}
