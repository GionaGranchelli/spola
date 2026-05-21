# Process Engine

## Overview

Spola's Process Engine is a deterministic DAG (Directed Acyclic Graph) runner. Unlike free-form agent conversations, process templates enforce a fixed execution plan where every step is typed, bounded, and predictable. The AI is just one node type in the graph alongside deterministic steps like `compile_project`, `git_commit`, and human approval gates.

**Why a Process Engine?**

Free-form agents are great for exploration but unreliable for production workflows. The process engine gives you:

- **Determinism** — The same template always runs the same steps in the same order
- **Repeatability** — Templates are stateless; every run starts from a clean state
- **Observability** — Every step is tracked, every gate decision is recorded
- **Safety** — Human gates can pause execution; failed steps trigger retry loops, not silent corruption

## Architecture

The process engine has four core components:

| Component | Role |
|-----------|------|
| **ProcessRunner** | Orchestrates template resolution, run lifecycle, gate polling, and retry loops |
| **ProcessTemplates** | Stateless workflow definitions using TramAI's `workflow` DSL |
| **GateDecisionStore** | SQLite-backed store for human approval decisions |
| **SpolaProcessPluginRegistry** | Registration of all deterministic plugin executors |

### ProcessRunner

`ProcessRunner` is the main entry point. It:

1. Resolves a template name to a `Workflow<SpolaState, SpolaState>` via `ProcessTemplates`
2. Creates a `SpolaState` with the given goal and config
3. Runs the workflow, handling three special results:

   - **`gate_pending`** — Polls `GateDecisionStore` every 5 seconds (max 1 hour) until a human approves or rejects
   - **`needs_retry`** — Increments the retry counter and re-runs from scratch (up to `maxRetries`)
   - **`gate_approved`** / **`gate_rejected`** / **`committed`** — Terminal states that complete the run

### Process Templates

Templates are defined in `ProcessTemplates` using TramAI's `workflow` DSL. Each template is a pure function that returns a `Workflow` — no mutable state, no side effects during construction.

**Critical architecture rule:** All conditional steps (fix, commit, revert, notify) must live *inside* `branchStep` branches, never as top-level steps. TramAI's execution engine runs every top-level step linearly. If a conditional step like `fix_compile` or `commit` is at the top level, it runs unconditionally, corrupting the workflow state even when the branch condition was not met.

### GateDecisionStore

SQLite-backed persistence for human approval gates. Uses Exposed ORM with a `gates` table keyed by `(runId, stepName)`. Supports:

- `awaitDecision(runId, stepName)` — Non-blocking poll (returns null if no decision yet)
- `decide(runId, stepName, decision, notes)` — Write a decision from CLI or API
- `hasExpired(runId, stepName, ttlSeconds)` — Check if a pending gate exceeded its TTL

### SpolaProcessPluginRegistry

Created at startup via `SpolaProcessPluginRegistry.create()`. Registers all built-in executor factories into an `ExternalStepExecutorRegistry`:

- `compile_project`
- `run_tests`
- `git_commit`
- `git_revert`
- `telegram_notify`

## Built-in Process Templates

### feature — 3-AI Pipeline with Review Gates

The full-feature workflow: implement → compile → review → final review → gate → commit.

**Flow:**
```
init → implement → verify_compile
  ├─ PASS → review → check_review
  │   ├─ PASS → final_review → check_final
  │   │   ├─ PASS → check_gate → gate_result
  │   │   │   ├─ APPROVED → commit → notify
  │   │   │   └─ REJECTED → revert
  │   │   └─ FAIL_RETRY → fix_review → retry
  │   └─ FAIL_RETRY → fix_review → retry
  └─ FAIL_RETRY → fix_compile → retry
```

**Usage:**
```bash
spola process run feature "Add rate limiting middleware to the API gateway"
```

The AI steps use these personas:
- **implement** — Expert software engineer implementing new features
- **review** — Senior code reviewer (correctness, security, style)
- **final_review** — Senior code reviewer doing final quality check

Compilation and review each get up to 3 retries. Failed retries abort with `aborted_max_retries`.

### hotfix — Fast Track, No Review

Shortcut workflow for urgent fixes: implement → compile → test → gate → commit.

