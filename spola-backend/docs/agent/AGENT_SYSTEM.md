# Agent System

Golem's custom agent system lets you define **named, isolated agent configurations** — each with its own system prompt, provider routing, tool permissions, memory scope, and budget. This separates *agent identity* (what it is and what it can do) from *model selection* (which LLM powers it).

---

## 1. Overview

A custom agent is an `AgentDefinition` data class with **25 fields** covering:

- **Identity** — id, name, description, version
- **Persona / System Prompt** — the full behavioral definition
- **Model Routing** — preferred + fallback provider/model pairs
- **Model Parameters** — temperature, maxTokens overrides
- **Tool Configuration** — ALL, LISTED, or NONE tool access
- **Permission Scope** — filesystem, shell, network levels
- **Autonomy** — execute mode (auto/ask_first/never), max turn override
- **Memory Scope** — global, agent-namespaced, or none
- **Output Format** — text, markdown, json, json_schema
- **Budget** — cost limit, timeout
- **Lifecycle** — enabled/disabled, tags, timestamps

Agents are persisted in **SQLite** (via `SqliteAgentStore`) and can also be imported/exported as **YAML files** in `~/.golem/agents/`.

---

## 2. AgentDefinition — All Fields

```kotlin
data class AgentDefinition(
    // ── Identity ──────────────────────────────────────
    val id: String,                          // unique identifier
    val name: String,                        // human-readable name
    val description: String = "",            // short description
    val version: Int = 1,                    // auto-incremented on update

    // ── Persona / System prompt ───────────────────────
    val systemPrompt: String,                // full behavioral definition

    // ── Model Routing ─────────────────────────────────
    val preferredModel: String,              // primary model (e.g. "claude-sonnet-4")
    val preferredProvider: String,           // provider (e.g. "anthropic")
    val fallbackModel: String? = null,       // fallback model (null = no fallback)
    val fallbackProvider: String? = null,    // fallback provider (null = same as preferred)

    // ── Model Parameters ─────────────────────────────
    val temperature: Double? = null,         // 0.0–2.0 override
    val maxTokens: Int? = null,              // max output tokens

    // ── Tool Configuration ──────────────────────────
    val toolPolicy: ToolPolicy = ToolPolicy.ALL,       // ALL | LISTED | NONE
    val toolsAllowed: List<String> = emptyList(),      // allowlist for LISTED

    // ── Permission Scope ─────────────────────────────
    val filesystemAccess: String = "read-write",       // read-write | read-only | none
    val shellAccess: Boolean = true,                    // shell commands allowed?
    val networkAccess: Boolean = true,                  // network tools allowed?

    // ── Autonomy ─────────────────────────────────────
    val executeCommands: String = "auto",               // auto | ask_first | never
    val maxTurnsOverride: Int? = null,                  // ReAct loop limit (default: 25)

    // ── Memory Scope ─────────────────────────────────
    val memoryScope: String = "global",                 // global | agent | none
    val memoryNamespace: String? = null,                // agent-specific namespace

    // ── Output Format ───────────────────────────────
    val responseFormat: String = "markdown",            // text | markdown | json | json_schema
    val outputSchema: String? = null,                   // JSON schema for structured output

    // ── Budget ──────────────────────────────────────
    val maxCostUsd: Double? = null,            // soft cost limit in USD
    val timeoutSeconds: Int? = null,           // total run timeout

    // ── Lifecycle ───────────────────────────────────
    val enabled: Boolean = true,
    val tags: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
)
```

---

## 3. Schema Validation

The `validateAgentDefinition()` function runs on every `create()` and `update()` call:

```kotlin
fun validateAgentDefinition(agent: AgentDefinition) {
    // Required fields
    require(agent.id.isNotBlank())      // id must not be blank
    require(agent.name.isNotBlank())    // name must not be blank
    require(agent.systemPrompt.isNotBlank())     // system prompt required
    require(agent.preferredModel.isNotBlank())   // model required

    // Enum values
    require(agent.filesystemAccess in listOf("read-write", "read-only", "none"))
    require(agent.executeCommands in listOf("auto", "ask_first", "never"))
    require(agent.memoryScope in listOf("global", "agent", "none"))

    // Logical consistency
    // shellAccess=false with executeCommands=auto is contradictory
    // memoryScope=none but memoryNamespace is set → error
    // toolPolicy=LISTED requires non-empty toolsAllowed
    // toolPolicy!=LISTED requires empty toolsAllowed
}
```

---

## 4. ToolPolicy — Tool Scoping

| Policy | Behavior | Use Case |
|--------|----------|----------|
| `ALL` | Agent sees the full global tool registry | General-purpose agents |
| `LISTED` | Agent can only use tools in `toolsAllowed` | Specialized agents (read-only review, Q&A) |
| `NONE` | No tool access — chat-only mode | Consultation agents |

```yaml
# YAML: read-only code reviewer
toolPolicy: LISTED
toolsAllowed:
  - read_file
  - search_files
  - memory_search
```

---

## 5. Provider Routing

Each agent specifies a **preferred** provider/model pair and an optional **fallback**. `AgentFactory.createFromAgentDefinition()` tries primary first; on failure, falls back.

### Built-in Providers (resolved by `ProviderStore`)

| Provider Name | Env Variable | Default Model | Default Base URL |
|---------------|--------------|---------------|------------------|
| `openai` | `OPENAI_API_KEY` | `gpt-4o` | `OPENAI_BASE_URL` (optional) |
| `anthropic` | `ANTHROPIC_API_KEY` | `claude-sonnet-4-20250514` | — |
| `openai-compat` | `OPENAI_COMPAT_API_KEY` or `OPENAI_API_KEY` | `gpt-4o` | `http://localhost:8090/v1` |
| `ollama` | (none — uses "ollama" as noop key) | `llama3` | `http://localhost:11434/v1` |
| `google` | `GOOGLE_API_KEY` | `gemini-2.5-pro` | `https://generativelanguage.googleapis.com/v1beta/openai` |

Custom providers can be injected as a `Map<String, ProviderConfig>` at `ProviderStore` creation time.

```yaml
# Agent with fallback
preferredModel: claude-sonnet-4-20250514
preferredProvider: anthropic
fallbackModel: gpt-4o
fallbackProvider: openai
```

### How Fallback Works

```kotlin
val (llmProvider, modelName) = try {
    ProviderResolver.resolveNamed(
        providerConfig = providerStore.get(effectiveProviderName),
        modelName = effectiveModel,
    )
} catch (e: Exception) {
    if (agentDef.fallbackModel != null) {
        ProviderResolver.resolveNamed(
            providerConfig = providerStore.get(
                agentDef.fallbackProvider ?: effectiveProviderName
            ),
            modelName = agentDef.fallbackModel,
        )
    } else {
        throw e  // no fallback configured, propagate original error
    }
}
```

### Custom Providers in config.yaml

```yaml
providers:
  my-groq:
    type: openai-compat
    baseUrl: https://api.groq.com/openai/v1
    apiKey: ${GROQ_API_KEY}
    models:
      - llama3-70b-8192
```

---

## 6. Permissions

Five fields govern what an agent can do at runtime.

### filesystemAccess

| Value | Effect |
|-------|--------|
| `read-write` | Full read/write access (default) |
| `read-only` | Write ops blocked by `PermissionEnforcer.checkFileAccess()` |
| `none` | All file operations blocked |

### shellAccess

When `false`, `PermissionEnforcer.checkShell()` blocks all shell commands. The shell tool is also filtered from the registry.

### networkAccess

When `false`, network commands are blocked in `PermissionEnforcer.checkShell()`:

```kotlin
private val NETWORK_COMMANDS = setOf(
    "curl", "wget", "nc", "ncat", "ssh", "scp",
    "rsync", "sftp", "telnet", "ftp", "socat", "nmap",
)
```

### executeCommands

