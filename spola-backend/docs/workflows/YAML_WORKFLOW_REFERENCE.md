# YAML Workflow Reference

> **Status:** ✅ Current — Schema and syntax reference for YAML-defined workflows in Spola.
> Features are marked as **Implemented** (production-ready), **Passthrough** (parsed but not compiled), or **Deferred** (future).

## File Location

Workflow YAML files live in `~/.spola/workflows/` and are auto-discovered at startup by `YamlWorkflowLoader`:

```
~/.spola/workflows/
├── code-review.yaml
├── jvm-debug.yaml
├── jvm-migration.yaml
└── my-custom-workflow.yaml     # <-- add yours here
```

No rebuild needed — add a YAML file and restart Spola.

> **Note:** Runtime hot-reload (`POST /api/workflows/yaml`) is deferred.

## Full Schema

```yaml
# ~/.spola/workflows/my-workflow.yaml
# ── Required ──────────────────────────────────────────────
name: my-workflow                     # lowercase, hyphens. Used as workflow ID.
version: "1"                          # optional, default "1"
description: "What this workflow does" # optional, shown in GET /api/workflows

# ── Parameters ─────────────────────────────────────────────
# These become available as {{params.name}} in step goals.
params:
  target:
    type: string
    required: true
    description: "Path to the project"
  files:
    type: string
    required: false
    default: "**/*.kt"
    description: "File glob pattern"

# ── Steps ──────────────────────────────────────────────────
steps:
  - id: step-one                      # unique, lowercase-hyphens
    type: ai                           # step type (see table below)
    persona: "Expert persona"          # system prompt for the AI
    goal: "Do something with {{params.target}}"
    depends_on: []                     # optional: step dependencies (topologically sorted at compile time)
    done:                              # optional: per-step completion criteria
      - condition: output_has_content   # supported condition name (see table)
    onError: fail                      # fail | continue (default fail)
    retryCount: 0                      # number of retries on failure (default 0)

  - id: step-two
    type: ai
    persona: "Different expert"
    goal: "Use result: {{step.step-one.output}}"
    depends_on: [step-one]

# ── Global Done ────────────────────────────────────────────
# Evaluated after all steps complete. Blocks workflow if conditions fail.
done:
  - condition: all_steps_passed
  - condition: report_generated
```

## Step Types

| Type | Status | Description | Input Fields |
|------|--------|-------------|-------------|
| `ai` | ✅ **Implemented** | Single AI agent step | persona, goal, depends_on |
| `parallel_agents` | ✅ **Implemented** | Multiple agents concurrently | agents, goal, persona, depends_on |
| `human_approval` | ✅ **Implemented** | Approval gate (always blocks — returns `GateDecision(allowed=false)`) | prompt, depends_on |
| `shell` | ✅ **Implemented** | Shell command execution via ProcessBuilder | command, timeout, depends_on, on_error, retry_count |
| `local` | ✅ **Implemented** | Local shell command execution (same as shell) | command, timeout, depends_on |
| `composite` | ✅ **Implemented** | Nested sub-workflow — resolves via `WorkflowTemplateRegistry`, runs with cycle detection and depth guard | workflow_ref, depends_on |
| `parallel` | ❌ **Deferred** | Parallel execution (V2 — different from `parallel_agents`) | items |
| `gate` | ❌ **Deferred** | Human approval gate (replaced by `human_approval`) | prompt |

### `ai` — AI Agent Step

Runs a SpolaAgent with a persona and goal. The agent has access to all configured tools.

```yaml
- id: security-scan
  type: ai
  persona: |
    You are a Kotlin security specialist.
    Use jvm_project_overview, jvm_symbol_search, and jvm_file_outline
    to analyze the project for vulnerabilities.
  goal: "Scan {{params.target}} for hardcoded secrets and injection risks"
  done:
    - condition: output_has_content
```

**How it works internally:**
The compiler calls `spolaAgentStep(name, persona, goal)` — the same Kotlin DSL function used by built-in templates. The DAG engine receives a step identical to one written in Kotlin.

### `parallel_agents` — Parallel Agent Execution

Runs multiple agents concurrently. Each agent has the same persona and goal.

```yaml
- id: parallel-review
  type: parallel_agents
  agents: ["security-reviewer", "style-reviewer", "test-reviewer"]
  goal: "Review code in {{params.target}}"
  persona: "You are a thorough code reviewer."
```

**How it works internally:**
The compiler calls `TeamWorkflowSteps.parallelAgentsStep(name, agents, goal, config)`. Results are joined with `\n---\n` separators.

