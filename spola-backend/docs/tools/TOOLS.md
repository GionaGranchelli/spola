# Golem Tools Reference

> All 61 tools the Golem agent can call, with exact parameter names, types, descriptions, usage notes, and examples.

---

## 1. Tool Architecture

### How Tools Are Defined

Every tool is a `Tool` data class with four fields:

- **name** — String identifier the LLM uses to call the tool
- **description** — Tells the LLM when and why to use it
- **parameters** — List of `ToolParameter` objects (name, description, type, required, defaultValue)
- **execute** — Lambda `suspend (Map<String, Any>) -> ToolResult` that implements the tool

`ToolParameter` supports three types: `STRING`, `INTEGER`, `BOOLEAN`. Each parameter has a `required` flag and an optional `defaultValue`.

`ToolResult` is a sealed result:
- `ToolResult.ok(output)` — success with text output
- `ToolResult.fail(error)` — failure with error message

### How Tools Are Registered

All tools are registered via `ToolRegistryFactory`, which builds four registry variants:

- **buildDefaultToolRegistry** — Core + Memory + JVM + Scheduler + Checkpoint + Delivery + Provenance + ProjectInsight + Agent + Skill + Plugins. Used by CLI agent sessions.
- **buildAgentToolRegistry** — Subset scoped by AgentDefinition permissions. Used by custom agent runs.
- **buildApiToolRegistry** — Core + Memory + Scheduler + Kanban + Checkpoint + JVM + Delivery + Provenance + Agent + Skill. Used by REST API server.
- **buildMcpToolRegistry** — Core + Memory + Scheduler + Kanban + Checkpoint + JVM + Provenance + Agent + Skill. Used by MCP server mode.

### How the ReAct Loop Dispatches Tools

In `GolemAgent`:

1. The agent builds `ToolDefinition` objects from `toolRegistry.schemas()` and sends them with every LLM request.
2. The LLM responds with `toolCalls` — each has a `name` and `argumentsJson`.
3. `executeTool(name, argumentsJson)` looks up the tool in the registry, parses the JSON arguments via Jackson, and calls `tool.execute(args)`.
4. Results are compressed with TokenJuice if `config.compressionEnabled` is true.
5. Failed tools are retried once (2 total attempts per ADR-002).

### Security Architecture

- **Filesystem**: `GOLEM_ALLOWED_DIRS` env var restricts read/write/search to specific directories. `unsafeMode` (from `config.unsafe`) bypasses all checks.
- **Shell**: Blocked commands (`sudo`, `su`, `chown`, `dd`, `reboot`, etc.) and blocked interpreters (`bash`, `sh`, `python`, `node`, etc.) are enforced server-side.
- **Agent permissions**: Per-agent `filesystemAccess`, `shellAccess`, `networkAccess`, and `toolPolicy` control tool visibility.

---

## 2. File Tools

### `read_file`

Reads file content with line numbers, offset, and pagination. Primary tool for examining existing code, config, or docs.

**Parameters**

- `path` (String, required) — Path to the file to read
- `offset` (Integer, optional, default: 1) — Starting line number (1-indexed)
- `limit` (Integer, optional, default: 500, max: 2000) — Maximum number of lines to read

**Behavior**

- Resolves relative paths against the working directory (`config.workingDirectory` or `user.dir`)
- Rejects paths outside `GOLEM_ALLOWED_DIRS` unless `unsafeMode` is true
- Returns `"File not found"` if path doesn't exist, `"Not a regular file"` for directories
- Output format: `LINE_NUM|content` per line with a summary header `(N of M lines)`

**Edge Cases**

- Binary files: not blocked by tool but file contents may be garbled
- Large files: use offset/limit to paginate
- Symlinks: resolved to real path for security check

**Example**

```
Input: read_file(path="src/main.kt", offset=1, limit=20)
Output:
(20 of 150 lines)
1|package com.example
2|
3|fun main() { ...
```

---

### `write_file`

Creates or overwrites a file with the given content. Parent directories are created automatically.

**Parameters**

- `path` (String, required) — Path where to write the file
- `content` (String, required) — Complete content to write (overwrites existing)

**Behavior**

- Creates parent directories via `Files.createDirectories`
- Uses `TRUNCATE_EXISTING` — always overwrites, never appends
- Returns byte count on success

**Security**

- Subject to `GOLEM_ALLOWED_DIRS` checks
- Does NOT check file size limits — be mindful of what you write

**Example**

```
Input: write_file(path="hello.txt", content="Hello, world!")
Output: OK (13 bytes written)
```

---

### `search_files`

Recursively search files by regex pattern. Supports optional file glob filtering. Max 50 results.

**Parameters**

- `pattern` (String, required) — Regex pattern to search for
- `path` (String, optional, default: ".") — Directory to search in
- `file_glob` (String, optional) — Optional file glob pattern (e.g., `*.kt`, `*.md`)

**Behavior**

- Walks the directory tree recursively
- Skips files larger than 10 MB
- Skips binary files (files that throw on `Files.lines()`)
- Output format: `relative/path:line: content` (trimmed to first match per line)

**Edge Cases**

- Invalid regex returns `"Invalid regex pattern: ..."`
- Empty results returns `"No matches found for pattern: ..."`
- File glob fallback: if `PathMatcher` fails, falls back to a manual glob-to-regex conversion

