package dev.spola.skill

import java.nio.file.Path

/**
 * In-memory catalog of installed skills.
 * Built at startup by scanning ~/.golem/skills/ for SKILL.md files.
 */
class SkillCatalog(
    private val skillsDir: Path,
    private val repository: SkillRepository? = null,
) {
    private val _skills = mutableMapOf<String, SkillDefinition>()
    private val _byCategory = mutableMapOf<String, List<SkillDefinition>>()
    private val _skillDirectories = mutableMapOf<String, Path>()

    /** Reload catalog from disk. */
    fun refresh(): RefreshResult {
        _skills.clear()
        _byCategory.clear()
        _skillDirectories.clear()

        val errors = mutableListOf<String>()
        val loadedSkills = mutableListOf<SkillDefinition>()

        SkillLoader.discoverSkillFiles(skillsDir).forEach { file ->
            val skill = SkillLoader.loadFromFile(file, skillsDir)
            if (skill == null) {
                errors += "Failed to load skill from $file"
            } else {
                val normalizedName = skill.name.lowercase()
                _skills[normalizedName] = skill
                _skillDirectories[normalizedName] = file.parent
                loadedSkills += skill
            }
        }

        val grouped = loadedSkills
            .sortedWith(compareBy<SkillDefinition> { it.category }.thenBy { it.name })
            .groupBy { it.category }
            .toSortedMap()

        _byCategory.putAll(grouped)

        return RefreshResult(
            loaded = _skills.size,
            errors = errors,
        )
    }

    /** All skills sorted by category then name. */
    val allSkills: Map<String, List<SkillSummary>>
        get() = _byCategory.mapValues { (_, skills) ->
            skills
                .sortedBy { it.name }
                .map { it.toSummary() }
        }.toSortedMap()

    /** Get a skill by name. Returns null if not found. */
    fun get(name: String): SkillDefinition? {
        val normalized = name.lowercase()
        return repository?.get(normalized)?.toDefinition() ?: _skills[normalized]
    }

    /** Get skills in a category. */
    fun getCategory(category: String): List<SkillSummary> = allSkills[category].orEmpty()

    /** List all categories. */
    val categories: Set<String>
        get() = _byCategory.keys.toSortedSet()

    /** Format catalog as text for system prompt injection. */
    fun formatCatalog(): String {
        val catalog = allSkills
        if (catalog.isEmpty()) {
            return ""
        }

        val lines = mutableListOf(
            "## Available Skills",
            "Call `load_skill(name)` to load a skill's full body for detailed guidance.",
            "Call `load_reference(skill, file)` to load a supporting file.",
            "",
        )
        if (repository != null) {
            lines.add(3, "Call `search_skills(query)` to find relevant skills.")
        }

        catalog.forEach { (category, skills) ->
            val heading = if (category.isBlank()) "uncategorized" else category
            lines += "**$heading:**"
            skills.forEach { skill ->
                lines += "- ${skill.name} — ${skill.description}"
            }
            lines += ""
        }

        return lines.joinToString("\n").trimEnd()
    }

    internal fun getSkillDirectory(name: String): Path? = _skillDirectories[name.lowercase()]

    fun search(query: String, limit: Int = 10): List<SkillSummary> {
        val repo = repository ?: return emptyList()
        return repo.search(query, limit)
            .mapNotNull { result -> repo.get(result.skillName) }
            .distinctBy { it.name }
            .map { it.toSummary() }
    }

    fun reindexFromFiles(): IndexResult {
        val repo = repository ?: return IndexResult(upserted = 0, deleted = 0, errors = emptyList())
        return SkillIndexer(skillsDir, repo).reindex()
    }

    private fun SkillDefinition.toSummary() = SkillSummary(
        name = name,
        description = description,
        category = category,
        tags = tags,
    )

    private fun SkillRow.toDefinition() = SkillDefinition(
        name = name,
        description = description,
        category = category,
        tags = tags,
        tools = tools,
        references = references,
        body = body,
    )

    private fun SkillRow.toSummary() = SkillSummary(
        name = name,
        description = description,
        category = category,
        tags = tags,
    )
}

data class SkillSummary(
    val name: String,
    val description: String,
    val category: String,
    val tags: List<String>,
)

data class RefreshResult(
    val loaded: Int,
    val errors: List<String>,
)
