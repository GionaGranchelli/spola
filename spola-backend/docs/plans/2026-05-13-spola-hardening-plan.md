# Spola Hardening Plan

> **Review-driven planning** — synthesized from Codex, Copilot, and Gemini autopsies (May 13, 2026)
> Current state: 346 tests, all passing. Phase 3 committed.

**Goal:** Ship a secure, honest, focused Spola — fix the 3-review consensus findings, then narrow to the JVM moat.

**Methodology:** Each finding is tagged by which reviewers caught it. All-3-agree items go in Phase 1. 2-of-3 items go in Phase 2. Unique but important findings go in Phase 3.

---

## Review Finding Coverage

| # | Finding | Source | Phase | Severity |
|---|---------|--------|-------|----------|
| 1 | Missing tool registrations in `SpolaFactory.create()` (AgentTools, SkillTools, DeliveryTools) | Codex+Copilot+Gemini | P1 | CRITICAL |
| 2 | Temperature/maxTokens from AgentDefinition silently ignored in ModelRequest | Codex+Copilot+Gemini | P1 | CRITICAL |
| 3 | SSE MCP transport is `throw UnsupportedOperationException` | Codex+Copilot+Gemini | P1 | HIGH |
| 4 | API binds 0.0.0.0 by default, null auth = RCE-by-default | Codex+Copilot+Gemini | P1 | CRITICAL |
| 5 | PermissionEnforcer shell bypass (blocklist, documented escape hatch) | Codex+Copilot+Gemini | P1 | CRITICAL |
| 6 | PermissionEnforcer has zero tests | Copilot+Gemini | P1 | HIGH |
| 7 | Docs overselling / contradicting code (test counts, Kotlin version, desktop app, SSE claims) | Codex+Copilot | P1 | HIGH |
| 8 | No CI configuration (GitHub Actions) | Codex+Copilot | P1 | HIGH |
| 9 | CLI module (947 lines, 14 commands) has zero tests | Copilot+Gemini | P2 | HIGH |
| 10 | SpolaFactory.kt (534 lines) is kitchen-sink — 2 creation paths that drifted apart | Copilot+Gemini | P2 | MEDIUM |
| 11 | ArchitectMode duplicates provider resolution from SpolaFactory | Copilot+Gemini | P2 | MEDIUM |
| 12 | API server tool registry independent from SpolaFactory — must manually sync | Copilot+Gemini | P2 | MEDIUM |
| 13 | search_files reads entire files into memory (OOM risk) | Codex+Copilot | P2 | MEDIUM |
| 14 | TokenJuice.compact() calls `println()` to stdout (pollutes MCP/API) | Gemini | P2 | MEDIUM |
| 15 | No Gradle wrapper in project root | Copilot | P2 | LOW |
| 16 | Checkpoint auto-resume is dead code (fresh session ID every run) | Codex | P3 | HIGH |
| 17 | agent_run tool returns failure stub (advertised but dead) | Codex | P3 | HIGH |
| 18 | parallelAgentsStep() uses HTTP localhost from core (architectural laziness) | Codex | P3 | MEDIUM |
| 19 | Pairing endpoints unauthenticated | Gemini | P3 | MEDIUM |
| 20 | 6 separate SQLite databases for v0.1.0 | Copilot+Gemini | P3 | MEDIUM |
| 21 | FileTools.resolvePath() ignores config.workingDirectory (uses user.dir) | Codex | P3 | HIGH |
| 22 | Tool.kt dead code (list/listAll/listEnabled/isEnabled/toggleEnabled unused) | Gemini | P3 | LOW |
| 23 | ChatMessage not sealed (new types won't get exhaustiveness check) | Gemini | P3 | LOW |
| 24 | Feature bloat: Kanban, TTS, scheduling, delivery, pairing, QR — 70% built, 0% essential | All three | P4 | STRATEGIC |
| 25 | JVM intelligence under-leveraged (Architect mode ignores it, core loop doesn't prioritize) | All three | P4 | STRATEGIC |
| 26 | No benchmark / definition of done for "better than Claude Code on JVM repos" | Gemini | P4 | STRATEGIC |

---

## Phase 1 — Security & Truth

**Goal:** Fix the critical trust issues — auth defaults, missing wiring, dead features that are advertised as working, and the security theater. Estimated: 1-2 days.

### Fix 1.1: Auth secure by default

**File:** `spola-backend-cli/src/main/kotlin/dev/spola/cli/Main.kt`
**File:** `spola-backend-core/src/main/kotlin/dev/spola/api/SpolaApiServer.kt`
**File:** `spola-backend-core/src/main/kotlin/dev/spola/api/ApiAuth.kt`

- Change `SpolaApiServer` to bind to `127.0.0.1` by default instead of `0.0.0.0`
- Add `--insecure` flag to CLI that allows `0.0.0.0` without `--api-key`
- In `ApiAuth.validateApiKey()`: when `expectedApiKey` is null/blank and host is not localhost → reject with 403

**Review findings closed:** #4 (API binds 0.0.0.0 by default)

### Fix 1.2: Wire missing tool registrations

**File:** `spola-backend-core/src/main/kotlin/dev/spola/SpolaFactory.kt`
- Add `AgentTools.register()` call in `create()` (currently only in `createFromAgentDefinition()`)
- Verify `SkillTools.register()` and `DeliveryTools.registerDeliveryTools()` are present in `create()`
- Add `registerCheckpointTools()` to `createFromAgentDefinition()` if missing

**File:** `spola-backend-core/src/main/kotlin/dev/spola/api/SpolaApiServer.kt`
- Add `AgentTools`, `SkillTools`, `JvmTools`, `ProjectInsightTools`, `DeliveryTools` to `createApiToolRegistry()`

**File:** `spola-backend-core/src/main/kotlin/dev/spola/mcp/McpRunner.kt`
- Add `AgentTools` and `SkillTools` to MCP server tool registry

**Tests:**
- Add `SpolaFactoryToolRegistrationTest` — verify `create()` and `createFromAgentDefinition()` both produce registries containing all expected tool families
- Add `ApiToolRegistryTest` — verify API server tool registry matches SpolaFactory's

**Review findings closed:** #1 (missing tool registrations), #12 (API server registry drift)

### Fix 1.3: Thread temperature/maxTokens to ModelRequest

**File:** `spola-backend-core/src/main/kotlin/dev/spola/SpolaAgent.kt`
- In `callLlm()`: pass `config.temperature` and `config.maxTokens` to `ModelRequest`
- These fields already exist on `AgentDefinition` and are parsed by `SpolaFactory` — they just never make the last hop

**Tests:**
- Add `SpolaAgentModelParametersTest` — create agent with known temp/maxTokens, verify `ModelRequest` contains them

**Review findings closed:** #2 (temperature/maxTokens silently ignored)

### Fix 1.4: Fix or kill SSE transport

**File:** `spola-backend-core/src/main/kotlin/dev/spola/mcp/McpClientManager.kt` (~line 229)

Two options:
- **A)** Implement SSE transport by connecting to the MCP SSE endpoint instead of stdio
- **B)** Remove the SSE code paths entirely and throw a clear error at config validation time ("SSE transport not yet supported, use stdio")