**Example**

```
Input: search_files(pattern="fun main", path=".", file_glob="*.kt")
Output:
Found 3 matches:
src/main.kt:1: fun main() {
src/cli.kt:45: fun main(args: Array<String>) {
test/MainTest.kt:12: fun main() = runBlocking {
```

---

## 3. Edit Tool

### `edit_file`

Targeted find-and-replace for making surgical edits without rewriting entire files. Returns a diff of changes.

**Parameters**

- `path` (String, required) — Path to the file to edit
- `oldText` (String, required) — The exact text to find and replace
- `newText` (String, required) — The replacement text
- `replaceAll` (Boolean, optional, default: false) — If true, replace ALL occurrences

**Behavior**

1. Tries **exact string match** first (case-sensitive, character-for-character)
2. Falls back to **whitespace-normalized regex match** — replaces arbitrary whitespace sequences with `\s+` regex
3. If `replaceAll=false` and multiple matches found, returns error asking user to be more specific or use `replaceAll=true`
4. Returns a diff showing `- removed` and `+ added` lines (capped at 100 lines)

**Security**

- Subject to `checkAllowed()` path restrictions
- Only works on regular files (not directories)

**Example**

```
Input: edit_file(path="main.kt",
                  oldText="val x = 1",
                  newText="val x = 2")
Output: OK, file edited.
- val x = 1
+ val x = 2
```

---

## 4. Shell Tool

### `shell`

Execute a shell command using argv-based process execution (no shell injection vector). Uses RTK (Rust Token Killer) transparently for supported commands.

**Parameters**

- `command` (String, required) — Command string (parsed into argv respecting quotes)
- `workdir` (String, optional, default: ".") — Working directory
- `timeout` (Integer, optional, default: 30, max: 300) — Max execution time in seconds

**Security — Blocked Commands**

The following commands are **always blocked** (exit with error):

`sudo`, `su`, `passwd`, `chown`, `chmod`, `mount`, `umount`, `mkfs`, `dd`, `fdisk`, `parted`, `reboot`, `shutdown`, `halt`, `poweroff`, `init`, `killall`, `pkill`

**Security — Blocked Interpreters**

The following interpreters are **always blocked** (use direct commands instead):

`bash`, `sh`, `zsh`, `dash`, `ksh`, `fish`, `python`, `python3`, `perl`, `ruby`, `lua`, `node`, `nodejs`, `deno`, `php`

**RTK (Rust Token Killer) Support**

If `rtk` binary is available on the PATH and the command matches the supported list, Golem transparently prefixes `rtk` to compress output and save tokens. Supported: `git`, `cargo`, `npm`, `npx`, `yarn`, `pnpm`, `ls`, `cat`, `grep`, `rg`, `find`, `diff`, `gh`, `jest`, `vitest`, `pytest`, `go`, `ruff`, `docker`, `tsc`, `eslint`, `prettier`.

The `rtk` command itself is blocked from direct invocation to prevent blocklist bypass.

**Behavior**

- Command string is parsed into argv respecting single and double quotes (no shell injection)
- Stdout and stderr are read separately via background threads
- Output truncated at 50 KB
- Exit code 0 → success (stdout), non-zero → failure (stderr + stdout)

**Edge Cases**

- Timeout: process is destroyed via `destroyForcibly()` and returns failure
- Empty command: returns `"Empty command"`
- Working directory not found: returns error
- Permission enforced by `PermissionEnforcer` when running under agent scoping

**Example**

```
Input: shell(command="ls -la", workdir="/tmp", timeout=10)
Output:
total 12
drwxrwxr-x  2 user user 4096 ...
```

---

## 5. Git Tools

### `git_diff`

Show git diff output for the repository or a specific path.

**Parameters**

- `path` (String, optional, default: ".") — File/directory to diff
- `cached` (Boolean, optional, default: false) — Show staged changes only (`--cached`)
- `head` (String, optional) — Revision to diff against (e.g., `HEAD~1`)

**Behavior**

- Runs `git diff [--cached] [revision] -- <path>`
- Path is sanitized: resolved against working directory, relativized for git
- Timeout: 30 seconds

**Example**

```
Input: git_diff(path="src/main.kt", cached=false)
Output:
diff --git a/src/main.kt b/src/main.kt
index abc..def 100644
--- a/src/main.kt
+++ b/src/main.kt
@@ -1,3 +1,4 @@
+// new comment
```

---

### `git_commit`

Stage changes and create a commit with a message.

**Parameters**

- `message` (String, required) — Commit message (must not be empty)
- `files` (String, optional) — Space-separated file paths to stage. If empty, stages all changes (`git add -A`)

**Behavior**

- Runs `git add <files>` or `git add -A`, then `git commit -m <message>`
- Returns combined add and commit output
- Empty message returns error

**Example**

```
Input: git_commit(message="Fix null safety bug", files="src/main.kt")
Output:
[main abc1234] Fix null safety bug
 1 file changed, 3 insertions(+), 1 deletion(-)
```

---

### `git_status`

Show compact git status output.

**Parameters**

- `path` (String, optional, default: ".") — File/directory filter

**Behavior**

