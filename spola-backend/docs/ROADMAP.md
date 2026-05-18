# Golem Roadmap

> **Positioning:** The best agent for Kotlin/Java monorepos — embeddable into JVM products and CI.
> **Avoid:** Generalist assistant (Hermes territory), messaging platform (OpenClaw territory), pure MCP proxy (commodity).

## Phase 0 — Foundation Repair (Weeks 1-2)

**Theme:** Fix the gaps Codex identified before building anything new. Every feature below is a broken promise — unshipped code, stubs, or unimplemented wiring.

### 0.1 Wire agent/skill tools into the main runtime

**What:** `AgentTools.register()` and `SkillTools.register()` exist but are never called in `GolemFactory.create()` (line ~55) or the API tool registry (`GolemApiServer.kt:693`). Add the calls so `agent_create/list/delete/update/show/run` and `skill_list/run` are available as LLM tools.

**Why:** This is low-hanging fruit — the code is written, just not connected. Without it, the entire custom agent feature is invisible to the agent itself.

**Files:**
- `golem-core/src/main/kotlin/dev/golem/GolemFactory.kt` — add `AgentTools.register()` + `SkillTools.register()` calls
- `golem-core/src/main/kotlin/dev/golem/api/GolemApiServer.kt` — same in the API server tool registry
- `golem-core/src/main/kotlin/dev/golem/mcp/McpRunner.kt` — same for MCP mode

**Effort:** 0.5d
**Tests:** Update `GolemAgentTest` to verify tools are registered; add integration check in `GolemFactoryWorkflowTest`

### 0.2 Thread temperature/maxTokens through to the model request

**What:** `AgentDefinition` stores `temperature` and `maxTokens` but `GolemFactory.kt:233` has an explicit TODO: they are never applied to `ModelRequest`. Thread them through the provider call.

**Why:** Without this, custom agents can declare temperature/maxTokens but they're silently ignored — a trust-breaking bug.

**Files:**
- `golem-core/src/main/kotlin/dev/golem/GolemFactory.kt` — read from config/AgentDefinition, pass to model provider
- `golem-core/src/main/kotlin/dev/golem/agent/AgentDefinition.kt` — verify serialization round-trips

**Effort:** 0.5d
**Tests:** `GolemAgentTest` — verify temperature reaches mock provider

### 0.3 Implement SSE MCP client transport

**What:** `McpClientManager.kt:227` throws `UnsupportedOperationException` for SSE transport. Implement actual HTTP SSE connection using Ktor client, matching the stdio transport pattern.

**Why:** SSE is the primary MCP transport for remote servers. Without it, the MCP client can only talk to local stdio servers — dramatically limiting its value.

**Files:**
- `golem-core/src/main/kotlin/dev/golem/mcp/McpClientManager.kt` — SSE transport implementation using Ktor SSE client
- `golem-core/src/test/kotlin/dev/golem/mcp/McpClientManagerTest.kt` — add live SSE interop test (with a lightweight test server)

**Effort:** 2d
**Dependencies:** None

### 0.4 Strengthen the permission model

**What:** `PermissionEnforcer.kt:8` explicitly admits shell escape can bypass filesystem/network restrictions. Add runtime checks:
- Command allowlist/blocklist patterns in `AgentDefinition`
- Filesystem path prefix restrictions (not just read-only vs read-write)
- Network URL allowlist/blocklist (based on URL patterns)

**Why:** Enterprise adoption requires real sandbox guarantees. Current model is fine for personal use but a dealbreaker for teams.

**Files:**
- `golem-core/src/main/kotlin/dev/golem/agent/PermissionEnforcer.kt` — runtime command grep, path prefix check, URL pattern check
- `golem-core/src/main/kotlin/dev/golem/agent/AgentDefinition.kt` — add `allowedCommands`, `blockedCommands`, `allowedPaths`, `blockedPaths`, `allowedDomains`
- `golem-core/src/main/kotlin/dev/golem/tools/ShellTool.kt` — blocklist check before execution

