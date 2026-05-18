# SPEC: YAML Workflow Implementation Gaps (v2)

> **Status:** ✅ **Completed** — All 5+1 implementation gaps filled
> **Target:** 3 features + 2 prerequisites + 1 deferred feature
> **Context:** `/home/gionag/Development/golem`

## Executive Summary

The YAML workflow system parses definitions correctly and supports all step types. **Six** gaps have been filled (the original 3 + 2 prerequisite bugs + 1 deferred feature):

1. ✅ **Parser: YAML field aliasing** — `@JsonProperty` aliases added to `StepDef` for `depends_on`, `workflow_ref`, `on_error`, `retry_count`, `max_output_bytes`.
2. ✅ **Compiler: `{{step.x.output}}` runtime resolution** — Goal lambdas read from `state.intermediateResults` at execution time.
3. ✅ **Compiler: `dependsOn` topological sort** — Kahn's algorithm in `YamlWorkflowDagSorter`, wired into compiler.
4. ✅ **Compiler: Step type implementations** — `shell`, `local` via `YamlWorkflowStepRunner` (ProcessBuilder); `composite` via recursive compile with cycle detection.
5. ✅ **Compiler: Workflow-level done evaluation** — Global `done` gate appended after all steps.
6. ✅ **Compiler: Composite cycle detection** — `parentsChain: Set<String>` with compile-time cycle detection + runtime depth guard (max 10) + timeout + error boundary.

Filling all 6 makes the YAML workflow system production-ready.

---

## Prerequisite: YAML Field Aliasing

### Problem

Jackson YAML parser in `YamlWorkflowParser` uses default settings with no snake_case naming strategy. YAML typically uses `depends_on` (snake_case), but the Kotlin data model declares `dependsOn` (camelCase), `workflowRef`, and `retryCount`. The exporter already emits snake_case `depends_on` in `WorkflowExport.kt:75`, meaning exported YAML files silently lose their dependency information.

### Fix

Add `@JsonProperty` aliases to `StepDef` in `WorkflowDefinition.kt`:

```kotlin
data class StepDef(
    val id: String,
    val type: String,
    val goal: String = "",
    val persona: String? = null,
    val agents: List<String>? = null,
    @get:JsonProperty("depends_on") val dependsOn: List<String>? = null,
    val command: String? = null,
    val timeout: Int = 60,
    val prompt: String? = null,
    val expression: String? = null,
    @get:JsonProperty("workflow_ref") val workflowRef: String? = null,
    val invoke: String? = null,
    val done: List<DoneCondition> = emptyList(),
    @get:JsonProperty("on_error") val onError: String = "fail",
    @get:JsonProperty("retry_count") val retryCount: Int = 0,
)
```

### Tests
| Test | Description |
|------|-------------|
| `parse yaml with depends_on reads dependsOn` | YAML `depends_on: [a]` → StepDef.dependsOn = ["a"] |
| `parse yaml with workflow_ref reads workflowRef` | YAML `workflow_ref: foo` → StepDef.workflowRef = "foo" |
| `parse yaml with retry_count reads retryCount` | YAML `retry_count: 3` → StepDef.retryCount = 3 |
| `parse yaml with on_error reads onError` | YAML `on_error: skip` → StepDef.onError = "skip" |
| `exported yaml round-trips depends_on` | Export → parse → export yields same depends_on values |
| `parse yaml with mixed snake_case and camelCase` | Both `depends_on: [a]` and `dependsOn: [a]` work |

---

## Feature 1: `dependsOn` Topological Sort

### Current State

`YamlWorkflowCompiler.kt` (lines 49-127) iterates `resolved.steps` in list order. `dependsOn` is never read.

### Requirements

1. **Topological sort** — Before iterating steps, sort by `dependsOn`. Kahn's algorithm.
2. **Cycle detection** — Throw `IllegalStateException` listing the cycle.
3. **Empty dependsOn** — Steps with no deps preserve declaration order (stable sort).
4. **Single dependency** — A→B, B executes after A.
5. **Fan-out** — A→(B,C) and D depends on both B and C.
6. **Fan-in** — (A,B)→C, C after both.
7. **Missing dependency** — `dependsOn` references non-existent step → `IllegalArgumentException`.
8. **Self-reference** — Step depends on itself → cycle detected.
9. **Duplicate step IDs** — Steps with duplicate `id` → `IllegalArgumentException` before any sort.