**Option B is recommended** for this phase — it's a 10-minute fix vs 2-day implementation. Any docs mentioning SSE must be updated.

**Review findings closed:** #3 (SSE transport stub)

### Fix 1.5: PermissionEnforcer — add tests, document limitations honestly

**File:** Create `spola-backend-core/src/test/kotlin/dev/spola/agent/PermissionEnforcerTest.kt`
- Happy path: command allowed
- Blocklist match: blocked command returns `CommandRejected`
- Path prefix violation: fs restricted to `/tmp`, try to write `/etc/passwd`
- Shell escape: verify docstring honesty ("when shellAccess=true, all bets are off")
- Edge cases: symlink traversal, `..` path traversal, empty allowed dirs

**File:** Update `spola-backend-core/src/main/kotlin/dev/spola/agent/PermissionEnforcer.kt`
- Add concrete `python3`, `nodejs`, `deno`, `dash` to `blockedInterpreters` in `ShellTool.kt`
- Add prominent KDoc: "This is NOT a security boundary. Shell access = full OS access."

**Review findings closed:** #5 (PermissionEnforcer shell bypass), #6 (no tests)

### Fix 1.6: Add CI

**File:** Create `.github/workflows/ci.yml`
- Trigger: push to main, pull requests
- Jobs: `./gradlew build`, `./gradlew test` (all modules)
- Cache Gradle deps between runs
- Java 21 (matching toolchain)

**Review findings closed:** #8 (no CI)

