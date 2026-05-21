# SPEC: Deterministic Process Engine (Light n8n)

**Status:** Final (post-3-AI review)
**Target:** Spola master branch
**Estimated effort:** 5-7 days (single developer)

---

## 1. Motivation

Spola currently runs agents in one-shot mode — user types a goal, agent runs until it decides it's done. There's no guarantee the agent:
- Runs verification (compile/test) before declaring done
- Follows a review step
- Produces documentation
- Respects process order

The user's philosophy: **"Deterministic software orchestrates, AI executes the variable part."**

This spec defines a deterministic process engine where:
- A workflow is a **linear sequence of typed steps** with conditional branching
- AI agents are **one step type** alongside `compile_project`, `git_commit`, `human_approval`, etc.
- The **engine** (TramAI `Workflow`) controls the flow — not the AI
- The AI is called only when needed, with bounded scope and strict tool permissions

---

## 2. Architecture Overview

```
User (CLI / API)
  │  "spola process run feature 'add rate limiting'"
  ▼
TramAI Workflow Engine ─── deterministic Kotlin code
  │
  ├── Step 1: spola_agent("implement")
  │     └── SpolaAgent runs ReAct loop with tools=[file]
  │     └── Output: files changed, summary
  │
  ├── Step 2: compile_project("verify")
  │     └── Runs ./gradlew via shellStep with allowedCommands=["^./gradlew"]
  │     └── Output: exit_code, output
  │
  ├── branch(exit_code == 0) →
  │     ├── PASS → Step 3a: spola_agent("review")
  │     │         └── SpolaAgent runs with tools=[file.read]
  │     └── FAIL → Step 3b: git_revert + Telegram notify
  │
  ├── Step 4: human_approval("gate")
  │     └── delayStep loop checks GateDecisionStore (persistent SQLite)
  │
  └── Step 5: git_commit("commit")
        └── Runs git via shellStep with allowedCommands=["^git\\s+(add|commit)"]
```

**Key constraint:** The engine runs steps sequentially (or in parallel where declared). AI agents are spawned, given a bounded goal + tools, and waited on. The engine does the orchestration. Plugin steps DO NOT use bare `runProcess()` — they use TramAI's `shellStep` with `ShellStepConfig` for security.

---

## 3. Workflow Engine Foundation

**Already exists** in TramAI's `tramai-orchestration` module (already a Spola dependency).

**Step types used:**

| Step Type | Purpose | Provided By | Notes |
|-----------|---------|-------------|-------|
| `aiStep` | LLM agent execution (SpolaAgent) | TramAI + Spola wrapper | Bounded by StopPolicy |
| `pluginStep` | Custom deterministic action | Spola plugins | **Must use explicit merge function** — default merge requires Map state, not SpolaState |
| `branchStep` | Conditional routing | TramAI | **Forward-only** — no goto/loop. Use retry counters in SpolaState for loops |
| `gateStep` | Human approval | TramAI | **Synchronous** — use delayStep loop for polling, not gateStep |
| `parallelStep` | Fan-out execution | TramAI | For multi-agent review |
| `delayStep` | Wait and resume | TramAI | Used for gate polling loop |

### Critical Implementation Notes (from code review)

**pluginStep merge:** Every `pluginStep` call MUST include an explicit merge function because `SpolaState` is a data class, not a `Map`:

```kotlin
pluginStep("verify", type = "compile_project") { state, result, _ ->
    val passed = result["passed"] as? Boolean ?: false
    val output = result["output"] as? String ?: ""
    state.copy(
        result = if (passed) "verify_passed" else "verify_failed",
        intermediateResults = state.intermediateResults + (
            "verify_output" to output,
            "verify_passed" to passed.toString(),
        ),
    )
}
```

**Loop patterns:** Because `branchStep` is forward-only, retry loops use a retry counter in `SpolaState`:

```kotlin
// Track retries
data class SpolaState(
    // ... existing fields ...
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val currentPhase: String = "implement",
)

// A retry cycle is a localStep that checks the counter
localStep("check_retry") { state ->
    if (state.result == "verify_failed" && state.retryCount < state.maxRetries) {
        state.copy(retryCount = state.retryCount + 1, currentPhase = "fix")
    } else if (state.result == "verify_failed") {
        state.copy(result = "failed_max_retries", currentPhase = "abort")
    } else {
        state.copy(currentPhase = "review")
    }
}
```

---

## 4. Plugin Steps to Build

Each plugin implements `ExternalStepExecutorFactory` and is registered in a global `ExternalStepExecutorRegistry`.

