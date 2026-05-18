# Scheduler

## Overview

Golem's scheduler provides cron-based job execution. Jobs run Golem agent sessions on a schedule — perfect for recurring tasks like daily code analysis, weekly dependency checks, or periodic system maintenance.

The scheduler is built on:
- **GolemScheduler** — Polling loop that checks for due jobs every 5 seconds
- **GolemJobStore** — SQLite-backed job persistence
- **GolemCronParser** — Parses 5-field cron expressions
- **SchedulerTools** — LLM-accessible tools for job management

## Data Model

```kotlin
data class ScheduledJob(
    val id: String,              // UUID, auto-generated
    val name: String,            // Human-friendly name
    val goal: String,            // Goal to run when the job fires
    val cronExpression: String,  // 5-field cron expression
    val enabled: Boolean,        // Whether the job is active
    val createdAt: Instant,      // Creation timestamp
    val lastRunAt: Instant?,     // Last execution time (null if never run)
    val nextRunAt: Instant,      // Next scheduled execution
)
```

## Architecture

### GolemScheduler

The scheduler runs a polling loop in a coroutine scope:

1. Every 5 seconds, calls `jobStore.getDueJobs(now)` to find enabled jobs whose `nextRunAt <= now`
2. For each due job, creates a Golem agent via `GolemFactory.create()` and runs the job's goal
3. After execution (success or failure), calculates the next fire time using `GolemCronParser`
4. Updates `nextRunAt` and `lastRunAt` in the job store

```kotlin
val scheduler = GolemScheduler(
    jobStore = SqliteGolemJobStore("/path/to/scheduler.db"),
    config = GolemConfig(),
)
scheduler.start()  // Begin polling loop
// ... later ...
scheduler.stop()   // Graceful shutdown
```

The scheduler uses `CoroutineScope(SupervisorJob() + Dispatchers.Default)` so one failing job doesn't affect others. The `pollInterval` defaults to 5 seconds but is configurable.

### GolemJobStore Interface

```kotlin
interface GolemJobStore : AutoCloseable {
    suspend fun add(name: String, goal: String,
        cronExpression: String, enabled: Boolean = true): ScheduledJob
    suspend fun remove(id: String): Boolean
    suspend fun list(): List<ScheduledJob>
    suspend fun get(id: String): ScheduledJob?
    suspend fun getDueJobs(now: Instant): List<ScheduledJob>
    suspend fun updateNextRun(jobId: String, nextRunAt: Instant,
        lastRunAt: Instant = Instant.now()): Boolean
}
```

### SqliteGolemJobStore

SQLite implementation using Exposed ORM with a `scheduled_jobs` table:

```
Table: scheduled_jobs
├── id              VARCHAR(64)   PRIMARY KEY
├── name            VARCHAR(512)
├── goal            TEXT
├── cron_expression VARCHAR(128)
├── enabled         BOOLEAN
├── created_at      BIGINT
├── last_run_at     BIGINT        nullable
└── next_run_at     BIGINT
```

Auto-creates the database file and parent directories on initialization. Jobs are listed ordered by `nextRunAt ASC`.

### GolemCronParser

Parses standard 5-field cron expressions into TramAI's `CronSchedule`:

```
Format: minute hour day-of-month month day-of-week
Example: "0 6 * * 1" = Every Monday at 6:00 AM
```

The parser:
- Requires exactly 5 whitespace-separated fields
- Delegates to TramAI's `at("0 <expression>")` for actual scheduling
- Uses the system default timezone by default

Valid expressions:
- `"0 9 * * 1-5"` — Weekdays at 9:00 AM
- `"30 2 * * 0"` — Sundays at 2:30 AM
- `"0 */2 * * *"` — Every 2 hours
- `"0 0 1 * *"` — First day of every month at midnight

## Agent Tools

The scheduler is exposed to the Golem agent via three tools registered by `registerSchedulerTools()`.

### scheduler_add

Create a new scheduled job.

