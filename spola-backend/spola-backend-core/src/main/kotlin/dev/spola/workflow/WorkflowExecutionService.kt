package dev.spola.workflow

import dev.spola.SpolaConfig
import dev.spola.SpolaFactory
import dev.spola.factory.WorkflowFactory
import dev.spola.metrics.SpolaMetrics
import dev.spola.SpolaTracer
import dev.tramai.orchestration.WorkflowCheckpoint
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowGateRejectedException
import dev.tramai.orchestration.WorkflowPersistence
import kotlinx.serialization.json.Json

class WorkflowExecutionService(
    val config: SpolaConfig,
    private val executionStore: WorkflowExecutionStore,
    val workflowRegistry: WorkflowTemplateRegistry,
    private val chatService: WorkflowChatService? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun enqueue(input: NewWorkflowExecution, parentNestingDepth: Int = 0): WorkflowExecutionRecord {
        if (parentNestingDepth >= config.maxWorkflowNestingDepth) {
            throw IllegalStateException("Nested workflows are not supported (max depth exceeded)")
        }
        return executionStore.create(input)
    }

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
            else -> throw IllegalStateException("Execution ${record.id} is not executable from status ${record.status}")
        }

        val executionInput = json.decodeFromString<WorkflowExecutionInput>(claimed.inputJson)
        val template = workflowRegistry.resolve(claimed.workflowName)
        val workflow = template.build(config, executionInput.goal, executionInput.parametersJson)

        val checkpointDir = System.getProperty("java.io.tmpdir") + "/spola-workflows"
        val persistence: WorkflowPersistence<SpolaState>? =
            SpolaFactory.configurePersistence(
                checkpointDir = checkpointDir,
                deleteCheckpointOnCompletion = true,
            )

        val workflowContext = WorkflowContext()
        val metrics = SpolaMetrics(isEnabled = config.metrics.metricsEnabled)
        val tracer = SpolaTracer(
            otelEnabled = config.metrics.otelEnabled,
            otelEndpoint = config.metrics.otelEndpoint,
            otelServiceName = config.otelServiceName,
        )
        val observer = SpolaWorkflowObserver(
            metrics = metrics,
            tracer = tracer,
            chatService = chatService,
            executionId = claimed.id,
            sessionId = claimed.sessionId,
        )

        return try {
            val result = workflow.run(
                initialState = SpolaState.initial(
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
            ) ?: error("Failed to mark execution ${claimed.id} completed")
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
                record.copy(checkpointKey = workflowContext.workflowId, resumable = true)
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

            val checkpointDir = System.getProperty("java.io.tmpdir") + "/spola-workflows"
            val persistence = SpolaFactory.configurePersistence(
                checkpointDir = checkpointDir,
                deleteCheckpointOnCompletion = true,
            )

            // Patch checkpoint state with approval sentinel
            val checkpoint = persistence.checkpointStore.load(updated.workflowName, checkpointKey)
                ?: throw IllegalStateException(
                    "Checkpoint not found for workflow '${updated.workflowName}' execution '$executionId'"
                )
            val rawState = persistence.stateCodec.decode(checkpoint.statePayload)
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

            val metrics = SpolaMetrics(isEnabled = config.metrics.metricsEnabled)
            val tracer = SpolaTracer(
                otelEnabled = config.metrics.otelEnabled,
                otelEndpoint = config.metrics.otelEndpoint,
                otelServiceName = config.otelServiceName,
            )
            val observer = SpolaWorkflowObserver(
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
            ) ?: error("Failed to mark execution $executionId completed")
            if (current.sessionId != null) {
                chatService?.onWorkflowCompleted(
                    executionId = executionId,
                    workflowName = updated.workflowName,
                    sessionId = current.sessionId,
                    result = result,
                )
            }
            true
        } catch (rejected: WorkflowGateRejectedException) {
            // Second gate rejection after resume → re-transition to WAITING_APPROVAL
            executionStore.transition(
                executionId = executionId,
                expected = setOf(WorkflowExecutionStatus.RUNNING),
                target = WorkflowExecutionStatus.WAITING_APPROVAL,
                now = System.currentTimeMillis(),
            ) { record ->
                record.copy(checkpointKey = null)  // clear stale checkpoint, let runExecution set new one
            }
            throw rejected
        } catch (t: Throwable) {
            executionStore.fail(
                executionId = executionId,
                error = t.message ?: t::class.simpleName ?: "Workflow resume failed",
                now = System.currentTimeMillis(),
            )
            // Clean up patched checkpoint to avoid orphaned state
            runCatching {
                val checkpointDir = System.getProperty("java.io.tmpdir") + "/spola-workflows"
                val cleanupPersistence = SpolaFactory.configurePersistence(
                    checkpointDir = checkpointDir,
                    deleteCheckpointOnCompletion = false,
                )
                cleanupPersistence.checkpointStore.delete(updated.workflowName, checkpointKey)
            }
            throw t
        }
    }

    suspend fun getExecution(id: String): WorkflowExecutionRecord? = executionStore.get(id)

    suspend fun requestCancel(executionId: String): Boolean {
        val current = executionStore.get(executionId) ?: return false
        val now = System.currentTimeMillis()
        return when (current.status) {
            WorkflowExecutionStatus.QUEUED -> {
                executionStore.transition(
                    executionId = executionId,
                    expected = setOf(WorkflowExecutionStatus.QUEUED),
                    target = WorkflowExecutionStatus.CANCELLED,
                    now = now,
                ) { record ->
                    record.copy(
                        error = "Cancelled before execution",
                        completedAt = now,
                    )
                } != null
            }

            WorkflowExecutionStatus.RUNNING,
            WorkflowExecutionStatus.WAITING_APPROVAL -> {
                executionStore.transition(
                    executionId = executionId,
                    expected = setOf(WorkflowExecutionStatus.RUNNING, WorkflowExecutionStatus.WAITING_APPROVAL),
                    target = WorkflowExecutionStatus.CANCEL_REQUESTED,
                    now = now,
                ) != null
            }

            WorkflowExecutionStatus.CANCEL_REQUESTED,
            WorkflowExecutionStatus.COMPLETED,
            WorkflowExecutionStatus.FAILED,
            WorkflowExecutionStatus.CANCELLED -> false
        }
    }

    suspend fun listBySessionId(sessionId: String): List<WorkflowExecutionRecord> =
        executionStore.listBySessionId(sessionId)
}