**Security:** Plugin steps MUST NOT use bare `runProcess()` or direct `ProcessBuilder` calls. Instead, they use TramAI's `shellStep` with `ShellStepConfig` which provides:
- `allowedCommands` — regex allowlist (e.g., `["^./gradlew\\s+.*", "^git\\s+(add|commit)\\s+.*"]`)
- `deniedCommands` — regex blocklist
- `failOnNonZeroExit`
- `timeout` — per-step timeout
- `outputLimit` — max output chars

### 4.1 `compile_project`

```kotlin
class CompileProjectExecutorFactory : ExternalStepExecutorFactory {
    override val typeId = "compile_project"

    override fun create() = ExternalStepExecutor { spec ->
        val project = spec["project"] as? String ?: ""
        val tasks = spec["tasks"] as? String ?: "compileKotlin compileJava"
        val command = "./gradlew $project:$tasks"
        // Uses ShellStepConfig — NOT bare ProcessBuilder
        // allowedCommands = ["^./gradlew\\s+.*"] enforced at workflow level
        val result = runShell(command, timeoutSeconds = 300)
        mapOf(
            "exit_code" to result.exitCode,
            "output" to result.output.take(2000),
            "passed" to (result.exitCode == 0),
        )
    }
}
```

**Config options:**
- `project` (required) — Gradle project path, e.g. `:spola-backend-core`
- `tasks` (optional) — custom Gradle tasks, defaults to `compileKotlin compileJava`

### 4.2 `run_tests`

```kotlin
class RunTestsExecutorFactory : ExternalStepExecutorFactory {
    override val typeId = "run_tests"

    override fun create() = ExternalStepExecutor { spec ->
        val project = spec["project"] as? String ?: ""
        val command = "./gradlew $project:test --no-build-cache"
        val result = runShell(command, timeoutSeconds = 600)
        mapOf(
            "exit_code" to result.exitCode,
            "output" to result.output.take(2000),
            "passed" to (result.exitCode == 0),
        )
    }
}
```

### 4.3 `git_commit`

```kotlin
class GitCommitExecutorFactory : ExternalStepExecutorFactory {
    override val typeId = "git_commit"

    override fun create() = ExternalStepExecutor { spec ->
        val message = spec["message"] as? String ?: "Auto-commit from Spola"
        // Use list-based git command to prevent shell injection
        runShell("git add -A")
        runShell("git", listOf("commit", "-m", message), timeoutSeconds = 30)
        // ^ list form: no shell parsing, message is a single arg
        mapOf("exit_code" to result.exitCode, "hash" to extractHash(result.output))
    }
}
```

**Never give git_commit access to the AI agent.** Only the engine calls this step. The list-based form prevents command injection.

### 4.4 `git_revert`

```kotlin
class GitRevertExecutorFactory : ExternalStepExecutorFactory {
    override val typeId = "git_revert"
    override fun create() = ExternalStepExecutor {
        runShell("git checkout -- .", timeoutSeconds = 10)
        mapOf("exit_code" to 0)
    }
}
```

### 4.5 `telegram_notify`

```kotlin
class TelegramNotifyExecutorFactory : ExternalStepExecutorFactory {
    override val typeId = "telegram_notify"
    override fun create() = ExternalStepExecutor { spec ->
        val message = spec["message"] as? String ?: ""
        // Send via Spola's existing Telegram delivery system
        telegramService.send(message)
        mapOf("sent" to true)
    }
}
```

---

## 5. Gate Mechanism (Corrected)

**The spec originally proposed a polling `GateDecisionStore` + `gateStep`. This does not work.** TramAI's `gateStep` is synchronous — it calls `decide(state)` immediately and either passes or throws `WorkflowGateRejectedException`. It cannot wait for external input.

**Corrected design:**

### 5.1 Architecture

```
delayStep("wait_for_approval", duration = 5.seconds)
  → localStep("check_gate") {
      val decision = gateStore.get(runId, stepName)
      if (decision != null) {
        if (decision == "APPROVED") state.copy(result = "approved")
        else state.copy(result = "rejected")
      } else {
        state.copy(result = "pending")
      }
    }
  → branchStep("gate_result") {
      "approved" → "next_step"
      "rejected" → "notify_rejection"
      "pending"  → "wait_for_approval"  // loop back
    }
```

The loop is implemented by `branchStep` naming the next step's name. Since `branchStep` is forward-only, the "loop back" is a **named jump** to an earlier step label — this IS supported by TramAI's `branchStep(name, branches)` where branches map step names → step names.

### 5.2 GateDecisionStore (persistent)

