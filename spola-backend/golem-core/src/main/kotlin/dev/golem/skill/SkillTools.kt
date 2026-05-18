package dev.spola.skill

import dev.spola.GolemConfig
import dev.spola.Tool
import dev.spola.ToolParameter
import dev.spola.ToolParameterType
import dev.spola.ToolRegistry
import dev.spola.ToolResult
import java.nio.file.Files
import java.nio.file.Path

/**
 * Registers LLM-accessible tools for discovering and loading skills.
 */
object SkillTools {

    suspend fun register(
        registry: ToolRegistry,
        skillsDir: Path = SkillLoader.defaultSkillsDir,
        config: GolemConfig = GolemConfig(),
        repository: SkillRepository? = null,
        toolRegistry: ToolRegistry? = null,
    ) {
        if (!config.skillsEnabled) {
            return
        }

        val repositoryFactory = when {
            repository != null -> null
            config.skillsDbPath.isBlank() -> null
            else -> { { SkillRepository(config.skillsDbPath) } }
        }

        val effectiveRepo = repository ?: repositoryFactory?.invoke()?.also { repo ->
            SkillIndexer(skillsDir, repo).reindex()
        }
        if (repository != null) {
            SkillIndexer(skillsDir, repository).reindex()
        }

        val catalog = SkillCatalog(skillsDir, effectiveRepo)
        catalog.refresh()

        val skillsList = skillsListTool(catalog, skillsDir, effectiveRepo)
        registry.register(skillsList)
        registry.register(skillsList.copy(name = "skill_list"))
        registry.register(loadSkillTool(catalog, effectiveRepo, toolRegistry))
        registry.register(unloadSkillTool(toolRegistry))
        registry.register(loadReferenceTool(catalog, skillsDir, effectiveRepo))
        registry.register(searchSkillsTool(catalog, effectiveRepo))
    }

    private fun skillsListTool(
        catalog: SkillCatalog,
        skillsDir: Path,
        repository: SkillRepository?,
    ) = Tool(
        name = "skills_list",
        description = "List installed skills from the in-memory skill catalog. Optionally filter by category.",
        parameters = listOf(
            ToolParameter(
                name = "category",
                description = "Optional category to filter skills by",
                type = ToolParameterType.STRING,
                required = false,
            ),
        ),
        execute = { args ->
            if (repository == null) {
                catalog.refresh()
            }

            val category = (args["category"] as? String)?.trim().orEmpty()
            if (category.isBlank()) {
                val formatted = catalog.formatCatalog()
                if (formatted.isBlank()) {
                    ToolResult.ok("No skills installed in $skillsDir")
                } else {
                    ToolResult.ok(formatted)
                }
            } else {
                val skills = catalog.getCategory(category)
                if (skills.isEmpty()) {
                    ToolResult.ok("No skills found in category '$category'.")
                } else {
                    val lines = mutableListOf("## Available Skills", "", "**$category:**")
                    skills.forEach { skill ->
                        lines += "- ${skill.name} — ${skill.description}"
                    }
                    ToolResult.ok(lines.joinToString("\n"))
                }
            }
        },
    )

    private fun loadSkillTool(
        catalog: SkillCatalog,
        repository: SkillRepository?,
        toolRegistry: ToolRegistry?,
    ) = Tool(
        name = "load_skill",
        description = "Load the full Markdown body of an installed skill by name.",
        parameters = listOf(
            ToolParameter(
                name = "name",
                description = "Skill name",
                type = ToolParameterType.STRING,
            ),
        ),
        execute = { args ->
            val name = args["name"] as? String ?: return@Tool ToolResult.fail("name is required")
            if (repository == null) {
                catalog.refresh()
            }

            val skill = catalog.get(name)
                ?: return@Tool ToolResult.fail("Skill '$name' not found.")
            repository?.recordUsage(skill.name)

            val references = if (skill.references.isNotEmpty()) {
                skill.references.joinToString(", ")
            } else {
                "None"
            }
            val toolsAvailable = if (skill.tools.isNotEmpty()) {
                skill.tools.joinToString(", ") { it.name }
            } else {
                "None"
            }
            val registeredTools = if (toolRegistry != null && skill.tools.isNotEmpty()) {
                toolRegistry.activateSkill(skill.name, skill.tools, skill.body)
            } else {
                emptyList()
            }
            val workflows = if (skill.workflows.isNotEmpty()) {
                skill.workflows.joinToString(", ")
            } else {
                "None"
            }

            val bodyBlock = skill.body.ifBlank { "(no body)" }
            ToolResult.ok(
                buildString {
                    append("## Skill: ${skill.name}\n")
                    append(bodyBlock)
                    append("\n\n**References:** $references\n")
                    append("**Tools available:** $toolsAvailable\n")
                    if (registeredTools.isNotEmpty()) {
                        append("**Registered tools:** ${registeredTools.joinToString(", ")}\n")
                    }
                    append("**Related workflows:** $workflows\n")
                    append("Call `load_reference(${skill.name}, file)` to view a reference file. ")
                    if (skill.workflows.isNotEmpty()) {
                        append("Call `workflow_run(${skill.workflows.first()}, ...)` to run the related workflow.")
                    }
                },
            )
        },
    )