### `human_approval` — Human Gate

Pauses execution at a gate that currently always blocks.

```yaml
- id: approve-changes
  type: human_approval
  prompt: "Review the changes above. Approve?"
```

**Current behavior:** Returns `GateDecision(allowed = false)` — the workflow transitions to WAITING_APPROVAL. The execution can be resumed via `GET /api/workflows/executions/{id}/approve` or `spola workflow approve <execution-id>`, which checkpoints the pre-gate state, patches it with an approval sentinel, and calls `workflow.resume()`.

### `shell` — Shell Command Execution

Executes a shell command via `ProcessBuilder` (`/bin/sh -c`). Captures stdout as the step result and stderr for error reporting.

```yaml
- id: compile
  type: shell
  command: "./gradlew :composeApp:compileKotlinDesktop"
  timeout: 120
  retry_count: 2
  on_error: continue
```

**How it works internally:**
The compiler calls `YamlWorkflowStepRunner.execute()` which uses a dedicated daemon thread pool for async stdout/stderr reading. Features:
- **Timeout enforcement**: Kills the process if it exceeds `timeout` seconds
- **Retry**: Re-runs up to `retry_count + 1` times on failure
- **Error handling**: `on_error: fail` (default) throws; `on_error: continue` returns `[ERROR] message` as step output
- **Output cap**: Capped at 10MB to prevent OOM. UTF-8 encoding enforced
- **Template resolution**: `{{step.X.output}}` resolved at runtime from prior step results

### `local` — Local Step Execution

Identical to `shell` in current implementation — runs a command via `/bin/sh -c`. The type distinction exists for future non-shell execution (direct Kotlin lambda).

```yaml
- id: transform
  type: local
  command: "printf '{{step.analyze.output}} transformed'"
```

### `composite` — Nested Sub-Workflow

Executes another YAML workflow as a nested sub-workflow. The sub-workflow's output becomes the composite step's result.

```yaml
- id: sub-workflow
  type: composite
  workflow_ref: my-other-workflow
```

**How it works internally:**
At compile time, the compiler resolves the referenced workflow from `WorkflowTemplateRegistry`, builds the sub-workflow DAG, and emits a `localStep` that runs it at runtime via `runBlocking { subWorkflow.run() }`.

**Safety features:**
- **Cycle detection:** Self-references (A→A) and cross-references (A→B→A) detected at compile time. Error shows the full chain path (e.g., `three-a -> three-b -> three-c -> three-a`).
- **Depth guard:** Maximum 10 levels of composite nesting enforced at runtime.
- **Timeout:** Sub-workflow execution timed via the step's `timeout` field (default 60s, min 10s).
- **Error boundary:** `try/catch` wraps sub-workflow execution with descriptive error messages.

**Limitations:**
- Sub-workflow params resolve to their own YAML defaults — parent params are not forwarded.
- Sub-workflow runs without observer/persistence/tracing (NoOp defaults). LLM provider calls still work via TramAI's ProviderRegistry.

## Template Variables

### Compile-Time Resolution (before execution)

| Variable | Resolves to | Example |
|----------|-------------|---------|
| `{{params.target}}` | Value from `WorkflowExecutionInput.parametersJson` | `~/Development/openclaw-app` |
| `{{params.files}}` | Value or default from `ParamDef` | `**/*.kt` |
| `{{state.goal}}` | The `goal` field from the API call or tool input | `review MainActivity.kt` |

### Runtime Resolution (during execution)

| Variable | Resolves to | Available after |
|----------|-------------|-----------------|
| `{{step.<id>.output}}` | Output of the named step | That step completes |

**Example: chaining step outputs:**

```yaml
steps:
  - id: analyze
    type: ai
    persona: "Analyst"
    goal: "Analyze {{params.target}}"

  - id: fix
    type: ai
    persona: "Fixer"
    goal: |
      Apply fixes based on this analysis:
      {{step.analyze.output}}
    depends_on: [analyze]
```

## Definition of Done

### Supported Condition Names

The `DoneConditionEvaluator` supports these condition types. Each condition is a name (string) paired with an optional value (string). Unknown conditions pass silently (logged as warning):