```kotlin
class GateDecisionStore(private val db: SqliteDatabase) {
    // SQLite-backed: (runId TEXT, stepName TEXT, decision TEXT, notes TEXT, decidedAt TEXT)
    // PRIMARY KEY (runId, stepName)
    
    fun awaitDecision(runId: String, stepName: String): GateDecision {
        // Called from the delayStep loop
        return transaction(db) {
            Gates.select { (Gates.runId eq runId) and (Gates.stepName eq stepName) }
                .singleOrNull()?.toDecision()
        }
    }
    
    fun decide(runId: String, stepName: String, decision: GateDecision) {
        // Called from CLI or API
        transaction(db) {
            Gates.insert { ... }
        }
    }
}
```

### 5.3 Gate timeout

Every gate has a configurable TTL. If no decision arrives within the TTL, the workflow auto-rejects:

```kotlin
val gateTtlSeconds = spec["ttl"] as? Long ?: 3600L // default 1 hour

localStep("check_gate_ttl") { state ->
    val elapsed = currentTime - gateStartedAt
    if (elapsed > gateTtlSeconds * 1000) {
        state.copy(result = "rejected_timeout")
    } else {
        state // continue polling
    }
}
```

---

## 6. Composite Workflow Templates

Built using existing `workflow { ... }` DSL. Each uses retry counters in `SpolaState` for fix loops. Agent selection per step is configured via `SpolaState.perStepAgent` map.

### 6.1 `feature` workflow (3-AI pipeline)

```
[localStep]  init_retry → set retryCount=0, phase="implement"
[aiStep]     implement → agent=codex, tools=[file], goal=state.goal
[pluginStep] verify_compile → compile_project(project=config.project)
[branchStep] check_compile →
    PASS(counter<3) → review
    FAIL(counter<3) → [aiStep] fix → increment retry → verify_compile
    FAIL(counter=3) → abort
[aiStep]     review → agent=copilot, tools=[file.read]
[branchStep] check_review →
    PASS → final_review
    FAIL → [aiStep] fix → verify_compile
[aiStep]     final_review → agent=gemini, tools=[file.read]
[branchStep] check_final →
    PASS → human_gate
    FAIL → [aiStep] fix → verify_compile
[delayStep]  gate_poll → 5 seconds
[localStep]  check_gate → look up GateDecisionStore
[branchStep] gate_result →
    APPROVED → commit
    REJECTED → git_revert + telegram_notify
    PENDING → gate_poll
[pluginStep] commit → git_commit(message=summary)
[pluginStep] notify → telegram_notify("done: {commit_url}")
```

### 6.2 `hotfix` workflow (fast track, no review)

```
[aiStep]     implement → tools=[file]
[pluginStep] verify_compile
[branchStep] check_compile →
    PASS → verify_tests
    FAIL(max 2 retries) → fix → verify_compile
[pluginStep] verify_tests → run_tests(project=config.project)
[branchStep] check_tests →
    PASS → human_gate
    FAIL(max 2 retries) → fix → verify_compile
[delayStep]  gate_poll
[localStep]  check_gate
[branchStep] gate_result →
    APPROVED → commit
    REJECTED → git_revert + notify
    PENDING → gate_poll
[pluginStep] commit
[pluginStep] notify
```

### 6.3 `refactor` workflow (review-heavy, human-approval required before coding)

```
[aiStep]     analyze → tools=[file.read], analyzes impact
[delayStep]  plan_gate_poll
[localStep]  check_plan_gate
[branchStep] plan_gate_result →
    APPROVED → implement
    REJECTED → notify_cancelled
    PENDING → plan_gate_poll
[aiStep]     implement → tools=[file]
[pluginStep] verify_compile
[pluginStep] verify_tests
[branchStep] check_tests →
    PASS → review
    FAIL(max 3 retries) → fix → verify_compile
[aiStep]     review → tools=[file.read], agents=[copilot, gemini]
[delayStep]  final_gate_poll
[localStep]  check_final_gate
[branchStep] final_gate_result →
    APPROVED → commit
    REJECTED → git_revert + notify
    PENDING → final_gate_poll
[pluginStep] commit
[pluginStep] notify
```

---

## 7. CLI Surface

```
spola process list                      # List available process templates
spola process run <template> [goal]     # Run a process with a goal
spola process status <run-id>           # Check status of a running process
spola process cancel <run-id>           # Cancel a running process
spola process approve <run-id> [notes]  # Approve a pending gate step
spola process reject <run-id> [notes]   # Reject a pending gate step
```

