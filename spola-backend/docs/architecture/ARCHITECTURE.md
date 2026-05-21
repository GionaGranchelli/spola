# Spola Architecture

**Version:** 0.1.0 | **Modules:** `spola-backend-core` + `spola-backend-cli` | **LLM Layer:** TramAI

## Module Map

Spola is a multi-module Gradle project with a clean split:

### `spola-backend-core`

The engine. Contains everything that makes Spola work:

- **Agent Loop** — `SpolaAgent.kt`: the ReAct loop (think-act-observe cycle)
- **Configuration** — `SpolaConfig.kt`: data class with every knob (model, provider, paths, security)
- **Factory** — `SpolaFactory.kt`: thin orchestrator that delegates to 4 sub-factories
- **Tool System** — `Tool.kt` / `ToolRegistry.kt`: tools as data classes with execute lambdas
- **Runner** — `Runner.kt`: `runOneShot()` and `runRepl()` entry points
- **Chat** — `ChatMessage.kt`: system/user/assistant/tool message types
- **Memory** — SQLite-backed (`memory_save` / `memory_search` tools)
- **Persona** — PersonaLoader + PersonaStore (Persona Pocket: `~/.spola/people/*.md`)
- **Scheduler** — Cron-based job scheduler backed by SQLite
- **Checkpoint** — Conversation checkpoint/resume via SQLite
- **JVM Intelligence** — Project scanning, symbol search, dependency tracing
- **Process Engine** — Deterministic DAGs (compile → test → git_commit → human_approval)
- **Workflow** — TramAI orchestration integration
- **API Server** — Ktor-based REST API (port 8082)
- **MCP** — Both server (expose tools) and client (consume external MCP servers)
- **Plugin System** — `~/.spola/plugins/` JAR loader
- **Delivery** — Telegram / Email notification tools
- **TTS** — Text-to-speech (Edge, ElevenLabs)
- **Observability** — Prometheus metrics + OpenTelemetry tracing
- **Agent Definitions** — Custom agents with scoped permissions (filesystem, shell, network)

### `spola-backend-cli`

The CLI entry point. Thin — parses args via picocli, then delegates to `spola-backend-core`.

- **Main.kt** — `@Command` root with all global flags and subcommand registrations
- **Subcommands** — `agent`, `config`, `mcp`, `pairing`, `persona`, `process`, `project`, `remote`, `scheduler`, `skill`, `workflow`, `team`

## Data Flow

```
┌────────────┐    ┌──────────┐    ┌──────────┐    ┌──────────────────────┐
│ User/CLI   │───▶│  Runner  │───▶│  Factory │───▶│   SpolaAgent         │
│ (goal text)│    │one-shot  │    │wires all │    │   (ReAct Loop)       │
└────────────┘    │or REPL   │    │depts     │    └──────┬───────────────┘
                  └──────────┘    └──────────┘           │
                                                         ▼
                                              ┌─────────────────────┐
                                              │   ProviderResolver   │
                                              │  (TramAI ModelProv.) │
                                              └─────────┬───────────┘
                                                        │
                                                        ▼
                                              ┌─────────────────────┐
                                              │   LLM (OpenAI,      │
                                              │   Anthropic,        │
                                              │   Ollama, Google,   │
                                              │   openai-compat)    │
                                              └─────────────────────┘
```

### Detailed Agent Loop (ReAct)

```
messages = [system(persona), user(goal)]
for turn in 1..maxTurns:
    response = llm.complete(messages, tools=schemas)
    if response has text content only:
        return response.text          ← final answer
    if response has tool calls:
        for each tool_call:
            result = execute(tool_call)   ← retries once on failure
            messages += tool_result(result)
    checkpoint.save(sessionId, turn, messages)
throw MaxTurnsExceeded(maxTurns)
```

Key details from `SpolaAgent.kt` — line 70-117:

- **Observer pattern** — `AgentRunObserver` callbacks for `onStatus`, `onToken`, `onToolCall`, `onToolResult`, `onLlmCall`, `onLlmResult`, `onError`. The REPL's `ConsoleObserver` prints tool execution in real-time.
- **Retry** — Tools are retried once on failure (ADR-002).
- **Compression** — `TokenJuice` compresses large tool results automatically; a footer like `[TokenJuice: -123 chars]` is appended.
- **Checkpointing** — After each turn, the full conversation is saved to SQLite. Resume with `--session-id <id>`.

### Factory Wiring

`SpolaFactory` delegates to 4 focused factories:

1. **AgentFactory** — Creates `SpolaAgent` + `SpolaInstance`. Loads persona (from file, Persona Pocket, or auto-detected). Wraps observer with MetricsObserver + SpolaTracerObserver. Resolves the LLM provider.
2. **ProviderResolver** — Maps provider names (`openai`, `anthropic`, `openai-compat`, `ollama`, `google`) to TramAI `ModelProvider` instances. Reads API keys from environment, config, or `ProviderStore`.
3. **ToolRegistryFactory** — Builds the `ToolRegistry` with all default tools. Variants for: default agents, agent definitions (with permission scoping), API server, and MCP server.
4. **WorkflowFactory** — Creates and runs TramAI workflows with persistence, observers, and metrics.

## Key Design Decisions

### 1. ReAct Loop (Not Workflow DSL by Default)

ADR-002. The default mode is a simple turn-based ReAct loop, not a complex workflow DSL. A workflow engine exists for deterministic multi-step processes (code review, JVM refactoring) but the agent loop is intentionally simple:

- **25 max turns** (configurable)
- **No streaming** in MVP (tokens are collected and printed at the end)
- **Idempotent tools** — tools are retried once, so they should tolerate replay

### 2. Tools Are Functions (No Class Hierarchy)

ADR-003. Each tool is a data class:

```kotlin
data class Tool(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    val execute: suspend (Map<String, Any>) -> ToolResult,
)
```

No interfaces, no abstract classes, no plugin SPI. Tools are registered in a `Map<String, Tool>`. Schemas are auto-generated from metadata. Tool results are always flattened to strings.

### 3. Memory as Tools (No Hidden State)

Memory is explicit, not implicit context injection. Two tools are exposed to the agent:

- `memory_save(key, value)` — store a fact
- `memory_search(query)` — find relevant facts

The initial persona is augmented with stored memories at startup (auto-injected after "Context from Memory"), but the agent controls what to save and when. Backed by SQLite via Exposed ORM.

### 4. Persona as a File

The persona (system prompt) is loaded from a file — `AGENTS.md`, `CLAUDE.md`, or `~/.spola/people/*.md` with YAML frontmatter. No hardcoded persona in code. The Persona Pocket feature (`persona list/show/sync`) manages multiple named personas in a SQLite store.

### 5. TramAI as LLM Layer

Spola uses TramAI's `ModelProvider` interface and `ProviderRegistry` for LLM calls, but does NOT use TramAI's `@AiService` proxy or orchestration module. The ReAct loop is hand-rolled. This gives Spola full control over conversation state, tool execution, and checkpointing.

### 6. Deterministic Process Engine

Workflows are typed DAGs, not free-form prompts. AI fills variables within bounded, typed steps (e.g., `compile_project`, `git_commit`, `human_approval`). The Kotlin engine enforces the flow, not the LLM. This is the key differentiator from pure-AI agents.

## Tool Categories

- **Filesystem** — `read_file`, `write_file`, `search_files`, `edit_file`
- **Shell** — `shell` (argv mode)
- **Web** — `web_search` (DuckDuckGo), `web_fetch`
- **Memory** — `memory_save`, `memory_search`
- **JVM Intelligence** — `jvm_project_overview`, `jvm_symbol_search`, `jvm_file_outline`, `jvm_context_pack`, `jvm_dependency_trace`, `jvm_change_impact`, `jvm_failure_explain`
- **Orchestration** — `workflow_run`, `agent_run`, `agent_create/list/get/update/delete`
- **Process** — Process DAG execution (compile, test, git_commit, human_approval)
- **Scheduler** — `scheduler_add`, `scheduler_list`, `scheduler_remove`
- **MCP** — Client: consume external MCP tools; Server: expose Spola tools via MCP
- **Delivery** — `telegram_notify`, `email_send`
- **TTS** — Text-to-speech tools
- **Checkpoint** — `checkpoint_save`, `checkpoint_list`, `checkpoint_resume`

## Plugin System

JAR-based plugins in `~/.spola/plugins/`. Each plugin can register tools into the `ToolRegistry`. Plugins are loaded at startup by `PluginLoader` when `config.pluginsEnabled` is true.

## Security Model

- `--unsafe` flag disables path restrictions (agent can read/write any file)
- `--insecure` flag allows binding to `0.0.0.0` without API key
- Custom agents have per-tool permission scoping (filesystem: read-write/read-only/none, shell: on/off, network: on/off)
- API server requires `--api-key` unless `--insecure` is set
- TLS supported for API server (`--tls-cert` / `--tls-key`)