- Runs `git status --short -- <path>`
- Path sanitized same as git_diff

**Example**

```
Input: git_status()
Output:
 M src/main.kt
?? newfile.txt
```

---

### `git_log`

Show recent commit history in one-line format.

**Parameters**

- `limit` (Integer, optional, default: 10, max: 100) — Number of commits to return

**Behavior**

- Runs `git log --oneline -n <limit>`

**Example**

```
Input: git_log(limit=5)
Output:
abc1234 Fix null safety bug
def5678 Add user authentication
ghi9012 Initial commit
```

---

## 6. Web Tools

### `web_search`

Search the web using DuckDuckGo instant answers and related results.

**Parameters**

- `query` (String, required) — Search query
- `maxResults` (Integer, optional, default: 5, max: 20) — Maximum results to return

**Behavior**

- Calls DuckDuckGo Instant Answer API (`https://api.duckduckgo.com/?q=<query>&format=json&no_html=1`)
- Returns the Abstract answer (if available) plus Related Topics and Results
- Base URL is configurable via `duckDuckGoBaseUrl`

**Edge Cases**

- Empty query returns error
- No results returns `"No results found for query: ..."`
- Network failures propagate as `"Web search failed: ..."`

**Example**

```
Input: web_search(query="Kotlin sealed classes")
Output:
Kotlin Sealed Classes
https://kotlinlang.org/docs/sealed-classes.html
Sealed classes and interfaces provide controlled inheritance...
```

---

### `web_fetch`

Fetch a web page, strip HTML, and return readable text.

**Parameters**

- `url` (String, required) — URL to fetch
- `timeout` (Integer, optional, default: 10, max: 60) — Request timeout in seconds

**Behavior**

- HTTP GET with redirect following (up to 5 redirects per JDK defaults)
- Strips `<script>`, `<style>`, all HTML tags, and decodes HTML entities (`&nbsp;`, `&amp;`, etc.)
- Truncates at 5000 characters
- Returns `"(no text content)"` if body is empty after stripping

**Edge Cases**

- Non-200 status codes throw `IllegalStateException("HTTP <code>")`
- Empty URL returns error
- Connection timeout returns error

**Example**

```
Input: web_fetch(url="https://example.com")
Output:
Example Domain
This domain is for use in illustrative examples in documents...
```

---

## 7. Memory Tools

Stored in SQLite-backed persistent storage. Facts survive across sessions.

### `memory_save`

Save a fact to persistent memory.

**Parameters**

- `key` (String, required) — Unique key (e.g., `user_prefers_tabs`)
- `value` (String, required) — The fact content to store

**Notes**

- Under agent scoping, keys may be namespaced (e.g., `agent-id:key`) automatically
- Returns `"Saved: key"` on success

**Example**

```
Input: memory_save(key="project_build_tool", value="Gradle with Kotlin DSL")
Output: Saved: project_build_tool
```

---

### `memory_search`

Search persistent memory by key or value content.

**Parameters**

- `query` (String, required) — Search term

**Notes**

- Searches both keys and values (delegates to `MemoryStore.search`)
- Under agent scoping, results are filtered to the agent's namespace
- Each result shows key, value, and creation timestamp

**Example**

```
Input: memory_search(query="build tool")
Output:
Found 1 entries:
[project_build_tool]
Gradle with Kotlin DSL
(created: 2026-05-14T08:00:00)
```

---

## 8. Kanban Tools

Kanban task management backed by SQLite.

### `task_create`

Create a new kanban task.

**Parameters**

- `title` (String, required) — Task title
- `description` (String, optional) — Optional description
- `status` (String, optional, default: "todo") — One of: `todo`, `in_progress`, `blocked`, `done`
- `priority` (String, optional) — One of: `low`, `medium`, `high`, `critical`
- `labels` (String, optional) — Comma-separated labels

**Example**

```
Input: task_create(title="Add dark mode", status="todo", priority="high")
Output:
Task created:
id: abc-123
title: Add dark mode
status: todo
priority: high
createdAt: 2026-05-14T08:00:00
```

---

### `task_update`

Update an existing kanban task. Only provided fields are changed.

**Parameters**

- `id` (String, required) — Task ID to update
- `title` (String, optional) — New title
- `description` (String, optional) — New description
- `status` (String, optional) — New status
- `priority` (String, optional) — New priority
- `labels` (String, optional) — New comma-separated labels

**Example**

```
Input: task_update(id="abc-123", status="in_progress")
Output:
Task updated:
id: abc-123
title: Add dark mode
status: in_progress
...
```

---

### `task_list`

List all kanban tasks, optionally filtered by status.

**Parameters**

- `status` (String, optional) — Filter by status: `todo`, `in_progress`, `blocked`, `done`

**Example**

```
Input: task_list(status="todo")
Output:
id: def-456
title: Write tests
status: todo
priority: medium
```

---

### `task_delete`

Delete a kanban task by ID.

**Parameters**

- `id` (String, required) — Task ID to delete

**Example**

```
Input: task_delete(id="abc-123")
Output: Deleted task abc-123
```

---

## 9. Agent Tools

Tools for creating, listing, inspecting, updating, deleting, and running custom agents.

### `agent_create`

Create a new custom agent definition.

