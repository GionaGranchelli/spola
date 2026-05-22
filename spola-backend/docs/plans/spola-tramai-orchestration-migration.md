# Spola → TramAI Orchestration Integration Plan (Revised After Codex Review)

## Key Insight from Codex Review
**Do NOT replace the inner ReAct loop.** TramAI's `Workflow` DSL is an *orchestration* layer, not a *loop* replacement. The hand-rolled `SpolaAgent` loop stays as-is. The Workflow DSL wraps *around* it for multi-step, multi-agent, multi-branch orchestration.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  Workflow<SpolaState, String> (tramai-orchestration) │
│                                                      │
│  Step 1: aiStep → agent thinks                       │
│  Step 2: branch → tools OR respond                    │
│  Step 3: shell/mcp/http/plugin steps                   │
│  Step 4: hermesStep → delegate to another agent        │
│  Step 5: parallelStep → run N agents concurrently      │
│  Step 6: gateStep → human approval                     │
│  Step 7: delayStep → wait and resume                   │
│                                                      │
│  Internal: calls SpolaAgent.run() for each AI step   │
└─────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│  SpolaAgent (hand-rolled ReAct loop)                 │
│  - ModelProvider.complete()                           │
│  - ToolRegistry + PermissionEnforcer                  │
│  - Conversation management                            │
│  - TokenJuice compression                             │
│  - CheckpointManager                                  │
└─────────────────────────────────────────────────────┘
```

## Phase 1: Foundation — Add tramai-orchestration

### Step 1.1: Add dependency (CLI build.gradle.kts only, since orchestration is consumed at the CLI level)
- Add to `spola-backend-cli/build.gradle.kts`: `implementation(libs.tramai.orch...)`

### Step 1.2: Define SpolaState
```kotlin
data class SpolaState(
    val goal: String,
    val agentConfig: SpolaConfig,
    val agentDef: AgentDefinition? = null,
    val result: String? = null,
)
```

### Step 1.3: Create workflow builder extensions
```kotlin
// Wraps SpolaAgent.run() as an aiStep
fun WorkflowBuilder<SpolaState>.spolaRunStep(
    name: String,
    providerConfig: ProviderConfig,
    persona: String,
)
```

## Phase 2: Multi-Agent Orchestration

### Step 2.1: Use HermesStep to delegate to Spola CLI
Not custom internal steps (Codex confirmed these aren't a public API). Instead:
```kotlin
hermesStep(name = "security-review", ...) // shells out to `spola agent run security-reviewer <prompt>`
```
Or shell out to the Spola REST API:
```kotlin
httpStep(name = "security-review") {
    POST /api/agents/run with body { agentId: "security-reviewer", goal: "..." }
}
```

### Step 2.2: Build agent team workflows
```kotlin
val teamWorkflow = workflow<SpolaState>("code-review-team", "1") {
    parallelStep(name = "reviewers") {
        items = { listOf("security-reviewer", "style-reviewer", "test-reviewer") }
        invoke = { agentId ->
            httpPost("http://localhost:8082/api/agents/run",
                AgentRunAgentRequest(agentId = agentId, goal = state.goal))
        }
        merge = { state, results -> state.copy(agentDef = /* aggregate results */) }
    }
    aiStep(name = "summarize") { result -> summarizeFindings(result) }
}
```

## Phase 3: Hooks & Observability

### Step 3.1: Implement WorkflowObserver
```kotlin
class SpolaWorkflowObserver(
    private val agentObserver: AgentRunObserver?,
    private val metrics: SpolaMetrics?,
    private val tracer: SpolaTracer?,
) : WorkflowObserver { ... }
```
- Map lifecycle events to AgentRunObserver
- Emit Prometheus metrics per workflow and per step
- Create OpenTelemetry spans per step execution

### Step 3.2: Preserve existing SSE streaming
- WorkflowObserver.onStepStarted → SSE event "step_started"
- WorkflowObserver.onStepCompleted → SSE event "step_completed"
- **CRITICAL:** Existing SSE consumers (frontend, test assertions) must not break

## Phase 4: Parallel Execution & Branching

### Step 4.1: Expose ParallelStep
```kotlin
fun WorkflowBuilder<SpolaState>.parallelAgents(
    name: String,
    agentIds: List<String>,
    goal: (SpolaState) -> String,
    merge: (SpolaState, List<String>) -> SpolaState,
)
```

### Step 4.2: Expose BranchStep
```kotlin
fun WorkflowBuilder<SpolaState>.branchOnResult(
    name: String,
    hasToolCalls: (SpolaState) -> Boolean,
    onTools: WorkflowBuilder<SpolaState>.() -> Unit,
    onResponse: WorkflowBuilder<SpolaState>.() -> Unit,
)
```

### Step 4.3: Expose GateStep for human-in-the-loop approval gates

## Phase 5: Plugin System via ExternalStepExecutorRegistry

### Step 5.1: Register Spola plugins
```kotlin
class SpolaPluginExecutorFactory(plugin: SpolaPlugin) : ExternalStepExecutorFactory
```

### Step 5.2: Wire PluginLoader into ExternalStepExecutorRegistry
Spola's existing `PluginLoader.loadPlugins()` populates TramAI's registry.

## Phase 6: Durable Execution (Checkpoint & Resume)

### Step 6.1: Implement WorkflowPersistence on top of Spola's SQLite stores
- `WorkflowCheckpointStore` → new `SqliteWorkflowCheckpointStore` using Exposed
- `WorkflowStateCodec` → Jackson serialization of SpolaState
- `WorkflowLeaseStore` → prevents duplicate workflow runs

### Step 6.2: Keep existing CheckpointManager until migration is verified
- Don't delete it in Phase 6 — keep as fallback for Phase 7 cleanup

### Step 6.3: Add resume CLI command
```bash
spola workflow resume <workflow-id>
```

## Phase 7: CLI & API Integration

### Step 7.1: Add workflow CLI subcommands
```bash
spola workflow create|list|show|resume
spola agent run ...  # existing, unchanged
spola team run --agents "reviewer-a,reviewer-b" --goal "..."  # new
```

### Step 7.2: Add workflow API endpoints
```
POST /api/workflows/run    — run a workflow definition
GET  /api/workflows        — list running/completed workflows
POST /api/workflows/{id}/resume  — resume a suspended workflow
```

### Step 7.3: Wire SpolaApiServer with workflow endpoints (optional)

## Phase 8: Cleanup & Verification

### Step 8.1: Verify parity tests
For every existing test in `SpolaAgentTest`, create a corresponding `WorkflowAgentTest`:
- Agent returns text response → Workflow completes with result
- Agent executes tool calls → Workflow progresses through steps
- Agent enforces maxTurns → Workflow enforces StopPolicy
- Tool errors handled → Workflow step failures handled
- SSE events emitted → WorkflowObserver events match expected order
- Checkpoint save/load/resume → WorkflowPersistence works

### Step 8.2: Run full test suite
```bash
./gradlew :spola-backend-core:compileKotlin :spola-backend-cli:compileKotlin
./gradlew :spola-backend-core:test
```

### Step 8.3: Mark deprecated files
```kotlin
@Deprecated("Use WorkflowAgent instead. Will be removed in 0.2.0.")
class SpolaAgent { ... }
```

## File Map

```
spola-backend-core/src/main/kotlin/dev/spola/workflow/
├── SpolaState.kt                   — Workflow state
├── SpolaWorkflowObserver.kt        — WorkflowObserver → metrics/tracing/SSE
├── WorkflowSteps.kt                — workflow builder extensions (.spolaRunStep, .parallelAgents, .branchOnResult)