**Flow:**
```
init → implement → verify_compile
  ├─ PASS → verify_tests
  │   ├─ PASS → check_gate → gate_result
  │   │   ├─ APPROVED → commit → notify
  │   │   └─ REJECTED → revert
  │   └─ FAIL_RETRY → fix_test → retry
  └─ FAIL_RETRY → fix_compile → retry
```

**Usage:**
```bash
spola process run hotfix "Fix null pointer in user login endpoint"
```

No code review step. Faster turnaround. The AI persona emphasizes "quickly and safely."

### refactor — Plan-First, Dual Gates

Risk-aware workflow for codebase changes: analyze → plan gate → implement → compile → test → review → final gate → commit.

**Flow:**
```
init → analyze → check_plan_gate
  ├─ APPROVED → implement → verify_compile → verify_tests → review → check_final_gate
  │   ├─ APPROVED → commit → notify
  │   └─ REJECTED → revert
  └─ REJECTED → abort
```

**Usage:**
```bash
spola process run refactor "Extract payment processing into a separate module"
```

Two human gates: `plan_gate` (approve the analysis before code is written) and `final_gate` (approve the final result). The analyze step uses an "architect" persona and explicitly avoids making changes.

## Plugin Executors

### compile_project

Compiles a Gradle project. Runs `./gradlew <project>:<tasks>`.

| Config | Default | Description |
|--------|---------|-------------|
| `project` | *required* | Gradle project path, e.g. `:spola-backend-core` |
| `tasks` | `compileKotlin compileJava` | Gradle tasks to run |

**Result map:**
```kotlin
{
  "exit_code": 0,
  "output": "BUILD SUCCESSFUL...",
  "passed": true
}
```

**Example in a template:**
```kotlin
pluginStep(
    name = "verify_compile",
    type = "compile_project",
    config = mapOf("tasks" to "compileKotlin"),
) { state, result, _ ->
    val passed = result["passed"] as? Boolean ?: false
    state.copy(
        result = if (passed) "verify_passed" else "verify_failed",
        intermediateResults = state.intermediateResults + mapOf(
            "verify_output" to (result["output"] as? String ?: ""),
        ),
    )
}
```

### run_tests

Runs tests for a Gradle project. Runs `./gradlew <project>:test --no-build-cache`.

| Config | Default | Description |
|--------|---------|-------------|
| `project` | *required* | Gradle project path, e.g. `:spola-backend-core` |

**Result map:**
```kotlin
{
  "exit_code": 0,
  "output": "...tests passed...",
  "passed": true
}
```

**Example:**
```kotlin
pluginStep(
    name = "verify_tests",
    type = "run_tests",
    config = emptyMap(),
) { state, result, _ ->
    val passed = result["passed"] as? Boolean ?: false
    state.copy(result = if (passed) "test_passed" else "test_failed")
}
```

### git_commit

Stages all changes and commits. Uses list-based git commands (no shell injection).

| Config | Default | Description |
|--------|---------|-------------|
| `message` | `"Auto-commit from Spola Process Engine"` | Commit message |

**Result map:**
```kotlin
{
  "exit_code": 0,
  "hash": "abc1234",
  "passed": true
}
```

**Example:**
```kotlin
pluginStep(
    name = "commit",
    type = "git_commit",
    config = mapOf("message" to "feat: auto-commit from process engine"),
) { state, result, _ ->
    val exitCode = result["exit_code"] as? Long ?: -1L
    state.copy(
        result = if (exitCode == 0L) "committed" else "commit_failed",
        intermediateResults = state.intermediateResults + mapOf(
            "commit_hash" to (result["hash"] as? String ?: ""),
        ),
    )
}
```

### git_revert

Reverts all local changes. Runs `git checkout -- .` using list-based commands (no shell injection). No config required.

**Result map:**
```kotlin
{
  "exit_code": 0
}
```

**Example:**
```kotlin
pluginStep(
    name = "revert",
    type = "git_revert",
    config = emptyMap(),
) { state, result, _ ->
    val exitCode = result["exit_code"] as? Long ?: -1L
    state.copy(
        result = if (exitCode == 0L) "reverted" else "revert_failed",
        currentPhase = if (exitCode == 0L) "abort" else "revert_failed",
    )
}
```

### telegram_notify

Sends a Telegram notification (currently a stub that prints to stdout).

| Config | Default | Description |
|--------|---------|-------------|
| `message` | `""` | Notification text |

**Result map:**
```kotlin
{
  "sent": true
}
```

