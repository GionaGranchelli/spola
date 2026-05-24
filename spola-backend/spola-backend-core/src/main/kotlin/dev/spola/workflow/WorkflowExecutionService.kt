package dev.spola.workflow

import dev.spola.SpolaConfig
import dev.spola.SpolaFactory
import dev.spola.factory.WorkflowFactory
import dev.spola.metrics.SpolaMetrics
import dev.spola.SpolaTracer
import dev.tramai.orchestration.WorkflowCheckpoint
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowGateRejectedException
import dev.tramai.orchestration.WorkflowObserver
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

    suspend fun runExecution(
        record: WorkflowExecutionRecord,
        sseObserver: WorkflowObserver? = null,
    ): String {
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
        val effectiveConfig = if (executionInput.workingDirectory != null) {
            config.copy(workingDirectory = executionInput.workingDirectory)
        } else {
            config
        }
        val template = workflowRegistry.resolve(claimed.workflowName)
        val workflow = template.build(effectiveConfig, executionInput.goal, executionInput.parametersJson)

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
        val spolaObserver = SpolaWorkflowObserver(
            metrics = metrics,
            tracer = tracer,
            chatService = chatService,
            executionId = claimed.id,
            sessionId = claimed.sessionId,
        )
        val observer = if (sseObserver != null) {
            compose(spolaObserver, sseObserver)
        } else {
            spolaObserver
        }

        return try {
            val result = workflow.run(
                initialState = SpolaState.initial(
                    goal = executionInput.goal,
                    config = effectiveConfig,
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
            val effectiveConfig = if (executionInput.workingDirectory != null) {
                config.copy(workingDirectory = executionInput.workingDirectory)
            } else {
                config
            }
            val template = workflowRegistry.resolve(updated.workflowName)
            val workflow = template.build(effectiveConfig, executionInput.goal, executionInput.parametersJson)

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

    // ── Dashboard/Monitoring methods ─────────────────────────────────

    /**
     * Return checkpoint history for an execution.
     * Currently extracts checkpoint info from the execution record's [checkpointKey].
     * Extended in future to query TramAI's checkpoint store by execution ID.
     */
    suspend fun getCheckpointHistory(executionId: String): List<WorkflowCheckpointResponse> {
        val record = executionStore.get(executionId) ?: return emptyList()
        val checkpoints = mutableListOf<WorkflowCheckpointResponse>()

        // If the execution has a checkpoint key, report it
        if (record.checkpointKey != null) {
            checkpoints.add(
                WorkflowCheckpointResponse(
                    id = record.checkpointKey,
                    executionId = executionId,
                    stepName = "checkpoint_${record.checkpointKey.take(8)}",
                    timestamp = record.updatedAt,
                    stateSummary = "Status: ${record.status.name}, Resumable: ${record.resumable}",
                    resumable = record.resumable,
                ),
            )
        }

        // If the execution has started, include a timeline checkpoint
        if (record.startedAt != null) {
            checkpoints.add(
                0,
                WorkflowCheckpointResponse(
                    id = "${executionId}_start",
                    executionId = executionId,
                    stepName = "workflow_start",
                    timestamp = record.startedAt,
                    stateSummary = "Workflow '${record.workflowName}' started",
                ),
            )
        }

        return checkpoints
    }

    /**
     * Return step metrics for a completed execution.
     * Current implementation extracts metrics from execution record timestamps.
     * Extended in future to query SpolaOperationObserver's step timing data.
     */
    suspend fun getStepMetrics(executionId: String): WorkflowMetricsResponse {
        val record = executionStore.get(executionId) ?: return WorkflowMetricsResponse(
            executionId = executionId,
        )

        val totalDurationMs = when {
            record.completedAt != null && record.startedAt != null ->
                record.completedAt - record.startedAt
            record.startedAt != null ->
                System.currentTimeMillis() - record.startedAt
            else -> 0L
        }

        return WorkflowMetricsResponse(
            executionId = executionId,
            steps = emptyList(),
            totalInputTokens = 0,
            totalOutputTokens = 0,
            totalThinkingTokens = 0,
            totalDurationMs = totalDurationMs,
        )
    }

    /**
     * Resume execution from its latest checkpoint.
     * Delegates to [approveExecution] since the resume path is the same
     * for approval and checkpoint replay.
     */
    suspend fun resumeFromCheckpoint(executionId: String): Boolean {
        val record = executionStore.get(executionId) ?: return false
        // If in WAITING_APPROVAL, reuse the approve path
        if (record.status == WorkflowExecutionStatus.WAITING_APPROVAL) {
            return approveExecution(executionId)
        }
        // For COMPLETED/FAILED executions, re-enqueue for replay
        if (record.status == WorkflowExecutionStatus.COMPLETED || record.status == WorkflowExecutionStatus.FAILED) {
            val replayed = executionStore.create(
                NewWorkflowExecution(
                    definitionId = record.definitionId,
                    workflowName = record.workflowName,
                    userId = record.userId,
                    sessionId = record.sessionId,
                    triggerSource = "replay",
                    triggerRef = executionId,
                    inputJson = record.inputJson,
                ),
            )
            // Start running immediately in background
            kotlinx.coroutines.GlobalScope.launch {
                runCatching { runExecution(replayed) }
            }
            return true
        }
        return false
    }

    /**
     * Decide on a gate step (approve or reject).
     * Approval routes through [approveExecution].
     * Rejection marks the execution as cancelled.
     */
    suspend fun decideGate(executionId: String, stepName: String, approved: Boolean): Boolean {
        if (approved) {
            return approveExecution(executionId)
        }
        // Rejection: cancel the execution
        return requestCancel(executionId)
    }
}

/**
 * Compose two [WorkflowObserver] instances into one that delegates to both.
 */
private fun compose(first: WorkflowObserver, second: WorkflowObserver): WorkflowObserver =
    object : WorkflowObserver {
        override fun onWorkflowStarted(workflowName: String, context: WorkflowContext) {
            first.onWorkflowStarted(workflowName, context)
            second.onWorkflowStarted(workflowName, context)
        }

        override fun onWorkflowEvent(
            workflowName: String,
            name: String,
            attributes: Map<String, Any?>,
            context: WorkflowContext,
        ) {
            first.onWorkflowEvent(workflowName, name, attributes, context)
            second.onWorkflowEvent(workflowName, name, attributes, context)
        }

        override fun onStepStarted(
            workflowName: String,
            stepName: String,
            context: WorkflowContext,
        ) {
            first.onStepStarted(workflowName, stepName, context)
            second.onStepStarted(workflowName, stepName, context)
        }

        override fun onStepCompleted(
            workflowName: String,
            stepName: String,
            context: WorkflowContext,
        ) {
            first.onStepCompleted(workflowName, stepName, context)
            second.onStepCompleted(workflowName, stepName, context)
        }

        override fun onStepFailed(
            workflowName: String,
            stepName: String,
            error: Throwable,
            context: WorkflowContext,
        ) {
            first.onStepFailed(workflowName, stepName, error, context)
            second.onStepFailed(workflowName, stepName, error, context)
        }

        override fun onWorkflowCompleted(workflowName: String, context: WorkflowContext) {
            first.onWorkflowCompleted(workflowName, context)
            second.onWorkflowCompleted(workflowName, context)
        }

        override fun onWorkflowFailed(
            workflowName: String,
            error: Throwable,
            context: WorkflowContext,
        ) {
            first.onWorkflowFailed(workflowName, error, context)
            second.onWorkflowFailed(workflowName, error, context)
        }

        override fun onScheduledTick(
            workflowName: String,
            scheduledFireAt: java.time.Instant,
            context: WorkflowContext,
        ) {
            first.onScheduledTick(workflowName, scheduledFireAt, context)
            second.onScheduledTick(workflowName, scheduledFireAt, context)
        }

        override fun onSkippedTick(
            workflowName: String,
            scheduledFireAt: java.time.Instant,
            reason: String,
            context: WorkflowContext,
        ) {
            first.onSkippedTick(workflowName, scheduledFireAt, reason, context)
            second.onSkippedTick(workflowName, scheduledFireAt, reason, context)
        }

        override fun onMissedTick(
            workflowName: String,
            scheduledFireAt: java.time.Instant,
            reason: String,
            context: WorkflowContext,
        ) {
            first.onMissedTick(workflowName, scheduledFireAt, reason, context)
            second.onMissedTick(workflowName, scheduledFireAt, reason, context)
        }
    }
