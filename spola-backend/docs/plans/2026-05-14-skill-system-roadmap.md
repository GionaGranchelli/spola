# Skill System — Implementation Roadmap

## Architecture Decision

**Hybrid (files + SQLite FTS5)** — Markdown+YAML SKILL.md files as source of truth, SQLite FTS5 for search and telemetry. One-way flow: files → DB.

## Phases

### Phase 1 — In-Memory Catalog + SKILL.md File Format
**Goal:** Replace current flat-YAML skills with Hermes-compatible SKILL.md format. In-memory catalog at startup. Three tools: `skills_list`, `load_skill`, `load_reference`.

Files to create/modify:
1. `SkillDefinition.kt` — rewrite: YAML frontmatter + Markdown body, category, tags, tools, references
2. `SkillParser.kt` — NEW: parse SKILL.md (YAML frontmatter + markdown body split)
3. `SkillCatalog.kt` — NEW: in-memory catalog with category grouping, runtime mutable
4. `SkillLoader.kt` — rewrite: scan `~/.golem/skills/<category>/<name>/SKILL.md` recursively
5. `SkillTools.kt` — rewrite: `skills_list` (categorized catalog), `load_skill` (full body), `load_reference` (linked file)
6. `AgentFactory.kt` — inject `## Available Skills\n{name: description}` catalog into system prompt
7. `GolemConfig.kt` — add `skillsDir`, `skillsEnabled`
8. `ToolRegistryFactory.kt` — wire new SkillTools, pass skillsDir config

### Phase 2 — SQLite FTS5 + Search + Smart Injection
**Goal:** Add persistent index with full-text search. `search_skills` tool. Smart pre-injection of relevant sections.

Files to create/modify:
1. `SkillRepository.kt` — NEW: SQLite schema (skills, skill_sections FTS5, skill_files)
2. `SkillIndexer.kt` — NEW: one-way files→DB sync, hash-based change detection
3. `SkillTools.kt` — add `search_skills` (FTS5 query with snippets)
4. `AgentFactory.kt` — smart pre-injection: query FTS5 for goal-relevant sections
5. `GolemConfig.kt` — add `skillsDbPath`

### Phase 3 — Dynamic Tool Registration
**Goal:** Skills define tools in frontmatter. `load_skill` registers them. `unload_skill` cleans up.

Files to modify:
1. `SkillDefinition.kt` — add `tools: List<SkillToolDef>` field
2. `ToolRegistry.kt` — add `activateSkill(name, tools)`, `deactivateSkill(name)` with schema regeneration
3. `SkillTools.kt` — `load_skill` calls `registry.activateSkill()`, add `unload_skill`
4. `GolemAgent.kt` — regenerate tool schemas on next LLM call after registry change

### Phase 4 — File Watcher + Pinning + Telemetry
**Goal:** Hot-reindex on file changes. Pinned skills always injected. Telemetry-based ranking.

Files to modify:
1. `SkillIndexer.kt` — add java.nio.file.WatchService for directory watching
2. `SkillCatalog.kt` — add pinned set, `isPinned`, `togglePinned`
3. `SkillTools.kt` — add `skill_pin`, `skill_unpin`
4. `AgentFactory.kt` — pinned skills always injected into system prompt
5. Search ranking: `ORDER BY use_count DESC, last_used DESC`

### Phase 5 — Abstract Search Interface + Vector Search
**Goal:** Extensible `SkillSearcher` interface. Optional embedding-based search.

Files to create/modify:
1. `SkillSearcher.kt` — NEW: interface with `search(query, topK): List<SkillChunk>`
2. `Fts5Searcher.kt` — NEW: FTS5 implementation
3. `VectorSearcher.kt` — NEW: ONNX-based embedding search (all-MiniLM-L6-v2)
4. `SkillIndexer.kt` — generate embeddings alongside FTS5 index

## Implementation Workflow (per task)

1. Codex (OpenAI) — writes code
2. Copilot — reviews, flags issues
3. Codex — fixes issues
4. Gemini — reviews (final check)
5. Codex — fixes final issues
6. Me — verify build + tests + update docs
7. Rotate AI roles for next task
