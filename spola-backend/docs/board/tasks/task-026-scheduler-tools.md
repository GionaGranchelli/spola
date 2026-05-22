# TASK-026: Scheduler Tools + MCP Registration

## Goal
Register scheduler operations (add/list/remove jobs) as Spola tools, and expose them via MCP.

## Requirements

### 1. SchedulerTools.kt
New file with 3 tools registered in the Spola tool registry:

```kotlin
fun registerSchedulerTools(registry: ToolRegistry, jobStore: SpolaJobStore)
```

**Tool: scheduler_add**
- Parameters: name (string, required), cronExpression (string, required), goal (string, required), enabled (boolean, optional, default=true)
- Returns: the created job with id and nextRunAt

**Tool: scheduler_list**
- Parameters: none
- Returns: formatted list of all scheduled jobs (id, name, cron, enabled, nextRunAt, lastRunAt)

**Tool: scheduler_remove**
- Parameters: id (string, required)
- Returns: success/failure message

### 2. Integration with SpolaFactory
Add `registerSchedulerTools` call in `SpolaFactory.create()` when a schedulerDbPath is configured.
The job store should be created and passed to the tools.

### 3. MCP integration
In `McpRunner.kt`, add a `registerSchedulerTools` call so the scheduler tools appear in MCP mode too.

### 4. Tests
```kotlin
SchedulerToolsTest.kt
- scheduler_add creates a job and returns it
- scheduler_list returns all jobs
- scheduler_remove deletes a job
```

All 57 existing tests must still pass.

### 5. No new dependencies
Use existing Exposed, coroutines, and Tool system.

## Files to create/modify
```
spola-backend-core/src/main/kotlin/dev/spola/scheduler/
└── SchedulerTools.kt          — NEW: tool registration

spola-backend-core/src/test/kotlin/dev/spola/scheduler/
└── SchedulerToolsTest.kt      — NEW: tool tests

spola-backend-core/src/.../SpolaFactory.kt     — MODIFY: add registerSchedulerTools
spola-backend-core/src/.../mcp/McpRunner.kt    — MODIFY: add registerSchedulerTools
```