spola-backend-cli/src/main/kotlin/dev/spola/cli/
├── WorkflowCommand.kt              — spola workflow subcommands

Modified:
├── spola-backend-core/build.gradle.kts     — +tramai-orchestration
├── spola-backend-cli/build.gradle.kts      — +tramai-orchestration
├── SpolaFactory.kt                 — +buildWorkflow() method
├── SpolaApiServer.kt               — +workflow endpoints (optional)
├── gradle/libs.versions.toml       — +tramai-orchestration
```

## Dependencies

```toml
tramai-orchestration = { module = "dev.tramai:tramai-orchestration" }
```

```kotlin
// spola-backend-core/build.gradle.kts
implementation(libs.tramai.orchestration)

// spola-backend-cli/build.gradle.kts
implementation(libs.tramai.orchestration)
```

## Risk Assessment (Revised)

| Risk | Severity | Mitigation |
|------|----------|-----------|
| Observer/SSE regression | HIGH | Parity test: SSE event order and payloads match between old and new |
| Checkpoint tool contract break | HIGH | Keep old CheckpointManager alongside until Phase 8; dual-write first, then migrate readers |
| Plugin incompatibility | MEDIUM | PluginLoader.loadPlugins() continues to work; only new workflow plugins use ExternalStepExecutorRegistry |
| API surface churn from @Deprecated | MEDIUM | Deprecate with `@Deprecated(level = WARNING)` not ERROR, keep working until 0.2.0 |
| Turn count semantic drift | MEDIUM | maxTurns (LLM turns) ≠ maxStepExecutions (workflow steps). Explicitly map: `maxStepExecutions = maxTurns * stepsPerTurn`. Test exactly. |
| Internal step types not public API | HIGH | Don't create custom internal steps. Use public WorkflowBuilder methods only (aiStep, hermesStep, httpStep, shellStep, mcpStep, parallelStep, branchStep, gateStep, delayStep). |
| HermesStep requires hermes CLI installed | LOW | Document prerequisite. Offer httpStep-based fallback for users without Hermes CLI. |
