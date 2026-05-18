# TASK-020: Golem Cron/Scheduler Mode

## Goal
Add a cron/scheduler mode to Golem that runs scheduled jobs. Users can schedule
recurring Golem tasks (agent runs with a goal) using cron expressions, and the
scheduler runs as a daemon, polling for due jobs and executing them.

## Requirements

### 1. Job model
```kotlin
data class ScheduledJob(
    val id: String,           // UUID
    val name: String,          // human-friendly name
    val goal: String,          // agent goal to run
    val cronExpression: String, // 5-field cron ("*/5 * * * *")
    val enabled: Boolean,
    val createdAt: Instant,
    val lastRunAt: Instant?,
    val nextRunAt: Instant,
)
```

### 2. JobStore (SQLite via Exposed)
- Table: `scheduled_jobs`
- CRUD: add, remove, list, get
- `getDueJobs(now: Instant): List<ScheduledJob>` — finds jobs where enabled=true AND nextRunAt <= now
- `updateNextRun(jobId: String, nextRunAt: Instant)` — update after execution
- Use existing Exposed setup (same pattern as SqliteMemoryStore)

### 3. Cron expression parsing
- Use `CronSchedule` from TramAI scheduler (`dev.tramai.scheduler.at(expression)`)
- `cronSchedule.nextFireAfter(now)` to compute next run after execution
- Parse 5-field expressions (add "0" prefix for seconds internally)

### 4. Scheduler daemon
```kotlin
class GolemScheduler(
    private val jobStore: GolemJobStore,
    private val config: GolemConfig,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    private var loopJob: Job? = null
    private val pollInterval = 5.seconds

    fun start()  // launches polling coroutine
    suspend fun stop()
    suspend fun pollOnce()  // gets due jobs, runs them, updates nextRunAt
}
```

### 5. CLI integration
- `--daemon` flag — starts scheduler mode
- `scheduler add --name "daily backup" --cron "0 3 * * *" "run backup tool"`
- `scheduler list`
- `scheduler remove <id>`
- Or embed in REPL: `/scheduler add`, `/scheduler list`, `/scheduler remove`

### 6. Tool exposure (Phase 2)
- Register `scheduler_add`, `scheduler_remove`, `scheduler_list` as Golem tools
- This lets the agent self-schedule recurring tasks

## Files to create/modify

### New files:
```
golem-core/src/main/kotlin/dev/golem/scheduler/
├── GolemJobStore.kt      — SQLite-backed job store
├── GolemScheduler.kt      — Polling daemon
├── SchedulerTools.kt      — Tool registration
└── GolemCronParser.kt     — Cron expression wrapper

golem-core/src/test/kotlin/dev/golem/scheduler/
├── GolemJobStoreTest.kt
└── GolemSchedulerTest.kt
```

### Modified files:
```
golem-core/build.gradle.kts        — add tramai-scheduler dependency
golem-cli/src/.../cli/Main.kt      — add --daemon flag, scheduler commands
golem-core/src/.../mcp/McpRunner.kt — register scheduler tools
```

## Dependencies
- `libs.tramai.scheduler` (new — add to libs.versions.toml)
- `libs.coroutines.core` (already present)
- `libs.exposed.core` + `libs.exposed.jdbc` (already present)
- `libs.sqlite.jdbc` (already present)

## Test expectations
- 3+ unit tests per new class
- `GolemJobStoreTest` — add, remove, list, getDueJobs, updateNextRun
- `GolemSchedulerTest` — pollOnce finds and runs due jobs, updates nextRunAt
- All 43 existing tests must still pass (no regressions)
