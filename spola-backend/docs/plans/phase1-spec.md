# Phase 1: In-Memory Catalog + SKILL.md File Format

## Summary

Replace the current flat-YAML skill system with Hermes-compatible SKILL.md files
(Markdown with YAML frontmatter). Three agent-accessible tools: `skills_list`,
`load_skill`, `load_reference`. In-memory catalog at startup.

## Project Root

`/home/gionag/Development/golem/`

## Files to Create/Modify

### 1. SkillDefinition.kt — FULL REWRITE
**Path**: `golem-core/src/main/kotlin/dev/golem/skill/SkillDefinition.kt`

Replace the current data class with:

```kotlin
package dev.spola.skill

/**
 * A skill definition parsed from a SKILL.md file.
 *
 * Skills are stored in ~/.golem/skills/<category>/<name>/SKILL.md
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
    val author: String = "Golem",
    /** Category for grouping (e.g. "devops", "ml"). */
    val category: String = "",
    /** Tags for discoverability. */
    val tags: List<String> = emptyList(),
    /** Tool definitions this skill provides (for Phase 3). */
    val tools: List<SkillToolDef> = emptyList(),
    /** Reference/template/script file names. */
    val references: List<String> = emptyList(),
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
    val type: String = "string",  // string, integer, boolean
    val required: Boolean = false,
    val defaultValue: Any? = null,
)
```

### 2. SkillParser.kt — NEW FILE
**Path**: `golem-core/src/main/kotlin/dev/golem/skill/SkillParser.kt`

Parses SKILL.md files by splitting YAML frontmatter from Markdown body.

```kotlin
package dev.spola.skill

object SkillParser {
    /**
     * Parse a SKILL.md file content into a [SkillDefinition].
     * Frontmatter is between first --- and closing ---\n.
     * Everything after closing --- is the body.
     */
    fun parse(content: String, context: ParseContext): SkillDefinition?
    
    data class ParseContext(
        val filePath: java.nio.file.Path,
        val dirName: String,     // parent directory name = skill name
        val category: String?,   // grandparent directory name = category, or null for flat
    )
}
```

**Parsing rules:**
1. Content must start with `---\n`
2. Find closing `\n---\n` or `\n---` at end of frontmatter
3. Parse YAML between as frontmatter
4. Everything after the closing `---` is the Markdown body
5. Use Jackson YAML for frontmatter parsing (already in classpath)
6. If frontmatter has no `name`, use the parent directory name
7. If frontmatter has a `name`, use that
8. Category is derived from grandparent directory name, or from frontmatter `category` field
9. `references` in frontmatter are file paths relative to skill directory
10. Handle errors gracefully — log warning, return null

**SKILL.md format:**
```markdown
---
name: docker-compose
description: Build and manage Docker Compose environments
category: devops
version: 1.0.0
author: Golem
tags: [docker, devops, containers]
references:
  - references/compose-spec.md
  - templates/docker-compose.yml
requiredEnvironmentVariables:
  - DOCKER_HOST
---
# Docker Compose Skill

## Overview
Guidance for working with Docker Compose in development...
```

### 3. SkillCatalog.kt — NEW FILE
**Path**: `golem-core/src/main/kotlin/dev/golem/skill/SkillCatalog.kt`

In-memory index of all available skills, built at startup.

```kotlin
package dev.spola.skill

/**
 * In-memory catalog of installed skills.
 * Built at startup by scanning ~/.golem/skills/ for SKILL.md files.
 */
class SkillCatalog(
    private val skillsDir: java.nio.file.Path,
) {
    private val _skills = mutableMapOf<String, SkillDefinition>()
    private val _byCategory = mutableMapOf<String, List<SkillDefinition>>()
    
    /** Reload catalog from disk. */
    fun refresh(): RefreshResult
    
    /** All skills sorted by category then name. */
    val allSkills: Map<String, List<SkillSummary>>
    
    /** Get a skill by name. Returns null if not found. */
    fun get(name: String): SkillDefinition?
    
    /** Get skills in a category. */
    fun getCategory(category: String): List<SkillSummary>
    
    /** List all categories. */
    val categories: Set<String>
    
    /** Format catalog as text for system prompt injection. */
    fun formatCatalog(): String  // e.g. "## Available Skills\ndevops: docker-compose — Build and manage...\nml: llama-cpp — ..."
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
```

**Catalog format for system prompt injection:**
```
## Available Skills
Call `load_skill(name)` to load a skill's full body for detailed guidance.
Call `load_reference(skill, file)` to load a supporting file.
Call `search_skills(query)` to find relevant skills (when search tool is available).

**devops:**
- docker-compose — Build and manage Docker Compose environments
- kubernetes — Deploy and manage Kubernetes resources

**ml:**
- llama-cpp — Build, serve, and benchmark llama.cpp GGUF models
```

### 4. SkillLoader.kt — FULL REWRITE
**Path**: `golem-core/src/main/kotlin/dev/golem/skill/SkillLoader.kt`

Replace file scanning with recursive directory walk for SKILL.md files.

```kotlin
package dev.spola.skill

object SkillLoader {
    val defaultSkillsDir: Path = Path.of(home, ".golem", "skills")
    
    /** Walk directory tree looking for SKILL.md files. Returns parsed definitions. */
    fun loadFromDirectory(directory: Path = defaultSkillsDir): List<SkillDefinition>
    
    /** Parse a single SKILL.md file. Returns null on failure. */
    fun loadFromFile(filePath: Path): SkillDefinition?
}
```