```
Parameters:
  name (required)         — Human-friendly job name
  cronExpression (required) — Five-field cron expression
  goal (required)         — Goal to run when the job fires
  enabled (optional)      — Whether to start enabled (default: true)
```

**Example agent call:**
```
scheduler_add(name="Daily code cleanup", cronExpression="0 6 * * 1-5",
  goal="Run code cleanup and remove unused imports")
```

**Response:**
```
Scheduled job created:
id: 550e8400-e29b-41d4-a716-446655440000
name: Daily code cleanup
cron: 0 6 * * 1-5
enabled: true
nextRunAt: 2026-05-15T06:00:00Z
```

### scheduler_list

List all scheduled jobs with their next and last run times.

**Example agent call:**
```
scheduler_list()
```

**Response:**
```
id: 550e8400-e29b-41d4-a716-446655440000
name: Daily code cleanup
cron: 0 6 * * 1-5
enabled: true
nextRunAt: 2026-05-15T06:00:00Z
lastRunAt: 2026-05-14T06:00:00Z

id: 550e8400-e29b-41d4-a716-446655440001
name: Weekly dependency audit
cron: 0 9 * * 1
enabled: true
nextRunAt: 2026-05-18T09:00:00Z
lastRunAt: never
```

### scheduler_remove

Remove a scheduled job by ID.

```
Parameters:
  id (required) — Scheduled job ID
```

**Example agent call:**
```
scheduler_remove(id="550e8400-e29b-41d4-a716-446655440000")
```

**Response:** `Removed scheduled job 550e8400-e29b-41d4-a716-446655440000`

## CLI Reference

```
golem scheduler add --name <name> --cron <expr> <goal>
golem scheduler list
golem scheduler remove <id>
```

### Examples

```bash
# Add a daily maintenance job
golem scheduler add --name "Daily cleanup" --cron "0 6 * * 1-5" \
  "Run code cleanup and fix lint warnings"

# Add a weekly security scan
golem scheduler add --name "Security scan" --cron "0 10 * * 1" \
  "Review all dependencies for security advisories"

# List all jobs
golem scheduler list
# Output: job-id-1 | Daily cleanup | enabled=true | next=2026-05-15T06:00:00Z | cron=0 6 * * 1-5

# Remove a job
golem scheduler remove job-id-1
```

## API Reference

### GET /api/jobs

List all scheduled jobs.

**Response:**
```json
{
  "jobs": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "Daily code cleanup",
      "goal": "Run code cleanup",
      "cronExpression": "0 6 * * 1-5",
      "enabled": true,
      "nextRunAt": "2026-05-15T06:00:00Z",
      "lastRunAt": "2026-05-14T06:00:00Z"
    }
  ]
}
```

### POST /api/jobs

Create a scheduled job.

**Request:**
```json
{
  "name": "Weekly audit",
  "goal": "Audit dependencies",
  "cronExpression": "0 9 * * 1",
  "enabled": true
}
```

**Response (201 Created):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440001",
  "name": "Weekly audit",
  "goal": "Audit dependencies",
  "cronExpression": "0 9 * * 1",
  "enabled": true,
  "nextRunAt": "2026-05-18T09:00:00Z",
  "lastRunAt": null
}
```

### DELETE /api/jobs/{id}

Remove a scheduled job.

**Response:**
```json
{
  "removed": true,
  "id": "550e8400-e29b-41d4-a716-446655440001",
  "message": "Scheduled job removed"
}
```

## Daemon Mode

The scheduler runs as part of Golem's daemon mode:

```bash
golem --daemon
```

This starts the scheduler polling loop, kanban store, and other background services. The daemon keeps running until interrupted (Ctrl+C) or killed.

## Integration with Process Engine

Scheduled jobs can run process templates. The goal of a scheduled job is simply the goal string passed to the agent — the agent can then use tools to invoke process workflows:

```bash
golem scheduler add --name "Weekly refactor" --cron "0 3 * * 0" \
  "Run the refactor process template on the codebase to improve code quality"
```

The scheduler agent evaluates the goal and can call `process run` tools or orchestration tools as needed.