**Parameters**

- `id` (String, required) — Unique identifier (e.g., `security-reviewer`)
- `name` (String, required) — Human-readable name
- `description` (String, optional) — Short description
- `system_prompt` (String, required) — Full system prompt defining behavior and expertise
- `preferred_model` (String, required) — Primary model (e.g., `claude-sonnet-4`)
- `preferred_provider` (String, required) — Provider (e.g., `anthropic`, `openai`)
- `fallback_model` (String, optional) — Fallback model
- `temperature` (String, optional) — Temperature (0.0–2.0)
- `max_tokens` (Integer, optional) — Max output tokens
- `tool_policy` (String, optional) — `ALL`, `LISTED`, or `NONE`
- `tools_allowed` (String, optional) — Comma-separated tool names (when `tool_policy=LISTED`)
- `filesystem_access` (String, optional) — `read-write`, `read-only`, or `none`
- `shell_access` (String, optional) — `true` or `false`
- `network_access` (String, optional) — `true` or `false`
- `execute_commands` (String, optional) — `auto`, `ask_first`, or `never`
- `memory_scope` (String, optional) — `global`, `agent`, or `none`
- `tags` (String, optional) — Comma-separated tags

**Example**

```
Input: agent_create(id="code-helper", name="Code Helper",
                     preferred_model="gpt-4o", preferred_provider="openai",
                     system_prompt="You are a helpful coding assistant.")
Output: Created agent 'code-helper' (Code Helper)
```

---

### `agent_list`

List all custom agent definitions.

**Parameters**

- `tag` (String, optional) — Optional tag filter

**Example**

```
Input: agent_list()
Output:
Custom Agents (2):
✅ code-helper — Code Helper (openai/gpt-4o)
⛔ security-reviewer — Security Reviewer (anthropic/claude-sonnet-4)
```

---

### `agent_get`

Get full definition of a custom agent by ID.

**Parameters**

- `id` (String, required) — Agent ID to retrieve

---

### `agent_update`

Update an existing custom agent. Only provided fields are changed.

**Parameters**

- `id` (String, required) — Agent ID to update
- `name`, `description`, `system_prompt`, `preferred_model`, `preferred_provider` (all optional)
- `tool_policy`, `tools_allowed` (optional)
- `enabled` (String, optional) — `true` or `false`
- `filesystem_access`, `shell_access`, `memory_scope` (optional)

---

### `agent_delete`

Delete a custom agent definition.

**Parameters**

- `id` (String, required) — Agent ID to delete

---

### `agent_run`

Run a custom agent by ID with a specific goal.

**Parameters**

- `agent_id` (String, required) — Agent ID to run
- `goal` (String, required) — The goal or instruction

> **Note**: This tool requires the Golem API server running with `--api --api-key`. Use the `POST /api/agents/run` endpoint instead.

---

## 10. Skill Tools

Skills are reusable agent capabilities defined in YAML files in `~/.golem/skills/`.

### `skill_list`

List all installed skills.

**Parameters**

- `tag` (String, optional) — Filter by tag

**Example**

```
Input: skill_list()
Output:
Skills (2):
• code-review — Review code for bugs and style issues [review, code]
• doc-gen — Generate documentation from source [docs]
```

---

### `skill_run`

Run a skill by name with a goal. Creates a temporary agent configured per the skill definition.

**Parameters**

- `skill_name` (String, required) — Name of the skill (must match a `.yaml` filename)
- `goal` (String, required) — The goal to give the skill agent

---

## 11. Checkpoint Tools

Checkpoints save and restore agent conversation state. Backed by SQLite via `CheckpointManager`.

### `checkpoint_save`

Save the current agent conversation state.

**Parameters**

- `sessionId` (String, required) — Session identifier
- `turnNumber` (Integer, required) — Current turn number
- `conversationJson` (String, required) — JSON-serialized conversation

---

### `checkpoint_list`

List all available checkpoints with session IDs, turn numbers, and creation times.

**Parameters**

None.

---

### `checkpoint_resume`

Load the most recent checkpoint for a session.

**Parameters**

- `sessionId` (String, required) — Session to resume

**Returns**

Checkpoint metadata + `conversationJson` for agent resumption.

---

## 12. Scheduler Tools

Create, list, and remove cron-based scheduled jobs. Backed by SQLite.

### `scheduler_add`

Create a scheduled job that runs a goal on a cron expression.

**Parameters**

- `name` (String, required) — Human-friendly job name
- `cronExpression` (String, required) — Five-field cron expression (e.g., `0 9 * * 1` for Monday 9 AM)
- `goal` (String, required) — Goal to run when the job fires
- `enabled` (Boolean, optional, default: true) — Start enabled

**Example**

```
Input: scheduler_add(name="weekly report", cronExpression="0 9 * * 1",
                      goal="Generate weekly status report")
Output:
Scheduled job created:
id: job-123
name: weekly report
cron: 0 9 * * 1
enabled: true
nextRunAt: 2026-05-18T09:00:00
```

---

### `scheduler_list`

List all scheduled jobs with next and last run times.

**Parameters**

None.

---

### `scheduler_remove`

Remove a scheduled job by ID.

**Parameters**

- `id` (String, required) — Job ID to remove