**Effort:** 2d
**Tests:** `PermissionEnforcerTest` — happy path, blocklist match, path prefix violation, allowed domain match

### 0.5 Fix API placeholders

**What:** `/api/session/{id}/messages` returns `emptyList<String>()`. Implement actual session message retrieval from the session store.

**Why:** API surface with stubs breaks client trust and makes the web dashboard show empty data.

**Files:**
- `golem-core/src/main/kotlin/dev/golem/api/GolemApiServer.kt` — wire to actual session store
- `golem-core/src/main/kotlin/dev/golem/api/ApiModels.kt` — session message DTO

**Effort:** 1d
**Tests:** `GolemApiServerTest` — existing 36 tests must pass; add session message endpoint test

### 0.6 Wire delivery tools into main runtime

**What:** `DeliveryTools.kt` exists but is not registered in `GolemFactory.create()` or the API server tool registry. Add registration.

**Why:** Same as 0.1 — code is written, just not connected.

**Effort:** 0.5d

---

## Phase 1 — JVM Project Intelligence (Weeks 3-5)

**Theme:** Build a deterministic JVM project map that the ReAct loop can query instead of grep+crawl.

### 1.1 Project scanner + SQLite index

**What:** New package `golem-core/src/main/kotlin/dev/golem/jvm/`:
- `GradleProjectScanner.kt` — parses `settings.gradle.kts`, `build.gradle.kts`, `build.gradle` for modules, plugins, dependencies
- `BuildFileParsers.kt` — reusable Gradle/Maven parser helpers
- `JvmProjectIndex.kt` — interface for project metadata queries
- `SqliteJvmProjectIndex.kt` — SQLite-backed persistence via Exposed

**Why:** Golem needs a repo-native map of modules, source sets, test frameworks, Kotlin/JVM versions — the minimum useful moat.

**Effort:** 4d
**Dependencies:** None (greenfield package)
**Leverages:** `FileTools.kt`, `SqliteMemoryStore.kt` patterns, `ToolRegistry`, `GolemConfig`
**Success criteria:** Tool returns modules, plugin IDs, source/test roots, Java/Kotlin versions for multi-module fixtures without running the LLM

### 1.2 Symbol catalog

**What:**
- `KotlinSymbolExtractor.kt` — regex-based scanner for class/object/fun/val/var declarations in `.kt` files
- `JavaSymbolExtractor.kt` — same for `.java` files
- `SymbolLocation.kt` — data class (name, kind, file, line, column, enclosing)
- `SqliteSymbolIndex.kt` — persisted symbol index with module FK

**Why:** "Find the real class/function quickly" is the core value proposition. No other agent has a JVM-specific symbol catalog.

**Effort:** 5d
**Dependencies:** 1.1

### 1.3 JVM tools (first set)

**What:** `golem-core/src/main/kotlin/dev/golem/tools/JvmProjectTools.kt`:
- `jvm_symbol_search` — find symbols by name/kind/module
- `jvm_file_outline` — list all symbols in a file
- `jvm_project_overview` — modules, plugins, dependencies summary
- `jvm_context_pack` — compact high-signal summary for ReAct loop injection

**Why:** These tools are the agent's interface to the intelligence layer.

**Effort:** 2d
**Dependencies:** 1.1, 1.2

### 1.4 API + dashboard visibility

**What:**
- `/api/project/overview` — modules, plugins, build config
- `/api/project/symbols?q=` — symbol search
- Project tab in SPA dashboard (`golem-core/src/main/resources/web/index.html`)

**Why:** Users need to inspect and trust the index without involving the LLM.

**Effort:** 2d
**Dependencies:** 1.3

### 1.5 Deterministic CLI

**What:** `golem-cli/src/main/kotlin/dev/golem/cli/Main.kt`:
- `golem project scan` — force full reindex
- `golem project overview` — show module tree
- `golem project symbol <name>` — lookup symbol

