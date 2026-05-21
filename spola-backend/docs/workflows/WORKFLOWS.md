# Workflows

> **Status:** Current — Documents the existing Kotlin DSL engine powered by TramAI's workflow module.
> Some sections (SpolaState fields, API endpoints) have been corrected to match the current implementation.

## Overview

Spola's workflow system provides multi-step orchestration using TramAI's workflow DSL. While the **Process Engine** (see `PROCESS_ENGINE.md`) is a deterministic DAG for production automation, workflows are more flexible and are designed for AI-led, multi-step, multi-agent operations.

**When to Use Which**

| Aspect | Process Engine | Workflow |
|--------|---------------|----------|
| Flow | Deterministic DAG, fixed steps | Flexible, AI-guided |
| AI Role | One node type among many | Primary executor |
| Retry | Automatic retry loops | Manual via branchStep |
| Gates | SQLite polling (ProcessRunner) | Direct gateStep |
| Use Case | Compile → test → gate → commit | Code review teams, debugging pipelines |
| Templates | feature, hotfix, refactor | jvmDebug, jvmRefactor, jvmMigration, codeReview |

## Architecture

The workflow system sits on top of TramAI's `tramai-orchestration` module. Key components:

| Component | Role |
|-----------|------|
| **Workflow<S, R>** | Typed workflow with state type S and result type R |
| **WorkflowBuilder<S>** | DSL builder for constructing workflows with steps |
| **AbstractWorkflowBuilder<S>** | Base class with all step DSL functions (local, ai, shell, http, branch, gate, parallel, mcp, plugin, delay) |
| **SpolaState** | Spola-specific workflow state with goal, config, agent, conversation, retry tracking |
| **SpolaWorkflowObserver** | Bridges TramAI's observer to Spola's metrics, tracing, and SSE events |
| **SpolaWorkflowStateCodec** | Jackson-based state serialization for checkpoint/resume |

### SpolaState

The state object that flows through every workflow step:

```kotlin
data class SpolaState(
    val goal: String,                        // The user's goal
    val config: SpolaConfig,                 // Spola configuration
    val agentDef: AgentDefinition?,          // Optional agent definition for per-step agents
    val conversation: List<ChatMessage>,     // Conversation history
    val turnCount: Int,                      // Number of turns
    val intermediateResults: Map<String, String>,  // Step results keyed by step name
    val result: String?,                     // Current result string
    val workflowNestingDepth: Int = 0,       // Nesting depth for sub-workflow recursion guard
)
```

> **Note:** `retryCount`, `maxRetries`, `currentPhase`, and `perStepAgent` were documented previously but are not in the current data class. They were removed during a refactoring pass. See `SpolaState.kt` for the ground truth.

### SpolaWorkflowObserver

Integrates workflow events into Spola's observability stack:

- **AgentRunObserver** — Real-time SSE events (`workflow_started`, `step_started`, `step_completed`, `workflow_completed`)
- **SpolaMetrics** — Prometheus metrics recording step durations and tool call statuses
- **SpolaTracer** — OpenTelemetry tracing with root spans per workflow and automatic error reporting

### SpolaWorkflowStateCodec

Jackson-based codec for serializing `SpolaState` to/from JSON. Used for checkpoint/resume:

```kotlin
val codec = SpolaWorkflowStateCodec()
val json = codec.encode(state)    // SpolaState → JSON string
val restored = codec.decode(json) // JSON string → SpolaState
```

## Step Types

TramAI's workflow DSL provides these step types. All are available from `AbstractWorkflowBuilder<S>`.

### localStep

Pure state transformation. No side effects.

```kotlin
localStep("init") { state, _ ->
    state.copy(currentPhase = "analyze", result = "")
}
```

### aiStep

Calls an LLM with a typed input and merges the result back into state.

```kotlin
aiStep(
    name = "summarize",
    input = { state ->
        "Summarize these findings: ${state.intermediateResults["review"]}"
    },
    invoke = { input -> llmCall(input) },
    merge = { state, summary -> state.copy(result = summary) },
)
```

Spola wraps this as `spolaAgentStep` (see below).

### shellStep

Executes a shell command with full security controls.

```kotlin
shellStep(
    name = "check-disk",
    config = ShellStepConfig(timeoutSeconds = 30),
    definition = ShellCommandDefinition("df"),
    command = { _, _ -> ShellCommand(listOf("df", "-h")) },
    merge = { state, result, _ ->
        state.copy(
            intermediateResults = state.intermediateResults + (
                "disk_usage" to result.stdout
            ),
        )
    },
)
```

### httpStep

Makes an HTTP request.

```kotlin
httpStep(
    name = "fetch-status",
    request = { _, _ ->
        HttpRequest(uri = "https://api.example.com/status")
    },
    merge = { state, response, _ ->
        state.copy(
            intermediateResults = state.intermediateResults + (
                "api_status" to response.body
            ),
        )
    },
)
```

### branchStep

Conditional branching. Steps inside branches only execute when the branch is selected.