| Value | Effect |
|-------|--------|
| `auto` | Agent executes commands automatically (default) |
| `ask_first` | Agent must ask for human approval |
| `never` | Command execution disabled entirely |

### Controlled Commands per Access Level

| Access Level | Blocked Destructive (`rm`, `dd`, `mkfs`, `chmod`, `mv`, etc.) | Blocked Write (`tee`, `touch`, `cp`, `install`) | Blocked Network |
|--------------|:---:|:---:|:---:|
| `read-write` | — | — | conditional on `networkAccess` |
| `read-only` | ✅ | ✅ | conditional on `networkAccess` |
| `none` | ✅ | ✅ | conditional on `networkAccess` |

---

## 7. PermissionEnforcer — Runtime Checks

`PermissionEnforcer` wraps an `AgentDefinition` and provides two check methods used during tool execution.

### checkShell(command, workdir?)

```kotlin
fun checkShell(command: String, workdir: String? = null) {
    // Extracts the executable name (handles ./prefix, trailing /)
    // shellAccess=false → blocks all
    // executeCommands=never → blocks all
    // filesystemAccess=read-only → blocks destructive + write commands
    // filesystemAccess=none → blocks destructive + write commands
    // networkAccess=false → blocks network commands
}
```

### checkFileAccess(path, writeMode)

```kotlin
fun checkFileAccess(path: String, writeMode: Boolean = false) {
    // Only checks on write operations
    // read-only → PermissionDeniedException
    // none → PermissionDeniedException
}
```

### 3-Layer Model

| Layer | What | Where |
|-------|------|-------|
| 1 | Registry filtering (tools the model can see) | `ToolRegistryFactory` |
| 2 | Runtime enforcement (blocks at execution) | `PermissionEnforcer` |
| 3 | OS-level sandbox (recommended for shell agents) | Docker, seccomp, read-only mounts |

> **Security Note:** This is NOT a real security boundary. When `shellAccess = true`, a determined agent can always escape filesystem and network restrictions through the shell. For true isolation, use Docker, seccomp, or read-only mounts at the OS level.

---

## 8. Agent Store — SQLite Persistence

`SqliteAgentStore` uses its own Exposed `Database` connection (isolated from other stores like memory, kanban, scheduler) and stores agents in the `agent_definitions` table.

### Table Schema

```
Table: agent_definitions
  id              VARCHAR(128)  UNIQUE INDEX (primary key)
  name            VARCHAR(256)
  description     TEXT          nullable
  definition_json TEXT          full AgentDefinition as JSON
  enabled         BOOLEAN       default true
  tags            TEXT          comma-separated, nullable
  created_at      VARCHAR(32)
  updated_at      VARCHAR(32)
```

The full `AgentDefinition` is serialized to JSON in `definition_json`. Column-based fields (`name`, `description`, `enabled`, `tags`, `created_at`, `updated_at`) stay in sync for querying.

### AgentStore Interface

```kotlin
interface AgentStore : AutoCloseable {
    /** Create a new agent. Throws if id already exists. */
    suspend fun create(agent: AgentDefinition): AgentDefinition

    /** Retrieve an agent by id. Returns null if not found. */
    suspend fun get(id: String): AgentDefinition?

    /** List all agents, optionally filtered by tag (LIKE %tag%). */
    suspend fun list(tag: String? = null): List<AgentDefinition>

    /** Update an existing agent. Returns null if not found. */
    suspend fun update(agent: AgentDefinition): AgentDefinition?

    /** Delete an agent by id. Returns true if deleted. */
    suspend fun delete(id: String): Boolean

    /** Count all stored agents. */
    suspend fun count(): Int
}
```

### Connection Setup

```kotlin
val store = SqliteAgentStore("/home/user/.golem/agents.db")
// Internally connects to jdbc:sqlite:<dbPath> with org.sqlite.JDBC driver
// Auto-creates the agent_definitions table via SchemaUtils.create()
```

---

## 9. YAML File Import/Export

