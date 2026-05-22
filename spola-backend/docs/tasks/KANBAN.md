# Kanban Task Management

## Overview

Spola's Kanban system provides simple, SQLite-backed task management. Tasks can be created, updated, listed, and deleted via tools that the agent can call directly — no external service needed.

The kanban system is ideal for:
- Tracking work items during agent sessions
- Managing TODO lists that persist across sessions
- Lightweight project management without Jira/Asana
- Integration with scheduled jobs for recurring task creation

## Data Model

```kotlin
data class KanbanTask(
    val id: String,           // UUID, auto-generated
    val title: String,        // Task title
    val description: String?, // Optional description
    val status: String,       // "todo", "in_progress", "blocked", "done"
    val priority: String?,    // "low", "medium", "high", "critical"
    val labels: String?,      // Comma-separated labels
    val createdAt: Long,      // Epoch millis
    val updatedAt: Long,      // Epoch millis
)
```

Valid statuses: `todo`, `in_progress`, `blocked`, `done`
Valid priorities: `low`, `medium`, `high`, `critical`

## Storage

Tasks are stored in SQLite via `SqliteKanbanStore` using Exposed ORM. The `kanban_tasks` table is indexed on `status` for fast filtering.

```
Table: kanban_tasks
├── id          VARCHAR(64)   PRIMARY KEY
├── title       VARCHAR(512)
├── description TEXT          nullable
├── status      VARCHAR(32)   INDEXED
├── priority    VARCHAR(16)   nullable
├── labels      VARCHAR(256)  nullable
├── created_at  BIGINT
└── updated_at  BIGINT
```

The store auto-creates the database file and parent directories on initialization.

## Agent Tools

The kanban system is exposed to the Spola agent via four tools registered by `registerKanbanTools()`.

### task_create

Create a new task.

```
Parameters:
  title (required)      — Task title
  description (optional) — Task description
  status (optional)     — todo (default), in_progress, blocked, done
  priority (optional)   — low, medium, high, critical
  labels (optional)     — Comma-separated labels
```

**Example agent call:**
```
task_create(title="Refactor auth module", status="todo", priority="high", labels="security,refactor")
```

**Response:**
```
Task created:
id: 550e8400-e29b-41d4-a716-446655440000
title: Refactor auth module
status: todo
priority: high
labels: security,refactor
createdAt: 1700000000000
```

### task_update

Update an existing task. Only provided fields are changed.

```
Parameters:
  id (required)         — Task ID to update
  title (optional)      — New title
  description (optional) — New description
  status (optional)     — New status
  priority (optional)   — New priority
  labels (optional)     — New labels
```

**Example agent call:**
```
task_update(id="550e8400-e29b-41d4-a716-446655440000", status="in_progress", priority="critical")
```

**Response:**
```
Task updated:
id: 550e8400-e29b-41d4-a716-446655440000
title: Refactor auth module
status: in_progress
priority: critical
updatedAt: 1700000100000
```

### task_list

List all tasks, optionally filtered by status.

```
Parameters:
  status (optional) — Filter by todo, in_progress, blocked, done. Omit to list all.
```

**Example agent call:**
```
task_list(status="todo")
```

**Response:**
```
id: 550e8400-e29b-41d4-a716-446655440000
title: Refactor auth module
status: todo
priority: high
labels: security,refactor

id: 550e8400-e29b-41d4-a716-446655440001
title: Update README
status: todo
priority: low
```

### task_delete

Delete a task by ID.

```
Parameters:
  id (required) — Task ID to delete
```

**Example agent call:**
```
task_delete(id="550e8400-e29b-41d4-a716-446655440000")
```

**Response:** `Deleted task 550e8400-e29b-41d4-a716-446655440000`

## Store Interface

For programmatic access:

```kotlin
interface KanbanStore : AutoCloseable {
    suspend fun create(title: String, description: String? = null,
        status: String = "todo", priority: String? = null,
        labels: String? = null): KanbanTask
    suspend fun update(id: String, title: String? = null,
        description: String? = null, status: String? = null,
        priority: String? = null, labels: String? = null): KanbanTask?
    suspend fun list(status: String? = null): List<KanbanTask>
    suspend fun remove(id: String): Boolean
    suspend fun get(id: String): KanbanTask?
}
```

## Usage from Code

```kotlin
val store = SqliteKanbanStore("/path/to/kanban.db")

// Create
val task = store.create(
    title = "Add tests for payment module",
    status = "todo",
    priority = "high",
    labels = "testing,payment",
)

// Update
store.update(task.id, status = "in_progress")

// List
val allTasks = store.list()
val todoTasks = store.list(status = "todo")

// Get by ID
val found = store.get(task.id)

// Delete
val removed = store.remove(task.id)
```