```kotlin
branchStep("check_result", select = { state ->
    when {
        state.result?.contains("PASS") == true -> "PASS"
        state.retryCount < state.maxRetries -> "RETRY"
        else -> "ABORT"
    }
}) {
    branch("PASS") {
        localStep("passed") { state, _ ->
            state.copy(currentPhase = "done")
        }
    }
    branch("RETRY") {
        localStep("retry_mark") { state, _ ->
            state.copy(result = "needs_retry")
        }
    }
    branch("ABORT") {
        localStep("abort") { state, _ ->
            state.copy(result = "aborted", currentPhase = "abort")
        }
    }
    default {
        localStep("noop") { state, _ -> state }
    }
}
```

**Critical rule:** All steps that should only execute on a specific path MUST live inside `branch("KEY") { ... }` blocks. Top-level steps run unconditionally.

### gateStep

Human approval gate. Returns `GateDecision(allowed, reason)`.

```kotlin
gateStep(name = "approve-deploy") { state, context ->
    // In production, this would poll an external system
    GateDecision(allowed = true, reason = "Auto-approved for demo")
}
```

### parallelStep

Run operations concurrently. Results merged as a list.

```kotlin
parallelStep(
    name = "lint-all",
    items = { state -> listOf("src/main", "src/test") },
    invoke = { dir -> runLinter(dir) },
    merge = { state, results ->
        state.copy(
            intermediateResults = state.intermediateResults + (
                "lint_results" to results.joinToString("\n")
            ),
        )
    },
)
```

### mcpStep

Call an MCP tool from within a workflow.

```kotlin
mcpStep(
    name = "search-code",
    definition = McpToolCallDefinition(
        serverName = "filesystem",
        toolName = "search",
    ),
    toolCall = { state, _ ->
        McpToolCall(arguments = mapOf("pattern" to state.goal))
    },
    merge = { state, result, _ ->
        state.copy(
            intermediateResults = state.intermediateResults + (
                "search_results" to result.content
            ),
        )
    },
)
```

### pluginStep

Execute a registered plugin executor (same ones used by the Process Engine).

```kotlin
pluginStep(
    name = "compile",
    type = "compile_project",
    config = mapOf("tasks" to "compileKotlin"),
) { state, result, _ ->
    val passed = result["passed"] as? Boolean ?: false
    state.copy(result = if (passed) "ok" else "failed")
}
```

### delayStep

Wait for a specified duration.

```kotlin
delayStep(name = "wait-for-deploy", duration = 30, unit = TimeUnit.SECONDS)
```

## Built-in Templates

### jvmDebug

Two-step JVM debugging pipeline:
1. `scan-and-diagnose` — Inspect project structure, symbol search, failure analysis
2. `fix-and-verify` — Apply fixes and verify compilation

**Usage:**
```bash
spola workflow run jvm-debug "Fix the failing test in UserServiceTest"
```

### jvmRefactor

Two-step JVM refactoring pipeline:
1. `overview-and-impact` — Project overview, dependency trace, change impact
2. `plan-and-verify` — Concrete refactor plan with verification commands

**Usage:**
```bash
spola workflow run jvm-refactor "Extract payment processing into module :payment"
```

### jvmMigration

Two-step JVM dependency migration pipeline:
1. `catalog-and-window` — Catalog all usages, identify migration window
2. `module-apply-and-verify` — Apply changes per module, verify compilation

**Usage:**
```bash
spola workflow run jvm-migration "Upgrade from Ktor 2.x to 3.x"
```

### codeReview

Pre-built team review workflow. Runs three specialized reviewers in parallel, then summarizes.

Reviewers:
- **security-reviewer** — Security analysis
- **style-reviewer** — Code style assessment
- **test-reviewer** — Test coverage evaluation

**Usage:**
```bash
spola workflow run code-review "Review the changes in PR #42"
```

## Team Workflow Steps

`TeamWorkflowSteps` provides Spola-specific extensions for multi-agent orchestration.

### spolaAgentStep

The primary integration point. Wraps `SpolaAgent.run()` as a workflow step using `aiStep`.

```kotlin
spolaAgentStep(
    name = "implement",
    persona = { "You are an expert Kotlin developer." },
    goal = { state -> state.goal },
)
```

Available as an extension on `AbstractWorkflowBuilder<SpolaState>`. Uses `SpolaFactory` to create and run an agent instance, then merges the result into `SpolaState.result` and `intermediateResults`.

### parallelAgentsStep

Runs multiple Spola agents concurrently. Each agent is looked up by ID from `SqliteAgentStore` and invoked in-process.

```kotlin
workflow<SpolaState>("team-run", "1") {
    parallelAgentsStep(
        name = "parallel-review",
        agents = listOf("security-reviewer", "style-reviewer", "test-reviewer"),
        goal = { state -> "Review code for: ${state.goal}" },
    )
}.build { it.result ?: "no result" }
```

The `merge` function receives the state and a `List<String>` of results, one per agent in the same order as the `agents` list. Default behavior appends each (agentId, result) pair to `intermediateResults`.

### codeReviewWorkflow