### Implementation

**New file: `YamlWorkflowDagSorter.kt`**

```kotlin
object YamlWorkflowDagSorter {
    /**
     * Topologically sort steps by dependsOn using Kahn's algorithm.
     * @throws IllegalStateException on cycle
     * @throws IllegalArgumentException on missing dependency or duplicate IDs
     */
    fun sort(steps: List<ResolvedStep>): List<ResolvedStep>
}
```

**Modification: `YamlWorkflowCompiler.kt`** — Call sorter before step loop.

### Tests

Use `localStep` execution, NOT actual AI agent steps (matching existing test conventions):

| Test | Type | Description |
|------|------|-------------|
| `sort linear chain preserves order` | Unit | A→B→C, declared C,B,A → sorted A,B,C |
| `sort fan-out` | Unit | A→(B,C)→D, correct ordering |
| `sort fan-in` | Unit | (A,B)→C, C after both |
| `sort stable for equal-level steps` | Unit | Root steps preserve declaration order |
| `sort detects cycle` | Unit | A→B, B→C, C→A → IllegalStateException |
| `sort detects missing dependency` | Unit | A→X (no such step) → IllegalArgumentException |
| `sort self-reference detected as cycle` | Unit | A→A → IllegalStateException |
| `sort duplicate step ids` | Unit | Two steps with id="a" → IllegalArgumentException |
| `sort empty list` | Unit | No steps → empty |
| `compile workflow sorts by depends_on` | Integration | YAML with depends_on → steps execute in DAG order (verified with localStep result tracking) |
| `compile workflow with cycle throws` | Integration | YAML with cycle → compile throws |

---

## Feature 2: `{{step.x.output}}` Runtime Resolution (Prerequisite for Data Flow)

### Problem

Currently in `YamlWorkflowCompiler.kt`, `resolveRuntimeTemplates()` is called at step registration time (workflow build time) with an empty `stepOutputs` map. The `golemAgentStep` and `parallelAgentsStep` functions accept `(GolemState) -> String` goal lambdas that execute at runtime, but the compiler passes a pre-resolved string instead of using the lambda form.

### Fix

Change the goal lambda to read from `state.intermediateResults` at runtime:

```kotlin
// Instead of:
val resolvedGoal = WorkflowParameterResolver.resolveRuntimeTemplates(
    step.goal, stepOutputs  // stepOutputs is empty at build time!
)
golemAgentStep(
    name = step.id,
    persona = { step.persona ?: "..." },
    goal = { resolvedGoal },  // Pre-resolved — WRONG
    merge = { state, result ->
        stepOutputs[step.id] = result  // Only populated at runtime
        state.copy(result = result, ...)
    },
)

// Use:
golemAgentStep(
    name = step.id,
    persona = { step.persona ?: "..." },
    goal = { state ->
        // Resolve at execution time from actual step outputs
        WorkflowParameterResolver.resolveRuntimeTemplates(
            step.goal,
            state.intermediateResults,
        )
    },
    merge = { state, result ->
        state.copy(
            result = result,
            intermediateResults = state.intermediateResults + (step.id to result),
        )
    },
)
```

This ensures `{{step.analyze.output}}` resolves when step.analyze's output is actually available in `state.intermediateResults`.

### Tests

| Test | Description |
|------|-------------|
| `step output template resolves at runtime` | Step B has `{{step.A.output}}`, A runs first → B gets A's output |
| `step output template keeps placeholder if step not run` | Step B references unknown step → placeholder preserved |
| `step output template with multiple steps` | A→B→C, C references both A and B outputs |
| `parallel_agents output merge preserves template resolution` | Parallel step output available for downstream step |

---

## Feature 3: Step Type Implementations (shell, local, composite)

### Current State

`YamlWorkflowCompiler.kt` (lines 102-106) logs a warning and inserts a `localStep` passthrough.

### Requirements

#### `local` step
- Identity pass-through on `GolemState` (immutable, no expression evaluation).
- MVP only — safe default.