**Note:** `spola process validate` and `spola process run <definition.yaml>` are NOT included in MVP. YAML parsing is deferred. All process definitions are Kotlin DSL templates only.

**Examples:**
```bash
spola process run feature "add rate limiting to the API"
spola process status proc_abc123
spola process approve proc_abc123 "Looks good, ship it"
```

---

## 8. API Surface

```http
POST /api/processes/run
Content-Type: application/json

{
  "template": "feature",
  "goal": "add rate limiting to the API",
  "config": {
    "project": ":spola-backend-core"
  }
}
```

```http
GET /api/processes/status/{runId}
→ { runId, template, status, currentStep, result }
```

```http
POST /api/processes/cancel/{runId}
```

```http
POST /api/gates/{runId}/{stepName}/decide
Content-Type: application/json

{
  "decision": "APPROVED",
  "notes": "Looks good, ship it"
}
```

The gate endpoint writes to `GateDecisionStore` (SQLite-backed). The polling loop (`delayStep` + `localStep`) picks up the decision on its next 5-second tick.

---

## 9. Implementation Order

### Phase 1 — Plugin Executors + Security (Days 1-2)

1. **Create `runShell` helper** that wraps bash with timeout, output capture, and working directory (~40 lines)
   - NOT a bare `ProcessBuilder` — routes through existing `ShellTool.kt` security layer
   - Supports both string and list-based command forms (prevents injection)

2. **Create 5 plugin step executors** (~110 lines total)
   - `CompileProjectExecutor.kt` (30 lines)
   - `RunTestsExecutor.kt` (30 lines)
   - `GitCommitExecutor.kt` (25 lines) — uses list-based git commands
   - `GitRevertExecutor.kt` (15 lines)
   - `TelegramNotifyExecutor.kt` (15 lines)

3. **Create `SpolaProcessPluginRegistry.kt`** (~35 lines)
   - Registers all plugins into an `ExternalStepExecutorRegistry`
   - Factory method called at startup in `SpolaFactory`

### Phase 2 — Process Templates + Gate Store (Days 2-4)

4. **Create `SpolaState` extensions** for process engine (~30 lines)
   - Add `retryCount: Int`, `maxRetries: Int`, `currentPhase: String`, `perStepAgent: Map<String, String>` to `SpolaState`
   - Add merge helper for pluginStep results

5. **Create `GateDecisionStore.kt`** (~60 lines)
   - SQLite-backed table: `gates(runId, stepName, decision, notes, createdAt, decidedAt)`
   - `awaitDecision(runId, stepName): GateDecision?`
   - `decide(runId, stepName, decision, notes)`
   - TTL expiry for stale gates

6. **Create `ProcessTemplates.kt`** (~130 lines)
   - `feature()` — 3-AI pipeline with compile + review gates + fix loops
   - `hotfix()` — 2-AI pipeline, compile + test, no review
   - `refactor()` — plan-approval-gate + implementation + review + final-gate
   - Each uses `workflow<SpolaState> { ... }` DSL with explicit merge functions on pluginStep calls

7. **Create `ProcessRunner.kt`** (~100 lines)
   - Takes a template name + goal → resolves template → creates `Workflow` → runs with persistence
   - Handles checkpointing via existing `WorkflowPersistence`
   - Result collection: final state + step logs

### Phase 3 — CLI + API (Days 4-5)

8. **Create `ProcessCommand.kt`** (~150 lines)
   - `spola process list` — lists available templates from registry
   - `spola process run` — resolves template, calls ProcessRunner
   - `spola process status` — queries WorkflowPersistence for run state
   - `spola process cancel` — calls cancel on running Workflow
   - `spola process approve/reject` — writes to GateDecisionStore

9. **Create `ProcessRoutes.kt`** (~80 lines)
   - `POST /api/processes/run` — accepts template + goal + config
   - `GET /api/processes/status/{runId}` — returns run state
   - `POST /api/processes/cancel/{runId}`
   - `POST /api/gates/{runId}/{stepName}/decide`

### Phase 4 — Wiring + Tests (Days 5-7)

10. **Wire into `SpolaFactory` / startup** (~20 lines)
    - Initialize `ExternalStepExecutorRegistry` with plugins
    - Pass to `WorkflowFactory.createWorkflow()`

11. **Wire `SpolaApiServer` + `Main.kt`** (~15 lines)
    - Register `ProcessRoutes` in SpolaApiServer
    - Register `ProcessCommand` in Main.kt subcommands