**Example:**
```kotlin
pluginStep(
    name = "notify",
    type = "telegram_notify",
    config = mapOf("message" to "Feature process completed"),
) { state, result, _ ->
    val sent = result["sent"] as? Boolean ?: false
    state.copy(
        intermediateResults = state.intermediateResults + mapOf(
            "notification_sent" to sent.toString(),
        ),
    )
}
```

### ShellRunner

Low-level security-safe shell runner used by all plugin executors. Key properties:

- **List-based commands only** — No shell parsing, no injection risk
- **Blocked commands** — `sudo`, `su`, `passwd`, `chown`, `chmod`, `mount`, `umount`, `mkfs`, `dd`, `fdisk`, `parted`, `reboot`, `shutdown`, `halt`, `poweroff`, `init`, `killall`, `pkill`
- **Blocked interpreters** — `bash`, `sh`, `zsh`, `dash`, `ksh`, `fish`, `python`, `python3`, `perl`, `ruby`, `lua`, `node`, `nodejs`, `deno`, `php`
- **Timeout enforcement** — Default 60s, configurable per call
- **String convenience overload** — Parses quoted args safely

## Gate Mechanism

Gates are human approval checkpoints that pause process execution until a decision is made.

### How Gating Works

1. The workflow hits a `localStep` that checks `GateDecisionStore.awaitDecision(runId, stepName)`
2. If no decision exists, the workflow returns `result = "gate_pending"`
3. `ProcessRunner` detects `gate_pending` and enters a polling loop (5s interval, 1h timeout)
4. A human uses CLI or API to `approve` or `reject`
5. The next poll finds the decision and re-runs the workflow with the decision injected
6. The workflow branches on the result: approved → commit, rejected → revert

### Gate Step Names

Each template uses specific gate step names:

| Template | Gate Step Names |
|----------|----------------|
| feature | `gate` |
| hotfix | `gate` |
| refactor | `plan_gate`, `final_gate` |

### CLI Gate Commands

```bash
# Approve a gate (step name "gate")
spola process approve <run-id> --notes "LGTM"

# Reject a gate
spola process reject <run-id> --notes "Needs more tests"

# For refactor plan gates, step name is "plan_gate"
spola process approve <run-id> --notes "Plan approved"

# For refactor final gates, step name is "final_gate"
spola process reject <run-id> --notes "Revert and rethink"
```

## CLI Reference

```
spola process list          — List available templates
spola process run <tmpl> <goal>  — Run a process template
spola process status <id>   — Check run status
spola process cancel <id>   — Cancel a running process
spola process approve <id>  — Approve a gate decision
spola process reject <id>   — Reject a gate decision
```

### Examples

```bash
# List available templates
spola process list
# Output: Available process templates: feature, hotfix, refactor

# Run a feature process
spola process run feature "Add request logging interceptor"
# Output: Run: proc_a1b2c3d4
#   template: feature-process
#   status: RUNNING
#   result: n/a

# Check status
spola process status proc_a1b2c3d4
# Output: Run: proc_a1b2c3d4
#   template: feature-process
#   status: RUNNING
#   step: gate

# Approve the gate
spola process approve proc_a1b2c3d4 --notes "Looks good"

# Cancel if needed
spola process cancel proc_a1b2c3d4
```

## API Reference

### POST /api/processes/run

Start a process run.

**Request:**
```json
{
  "template": "feature",
  "goal": "Add rate limiting middleware",
  "project": ":spola-backend-core"
}
```

**Response:**
```json
{
  "runId": "proc_a1b2c3d4",
  "template": "feature-process",
  "goal": "Add rate limiting middleware",
  "status": "RUNNING",
  "currentStepName": null,
  "result": null,
  "createdAt": 1700000000000,
  "completedAt": null
}
```

### GET /api/processes/status/{runId}

Get the current status of a process run.

**Response:**
```json
{
  "runId": "proc_a1b2c3d4",
  "template": "feature-process",
  "goal": "Add rate limiting middleware",
  "status": "RUNNING",
  "currentStepName": "gate",
  "result": null
}
```

### POST /api/processes/cancel/{runId}

Cancel a running process.

**Response:** `{ "cancelled": true }`

### POST /api/gates/{runId}/{stepName}/decide

Submit a human decision for a gate.

