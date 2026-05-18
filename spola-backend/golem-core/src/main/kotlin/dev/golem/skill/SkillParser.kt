package dev.spola.skill

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Path

object SkillParser {
    private val logger = LoggerFactory.getLogger(SkillParser::class.java)

    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())

    /**
     * Parse a SKILL.md file content into a [SkillDefinition].
     * Frontmatter is between first --- and closing ---\n.
     * Everything after closing --- is the body.
     */
    fun parse(content: String, context: ParseContext): SkillDefinition? {
        val normalizedContent = content.replace("\r\n", "\n")
        if (!normalizedContent.startsWith("---\n")) {
            logger.warn("Failed to parse skill {}: missing opening frontmatter delimiter", context.filePath)
            return null
        }

        val frontmatterEnd = findFrontmatterEnd(normalizedContent)
        if (frontmatterEnd == null) {
            logger.warn("Failed to parse skill {}: missing closing frontmatter delimiter", context.filePath)
            return null
        }

        return try {
            val frontmatter = normalizedContent.substring(4, frontmatterEnd.delimiterIndex)
            val body = normalizedContent.substring(frontmatterEnd.bodyStartIndex)
            val raw = parseFrontmatter(frontmatter)

            val name = raw["name"]?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: context.dirName
            val category = raw["category"]?.toString()?.trim().takeUnless { it.isNullOrBlank() }
                ?: context.category.orEmpty()

            SkillDefinition(
                name = name,
                description = raw["description"]?.toString()?.trim().orEmpty(),
                version = raw["version"]?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "1.0.0",
                license = raw["license"]?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "MIT",
                author = raw["author"]?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "Golem",
                category = category,
                tags = stringList(raw["tags"]),
                tools = toolList(raw["tools"]),
                references = stringList(raw["references"]),
                workflows = stringList(raw["workflows"]),
                body = body,
                requiredEnvironmentVariables = stringList(raw["requiredEnvironmentVariables"]),
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse skill {}: {}", context.filePath, e.message)
            null
        }
    }

    data class ParseContext(
        val filePath: Path,
        val dirName: String,
        val category: String?,
    )

    private fun parseFrontmatter(frontmatter: String): Map<String, Any?> {
        if (frontmatter.isBlank()) {
            return emptyMap()
        }
        return mapper.readValue(frontmatter, object : TypeReference<Map<String, Any?>>() {})
    }

    private fun stringList(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is List<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        else -> listOf(value.toString().trim()).filter { it.isNotEmpty() }
    }

    private fun toolList(value: Any?): List<SkillToolDef> {
        if (value !is List<*>) {
            return emptyList()
        }
        return value.mapNotNull { entry ->
            try {
                mapper.convertValue(entry, SkillToolDef::class.java)
            } catch (e: IllegalArgumentException) {
                logger.warn("Ignoring invalid tool entry in skill frontmatter: {}", e.message)
                null
            }
        }
    }

    private fun findFrontmatterEnd(content: String): FrontmatterEnd? {
        var searchIndex = 4
        while (searchIndex < content.length) {
            val delimiterIndex = content.indexOf("\n---", startIndex = searchIndex)
            if (delimiterIndex < 0) {
                return null
            }

            val afterDelimiter = delimiterIndex + 4
            if (afterDelimiter == content.length) {
                return FrontmatterEnd(delimiterIndex = delimiterIndex, bodyStartIndex = afterDelimiter)
            }

            if (content[afterDelimiter] == '\n') {
                return FrontmatterEnd(delimiterIndex = delimiterIndex, bodyStartIndex = afterDelimiter + 1)
            }

            searchIndex = afterDelimiter
        }
        return null
    }

    private data class FrontmatterEnd(
        val delimiterIndex: Int,
        val bodyStartIndex: Int,
    )
}
