# Phase 2: SQLite + FTS5 + Search + Smart Injection

## Summary

Add SQLite-backed persistence to the skill system with FTS5 full-text search.
Add `search_skills` tool. Smart pre-injection of relevant skill sections into
the system prompt. Cache SkillCatalog to avoid re-parsing on every tool call.

## Files to Create

### 1. SkillRepository.kt — NEW
**Path**: `golem-core/src/main/kotlin/dev/golem/skill/SkillRepository.kt`

SQLite-backed repository using Exposed (same pattern as SqliteMemoryStore).
Follow the existing patterns in `golem-core/src/main/kotlin/dev/golem/memory/SqliteMemoryStore.kt`.

```kotlin
package dev.spola.skill

class SkillRepository(dbPath: String) : AutoCloseable {
    // Schema:
    // CREATE TABLE IF NOT EXISTS skills (
    //     name TEXT PRIMARY KEY,
    //     description TEXT NOT NULL,
    //     category TEXT DEFAULT '',
    //     tags TEXT DEFAULT '[]',  -- JSON array
    //     tools TEXT DEFAULT '[]',  -- JSON array
    //     references TEXT DEFAULT '[]',  -- JSON array
    //     body TEXT DEFAULT '',
    //     body_hash TEXT NOT NULL,  -- SHA-256 for change detection
    //     file_path TEXT NOT NULL,
    //     use_count INTEGER DEFAULT 0,
    //     last_used TEXT,
    //     pinned INTEGER DEFAULT 0
    // )
    //
    // CREATE VIRTUAL TABLE IF NOT EXISTS skill_sections USING fts5(
    //     skill_name, section_title, section_body,
    //     tokenize='porter unicode61'
    // )
    //
    // CREATE TABLE IF NOT EXISTS skill_files (
    //     skill_name TEXT REFERENCES skills(name) ON DELETE CASCADE,
    //     file_type TEXT CHECK(file_type IN ('reference', 'template', 'script')),
    //     file_name TEXT,
    //     file_path TEXT NOT NULL,
    //     PRIMARY KEY (skill_name, file_type, file_name)
    // )
    
    fun upsert(skill: SkillDefinition, filePath: String): SkillDefinition
    fun delete(name: String)
    fun get(name: String): Row?
    fun getAll(): List<Row>
    fun search(query: String, limit: Int = 10): List<SearchResult>
    fun recordUsage(name: String)
    fun getUsageStats(name: String): UsageStats?
    fun close()
}

data class SkillRow(name, description, category, tags, tools, references, body, bodyHash, filePath, useCount, lastUsed, pinned)
data class SearchResult(skillName: String, sectionTitle: String, snippet: String)
data class UsageStats(useCount: Int, lastUsed: String?, pinned: Boolean)
```

**FTS5 notes:**
- SQLite JDBC includes FTS5 compiled in — no extra dependency
- Create virtual tables with `transaction { exec("CREATE VIRTUAL TABLE IF NOT EXISTS skill_sections USING fts5(...)") }`
- FTS5 needs a `content=` option to sync with the source table, or use external content. 
- For simplicity: populate FTS5 separately with skill body content when upserting
- Query: `SELECT skill_name, snippet(skill_sections, 2, '<b>', '</b>', '...', 32) FROM skill_sections WHERE skill_sections MATCH ?`

**Exposed note:** Use `transaction(database) { exec("SQL STATEMENT") }` for raw SQL since Exposed doesn't have FTS5 Table support.

