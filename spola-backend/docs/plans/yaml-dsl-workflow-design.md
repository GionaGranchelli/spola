# YAML Workflow DSL — Design Specification

**Date:** 2026-05-16
**Status:** Draft for review
**Target:** Spola master branch

---

## 1. Executive Summary

Spola currently defines workflows as hardcoded Kotlin DSL templates in `WorkflowTemplateRegistry`. This design adds a **YAML → DAG compiler** that lets users define workflows as YAML files, compiled to TramAI `Workflow` objects at runtime — no rebuild required.

The YAML DSL sits as a **thin translation layer** above the existing TramAI orchestration engine. All existing features (checkpoint/resume, parallel steps, semaphores, branching, AI steps, git steps, compile steps, human approval gates, SQLite-backed persistent queue, AsyncWorkflowDispatcher) remain exactly as they are. The YAML is desugared into the same Kotlin DSL that hardcoded templates use.

---

## 2. What Stays the Same (Zero Changes)

| Component | File | Why unchanged |
|-----------|------|---------------|
| `Workflow<S, R>` (TramAI) | `tramai-orchestration` | YAML compiles TO this. No changes needed. |
| `WorkflowBuilder` DSL | `tramai-orchestration` | YAML compiler calls `aiStep`, `parallelStep`, `branchStep`, etc. internally |
| `SpolaState` | `spola-backend-core/.../SpolaState.kt` | Data class is sufficient. Add optional `parameters` field. |
| `AsyncWorkflowDispatcher` | `spola-backend-core/.../WorkflowDispatcher.kt` | Still polls SQLite queue and dispatches execution |
| `WorkflowExecutionService` | `spola-backend-core/.../WorkflowExecutionService.kt` | Still resolves template → builds workflow → runs |
| `WorkflowTemplateRegistry` | `spola-backend-core/.../WorkflowTemplateRegistry.kt` | Gets a new source: YAML-compiled templates alongside hardcoded ones |
| `WorkflowFactory` | `spola-backend-core/.../WorkflowFactory.kt` | No changes needed |
| `SqliteWorkflowExecutionStore` | `spola-backend-core/.../WorkflowExecutionStore.kt` | No changes |
| `WorkflowPersistence` / checkpoint | `tramai-orchestration` | YAML workflows get checkpoint/resume for free |
| `SpolaWorkflowObserver` | `spola-backend-core/.../SpolaWorkflowObserver.kt` | No changes |
| `SpolaWorkflowStateCodec` | `spola-backend-core/.../SpolaWorkflowStateCodec.kt` | No changes |
| CLI/API entry points | Various | Minimal wiring changes only |
| Plugin system | `ExternalStepExecutorRegistry` | YAML can reference plugin steps by type ID |

---

## 3. The YAML Workflow Schema

### 3.1 Full Schema

```yaml
# ~/.spola/workflows/<name>.yaml
# OR <project>/.spola/workflows/<name>.yaml
# OR loaded from any configured path

name: string                  # Required. Unique workflow name (lowercase, hyphens)
version: string               # Required. Semantic version string (e.g., "1.0.0")
description: string           # Optional. Human-readable description

params:                       # Optional. Declared input parameters
  <key>:
    type: string              # "string" | "number" | "boolean" | "array" | "object"
    required: boolean         # Default: false
    default: any              # Default value
    description: string       # Human-readable description
    options: [string...]      # Optional: enum-like constraints

steps:                        # Required. Ordered list of workflow steps
  - id: string                # Required. Unique step identifier
    type: string              # Required. Step type (see 3.2)
    depends_on: [string...]   # Optional. Explicit dependency declarations
    # ... type-specific fields ...

# Optional. Global "definition of done" for the entire workflow.
# Evaluated after all steps complete.
done:
  - condition: string         # Condition string
    severity: string          # "blocking" | "warning"

# Optional. Default retry configuration for all steps.
retry:
  max_attempts: int           # Default: 1 (no retry)
  backoff: string             # "fixed" | "exponential" | "linear" (default: "fixed")
  delay_seconds: int          # Delay between retries (default: 5)
```

### 3.2 Step Types

#### `ai` — AI Agent Step

```yaml
- id: security-scan
  type: ai
  persona: string              # Required. System prompt for the LLM agent
  goal: string                 # Required. Goal description (supports {{param}} templates)
  model: string                # Optional. Override default model (e.g., "gpt-4o")
  provider: string             # Optional. Override default provider
  tools: [string...]           # Optional. Tool allowlist (default: all tools)
  temperature: number          # Optional. 0.0-2.0
  max_tokens: int              # Optional. Max tokens in response
  replay_policy: string        # Optional. "idempotent" | "non_replayable" (default: "non_replayable")
  timeout_seconds: int         # Optional. Step timeout (default: 300)
  done:                        # Optional. Per-step "definition of done"
    - condition: string
      severity: string
```