**Scanning rules:**
- Walk the directory recursively
- Find all files named `SKILL.md` (case-sensitive)
- Category = grandparent directory name (e.g., `skills/devops/docker-compose/SKILL.md` → category="devops")
- If flat (e.g., `skills/docker-compose/SKILL.md`), category from frontmatter or ""
- Parse each SKILL.md with SkillParser
- Skip files in .hidden directories
- Collect errors but don't halt on failures

### 5. SkillTools.kt — FULL REWRITE
**Path**: `golem-core/src/main/kotlin/dev/golem/skill/SkillTools.kt`

Three tools: `skills_list`, `load_skill`, `load_reference`.

**`skills_list`:**
- Returns: categorized catalog of all installed skills
- Optional `category` filter parameter
- No arguments = full catalog

**`load_skill`:**
- Parameters: `name` (required, string)
- Returns: full SKILL.md body text
- On success: returns "## Skill: name\n{body}\n\n**References:** file1, file2, ...\n**Tools available:** tool1, tool2, ...\nCall `load_reference(name, file)` to view a reference file."

**`load_reference`:**
- Parameters: `skill` (required, string), `file` (required, string)
- Returns: file contents
- File must be within the skill directory (prevent path traversal)
- Only allows files from references/, templates/, scripts/ subdirectories

**Tool registration method:**
```kotlin
suspend fun register(
    registry: ToolRegistry,
    skillsDir: Path = SkillLoader.defaultSkillsDir,
    config: GolemConfig = GolemConfig(),
)
```

Remove the `skill_run` tool entirely (moved to sub-agent approach in Phase 3).

### 6. AgentFactory.kt — MODIFY
**Path**: `golem-core/src/main/kotlin/dev/golem/factory/AgentFactory.kt`

After the memory injection block (around line 105-115), inject the skill catalog:

```kotlin
// Inject skill catalog into system prompt
val skillsDir = Path.of(config.skillsDir)
val catalog = SkillCatalog(skillsDir)
catalog.refresh()
val skillBlock = catalog.formatCatalog()
persona = if (skillBlock.isNotBlank()) {
    "$persona\n\n$skillBlock"
} else {
    persona
}
```

Add import for `java.nio.file.Path` and `dev.spola.skill.SkillCatalog`.

### 7. GolemConfig.kt — MODIFY
**Path**: `golem-core/src/main/kotlin/dev/golem/GolemConfig.kt`

Add these fields:
```kotlin
val skillsDir: String = Path.of(
    System.getProperty("user.home"), ".golem", "skills"
).toString(),
val skillsEnabled: Boolean = true,
```

### 8. ToolRegistryFactory.kt — MODIFY
**Path**: `golem-core/src/main/kotlin/dev/golem/factory/ToolRegistryFactory.kt`

Update the `SkillTools.register` call in all four builder methods to pass `config.skillsDir`:

```kotlin
SkillTools.register(this, skillsDir = Path.of(config.skillsDir), config = config)
```

### 9. SkillCommand.kt — MODIFY (CLI)
**Path**: `golem-cli/src/main/kotlin/dev/golem/cli/SkillCommand.kt`

Update `SkillListCommand` to use the new `SkillLoader.loadFromDirectory` (still returns `List<SkillDefinition>` — should work).
Update `SkillInstallCommand` to copy files into `skills/<category>/<name>/SKILL.md` structure.
Remove `SkillRunCommand` (no longer needed — agents run skills via tools).

### 10. Tests — UPDATE EXISTING + ADD NEW

**SkillLoaderTest.kt** — rewrite all tests for SKILL.md format:
- `parses SKILL.md with frontmatter and body`
- `uses directory name as fallback name`
- `handles missing closing frontmatter`
- `categorized layout: parses category from directory structure`
- `empty directory returns empty list`
- `missing directory returns empty list`
- `balanced: body preserves all markdown formatting`

**SkillToolsTest.kt** — rewrite for new tools:
- `skills_list returns catalog with categories`
- `skills_list with category filter`
- `load_skill returns full body`
- `load_skill for nonexistent skill`
- `load_reference returns reference file`
- `load_reference with path traversal rejected`

**SkillParserTest.kt** — NEW file with parser unit tests:
- `parses standard SKILL.md`
- `frontmatter-only (no body)`
- `body with markdown formatting (lists, code blocks, tables)`
- `empty frontmatter`
- `invalid YAML frontmatter returns null`
- `missing name uses directory fallback`
- `category from grandparent directory structure`
- `category from frontmatter overrides directory`

## Dependencies

No new dependencies. All already in classpath:
- Jackson YAML (`jackson-dataformat-yaml`) — for frontmatter parsing
- Jackson Kotlin module — for data class mapping
- JUnit 5 + AssertJ — for tests

## Build Command

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.7-tem
cd /home/gionag/Development/golem
./gradlew build 2>&1 | tail -30
```

## What NOT to Change

- Do NOT modify existing Persona tools or PersonaStore
- Do NOT modify GolemAgent.kt ReAct loop
- Do NOT add SQLite (that's Phase 2)
- Do NOT modify any existing test for non-skill features
- Keep backward compatibility: old YAML-based skills still load if found (use old path as fallback)