**Headings extraction for sections:**
- Parse Markdown headings (# through ######) from the skill body
- Each heading + its content until the next heading = one section
- Index each section separately in FTS5

### 2. SkillIndexer.kt — NEW
**Path**: `golem-core/src/main/kotlin/dev/golem/skill/SkillIndexer.kt`

One-way sync from files → SQLite. Uses hash-based change detection.

```kotlin
package dev.spola.skill

class SkillIndexer(
    private val skillsDir: Path,
    private val repository: SkillRepository,
) {
    /** Sync files to DB. Returns stats. */
    fun reindex(): IndexResult
    
    /** Hash a file's content for change detection. */
    private fun hash(content: String): String  // SHA-256
    
    /** Extract sections from Markdown body. */
    private fun extractSections(body: String): List<Section>
}

data class Section(title: String, body: String)
data class IndexResult(upserted: Int, deleted: Int, errors: List<String>)
```

**reindex() logic:**
1. Walk skillsDir for SKILL.md + legacy .yaml/.yml files (same as SkillLoader)
2. For each file, compute SHA-256 hash of content
3. If hash differs from DB or skill not in DB → parse + upsert
4. For skills in DB but not on disk → delete from DB
5. Parse headings from body body for FTS5 sections
6. Populate skill_files table from references/frontmatter

## Files to Modify

### 3. SkillCatalog.kt — MODIFY
**Path**: `golem-core/src/main/kotlin/dev/golem/skill/SkillCatalog.kt`

Add caching and repository-backed methods.

Changes:
- Accept optional `repository: SkillRepository?` parameter
- `refresh()` first reads from disk (as before), then upserts into repository
- If repository is available, `get(name)` checks DB first, falls back to in-memory
- Add `search(query: String, limit: Int = 10): List<SkillSummary>` that delegates to repository.search()
- Keep the in-memory maps as a write-through cache — `refresh()` populates both memory and DB
- Add `search` to `formatCatalog()` output only when repository is available
- Add `reindexFromFiles()` method that delegates to SkillIndexer

### 4. SkillTools.kt — MODIFY
**Path**: `golem-core/src/main/kotlin/dev/golem/skill/SkillTools.kt`

Changes:
- `register()` now accepts optional `repository: SkillRepository?`
- Add `search_skills` tool:
  - name: `search_skills`
  - description: "Search installed skills by keyword. Returns matching sections with snippets."
  - parameters: `query` (required, string), `limit` (optional, integer, default 10)
  - Returns: formatted search results with skill name, section title, and snippet
- `skills_list` tool: use cached repository when available instead of re-creating SkillCatalog
- `load_skill` tool: same optimization
- `load_reference` tool: reference skill_files table for file listing

### 5. AgentFactory.kt — MODIFY
**Path**: `golem-core/src/main/kotlin/dev/golem/factory/AgentFactory.kt`

Add smart pre-injection of relevant skill sections.

After the existing skill catalog injection block (lines 82-90), add:

```kotlin
// Smart pre-injection: search for goal-relevant skills
if (config.skillsEnabled && goal != null) {
    val repository = SkillRepository(config.skillsDbPath)
    val indexer = SkillIndexer(Path.of(config.skillsDir), repository)
    indexer.reindex()
    val relevant = repository.search(goal, limit = 2)
    if (relevant.isNotEmpty()) {
        val preBlock = relevant.joinToString("\n\n") { match ->
            "## Pre-loaded: ${match.skillName} — ${match.sectionTitle}\n${match.snippet}"
        }
        persona = "$persona\n\n$preBlock"
    }
}
```

**THIS ONLY GOES IN THE create() METHOD'S persona-building block.**
Do NOT modify createFromAgentDefinition() in this phase.

### 6. GolemConfig.kt — MODIFY
**Path**: `golem-core/src/main/kotlin/dev/golem/GolemConfig.kt`

Add:
```kotlin
val skillsDbPath: String = Path.of(
    System.getProperty("user.home"), ".golem", "skills.db"
).toString(),
```

### 7. ToolRegistryFactory.kt — MODIFY
**Path**: `golem-core/src/main/kotlin/dev/golem/factory/ToolRegistryFactory.kt`

Update `SkillTools.register()` calls to pass the repository:
```kotlin
val skillRepo = SkillRepository(config.skillsDbPath)
val skillIndexer = SkillIndexer(Path.of(config.skillsDir), skillRepo)
skillIndexer.reindex()
SkillTools.register(this, skillsDir = Path.of(config.skillsDir), config = config, repository = skillRepo)
```

Add import for `SkillRepository`, `SkillIndexer`.

## Test Files

### 8. SkillRepositoryTest.kt — NEW
**Path**: `golem-core/src/test/kotlin/dev/golem/skill/SkillRepositoryTest.kt`

Tests (use @TempDir for DB path):
- `inserts and retrieves skill`
- `upsert updates existing skill by name`
- `delete removes skill`
- `search returns matching sections`
- `search with FTS5 stemming works (e.g. "run" matches "running")`
- `recordUsage increments count`
- `getAll returns all skills`
- `get returns null for nonexistent skill`
- `empty DB search returns empty list`

### 9. SkillIndexerTest.kt — NEW
**Path**: `golem-core/src/test/kotlin/dev/golem/skill/SkillIndexerTest.kt`

Tests:
- `reindex syncs SKILL.md files to DB`
- `change detection: hash mismatch triggers re-upsert`
- `deleted file removes from DB`
- `heading extraction creates FTS5 sections`

## Boundaries — DO NOT TOUCH

- Do NOT modify GolemAgent.kt, Tool.kt, ChatMessage.kt, Runner.kt, GolemFactory.kt, GolemInstance.kt
- Do NOT modify SkillDefinition.kt, SkillParser.kt, SkillLoader.kt (Phase 1 files unchanged)
- Do NOT modify API routes, CLI commands (SkillCommand.kt), or any existing test files
- Do NOT add MCP, webhooks, or delivery integrations for skills
- Do NOT add the `skill_pin` or `skill_unpin` tools (that's Phase 4)

## Build Check

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.7-tem
cd /home/gionag/Development/golem
./gradlew compileKotlin
./gradlew test --tests "dev.spola.skill.*"
./gradlew test
```