    private fun unloadSkillTool(
        toolRegistry: ToolRegistry?,
    ) = Tool(
        name = "unload_skill",
        description = "Remove a skill's tools from the agent's tool registry.",
        parameters = listOf(
            ToolParameter(
                name = "name",
                description = "Skill name",
                type = ToolParameterType.STRING,
            ),
        ),
        execute = { args ->
            val name = args["name"] as? String ?: return@Tool ToolResult.fail("name is required")
            if (toolRegistry == null) {
                return@Tool ToolResult.ok("Tool registry is not available — skill tools cannot be managed.")
            }
            val deactivated = toolRegistry.deactivateSkill(name)
            if (deactivated) {
                ToolResult.ok("Unloaded active tools for skill '$name'.")
            } else {
                ToolResult.ok("Skill '$name' has no active tools.")
            }
        },
    )

    private fun loadReferenceTool(
        catalog: SkillCatalog,
        skillsDir: Path,
        repository: SkillRepository?,
    ) = Tool(
        name = "load_reference",
        description = "Load a reference, template, or script file for an installed skill.",
        parameters = listOf(
            ToolParameter(
                name = "skill",
                description = "Skill name",
                type = ToolParameterType.STRING,
            ),
            ToolParameter(
                name = "file",
                description = "Relative file path inside references/, templates/, or scripts/",
                type = ToolParameterType.STRING,
            ),
        ),
        execute = { args ->
            val skillName = args["skill"] as? String ?: return@Tool ToolResult.fail("skill is required")
            val file = args["file"] as? String ?: return@Tool ToolResult.fail("file is required")
            if (repository == null) {
                catalog.refresh()
            }

            val skill = catalog.get(skillName)
                ?: return@Tool ToolResult.fail("Skill '$skillName' not found.")
            val skillDir = catalog.getSkillDirectory(skill.name)
                ?: return@Tool ToolResult.fail("Skill directory for '$skillName' not found.")

            val resolved = skillDir.resolve(file).normalize()
            if (!resolved.startsWith(skillDir)) {
                return@Tool ToolResult.fail("Path traversal is not allowed.")
            }

            val relative = skillDir.relativize(resolved)
            val allowedRoots = setOf("references", "templates", "scripts")
            if (relative.nameCount < 2 || relative.getName(0).toString() !in allowedRoots) {
                return@Tool ToolResult.fail("Only files in references/, templates/, or scripts/ may be loaded.")
            }

            if (!Files.isRegularFile(resolved)) {
                return@Tool ToolResult.fail("Reference file not found: $file")
            }

            val fileCheck = repository?.let { repo ->
                if (repo.hasFile(skill.name, file)) {
                    null
                } else {
                    val available = repo.listFiles(skill.name).joinToString(", ") { it.fileName }
                    val suffix = if (available.isBlank()) "" else " Available files: $available"
                    "Reference file not indexed: $file.$suffix"
                }
            }
            if (fileCheck != null) {
                return@Tool ToolResult.fail(fileCheck)
            }

            ToolResult.ok(Files.readString(resolved))
        },
    )

    private fun searchSkillsTool(
        catalog: SkillCatalog,
        repository: SkillRepository?,
    ) = Tool(
        name = "search_skills",
        description = "Search installed skills by keyword. Returns matching sections with snippets.",
        parameters = listOf(
            ToolParameter(
                name = "query",
                description = "Keyword query for skill search",
                type = ToolParameterType.STRING,
            ),
            ToolParameter(
                name = "limit",
                description = "Maximum number of results to return",
                type = ToolParameterType.INTEGER,
                required = false,
                defaultValue = 10,
            ),
        ),
        execute = { args ->
            val query = (args["query"] as? String)?.trim().orEmpty()
            if (query.isBlank()) {
                return@Tool ToolResult.fail("query is required")
            }

            val limit = (args["limit"] as? Number)?.toInt() ?: 10
            val results = repository?.search(query, limit) ?: run {
                catalog.refresh()
                catalog.allSkills.values
                    .flatten()
                    .filter {
                        it.name.contains(query, ignoreCase = true) ||
                            it.description.contains(query, ignoreCase = true) ||
                            catalog.get(it.name)?.body?.contains(query, ignoreCase = true) == true
                    }
                    .take(limit)
                    .map {
                        SearchResult(
                            skillName = it.name,
                            sectionTitle = "Overview",
                            snippet = catalog.get(it.name)?.body?.lineSequence()?.firstOrNull().orEmpty(),
                        )
                    }
            }

            if (results.isEmpty()) {
                ToolResult.ok("No matching skills found for '$query'.")
            } else {
                ToolResult.ok(
                    results.joinToString("\n\n") { result ->
                        "## ${result.skillName} — ${result.sectionTitle}\n${result.snippet}"
                    },
                )
            }
        },
    )
}
