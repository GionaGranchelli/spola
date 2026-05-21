package dev.spola.scheduler

import dev.spola.sqlite.SqliteStoreSupport
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ScheduledJob(
    val id: String,
    val name: String,
    val goal: String,
    val cronExpression: String,
    val enabled: Boolean,
    val workflowDefinitionId: String? = null,
    val parametersJson: String? = null,
    val createdAt: Instant,
    val lastRunAt: Instant?,
    val nextRunAt: Instant,
)

interface SpolaJobStore : AutoCloseable {
    suspend fun add(
        name: String,
        goal: String,
        cronExpression: String,
        enabled: Boolean = true,
        workflowDefinitionId: String? = null,
        parametersJson: String? = null,
    ): ScheduledJob

    suspend fun remove(id: String): Boolean

    suspend fun list(): List<ScheduledJob>

    suspend fun get(id: String): ScheduledJob?

    @Deprecated("Use claimDueJobs() for scheduler polling to avoid double execution")
    suspend fun getDueJobs(now: Instant): List<ScheduledJob>

    suspend fun claimDueJobs(
        now: Instant,
        claimantId: String,
        limit: Int = 10,
    ): List<ScheduledJob>

    suspend fun renewClaim(
        jobId: String,
        claimantId: String,
        claimedAt: Instant,
    ): Boolean

    suspend fun updateNextRun(
        jobId: String,
        nextRunAt: Instant,
        lastRunAt: Instant = Instant.now(),
        claimantId: String? = null,
    ): Boolean
}