#### `parallel` — Fan-Out Parallel Execution

```yaml
- id: parallel-review
  type: parallel
  items:                       # Required. List of items to process in parallel
    - id: string               # Required. Identifier for this branch
      goal: string             # Required. Goal for this branch
      persona: string          # Optional. Per-item persona override
  strategy: string             # Optional. "all" | "race" (default: "all")
  max_concurrent: int          # Optional. Limit parallelism (default: no limit)
  merge_strategy: string       # Optional. "append" | "collect" (default: "collect")
```

#### `branch` — Conditional Routing

```yaml
- id: check-result
  type: branch
  condition: string            # Required. JavaScript-like expression evaluated against state
  branches:                    # Required. Map of outcome → step ID
    "passed": next-step-id
    "failed": error-handler-id
  default: string              # Optional. Fallback step if no branch matches
```

#### `gate` — Human Approval Gate

```yaml
- id: approval-gate
  type: gate
  description: string          # Required. What the approver is being asked to approve
  ttl_seconds: int             # Optional. Auto-reject after this many seconds (default: 3600)
  approvers: [string...]       # Optional. List of authorized approver IDs
  notify:                      # Optional. Notification configuration
    telegram: boolean          # Send notification via Telegram
    email: boolean             # Send notification via email
```

#### `plugin` — Custom External Step

```yaml
- id: compile-verify
  type: plugin
  plugin_type: string          # Required. Registered plugin type ID (e.g., "compile_project")
  config:                      # Required. Plugin-specific configuration map
    project: "{{params.project}}"
    tasks: "compileKotlin"
```

#### `shell` — Shell Command Step

```yaml
- id: run-tests
  type: shell
  command: string              # Required. Shell command to execute
  workdir: string              # Optional. Working directory
  timeout_seconds: int         # Optional. Command timeout (default: 300)
  fail_on_nonzero: boolean     # Optional. Fail step on non-zero exit (default: true)
  allowed_commands: [string...] # Optional. Command allowlist regexes
  env:                         # Optional. Environment variables
    KEY: value
```

#### `http` — HTTP Request Step

```yaml
- id: webhook-notify
  type: http
  method: string               # "GET" | "POST" | "PUT" | "DELETE"
  url: string                  # Required. Request URL (supports {{param}} templates)
  headers:                     # Optional. HTTP headers
    Content-Type: application/json
  body: string                 # Optional. Request body (for POST/PUT)
  timeout_seconds: int         # Optional. Request timeout (default: 30)
  retry:                       # Optional. Retry configuration
    max_attempts: int
    delay_seconds: int
```

#### `local` — Deterministic Transform

```yaml
- id: check-retry
  type: local
  transform: string            # Expression-like description of the state transform
  # Implementation: compiled to a localStep that reads/writes SpolaState fields
```

#### `delay` — Wait Step

```yaml
- id: wait-for-resource
  type: delay
  duration_seconds: int        # Required. How long to wait
```

---

## 4. YAML → DAG Compiler Architecture

### 4.1 Pipeline

```
YAML file
    │
    ▼
[YamlWorkflowParser] ──► YamlWorkflowDefinition (intermediate IR)
    │  Uses Jackson YAMLFactory (already a dependency via SkillParser)
    │
    ▼
[WorkflowDagCompiler]  ──► Workflow<SpolaState, String> (TramAI)
    │  Translates IR into WorkflowBuilder DSL calls
    │
    ▼
[WorkflowTemplateRegistry] ──► resolve("my-workflow") → compiled Workflow
    │  Stored in memory alongside hardcoded templates
    │
    ▼
[WorkflowExecutionService] ──► run / checkpoint / resume
    │  No changes — receives a Workflow<SpolaState, String> like any other
```

### 4.2 Key Classes (New)

#### `YamlWorkflowParser`