**Why:** Works without any LLM — debugging and trust-building.

**Effort:** 2d
**Dependencies:** 1.3

### 1.6 Test fixtures

**What:** `golem-core/src/test/resources/fixtures/jvm/` with multi-module Gradle project (2-3 modules, Kotlin + Java mix, dependencies). Tests for scanner, symbol extractor, JVM tools covering happy path + edge cases (malformed files, duplicate symbols, empty repos, no Gradle wrapper).

**Effort:** 3d
**Dependencies:** 1.1-1.3

---

## Phase 2 — Automated Code Intelligence (Weeks 6-9)

**Theme:** Turn the index into agent decisions that generic shell-based coding agents do badly on JVM repos.

### 2.1 Module dependency + task graph

**What:**
- `ModuleDependencyGraph.kt` — resolve Gradle `api`/`implementation`/`compileOnly`
- `GradleTaskCataloger.kt` — map modules to Gradle tasks
- `GradleTaskCache.kt` — cache results keyed by build file hash
- Tools: `jvm_dependency_trace`, `jvm_task_suggest`

**Why:** Golem should know which module and Gradle task matter before it edits anything. "What's the smallest test I need to run?"

**Effort:** 5d
**Dependencies:** Phase 1

### 2.2 Change impact analysis

**What:**
- `GitChangeCollector.kt` — parse `git diff` into file/symbol-level changes
- `ImpactAnalyzer.kt` — map changed symbols to impacted modules/tests
- `TestSelectionEngine.kt` — emit minimal `./gradlew :module:test --tests "..."` commands
- Tool: `jvm_change_impact`

**Why:** Smarter verification, not more agents. This is the clearest product differentiator.

**Effort:** 5d
**Dependencies:** 2.1 (dependency graph) + symbol catalog (1.2)

### 2.3 Build/test failure explainer

**What:**
- `GradleFailureParser.kt` — parse Gradle output for: failing module, failing test, stack trace root, error type
- `JvmFailureExplainer.kt` — collapse raw logs into: owner, cause, next fix site, suggested command
- Tool: `jvm_failure_explain`

**Why:** Raw Gradle output is too noisy for the ReAct loop. Collapse it to actionable signal.

**Effort:** 4d
**Dependencies:** Phase 1

### 2.4 JVM-aware Architect Mode

**What:** Update `ArchitectMode.kt` + add `JvmPlanningPrompts.kt`. Architect phase calls `jvm_context_pack` and `jvm_change_impact` before emitting a plan.

**Why:** The architect/editor split is already present. It should exploit project intelligence by default — this is a 3-day upgrade that compounds everything built so far.

**Effort:** 3d
**Dependencies:** 2.2 (impact analysis), 1.3 (context pack)

### 2.5 Verification guardrail

**What:**
- `JvmPatchPreflight.kt` — after any tool edit, auto-check: compilation scope? test scope? ABI impact?
- Hook into `ShellTool.kt` post-execution and `WorkflowSteps.kt`

**Why:** Default to safe smallest-step verification, not brute force `./gradlew build`.

**Effort:** 5d
**Dependencies:** 2.2, 2.3

---

## Phase 2.5 — Deterministic Process Engine (Weeks 9-10)

**Theme:** Build a "light n8n" where deterministic Kotlin code orchestrates a DAG of typed steps. AI agents are one node type alongside `compile_project`, `git_commit`, `human_approval`. The engine controls the flow, not the AI.

### 2.5.1 Plugin step executors

**What:** 5 plugin steps implementing TramAI's `ExternalStepExecutorFactory`:
- `compile_project` — runs `./gradlew :module:compileKotlin` via `shellStep` with `ShellStepConfig` security
- `run_tests` — runs `./gradlew :module:test`
- `git_commit` — `git add + git commit` via list-based git (no shell injection)
- `git_revert` — `git checkout -- .`
- `telegram_notify` — send status via Telegram

