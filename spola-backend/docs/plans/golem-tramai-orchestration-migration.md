# Golem → TramAI Orchestration Integration Plan (Revised After Codex Review)

## Key Insight from Codex Review
**Do NOT replace the inner ReAct loop.** TramAI's `Workflow` DSL is an *orchestration* layer, not a *loop* replacement. The hand-rolled `GolemAgent` loop stays as-is. The Workflow DSL wraps *around* it for multi-step, multi-agent, multi-branch orchestration.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  Workflow<GolemState, String> (tramai-orchestration) │
│                                                      │
│  Step 1: aiStep → agent thinks                       │
│  Step 2: branch → tools OR respond                    │
│  Step 3: shell/mcp/http/plugin steps                   │
│  Step 4: hermesStep → delegate to another agent        │
│  Step 5: parallelStep → run N agents concurrently      │
│  Step 6: gateStep → human approval                     │
│  Step 7: delayStep → wait and resume                   │
│                                                      │
│  Internal: calls GolemAgent.run() for each AI step   │
└─────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│  GolemAgent (hand-rolled ReAct loop)                 │
│  - ModelProvider.complete()                           │
│  - ToolRegistry + PermissionEnforcer                  │
│  - Conversation management                            │
│  - TokenJuice compression                             │
│  - CheckpointManager                                  │
└─────────────────────────────────────────────────────┘
```

## Phase 1: Foundation — Add tramai-orchestration

### Step 1.1: Add dependency (CLI build.gradle.kts only, since orchestration is consumed at the CLI level)
- Add to `golem-cli/build.gradle.kts`: `implementation(libs.tramai.orch...)`

### Step 1.2: Define GolemState
```kotlin
data class GolemState(
    val goal: String,
    val agentConfig: GolemConfig,
    val agentDef: AgentDefinition? = null,
    val result: String? = null,
)
```

### Step 1.3: Create workflow builder extensions
```kotlin
// Wraps GolemAgent.run() as an aiStep
fun WorkflowBuilder<GolemState>.golemRunStep(
    name: String,
    providerConfig: ProviderConfig,
    persona: String,
)
```

## Phase 2: Multi-Agent Orchestration

### Step 2.1: Use HermesStep to delegate to Golem CLI
Not custom internal steps (Codex confirmed these aren't a public API). Instead:
```kotlin
hermesStep(name = "security-review", ...) // shells out to `golem agent run security-reviewer <prompt>`
```
Or shell out to the Golem REST API:
```kotlin
httpStep(name = "security-review") {
    POST /api/agents/run with body { agentId: "security-reviewer", goal: "..." }
}
```

### Step 2.2: Build agent team workflows
```kotlin
val teamWorkflow = workflow<GolemState>("code-review-team", "1") {
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
class GolemWorkflowObserver(
    private val agentObserver: AgentRunObserver?,
    private val metrics: GolemMetrics?,
    private val tracer: GolemTracer?,
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
fun WorkflowBuilder<GolemState>.parallelAgents(
    name: String,
    agentIds: List<String>,
    goal: (GolemState) -> String,
    merge: (GolemState, List<String>) -> GolemState,
)
```

### Step 4.2: Expose BranchStep
```kotlin
fun WorkflowBuilder<GolemState>.branchOnResult(
    name: String,
    hasToolCalls: (GolemState) -> Boolean,
    onTools: WorkflowBuilder<GolemState>.() -> Unit,
    onResponse: WorkflowBuilder<GolemState>.() -> Unit,
)
```

### Step 4.3: Expose GateStep for human-in-the-loop approval gates

## Phase 5: Plugin System via ExternalStepExecutorRegistry

### Step 5.1: Register Golem plugins
```kotlin
class GolemPluginExecutorFactory(plugin: GolemPlugin) : ExternalStepExecutorFactory
```

### Step 5.2: Wire PluginLoader into ExternalStepExecutorRegistry
Golem's existing `PluginLoader.loadPlugins()` populates TramAI's registry.

## Phase 6: Durable Execution (Checkpoint & Resume)

### Step 6.1: Implement WorkflowPersistence on top of Golem's SQLite stores
- `WorkflowCheckpointStore` → new `SqliteWorkflowCheckpointStore` using Exposed
- `WorkflowStateCodec` → Jackson serialization of GolemState
- `WorkflowLeaseStore` → prevents duplicate workflow runs

### Step 6.2: Keep existing CheckpointManager until migration is verified
- Don't delete it in Phase 6 — keep as fallback for Phase 7 cleanup

### Step 6.3: Add resume CLI command
```bash
golem workflow resume <workflow-id>
```

## Phase 7: CLI & API Integration

### Step 7.1: Add workflow CLI subcommands
```bash
golem workflow create|list|show|resume
golem agent run ...  # existing, unchanged
golem team run --agents "reviewer-a,reviewer-b" --goal "..."  # new
```

### Step 7.2: Add workflow API endpoints
```
POST /api/workflows/run    — run a workflow definition
GET  /api/workflows        — list running/completed workflows
POST /api/workflows/{id}/resume  — resume a suspended workflow
```

### Step 7.3: Wire GolemApiServer with workflow endpoints (optional)

## Phase 8: Cleanup & Verification

### Step 8.1: Verify parity tests
For every existing test in `GolemAgentTest`, create a corresponding `WorkflowAgentTest`:
- Agent returns text response → Workflow completes with result
- Agent executes tool calls → Workflow progresses through steps
- Agent enforces maxTurns → Workflow enforces StopPolicy
- Tool errors handled → Workflow step failures handled
- SSE events emitted → WorkflowObserver events match expected order
- Checkpoint save/load/resume → WorkflowPersistence works

### Step 8.2: Run full test suite
```bash
./gradlew :golem-core:compileKotlin :golem-cli:compileKotlin
./gradlew :golem-core:test
```

### Step 8.3: Mark deprecated files
```kotlin
@Deprecated("Use WorkflowAgent instead. Will be removed in 0.2.0.")
class GolemAgent { ... }
```

## File Map

```
golem-core/src/main/kotlin/dev/golem/workflow/
├── GolemState.kt                   — Workflow state
├── GolemWorkflowObserver.kt        — WorkflowObserver → metrics/tracing/SSE
├── WorkflowSteps.kt                — workflow builder extensions (.golemRunStep, .parallelAgents, .branchOnResult)

golem-cli/src/main/kotlin/dev/golem/cli/
├── WorkflowCommand.kt              — golem workflow subcommands

Modified:
├── golem-core/build.gradle.kts     — +tramai-orchestration
├── golem-cli/build.gradle.kts      — +tramai-orchestration
├── GolemFactory.kt                 — +buildWorkflow() method
├── GolemApiServer.kt               — +workflow endpoints (optional)
├── gradle/libs.versions.toml       — +tramai-orchestration
```

## Dependencies

```toml
tramai-orchestration = { module = "dev.tramai:tramai-orchestration" }
```

```kotlin
// golem-core/build.gradle.kts
implementation(libs.tramai.orchestration)

// golem-cli/build.gradle.kts
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