---

## 13. JVM Project Tools

Intelligent tools for understanding, navigating, and analyzing JVM/Gradle projects. All tools require a JVM project index (SQLite-backed) and auto-refresh via file watcher.

### `jvm_project_overview`

Inspect the Gradle project: modules, source roots, plugins, Java/Kotlin versions.

**Parameters**

None.

**Example**

```
Input: jvm_project_overview()
Output:
Project: /home/user/project
Scanned at: 2026-05-14T08:00:00
Modules (3):
- :app (root)
  plugins: kotlin, application
  versions: Java 21, Kotlin 2.0
- :core (root)
  plugins: kotlin, java-library
- :data
  plugins: kotlin
```

---

### `jvm_symbol_search`

Search Kotlin and Java symbols by name, optionally filtered by kind and module.

**Parameters**

- `name` (String, required) — Symbol name or substring
- `kind` (String, optional) — One of: `CLASS`, `INTERFACE`, `OBJECT`, `FUN`, `VAL`, `VAR`, `ENUM`, `ANNOTATION`
- `module` (String, optional) — Gradle module name (e.g., `:golem-core`)

**Output format**

`:module KIND name file:line:col visibility`

**Example**

```
Input: jvm_symbol_search(name="User", kind="CLASS", module=":core")
Output:
:core CLASS User src/main/kotlin/User.kt:10:1 public
:core CLASS UserRepository src/main/kotlin/UserRepository.kt:5:1 public
```

---

### `jvm_file_outline`

Return symbols declared in a source file.

**Parameters**

- `path` (String, required) — Source file path (absolute or relative to project)

**Security**

Rejects paths outside the project root directory.

**Example**

```
Input: jvm_file_outline(path="src/main/kotlin/Main.kt")
Output:
:app FUN main src/main/kotlin/Main.kt:3:1 public
:app CLASS AppConfig src/main/kotlin/Main.kt:15:5 internal
```

---

### `jvm_context_pack`

Build a compact JVM project context summary, optionally focused by goal keywords.

**Parameters**

- `goal` (String, optional) — Keywords to focus symbol and dependency context

**Behavior**

- Extracts goal-relevant symbols and dependencies (keywords ≥3 chars)
- Results compressed with TokenJuice, capped at 2000 chars
- Shows modules, plugins, versions, source dirs, and matching symbols

---

### `jvm_dependency_trace`

Show module dependency graph. Supports both static (Gradle file parsing) and resolved (running `./gradlew dependencies`).

**Parameters**

- `module` (String, optional) — Filter to a specific module
- `dependency` (String, optional) — Trace a specific dependency
- `use_gradle_command` (Boolean, optional, default: false) — Use `./gradlew dependencies` for resolved tree

---

### `jvm_task_suggest`

Suggest Gradle commands for a module and change type.

**Parameters**

- `change_type` (String, required) — One of: `src`, `test`, `build`, `java`
- `module` (String, optional) — Gradle module (default: `":"` for root)

**Example**

```
Input: jvm_task_suggest(change_type="test", module=":core")
Output:
./gradlew :core:test
./gradlew :core:compileTestKotlin
```

---

### `jvm_change_impact`

Analyze current git changes and suggest impacted modules and verification commands.

**Parameters**

None.

**Output includes**

- Changed files with change type (MODIFIED, ADDED, DELETED)
- Changed symbols
- Impacted modules
- Compilation scope
- Verification commands (`./gradlew ...`)

---

### `jvm_failure_explain`

Parse Gradle console output and explain build/test failures.

**Parameters**

- `output` (String, required) — Gradle console output to analyze

**Output includes**

- Summary of failures
- Root causes (module, file, symbol, message)
- Suggested fix commands

---

### `jvm_verify_plan`

Suggest minimal verification commands for edited file paths.

**Parameters**

- `paths` (String, required) — Comma-separated edited file paths

**Output**

```
Compilation: ./gradlew :module:compileKotlin
Tests: ./gradlew :module:test --tests "*.SpecificTest"
Estimated duration: 45s
```

---

## 14. Project Insight Tools

Stored conventions and facts about specific projects, modules, or symbols. Backed by a SQLite store separate from the main index.

### `project_insight_save`

Save a project-specific convention or fact.

**Parameters**

- `module` (String, optional) — Gradle module name
- `symbol` (String, optional) — Symbol or file-specific target
- `key` (String, required) — Insight key
- `value` (String, required) — Insight value

**Example**

```
Input: project_insight_save(module=":core", key="naming_convention",
                              value="Use Repository suffix for data layer classes")
Output: Saved insight 'naming_convention'.
```

---

### `project_insight_search`

Search stored project conventions.

**Parameters**

- `module` (String, optional) — Filter by module
- `symbol` (String, optional) — Filter by symbol
- `key` (String, optional) — Search by key

---

### `project_insight_delete`

Delete a stored insight.

**Parameters**

- `module` (String, optional) — Module filter
- `symbol` (String, optional) — Symbol filter
- `key` (String, required) — Insight key to delete

---

## 15. Provenance Tools

Export, list, and inspect provenance bundles — full audit records of agent sessions including tool calls, test results, and timestamps.

### `provenance_export`

Export a session's provenance as JSON or HTML.

