# YAML Workflow Definition System

> **Status:** ✅ Current — Architecture and implementation reference.
> All features described are shipped: shell/local step execution, topological sort, global done evaluation.
> See "What's Deferred" for features still on the roadmap.

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│  ~/.spola/workflows/*.yaml                          │
│  ┌───────────────────────────────────────────┐      │
│  │ name: code-review                          │      │
│  │ version: 1                                 │      │
│  │ params: ...                                │      │
│  │ steps: [...]                               │      │
│  │ done: [...]                                │      │
│  └───────────────────────────────────────────┘      │
│                      │                               │
│                      ▼                               │
│  ┌──────────────────────────────────────┐            │
│  │ YamlWorkflowParser                   │            │
│  │  - Parses YAML → WorkflowDefinition  │            │
│  │  - Validates schema                  │            │
│  └──────────┬───────────────────────────┘            │
│             │                                       │
│             ▼                                        │
│  ┌──────────────────────────────────────┐            │
│  │ YamlWorkflowCompiler                 │            │
│  │  - Resolves {{params}}               │            │
│  │  - Converts steps → DAG              │            │
│  │  - Attaches done-condition gates     │            │
│  │  - Produces Workflow<SpolaState>     │            │
│  └──────────┬───────────────────────────┘            │
│             │                                       │
│             ▼                                        │
│  ┌──────────────────────────────────────┐            │
│  │ WorkflowTemplateRegistry             │            │
│  │  - Built-in templates (Kotlin)       │            │
│  │  + YAML workflows (file-discovered)  │            │
│  └──────────┬───────────────────────────┘            │
│             │                                       │
│             ▼                                        │
│  ┌──────────────────────────────────────┐            │
│  │ WorkflowExecutionService             │            │
│  │  - Resolves template by name         │            │
│  │  - Calls build(goal, params)         │            │
│  │  - Runs via WorkflowFactory          │            │
│  └──────────────────────────────────────┘            │
└─────────────────────────────────────────────────────┘
```

## YAML Schema

### Top-Level Structure

```yaml
# ~/.spola/workflows/code-review.yaml
name: code-review                    # Required: workflow name (lowercase, hyphens)
version: "1"                         # Optional: version string (default: "1")
description: "Multi-reviewer code review with security, style, and test analysis"
params:                              # Optional: parameter definitions
  files:
    type: string
    description: "File glob to review"
    default: "**/*.kt"
  reviewers:
    type: array
    description: "List of reviewer agent IDs"
    default: ["security-reviewer", "style-reviewer", "test-reviewer"]

steps:                               # Required: at least one step
  - id: parallel-review
    type: parallel_agents             # Step type
    agents: "{{params.reviewers}}"
    goal: "Review code in {{params.files}} for the project: {{state.goal}}"
    persona: "You are a thorough code reviewer."
    invoke: spola                     # Which engine to use
    done:                            # Definition of Done for this step
      - condition: "all_agents_completed"
      - condition: "output_has_content"

  - id: summarize
    type: ai
    depends_on: [parallel-review]     # DAG dependency
    goal: |
      Aggregate the parallel review results into a final summary.
      Security review: {{step.parallel-review.output}}
    persona: "You are a technical editor."
    done:
      - condition: "output_contains" "CRITICAL|HIGH|MEDIUM|LOW"
      - condition: "markdown_valid"

done:                                # Overall Definition of Done
  - condition: "all_steps_passed"
  - condition: "report_generated"
  - condition: "no_critical_blockers"
```

### Step Types

| Type | Status | Description | Input Fields |
|------|--------|-------------|-------------|
| `ai` | ✅ Implemented | Single AI agent step | persona, goal, depends_on |
| `parallel_agents` | ✅ Implemented | Multiple agents concurrently | agents, goal, persona, depends_on |
| `human_approval` | ✅ Implemented | Approval gate (always blocks) | prompt, depends_on |
| `shell` | ✅ Implemented | Shell command execution via ProcessBuilder | command, timeout, depends_on, on_error, retry_count |
| `composite` | ✅ **Implemented** | Nested sub-workflow (registry-resolved, cycle detection, depth guard) | workflow_ref, depends_on |
| `local` | ✅ Implemented | Local shell command execution (same as shell) | command, timeout, depends_on |

### Definition of Done Conditions

| Condition | Value | What it checks |
|-----------|-------|----------------|
| `all_agents_completed` | (none) | Parallel step: all sub-tasks finished |
| `output_has_content` | (none) | Step output is non-null and non-blank |
| `output_contains` | Regex pattern | Output matches regex (case-insensitive) |
| `output_not_contains` | Regex pattern | Output does NOT match regex |
| `markdown_valid` | (none) | Output contains `##`, `` ``` ``, `- `, or `1. ` |
| `all_steps_passed` | (none) | All steps completed without error |
| `report_generated` | (none) | Final result is non-empty + formatted |
| `no_critical_blockers` | (none) | Output doesn't contain CRITICAL/ERROR/FATAL |