class SqliteSpolaJobStore(
    dbPath: String,
    private val clock: Clock = Clock.systemUTC(),
) : SpolaJobStore {
    private val database = SqliteStoreSupport.connectSqliteDatabase(dbPath)

    init {
        runBlocking {
            SqliteStoreSupport.retryingTransaction(database) {
                SchemaUtils.createMissingTablesAndColumns(ScheduledJobs)
            }
        }
    }

    object ScheduledJobs : Table("scheduled_jobs") {
        val id = varchar("id", 64)
        val name = varchar("name", 512)
        val goal = text("goal")
        val cronExpression = varchar("cron_expression", 128)
        val enabled = bool("enabled")
        val workflowDefinitionId = varchar("workflow_definition_id", 128).nullable()
        val parametersJson = text("parameters_json").nullable()
        val createdAt = long("created_at")
        val lastRunAt = long("last_run_at").nullable()
        val nextRunAt = long("next_run_at")
        val claimedBy = varchar("claimed_by", 128).nullable()
        val claimedAt = long("claimed_at").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    override suspend fun add(
        name: String,
        goal: String,
        cronExpression: String,
        enabled: Boolean,
        workflowDefinitionId: String?,
        parametersJson: String?,
    ): ScheduledJob {
        val now = clock.instant()
        val nextRunAt = SpolaCronParser.parse(cronExpression).nextFireAfter(now)
        val job = ScheduledJob(
            id = UUID.randomUUID().toString(),
            name = name,
            goal = goal,
            cronExpression = cronExpression.trim(),
            enabled = enabled,
            workflowDefinitionId = workflowDefinitionId,
            parametersJson = parametersJson,
            createdAt = now,
            lastRunAt = null,
            nextRunAt = nextRunAt,
        )

        SqliteStoreSupport.retryingTransaction(database) {
            ScheduledJobs.insert {
                it[id] = job.id
                it[ScheduledJobs.name] = job.name
                it[ScheduledJobs.goal] = job.goal
                it[ScheduledJobs.cronExpression] = job.cronExpression
                it[ScheduledJobs.enabled] = job.enabled
                it[ScheduledJobs.workflowDefinitionId] = job.workflowDefinitionId
                it[ScheduledJobs.parametersJson] = job.parametersJson
                it[createdAt] = job.createdAt.toEpochMilli()
                it[lastRunAt] = null
                it[ScheduledJobs.nextRunAt] = job.nextRunAt.toEpochMilli()
                it[claimedBy] = null
                it[claimedAt] = null
            }
        }

        return job
    }

    override suspend fun remove(id: String): Boolean = SqliteStoreSupport.retryingTransaction(database) {
        ScheduledJobs.deleteWhere { ScheduledJobs.id eq id } > 0
    }

    override suspend fun list(): List<ScheduledJob> = SqliteStoreSupport.retryingTransaction(database) {
        ScheduledJobs.selectAll()
            .orderBy(ScheduledJobs.nextRunAt, SortOrder.ASC)
            .map(::rowToJob)
    }

    override suspend fun get(id: String): ScheduledJob? = SqliteStoreSupport.retryingTransaction(database) {
        ScheduledJobs.selectAll()
            .where { ScheduledJobs.id eq id }
            .singleOrNull()
            ?.let(::rowToJob)
    }

    @Deprecated("Use claimDueJobs() for scheduler polling to avoid double execution")
    override suspend fun getDueJobs(now: Instant): List<ScheduledJob> = SqliteStoreSupport.retryingTransaction(database) {
        ScheduledJobs.selectAll()
            .where {
                (ScheduledJobs.enabled eq true) and
                    (ScheduledJobs.nextRunAt lessEq now.toEpochMilli())
            }
            .orderBy(ScheduledJobs.nextRunAt, SortOrder.ASC)
            .map(::rowToJob)
    }

    override suspend fun claimDueJobs(
        now: Instant,
        claimantId: String,
        limit: Int,
    ): List<ScheduledJob> = SqliteStoreSupport.retryingTransaction(database) {
        val nowMillis = now.toEpochMilli()
        val reclaimBefore = nowMillis - CLAIM_TIMEOUT_MILLIS
        val candidates = ScheduledJobs.selectAll()
            .where {
                (ScheduledJobs.enabled eq true) and
                    (ScheduledJobs.nextRunAt lessEq nowMillis) and
                    (
                        ScheduledJobs.claimedBy.isNull() or
                            (
                                ScheduledJobs.claimedAt.isNotNull() and
                                    (ScheduledJobs.claimedAt lessEq reclaimBefore)
                            )
                        )
            }
            .orderBy(ScheduledJobs.nextRunAt, SortOrder.ASC)
            .limit(limit)
            .map(::rowToJob)

        candidates.mapNotNull { job ->
            val updated = ScheduledJobs.update({
                (ScheduledJobs.id eq job.id) and
                    (
                        ScheduledJobs.claimedBy.isNull() or
                            (
                                ScheduledJobs.claimedAt.isNotNull() and
                                    (ScheduledJobs.claimedAt lessEq reclaimBefore)
                            )
                        )
            }) {
                it[claimedBy] = claimantId
                it[claimedAt] = nowMillis
            }
            if (updated > 0) {
                job
            } else {
                null
            }
        }
    }

    override suspend fun renewClaim(
        jobId: String,
        claimantId: String,
        claimedAt: Instant,
    ): Boolean = SqliteStoreSupport.retryingTransaction(database) {
        ScheduledJobs.update({
            (ScheduledJobs.id eq jobId) and
                (ScheduledJobs.claimedBy eq claimantId)
        }) {
            it[ScheduledJobs.claimedAt] = claimedAt.toEpochMilli()
        } > 0
    }

    override suspend fun updateNextRun(
        jobId: String,
        nextRunAt: Instant,
        lastRunAt: Instant,
        claimantId: String?,
    ): Boolean = SqliteStoreSupport.retryingTransaction(database) {
        val claimedByMatches = claimantId?.let { ScheduledJobs.claimedBy eq it } ?: Op.TRUE
        ScheduledJobs.update({
            (ScheduledJobs.id eq jobId) and claimedByMatches
        }) {
            it[ScheduledJobs.nextRunAt] = nextRunAt.toEpochMilli()
            it[ScheduledJobs.lastRunAt] = lastRunAt.toEpochMilli()
            it[ScheduledJobs.claimedBy] = null
            it[ScheduledJobs.claimedAt] = null
        } > 0
    }

    private fun rowToJob(row: ResultRow): ScheduledJob = ScheduledJob(
        id = row[ScheduledJobs.id],
        name = row[ScheduledJobs.name],
        goal = row[ScheduledJobs.goal],
        cronExpression = row[ScheduledJobs.cronExpression],
        enabled = row[ScheduledJobs.enabled],
        workflowDefinitionId = row[ScheduledJobs.workflowDefinitionId],
        parametersJson = row[ScheduledJobs.parametersJson],
        createdAt = Instant.ofEpochMilli(row[ScheduledJobs.createdAt]),
        lastRunAt = row[ScheduledJobs.lastRunAt]?.let(Instant::ofEpochMilli),
        nextRunAt = Instant.ofEpochMilli(row[ScheduledJobs.nextRunAt]),
    )

    override fun close() {
        // SQLite resources are managed by Exposed.
    }

    private companion object {
        const val CLAIM_TIMEOUT_MILLIS = 30 * 60 * 1000L
    }
}