#### `shell` step
- Executes shell command via reused `kotlinx.coroutines` + `ProcessBuilder` pattern (avoids duplicating shell execution logic).
- Timeout from `timeout` field (default 60s) enforced via `withTimeout`.
- Captures stdout only. Stderr logged.
- On success: stores stdout in both `result` and `state.intermediateResults[step.id]`.
- On non-zero exit: stores error message, behavior follows `onError` (default "fail" → throw; "skip" → log and continue).
- **Security:** No shell injection vector — command string is passed literally to `ProcessBuilder("sh", "-c", command)`.

#### `composite` step
- ✅ **Implemented.** Nested sub-workflow referencing via `workflow_ref`.
- At compile time: resolves the referenced workflow from `WorkflowTemplateRegistry`, builds the sub-workflow DAG.
- At runtime: executes sub-workflow via `runBlocking { subWorkflow.run(initialState, WorkflowContext()) }`.
- **Cycle detection:** `parentsChain: Set<String>` tracks the compilation chain. Self-references and cross-references (A→B→A) detected at compile time with clear error message including the chain path.
- **Safety:** Runtime depth guard (max 10), `withTimeout` from step `timeout` field, `try/catch` with descriptive error messages.
- **Architecture:** `WorkflowTemplate.supportsRecursiveCompilation` / `compileRecursive()` interface method for clean opt-in. `YamlWorkflowTemplate` overrides both. No instanceof checks.
- **Parameters:** Sub-workflow params resolve to their own YAML defaults. Parent params are not forwarded (independent scope).
- **Observability:** Sub-workflow runs without observer/persistence/tracing (NoOp defaults). LLM provider calls still work via TramAI ProviderRegistry.
- **Limitation:** Built-in templates (code-review, jvm-debug) cannot contain composite steps — `supportsRecursiveCompilation` defaults to `false` for non-YAML templates.

### Implementation

**Reuse existing shell execution logic** (do NOT add a second ProcessBuilder path). Check if `GolemAgent` or `Tool` infrastructure has a shell utility. If not, use `kotlin.runCatching { ProcessBuilder("sh", "-c", command).start() }`.

### Tests

|| Test | Description |
||------|-------------|
|| `shell step runs echo and captures output` | `echo "hello"` → result contains "hello" |
|| `shell step missing command throws` | shell without command → IllegalArgumentException |
|| `shell step non-zero exit captured` | `exit 1` → error stored |
|| `shell step timeout enforced` | Short timeout → timeout error |
|| `local step passes state through` | local step → state unchanged |
|| `shell step with env vars executes with custom environment` | Env vars passed to ProcessBuilder |
|| `all implemented step types coexist` | YAML with ai + shell + local → all execute |
|| `composite step executes sub-workflow and captures output` | Sub-workflow → result propagated |
|| `composite step missing workflow_ref throws` | Missing ref → IllegalArgumentException |
|| `composite step refers to non-existent workflow throws` | Bad ref → IllegalStateException |
|| `composite step output available via template resolution` | `{{step.main.output}}` resolved |
|| `composite step self-reference detected as cycle` | A→A → IllegalStateException with chain |
|| `composite step chain cycle detected` | A→B→A → IllegalStateException with chain |
|| `composite step three-way cycle detected` | A→B→C→A → IllegalStateException |
|| `composite step no cycle with deep chain` | A→B→C → executes successfully |
|| `composite step diamond dependency` | A→{B,C}→D → executes successfully |
|| `composite step multiple refs same sub` | Two composite steps → same leaf |
|| `composite step nesting depth limit enforced` | 12-depth chain → max depth error |

---

## Feature 4: Workflow-Level `done` Evaluation

### Current State

`WorkflowDefinition.done` parsed but never evaluated by `YamlWorkflowCompiler`.

### Requirements

1. **Global done gate** — Appended after all steps. Uses `gateStep` rejection semantics (same as per-step done) — NOT result mutation.
2. **Failure semantics:** When global done fails → `gateStep` returns `GateDecision(allowed = false, reason = "...")` → workflow rejects with TramAI gate rejection. This is consistent with per-step done behavior.
3. **Empty global done** — Skip gate entirely. Zero behavior change for workflows without `done:`.
4. **Mixed per-step + global** — Per-step gates fire after each step; global gate fires after all steps. Both must pass.
5. **Error message** includes which specific condition failed.