### Parameter Resolution

Use `{{variable}}` syntax throughout step fields:

| Variable | Source | Resolved at | Example |
|----------|--------|-------------|---------|
| `{{params.X}}` | From YAML `params` section, overridden at runtime | Compile time | `{{params.files}}` |
| `{{state.goal}}` | The user's run goal | Compile time | `Review the auth module` |
| `{{step.X.output}}` | Output of a previous step | Runtime (during execution) | `{{step.analyze.output}}` |

Resolution uses `kotlin.text.replace()` with simple regex — no Mustache library needed.

> `{{state.X}}` for arbitrary fields (e.g., `{{state.intermediateResults}}`) is **not supported** — only `{{state.goal}}` and `{{params.X}}` resolve at compile time. `{{step.X.output}}` resolves at runtime via `WorkflowParameterResolver.resolveRuntimeTemplates()`.

## Implementation Status

### Files Created (all exist, all functional)

| File | Purpose | Status |
|------|---------|--------|
| `WorkflowDefinition.kt` | Data classes for YAML schema | ✅ Complete |
| `YamlWorkflowParser.kt` | Parse YAML → data classes | ✅ Complete |
| `YamlWorkflowCompiler.kt` | Data classes → TramAI Workflow DAG | ✅ Complete (ai, parallel_agents, human_approval, shell, local, composite) |
| `WorkflowParameterResolver.kt` | `{{var}}` template resolution | ✅ Complete (compile-time + runtime) |
| `DoneConditionEvaluator.kt` | Per-step done condition verification | ✅ Complete (8 conditions) |
| `YamlWorkflowLoader.kt` | File discovery + registration | ✅ Complete |
| `WorkflowExport.kt` | Kotlin template → YAML export | ✅ Complete (4 built-in templates) |

### Files Modified (existing)

| File | Change | Status |
|------|--------|--------|
| `WorkflowTemplateRegistry.kt` | Register YAML workflows alongside built-ins | ✅ Complete |
| `WorkflowExecutionService.kt` | Pass parameters through to template build | ✅ Complete |
| `SpolaConfig.kt` | `workflowsDir` config property | ✅ Complete |
| `WorkflowCommands.kt` | `spola workflow export` command | ✅ Complete |
| `WorkflowTemplateRegistry.kt` | Auto-discover YAML files on startup | ✅ Complete |

### Execution Infrastructure (beyond YAML)

| Component | Purpose | Status |
|-----------|---------|--------|
| `WorkflowExecutionService` | Enqueue, run, cancel, get executions | ✅ Complete |
| `WorkflowExecutionStore` | SQLite persistence for executions | ✅ Complete |
| `AsyncWorkflowDispatcher` | Background poller with semaphore concurrency | ✅ Complete |
| `WorkflowExecutionModels` | Status enum + record data classes | ✅ Complete |
| `WorkflowRoutes` (API) | HTTP endpoints for workflows | ✅ Complete |
| `WorkflowTools` | `workflow_run` agent tool | ✅ Complete |
| `WorkflowSchedulerService` | Scheduler → workflow trigger | ✅ Complete |
| `WorkflowKanbanService` | Kanban → workflow trigger | ✅ Complete |
| `WorkflowChatService` | Chat session → workflow integration | ✅ Complete |
| `WorkflowSessionRoutes` | Session-bound execution history | ✅ Complete |

### Minimum Viable Change (MVC) — All Complete

The MVC is implemented and functional:

1. ✅ `WorkflowDefinition.kt` — schema data classes
2. ✅ `YamlWorkflowParser.kt` — parse YAML via Jackson YAML mapper
3. ✅ `YamlWorkflowCompiler.kt` — convert to `Workflow<SpolaState, String>` using TeamWorkflowSteps
4. ✅ `WorkflowParameterResolver.kt` — `{{var}}` -> value substitution
5. ✅ `WorkflowTemplateRegistry.kt` — scan `~/.spola/workflows/` and register
6. ✅ `WorkflowExecutionService.kt` — pass parameters to template build

## Concrete Step: YAML → DAG Compilation

### How `YamlWorkflowCompiler` works

The parser reads YAML into `WorkflowDefinition` data classes. The compiler then calls the *same* Kotlin DSL functions that the hardcoded templates use — it just generates them programmatically.