**Parameters**

- `sessionId` (String, required) — Checkpoint session ID
- `format` (String, required) — `json` or `html`

---

### `provenance_list`

List all sessions available for provenance export.

**Parameters**

None.

---

### `provenance_info`

Show a summary for a provenance bundle.

**Parameters**

- `bundleId` (String, required) — Bundle/session ID

**Output**

```
sessionId=abc-123
version=1
model=claude-sonnet-4
toolCalls=15
testResults=3
timestamps=12
```

---

## 16. Delivery Tools

Send notifications via Telegram and Email. Require configuration via `GolemConfig` or environment variables.

### `telegram_send`

Send a message via Telegram Bot API.

**Parameters**

- `chat_id` (String, required) — Chat ID (numeric or `@channelusername`)
- `text` (String, required) — Message text (max 4096 characters)

**Configuration**

- `TELEGRAM_BOT_TOKEN` env var or `GolemConfig.telegramBotToken`
- Messages sent with `parse_mode=Markdown`

**Error modes**

- 401 Unauthorized → check bot token
- 400 Bad Request → malformed message or chat ID
- Connection/timeout errors reported explicitly

---

### `email_send`

Send an email via SMTP.

**Parameters**

- `to` (String, required) — Recipient email address
- `subject` (String, required) — Subject line
- `body` (String, required) — Plain text body

**Configuration**

- `EMAIL_SMTP_HOST` / `emailSmtpHost` — SMTP server hostname
- `EMAIL_SMTP_PORT` / `emailSmtpPort` — SMTP port (default: 587)
- `EMAIL_USERNAME` / `emailUsername` — SMTP auth username
- `EMAIL_PASSWORD` / `emailPassword` — SMTP auth password
- `EMAIL_FROM` / `emailFrom` — From address

Uses STARTTLS on port 587 with 10s connect and 15s read timeouts.

---

## 17. Browser Tools

Browser automation via CDP (Chrome DevTools Protocol). All browser tools are gated by `config.browserEnabled`.

### `browser_navigate`

Navigate the browser to a URL and return the page title or current URL.

**Parameters**

- `url` (String, required) — URL to open in the current browser page

**Behavior**

- Creates a browser session on first call (lazy CDP connection)
- Waits for page readyState to reach `interactive` or `complete`
- Returns the page title (falls back to current URL)

**Edge Cases**

- Invalid URL format: connection error
- Connection refused: browser not running on configured host:port

**Example**

```
Input: browser_navigate(url="https://example.com")
Output: Example Domain
```

---

### `browser_click`

Click an element matched by a CSS selector on the current page.

**Parameters**

- `selector` (String, required) — CSS selector for the element to click

**Behavior**

- Uses `document.querySelector` to find the element, then calls `.click()`
- Waits for page readyState after click (navigation/load)
- Returns `"Clicked <selector>"` on success

**Edge Cases**

- Element not found: returns tool failure with `"Element not found for selector: ..."`
- Requires prior `browser_navigate` call

---

### `browser_type`

Focus an element matched by a CSS selector and type text into it.

**Parameters**

- `selector` (String, required) — CSS selector for the target input element
- `text` (String, required) — Text to type
- `clear` (Boolean, optional, default: true) — Whether to clear the field before typing

**Behavior**

- Focuses the element and dispatches `input`/`change` events
- When `clear=true`, sets `element.value = ""` and calls `.select()`
- Uses CDP `Input.insertText` for reliable character-by-character input
- Returns `"Typed N characters into <selector>"`

**Edge Cases**

- Element not found: tool failure
- Empty selector or text: validation error

---

### `browser_snapshot`

Capture the current page as text or HTML.

**Parameters**

- `full` (Boolean, optional, default: true) — `true` returns `document.body.innerText`; `false` returns truncated `document.body.outerHTML`

**Behavior**

- `full=true`: returns body innerText (readable page content)
- `full=false`: returns truncated outerHTML (capped at `browserSnapshotMaxLength`)
- Use `full=false` when you need to see interactive element structure

---

### `browser_screenshot`

Take a screenshot of the current page and write it to disk.

**Parameters**

- `outputPath` (String, optional) — Custom output path for the screenshot PNG

**Behavior**

- Captures via CDP `Page.captureScreenshot` with format `png`
- Validates PNG magic bytes before writing
- Default path: `<browserScreenshotDir>/browser-<timestamp>.png`
- Subject to `checkAllowed()` filesystem security

**Returns**

The absolute path to the saved screenshot.

---

### `browser_back`

Navigate back in browser history and return the current URL.

**Parameters**

None.

**Behavior**

- Calls `window.history.back()` and waits for page ready
- Returns `"Current URL: <url>"`

---

### `browser_scroll`

Scroll the current page in a direction.

**Parameters**

- `direction` (String, required) — One of: `up`, `down`, `left`, `right`

**Behavior**

- `down`/`right`: scrolls +600px
- `up`/`left`: scrolls -600px
- Returns current scroll position (e.g., `"Scrolled to x=0 y=600"`)

**Edge Cases**

- Invalid direction string: tool failure with `"Unsupported direction: ..."`

---

### `browser_get_url`

Return the current browser URL.

**Parameters**

None.

**Behavior**