`AgentLoader` handles YAML I/O from `~/.golem/agents/`. Each `.yaml` or `.yml` file becomes one agent. The filename stem is used as the agent id if not specified in YAML.

### Complete YAML Example

```yaml
# ~/.golem/agents/security-reviewer.yaml
id: security-reviewer
name: Security Reviewer
description: Audits code for security vulnerabilities
version: 1
systemPrompt: >
  You are a security code reviewer. Analyze code for:
  - SQL injection, XSS, CSRF vulnerabilities
  - Insecure authentication or authorization patterns
  - Hardcoded secrets or API keys
  - Command injection and path traversal
  Provide severity levels (CRITICAL, HIGH, MEDIUM, LOW)
  and specific remediation steps for each finding.

preferredModel: claude-sonnet-4-20250514
preferredProvider: anthropic
fallbackModel: gpt-4o
fallbackProvider: openai

temperature: 0.1
maxTurnsOverride: 10

toolPolicy: LISTED
toolsAllowed:
  - read_file
  - search_files
  - memory_search
  - memory_save

filesystemAccess: read-only
shellAccess: false
networkAccess: false
executeCommands: never

memoryScope: agent
memoryNamespace: security-reviewer
responseFormat: markdown

maxCostUsd: 0.50
timeoutSeconds: 300

enabled: true
tags:
  - security
  - audit
```

### AgentLoader Operations

```kotlin
// Load all YAML agents from ~/.golem/agents/ (or custom path)
val agents: List<AgentDefinition> = AgentLoader.loadFromDirectory()

// Load a single file
val agent: AgentDefinition? = AgentLoader.loadFromFile(path)

// Write an agent to YAML (creates dirs, writes ${agent.id}.yaml)
AgentLoader.writeToFile(directory, agent)

// Delete an agent's YAML file
AgentLoader.deleteFile(directory, "security-reviewer")

// Sync YAML files ↔ SQLite (adds new, updates existing, removes orphans)
val synced: List<AgentDefinition> = AgentLoader.sync(store)
```

### Sync Algorithm

```kotlin
AgentLoader.sync(store, directory)
// 1. Load all YAML files from directory
// 2. For each file agent:
//    - If id exists in store → store.update()
//    - If id is new → store.create()
// 3. Delete from store any ids not present in files
// 4. Return store.list()
```

---

## 10. Agent Tools — LLM-Accessible CRUD

`AgentTools.register()` registers 6 tools into the tool registry. This lets the AI (or user) manage agents at runtime.

### agent_create

Creates a fully-configured custom agent.

```
Parameters:
  id                String (required)  — unique identifier
  name              String (required)  — human-readable name
  system_prompt     String (required)  — full behavioral definition
  preferred_model   String (required)  — primary model
  preferred_provider String (required) — primary provider
  description       String (optional)
  fallback_model    String (optional)
  temperature       String (optional)  — "0.5", etc.
  max_tokens        Int (optional)
  tool_policy       String (optional)  — "ALL", "LISTED", "NONE"
  tools_allowed     String (optional)  — comma-separated
  filesystem_access String (optional)  — "read-write", "read-only", "none"
  shell_access      String (optional)  — "true" or "false"
  network_access    String (optional)  — "true" or "false"
  execute_commands  String (optional)  — "auto", "ask_first", "never"
  memory_scope      String (optional)  — "global", "agent", "none"
  tags              String (optional)  — comma-separated
```

**Example agent conversation:**

```
User: Create a security reviewer agent using Claude Sonnet with read-only access
Agent: → agent_create(
    id="security-reviewer",
    name="Security Reviewer",
    system_prompt="You are a security code reviewer...",
    preferred_model="claude-sonnet-4-20250514",
    preferred_provider="anthropic",
    filesystem_access="read-only",
    shell_access="false",
    memory_scope="agent"
  )
  ← Created agent 'security-reviewer' (Security Reviewer)
```

### agent_list

Lists all agents, optionally filtered by tag.