**Request:**
```json
{
  "decision": "APPROVED",
  "notes": "LGTM, proceed with commit"
}
```

**Response:** `{ "decided": true }`

## Creating Custom Templates

To add a new process template, extend `ProcessTemplates`:

```kotlin
object ProcessTemplates {
    fun deployWorkflow(
        pluginRegistry: ExternalStepExecutorRegistry,
        gateStore: GateDecisionStore,
    ): Workflow<SpolaState, SpolaState> {
        return workflow<SpolaState>("deploy-process", "1.0") {
            localStep("init") { state, _ ->
                state.copy(currentPhase = "build", result = "")
            }

            // Build step
            pluginStep(
                name = "build",
                type = "compile_project",
                config = mapOf("tasks" to "build"),
            ) { state, result, _ ->
                state.copy(
                    result = if ((result["passed"] as? Boolean) == true)
                        "build_passed" else "build_failed",
                )
            }

            // Verify
            pluginStep(
                name = "run_tests",
                type = "run_tests",
                config = emptyMap(),
            ) { state, result, _ ->
                state.copy(
                    result = if ((result["passed"] as? Boolean) == true)
                        "test_passed" else "test_failed",
                )
            }

            // Gate before deploy
            localStep("check_gate") { state, ctx ->
                val decision = gateStore.awaitDecision(ctx.workflowId, "gate")
                when {
                    decision != null && decision.decision.equals("APPROVED", ignoreCase = true) ->
                        state.copy(result = "gate_approved", currentPhase = "deploy")
                    decision != null ->
                        state.copy(result = "gate_rejected", currentPhase = "abort")
                    else ->
                        state.copy(result = "gate_pending", currentPhase = "gate_pending")
                }
            }

            // Route gate result
            branchStep("gate_result", select = { it.result ?: "gate_pending" }) {
                branch("gate_approved") {
                    pluginStep(
                        name = "notify",
                        type = "telegram_notify",
                        config = mapOf("message" to "Deploy approved!"),
                    ) { state, _, _ -> state }
                }
                branch("gate_rejected") {
                    localStep("abort") { state, _ ->
                        state.copy(result = "deploy_rejected", currentPhase = "abort")
                    }
                }
                branch("gate_pending") {
                    localStep("pending") { state, _ ->
                        state.copy(currentPhase = "gate_pending")
                    }
                }
            }
        }.build(
            externalStepExecutorResolver = pluginRegistry,
        ) { state -> state }
    }
}
```

Then register it in `ProcessRunner.resolveWorkflow()`:

```kotlin
private fun resolveWorkflow(template: String): Workflow<SpolaState, SpolaState> {
    return when (template.lowercase()) {
        "feature" -> ProcessTemplates.featureWorkflow(pluginRegistry, gateStore)
        "hotfix" -> ProcessTemplates.hotfixWorkflow(pluginRegistry, gateStore)
        "refactor" -> ProcessTemplates.refactorWorkflow(pluginRegistry, gateStore)
        "deploy" -> ProcessTemplates.deployWorkflow(pluginRegistry, gateStore)  // NEW
        else -> throw IllegalArgumentException("...")
    }
}
```

## Creating Custom Plugin Executors

Implement `ExternalStepExecutorFactory`:

```kotlin
class DockerBuildExecutorFactory : ExternalStepExecutorFactory {
    override val typeId: String = "docker_build"

    override fun create(): ExternalStepExecutor = ExternalStepExecutor { spec ->
        val tag = spec["tag"] as? String ?: "latest"
        val dockerfile = spec["dockerfile"] as? String ?: "Dockerfile"

        val result = runShell(
            listOf("docker", "build", "-t", tag, "-f", dockerfile, "."),
            timeoutSeconds = 600L,
        )
        mapOf(
            "exit_code" to result.exitCode,
            "output" to result.output.take(2000),
            "passed" to (result.exitCode == 0),
        )
    }
}
```

Register it:

```kotlin
val registry = SpolaProcessPluginRegistry.create()
registry.register(DockerBuildExecutorFactory())
```

Then use it in any template:

```kotlin
pluginStep(
    name = "docker_build",
    type = "docker_build",
    config = mapOf("tag" to "my-app:latest"),
) { state, result, _ ->
    state.copy(
        result = if ((result["passed"] as? Boolean) == true)
            "build_passed" else "build_failed",
    )
}
```