- Returns `document.URL` as a string
- Requires prior `browser_navigate` call

---

## 18. Image Generation Tool

Generates images via a configured provider (OpenAI DALL-E 3). Gated by `config.imageGenEnabled`.

### `generate_image`

Generate an image from a prompt and save it to disk.

**Parameters**

- `prompt` (String, required) — Text prompt describing the image to generate
- `size` (String, optional, default: `config.imageGenDefaultSize`, `"1024x1024"`) — Image size: `1024x1024`, `1792x1024`, or `1024x1792`
- `outputPath` (String, optional) — Custom output path for the generated PNG

**Configuration**

- `imageGenApiKey` — Required for OpenAI provider
- `imageGenDefaultProvider` — Provider: `"openai"` (default)
- `imageGenDefaultSize` — Default size (default: `"1024x1024"`)
- `imageGenOutputDir` — Output directory (default: `~/.golem/images/`)

**Behavior**

- Default output path: `<imageGenOutputDir>/image_<timestamp>_<provider>.png`
- Creates parent directories automatically
- Writes raw PNG bytes from the provider response
- Returns the absolute output path on success

**Edge Cases**

- Missing or empty prompt: validation error
- Missing API key: provider creation fails, tool not registered
- Unsupported size: passed to provider which validates
- File write failure: returns error

**Example**

```
Input: generate_image(prompt="A serene mountain landscape at sunset")
Output: /home/user/.golem/images/image_20260514_123000_openai.png
```

---

## 19. Code Execution Tool

Execute short code snippets in a temporary sandbox directory. Gated by `config.codeExecEnabled`.

### `run_code`

Execute code in a temp directory with timeout and memory limits.

**Parameters**

- `language` (String, required) — Language: `python3`, `node`, `kotlin`, or `shell`
- `code` (String, required) — Source code to execute
- `timeout` (Integer, optional, default: `config.codeExecTimeoutSeconds`, max: `config.codeExecTimeoutSeconds`) — Execution timeout in seconds

**Configuration**

- `codeExecEnabled` — Enable/disable (default: `true`)
- `codeExecTimeoutSeconds` — Default/max timeout (default: 30)
- `codeExecMaxMemoryMb` — Memory limit (default: 100)
- `codeExecAllowedLanguages` — Allowed languages (default: `python3`, `node`, `kotlin`, `shell`)
- `codeExecTempRoot` — Custom temp root directory
- `codeExecCaptureMaxOutputBytes` — Max captured output (default: 50 KB)

**Behavior**

- Creates a temp directory per execution
- Creates a wrapper bash script with `ulimit -v` for memory restriction
- Launches process via `ProcessBuilder`, captures stdout/stderr asynchronously
- Output truncated at `captureMaxOutputBytes` (default 50KB)
- Temp directory deleted after execution (in `finally` block)
- Timeout: process `destroyForcibly()`, returns exit code `-1`

**Security**

- Limited to configured languages only
- Enforced `ulimit -v` memory limit
- Subject to `PermissionEnforcer.checkCodeExec` when agent-scoped
- NOT a hardened sandbox — no network isolation

**Output format**

```
Exit code: 0
Work dir: /tmp/golem-codeexec-xxx
Stdout:
Hello, world!
Stderr:
(no output)
```

**Edge Cases**

- Unsupported language: `"Unsupported language: ..."`
- Empty code: validation error
- Timeout: `"Code execution timed out after Ns"` + partial output

**Example**

```
Input: run_code(language="python3", code="print('Hello, world!')")
Output:
Exit code: 0
Work dir: /tmp/golem-codeexec-abc123
Stdout:
Hello, world!
Stderr:
(no output)
```

---

## 20. Clarify Tool

Ask the user a clarification question and pause the agent until an answer is provided. Gated by `config.clarifyEnabled`. Backed by SQLite via `ClarifyStore`.

### `clarify`

Ask the user a clarification question and pause agent execution.

**Parameters**

- `question` (String, required) — The clarification question
- `choices` (String, optional) — Optional JSON array of suggested answers (e.g., `'["Option A", "Option B"]'`)

**Behavior**

- Creates a `pending` record in the `ClarifyStore` with a UUID
- Returns `"__CLARIFY__:<uuid>"` — the agent loop detects this prefix and calls `ClarifyStore.awaitAnswer()`
- The agent loop blocks (polls) until the user provides an answer via the API
- The answer is appended to the conversation as a user message

**Output format**

On success: `__CLARIFY__:<clarification-id>`

**Edge Cases**

- No active session: `"clarify requires an active session id"`
- Empty question: validation error
- Invalid choices JSON: `"choices must be a JSON array string"`

**Example**

```
Input: clarify(question="Which approach should I use?",
                choices='["Refactor the whole module", "Fix the specific bug only", "Write integration tests first"]')
Output: __CLARIFY__:abc-1234-def-5678
```

---

## 21. Vision Tool

Load an image file and pass it to the LLM for visual analysis. Gated by `config.visionEnabled`.

### `analyze_image`

Load an image file and pass it to the LLM for visual analysis.

**Parameters**

- `path` (String, required) — Path to the image file (JPEG, PNG, WebP, or GIF)
- `question` (String, optional) — Optional question to ask about the image
- `detail` (String, optional, default: `"auto"`) — Detail level: `auto`, `low`, or `high`

