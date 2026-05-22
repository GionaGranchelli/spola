# Spola Project Board

## Epic-001: Core Agent Loop ✅
- [x] ADR-001: Project scope
- [x] ADR-002: ReAct loop design
- [x] ADR-003: Tool system
- [x] ADR-004: Memory system
- [x] SPEC-001: Agent loop spec
- [x] SPEC-002: Tools spec
- [x] SPEC-003: Persona spec
- [x] SPEC-004: CLI spec
- [x] TASK-001: Gradle build + project scaffold
- [x] TASK-002: SpolaConfig model
- [x] TASK-003: Tool abstraction + registry
- [x] TASK-004: ReAct agent loop

## Epic-002: Built-in Tools ✅
- [x] TASK-005: FileTools (read, write, search)
- [x] TASK-006: ShellTool
- [x] TASK-007: MemoryStore (SQLite + Exposed)

## Epic-003: Persona + CLI ✅
- [x] TASK-008: PersonaLoader (AGENTS.md > CLAUDE.md > default)
- [x] TASK-009: CLI entry point (one-shot + REPL via picocli)
- [x] TASK-010: REPL commands (/memory, /tools, /exit, /help, /persona)

## Epic-004: Integration Verification ✅
- [x] TASK-011: Full agent end-to-end test (mock LLM, ReAct loop)
- [x] TASK-012: Build + test pass (37 tests, 0 failures)
- [x] TASK-013: AGENTS.md creation for Spola itself

## Test Results
- **63 tests, 0 failures, 0 skipped**
- ToolRegistryTest: 4 tests ✅
- SpolaAgentTest: 5 tests ✅ (ReAct loop, tool calls, maxTurns, errors, parallel tools)
- FileToolsTest: 9 tests ✅ (read, write, search, offset, glob, errors)
- ShellToolTest: 4 tests ✅ (execution, failure, timeout, absolute paths)
- SqliteMemoryStoreTest: 7 tests ✅ (save, search, delete, isolation)
- MemoryToolsTest: 3 tests ✅ (save+search, overwrite, no results)
- PersonaLoaderTest: 4 tests ✅ (default, AGENTS.md, CLAUDE.md, explicit path)
- SpolaMcpServerTest: 7 tests ✅ (server build, all types, tool registry, full tools)
- SpolaCronParserTest: 3 tests ✅ (parse, blank, 6-field reject)
- SpolaJobStoreTest: 5 tests ✅ (add, list, getDueJobs, updateNextRun, remove)
- SpolaSchedulerTest: 4 tests ✅ (pollOnce, skip non-due, fail-safe, multiple jobs)
- SpolaApiServerTest: 3 tests ✅ (health, agent run, jobs CRUD)
- SchedulerToolsTest: 3 tests ✅ (scheduler_add, scheduler_list, scheduler_remove)

## Epic-005: MCP Server ✅ (Task #2)
- [x] TASK-0014: SpolaMcpServer class (tool → MCP tool mapping with JSON Schema)
- [x] TASK-0015: Stdio transport (compatible with Hermes, Claude Code, Codex CLI)
- [x] TASK-0016: SSE transport on :8091 (HTTP + Ktor + Server-Sent Events)
- [x] TASK-0017: CLI flags (--mcp, --mcp-port, --mcp-transport)
- [x] TASK-0018: No LLM provider required in MCP mode (tools-only mode)
- [x] TASK-0019: Integration tests (43 total, 7 MCP-specific)

## Epic-006: Cron/Scheduler ✅ (Task #3)
- [x] TASK-020: SpolaCronParser (5-field → TramAI CronSchedule wrapper)
- [x] TASK-021: SpolaJobStore (SQLite + Exposed, CRUD, getDueJobs)
- [x] TASK-022: SpolaScheduler (polling daemon, coroutine-based)
- [x] TASK-023: CLI flags (--daemon, --scheduler-db, scheduler add/list/remove)
- [x] TASK-024: 12 scheduler tests (0 failures, 57 total)

## Epic-007: REST API Server ✅ (Task #4)
- [x] TASK-025: SpolaApiServer (Ktor, :8082, /api/health, /api/agent/run)
- [x] TASK-026: Jobs CRUD via API (GET/POST/DELETE /api/jobs)
- [x] TASK-027: CLI flags (--api, --api-port)
- [x] TASK-028: Kotlin serialization + content negotiation deps
- [x] TASK-029: API server tests (3 tests, health+agent+jobs)

