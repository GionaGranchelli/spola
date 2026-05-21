package dev.spola.skill

/**
 * A skill definition parsed from a SKILL.md file.
 *
 * Skills are stored in ~/.spola/skills/<category>/<name>/SKILL.md
 * with YAML frontmatter + Markdown body.
 */
data class SkillDefinition(
    /** Unique skill name (lowercase, hyphens). */
    val name: String,
    /** Human-readable description, ≤1024 chars. */
    val description: String,
    /** Optional version string (e.g. "1.0.0"). */
    val version: String = "1.0.0",
    /** License. */
    val license: String = "MIT",
    /** Author. */
    val author: String = "Spola",
    /** Category for grouping (e.g. "devops", "ml"). */
    val category: String = "",
    /** Tags for discoverability. */
    val tags: List<String> = emptyList(),
    /** Tool definitions this skill provides (for Phase 3). */
    val tools: List<SkillToolDef> = emptyList(),
    /** Reference/template/script file names. */
    val references: List<String> = emptyList(),
    /** Associated workflow names that this skill can trigger or relates to. */
    val workflows: List<String> = emptyList(),
    /** The Markdown body (after frontmatter). */
    val body: String = "",
    /** Required environment variables. */
    val requiredEnvironmentVariables: List<String> = emptyList(),
)

/**
 * A tool definition that a skill can register with the agent.
 * Used in Phase 3 for dynamic tool registration.
 */
data class SkillToolDef(
    val name: String,
    val description: String,
    val parameters: List<SkillToolParam> = emptyList(),
)

data class SkillToolParam(
    val name: String,
    val description: String,
    val type: String = "string",
    val required: Boolean = false,
    val defaultValue: Any? = null,
)
