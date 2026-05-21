package dev.spola.scheduler

import dev.spola.SpolaConfig
import dev.spola.SpolaFactory
import dev.spola.workflow.WorkflowSchedulerService
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import java.time.Clock
import java.util.UUID

class SpolaScheduler(
    private val jobStore: SpolaJobStore,
    private val config: SpolaConfig,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: Clock = Clock.systemUTC(),
    private val pollInterval: Duration = 5.seconds,
    private val workflowSchedulerService: WorkflowSchedulerService? = null,
    private val jobRunner: suspend (ScheduledJob, SpolaConfig) -> Unit = ::runScheduledJob,
) : AutoCloseable {
    private var loopJob: Job? = null
    private val claimantId = "scheduler-${UUID.randomUUID()}"

    fun start() {
        if (loopJob?.isActive == true) {
            return
        }

        loopJob = scope.launch {
            while (isActive) {
                try {
                    pollOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println("[scheduler] Poll failed: ${e.message}")
                }
                delay(pollInterval)
            }
        }
    }

    suspend fun stop() {
        val job = loopJob ?: return
        loopJob = null
        job.cancelAndJoin()
    }

    suspend fun pollOnce() {
        val now = clock.instant()
        val claimedJobs = jobStore.claimDueJobs(now, claimantId, limit = MAX_CLAIM_BATCH)

        val jobs = claimedJobs.map { job ->
            scope.launch {
                processClaimedJob(job)
            }
        }
        jobs.forEach { it.join() }
    }

    override fun close() {
        runBlocking {
            stop()
        }
    }

    private suspend fun processClaimedJob(job: ScheduledJob) {
        coroutineScope {
            val leaseRenewalJob = launch {
                while (isActive) {
                    delay(CLAIM_RENEWAL_INTERVAL)
                    jobStore.renewClaim(
                        jobId = job.id,
                        claimantId = claimantId,
                        claimedAt = clock.instant(),
                    )
                }
            }

            try {
                if (job.workflowDefinitionId != null) {
                    workflowSchedulerService?.executeScheduledJob(job) ?: jobRunner(job, config)
                } else {
                    jobRunner(job, config)
                }
            } catch (e: Exception) {
                System.err.println("[scheduler] Job ${job.id} (${job.name}) failed: ${e.message}")
            } finally {
                leaseRenewalJob.cancelAndJoin()
                val completedAt = clock.instant()
                val nextRunAt = SpolaCronParser.parse(job.cronExpression).nextFireAfter(completedAt)
                jobStore.updateNextRun(
                    jobId = job.id,
                    nextRunAt = nextRunAt,
                    lastRunAt = completedAt,
                    claimantId = claimantId,
                )
            }
        }
    }

    private companion object {
        const val MAX_CLAIM_BATCH = 16
        val CLAIM_RENEWAL_INTERVAL = 2.minutes
    }
}

private suspend fun runScheduledJob(job: ScheduledJob, config: SpolaConfig) {
    val instance = SpolaFactory.create(config = config)
    try {
        println("[scheduler] Running job ${job.id} (${job.name})")
        val result = instance.run(job.goal)
        println("[scheduler] Job ${job.id} completed")
        println(result)
    } finally {
        instance.close()
    }
}
