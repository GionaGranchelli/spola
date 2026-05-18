# Memory System

Golem's memory system gives agents **explicit, persistent recall** across sessions. Memory is surfaced as tools the agent can call — not hidden state or vector embeddings. The agent decides what to remember and when to look it up.

---

## 1. Why Memory as Tools

Most agent frameworks hide memory in the system prompt or inject it silently. Golem takes a different approach:

- **Explicit tools** — `memory_save` and `memory_search` are tools the agent chooses to call
- **No hidden state** — everything stored is visible, queryable, and deletable
- **Token-efficient** — only relevant memories are loaded (via search), not the entire store
- **Session-persistent** — data survives restarts in SQLite
- **Namespace isolation** — agents with `memoryScope = "agent"` get isolated key prefixes

The agent is instructed to use these tools naturally:

> "Use `memory_save` to store user preferences and project conventions. Use `memory_search` to find facts from previous sessions."

---

## 2. MemoryStore Interface

```kotlin
interface MemoryStore : AutoCloseable {
    /** Save a fact. Upserts if key already exists. */
    suspend fun save(key: String, value: String)

    /** Search memory by key or value content (LIKE %query%). Returns matching entries. */
    suspend fun search(query: String): List<MemoryEntry>

    /** List all entries in the store, ordered by most recent update. */
    suspend fun listAll(): List<MemoryEntry>

    /** Delete an entry by key. Returns true if deleted. */
    suspend fun delete(key: String): Boolean
}

data class MemoryEntry(
    val key: String,
    val value: String,
    val createdAt: String,     // ISO-8601
    val updatedAt: String,     // ISO-8601
)
```

All operations are `suspend` functions — coroutine-safe for concurrent access.

---

## 3. SqliteMemoryStore — Persistence

The primary implementation uses **SQLite via Exposed ORM** with a unique index on the `key` column.

### Table Schema

```
Table: memory_entries
  id         INTEGER PRIMARY KEY AUTOINCREMENT
  key        VARCHAR(512)  UNIQUE INDEX
  value      TEXT
  created_at VARCHAR(32)
  updated_at VARCHAR(32)
```

### Key Behaviors

- **Upsert on save** — if a key exists, the value and `updated_at` are updated via primary key lookup to avoid race conditions
- **LIKE-based search** — `search(query)` matches `%query%` against both `key` and `value` columns (case-insensitive)
- **Limit 20** — search results capped at 20 entries, sorted by most recent update (DESC)
- **Auto-create directory** — parent directory created on init if it doesn't exist
- **Exposed manages connections** — `close()` is a no-op; connection lifecycle is handled by Exposed

### Basic Usage

```kotlin
val store = SqliteMemoryStore("./.golem/memory.db")

// Save a fact (upserts by key)
store.save("user_prefers_tabs", "User prefers tabs over spaces, indent size 2")

// Search by key or value
val results = store.search("tabs")
// Returns list of MemoryEntry matching %tabs% in key or value

// List all entries (most recent first)
val all = store.listAll()

// Delete an entry
val deleted = store.delete("old_temporary_fact")
```

### Concurrent Save Handling

```kotlin
// The implementation uses primary key-based update to avoid race conditions:
override suspend fun save(key: String, value: String) {
    val now = LocalDateTime.now().format(formatter)
    transaction(database) {
        val existing = MemoryEntries.selectAll()
            .where { MemoryEntries.key eq key }.singleOrNull()

        if (existing != null) {
            val id = existing[MemoryEntries.id]
            MemoryEntries.update({ MemoryEntries.id eq id }) {
                it[MemoryEntries.value] = value
                it[MemoryEntries.updatedAt] = now
            }
        } else {
            MemoryEntries.insert {
                it[MemoryEntries.key] = key
                it[MemoryEntries.value] = value
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }
}
```

---

## 4. Memory Tools — `memory_save` and `memory_search`

`registerMemoryTools()` registers two LLM-callable tools into the tool registry.

### memory_save

```kotlin
Tool(
    name = "memory_save",
    description = "Save a fact to persistent memory. Facts are remembered across sessions. " +
        "Use this for user preferences, project conventions, and environment details.",
    parameters = listOf(
        ToolParameter("key", "Unique key for the fact (e.g., 'user_prefers_tabs')", STRING),
        ToolParameter("value", "The fact content to store", STRING),
    ),
    execute = { args -> /* calls memoryStore.save(key, value) */ }
)
```