### Fix 1.7: Audit docs for honesty

**Files to scan:**
- `README.md` — fix test count (use badge), fix Kotlin version, remove desktop app claim
- `GETTING_STARTED.md` — fix test count, remove stale claims
- `docs/api/API.md` — remove SSE claims if killed, document `agent_run` as experimental
- `docs/overview/README.md` — remove forward-looking feature claims as shipped
- `AGENTS.md` — reflect API server + MCP + scheduler reality, not just CLI
- `docs/architecture/ARCHITECTURE.md` — mark planned vs shipped clearly

Each docs change requires reading the actual source and comparing claims. Use the AGENTS.md as the source of truth — the persona file is what agents read, so it must be accurate.

**Review findings closed:** #7 (docs overselling)

---

## Phase 2 — Code Health & Test Coverage

**Goal:** Close the gap between "347 tests that pass" and "code that inspires confidence." Estimated: 3-5 days.

### Fix 2.1: CLI tests

**File:** Create `spola-backend-cli/src/test/kotlin/dev/spola/cli/MainTest.kt`

Test key codepaths without needing a running agent:
- `SpolaCli.call()` for each mode flag (`--api`, `--version`, `--mcp`, `--help`)
- `SchedulerAddCommand`, `SchedulerListCommand`, `SchedulerRemoveCommand`
- `AgentCreateCommand`, `AgentShowCommand`, `AgentRunCommand`
- `ProvenanceExportCommand`, `ProvenanceAddHostCommand`

Use picocli's `CommandLine` for programmatic invocation. Mock the config/build to avoid real SQLite.

**Review findings closed:** #9 (CLI has zero tests)

### Fix 2.2: Refactor SpolaFactory → split concerns

**File:** `spola-backend-core/src/main/kotlin/dev/spola/SpolaFactory.kt` (534 lines → split)

Split into:
- `AgentFactory` — creates `SpolaAgent` instances from `AgentDefinition` or defaults
- `ToolRegistryFactory` — builds tool registries for different contexts (CLI, API, MCP), single source of truth
- `ProviderResolver` — resolves LLM providers from config (extract from ArchitectMode too)
- `WorkflowFactory` — builds TramAI workflows

Each class under 150 lines. `SpolaFactory` becomes a simple orchestrator calling the 4 factories.

**Note:** `ToolRegistryFactory` as a single source of truth eliminates finding #12 (API server registry drift) permanently.

**Review findings closed:** #10 (SpolaFactory kitchen-sink), #12 (API server registry drift)

### Fix 2.3: Deduplicate provider resolution

**File:** `spola-backend-core/src/main/kotlin/dev/spola/ArchitectMode.kt` — remove `resolveNamedProvider()`, reference `SpolaFactory` or the new `ProviderResolver` instead.

**Review findings closed:** #11 (ArchitectMode duplication)

### Fix 2.4: search_files streaming

**File:** `spola-backend-core/src/main/kotlin/dev/spola/tools/FileTools.kt` (~line 143-181)
- Replace `Files.readAllLines(file)` with `Files.lines(file).use { stream -> ... }` for line-by-line matching
- Add configurable `maxFileSize` parameter (default 10MB) to skip large binary files

**Review findings closed:** #13 (search_files OOM risk)

### Fix 2.5: TokenJuice logging

**File:** `spola-backend-core/src/main/kotlin/dev/spola/compression/TokenJuice.kt` (~line 59-62)
- Replace `println()` with SLF4J logger at DEBUG level
- Verify: no more stdout pollution in API/MCP mode

**Review findings closed:** #14 (TokenJuice println to stdout)

### Fix 2.6: Gradle wrapper

**File:** Add `gradlew` and `gradlew.bat` to project root. Update `.gitignore` to not exclude them.

**Review findings closed:** #15 (no Gradle wrapper)

---

## Phase 3 — Trust & Completeness

**Goal:** Kill dead code, fix broken features that users will hit, and make the remaining surface area truthful. Estimated: 2-3 days.

### Fix 3.1: Fix or kill checkpoint auto-resume

**File:** `spola-backend-core/src/main/kotlin/dev/spola/SpolaAgent.kt` (~line 50-62)
**File:** `spola-backend-core/src/main/kotlin/dev/spola/checkpoint/CheckpointManager.kt`

Current: every run generates a fresh UUID session ID, then immediately tries to `resumeSession` — can never match anything.

