# SPEC: Approval Resume Path (WAITING_APPROVAL → RUNNING)

> **Status:** Draft
> **Context:** golem-core (WorkflowExecutionService, YamlWorkflowCompiler, WorkflowRoutes, WorkflowCommands)
> **Dependencies:** TramAI orchestration 0.3.0

## Problem

The `human_approval` step uses `gateStep` which returns `GateDecision(allowed=false)`, throwing `WorkflowGateRejectedException`. The `WorkflowExecutionService.runExecution()` catches ALL throwables and transitions to `FAILED`. No resume path exists, making `human_approval` terminal.

## Architecture

TramAI already supports checkpoint-based resume:
- `WorkflowCheckpointStore` — saves/loads/deletes checkpoints by `(workflowName, workflowId)`
- `WorkflowPersistence` — wraps checkpoint store + state codec
- `Workflow.resume()` — loads checkpoint, decodes state, executes remaining steps from `nextStepIndex`
- `Workflow.run()` — saves checkpoint after each completed step; on error, `abort()` releases lease but checkpoint persists

The gate step saves checkpoint BEFORE reaching the gate (after the last completed step). On resume, the gate re-executes → we intercept via a sentinel in `state.intermediateResults["__approval_granted"]`.

## Flow

```
First Run:
  QUEUED → RUNNING → step 0 → step 1 → human_approval gate
                                                   │
                     checkpoint saved after step 1  │
                                                   ▼
                                         GateDecision(allowed=false)
                                         WorkflowGateRejectedException
                                                   │
                                                   ▼
                                         WAITING_APPROVAL (checkpointKey=workflowId)

Resume:
  WAITING_APPROVAL → RUNNING → patched checkpoint with __approval_granted=true
                                         │
                                         ▼
                              Workflow.resume() → load checkpoint
                                         │
                                         ▼
                              human_approval gate: __approval_granted=true → ALLOW
                                         │
                                         ▼
                              step 3 → ... → COMPLETED
```

## Files to Modify

### 1. `YamlWorkflowCompiler.kt` — Smarter `human_approval` gate

Replace:
```kotlin
humanApprovalGate(name = step.id, decide = { _, _ ->
    GateDecision(allowed = false, reason = step.prompt ?: "Awaiting human approval")
})
```

With direct `gateStep` that checks approval sentinel:
```kotlin
gateStep(name = step.id) { state, _ ->
    if (state.intermediateResults["__approval_granted"] == "true") {
        GateDecision.allow()
    } else {
        GateDecision.reject(step.prompt ?: "Awaiting human approval")
    }
}
```

**Import needed:** Remove `import dev.spola.workflow.TeamWorkflowSteps` (no longer needed for human_approval, but check if still used for other steps). Keep `import dev.tramai.orchestration.GateDecision`.

### 2. `WorkflowExecutionService.kt` — Add persistence + approval resume

**New imports:**
```kotlin
import dev.spola.GolemFactory
import dev.tramai.orchestration.WorkflowGateRejectedException
import dev.tramai.orchestration.WorkflowCheckpoint
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowPersistence
import java.nio.file.Paths
```

**Modify `runExecution()`:**

```kotlin
suspend fun runExecution(record: WorkflowExecutionRecord): String {
    val claimed = when (record.status) {
        WorkflowExecutionStatus.QUEUED -> {
            executionStore.claimQueued(
                executionId = record.id,
                claimantId = "workflow-executor-${record.id}",
                now = System.currentTimeMillis(),
            ) ?: throw IllegalStateException("Execution ${record.id} is not queued")
        }
        WorkflowExecutionStatus.RUNNING -> record
        else -> throw IllegalStateException(
            "Execution ${record.id} is not executable from status ${record.status}"
        )
    }

    val executionInput = json.decodeFromString<WorkflowExecutionInput>(claimed.inputJson)
    val template = workflowRegistry.resolve(claimed.workflowName)
    val workflow = template.build(config, executionInput.goal, executionInput.parametersJson)

    val checkpointDir = System.getProperty("java.io.tmpdir") + "/golem-workflows"
    val persistence: WorkflowPersistence<GolemState>? =
        GolemFactory.configurePersistence(
            checkpointDir = checkpointDir,
            deleteCheckpointOnCompletion = true,
        )

    // Create context so we can capture workflowId for resume
    val workflowContext = WorkflowContext()
    val metrics = dev.spola.metrics.GolemMetrics(isEnabled = config.metricsEnabled)
    val tracer = dev.spola.GolemTracer(
        otelEnabled = config.otelEnabled,
        otelEndpoint = config.otelEndpoint,
        otelServiceName = config.otelServiceName,
    )
    val observer = GolemWorkflowObserver(
        metrics = metrics,
        tracer = tracer,
        chatService = chatService,
        executionId = claimed.id,
        sessionId = claimed.sessionId,
    )

    return try {
        val result = workflow.run(
            initialState = GolemState.initial(
                goal = executionInput.goal,
                config = config,
                workflowNestingDepth = 1,
            ),
            context = workflowContext,
            observer = observer,
            persistence = persistence,
        )
        executionStore.complete(
            executionId = claimed.id,
            outputJson = json.encodeToString(mapOf("result" to result)),
            result = result,
            now = System.currentTimeMillis(),
        )
        if (claimed.sessionId != null) {
            chatService?.onWorkflowCompleted(
                executionId = claimed.id,
                workflowName = claimed.workflowName,
                sessionId = claimed.sessionId,
                result = result,
            )
        }
        result
    } catch (rejected: WorkflowGateRejectedException) {
        // Gate rejected (e.g., human_approval) → store checkpoint key, transition to WAITING_APPROVAL
        executionStore.transition(
            executionId = claimed.id,
            expected = setOf(WorkflowExecutionStatus.RUNNING),
            target = WorkflowExecutionStatus.WAITING_APPROVAL,
            now = System.currentTimeMillis(),
        ) { record ->
            record.copy(checkpointKey = workflowContext.workflowId)
        }
        throw rejected
    } catch (t: Throwable) {
        executionStore.fail(
            executionId = claimed.id,
            error = t.message ?: t::class.simpleName ?: "Workflow execution failed",
            now = System.currentTimeMillis(),
        )
        throw t
    }
}
```