**Behavior**

- Resolves and validates the path (subject to `checkAllowed()`)
- Detects MIME type from file extension (`.jpg`/`.jpeg` → `image/jpeg`, `.png` → `image/png`, `.webp` → `image/webp`, `.gif` → `image/gif`)
- Reads entire file into memory, checks size ≤ 20MB
- Returns `ToolResult.ok` with the text output AND `contentParts` containing the image as `ImagePart`
- When a `question` is provided, content includes a `TextPart(question)` before the `ImagePart`

**Edge Cases**

- Missing path: validation error
- File not found: `"Image file not found: ..."`
- Unreadable file: `"Image file is not readable: ..."`
- Unsupported format: `"Unsupported image format. Supported: JPEG, PNG, WebP, GIF"`
- Empty file: `"Image file is empty: ..."`
- File > 20MB: `"Image file too large: ...MB (max: 20MB)"`
- Path outside allowed dirs: `SecurityException`

**Example**

```
Input: analyze_image(path="/path/to/screenshot.png",
                      question="What are the key metrics shown?")
Output: Image loaded for analysis: /path/to/screenshot.png
```

---

## 22. TTS Tools

Text-to-speech via ElevenLabs (if API key configured) or Edge TTS.

### `tts_say`

Convert text to speech and save as an audio file.

**Parameters**

- `text` (String, required) — Text to synthesize
- `voice` (String, optional) — Voice identifier (provider-specific)
- `provider` (String, optional) — Override: `elevenlabs` or `edge`
- `output_path` (String, optional) — Custom output path (default: `~/.golem/audio/tts_<timestamp>_<provider>.mp3` or `.wav`)

**Behavior**

- Default provider: ElevenLabs if `elevenlabsApiKey` is set, otherwise Edge TTS
- ElevenLabs produces `.mp3`, Edge TTS produces `.wav`
- Returns file path and byte count on success

---

## Complete Tool List

- **read_file** (File) — path, offset, limit
- **write_file** (File) — path, content
- **search_files** (File) — pattern, path, file_glob
- **edit_file** (Edit) — path, oldText, newText, replaceAll
- **shell** (Shell) — command, workdir, timeout
- **git_diff** (Git) — path, cached, head
- **git_commit** (Git) — message, files
- **git_status** (Git) — path
- **git_log** (Git) — limit
- **web_search** (Web) — query, maxResults
- **web_fetch** (Web) — url, timeout
- **memory_save** (Memory) — key, value
- **memory_search** (Memory) — query
- **task_create** (Kanban) — title, description, status, priority, labels
- **task_update** (Kanban) — id, title, description, status, priority, labels
- **task_list** (Kanban) — status
- **task_delete** (Kanban) — id
- **agent_create** (Agent) — id, name, description, system_prompt, preferred_model, preferred_provider, ...
- **agent_list** (Agent) — tag
- **agent_get** (Agent) — id
- **agent_update** (Agent) — id, ...
- **agent_delete** (Agent) — id
- **agent_run** (Agent) — agent_id, goal
- **skill_list** (Skill) — tag
- **skill_run** (Skill) — skill_name, goal
- **checkpoint_save** (Checkpoint) — sessionId, turnNumber, conversationJson
- **checkpoint_list** (Checkpoint) — *(none)*
- **checkpoint_resume** (Checkpoint) — sessionId
- **scheduler_add** (Scheduler) — name, cronExpression, goal, enabled
- **scheduler_list** (Scheduler) — *(none)*
- **scheduler_remove** (Scheduler) — id
- **jvm_project_overview** (JVM) — *(none)*
- **jvm_symbol_search** (JVM) — name, kind, module
- **jvm_file_outline** (JVM) — path
- **jvm_context_pack** (JVM) — goal
- **jvm_dependency_trace** (JVM) — module, dependency, use_gradle_command
- **jvm_task_suggest** (JVM) — change_type, module
- **jvm_change_impact** (JVM) — *(none)*
- **jvm_failure_explain** (JVM) — output
- **jvm_verify_plan** (JVM) — paths
- **project_insight_save** (Insight) — module, symbol, key, value
- **project_insight_search** (Insight) — module, symbol, key
- **project_insight_delete** (Insight) — module, symbol, key
- **provenance_export** (Provenance) — sessionId, format
- **provenance_list** (Provenance) — *(none)*
- **provenance_info** (Provenance) — bundleId
- **telegram_send** (Delivery) — chat_id, text
- **email_send** (Delivery) — to, subject, body
- **tts_say** (TTS) — text, voice, provider, output_path
- **browser_navigate** (Browser) — url
- **browser_click** (Browser) — selector
- **browser_type** (Browser) — selector, text, clear
- **browser_snapshot** (Browser) — full
- **browser_screenshot** (Browser) — outputPath
- **browser_back** (Browser) — *(none)*
- **browser_scroll** (Browser) — direction
- **browser_get_url** (Browser) — *(none)*
- **generate_image** (ImageGen) — prompt, size, outputPath
- **run_code** (CodeExec) — language, code, timeout
- **clarify** (Clarify) — question, choices
- **analyze_image** (Vision) — path, question, detail
