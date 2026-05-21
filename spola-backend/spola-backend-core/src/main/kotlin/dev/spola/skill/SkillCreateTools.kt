package dev.spola.skill

import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Registers LLM-accessible tools for creating skills.
 */
object SkillCreateTools {

    private val logger = LoggerFactory.getLogger(SkillCreateTools::class.java)

    suspend fun register(
        registry: ToolRegistry,
        skillsDir: Path = SkillLoader.defaultSkillsDir,
        catalog: SkillCatalog,
        repository: SkillRepository? = null,
    ) {
        registry.register(skillCreateTool(skillsDir, catalog, repository))
    }

    private fun skillCreateTool(
        skillsDir: Path,
        catalog: SkillCatalog,
        repository: SkillRepository?,
    ) = Tool(
        name = "skill_create",
        description = "Create a new skill file (SKILL.md) in the skills directory. Skills are reusable knowledge bundles that the agent can load. Provide a name, category (or use default), description, and the full Markdown content including YAML frontmatter. Re-indexes the catalog after creation.",
        parameters = listOf(
            ToolParameter(
                name = "name",
                description = "Unique skill name (lowercase, hyphens allowed, max 64 chars). Used as the directory name.",
                type = ToolParameterType.STRING,
            ),
            ToolParameter(
                name = "content",
                description = "Full Markdown content of the SKILL.md file. Must include YAML frontmatter with at minimum: name, description. Optional: category, version, tags, related_skills.",
                type = ToolParameterType.STRING,
            ),
            ToolParameter(
                name = "category",
                description = "Category for organizing the skill (e.g., 'devops', 'data-science', 'mlops'). Default: 'custom'.",
                type = ToolParameterType.STRING,
                required = false,
            ),
        ),
        execute = { args ->
            val name = args["name"] as? String ?: return@Tool ToolResult.fail("name is required")
            val content = args["content"] as? String ?: return@Tool ToolResult.fail("content is required")
            val category = (args["category"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: "custom"

            if (!name.matches(Regex("^[a-z][a-z0-9_-]{1,63}$"))) {
                return@Tool ToolResult.fail("Invalid skill name '$name'. Use 2-64 lowercase letters, digits, hyphens, underscores.")
            }

            val skillDir = skillsDir.resolve(category).resolve(name)
            if (Files.exists(skillDir)) {
                return@Tool ToolResult.fail("Skill '$name' already exists in category '$category' at $skillDir.")
            }

            try {
                // Write SKILL.md
                Files.createDirectories(skillDir)
                val skillFile = skillDir.resolve("SKILL.md")
                Files.writeString(skillFile, content)

                // Re-index catalog
                if (repository != null) {
                    SkillIndexer(skillsDir, repository).reindex()
                }
                catalog.refresh()

                logger.info("Created skill '{}' in category '{}' at {}", name, category, skillFile)
                ToolResult.ok("Skill '$name' created in category '$category' at $skillFile")
            } catch (e: Exception) {
                // Cleanup on failure
                try {
                    skillDir.toFile().deleteRecursively()
                } catch (_: Exception) {}
                ToolResult.fail("Failed to create skill '$name': ${e.message}")
            }
        },
    )
}