Fix: Add `--resume <session-id>` CLI flag. When absent, skip resume entirely. When present, load that session's checkpoint.

**Review findings closed:** #16 (checkpoint auto-resume dead code)

### Fix 3.2: agent_run tool

**File:** `spola-backend-core/src/main/kotlin/dev/spola/agent/AgentTools.kt` (~line 242-250)

Currently returns `ToolResult.fail("agent_executor not available")`. Either implement it (call the API server's agent run endpoint) or replace with a clear error: "agent_run requires the API server to be running with `spola --api`".

**Review findings closed:** #17 (agent_run stub)

### Fix 3.3: parallelAgentsStep → direct call, not HTTP

**File:** `spola-backend-core/src/main/kotlin/dev/spola/workflow/TeamWorkflowSteps.kt` (~line 75-137)

Replace the HTTP `http://localhost:8082/api/agents/run` call with:
- A direct method call on `SpolaAgent` (create agent instance, run, collect result)
- OR an internal service /use-case that both the workflow and the API server call

This eliminates the HTTP dependency, serialization overhead, and runtime requirement that the API server is up.

**Review findings closed:** #18 (workflow HTTP localhost coupling)

### Fix 3.4: Secure pairing endpoints

**File:** `spola-backend-core/src/main/kotlin/dev/spola/api/SpolaApiServer.kt` (~line 236-262)

Add authentication to `/api/pairing/info` and `/api/pairing/qrcode`. The pairing token is sufficient if the endpoint validates it. Currently returns pairing info to any unauthenticated LAN caller.

**Review findings closed:** #19 (pairing endpoints unauthenticated)

### Fix 3.5: Consolidate SQLite databases

6 databases: memory, checkpoint, scheduler, agents, sessions, kanban

Merge into a single `spola.db` with separate tables. Use a shared `Database` connection in `SpolaConfig`. Add connection pooling (HikariCP for SQLite, or manually manage a single connection).

**Note:** This is a significant refactor — every store class takes a `String dbPath`. Consider deferring to after the Factory split (Phase 2) to avoid fighting with two refactors at once.

**Review findings closed:** #20 (6 SQLite databases)

### Fix 3.6: FileTools workingDirectory

**File:** `spola-backend-core/src/main/kotlin/dev/spola/tools/FileTools.kt` (~line 22-27)

`resolvePath()` currently resolves relative paths from `System.getProperty("user.dir")`. Change to:
1. Use `config.workingDirectory` if set
2. Fall back to `user.dir`
3. Also apply in `search_files` and `write_file`

**Review findings closed:** #21 (FileTools ignores config.workingDirectory)

### Fix 3.7: Remove dead code in Tool.kt

**File:** `spola-backend-core/src/main/kotlin/dev/spola/Tool.kt`
- Remove or deprecate `list()`, `listAll()`, `listEnabled()` overloads
- Remove `isEnabled()`, `toggleEnabled()` if never called
- Keep only `schemas()` and `execute()` as the public API

**Review findings closed:** #22 (Tool.kt dead code)

### Fix 3.8: Fix Gradle wrapper

Already listed in Phase 2 as 2.6, move to Phase 2.

### Fix 3.9: Make ChatMessage sealed

**File:** `spola-backend-core/src/main/kotlin/dev/spola/ChatMessage.kt`

If `ChatMessage` isn't a sealed class/interface, make it one. This ensures exhaustive `when` branches at compile time when new message types are added.

**Review findings closed:** #23 (ChatMessage not sealed)

---

## Phase 4 — Strategic: Narrow to JVM Moat

**Goal:** Cut gimmicks, prioritize JVM intelligence, and establish benchmarks. Estimated: 1-2 weeks (ongoing).

### Fix 4.1: Evaluate feature bloat

Run a PM exercise on every feature not core to "JVM coding agent":

| Feature | Keep? | Reasoning |
|---------|-------|-----------|
| Kanban | ❌ Cut | Not JVM-specific. Hermes/Linear exist. |
| TTS | ❌ Cut | Vanity. A CLI agent doesn't need speech. |
| Scheduling/Cron | ❌ Cut | 10% of the use case, 30% of the infra. |
| Email delivery | ❌ Cut | Users can pipe output themselves. |
| Pairing/QR | ❌ Cut | Solves a problem nobody has yet. |
| MCP client | ⚠️ Re-evaluate | Does Spola need to consume MCP tools or just expose them? |
| MCP server | ✅ Keep | JVM agent exposing tools via MCP is a distribution channel. |
| REST API | ⚠️ Trim to health+run only | Full management API is overkill for v0.1. |
| Web dashboard | ❌ Cut | Embedded HTML/CSS/JS in a JAR is the wrong delivery mechanism. |
| Provenance bundles | ✅ Keep | Differentiator for regulated teams — lightweight, compact. |
| JVM intelligence | ✅ ✅ Invest | Only moat. Symbol search, impact analysis, failure explanation. |

Cut features by:
1. Removing CLI commands and API endpoints
2. Removing dependencies (ZXing, Angus Mail, TTS libs)
3. Removing test fixtures for removed features
4. Updating docs

### Fix 4.2: Deepen JVM intelligence

- Replace regex-based symbol extraction with PSI (kotlin-compiler-embeddable) for Kotlin and `com.sun.source.tree` for Java
- Add cross-module dependency graph (parse `build.gradle.kts` dependencies)
- Add change-impact analysis: "If I modify `UserService.kt`, which files/tests are affected?"
- Add Gradle compilation error explanation: parse `:compileKotlin` output and explain failures

### Fix 4.3: Create benchmark suite

Define 5 JVM tasks with acceptance criteria:
1. **Refactor:** Rename a class across a multi-module project → all references updated, project compiles
2. **Debug:** Find and fix a compilation error in a Gradle build → `./gradlew build` passes
3. **Migrate:** Migrate from JUnit 4 to JUnit 5 → tests pass, no JUnit4 deps remain
4. **Explain:** Given a test failure output, identify the root cause with file+line
5. **Impact:** Given a source change, list all files that need updating

Each benchmark has a script that runs it, measures time, and validates results. Track these over time.

**Review findings closed:** #25 (JVM intelligence under-leveraged), #26 (no benchmark)

---

## Execution Order

```
Phase 1 — Security & Truth
├── Fix 1.1  Auth defaults (secure by default)
├── Fix 1.2  Wire missing tool registrations + test
├── Fix 1.3  Thread temperature/maxTokens + test
├── Fix 1.4  Fix/kill SSE transport
├── Fix 1.5  PermissionEnforcer tests + blocklist fixes
├── Fix 1.6  Add CI (GitHub Actions)
├── Fix 1.7  Audit docs for honesty
│
Phase 2 — Code Health & Test Coverage
├── Fix 2.1  CLI tests
├── Fix 2.2  Split SpolaFactory → ToolRegistryFactory (eliminates registry drift)
├── Fix 2.3  Deduplicate provider resolution
├── Fix 2.4  search_files streaming (OOM fix)
├── Fix 2.5  TokenJuice logger
└── Fix 2.6  Gradle wrapper
│
Phase 3 — Trust & Completeness
├── Fix 3.1  Checkpoint resume (CLI flag)
├── Fix 3.2  agent_run honest error
├── Fix 3.3  parallelAgentsStep direct call
├── Fix 3.4  Secure pairing endpoints
├── Fix 3.5  Consolidate SQLite databases
├── Fix 3.6  FileTools workingDirectory
├── Fix 3.7  Remove dead Tool.kt code
└── Fix 3.8  Make ChatMessage sealed
│
Phase 4 — Strategic: Narrow to JVM Moat
├── Fix 4.1  Feature bloat audit (cut Kanban, TTS, scheduling, etc.)
├── Fix 4.2  Deepen JVM intelligence (PSI, dep graph, impact analysis)
└── Fix 4.3  Create benchmark suite
```

---

## Verification Cadence

After each fix in Phases 1-3:
1. `./gradlew :spola-backend-core:test --no-build-cache` ✅
2. `./gradlew :spola-backend-cli:test --no-build-cache` ✅ (after Phase 2.1)

After Phase 1:
- `./spola-backend-cli` (CLI mode) — one-shot prompt, verifies tool registrations
- Clean `git clone` → `./gradlew build` (verifies wrapper + CI readiness)

After Phase 4:
- Benchmark suite run against the 5 JVM tasks
- Feature-flag gated removal (cut = don't delete yet, just disable with a config flag)

---

**Ready to execute when you are.** Phase 1 is ~1-2 days and addresses every finding that all 3 reviewers flagged as critical.
