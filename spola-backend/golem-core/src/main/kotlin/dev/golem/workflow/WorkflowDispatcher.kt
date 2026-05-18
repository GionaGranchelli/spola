package dev.spola.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface WorkflowDispatcher {
    suspend fun start()
    suspend fun stop()
}

class AsyncWorkflowDispatcher(
    private val executionStore: WorkflowExecutionStore,
    private val executionService: WorkflowExecutionService,
    private val claimantId: String = "dispatcher-${UUID.randomUUID()}",
    private val pollIntervalMs: Long = 5000,
    private val batchSize: Int = 10,
    private val globalMaxConcurrent: Int = 4,
    private val perUserMaxConcurrent: Int = 2,
    private val pollScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val executionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : WorkflowDispatcher {
    private val globalSemaphore = Semaphore(globalMaxConcurrent)
    private val perUserSemaphores = ConcurrentHashMap<String, Semaphore>()
    private var pollJob: Job? = null

    override suspend fun start() {
        if (pollJob?.isActive == true) {
            return
        }

        executionStore.recoverOnBoot(System.currentTimeMillis())

        pollJob = pollScope.launch {
            while (isActive) {
                try {
                    pollOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println("[workflow-dispatcher] Poll failed: ${e.message}")
                }
                delay(pollIntervalMs)
            }
        }
    }

    override suspend fun stop() {
        val job = pollJob ?: return
        pollJob = null
        job.cancel()
        job.join()
    }

    private suspend fun pollOnce() {
        val queued = executionStore.listByStatus(
            statuses = setOf(WorkflowExecutionStatus.QUEUED),
            limit = batchSize,
        )

        for (record in queued) {
            val claimed = executionStore.claimQueued(
                executionId = record.id,
                claimantId = claimantId,
                now = System.currentTimeMillis(),
            ) ?: continue

            executionScope.launch {
                runClaimedExecution(claimed)
            }
        }
    }

    private suspend fun runClaimedExecution(record: WorkflowExecutionRecord) {
        globalSemaphore.withPermit {
            userSemaphore(record.userId).withPermit {
                try {
                    executionService.runExecution(record)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println("[workflow-dispatcher] Execution ${record.id} failed: ${e.message}")
                }
            }
        }
    }

    private fun userSemaphore(userId: String?): Semaphore {
        val key = userId ?: "__anonymous__"
        return perUserSemaphores.computeIfAbsent(key) {
            Semaphore(perUserMaxConcurrent)
        }
    }
}