Pre-built three-agent code review workflow:

```kotlin
val wf = TeamWorkflowSteps.codeReviewWorkflow()
val result = wf.run(
    initialState = SpolaState.initial(
        goal = "Review the PR for the payment module",
        config = config,
    ),
)
println(result) // Aggregated summary from all three reviewers
```

The workflow:
1. Runs `security-reviewer`, `style-reviewer`, and `test-reviewer` in parallel
2. Aggregates all three reviews via an `aiStep` summarizer
3. Stores the final summary in the workflow result

### branchOnResult

Convenience wrapper around `branchStep` for Spola workflows:

```kotlin
branchOnResult(
    name = "route-by-status",
    selectBranch = { state ->
        when {
            state.result == "success" -> "OK"
            state.result == "failed" -> "FAIL"
            else -> "PENDING"
        }
    },
) {
    branch("OK") { /* ... */ }
    branch("FAIL") { /* ... */ }
    default { /* ... */ }
}
```

### humanApprovalGate

Convenience wrapper around `gateStep`:

```kotlin
humanApprovalGate(name = "deploy-gate") { state, context ->
    // Check external approval system
    val approved = checkApprovalSystem(context.workflowId)
    GateDecision(allowed = approved, reason = if (approved) null else "Awaiting approval")
}
```

## CLI Reference

```
spola workflow run <name> <goal>  — Run a named workflow
spola team run --agents <ids> --goal <goal>  — Run multiple agents in parallel
```

### Examples

```bash
# Run a code review workflow
spola workflow run code-review "Review the refactoring of the auth module"

# Run a JVM debug workflow
spola workflow run jvm-debug "Fix compilation error in SpolaState.kt"

# Run a team of custom agents in parallel
spola team run --agents "security-reviewer,style-reviewer,test-reviewer" \
  --goal "Review the new API endpoint implementation"
```

## API Reference

### POST /api/workflows/run

Run a named workflow.

**Request:**
```json
{
  "workflowName": "code-review",
  "goal": "Review payment module changes"
}
```

**Response:**
```json
{
  "workflowName": "code-review",
  "result": "## Security Review\nNo vulnerabilities found.\n## Style Review\nFollows Kotlin conventions.\n## Test Review\nAdequate test coverage."
}
```

### GET /api/workflows

List available workflows.

**Response:**
```json
{
  "workflows": [
    {
      "name": "code-review",
      "description": "Run a code review with security, style, and test reviewers"
    }
  ]
}
```

## Persistence: Checkpoint and Resume

Workflow state can be serialized for checkpointing using `SpolaWorkflowStateCodec`:

```kotlin
// At any point during execution
val codec = SpolaWorkflowStateCodec()
val checkpoint = codec.encode(state)
saveToStorage(checkpoint, context.workflowId)

// On resume
val stateJson = loadFromStorage(workflowId)
val restoredState = codec.decode(stateJson)
```

The codec uses Jackson with the Kotlin module for full serialization of `SpolaState`, including nested `SpolaConfig`, `AgentDefinition`, and conversation history.

## Observability

`SpolaWorkflowObserver` integrates with three pillars:

```kotlin
val observer = SpolaWorkflowObserver(
    agentObserver = myAgentRunObserver,  // Real-time SSE events
    metrics = myMetrics,                // Prometheus metrics
    tracer = myTracer,                  // OpenTelemetry tracing
)
```

**Events emitted:**
- `workflow_started` / `workflow_completed` — Full workflow lifecycle
- `step_started` / `step_completed` / `step_failed` — Per-step timing
- `workflow_failed` — Error reporting with stack traces

**Metrics recorded:**
- `tool_call` duration per step (success/error status)
- `agent_run` duration per workflow

**Tracing:**
- Root span created on workflow start
- Span ended on workflow completion
- Span marked as failed on workflow error

## Defining Custom Workflows

Create a custom workflow using the `workflow` DSL:

```kotlin
import dev.tramai.orchestration.workflow
import dev.spola.workflow.SpolaState
import dev.spola.workflow.spolaAgentStep

val myWorkflow = workflow<SpolaState>("my-custom-pipeline", "1.0") {
    spolaAgentStep(
        name = "analyze",
        persona = { "You are a code analyst." },
        goal = { "Analyze the project for: ${it.goal}" },
    )

    spolaAgentStep(
        name = "implement",
        persona = { "You are an expert engineer." },
        goal = { state ->
            "Implement the solution based on analysis: ${state.result}"
        },
    )

    branchStep("check-result", select = { state ->
        if (state.result?.isNotEmpty() == true) "DONE" else "RETRY"
    }) {
        branch("DONE") {
            localStep("finish") { state, _ ->
                state.copy(result = "completed")
            }
        }
        branch("RETRY") {
            localStep("needs-work") { state, _ ->
                state.copy(result = "needs_retry")
            }
        }
    }
}.build { it.result ?: "no result" }

// Run it
val result = myWorkflow.run(
    initialState = SpolaState.initial(
        goal = "Add input validation to the API",
        config = config,
    ),
)
```