```kotlin
/**
 * Parses YAML workflow files into an intermediate representation.
 * Uses Jackson YAMLFactory (same as SkillParser).
 */
object YamlWorkflowParser {
    data class YamlWorkflowDefinition(
        val name: String,
        val version: String,
        val description: String = "",
        val params: Map<String, ParamDef> = emptyMap(),
        val steps: List<StepDef>,
        val done: List<DoneCondition> = emptyList(),
        val retry: RetryConfig = RetryConfig(),
    )
    
    data class ParamDef(
        val type: String,
        val required: Boolean = false,
        val default: Any? = null,
        val description: String = "",
        val options: List<String>? = null,
    )
    
    sealed interface StepDef {
        val id: String
        val dependsOn: List<String>
        val done: List<DoneCondition>
    }
    
    // One subclass per step type (AiStepDef, ParallelStepDef, BranchStepDef, etc.)
    
    data class DoneCondition(
        val condition: String,
        val severity: String,  // "blocking" | "warning"
    )
    
    data class RetryConfig(
        val maxAttempts: Int = 1,
        val backoff: String = "fixed",
        val delaySeconds: Int = 5,
    )

    fun parse(yamlContent: String): YamlWorkflowDefinition
    fun parseFile(path: Path): YamlWorkflowDefinition
    fun loadFromDirectory(dir: Path): List<YamlWorkflowDefinition>
}
```

#### `WorkflowDagCompiler`

```kotlin
/**
 * Compiles a YamlWorkflowDefinition into a TramAI Workflow<SpolaState, String>.
 * This is where YAML step types are mapped to Kotlin WorkflowBuilder DSL calls.
 */
class WorkflowDagCompiler(
    private val externalStepExecutorResolver: ExternalStepExecutorResolver = NoOpExternalStepExecutorResolver,
) {
    fun compile(
        definition: YamlWorkflowParser.YamlWorkflowDefinition,
        params: Map<String, Any?> = emptyMap(),
    ): Workflow<SpolaState, String> {
        // 1. Resolve template parameters ({{param}} → resolved value)
        // 2. Topological sort steps by depends_on
        // 3. Build WorkflowBuilder DSL programmatically
        // 4. Return built Workflow
    }
    
    private fun compileStep(
        stepDef: StepDef,
        paramResolver: ParamResolver,
        builder: WorkflowBuilder<SpolaState>,
    )
    
    private fun compileAiStep(def: AiStepDef, resolver: ParamResolver): WorkflowBuilder<SpolaState>.() -> Unit
    private fun compileParallelStep(def: ParallelStepDef, resolver: ParamResolver): WorkflowBuilder<SpolaState>.() -> Unit
    private fun compileBranchStep(def: BranchStepDef, resolver: ParamResolver): WorkflowBuilder<SpolaState>.() -> Unit
    private fun compileGateStep(def: GateStepDef, resolver: ParamResolver): WorkflowBuilder<SpolaState>.() -> Unit
    private fun compilePluginStep(def: PluginStepDef, resolver: ParamResolver): WorkflowBuilder<SpolaState>.() -> Unit
    private fun compileShellStep(def: ShellStepDef, resolver: ParamResolver): WorkflowBuilder<SpolaState>.() -> Unit
    private fun compileHttpStep(def: HttpStepDef, resolver: ParamResolver): WorkflowBuilder<SpolaState>.() -> Unit
    private fun compileDelayStep(def: DelayStepDef, resolver: ParamResolver): WorkflowBuilder<SpolaState>.() -> Unit
    private fun compileLocalStep(def: LocalStepDef, resolver: ParamResolver): WorkflowBuilder<SpolaState>.() -> Unit
}
```

#### `YamlWorkflowTemplate` (implements `WorkflowTemplate`)

```kotlin
/**
 * A WorkflowTemplate backed by a compiled YAML definition.
 * This bridges the YAML DSL into the existing WorkflowTemplateRegistry.
 */
class YamlWorkflowTemplate(
    override val name: String,
    override val version: String,
    private val definition: YamlWorkflowParser.YamlWorkflowDefinition,
    private val compiler: WorkflowDagCompiler,
) : WorkflowTemplate {
    override fun build(config: SpolaConfig, goal: String, parametersJson: String): Workflow<SpolaState, String> {
        val params = parseAndValidateParams(parametersJson)
        val mergedParams = applyDefaults(params)
        return compiler.compile(definition, mergedParams)
    }
    
    fun getParamSchema(): Map<String, ParamDef> = definition.params
    
    private fun parseAndValidateParams(json: String): Map<String, Any?>
    private fun applyDefaults(params: Map<String, Any?>): Map<String, Any?>
}
```

#### `YamlWorkflowLoader`

```kotlin
/**
 * Scans directories for .yaml workflow files and registers them.
 */
class YamlWorkflowLoader {
    companion object {
        /**
         * Load all YAML workflows from standard search paths and
         * register them into a WorkflowTemplateRegistry.
         */
        fun loadAndRegister(
            registry: WorkflowTemplateRegistry,
            searchPaths: List<Path> = defaultSearchPaths(),
        ): List<String>  // Returns list of registered workflow names
        
        fun defaultSearchPaths(): List<Path> = listOf(
            Path.of(System.getProperty("user.home"), ".spola", "workflows"),
            Path.of(".spola", "workflows"),  // project-local
            Path.of(".spola", "workflows.yaml"),  // single-file alternative
        )
    }
}
```