All plugin steps route through existing `ShellTool.kt` security (blocked commands, blocked interpreters). No bare `ProcessBuilder`.

**Files:**
- `golem-core/.../process/plugins/CompileProjectExecutor.kt`
- `golem-core/.../process/plugins/RunTestsExecutor.kt`
- `golem-core/.../process/plugins/GitCommitExecutor.kt`
- `golem-core/.../process/plugins/GitRevertExecutor.kt`
- `golem-core/.../process/plugins/TelegramNotifyExecutor.kt`
- `golem-core/.../process/GolemProcessPluginRegistry.kt`

**Effort:** 1.5d
**Dependencies:** Phase 2.5 verification guardrails ensure plugin steps run in a safe-compile context.

### 2.5.2 Process templates (3 composites)

**What:** 3 workflow templates built with TramAI's `workflow { ... }` DSL:
- **`feature`** — 3-AI pipeline (implement → compile → review → final-review → human-gate → commit)
- **`hotfix`** — 2-AI fast track (implement → compile → test → human-gate → commit, no review)
- **`refactor`** — Plan-first (analyze → human-approve-plan → implement → test → review → final-gate → commit)

Fix loops use retry counters in `GolemState` (max 3 retries, then abort). `branchStep` is forward-only — no goto/loop. Gates use `delayStep` + polling loop (not `gateStep`, which is synchronous).

All `pluginStep` calls use explicit merge functions (default merge requires Map state, not GolemState).

**Files:**
- `golem-core/.../process/ProcessTemplates.kt`
- `golem-core/.../process/ProcessRunner.kt`
- `golem-core/.../process/GateDecisionStore.kt` (SQLite-backed gate approval)

**Effort:** 2d
**Dependencies:** 2.5.1

### 2.5.3 CLI + API

**What:**
- `golem process list|run|status|cancel|approve|reject` — CLI subcommand
- `POST /api/processes/run`, `GET /api/processes/status/{id}`, `POST /api/processes/cancel/{id}`, `POST /api/gates/{runId}/{stepName}/decide` — API endpoints

No YAML definition parser in MVP. No visual UI. Kotlin DSL only.

**Files:**
- `golem-cli/.../cli/ProcessCommand.kt`
- `golem-core/.../api/routes/ProcessRoutes.kt`

**Effort:** 2d
**Dependencies:** 2.5.2

### 2.5.4 Wiring + Tests

**Files:** GolemFactory.kt, GolemApiServer.kt, Main.kt, GolemState.kt

**Effort:** 1.5d

**Total Phase 2.5: ~7 days**

**Roadmap alignment:** The process engine replaces the kanban-driven orchestration use case. Kanban is NOT removed — keeps task management. Phase 3.3 (Opinionated JVM workflows) will use this engine as its foundation.

---

## Phase 3 — Enterprise & Durability (Weeks 10-16)

**Theme:** Make the intelligence layer durable, incremental, and workflow-native.

### 3.1 Incremental reindexing

**What:**
- `JvmIndexCoordinator.kt` — schedules partial rescans
- `JvmFileWatcher.kt` — watches `.kt`, `.java`, `build.gradle.kts` for changes
- `IndexFreshnessPolicy.kt` — staleness thresholds per query criticality

**Why:** Full rescans on real repos (1000+ files) are too slow. Edit one file → reindex one file.

**Effort:** 6d
**Dependencies:** Phase 1-2

### 3.2 Project insight memory

**What:**
- `ProjectInsightStore.kt` — SQLite store keyed by module/symbol
- `ProjectInsightTools.kt` — `project_insight_save`, `project_insight_search` bound to module context
- Agent can learn and recall: "module X uses Kotest, not JUnit," "don't edit generated Y," "always build Z first"

**Why:** Repo-specific conventions are where generic agents forget and local agents win.

**Effort:** 5d
**Dependencies:** 3.1

### 3.3 Opinionated JVM workflows