```
Parameters:
  tag (optional) — filter by tag (LIKE match)
```

### agent_get

Returns the full agent definition by id.

```
Parameters:
  id (required)
```

### agent_update

Partial update — only provided fields are changed.

```
Parameters:
  id (required) — plus any subset of: name, description, system_prompt,
  preferred_model, preferred_provider, tool_policy, tools_allowed,
  enabled, filesystem_access, shell_access, memory_scope
```

Version auto-increments (`version + 1`). `updatedAt` is refreshed. When `memory_scope` is updated to `"agent"`, `memoryNamespace` is set to the agent id if not already set.

### agent_delete

Removes an agent by id.

```
Parameters:
  id (required)
```

### agent_run

Runs a custom agent with a specific goal. **Note:** This tool returns a message directing the user to use the API server — it requires `POST /api/agents/run` on a running Golem API server (`golem --api --api-key <key>`).

```
Parameters:
  agent_id (required)
  goal (required)
```

---

## 11. CLI Commands

```
golem agent list                          # List all custom agents
golem agent show <id>                     # Show full agent definition
golem agent create                        # Create a new agent
  --id <id>                                # Unique identifier (required)
  --name <name>                            # Human-readable name (required)
  --system-prompt|-i <text>               # System prompt (required)
  --model <model>                         # Preferred model (required)
  --provider <provider>                   # Preferred provider (required)
  --fallback-model <model>                # Fallback model
  --temp <0.0-2.0>                       # Temperature override
  --fs <level>                            # Filesystem access (default: read-write)
  --shell <bool>                          # Shell access (default: true)
  --network <bool>                        # Network access (default: true)
  --exec <mode>                           # Execute mode (default: auto)
  --memory <scope>                        # Memory scope (default: global)
  --tags <tag1,tag2>                      # Comma-separated tags

golem agent update <id>                   # Update an agent (partial)
  --name, --desc, --system-prompt, --model, --provider, --fs, --enable

golem agent delete <id>                   # Delete an agent
golem agent run <id> <goal>               # Run an agent with a goal
```

### Example: Create security reviewer from CLI

```bash
golem agent create \
  --id security-reviewer \
  --name "Security Reviewer" \
  -i "You audit code for vulnerabilities..." \
  --model claude-sonnet-4-20250514 \
  --provider anthropic \
  --temp 0.1 \
  --fs read-only \
  --shell false \
  --network false \
  --exec never \
  --tags security,audit
```

### Example: Run a custom agent

```bash
golem agent run security-reviewer "Audit src/auth/LoginController.kt for vulnerabilities"
```

### What happens during `golem agent run`

1. `SqliteAgentStore.get(agentId)` loads the definition from `agents.db`
2. Checks `agentDef.enabled` — disabled agents are rejected
3. `GolemFactory.createFromAgentDefinition()` builds a full `GolemInstance`:
   - `ProviderStore` resolves provider credentials
   - `PermissionEnforcer` wraps the definition
   - Tool registry is filtered per `toolPolicy` and permissions
   - Agent's `systemPrompt` is used as persona
   - Memory store is set up (namespace-isolated if `memoryScope == "agent"`)
4. The ReAct loop runs with the agent's model, temperature, maxTurns, and constraints
5. On primary provider failure, fallback is attempted
6. Result is returned as text

---

## 12. API Endpoints

All endpoints require `Authorization: Bearer <api-key>` header.

### Agent CRUD (`/api/agents`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/agents` | List all agents (optional `?tag=security`) |
| `GET` | `/api/agents/{id}` | Get agent by id |
| `POST` | `/api/agents` | Create a new agent |
| `PUT` | `/api/agents/{id}` | Update an existing agent |
| `DELETE` | `/api/agents/{id}` | Delete an agent |
| `POST` | `/api/agents/run` | Run an agent |

### List agents

```bash
curl -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/agents

# Filtered by tag
curl -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/agents?tag=review
```

### Create an agent