### 4.3 SpolaState Extension

```kotlin
/**
 * Extended SpolaState with workflow definition fields.
 * The `parameters` map holds resolved YAML workflow parameters.
 */
data class SpolaState(
    val goal: String,
    val config: SpolaConfig = SpolaConfig(),
    val agentDef: AgentDefinition? = null,
    val conversation: List<ChatMessage> = emptyList(),
    val turnCount: Int = 0,
    val intermediateResults: Map<String, String> = emptyMap(),
    val result: String? = null,
    val workflowNestingDepth: Int = 0,
    // NEW: DSL-specific fields
    val parameters: Map<String, Any?> = emptyMap(),  // Resolved YAML workflow params
    val stepResults: Map<String, Any?> = emptyMap(),  // Each step's result keyed by ID
    val currentStepId: String? = null,               // Currently executing step ID
) {
    companion object {
        fun initial(
            goal: String,
            config: SpolaConfig = SpolaConfig(),
            workflowNestingDepth: Int = 0,
            parameters: Map<String, Any?> = emptyMap(),
        ): SpolaState = SpolaState(
            goal = goal,
            config = config,
            workflowNestingDepth = workflowNestingDepth,
            parameters = parameters,
        )
    }
}
```

---

## 5. Template Resolution: How `{{param}}` Works

### 5.1 Parameter Source Hierarchy

1. **`parametersJson`** passed at execution time (from CLI `--param`, API body, or tool call)
2. **`params.default`** from YAML definition — used if no runtime value provided
3. **`goal`** — available as `{{goal}}` in any step goal field
4. **Context variables** — `{{step.<id>.result}}` references another step's result

### 5.2 Implementation

Use a simple `{{...}}` regex-based template engine (no Mustache/Handlebars dependency needed):

```kotlin
object ParamResolver {
    private val TEMPLATE_PATTERN = Regex("\\{\\{\\s*([^}\\s]+)\\s*\\}\\}")
    
    fun resolve(
        template: String,
        params: Map<String, Any?>,
        stepResults: Map<String, Any?> = emptyMap(),
    ): String = TEMPLATE_PATTERN.replace(template) { match ->
        val key = match.groupValues[1]
        when {
            key == "goal" -> params["goal"]?.toString() ?: match.value
            key.startsWith("params.") -> {
                val paramKey = key.removePrefix("params.")
                params[paramKey]?.toString() ?: match.value
            }
            key.startsWith("step.") -> {
                val parts = key.removePrefix("step.").split(".", limit = 2)
                val stepId = parts[0]
                val field = parts.getOrElse(1) { "result" }
                // Look up step result from stepResults map
                stepResults[stepId]?.let { result ->
                    when (result) {
                        is Map<*, *> -> result[field]?.toString()
                        else -> if (field == "result") result.toString() else null
                    }
                } ?: match.value
            }
            else -> params[key]?.toString() ?: match.value
        }
    }
}
```

---

## 6. "Definition of Done" — Verification

### 6.1 How `done` Conditions Work

Each step (and the overall workflow) can declare `done` conditions. These are **optional** — if absent, the step is considered done when it produces any output.

```yaml
- id: security-scan
  type: ai
  persona: "You are a Kotlin security specialist"
  goal: "Scan {{params.target}} for vulnerabilities"
  done:
    - condition: "no CRITICAL vulnerabilities found"
      severity: blocking    # Step fails if condition not met
    - condition: "report generated"
      severity: warning     # Logged but doesn't fail
```

### 6.2 Verification Strategies (3 tiers)

| Tier | Strategy | When | How |
|------|----------|------|-----|
| **1** | LLM self-verification | After every `ai` step | Ask the LLM: "Did you meet these conditions?" Append to the prompt |
| **2** | Regex/literal check | After `shell`, `http`, `plugin` steps | Parse stdout/response body for patterns |
| **3** | Dedicated verification step | Explicit `type: verify` step in YAML | Full agent run dedicated to checking results |

**Tier 1 implementation** (automatically appended to AI step prompts):

```kotlin
fun buildAiPrompt(persona: String, goal: String, doneConditions: List<DoneCondition>): String {
    val conditionsBlock = if (doneConditions.isEmpty()) "" else """
    
    ## Definition of Done
    After completing the goal, verify each of these conditions:
    ${doneConditions.joinToString("\n") { "- ${it.condition} (${it.severity})" }}
    
    For each condition, state CLEARLY whether it PASSED or FAILED.
    If any BLOCKING condition fails, describe what's missing.
    """
    return "$persona\n\n$goal$conditionsBlock"
}
```