**What:**
- `golem-core/src/main/kotlin/dev/golem/workflow/JvmWorkflowTemplates.kt`
  - `jvm-debug` — scan project → identify compilation/test failures → fix → verify
  - `jvm-refactor` — project overview → impact analysis → plan → edit → verify
  - `jvm-migration` — dependency catalog → migration window → per-module apply → verify
- Hooks in `TeamWorkflowSteps.kt`, CLI (`Main.kt`), docs (`docs/workflows/WORKFLOWS.md`)

**Why:** Productize intelligence into repeatable, sellable flows.

**Effort:** 5d
**Dependencies:** All prior phases

### 3.4 Engineering dossiers

**What:** Turn checkpoints + git diff + metrics into signed provenance bundles:
- `ProvenanceBundle.kt` — tool calls, code diff, test results, model used, timestamps
- Bundle export to JSON/HTML
- Rollback/replay support from a bundle

**Why:** Valuable for CI, regulated teams, and audit. No other agent does this.

**Effort:** 5d
**Leverages:** `CheckpointManager.kt`, `GolemMetrics.kt`, `GolemTracer.kt`

---

## Quick Wins (can be done in a day)

- `project overview` CLI command — parse `settings.gradle.kts` only (0.5d)
- `/api/project/overview` endpoint in `GolemApiServer.kt` (0.5d)
- JVM fixture repo under `test/resources/fixtures/jvm/simple-multi-module/` (1d)
- `jvm_context_pack` tool using module/build-file summaries only (1d)
- Wire `DeliveryTools.kt`, `AgentTools.kt`, `SkillTools.kt` in `GolemFactory` (1d for all three)
- Fix `/api/session/{id}/messages` placeholder (1d)

## What NOT to Build

| Feature | Why deferred |
|---------|-------------|
| LSP/IDE plugin | API + MCP surface is enough for Phase 1-2 |
| Cloud index / vector DB / K8s | Violates local-first constraint |
| Kotlin compiler plugin / PSI engine | Start with lightweight scanners; pay compiler complexity only if impact tools prove value |
| Python/TS/Go intelligence | Before JVM workflows are clearly better than generic agents, polyglot dilutes focus |
| More communication channels / voice | Existing UI/API surface is sufficient |
| Autonomous commit/push flows | Verification quality is the higher-leverage moat |
| Hermes-style skills ecosystem | Golem's skills are tools, not a marketplace |

## Completed Features

| Feature | Date | Description |
|---------|------|-------------|
| YAML Config File | May 2026 | `~/.golem/config.yaml` with all `GolemConfig` fields, custom providers, `${VAR}` env resolution, CLI-wins merge |
| `golem config` CLI | May 2026 | `config show`, `config path`, `config init` subcommands |
| Providers UI | May 2026 | Visual provider management in dashboard (🔌 Providers tab) |
| Settings UI | May 2026 | Visual config editor in dashboard (⚙️ Settings tab) |
| Config API | May 2026 | `GET /api/config` returns merged config, `GET /api/providers` lists providers, `POST /api/provider/test` tests connectivity |
| TLS/HTTPS Support | May 2026 | `--tls-cert`/`--tls-key` flags, PEM→JKS conversion, Ktor `sslConnector` for encrypted API server |
|| Remote CLI | May 2026 | `golem remote connect <host:port>` — terminal client via REST API + SSE streaming |
|| Process Engine | May 2026 | 5 plugin step executors, 3 process templates (feature/hotfix/refactor), CLI + API for process runs and gate approvals |

## Effort Summary

|| Phase | Effort | Duration |
||-------|:------:|:--------:|
|| 0 — Foundation Repair | 6.5d | 2 weeks |
|| 1 — JVM Intelligence | 18d | 3 weeks |
|| 2 — Automated Code Intel | 22d | 4 weeks |
|| 2.5 — Process Engine | 7d | 2 weeks |
|| 3 — Enterprise & Durability | 21d | 6 weeks |
|| **Total** | **~74.5d** | **~17 weeks** |