### Implementation

```kotlin
// After all steps, evaluate global done conditions
if (resolved.done.isNotEmpty()) {
    gateStep("workflow-done-check") { state, _ ->
        val failedCondition = resolved.done.firstOrNull { condition ->
            !DoneConditionEvaluator.evaluate(condition, state, stepOutputs.toMap())
        }
        GateDecision(
            allowed = failedCondition == null,
            reason = if (failedCondition != null)
                "Workflow done condition failed: '${failedCondition.condition}'"
            else null,
        )
    }
}
```

### Tests

| Test | Description |
|------|-------------|
| `global done passes when conditions met` | `done: [output_has_content]` with result set → success |
| `global done fails when condition not met` | `done: [output_has_content]` with null result → rejection |
| `global done empty skips evaluation` | No `done` → completes normally |
| `per-step + global both pass` | Both per-step and global satisfied → success |
| `per-step passes but global fails` | Per-step ok, global fails → rejection |
| `global done with multiple conditions` | Two conditions, one fails → rejection with specific condition name |
| `global done is a gateStep` | Structure assertion: gate exists after step loop |

---

## Implementation Order

```
Prerequisite A: YAML Field Aliasing (@JsonProperty annotations)   → ✅ Done
   └── Modify WorkflowDefinition.kt + parser tests

Prerequisite B: {{step.x.output}} Runtime Resolution                → ✅ Done
   └── Modify YamlWorkflowCompiler.kt goal lambdas

Feature 1: depends_on topological sort                              → ✅ Done
   ├── Create YamlWorkflowDagSorter.kt + unit tests
   └── Modify YamlWorkflowCompiler.kt to call sorter

Feature 2: shell + local step types                                 → ✅ Done
   ├── Create YamlWorkflowStepRunner.kt (ProcessBuilder, timeout, retry, UTF-8, 10MB cap, ioExecutor)
   └── Integration tests

Feature 3: workflow-level done evaluation                           → ✅ Done
   └── Modify YamlWorkflowCompiler.kt to append global gate

Feature 4: composite step type                                      → ✅ Done
   ├── YamlWorkflowCompiler + registry wiring
   ├── Compile-time cycle detection (parentsChain)
   ├── Runtime depth guard (max 10), withTimeout, try/catch
   └── Integration tests (7: basic, missing ref, cycle, deep chain, diamond)
```
After each feature: ./gradlew :golem-core:test
After ALL: ./gradlew :golem-core:test :golem-cli:test
```

---

## Test Conventions

1. **No real AI agent calls in tests** — Use `localStep` execution to verify ordering and state. Existing tests like `YamlWorkflowSystemTest` build workflows but don't actually run AI steps.
2. **Parser tests** — Pure data → data, no execution.
3. **Sorter tests** — Pure algorithm tests on `ResolvedStep` lists.
4. **Compiler integration tests** — Build the workflow, run with `localStep` content, verify `state.intermediateResults`.
5. **Shell tests** — Isolated, platform-safe (`echo`, `exit` commands only).
6. **Full suite after each change** — `./gradlew :golem-core:test` catches regressions.

---

## Risk Register

|| Risk | Impact | Mitigation | Status |
||------|--------|------------|--------|
|| depends_on cycle detection | Edge cases in large graphs | Kahn's algorithm, tested on 3+ cycle topologies | ✅ |
|| {{step.x.output}} resolution changes existing behavior | Workflows without step references unaffected | Only affects templates containing `{{step.*}}` | ✅ |
|| shell step process blocking | Long command freezes workflow | ProcessBuilder + kotlinx timeout | ✅ |
|| shell step platform differences | Tests pass on Linux, fail on macOS/Windows | Test with `echo` and `exit` only | ✅ |
|| composite step needs registry access | Can't fully implement | Registry wired through YamlWorkflowTemplate + WorkflowTemplate interface | ✅ |
|| composite step infinite recursion | Stack overflow on cyclical refs | Compile-time parentsChain detection + runtime depth guard (max 10) + withTimeout | ✅ |
|| Global done gate regression | Workflows without `done:` unchanged | Empty check → skip → zero behavioral change | ✅ |