**Tier 2 implementation** — for non-AI steps:

```kotlin
fun evaluateDoneConditions(
    result: String,
    conditions: List<DoneCondition>,
): List<DoneEvaluation> = conditions.map { condition ->
    val passed = when {
        condition.condition.startsWith("exit_code=") -> {
            val expected = condition.condition.removePrefix("exit_code=").trim().toIntOrNull()
            // Check actual exit code from shell step result
        }
        condition.condition.startsWith("contains=") -> {
            val expected = condition.condition.removePrefix("contains=").trim()
            result.contains(expected, ignoreCase = true)
        }
        condition.condition.startsWith("matches=") -> {
            val pattern = condition.condition.removePrefix("matches=").trim()
            result.contains(Regex(pattern))
        }
        else -> true  // Unstructured conditions skip runtime check
    }
    DoneEvaluation(condition, passed = passed)
}
```

### 6.3 Workflow-level `done`

The top-level `done` block is evaluated after ALL steps complete:

```yaml
done:
  - condition: "all steps passed"
    severity: blocking
  - condition: "report saved to {{params.output_path}}"
    severity: warning
```

Implementation: a final `localStep` appended to the DAG that runs after all user-defined steps.

---

## 7. Dependency Resolution and Topological Sort

### 7.1 Explicit `depends_on`

```yaml
- id: style-check
  type: ai
  depends_on: [security-scan]  # Runs after security-scan completes
```

### 7.2 Implicit Dependencies

If a step references `{{step.<id>.result}}` in its goal/persona, an implicit dependency is created.

### 7.3 Topological Sort

```kotlin
/**
 * Topologically sorts steps considering both explicit depends_on and
 * implicit template-parameter dependencies.
 * Throws if a cycle is detected.
 */
fun topologicalSort(steps: List<StepDef>): List<StepDef> {
    val graph = mutableMapOf<String, MutableList<String>>()
    steps.forEach { step ->
        val deps = step.dependsOn.toMutableList()
        // Extract implicit deps from {{step.<id>.result}} references
        val implicitDeps = EXTRACT_STEP_REF.findAll(step.toString())
            .map { it.groupValues[1] }
            .filter { it != step.id }
        deps.addAll(implicitDeps)
        graph[step.id] = deps
    }
    // Kahn's algorithm
    val inDegree = mutableMapOf<String, Int>()
    steps.forEach { inDegree[it.id] = 0 }
    graph.forEach { (id, deps) ->
        deps.forEach { dep ->
            inDegree[id] = (inDegree[id] ?: 0) + 1
        }
    }
    // ... standard topological sort ...
}
```

### 7.4 How Dependencies Become DAG Steps