| Condition | Value | What it checks |
|-----------|-------|----------------|
| `output_has_content` | (none) | Step result is non-null and non-blank |
| `output_contains` | Regex pattern | Result matches the regex (case-insensitive) |
| `output_not_contains` | Regex pattern | Result does NOT match the regex |
| `all_agents_completed` | (none) | `intermediateResults` is non-empty |
| `all_steps_passed` | (none) | `result` is non-null |
| `no_critical_blockers` | (none) | Result doesn't contain CRITICAL/BLOCKER/SEVERE markers |
| `markdown_valid` | (none) | Result contains `##`, `` ``` ``, `- `, or `1. ` |
| `report_generated` | (none) | Result length >= 50 and contains words |

### Per-Step Conditions

Verified immediately after a step completes via a `gateStep` appended to the DAG:

1. Run the step (e.g., AI produces output)
2. Check all `done` conditions against the output
3. If any condition fails → the gate blocks the workflow
4. All pass → proceed to next step

```yaml
done:
  - condition: output_has_content
  - condition: output_contains "CRITICAL|HIGH|MEDIUM|LOW"
  - condition: markdown_valid
```

### Global Conditions

Workflow-level `done` is **evaluated by the compiler**. After all steps complete, the compiler inserts a `gateStep("workflow-done-check")` that evaluates all workflow-level done conditions against the final state. If any condition fails, the workflow gate blocks with `"Global done conditions not met for workflow '...'"`.

```yaml
done:
  - condition: all_steps_passed
  - condition: report_generated
```

## Example: Complete Workflow

```yaml
name: project-health
version: "1"
description: "Full project health check: security, style, dependencies"

params:
  target:
    type: string
    required: true
    description: "Project root path"

steps:
  - id: overview
    type: ai
    persona: "Software architect"
    goal: "Give me a project overview of {{params.target}}: language, framework, module structure, build system"

  - id: security-scan
    type: ai
    persona: "Security engineer"
    goal: |
      Scan {{params.target}} for:
      - Hardcoded API keys and secrets
      - Insecure dependencies
      - Permission issues
    done:
      - condition: output_has_content

  - id: style-audit
    type: ai
    persona: "Kotlin style expert"
    goal: "Review code quality in {{params.target}}: naming conventions, complexity, dead code"

  - id: dependency-check
    type: ai
    persona: "Dependency manager"
    goal: "Audit build.gradle.kts files in {{params.target}} for outdated or vulnerable dependencies"

  - id: report
    type: ai
    persona: "Technical writer"
    goal: |
      Aggregate these findings into a markdown report:

      ## Project Overview
      {{step.overview.output}}

      ## Security Issues
      {{step.security-scan.output}}

      ## Style Issues
      {{step.style-audit.output}}

      ## Dependency Issues
      {{step.dependency-check.output}}

done:
  - condition: all_steps_passed
  - condition: report_generated
```

## Calling a Workflow

### Via API

```bash
POST /api/workflows/run
Content-Type: application/json
Authorization: Bearer ***

{
  "workflowName": "project-health",
  "goal": "health check on composeApp",
  "inputJson": "{\"target\":\"/home/user/Development/my-project\"}"
}
```

Request fields:
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `workflowName` | string | ✅ | Name of the workflow (built-in or YAML-defined) |
| `goal` | string | ✅ | The user's goal string, stored as `{{state.goal}}` |
| `definitionId` | string | ❌ | Optional definition ID override |
| `sessionId` | string | ❌ | Associate execution with a chat session |
| `inputJson` | string | ❌ | JSON-serialized parameter map (default: `{}`) |

Response: HTTP 202

```json
{
  "executionId": "uuid"
}
```

Poll status:

```bash
GET /api/workflows/executions/{executionId}
```

List all:

```bash
GET /api/workflows/executions?limit=50
```

### Via CLI

```bash
spola workflow run project-health "health check on composeApp" --param target=/home/user/project
# Parameters are serialized as key=value pairs into parametersJson
```

List available workflows:

```bash
spola workflow list
```

### Via Agent

The agent has a single `workflow_run` tool that accepts a JSON payload:

```
User: "Run the health check on my openclaw-app project"
Agent: calls workflow_run with:
  {
    "workflowName": "project-health",
    "goal": "health check on openclaw-app",
    "inputJson": "{\"target\":\"~/Development/openclaw-app\"}"
  }
```

## Workflow Lifecycle States

```
QUEUED ──► RUNNING ──► COMPLETED
                │
                ├──► WAITING_APPROVAL ──► RUNNING (via approve API/CLI)
                │          │
                │          └──► CANCELLED
                │
                ├──► CANCEL_REQUESTED ──► CANCELLED
                │
                └──► FAILED (done condition violated or step error)
