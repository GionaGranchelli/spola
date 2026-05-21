package dev.spola.workflow

data class WorkflowDispatcherConfig(
    val enabled: Boolean = false,
    val pollIntervalMs: Long = 5000,
    val batchSize: Int = 10,
    val globalMaxConcurrent: Int = 4,
    val perUserMaxConcurrent: Int = 2,
)
