package dev.spola.workflow

import dev.spola.sqlite.SqliteStoreSupport
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import java.util.UUID

interface WorkflowExecutionStore : AutoCloseable {
    suspend fun create(request: NewWorkflowExecution): WorkflowExecutionRecord
    suspend fun get(id: String): WorkflowExecutionRecord?
    suspend fun listAll(limit: Int = 50): List<WorkflowExecutionRecord>
    suspend fun listByStatus(statuses: Set<WorkflowExecutionStatus>, limit: Int = 100): List<WorkflowExecutionRecord>
    suspend fun listBySessionId(sessionId: String): List<WorkflowExecutionRecord>
    suspend fun listByTrigger(triggerSource: String, triggerRef: String): List<WorkflowExecutionRecord>
    suspend fun claimQueued(executionId: String, claimantId: String, now: Long): WorkflowExecutionRecord?
    suspend fun transition(
        executionId: String,
        expected: Set<WorkflowExecutionStatus>,
        target: WorkflowExecutionStatus,
        now: Long = System.currentTimeMillis(),
        mutate: (WorkflowExecutionRecord) -> WorkflowExecutionRecord = { it },
    ): WorkflowExecutionRecord?

    suspend fun complete(executionId: String, outputJson: String?, result: String?, now: Long): WorkflowExecutionRecord?
    suspend fun fail(executionId: String, error: String, now: Long): WorkflowExecutionRecord?
    suspend fun cancel(executionId: String, reason: String?, now: Long): WorkflowExecutionRecord?
    suspend fun recoverOnBoot(now: Long): WorkflowBootRecovery
}

class SqliteWorkflowExecutionStore(dbPath: String) : WorkflowExecutionStore {
    private val database = SqliteStoreSupport.connectSqliteDatabase(dbPath)

    init {
        runBlocking {
            SqliteStoreSupport.retryingTransaction(database) {
                SchemaUtils.create(WorkflowExecutions)
            }
        }
    }