12. **Tests** (~200 lines)
    - Plugin step unit tests (mock shell, verify output)
    - GateDecisionStore tests (SQLite-backed CRUD, TTL expiration)
    - Workflow template tests (run through TramAI test harness with mock steps)
    - CLI smoke tests (parse args, verify routing)

---

## 10. Files Summary

### New files (~700 lines total)

| File | Lines | Purpose |
|------|-------|---------|
| `spola-backend-core/.../process/ProcessRunner.kt` | 100 | Execute + persist workflows |
| `spola-backend-core/.../process/ProcessTemplates.kt` | 130 | 3 workflow templates with retry loops |
| `spola-backend-core/.../process/GateDecisionStore.kt` | 60 | SQLite-backed gate approval store |
| `spola-backend-core/.../process/SpolaProcessPluginRegistry.kt` | 35 | Plugin registration |
| `spola-backend-core/.../process/plugins/CompileProjectExecutor.kt` | 30 | `compile_project` plugin |
| `spola-backend-core/.../process/plugins/RunTestsExecutor.kt` | 30 | `run_tests` plugin |
| `spola-backend-core/.../process/plugins/GitCommitExecutor.kt` | 25 | `git_commit` plugin (list-based) |
| `spola-backend-core/.../process/plugins/GitRevertExecutor.kt` | 15 | `git_revert` plugin |
| `spola-backend-core/.../process/plugins/TelegramNotifyExecutor.kt` | 15 | `telegram_notify` plugin |
| `spola-backend-cli/.../cli/ProcessCommand.kt` | 150 | CLI subcommand (list/run/status/cancel/approve/reject) |
| `spola-backend-core/.../api/routes/ProcessRoutes.kt` | 80 | API endpoints |
| **TOTAL NEW** | **~770** | |

### Modified files

| File | Changes |
|------|---------|
| `SpolaState.kt` | Add `retryCount`, `maxRetries`, `currentPhase`, `perStepAgent` fields |
| `WorkflowSteps.kt` | (Optional) Add `spolaAgentStepWithAgent(name)` overload for per-step agent selection |
| `SpolaFactory.kt` | Wire `ExternalStepExecutorRegistry` into `WorkflowFactory` |
| `SpolaApiServer.kt` | Register `ProcessRoutes` |
| `Main.kt` | Register `ProcessCommand` subcommand |
| `SpolaConfig.kt` | (Optional) Add `processDbPath` for gate store |

### NOT modified

- `SqliteKanbanStore.kt` / `KanbanTask.kt` — kanban stays until Phase 4
- `WorkflowSteps.kt` / `TeamWorkflowSteps.kt` — unchanged, templates use them
- `PersonaStore.kt` — unchanged, process engine can read persona
- `AgentFactory.kt` — unchanged
- `ShellTool.kt` — unchanged, plugin steps route through existing security

---

## 11. Key Design Decisions

1. **No YAML process definition parser.** Kotlin DSL only. YAML parsing may be added after the engine proves itself.
2. **No visual UI.** CLI + API only.
3. **Gates use `delayStep` + polling loop**, NOT `gateStep`. The synchronous `gateStep` cannot wait for external input.
4. **All `pluginStep` calls use explicit merge functions.** Default merge requires `Map` state, not `SpolaState`.
5. **Retry loops use retry counters in `SpolaState`**, not inline goto/loop constructs. `branchStep` is forward-only.
6. **Plugin steps route through existing shell security.** No bare `ProcessBuilder`. Use `ShellTool.kt` blocked commands/interpreters.
7. **Git commands use list-based execution** to prevent command injection via commit messages.
8. **Ketkanban is NOT removed.** It coexists. The process engine replaces the orchestration use case (automated pipelines). Kanban retains task management.

---

## 12. Risks

| Risk | Mitigation |
|------|------------|
| Process engine complexity trap (rebuilding n8n) | Start with 3 templates, 5 plugins. No YAML editor. No visual UI. |
| Plugin errors crash the process | TramAI's `Workflow` catches step failures. Failed steps can be retried or branched.|
| Gate polling loop consumes coroutines | Each gate uses one `delayStep` + one `localStep` per 5-second cycle. Bounded by max concurrent workflows (default 5). |
| Gate decision lost on restart | `GateDecisionStore` is SQLite-backed. Decisions survive restart. |
| Feature template infinite fix loop | Max retry count hard-coded at 3. Exceeded → abort path with Telegram notification. |
| Agent times out mid-process | TramAI `StopPolicy.maxStepExecutions` bounds agent runtime. Default 5 minutes per step. |
| YAML requested by users | Document as Phase 2 feature. Template catalog is still useful without YAML. |