```bash
curl -X POST http://127.0.0.1:8082/api/agents \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "security-reviewer",
    "name": "Security Reviewer",
    "description": "Audits code for vulnerabilities",
    "systemPrompt": "You are a security code reviewer...",
    "preferredModel": "claude-sonnet-4-20250514",
    "preferredProvider": "anthropic",
    "toolPolicy": "LISTED",
    "toolsAllowed": ["read_file", "search_files"],
    "filesystemAccess": "read-only",
    "shellAccess": false
  }'
```

### Run an agent

```bash
curl -X POST http://127.0.0.1:8082/api/agents/run \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "agentId": "security-reviewer",
    "goal": "Scan the authentication module for vulnerabilities"
  }'
```

Response:
```json
{
  "agentId": "security-reviewer",
  "result": "# Security Audit Report\n\n## Findings..."
}
```

### Session Agent Run (`/api/agent/run`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/agent/run` | Run the default agent with a goal |
| `POST` | `/api/agent/run/stream` | Streamed agent run via SSE |
| `GET` | `/api/agent/status` | Get agent status (model, provider, running) |

---

## 13. Complete Example: security-reviewer Agent

### YAML definition (`~/.golem/agents/security-reviewer.yaml`)

```yaml
id: security-reviewer
name: Security Reviewer
description: Audits code for security vulnerabilities
version: 1
systemPrompt: >
  You are a security code reviewer. Analyze code for:
  - SQL injection, XSS, CSRF vulnerabilities
  - Insecure authentication or authorization patterns
  - Hardcoded secrets or API keys
  - Command injection and path traversal
  Provide severity levels (CRITICAL, HIGH, MEDIUM, LOW)
  and specific remediation steps for each finding.

preferredModel: claude-sonnet-4-20250514
preferredProvider: anthropic
fallbackModel: gpt-4o
fallbackProvider: openai

temperature: 0.1
maxTurnsOverride: 10

toolPolicy: LISTED
toolsAllowed:
  - read_file
  - search_files
  - memory_search
  - memory_save

filesystemAccess: read-only
shellAccess: false
networkAccess: false
executeCommands: never

memoryScope: agent
memoryNamespace: security-reviewer
responseFormat: markdown

maxCostUsd: 0.50
timeoutSeconds: 300

enabled: true
tags:
  - security
  - audit
```

### Usage

```bash
# List available agents
golem agent list

# Show security reviewer details
golem agent show security-reviewer

# Run it
golem agent run security-reviewer "Scan the authentication module"

# Via API
curl -X POST http://localhost:8080/api/agents/run \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "agentId": "security-reviewer",
    "goal": "Scan the authentication module for vulnerabilities"
  }'
```

### Under the hood

1. `SqliteAgentStore.get("security-reviewer")` loads the definition
2. `ProviderStore` resolves `anthropic` provider credentials from `ANTHROPIC_API_KEY`
3. `PermissionEnforcer` wraps the definition for runtime checks
4. `ToolRegistryFactory` builds a filtered tool registry (only listed tools, no shell, read-only filesystem)
5. `AgentFactory.createFromAgentDefinition()` creates a `GolemInstance` with the agent's `systemPrompt` as its persona
6. The ReAct loop runs with the agent's model, temperature, maxTurns, and permission constraints
7. On failure of primary `anthropic/claude-sonnet-4`, it falls back to `openai/gpt-4o`

---

## 14. AgentRuntimeConfig & AgentPermissions

Two helper data classes bridge agent definitions to the runtime:

```kotlin
data class AgentPermissions(
    val filesystemAccess: String,
    val shellAccess: Boolean,
    val networkAccess: Boolean,
    val executeCommands: String,
)

data class AgentRuntimeConfig(
    val maxTurns: Int,
    val temperature: Double?,
    val maxTokens: Int?,
    val memoryNamespace: String?,
)
```

`AgentPermissions` is used for registry filtering. `AgentRuntimeConfig` carries model parameter overrides into the `GolemAgent` constructor.