**Agent conversation example:**

```
User: I prefer tabs over spaces, 2-space width
Agent: → memory_save(
    key="user_prefers_tabs",
    value="User prefers tabs over spaces with 2-space indent"
  )
  ← Saved: user_prefers_tabs
```

### memory_search

```kotlin
Tool(
    name = "memory_search",
    description = "Search persistent memory for facts by key or content. " +
        "Returns matching entries with their keys and values.",
    parameters = listOf(
        ToolParameter("query", "Search term to find in memory keys or values", STRING),
    ),
    execute = { args -> /* calls memoryStore.search(query), filters by namespace */ }
)
```

**Agent conversation example:**

```
Agent: → memory_search(query="preferences")
  ← Found 3 entries:
  [user_prefers_tabs]
  User prefers tabs over spaces with 2-space indent
  (created: 2026-05-14T08:30:00)
  ---
  [user_project]
  Currently working on Golem's memory system
  (created: 2026-05-13T10:00:00)
```

### Namespace Isolation

When memory tools are registered with a `namespace` parameter, keys are transparently prefixed:

```kotlin
// During agent creation, if memoryScope == "agent":
registerMemoryTools(registry, store, namespace = "security-reviewer")
```

**Under the hood:**

- `memory_save(key="finding_xss", ...)` stores the key as `"security-reviewer:finding_xss"`
- `memory_search(query="xss")` filters results to only those starting with `"security-reviewer:"`
- The agent sees the unprefixed key in responses (prefix is stripped for display)
- Storage is fully isolated per-agent namespace

```kotlin
// Namespace prefix logic in registerMemoryTools:
val namespacePrefix = namespace?.let { "$it:" }
fun namespacedKey(key: String): String = namespace?.let { "$it:$key" } ?: key
fun displayKey(key: String): String = namespacePrefix?.let { key.removePrefix(it) } ?: key
```

---

## 5. Auto-Injection at Session Start

At session startup, `AgentFactory.create()` automatically loads **all stored memories** via `memoryStore.listAll()` and injects them into the system prompt.

### What Gets Injected

After persona loading, the factory appends:

```kotlin
val memories = memoryStore.listAll()
if (memories.isNotEmpty()) {
    val memoryBlock = memories.joinToString("\n") { "- **${it.key}**: ${it.value}" }
    persona = "$persona\n\n## Context from Memory (auto-loaded)\n$memoryBlock\n\n" +
        "Use `memory_search` to find more specific facts or `memory_save` to store new ones."
}
```

**Result in the system prompt:**

```
... [persona content] ...

## Context from Memory (auto-loaded)
- **user_prefers_tabs**: User prefers tabs over spaces, indent size 2
- **project_convention**: Project uses Kotlin with Exposed ORM for SQLite

Use `memory_search` to find more specific facts or `memory_save` to store new ones.
```

### Error Handling

If `memoryStore.listAll()` throws (e.g., database corruption), the error is silently caught and the agent starts with just the persona — no memory injection, no crash.

### Memory-Only Agents (memoryScope = "agent")

When `memoryScope = "agent"` is set in an `AgentDefinition`, the memory is namespace-isolated. The auto-injection still loads all memories (the namespace is applied at the tool level for save/search), so the agent's context includes its own previously saved facts.

---

## 6. NoopMemoryStore — Disabled Memory

When `memoryScope = "none"` is set, `GolemFactory` swaps in a `NoopMemoryStore`:

```kotlin
class NoopMemoryStore : MemoryStore {
    override suspend fun save(key: String, value: String) { /* no-op */ }
    override suspend fun search(query: String): List<MemoryEntry> = emptyList()
    override suspend fun listAll(): List<MemoryEntry> = emptyList()
    override suspend fun delete(key: String): Boolean = false
    override fun close() { /* no-op */ }
}
```

This ensures agents with `memoryScope = "none"` cannot read or write any memory, even if memory tools are registered in the registry. The auto-injection at startup produces an empty memory block (if no memory, nothing is appended).

---

## 7. Memory in the Web UI — HtmlMemoryRenderer

`HtmlMemoryRenderer` converts markdown-formatted memory values into HTML for human-friendly viewing. The primary consumer of memory is the AI (markdown is token-efficient), so this renderer is used **only** for the human-facing UI.

### Render a Single Memory Entry