```kotlin
fun compile(def: WorkflowDefinition, params: Map<String, Any?>): Workflow<SpolaState, String> {
    val resolved = WorkflowParameterResolver.resolve(def, params)
    return WorkflowFactory.createWorkflow(
        name = resolved.name,
        version = resolved.version,
        workflow = {
            val stepIds = mutableListOf<String>()
            for (step in resolved.steps) {
                stepIds.add(step.id)
                when (step.type) {
                    "ai" -> spolaAgentStep(
                        name = step.id,
                        persona = { step.persona ?: "You are a helpful assistant." },
                        goal = { step.goal },
                    )
                    "parallel_agents" -> parallelAgentsStep(
                        name = step.id,
                        agents = step.agents ?: emptyList(),
                        goal = { step.goal },
                        config = state.config,
                    )
                    "human_approval" -> humanApprovalGate(
                        name = step.id,
                        decide = { state, context ->
                            GateDecision(allowed = false, reason = step.prompt ?: "Awaiting approval")
                        },
                    )
                    else -> throw IllegalArgumentException("Unknown step type: ${step.type}")
                }
                // Attach done-condition gate after each step
                if (step.done.isNotEmpty()) {
                    gateStep(name = "${step.id}-done-check") { state, _ ->
                        val passed = DoneConditionEvaluator.evaluate(step.done, state)
                        GateDecision(allowed = passed, reason = if (passed) null else "Done conditions not met")
                    }
                }
            }
        },
        resultSelector = { state -> state.result ?: "no result" },
    )
}
```

This is the key insight: **We don't change the engine.** We write a compiler that emits the same Kotlin DSL calls the hardcoded templates make. The engine stays exactly as-is.

## Definition of Done Mechanism

Done conditions are evaluated after each step by `DoneConditionEvaluator`:

```yaml
done:
  - condition: output_contains "CRITICAL"
  - condition: markdown_valid
```

The evaluator:
1. Reads the condition name
2. Applies the check against current `SpolaState` (result, intermediateResults)
3. Returns `true`/`false`

If a step's done conditions fail, the gate blocks progression and the workflow fails.

> **Note:** Workflow-level `done` (declared at the top level) is parsed and **evaluated by the compiler** — it appends a global gate that fires after all steps.

## Error Handling Strategy

| Failure | Behavior | Notes |
|---------|----------|-------|
| Done condition fails | Gate blocks → workflow FAILED | `onError: fail` is the default; `continue` passes with `[ERROR]` prefix |
| Step execution throws | Workflow marked FAILED; error persisted | Error stored in `WorkflowExecutionRecord.error` |
| Unknown step type | Compile error thrown | `IllegalArgumentException` with list of supported types |
| Composite step | Compile error if cycle detected; runtime error if depth exceeded | A→B→A detected at compile time; max 10 depth; withTimeout |
| {{param}} missing | Defaults to empty string if not required; logged as warning | `required` flag on `ParamDef` |
| Unknown done condition | Warning logged, condition passes silently | May mask real failures |
| YAML parse error | Workflow not registered; startup log shows warning | No crash, user must fix and restart |

## Visual Editor Compatibility

The YAML format IS the serialization format. A visual editor (like a mini n8n) would:
1. Load YAML → populate canvas
2. User drags/drops steps
3. Save canvas → YAML

Bidirectional: `YAML ↔ Canvas`. The YAML file is the source of truth.

## Exporting Existing Kotlin Templates

`WorkflowExport` generates YAML for known built-in templates using hardcoded templates rather than walking the runtime DAG structure:

```kotlin
fun exportTemplate(registry: WorkflowTemplateRegistry, name: String): String? {
    return when (name) {
        "code-review" -> exportCodeReviewYaml()
        "jvm-debug" -> exportJvmDebugYaml()
        "jvm-refactor" -> exportJvmRefactorYaml()
        "jvm-migration" -> exportJvmMigrationYaml()
        else -> generateGenericYaml(name, template)
    }
}
```

This lets users do:
```bash
spola workflow export jvm-debug > ~/.spola/workflows/jvm-debug.yaml
spola workflow edit jvm-debug  # opens YAML in $EDITOR
```

## Skill Integration (Proposed)

**Future feature — not yet implemented.** A skill definition (SKILL.md) could optionally include a `workflow` field that references a YAML workflow file:

```yaml
# ~/.spola/skills/devops/deploy/SKILL.md
---
name: deploy
description: Deploy to production
workflow: ~/.spola/workflows/deploy.yaml
---
The deploy workflow uses 3 stages: build, test, deploy.
```

When a skill has a `workflow` reference, running the skill would invoke the workflow instead of injecting a system prompt. This bridges the gap between "skill as persona" and "workflow as process".

> **Implementation status:** The agent currently has a `workflow_run` tool but no `skill_create` or `workflow_create` tools. Skill→workflow binding is a design concept, not implemented.