**New method `approveExecution()`:**

```kotlin
suspend fun approveExecution(executionId: String): Boolean {
    val current = executionStore.get(executionId) ?: return false
    if (current.status != WorkflowExecutionStatus.WAITING_APPROVAL) return false
    val checkpointKey = current.checkpointKey ?: return false

    val updated = executionStore.transition(
        executionId = executionId,
        expected = setOf(WorkflowExecutionStatus.WAITING_APPROVAL),
        target = WorkflowExecutionStatus.RUNNING,
        now = System.currentTimeMillis(),
    ) { record -> record.copy(startedAt = System.currentTimeMillis()) }
    if (updated == null) return false

    val localJson = json
    return try {
        val executionInput = localJson.decodeFromString<WorkflowExecutionInput>(updated.inputJson)
        val template = workflowRegistry.resolve(updated.workflowName)
        val workflow = template.build(config, executionInput.goal, executionInput.parametersJson)

        val checkpointDir = System.getProperty("java.io.tmpdir") + "/golem-workflows"
        val persistence = GolemFactory.configurePersistence(
            checkpointDir = checkpointDir,
            deleteCheckpointOnCompletion = true,
        )

        // Patch checkpoint state with approval sentinel
        val checkpoint = persistence.checkpointStore.load(updated.workflowName, checkpointKey)
            ?: throw IllegalStateException(
                "Checkpoint not found for workflow '${updated.workflowName}' execution '$executionId'"
            )
        val rawState = persistence.stateCodec.decode(checkpoint.statePayload) as GolemState
        val patchedState = rawState.copy(
            intermediateResults = rawState.intermediateResults +
                ("__approval_granted" to "true")
        )
        val patchedPayload = persistence.stateCodec.encode(patchedState)
        persistence.checkpointStore.save(
            WorkflowCheckpoint(
                workflowName = checkpoint.workflowName,
                workflowId = checkpoint.workflowId,
                nextStepIndex = checkpoint.nextStepIndex,
                stepExecutions = checkpoint.stepExecutions,
                lastCompletedStepName = checkpoint.lastCompletedStepName,
                statePayload = patchedPayload,
                metadata = checkpoint.metadata,
            ),
            expectedRevision = checkpoint.revision,
        )

        val metrics = dev.spola.metrics.GolemMetrics(isEnabled = config.metricsEnabled)
        val tracer = dev.spola.GolemTracer(
            otelEnabled = config.otelEnabled,
            otelEndpoint = config.otelEndpoint,
            otelServiceName = config.otelServiceName,
        )
        val observer = GolemWorkflowObserver(
            metrics = metrics,
            tracer = tracer,
            chatService = chatService,
            executionId = executionId,
            sessionId = current.sessionId,
        )
        val context = WorkflowContext(workflowId = checkpointKey)
        val result = workflow.resume(
            context = context,
            observer = observer,
            persistence = persistence,
        )

        executionStore.complete(
            executionId = executionId,
            outputJson = localJson.encodeToString(mapOf("result" to result)),
            result = result,
            now = System.currentTimeMillis(),
        )
        if (current.sessionId != null) {
            chatService?.onWorkflowCompleted(
                executionId = executionId,
                workflowName = updated.workflowName,
                sessionId = current.sessionId,
                result = result,
            )
        }
        true
    } catch (t: Throwable) {
        executionStore.fail(
            executionId = executionId,
            error = t.message ?: t::class.simpleName ?: "Workflow resume failed",
            now = System.currentTimeMillis(),
        )
        throw t
    }
}
```

### 3. `WorkflowRoutes.kt` — New approve endpoint

Add route after the existing `get("/workflows/executions/{id}")`:

```kotlin
post("/workflows/executions/{id}/approve") {
    call.enforceBearerAuth(config.apiKey)
    val id = call.parameters["id"]
        ?: throw IllegalArgumentException("Missing execution id")
    try {
        val approved = workflowExecutionService.approveExecution(id)
        if (approved) {
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "approved"))
        } else {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to "Execution is not in WAITING_APPROVAL state, not found, or has no checkpoint"),
            )
        }
    } catch (t: Throwable) {
        call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to (t.message ?: "Workflow resume failed")),
        )
    }
}
```

### 4. `WorkflowCommands.kt` (CLI) — New `approve` subcommand

**Add to subcommand list:**
```kotlin
subcommands = [
    WorkflowRunCommand::class,
    WorkflowListCommand::class,
    WorkflowExportCommand::class,
    WorkflowApproveCommand::class,  // NEW
]
```

**New command class (add before `TeamCommand`):**
```kotlin
@Command(name = "approve", description = ["Approve a workflow execution waiting for human approval"])
class WorkflowApproveCommand : Callable<Int> {
    @ParentCommand
    lateinit var workflowCommand: WorkflowCommand

    private val root get() = workflowCommand.parent

    @Parameters(index = "0", description = ["Execution ID to approve"])
    lateinit var executionId: String

    override fun call(): Int = runBlocking {
        val config = buildConfig(root)
        val executionStore = SqliteWorkflowExecutionStore(config.workflowDbPath)
        val registry = WorkflowTemplateRegistry().apply {
            registerBuiltInTemplates()
            YamlWorkflowLoader.loadAndRegister(this, config)
        }
        val executionService = WorkflowExecutionService(
            config = config,
            executionStore = executionStore,
            workflowRegistry = registry,
        )
        return@runBlocking try {
            executionService.approveExecution(executionId)
            println("Execution $executionId approved and resumed.")
            0
        } catch (t: Throwable) {
            System.err.println("Failed to approve execution $executionId: ${t.message}")
            1
        }
    }
}
```

**Import:** `import dev.spola.workflow.yaml.YamlWorkflowLoader`

## Tests

### Unit test (`WorkflowExecutionServiceTest.kt` or `YamlWorkflowSystemTest.kt`)

```kotlin
@Test
fun `workflow with human_approval step transitions to WAITING_APPROVAL`() = runTest {
    // Given: a YAML workflow with human_approval step followed by a shell step
    val yaml = """
        name: test-approval
        version: "1"
        steps:
          - id: pre-step
            type: shell
            command: "echo setup done"
          - id: review
            type: human_approval
            prompt: "Approve this step?"
          - id: post-step
            type: shell
            command: "echo approved"
            depends_on: [review]
    """.trimIndent()
    
    // When: enqueue and run
    val registry = WorkflowTemplateRegistry()
    val workflowDef = parseAndRegisterYaml(yaml, registry)
    val store = SqliteWorkflowExecutionStore(":memory:")
    val service = WorkflowExecutionService(config = testConfig, executionStore = store, workflowRegistry = registry)
    
    val record = service.enqueue(NewWorkflowExecution(workflowName = "test-approval", inputJson = """{"goal":"test"}"""))
    assertThrows<WorkflowGateRejectedException> {
        service.runExecution(record)
    }
    
    // Then: status is WAITING_APPROVAL with checkpoint key
    val updated = store.get(record.id)!!
    assertEquals(WorkflowExecutionStatus.WAITING_APPROVAL, updated.status)
    assertNotNull(updated.checkpointKey) // stored for resume
}

@Test
fun `approve execution resumes workflow from checkpoint`() = runTest {
    // Given: workflow that was WAITING_APPROVAL
    // (setup same as above up to the gate rejection)
    
    // When: approve
    val approved = service.approveExecution(record.id)
    
    // Then: workflow completed, post-step executed
    assertTrue(approved)
    val completed = store.get(record.id)!!
    assertEquals(WorkflowExecutionStatus.COMPLETED, completed.status)
    assertNotNull(completed.result)
    assertTrue(completed.result!!.contains("approved"))
}

@Test
fun `approve on non-WAITING_APPROVAL execution returns false`() = runTest {
    val record = service.enqueue(NewWorkflowExecution(workflowName = "test-approval", inputJson = """{"goal":"test"}"""))
    val result = service.approveExecution(record.id)
    assertFalse(result) // still QUEUED, not WAITING_APPROVAL
}

@Test
fun `approve on completed execution returns false`() = runTest {
    val record = service.enqueue(NewWorkflowExecution(workflowName = "simple-shell", inputJson = """{"goal":"test"}"""))
    service.runExecution(record)  // completes normally
    val result = service.approveExecution(record.id)
    assertFalse(result) // already COMPLETED
}
```

## Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| `WorkflowCheckpointStore.list()` not available for finding checkpoints after rejection | Medium | Captured via `WorkflowContext.workflowId` before gate rejection |
| Checkpoint deleted on re-throw | Low | `abort()` only releases lease, doesn't delete checkpoint |
| State codec can't decode after workflow definition change | Low | TramAI checks `definitionDigest` — if changed, `WorkflowResumeException` |
| FileWorkflowCheckpointStore checkpoints persist on disk after completion | Low | `deleteCheckpointOnCompletion = true` deletes on `complete()` |