```kotlin
val html = HtmlMemoryRenderer.render(markdownText)
// Simple inline conversion: bold, italic, code, links, headers, lists, tables

// Full standalone HTML page
val fullPage = HtmlMemoryRenderer.render(markdownText, fullDocument = true)
```

### Render a Full Memory List Page

```kotlin
val page = HtmlMemoryRenderer.renderMemoryList(
    entries = store.listAll().map { entry ->
        HtmlMemoryRenderer.MemoryEntry(
            key = entry.key,
            value = entry.value,
            updatedAt = entry.updatedAt,
        )
    },
    query = "preferences",     // optional search badge shown in header
)
// Returns standalone HTML page with dark GitHub-dark theme
```

### Supported Markdown in HTML Rendering

- Bold `**text**`, italic `*text*`
- Inline code `` `code` ``
- Code blocks ` ``` `
- Unordered lists (`-` and `*`)
- Ordered lists (`1.`)
- Headers (`#` through `######`)
- Links `[text](url)`
- Tables (`| col | col |`)
- Horizontal rules (`---`)

### Dark Theme CSS

The rendered pages use a GitHub-dark-inspired theme (dark background `#0d1117`, cards `#161b22`, borders `#30363d`, accent `#58a6ff`) — no external dependencies.

---

## 8. Configuration

### Database Path

Configured in `GolemConfig`:

```kotlin
val memoryDbPath: String = "./.golem/memory.db"   // default (relative to workdir)
```

### In config.yaml

```yaml
memory-db: ~/.golem/data/memory.db   # override default
```

The directory is auto-created on first `SqliteMemoryStore` initialization.

### Path Resolution Order

1. **CLI flag** (if any) — `--memory-db` 
2. **Config file** — `memory-db` in `config.yaml`
3. **Default** — `./.golem/memory.db` relative to working directory
4. **Programmatic** — pass `dbPath` directly to `SqliteMemoryStore(dbPath)` constructor

---

## 9. Lifecycle: How Memory Flows Through a Session

```
┌────────────────────────────────────────────────────────┐
│                      Session Start                      │
│                                                         │
│  1. AgentFactory.create()                               │
│     ├─ PersonaLoader.load() → base system prompt         │
│     ├─ memoryStore.listAll() → all stored facts          │
│     └─ Injects facts into system prompt                  │
│        as "## Context from Memory (auto-loaded)"         │
│                                                          │
│  2. ReAct Loop Starts                                    │
│     ├─ Agent calls memory_save → storage                 │
│     ├─ Agent calls memory_search → retrieval             │
│     └─ Tool uses PermissionEnforcer checks               │
│                                                          │
│  3. Session End                                          │
│     └─ All saved facts persisted in SQLite               │
│                                                          │
│  Next Session Start → repeat from step 1                 │
└────────────────────────────────────────────────────────┘
```

---

## 10. Best Practices

### What to Save

- **User preferences** — tabs vs spaces, preferred tools, coding style, naming conventions
- **Project conventions** — testing framework, build system, architecture patterns
- **Environment details** — API endpoints, database connections, service URLs, versions
- **Corrections** — user corrected something important; save it to avoid repeating mistakes
- **Decision rationale** — why a particular approach was chosen

### What NOT to Save

- **Task progress** — the agent loop already handles this via conversation history
- **Transient state** — temporary variables, intermediate results
- **Common knowledge** — standard library docs, common programming patterns
- **Secrets** — passwords, API keys, tokens (use a secrets manager, not memory)

### Example Save Patterns

```markdown
Key: user_prefers_tabs
Value: User prefers tabs over spaces. Indent size: 2. No trailing whitespace.

Key: project_convention_testing
Value: Test framework: Kotest with string specs. Tests in src/test/kotlin/.
       Run tests with: ./gradlew test

Key: env_tools
Value: Node.js 20, npm 10, Docker 24. MySQL on localhost:3306 with user 'dev'.

Key: project_architecture
Value: Monorepo with 3 modules: golem-core, golem-cli, golem-api.
       Communication via internal event bus.
```

### Agent Lifecycle with Memory

```
Session 1:
  Agent creates code → user says "use tabs" → agent saves →
  memory_save("user_prefers_tabs", "User prefers tabs...")
  ↑ This is now stored persistently in SQLite

Session 2 (new run, next day):
  Auto-loaded into system prompt:
  "## Context from Memory (auto-loaded)
   - **user_prefers_tabs**: User prefers tabs..."
  Agent reads it and uses tabs from the start ✓
```
