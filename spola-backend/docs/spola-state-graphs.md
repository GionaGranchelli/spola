# Spola State Graphs — Typed Workflow DSL

**Spola State Graphs** are the TramAI orchestration module's sealed-class-based workflow DSL, rebranded for Spola. They provide compile-time-safe state transitions, checkpointed persistence, and first-class agent step types — all on the JVM.

> The only JVM-native stateful agent orchestration framework. Python has no sealed classes — LangGraph cannot replicate this level of compile-time safety.

---

## Table of Contents

1. [Introduction](#introduction)
2. [Quick Start](#quick-start)
3. [Complete DSL Reference](#complete-dsl-reference)
   - [workflow {} builder](#workflow--builder)
   - [localStep](#localstep)
   - [aiStep](#aistep)
   - [httpStep](#httpstep)
   - [shellStep](#shellstep)
   - [hermesStep](#hermesstep)
   - [codexStep](#codexstep)
   - [mcpStep](#mcpstep)
   - [pluginStep](#pluginstep)
   - [gateStep](#gatestep)
   - [delayStep](#delaystep)
   - [branchStep](#branchstep)
   - [parallelStep](#parallelstep)
4. [Compile-time Edge Validation](#compile-time-edge-validation)
5. [ReplayPolicy](#replaypolicy)
6. [WorkflowPersistence](#workflowpersistence)
7. [Lifecycle](#lifecycle)
8. [Full Example](#full-example)
9. [LangGraph Comparison](#langgraph-comparison)

---

## Introduction

A **Spola State Graph** is a typed `Workflow<S, R>` constructed via the `workflow()` DSL builder. The state type `S` is a Kotlin `sealed class` — each subclass represents a well-defined state in the graph. The result type `R` is the final output extracted from the terminal state.

Key concepts:

- **Typed state** — `Workflow<S, R>` parameterises over the state type `S`. The compiler verifies all state transitions.
- **Sealed-class branches** — `branchStep` accepts a `select: (S) -> String` lambda. Because `S` is sealed, the Kotlin `when` expression enforces exhaustive matching.
- **Checkpointed persistence** — Every top-level step boundary can persist state to a `WorkflowCheckpointStore`, enabling resume from crash or scheduled wake-up.
- **12 step types** — Pure transforms, AI calls, HTTP requests, shell commands, agent CLI invocations, MCP tool calls, plugin steps, human gates, delays, conditional branching, and parallel execution.
- **Replay-aware** — Each step declares a `ReplayPolicy` (PURE, IDEMPOTENT, EXTERNALLY_IDEMPOTENT, NON_REPLAYABLE) for safe distributed execution.

---

## Quick Start

```kotlin
import dev.tramai.orchestration.*
import java.util.concurrent.TimeUnit

// 1. Define your state as a sealed class
sealed class ReviewState {
    data object Init : ReviewState()
    data class CodeFetched(val files: List<String>) : ReviewState()
    data class AiReviewed(val results: Map<String, String>) : ReviewState()
    data class Approved(val summary: String) : ReviewState()
    data class Rejected(val reason: String) : ReviewState()
}

// 2. Build the workflow
val reviewWorkflow = workflow<ReviewState>(
    name = "code-review",
    definitionVersion = "1",
) {
    localStep("fetch-code") { state, _ ->
        when (state) {
            is ReviewState.Init -> ReviewState.CodeFetched(
                files = listOf("src/main.kt", "src/utils.kt")
            )
            else -> state
        }
    }

    branchStep("review-decision", select = { state ->
        when {
            state is ReviewState.Approved -> "approved"
            state is ReviewState.Rejected -> "rejected"
            else -> "needs-review"
        }
    }) {
        branch("needs-review") {
            localStep("trigger-review") { state, _ ->
                state // hand off to external reviewer
            }
        }
        branch("approved") {
            localStep("finalize") { state, _ -> state }
        }
        default {
            localStep("handle-unknown") { state, _ -> state }
        }
    }
}.build { state ->
    when (state) {
        is ReviewState.Approved -> "Approved: ${state.summary}"
        is ReviewState.Rejected -> "Rejected: ${state.reason}"
        else -> "Incomplete"
    }
}

// 3. Run it
suspend fun main() {
    val result = reviewWorkflow.run(initialState = ReviewState.Init)
    println(result)
}
```

---

## Complete DSL Reference

### `workflow {}` builder

Entry-point function. Returns a `WorkflowBuilder<S>`.

```kotlin
inline fun <reified S> workflow(
    name: String,
    definitionVersion: String = DEFAULT_WORKFLOW_DEFINITION_VERSION, // "1"
    configure: WorkflowBuilder<S>.() -> Unit,
): WorkflowBuilder<S>
```

Build into a `Workflow<S, R>`:

```kotlin
class WorkflowBuilder<S> {
    var schedule: WorkflowScheduleDefinition? = null

    inline fun <reified R> build(
        stopPolicy: StopPolicy = StopPolicy(),
        clock: Clock = Clock.systemUTC(),
        externalStepExecutorResolver: ExternalStepExecutorResolver = NoOpExternalStepExecutorResolver,
        noinline resultSelector: (S) -> R,
    ): Workflow<S, R>
}
```

### localStep

Pure state transformation with no side effects.

```kotlin
fun localStep(
    name: String,
    transform: suspend (S, WorkflowContext) -> S,
)
```

- **ReplayPolicy**: `PURE` (always safe to replay)
- **Use for**: Data transformation, validation, state mapping

```kotlin
localStep("normalize-input") { state, _ ->
    when (state) {
        is InputState.Raw -> InputState.Normalized(state.data.trim())
        else -> state
    }
}
```

### aiStep

Invokes an arbitrary suspend function (typically an LLM call). Four overloads:

**Overload 1 — Legacy (defaults to `ReplayPolicy.IDEMPOTENT`):**
```kotlin
fun <I, O> aiStep(
    name: String,
    input: (S) -> I,
    invoke: suspend (I) -> O,
    merge: (S, O) -> S,
)
```

**Overload 2 — Explicit replay policy:**
```kotlin
fun <I, O> aiStep(
    name: String,
    replayPolicy: ReplayPolicy,
    input: (S) -> I,
    invoke: suspend (I) -> O,
    merge: (S, O) -> S,
)
```

**Overload 3 — With idempotency key:**
```kotlin
fun <I, O> aiStep(
    name: String,
    replayPolicy: ReplayPolicy,
    idempotencyKey: (S, WorkflowContext) -> String?,
    input: (S) -> I,
    invoke: suspend (I) -> O,
    merge: (S, O) -> S,
)
```

**Overload 4 — Preferred: WorkflowContext-aware (defaults to `ReplayPolicy.NON_REPLAYABLE`):**
```kotlin
fun <I, O> aiStep(
    name: String,
    replayPolicy: ReplayPolicy = ReplayPolicy.NON_REPLAYABLE,
    idempotencyKey: ((S, WorkflowContext) -> String?)? = null,
    input: (S, WorkflowContext) -> I,
    invoke: suspend (I, WorkflowContext) -> O,
    merge: (S, O, WorkflowContext) -> S,
)
```

- **`aiStep` does NOT apply framework-owned prompt defenses** — use `hermesStep` or `codexStep` for that.
- **Validation**: `PURE` is rejected; `EXTERNALLY_IDEMPOTENT` requires a non-null `idempotencyKey`.

```kotlin
aiStep(
    name = "classify-intent",
    replayPolicy = ReplayPolicy.IDEMPOTENT,
    input = { state: ReviewState -> state.toString() },
    invoke = { prompt -> llmClient.classify(prompt) },
    merge = { state, classification ->
        when (classification) {
            "approve" -> ReviewState.Approved("Auto-approved")
            "reject" -> ReviewState.Rejected(classification)
            else -> state
        }
    },
)
```

### httpStep

Makes an HTTP request with configurable timeout, retry, and security policies.

```kotlin
fun httpStep(
    name: String,
    config: HttpStepConfig = HttpStepConfig(),
    request: suspend (S, WorkflowContext) -> HttpRequest,
    merge: suspend (S, HttpResponse, WorkflowContext) -> S,
)
```

**`HttpRequest`**:
```kotlin
data class HttpRequest(
    val method: String,   // GET, POST, PUT, DELETE, PATCH
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)
```

**`HttpResponse`**:
```kotlin
data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String?,
)
```

**`HttpStepConfig`**:
```kotlin
data class HttpStepConfig(
    val timeoutSeconds: Long = 30,
    val maxResponseBytes: Long = 1_048_576,   // 1 MB
    val retryOnStatus: Set<Int> = emptySet(),
    val maxRetries: Int = 0,
    val allowedHosts: Set<String>? = null,    // null = any public host
)
```

- **Security**: Private/loopback addresses are rejected unless explicitly in `allowedHosts`.
- **SSRF protection**: Resolves DNS and rejects private, link-local, and CGNAT addresses.
- **ReplayPolicy**: Auto-detected from HTTP method: GET/HEAD/OPTIONS/PUT/DELETE → `IDEMPOTENT`; POST/PATCH → `NON_REPLAYABLE` (or `EXTERNALLY_IDEMPOTENT` if `Idempotency-Key` header is present).

```kotlin
httpStep(
    name = "fetch-pr",
    config = HttpStepConfig(
        timeoutSeconds = 15,
        maxRetries = 2,
        retryOnStatus = setOf(429, 502, 503),
    ),
    request = { state, _ ->
        HttpRequest(
            method = "GET",
            url = "https://api.github.com/repos/owner/repo/pulls/42",
            headers = mapOf("Authorization" to "Bearer ${System.getenv("GITHUB_TOKEN")}"),
        )
    },
    merge = { state, response, _ ->
        ReviewState.CodeFetched(files = parseFiles(response.body ?: "[]"))
    },
)
```

### shellStep

Executes a shell command with security policies.

```kotlin
fun shellStep(
    name: String,
    config: ShellStepConfig = ShellStepConfig(),
    definition: ShellCommandDefinition,
    command: suspend (S, WorkflowContext) -> ShellCommand,
    merge: suspend (S, ShellResult, WorkflowContext) -> S,
)
```

**`ShellCommandDefinition`** (static metadata, validated at build time):
```kotlin
data class ShellCommandDefinition(
    val executable: String,           // e.g. "sh", "git"
    val hasWorkdir: Boolean = false,
    val envKeys: Set<String> = emptySet(),
)
```

**`ShellCommand`** (runtime command):
```kotlin
data class ShellCommand(
    val command: List<String>,
    val workdir: String? = null,
    val env: Map<String, String> = emptyMap(),
)
```

**`ShellResult`**:
```kotlin
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean = false,
)
```

**`ShellStepConfig`**:
```kotlin
data class ShellStepConfig(
    val timeoutSeconds: Long = 60,
    val maxOutputBytes: Long = 1_048_576,
    val failOnNonZeroExit: Boolean = true,
    val failOnStderr: Boolean = false,
    val allowedCommands: Set<String> = emptySet(),  // empty = none allowed
    val deniedCommands: Set<String> = emptySet(),
    val charset: Charset = Charsets.UTF_8,
)
```

- **ReplayPolicy**: `NON_REPLAYABLE` (shell commands may have side effects)

```kotlin
shellStep(
    name = "compile-project",
    config = ShellStepConfig(
        timeoutSeconds = 120,
        allowedCommands = setOf("sh", "gradle"),
    ),
    definition = ShellCommandDefinition(
        executable = "sh",
        hasWorkdir = true,
    ),
    command = { state, _ ->
        ShellCommand(
            command = listOf("sh", "-c", "./gradlew build"),
            workdir = "/home/user/project",
        )
    },
    merge = { state, result, _ ->
        ReviewState.Compiled(result.exitCode == 0)
    },
)
```

### hermesStep

Invokes the Hermes CLI (`hermes chat -q <prompt> --model <model>`).

```kotlin
fun hermesStep(
    name: String,
    config: HermesStepConfig = HermesStepConfig(),
    prompt: suspend (S, WorkflowContext) -> String,
    merge: suspend (S, String, WorkflowContext) -> S,
)

fun <T> hermesStep(
    name: String,
    config: HermesStepConfig = HermesStepConfig(),
    prompt: suspend (S, WorkflowContext) -> String,
    decode: suspend (String) -> T,        // optional structured decode
    merge: suspend (S, T, WorkflowContext) -> S,
)
```

**`HermesStepConfig`**:
```kotlin
data class HermesStepConfig(
    val timeoutSeconds: Long = 180,
    val maxOutputBytes: Long = 1_048_576,
    val cliPath: String = "hermes",
    val model: String = "claude-sonnet-4",
    val security: StepSecurityConfig = StepSecurityConfig.Default,
)
```

- **ReplayPolicy**: `NON_REPLAYABLE`
- **Security**: Applies framework-owned prompt defense layers (sanitizer, output validator, instruction defense) via `StepSecurityConfig`

```kotlin
hermesStep(
    name = "review-code",
    config = HermesStepConfig(model = "claude-opus-4"),
    prompt = { state, _ ->
        "Review these files for bugs and style issues:\n${(state as ReviewState.CodeFetched).files.joinToString("\n")}"
    },
    merge = { state, response, _ ->
        ReviewState.AiReviewed(mapOf("review" to response))
    },
)
```

### codexStep

Invokes the Codex CLI (`codex exec -- <prompt>`).

```kotlin
fun codexStep(
    name: String,
    config: CodexStepConfig = CodexStepConfig(),
    prompt: suspend (S, WorkflowContext) -> String,
    merge: suspend (S, String, WorkflowContext) -> S,
)

fun <T> codexStep(
    name: String,
    config: CodexStepConfig = CodexStepConfig(),
    prompt: suspend (S, WorkflowContext) -> String,
    decode: suspend (String) -> T,
    merge: suspend (S, T, WorkflowContext) -> S,
)
```

**`CodexStepConfig`**:
```kotlin
data class CodexStepConfig(
    val timeoutSeconds: Long = 180,
    val maxOutputBytes: Long = 1_048_576,
    val cliPath: String = "codex",
    val workdir: String? = null,
    val security: StepSecurityConfig = StepSecurityConfig.Default,
)
```

- **ReplayPolicy**: `NON_REPLAYABLE`

```kotlin
codexStep(
    name = "apply-fixes",
    config = CodexStepConfig(workdir = "/home/user/project"),
    prompt = { state, _ ->
        "Apply the following review suggestions:\n${(state as ReviewState.AiReviewed).results}"
    },
    merge = { state, output, _ ->
        ReviewState.FixesApplied(output)
    },
)
```

### mcpStep

Calls an MCP (Model Context Protocol) tool via a subprocess server.

```kotlin
fun mcpStep(
    name: String,
    config: McpStepConfig = McpStepConfig(),
    definition: McpToolCallDefinition,
    toolCall: suspend (S, WorkflowContext) -> McpToolCall,
    merge: suspend (S, McpToolResult, WorkflowContext) -> S,
)

fun <T> mcpStep(
    name: String,
    config: McpStepConfig = McpStepConfig(),
    definition: McpToolCallDefinition,
    toolCall: suspend (S, WorkflowContext) -> McpToolCall,
    decode: suspend (McpToolResult) -> T,
    merge: suspend (S, T, WorkflowContext) -> S,
)
```

**`McpToolCallDefinition`** (static build-time metadata):
```kotlin
data class McpToolCallDefinition(
    val serverCommand: List<String>,
    val serverEnv: Map<String, String> = emptyMap(),
    val toolName: String,
    val argumentKeys: Set<String> = emptySet(),
)
```

**`McpToolCall`** (runtime call):
```kotlin
data class McpToolCall(
    val serverCommand: List<String>,
    val serverEnv: Map<String, String> = emptyMap(),
    val toolName: String,
    val arguments: Map<String, String> = emptyMap(),
)
```

**`McpToolResult`**:
```kotlin
data class McpToolResult(
    val content: String?,
    val structuredContent: String?,
    val isError: Boolean = false,
)
```

**`McpStepConfig`**:
```kotlin
data class McpStepConfig(
    val timeoutSeconds: Long = 30,
    val maxOutputBytes: Long = 1_048_576,
    val reconnect: Boolean = true,
    val toolAllowlist: Set<String>? = null,
    val allowedCommands: Set<String> = emptySet(),
    val deniedCommands: Set<String> = emptySet(),
) {
    companion object {
        fun unrestricted(): McpStepConfig
    }
}
```

- **ReplayPolicy**: `NON_REPLAYABLE`
- The runtime `McpToolCall` must match the static `McpToolCallDefinition` (server command, env, tool name, argument keys) — enforced at runtime.

```kotlin
mcpStep(
    name = "search-codebase",
    config = McpStepConfig(timeoutSeconds = 60),
    definition = McpToolCallDefinition(
        serverCommand = listOf("npx", "@modelcontextprotocol/server-github"),
        toolName = "search_code",
        argumentKeys = setOf("query"),
    ),
    toolCall = { state, _ ->
        McpToolCall(
            serverCommand = listOf("npx", "@modelcontextprotocol/server-github"),
            toolName = "search_code",
            arguments = mapOf("query" to "TODO"),
        )
    },
    merge = { state, result, _ ->
        ReviewState.SearchResults(result.content ?: "")
    },
)
```

### pluginStep

Executes an external step via the `ExternalStepExecutor` SPI. Two overloads:

```kotlin
fun pluginStep(
    name: String,
    type: String,                                // matches ExternalStepExecutorFactory.typeId
    config: Map<String, Any?> = emptyMap(),
)

fun pluginStep(
    name: String,
    type: String,
    config: Map<String, Any?> = emptyMap(),
    merge: suspend (S, Map<String, Any?>, WorkflowContext) -> S,
)
```

- **Without a custom `merge`**, the step expects `S` to be `Map<String, Any?>` and shallow-merges the result.
- **ReplayPolicy**: `NON_REPLAYABLE`
- **SPI**: Register via `ExternalStepExecutorRegistry.register(factory)`.

```kotlin
// Register
val registry = ExternalStepExecutorRegistry()
registry.register(object : ExternalStepExecutorFactory {
    override val typeId = "slack-notify"
    override fun create() = ExternalStepExecutor { spec ->
        sendSlackMessage(spec["channel"] as String, spec["message"] as String)
        emptyMap()
    }
})

// In workflow
pluginStep(
    name = "notify-reviewer",
    type = "slack-notify",
    config = mapOf("channel" to "#reviews"),
)
```

### gateStep

Human-in-the-loop gate. Throws `WorkflowGateRejectedException` when rejected.

```kotlin
fun gateStep(
    name: String,
    decide: suspend (S, WorkflowContext) -> GateDecision,
)
```

**`GateDecision`**:
```kotlin
data class GateDecision(
    val allowed: Boolean,
    val reason: String? = null,
) {
    companion object {
        fun allow(): GateDecision
        fun reject(reason: String): GateDecision
    }
}
```

- **ReplayPolicy**: `PURE` (no side effects — the gate is re-evaluated on replay)
- **Exception**: `WorkflowGateRejectedException`

```kotlin
gateStep("human-approval") { state, _ ->
    val decision = promptForHumanDecision(
        "Approve review of ${(state as ReviewState.AiReviewed).results.size} files?"
    )
    when (decision) {
        "approve" -> GateDecision.allow()
        "reject" -> GateDecision.reject("Human declined")
        else -> GateDecision.allow() // timeout = auto-approve
    }
}
```

### delayStep

Checkpointed delay. **Requires `WorkflowPersistence`** — the workflow is suspended and can be resumed after the delay.

```kotlin
fun delayStep(
    name: String,
    duration: Long,
    unit: TimeUnit,
)
```

- **ReplayPolicy**: `PURE`
- **Exception**: `WorkflowSuspendedException` — the workflow yields control until the delay expires.
- The delay metadata is stored in the checkpoint; the `WorkflowDelayWakeupScheduler` is called for external scheduling.

```kotlin
delayStep("wait-for-review", duration = 24, unit = TimeUnit.HOURS)
```

### branchStep

Conditional branching with exhaustive sealed-class matching.

```kotlin
fun branchStep(
    name: String,
    select: (S) -> String,
    configure: BranchBuilder<S>.() -> Unit,
)
```

**`BranchBuilder<S>`**:
```kotlin
class BranchBuilder<S> {
    fun branch(key: String, configure: BranchWorkflowBuilder<S>.() -> Unit)
    fun default(configure: BranchWorkflowBuilder<S>.() -> Unit)
}
```

- **ReplayPolicy**: `PURE`
- **Compile-time safety**: The `select` lambda's `when` expression on the sealed class `S` is compile-time safe — the compiler enforces exhaustive matching of all state subclasses. However, the String-keyed branch routing (matching the returned key against defined branches) is runtime-only: an unrecognized key throws `WorkflowBranchSelectionException`.
- **Exception**: `WorkflowBranchSelectionException` when the selected key has no matching branch or default.

```kotlin
branchStep("route-by-status", select = { state ->
    when (state) {
        is ReviewState.Init -> "init"
        is ReviewState.CodeFetched -> "fetch"
        is ReviewState.AiReviewed -> "review"
        is ReviewState.Approved -> "done"
        is ReviewState.Rejected -> "done"
    }
}) {
    branch("init") {
        localStep("start-processing") { state, _ -> state }
    }
    branch("fetch") {
        httpStep("fetch-details") { /* ... */ }
    }
    branch("review") {
        aiStep("deep-review") { /* ... */ }
    }
    branch("done") {
        localStep("finalize") { state, _ -> state }
    }
}
```

### parallelStep

Executes items in parallel with a bounded semaphore.

```kotlin
fun <I, O> parallelStep(
    name: String,
    items: (S) -> Iterable<I>,
    invoke: suspend (I) -> O,
    merge: (S, List<O>) -> S,
)
```

- **ReplayPolicy**: `NON_REPLAYABLE`
- **Bounded by**: `StopPolicy.maxParallelBranches` (default: 16)
- **Exception**: `WorkflowLimitExceededException` if items exceed `maxParallelBranches`

```kotlin
parallelStep(
    name = "review-all-files",
    items = { state -> (state as ReviewState.CodeFetched).files },
    invoke = { file -> llmClient.reviewFile(file) },
    merge = { state, reviews ->
        ReviewState.AiReviewed(reviews.mapIndexed { i, r ->
            (state as ReviewState.CodeFetched).files[i] to r
        }.toMap())
    },
)
```

---

## Compile-time Edge Validation

Sealed classes are Kotlin's mechanism for exhaustive type hierarchies. Combined with `branchStep`, every possible state is handled at compile time:

```kotlin
sealed class OrderState {
    data object Pending : OrderState()
    data class Paid(val amount: Double) : OrderState()
    data class Shipped(val tracking: String) : OrderState()
    data class Delivered(val signature: String) : OrderState()
}

branchStep("order-flow", select = { state ->
    when (state) {
        is OrderState.Pending -> "pending"
        is OrderState.Paid -> "paid"
        is OrderState.Shipped -> "shipped"
        is OrderState.Delivered -> "delivered"
        // Compiler ERROR if a new subclass is added without handling it
    }
}) {
    branch("pending") { /* ... */ }
    branch("paid") { /* ... */ }
    branch("shipped") { /* ... */ }
    branch("delivered") { /* ... */ }
}
```

**Python has no sealed classes** — LangGraph cannot replicate this. In Python, branches are stringly-typed and errors only surface at runtime. Spola State Graphs catch missing branches during compilation.

Additional compile-time validation performed by `WorkflowBuilder.build()`:

- **Duplicate step names**: Step names must be unique across the full workflow (including nested branches).
- **Plugin step types**: Validated at runtime against the `ExternalStepExecutorRegistry` — no compile-time check for plugin registration.
- **MCP command policies**: Validated statically against `allowedCommands`.
- **`aiStep` replay policies**: `PURE` is rejected; `EXTERNALLY_IDEMPOTENT` requires an idempotency key.
- **Step name non-blank**: All steps must have non-blank names.

---

## ReplayPolicy

Each step declares how it can be safely replayed by a distributed worker:

| Policy | Description | Example Steps |
|--------|-------------|---------------|
| `PURE` | Deterministic, no side effects. Always safe to replay. | `localStep`, `gateStep`, `delayStep`, `branchStep` |
| `IDEMPOTENT` | Safe to replay because repeated execution produces the same result (e.g., read-only HTTP). | `aiStep` (legacy default), GET/HEAD/OPTIONS/PUT/DELETE HTTP |
| `EXTERNALLY_IDEMPOTENT` | Safe to replay with an explicit idempotency key issued to the external system. | POST/PATCH with `Idempotency-Key` header |
| `NON_REPLAYABLE` | Must NOT be replayed — side effects cannot be safely repeated. Worker raises `NonReplayableStepStateUnknownException` on resume. | `shellStep`, `hermesStep`, `codexStep`, `mcpStep`, `pluginStep`, `parallelStep`, `aiStep` (context-aware default) |

The `ReplayPolicy` is defined in `StepAttemptRecord.kt`:

```kotlin
enum class ReplayPolicy {
    PURE,
    IDEMPOTENT,
    EXTERNALLY_IDEMPOTENT,
    NON_REPLAYABLE,
}
```

---

## WorkflowPersistence

`WorkflowPersistence<S>` configures checkpoint storage and optional lease coordination:

```kotlin
data class WorkflowPersistence<S>(
    val checkpointStore: WorkflowCheckpointStore,
    val stateCodec: WorkflowStateCodec<S>,
    val delayWakeupScheduler: WorkflowDelayWakeupScheduler? = null,
    val leaseStore: WorkflowLeaseStore? = null,
    val leasePolicy: WorkflowLeasePolicy? = null,
    val deleteCheckpointOnCompletion: Boolean = true,
)
```

### Checkpoint Stores

| Store | Description | Constructor |
|-------|-------------|-------------|
| `InMemoryWorkflowCheckpointStore` | In-memory for tests and prototyping | `InMemoryWorkflowCheckpointStore()` |
| `FileWorkflowCheckpointStore` | Properties-based envelope on local filesystem | `FileWorkflowCheckpointStore(rootDirectory: Path)` |
| `MarkdownWorkflowCheckpointStore` | Human-readable Markdown with YAML front matter | `MarkdownWorkflowCheckpointStore(rootDirectory: Path)` |
| `JdbcWorkflowCheckpointStore` | PostgreSQL-ready via `javax.sql.DataSource` | `JdbcWorkflowCheckpointStore(dataSource: DataSource)` |

**`JdbcWorkflowCheckpointStore`** creates/uses a table named `tramai_workflow_checkpoint` (configurable via `JdbcWorkflowCheckpointTable`). Call `createTableSql()` for the DDL.

```kotlin
val jdbcStore = JdbcWorkflowCheckpointStore(myDataSource)
// jdbcStore.createTableSql() → CREATE TABLE tramai_workflow_checkpoint ( ...
```

### State Codec

```kotlin
interface WorkflowStateCodec<S> {
    fun encode(state: S): String
    fun decode(payload: String): S
}
```

### Lease Store

For multi-worker coordination:

```kotlin
data class WorkflowLeasePolicy(
    val ownerId: String,
    val leaseDurationMillis: Long = 30_000,
)

interface WorkflowLeaseStore {
    suspend fun currentLease(workflowName: String, workflowId: String): WorkflowLease?
    suspend fun claim(workflowName, workflowId, ownerId, checkpointRevision, leaseDurationMillis): WorkflowLease
    suspend fun renew(lease, checkpointRevision, leaseDurationMillis): WorkflowLease
    suspend fun release(lease: WorkflowLease)
}
```

Built-in implementations: `InMemoryWorkflowLeaseStore`, `FileWorkflowLeaseStore`, `JdbcWorkflowLeaseStore`.

### Full persistence wiring

```kotlin
val persistence = WorkflowPersistence(
    checkpointStore = JdbcWorkflowCheckpointStore(dataSource),
    stateCodec = JsonWorkflowStateCodec(),
    leaseStore = JdbcWorkflowLeaseStore(dataSource),
    leasePolicy = WorkflowLeasePolicy(ownerId = "worker-1"),
    delayWakeupScheduler = myScheduler,
)

workflow.run(
    initialState = MyState.Init,
    persistence = persistence,
    observer = myObserver,
)

// Or resume
workflow.resume(
    context = WorkflowContext(workflowId = existingId),
    persistence = persistence,
)
```

---

## Lifecycle

### StopPolicy

Explicit execution bounds:

```kotlin
data class StopPolicy(
    val maxStepExecutions: Int = 100,
    val maxParallelBranches: Int = 16,
)
```

- `maxStepExecutions`: Hard limit on total steps executed (including parallel branches). Throws `WorkflowLimitExceededException`.
- `maxParallelBranches`: Maximum concurrent items in a `parallelStep`.

### WorkflowObserver

Observation seam for monitoring workflow execution:

```kotlin
interface WorkflowObserver {
    fun onWorkflowStarted(workflowName: String, context: WorkflowContext) = Unit
    fun onWorkflowEvent(workflowName: String, name: String, attributes: Map<String, Any?>, context: WorkflowContext) = Unit
    fun onStepStarted(workflowName: String, stepName: String, context: WorkflowContext) = Unit
    fun onStepCompleted(workflowName: String, stepName: String, context: WorkflowContext) = Unit
    fun onStepFailed(workflowName: String, stepName: String, error: Throwable, context: WorkflowContext) = Unit
    fun onWorkflowCompleted(workflowName: String, context: WorkflowContext) = Unit
    fun onWorkflowFailed(workflowName: String, error: Throwable, context: WorkflowContext) = Unit
    fun onScheduledTick(...) = Unit
    fun onSkippedTick(...) = Unit
    fun onMissedTick(...) = Unit
}
```

### WorkflowContext

Execution metadata:
```kotlin
data class WorkflowContext(
    val workflowId: String = UUID.randomUUID().toString(),
    val attributes: Map<String, Any?> = emptyMap(),
)
```

---

## Full Example

Complete code-review workflow as a Spola State Graph:

```kotlin
import dev.tramai.orchestration.*
import java.util.concurrent.TimeUnit

// State definition
sealed class CodeReviewState {
    data object Init : CodeReviewState()
    data class CodeFetched(val repoUrl: String, val files: List<String>) : CodeReviewState()
    data class AiReviewed(val files: Map<String, String>) : CodeReviewState()
    data class Approved(val summary: String) : CodeReviewState()
    data class Rejected(val reason: String) : CodeReviewState()
    data class PatchApplied(val output: String) : CodeReviewState()
}

val codeReviewWorkflow = workflow<CodeReviewState>(
    name = "code-review-pipeline",
    definitionVersion = "2",
) {
    // Step 1: Fetch code (local transformation)
    localStep("fetch-code") { state, _ ->
        when (state) {
            is CodeReviewState.Init -> CodeReviewState.CodeFetched(
                repoUrl = "https://github.com/owner/repo",
                files = listOf("src/main.kt", "src/utils.kt", "test/Test.kt"),
            )
            else -> state
        }
    }

    // Step 2: AI review (aiStep with context)
    aiStep(
        name = "ai-review",
        replayPolicy = ReplayPolicy.IDEMPOTENT,
        input = { state: CodeReviewState ->
            val fetched = state as CodeReviewState.CodeFetched
            "Review these files for bugs, style issues, and security vulnerabilities:\n" +
                fetched.files.joinToString("\n---\n") { file ->
                    "### $file\n${readFile(file)}"
                }
        },
        invoke = { prompt -> llmClient.review(prompt) },
        merge = { state, results ->
            val fetched = state as CodeReviewState.CodeFetched
            CodeReviewState.AiReviewed(
                files = fetched.files.associateWith { results }
            )
        },
    )

    // Step 3: Human approval gate (gateStep)
    gateStep("human-approval") { state, context ->
        val reviewed = state as CodeReviewState.AiReviewed
        val approved = requestApproval(
            workflowId = context.workflowId,
            review = reviewed.files.values.joinToString("\n"),
        )
        if (approved) GateDecision.allow()
        else GateDecision.reject("Human reviewer declined")
    }

    // Step 4: Apply fixes via Codex (codexStep)
    codexStep(
        name = "apply-fixes",
        config = CodexStepConfig(workdir = "/home/user/project"),
        prompt = { state, _ ->
            val reviewed = state as CodeReviewState.AiReviewed
            "Apply these code review suggestions:\n${reviewed.files.values.joinToString("\n")}"
        },
        merge = { state, output, _ ->
            CodeReviewState.PatchApplied(output)
        },
    )

    // Step 5: Report results (aiStep)
    aiStep(
        name = "generate-report",
        replayPolicy = ReplayPolicy.NON_REPLAYABLE,
        input = { state: CodeReviewState ->
            "Generate a final summary of the code review. Applied changes:\n" +
                (state as CodeReviewState.PatchApplied).output
        },
        invoke = { prompt -> llmClient.summarize(prompt) },
        merge = { state, summary ->
            CodeReviewState.Approved(summary)
        },
    )

    // Step 6: Parallel notification (parallelStep)
    parallelStep(
        name = "notify-stakeholders",
        items = { listOf("slack", "email") },
        invoke = { channel -> sendNotification(channel, "Review complete") },
        merge = { state, _ -> state },
    )
}.build(
    stopPolicy = StopPolicy(maxStepExecutions = 50),
) { state ->
    when (state) {
        is CodeReviewState.Approved -> "Code review complete: ${state.summary}"
        is CodeReviewState.Rejected -> "Code review rejected: ${state.reason}"
        else -> "Review incomplete"
    }
}

// Run with persistence
suspend fun runReview() {
    val persistence = WorkflowPersistence(
        checkpointStore = InMemoryWorkflowCheckpointStore(),
        stateCodec = object : WorkflowStateCodec<CodeReviewState> {
            override fun encode(state: CodeReviewState) = state.toString()
            override fun decode(payload: String) = CodeReviewState.Init
        },
    )

    val result = codeReviewWorkflow.run(
        initialState = CodeReviewState.Init,
        persistence = persistence,
        observer = object : WorkflowObserver {
            override fun onStepCompleted(workflowName: String, stepName: String, context: WorkflowContext) {
                println("Step '$stepName' completed [${context.workflowId}]")
            }
            override fun onStepFailed(workflowName: String, stepName: String, error: Throwable, context: WorkflowContext) {
                println("Step '$stepName' failed: ${error.message}")
            }
        },
    )
    println(result)
}
```

---

## LangGraph Comparison

| Feature | Spola State Graphs (TramAI) | LangGraph (Python) | LangChain4j | Spring AI |
|---------|---------------------------|--------------------|-------------|-----------|
| **Language** | Kotlin/JVM | Python | Java | Java/Spring |
| **State typing** | Sealed class `S` — compile-time exhaustive matching | TypedDict / Pydantic (runtime) | None (raw object) | None (raw object) |
| **Edge validation** | Compile-time — missing branches fail to compile | Runtime — errors surface during execution | Runtime | Runtime |
| **Branch safety** | Exhaustive `when` on sealed class | Stringly-typed dict keys | N/A | N/A |
| **Workflow definition** | `workflow<S>(name) { ... }.build { ... }` | `StateGraph(State).add_node(...).add_edge(...)` | Declarative `@Bean` chain | Declarative `@Bean` chain |
|| **Step types** | 12 built-in types | Custom nodes + conditional edges | Limited | Limited |
| **Agent CLI steps** | Native `hermesStep`, `codexStep` | ✗ | ✗ | ✗ |
| **MCP integration** | Native `mcpStep` | External plugin | Limited | Limited |
| **Shell/HTTP steps** | Built-in with security policies | Third-party only | ✗ | ✗ |
| **Human-in-the-loop** | `gateStep` with `GateDecision` | `interrupt` + resume | ✗ | ✗ |
| **Checkpointed delay** | `delayStep` with persistence | `time travel` | ✗ | ✗ |
| **Plugin SPI** | `ExternalStepExecutor` interface | ✗ | ✗ | ✗ |
| **Persistence** | 4 built-in stores (JDBC, File, Markdown, InMemory) | SQLite / Postgres (via checkpointer) | ✗ | ✗ |
| **Lease coordination** | `WorkflowLeaseStore` SPI (JDBC, File, InMemory) | ✗ | ✗ | ✗ |
| **Replay policies** | 4-tier: PURE, IDEMPOTENT, EXTERNALLY_IDEMPOTENT, NON_REPLAYABLE | ✗ | ✗ | ✗ |
| **Distributed worker** | `TramaiWorker` with polling, leasing, partitions | LangGraph Cloud | ✗ | ✗ |
| **Concurrency model** | Coroutines (suspend functions) | asyncio | Thread pool | Thread pool |
| **Streaming** | Via `WorkflowObserver` events | Event streaming | Yes | Yes |
| **Observability** | `WorkflowObserver` + `TramaiWorkerObserver` SPI | LangSmith | Metrics | Micrometer |

---

## Reference

- **Package**: `dev.tramai.orchestration`
- **Entry function**: `workflow<S>(name, definitionVersion, configure)`
- **Core class**: `Workflow<S, R>` — constructed via `WorkflowBuilder<S>.build { (S) -> R }`
- **Persistence**: `WorkflowPersistence<S>` with `WorkflowCheckpointStore`, `WorkflowStateCodec`, `WorkflowLeaseStore`
- **Replay**: `ReplayPolicy` enum, `StepAttemptRecord`, `StepAttemptRecordStore`
- **Distribution**: `TramaiWorker`, `WorkerConfig`, `WorkerRegistryStore`, `PartitionAssignmentStrategy`
- **Exceptions**: `WorkflowGateRejectedException`, `WorkflowSuspendedException`, `WorkflowLimitExceededException`, `WorkflowBranchSelectionException`, `NonReplayableStepStateUnknownException`