## Epic-008: Scheduler Tools + MCP ✅ (Task #4b)
- [x] TASK-030: SchedulerTools.kt (scheduler_add/list/remove Spola tools)
- [x] TASK-031: MCP registration (scheduler tools in McpRunner.kt)
- [x] TASK-032: SpolaFactory integration (scheduler tools when schedulerDbPath set)
- [x] TASK-033: Scheduler tools tests (3 tests)

## Test Results
- **182 tests, 0 failures, 0 skipped**
- +16 tests from T-404 (metrics) + T-405 (voice)

## Epic-009: Visibility (completed in Tier 4) ✅
- [x] T-401: Plugin system (Copilot review, fix lifecycle + conflict detection)
- [x] T-402: Containerization (Gemini review, fix API key exposure + security)
- [x] T-403: Dashboard API
- [x] T-404: Application Metrics (Prometheus, /api/metrics, SpolaMetrics)
- [x] T-405: Voice/TTS (tts_say tool, ElevenLabs + Edge TTS providers)

## Epic-010: Skill System Phase 1 — SKILL.md Catalog ✅
- [x] SPEC: SKILL.md file format (YAML frontmatter + Markdown body)
- [x] SkillDefinition.kt: full rewrite with SKILL.md data model (tools, references, tags, body)
- [x] SkillIndex.kt: YAML frontmatter parser + file system scanning
- [x] SkillCatalog.kt: in-memory catalog at startup, cache body to avoid re-parsing
- [x] SkillTools.kt: skills_list, load_skill, load_reference tools
- [x] SkillService.kt: orchestration layer binding catalog + tools
- [x] SpolaFactory.kt integration: wire skill system into agent
- [x] Cross-model review: Codex → fix → Copilot review → fix → Gemini review (3 issues P1/P2, 8 issues Critical/Warning, 1 security concern)
- [x] All tests pass

## Epic-011: Skill System Phase 2 — SQLite + FTS5 ✅
- [x] SkillRepository.kt: SQLite + Exposed, skills table with full-text search
- [x] FTS5 virtual table for full-text search across skill body + tags + description
- [x] SkillIndexer.kt: SHA-256 body_hash change detection, one-way sync (SKILL.md → SQLite)
- [x] search_skills tool: FTS5-backed with relevance ranking
- [x] Smart pre-injection: relevant skill sections into system prompt
- [x] Extracted shared sections parser (extractSections.kt)
- [x] 3-AI review: Codex → fixes → Copilot review (8 more issues: CRLF normalization, thread safety, exceptions) → fixes → Gemini review (SQL injection mitigation)
- [x] All tests pass

## Epic-012: Skill System Phase 3 — Dynamic Tool Registration ✅
- [x] ToolRegistry.kt: addSkillTool / removeSkillTool lifecycle (ConcurrentHashMap + @Synchronized)
- [x] Namespace prefix: skill tools registered as `skillname.toolname`
- [x] load_skill: parse SKILL.md tool frontmatter → live Tool in registry
- [x] unload_skill: deregister all tools from a skill
- [x] Automatic discovery: next LLM call picks up new schemas via toolRegistry.schemas()
- [x] "Documentation as execution": skill tool calls return skill body as context
- [x] FTS5 dead code guard (production uses FTS5 path only)
- [x] 3-AI review pass: Codex → fixes → Copilot → fixes → Gemini (all resolved)
- [x] Tests: 333+ pass (0 failures)

## Epic-013: Dashboard Management CRUD ✅
- [x] Phase 0.1: ToolSchemaResponse.enabled field in API models
- [x] Phase 0.2: SpolaConfigFileStore + ConfigRoutes for YAML persistence
- [x] Phase 0.3: Providers API (GET/POST/DELETE /api/providers, POST /api/provider/test)
- [x] Phase 1.1: Chat tab — real session history on switch (GET /api/session/{id}/messages)
- [x] Phase 1.2: Memory tab — DELETE entries (DELETE /api/memory/{id})
- [x] Phase 1.3: Scheduler tab — Add Job form + per-job Delete
- [x] Phase 1.4: Agents tab — full CRUD (Create, Read, Update, Delete) + Run modal
- [x] Phase 1.5: Workflows tab — list + "Run with Goal" modal
- [x] Phase 1.6: Checkpoints tab — session filter, diff viewer modal, resume button
- [x] Phase 1.7: Delivery tab — test Telegram + Email triggers
- [x] Phase 2.A: Tools tab — enable/disable toggle switches for every tool
- [x] Phase 2.B: Settings tab — real Save button writing to ~/.spola/config.yaml
- [x] Phase 2.C: Providers tab — real API wiring (add, delete, test, key status)
- [x] Single index.html (~2800 lines), vanilla JS/CSS, no frameworks
- [x] All tests pass