Steps WITHOUT dependencies run in sequence (they're just ordered by list position).
Steps WITH `depends_on` that reference earlier steps can run in parallel groups:

```yaml
steps:
  - id: security-scan        # Step 1
  - id: style-check          # Depends on security-scan → runs after
  - id: test-coverage        # No dependency → runs in parallel with security-scan
  - id: summary              # Depends on [style-check, test-coverage] → runs after both
```

Gets compiled to:
```
parallelStep("security-scan_and_test-coverage")           # Parallel
  ├── aiStep("security-scan")
  └── aiStep("test-coverage")
aiStep("style-check")                                       # Sequential after security-scan
aiStep("summary")                                           # After both style-check and test-coverage
```

The compiler finds the optimal parallel structure automatically.

---

## 8. Migration Path

### 8.1 Phase 1: Parallel Operation (YAML + Kotlin)

Both systems work simultaneously:
- Hardcoded templates continue to work exactly as before
- YAML workflows are loaded and registered alongside them
- `WorkflowTemplateRegistry` merges both sources

### 8.2 Phase 2: YAML-First

- New workflow creation goes through YAML
- Built-in templates with `yaml-equivalent: true` in their spec are candidates for YAML rewrite
- Kotlin templates maintained for backward compat

### 8.3 Phase 3: Kotlin Templates → YAML Migration

The 4 built-in templates migrate to YAML:

**code-review.yaml:**
```yaml
name: code-review
version: "1.0.0"
params:
  target:
    type: string
    required: true
  files:
    type: string
    default: "**/*.kt"
steps:
  - id: parallel-review
    type: parallel
    items:
      - id: security-reviewer
        goal: "Review {{params.target}}/{{params.files}} for security vulnerabilities. Use read_file, search_files, git_diff."
        persona: "You are a Kotlin security specialist"
      - id: style-reviewer
        goal: "Review {{params.target}}/{{params.files}} for style issues and best practices."
        persona: "You are a Kotlin style expert"
      - id: test-reviewer
        goal: "Review {{params.target}}/{{params.files}} for test coverage gaps."
        persona: "You are a test coverage specialist"
  - id: summarize
    type: ai
    depends_on: [parallel-review]
    persona: "You are a code review aggregator"
    goal: >
      Synthesize the following reviews into a concise final summary.
      Security: {{step.security-reviewer.result}}
      Style: {{step.style-reviewer.result}}
      Tests: {{step.test-reviewer.result}}
```

### 8.4 Backward Compatibility Guarantee

The `WorkflowTemplate` interface and `WorkflowTemplateRegistry.register()` remain unchanged. Existing code calling `registry.resolve("code-review")` gets the same `Workflow<SpolaState, String>` regardless of whether the template was hardcoded or YAML-compiled.

---

## 9. Interaction with the Skill System (SKILL.md)

### 9.1 Relationship

Skills and Workflows serve different purposes:

| Aspect | Skills (SKILL.md) | Workflows (YAML) |
|--------|-------------------|-------------------|
| Purpose | Structured prompts + tool configs for the ReAct loop | Deterministic multi-step DAGs with type-checked steps |
| Execution | Injected as system prompt, LLM decides what to do | Engine-controlled, steps execute in declared order |
| Reusability | Composable via `skill_run` tool | Registerable templates, callable via `workflow_run` tool |
| State | None — just context injection | `SpolaState` with checkpoint/resume |
| File format | SKILL.md (YAML frontmatter + Markdown body) | .yaml (pure structured YAML) |

### 9.2 Calling a Skill from a Workflow

A YAML workflow step can reference a skill:

```yaml
- id: code-review
  type: ai
  skill: code-review           # Load persona/tools from SKILL.md
  goal: "Review src/ for issues"
```

Implementation: the compiler resolves `skill: code-review` by loading the SkillDefinition from the skill store and using its `body` as the persona, `tools_allowed` as the tool allowlist.

### 9.3 Calling a Workflow from a Skill

The `workflow_run` tool (already registered) lets any agent/skill kick off a workflow execution. No changes needed.

---

## 10. Visual Editor (Mini n8n)

### 10.1 Architecture

```
React Flow Editor
    │  Serializes to/from YAML
    ▼
YAML Workflow File
    │
    ▼
Spola DAG Compiler → TramAI Workflow
```

### 10.2 Editor ↔ YAML Contract

The YAML IS the source of truth. The editor:
1. **Reads** YAML → parses into React Flow nodes + edges
2. **Displays** step configurations in form panels
3. **Writes** modifications back to YAML
4. **Saves** to the same file path

### 10.3 React Flow Node Mappings

| YAML Step Type | React Flow Node Shape | Form Panel |
|----------------|----------------------|------------|
| `type: ai` | Rounded rectangle (blue) | Persona textarea, goal textarea, tool checkboxes |
| `type: parallel` | Wide rectangle (green) | Item editor (add/remove branches) |
| `type: branch` | Diamond (yellow) | Condition expression, branch target selectors |
| `type: gate` | Octagon (red) | Description, TTL, approver selection |
| `type: plugin` | Rectangle (gray) | Plugin type dropdown, config key-value editor |
| `type: shell` | Terminal icon (dark) | Command input, workdir, env var editor |
| `type: http` | Globe icon (purple) | Method dropdown, URL input, header editor |
| `type: delay` | Clock icon (orange) | Duration input |

### 10.4 Serialization Round-Trip

```yaml
# Editor reads → React Flow nodes
# User edits → modified node data
# Editor writes → YAML (preserving comments and ordering)
```

Use `snakeyaml` (already available via Jackson YAMLFactory) with `DumperOptions` to preserve:
- Key ordering (via `LinkedHashMap`)
- Comments (via `YamlCommentParser` utility)
- Multi-line strings (via `LiteralScalarStyle`)

---

## 11. New Files to Create

### 11.1 Core Compiler

| File | Package | Purpose |
|------|---------|---------|
| `YamlWorkflowParser.kt` | `dev.spola.workflow.dsl` | Parse YAML → intermediate IR |
| `YamlWorkflowDefinition.kt` | `dev.spola.workflow.dsl` | IR data classes (StepDef hierarchy) |
| `WorkflowDagCompiler.kt` | `dev.spola.workflow.dsl` | IR → TramAI Workflow |
| `ParamResolver.kt` | `dev.spola.workflow.dsl` | `{{param}}` template resolution |
| `DoneEvaluator.kt` | `dev.spola.workflow.dsl` | Definition-of-done verification |
| `DagTopologicalSort.kt` | `dev.spola.workflow.dsl` | Dependency resolution + cycle detection |
| `YamlWorkflowTemplate.kt` | `dev.spola.workflow.dsl` | WorkflowTemplate impl for YAML |
| `YamlWorkflowLoader.kt` | `dev.spola.workflow.dsl` | Directory scanning + registration |

### 11.2 Extensions to Existing Files

| File | Change |
|------|--------|
| `SpolaState.kt` | Add `parameters`, `stepResults`, `currentStepId` fields |
| `WorkflowTemplateRegistry.kt` | Add `registerYamlWorkflows(path)` helper, `listBySource()` for filtering |
| `WorkflowExecutionService.kt` | Thread `parameters` through to template.build() |
| `WorkflowCommands.kt` | Add `--param key=value` for YAML workflow params |
| `WorkflowTools.kt` | Update `WorkflowRunToolInput` to include parameters |
| `SpolaFactory.kt` | Call `YamlWorkflowLoader.loadAndRegister()` at startup |

### 11.3 Example YAML Workflow Files

| File | Purpose |
|------|---------|
| `~/.spola/workflows/code-review.yaml` | Port of built-in code-review |
| `~/.spola/workflows/jvm-debug.yaml` | Port of built-in jvm-debug |
| `~/.spola/workflows/jvm-refactor.yaml` | Port of built-in jvm-refactor |
| `~/.spola/workflows/jvm-migration.yaml` | Port of built-in jvm-migration |

### 11.4 Tests

| File | Purpose |
|------|---------|
| `YamlWorkflowParserTest.kt` | Parse various YAML inputs, validate error handling |
| `WorkflowDagCompilerTest.kt` | Compile IR → Workflow, verify step types and ordering |
| `DagTopologicalSortTest.kt` | Dependency resolution, cycle detection |
| `DoneEvaluatorTest.kt` | Done condition evaluation logic |
| `ParamResolverTest.kt` | Template resolution with various edge cases |
| `YamlWorkflowLoaderTest.kt` | Directory scanning, registration |
| `YamlWorkflowIntegrationTest.kt` | Full pipeline: YAML → execute workflow → verify result |

---

## 12. Step-by-Step Implementation Plan

### Day 1 — Parser + IR

1. Create `YamlWorkflowParser.kt` — parse YAML into typed IR
2. Create `YamlWorkflowDefinition.kt` — all IR data classes
3. Write `YamlWorkflowParserTest.kt`

### Day 2 — Dependency Resolution + Compiler

1. Create `DagTopologicalSort.kt` — Kahn's algorithm, cycle detection
2. Create `WorkflowDagCompiler.kt` — IR → WorkflowBuilder DSL calls
3. Create `ParamResolver.kt` — `{{param}}` resolution
4. Write tests for each component

### Day 3 — Done Conditions + SpolaState Extension

1. Create `DoneEvaluator.kt` — tier 1/2/3 verification
2. Extend `SpolaState.kt` — add `parameters`, `stepResults`, `currentStepId`
3. Write `DoneEvaluatorTest.kt`

### Day 4 — Registration + Loading

1. Create `YamlWorkflowTemplate.kt` — bridge IR into WorkflowTemplate interface
2. Create `YamlWorkflowLoader.kt` — directory scanning + registry registration
3. Wire into `SpolaFactory.kt` startup
4. Update `WorkflowExecutionService.kt` to pass parameters

### Day 5 — CLI + API Integration

1. Update `WorkflowCommands.kt` — `--param` flag, YAML workflow listing
2. Update `WorkflowTools.kt` — parameter passthrough
3. Write `YamlWorkflowIntegrationTest.kt`

### Day 6 — Migration of Built-in Templates

1. Create YAML equivalents for code-review, jvm-debug, jvm-refactor, jvm-migration
2. Verify identical behavior between Kotlin and YAML versions
3. Add `yaml-equivalent: true` annotation (or keep both registered for testing)

### Day 7 — Documentation + Polish

1. Write usage docs (`docs/workflows/YAML_WORKFLOWS.md`)
2. Add schema validation with helpful error messages
3. Write example YAML workflows (hotfix, feature, refactor from spec-process-engine.md)

---

## 13. Edge Cases and Error Handling

### 13.1 What happens when a YAML workflow has a syntax error?

- **Parse time:** `YamlWorkflowParser` throws a descriptive `YamlWorkflowParseException` with file, line number, and error message
- **Registration time:** Failed YAML files are logged as warnings, other workflows still load
- **Execution time:** If the IR is valid but compilation fails, `WorkflowDagCompiler` throws `WorkflowCompilationException`

### 13.2 What happens when a step references a non-existent dependency?

```yaml
- id: my-step
  depends_on: [non-existent-step]
```

`DagTopologicalSort` throws `MissingDependencyException("Step 'my-step' depends on 'non-existent-step' which is not defined")` during compilation (not at parse time, so all structural validation happens before execution).

### 13.3 What happens with circular dependencies?

```yaml
- id: a
  depends_on: [b]
- id: b
  depends_on: [a]
```

`DagTopologicalSort` detects the cycle and throws `CyclicDependencyException("Circular dependency detected: a → b → a")`.

### 13.4 What happens when a `done` condition fails at runtime?

- **blocking severity:** The step result is marked as FAILED. The workflow can either:
  - Halt (default behavior)
  - Route to an error handler step if a branch step follows
- **warning severity:** The condition is logged but the workflow continues

### 13.5 What happens when a parameter is missing?

```yaml
params:
  target:
    type: string
    required: true
```

If `target` is not provided at execution time AND has no default, `YamlWorkflowTemplate.build()` throws `MissingParameterException("Required parameter 'target' was not provided")` before the workflow starts.

### 13.6 File watching for hot-reload

Add a `java.nio.file.WatchService` that monitors the workflows directory and re-registers changed YAML files:

```kotlin
class YamlWorkflowWatcher(
    private val registry: WorkflowTemplateRegistry,
    private val watchDir: Path,
) {
    fun start() {
        val watchService = FileSystems.getDefault().newWatchService()
        watchDir.register(watchService, 
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
        )
        Thread.ofPlatform().daemon().start {
            while (true) {
                val key = watchService.take()
                // Re-parse changed YAML files and update registry
                key.pollEvents().forEach { event ->
                    val file = watchDir.resolve(event.context() as Path)
                    if (file.toString().endsWith(".yaml")) {
                        reloadWorkflow(file)
                    }
                }
                key.reset()
            }
        }
    }
}
```

---

## 14. Complete Example: Feature Workflow

```yaml
name: feature
version: "1.0.0"
description: "Implement a feature with compile verification, review, and human approval"

params:
  project:
    type: string
    required: true
    description: "Gradle project path (e.g., :spola-backend-core)"
  max_retries:
    type: number
    default: 3
    description: "Maximum retries on compilation failure"

steps:
  - id: implement
    type: ai
    persona: "You are a Kotlin developer implementing features. Use file tools to write code."
    goal: "Implement: {{goal}} in project {{params.project}}"
    tools: [read_file, write_file, search_files, search_symbols, jvm_file_outline]

  - id: verify-compile
    type: plugin
    depends_on: [implement]
    plugin_type: compile_project
    config:
      project: "{{params.project}}"
      tasks: "compileKotlin"

  - id: check-compile
    type: branch
    condition: "{{step.verify-compile.result.passed}} == true"
    branches:
      "true": review
      "false": fix-compile

  - id: fix-compile
    type: ai
    persona: "You are a Kotlin developer. Fix compilation errors."
    goal: "Fix these compilation errors: {{step.verify-compile.result.output}}"
    retry:
      max_attempts: "{{params.max_retries}}"
    depends_on: [verify-compile]

  - id: review
    type: ai
    depends_on: [check-compile]
    persona: "You are a senior code reviewer. Read code, don't edit it."
    goal: "Review the implementation for correctness, style, and edge cases."
    tools: [read_file, search_files, git_diff]

  - id: approval-gate
    type: gate
    depends_on: [review]
    description: "Review the implementation and approve or reject"
    ttl_seconds: 3600
    notify:
      telegram: true

  - id: commit
    type: plugin
    depends_on: [approval-gate]
    plugin_type: git_commit
    config:
      message: "feat: {{goal}}"

done:
  - condition: "verification passed"
    severity: blocking
  - condition: "code has been committed"
    severity: warning
```

---

## 15. Summary: Key Design Decisions

1. **No new runtime dependencies.** Jackson YAMLFactory is already pulled in by `SkillParser`. Param resolution is a regex engine (~40 lines). SnakeYAML is already a transitive dep.

2. **No changes to TramAI orchestration.** The YAML DSL is a pure translation layer. Compiled workflows are indistinguishable from hand-written ones.

3. **Backward compatibility.** The `WorkflowTemplate` interface is the contract. Existing hardcoded templates, CLI commands, API endpoints, and the dispatcher all continue to work unchanged.

4. **Parameters are validated at composition time, not execution time.** Missing required params fail fast when the template is instantiated.

5. **YAML is the source of truth.** The visual editor serializes/deserializes YAML. There is no parallel database of DAG state.

6. **File watching for hot-reload.** Changes to YAML files in the watched directory are picked up without restart (powered by `java.nio.file.WatchService`).

7. **`done` conditions are optional verification gates.** They don't define the DAG structure — they annotate steps with post-condition checks.