```

Status transitions are enforced by `WorkflowExecutionService`:
- `QUEUED` → `RUNNING` (claimed by dispatcher)
- `RUNNING` → `COMPLETED`, `FAILED`, `CANCEL_REQUESTED`, or `WAITING_APPROVAL`
- `WAITING_APPROVAL` → `RUNNING` (via approve API/CLI) or `CANCELLED`
- `CANCEL_REQUESTED` → `CANCELLED`
- Terminal: `COMPLETED`, `FAILED`, `CANCELLED`

> **Note:** The `WAITING_APPROVAL` → `RUNNING` resume path is wired via checkpoint-based resume. Use `POST /api/workflows/executions/{id}/approve` or `spola workflow approve <execution-id>` to resume.

## Error Handling

| Scenario | Behavior |
|----------|----------|
| AI step throws | Workflow FAILED with error message; persisted to `WorkflowExecutionRecord.error` |
| Per-step done condition fails | Gate blocks → workflow FAILED |
| Global done condition fails | Final gate blocks → workflow FAILED with `"Global done conditions not met"` |
| Shell step exits non-zero (`on_error: fail`) | Step throws `IllegalStateException` → workflow FAILED (after retries exhausted) |
| Shell step exits non-zero (`on_error: continue`) | Returns `[ERROR] message` as step output, workflow continues |
| Shell step timeout | Process killed, `IllegalStateException` thrown |
| Unknown done condition | Logged as warning, condition passes silently |
| Missing required param | Value defaults to empty string (logged as warning) |
| Unknown step type | Compile error: `IllegalArgumentException` with list of supported types |
|| Composite step type | Compile error if cycle detected; runtime error if depth exceeded; otherwise executes sub-workflow |
| YAML parse error | Workflow not registered; startup log shows warning |
| Rate limit / 429 | Bubbles up as step error → workflow FAILED |

## Migration: Built-in → YAML

Existing Kotlin templates can be exported:

```bash
spola workflow export code-review              # stdout
spola workflow export jvm-debug -o ~/.spola/workflows/jvm-debug.yaml  # to file
```

Result: YAML with correct `{{step.x.output}}` and `{{state.goal}}` syntax — editable, no rebuild needed.

Known built-in templates available for export: `code-review`, `jvm-debug`, `jvm-refactor`, `jvm-migration`.

Export generates best-effort YAML from hardcoded template metadata. It produces the correct step structure but may not capture every runtime detail (e.g., tool-specific prompts embedded in `TeamWorkflowSteps`).

## File Inventory (Implementation)

| File (under `spola-backend-core/.../workflow/yaml/`) | Lines | Purpose |
|------|-------|---------|
| `WorkflowDefinition.kt` | 91 | YAML schema data classes |
| `YamlWorkflowParser.kt` | ~130 | YAML → data classes (Jackson) |
| `WorkflowParameterResolver.kt` | 149 | `{{var}}` template resolution (compile-time + runtime) |
| `DoneConditionEvaluator.kt` | 106 | Per-step and global done condition checking |
| `YamlWorkflowDagSorter.kt` | 93 | Topological sort of `depends_on` dependencies (Kahn's algorithm) |
| `YamlWorkflowCompiler.kt` | 174 | Data classes → TramAI Workflow DAG (incl. shell/local step compilation) |
| `YamlWorkflowStepRunner.kt` | 149 | ProcessBuilder execution with timeout, retry, output cap, UTF-8 |
| `YamlWorkflowLoader.kt` | ~140 | Filesystem discovery + registration |
| `WorkflowExport.kt` | 265 | Built-in Kotlin → YAML export |

Also relevant:

| File | Purpose |
|------|---------|
| `WorkflowExecutionService.kt` | Enqueue + run + cancel executions |
| `WorkflowExecutionStore.kt` | SQLite persistence for execution records |
| `WorkflowDispatcher.kt` | Background poller with semaphore concurrency |
| `WorkflowTools.kt` | Single `workflow_run` tool for agent use |
| `WorkflowCommands.kt` (CLI) | `run`, `list`, `export` subcommands |
| `WorkflowRoutes.kt` (API) | `GET /workflows`, `POST /workflows/run`, execution history |

## What's Deferred

|- `parallel` and `gate` step types (use `parallel_agents` and `human_approval` instead)
|- `POST /api/workflows/yaml` runtime upload without restart
|- Visual editor (React Flow)
|- Workflow sharing / community registry
|- Per-step retry with exponential backoff (`retry_count` uses fixed-delay; V2 may add exponential)
|- `--all` flag on `spola workflow export`