    object WorkflowExecutions : Table("workflow_executions") {
        val id = varchar("id", 64)
        val definitionId = varchar("definition_id", 128).nullable()
        val workflowName = varchar("workflow_name", 255)
        val status = varchar("status", 32)
        val userId = varchar("user_id", 128).nullable()
        val sessionId = varchar("session_id", 128).nullable()
        val triggerSource = varchar("trigger_source", 64).nullable()
        val triggerRef = varchar("trigger_ref", 255).nullable()
        val inputJson = text("input_json")
        val outputJson = text("output_json").nullable()
        val result = text("result").nullable()
        val error = text("error").nullable()
        val startedAt = long("started_at").nullable()
        val completedAt = long("completed_at").nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val checkpointKey = varchar("checkpoint_key", 255).nullable()
        val resumable = bool("resumable")
        val priority = integer("priority")
        val claimantId = varchar("claimant_id", 128).nullable()
        val claimedAt = long("claimed_at").nullable()

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, status, priority, createdAt)
            index(false, claimantId)
            index(false, userId, status)
        }
    }

    override suspend fun create(request: NewWorkflowExecution): WorkflowExecutionRecord {
        val now = System.currentTimeMillis()
        val record = WorkflowExecutionRecord(
            id = UUID.randomUUID().toString(),
            definitionId = request.definitionId,
            workflowName = request.workflowName,
            status = WorkflowExecutionStatus.QUEUED,
            userId = request.userId,
            sessionId = request.sessionId,
            triggerSource = request.triggerSource,
            triggerRef = request.triggerRef,
            inputJson = request.inputJson,
            createdAt = now,
            updatedAt = now,
            priority = request.priority,
        )
        SqliteStoreSupport.retryingTransaction(database) {
            WorkflowExecutions.insert { row -> writeRecord(row, record) }
        }
        return record
    }

    override suspend fun listAll(limit: Int): List<WorkflowExecutionRecord> =
        SqliteStoreSupport.retryingTransaction(database) {
            WorkflowExecutions.selectAll()
                .orderBy(WorkflowExecutions.createdAt, SortOrder.DESC)
                .limit(limit)
                .map(::rowToRecord)
        }

    override suspend fun get(id: String): WorkflowExecutionRecord? = SqliteStoreSupport.retryingTransaction(database) {
        WorkflowExecutions.selectAll()
            .where { WorkflowExecutions.id eq id }
            .singleOrNull()
            ?.let(::rowToRecord)
    }

    override suspend fun listByStatus(
        statuses: Set<WorkflowExecutionStatus>,
        limit: Int,
    ): List<WorkflowExecutionRecord> = SqliteStoreSupport.retryingTransaction(database) {
        if (statuses.isEmpty()) {
            emptyList()
        } else {
            WorkflowExecutions.selectAll()
                .where { WorkflowExecutions.status inList statuses.map { it.name } }
                .orderBy(
                    WorkflowExecutions.priority to SortOrder.DESC,
                    WorkflowExecutions.createdAt to SortOrder.ASC,
                )
                .limit(limit)
                .map(::rowToRecord)
        }
    }

    override suspend fun listBySessionId(sessionId: String): List<WorkflowExecutionRecord> =
        SqliteStoreSupport.retryingTransaction(database) {
            WorkflowExecutions.selectAll()
                .where { WorkflowExecutions.sessionId eq sessionId }
                .orderBy(WorkflowExecutions.createdAt to SortOrder.DESC)
                .map(::rowToRecord)
        }

    override suspend fun listByTrigger(triggerSource: String, triggerRef: String): List<WorkflowExecutionRecord> =
        SqliteStoreSupport.retryingTransaction(database) {
            WorkflowExecutions.selectAll()
                .where {
                    (WorkflowExecutions.triggerSource eq triggerSource) and
                        (WorkflowExecutions.triggerRef eq triggerRef)
                }
                .orderBy(WorkflowExecutions.createdAt, SortOrder.DESC)
                .map(::rowToRecord)
        }

    override suspend fun claimQueued(
        executionId: String,
        claimantId: String,
        now: Long,
    ): WorkflowExecutionRecord? = transition(
        executionId = executionId,
        expected = setOf(WorkflowExecutionStatus.QUEUED),
        target = WorkflowExecutionStatus.RUNNING,
        now = now,
    ) { current ->
        current.copy(
            claimantId = claimantId,
            claimedAt = now,
            startedAt = now,
        )
    }

    override suspend fun transition(
        executionId: String,
        expected: Set<WorkflowExecutionStatus>,
        target: WorkflowExecutionStatus,
        now: Long,
        mutate: (WorkflowExecutionRecord) -> WorkflowExecutionRecord,
    ): WorkflowExecutionRecord? = SqliteStoreSupport.retryingTransaction(database) {
        if (expected.isEmpty()) return@retryingTransaction null

        val current = WorkflowExecutions.selectAll()
            .where {
                (WorkflowExecutions.id eq executionId) and
                    (WorkflowExecutions.status inList expected.map { it.name })
            }
            .singleOrNull()
            ?.let(::rowToRecord)
            ?: return@retryingTransaction null

        val next = mutate(current).copy(
            id = current.id,
            status = target,
            updatedAt = now,
        )

        val updated = WorkflowExecutions.update({
            (WorkflowExecutions.id eq executionId) and
                (WorkflowExecutions.status inList expected.map { it.name })
        }) { row ->
            writeRecord(row, next)
        }

        if (updated == 0) {
            null
        } else {
            WorkflowExecutions.selectAll()
                .where { WorkflowExecutions.id eq executionId }
                .single()
                .let(::rowToRecord)
        }
    }

    override suspend fun complete(
        executionId: String,
        outputJson: String?,
        result: String?,
        now: Long,
    ): WorkflowExecutionRecord? = transition(
        executionId = executionId,
        expected = setOf(WorkflowExecutionStatus.RUNNING),
        target = WorkflowExecutionStatus.COMPLETED,
        now = now,
    ) { current ->
        current.copy(
            outputJson = outputJson,
            result = result,
            error = null,
            completedAt = now,
        )
    }

    override suspend fun fail(
        executionId: String,
        error: String,
        now: Long,
    ): WorkflowExecutionRecord? = transition(
        executionId = executionId,
        expected = setOf(WorkflowExecutionStatus.RUNNING, WorkflowExecutionStatus.CANCEL_REQUESTED),
        target = WorkflowExecutionStatus.FAILED,
        now = now,
    ) { current ->
        current.copy(
            error = error,
            completedAt = now,
        )
    }

    override suspend fun cancel(
        executionId: String,
        reason: String?,
        now: Long,
    ): WorkflowExecutionRecord? = transition(
        executionId = executionId,
        expected = setOf(WorkflowExecutionStatus.CANCEL_REQUESTED),
        target = WorkflowExecutionStatus.CANCELLED,
        now = now,
    ) { current ->
        current.copy(
            error = reason ?: current.error,
            completedAt = now,
        )
    }

    override suspend fun recoverOnBoot(now: Long): WorkflowBootRecovery = SqliteStoreSupport.retryingTransaction(database) {
        val failed = WorkflowExecutions.selectAll()
            .where {
                WorkflowExecutions.status inList listOf(
                    WorkflowExecutionStatus.RUNNING.name,
                    WorkflowExecutionStatus.CANCEL_REQUESTED.name,
                )
            }
            .map { it[WorkflowExecutions.id] }

        val requeued = WorkflowExecutions.selectAll()
            .where { WorkflowExecutions.status eq WorkflowExecutionStatus.QUEUED.name }
            .map { it[WorkflowExecutions.id] }

        if (failed.isNotEmpty()) {
            WorkflowExecutions.update({
                WorkflowExecutions.id inList failed
            }) { row ->
                row[status] = WorkflowExecutionStatus.FAILED.name
                row[error] = "Recovered after process crash"
                row[completedAt] = now
                row[updatedAt] = now
            }
        }

        if (requeued.isNotEmpty()) {
            WorkflowExecutions.update({
                WorkflowExecutions.id inList requeued
            }) { row ->
                row[claimantId] = null
                row[claimedAt] = null
                row[updatedAt] = now
            }
        }

        WorkflowBootRecovery(
            failedRunningIds = failed,
            requeuedIds = requeued,
        )
    }

    private fun rowToRecord(row: ResultRow): WorkflowExecutionRecord = WorkflowExecutionRecord(
        id = row[WorkflowExecutions.id],
        definitionId = row[WorkflowExecutions.definitionId],
        workflowName = row[WorkflowExecutions.workflowName],
        status = WorkflowExecutionStatus.valueOf(row[WorkflowExecutions.status]),
        userId = row[WorkflowExecutions.userId],
        sessionId = row[WorkflowExecutions.sessionId],
        triggerSource = row[WorkflowExecutions.triggerSource],
        triggerRef = row[WorkflowExecutions.triggerRef],
        inputJson = row[WorkflowExecutions.inputJson],
        outputJson = row[WorkflowExecutions.outputJson],
        result = row[WorkflowExecutions.result],
        error = row[WorkflowExecutions.error],
        startedAt = row[WorkflowExecutions.startedAt],
        completedAt = row[WorkflowExecutions.completedAt],
        createdAt = row[WorkflowExecutions.createdAt],
        updatedAt = row[WorkflowExecutions.updatedAt],
        checkpointKey = row[WorkflowExecutions.checkpointKey],
        resumable = row[WorkflowExecutions.resumable],
        priority = row[WorkflowExecutions.priority],
        claimantId = row[WorkflowExecutions.claimantId],
        claimedAt = row[WorkflowExecutions.claimedAt],
    )

    private fun writeRecord(row: UpdateBuilder<*>, record: WorkflowExecutionRecord) {
        row[WorkflowExecutions.id] = record.id
        row[WorkflowExecutions.definitionId] = record.definitionId
        row[WorkflowExecutions.workflowName] = record.workflowName
        row[WorkflowExecutions.status] = record.status.name
        row[WorkflowExecutions.userId] = record.userId
        row[WorkflowExecutions.sessionId] = record.sessionId
        row[WorkflowExecutions.triggerSource] = record.triggerSource
        row[WorkflowExecutions.triggerRef] = record.triggerRef
        row[WorkflowExecutions.inputJson] = record.inputJson
        row[WorkflowExecutions.outputJson] = record.outputJson
        row[WorkflowExecutions.result] = record.result
        row[WorkflowExecutions.error] = record.error
        row[WorkflowExecutions.startedAt] = record.startedAt
        row[WorkflowExecutions.completedAt] = record.completedAt
        row[WorkflowExecutions.createdAt] = record.createdAt
        row[WorkflowExecutions.updatedAt] = record.updatedAt
        row[WorkflowExecutions.checkpointKey] = record.checkpointKey
        row[WorkflowExecutions.resumable] = record.resumable
        row[WorkflowExecutions.priority] = record.priority
        row[WorkflowExecutions.claimantId] = record.claimantId
        row[WorkflowExecutions.claimedAt] = record.claimedAt
    }

    override fun close() {
        // SQLite resources managed by Exposed.
    }
}
