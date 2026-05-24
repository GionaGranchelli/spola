# Spola — JVM Autonomous Coding Agent

[![Build](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/nousresearch/spola)
[![Version](https://img.shields.io/badge/version-0.1.1-blue.svg)](https://github.com/nousresearch/spola)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-21%2B-orange.svg)](https://adoptium.net)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3-purple.svg)](https://kotlinlang.org)
[![Tests](https://img.shields.io/badge/build-passing-brightgreen.svg)](TESTING.md)

**Spola is a JVM autonomous coding agent powered by TramAI.** One agent loop, four interfaces — CLI, REST API, Web Dashboard, and MCP — all built on a modular Kotlin core with SQLite persistence, a rich plugin tool system, and first-class support for custom agent definitions and multi-agent orchestration.

---

## Screenshots

```
┌─────────────────────────────────────────────────────────────────────────┐
│  THREE WAYS TO INTERACT                                                 │
├────────────────────────┬─────────────────────────┬──────────────────────┤
│    CLI + REPL          │    Web Dashboard         │    REST API          │
│                        │                         │                      │
│  Spola v0.1.1          │  ┌──────────────────┐    │  curl localhost:8082 │
│  Type a goal...        │  │  💬 Chat  🧰 Tools│    │  POST /api/agent/run│
│                        │  │  🧠 Memory ⏰ Jobs │    │  SSE streaming     │
│  > review this PR      │  │  📊 Metrics       │    │  40+ endpoints     │
│                        │  │                   │    │                     │
│  🔧 read_file ✓        │  │  [Dark/Light 🌙] │    │  spola --api        │
│  🔧 shell ✓            │  │  Ctrl+K Palette   │    │  :8082              │
│  🔧 git_commit ✓       │  └──────────────────┘    │                     │
│                        │                         │                      │
│  $ spola "write test"  │  $ spola --api          │                     │
│  $ spola               │  → :8082/web/           │                     │
└────────────────────────┴─────────────────────────┴──────────────────────┘
```

---

## Features

### Interface

- **Polished CLI + REPL** — ANSI-colored output, real-time tool call timeline with `✓`/`✗` status, animated spinner, `/tools`, `/memory`, `/model`, `/provider` commands
- **REST API** — Ktor server on `:8082` with 40+ endpoints, SSE streaming for agent runs, SQLite session persistence, bearer auth
- **Web Dashboard** — Single-page app served at `:8082/web/`, dark/light theme toggle, Ctrl+K command palette, real-time health polling, chat with tool timeline, tools/memory/scheduler/metrics tabs
- **Four Interfaces** — CLI (picocli), REST API (Ktor), MCP (stdio/SSE), Web Dashboard

### Agent

- **ReAct Loop** — Proven hand-rolled agent loop based on the ReAct pattern: LLM thinks, calls tools, observes results, produces final answer
- **Custom Agent Definitions** — Create named agents with specific: provider + model (with fallback), system prompt / persona, tool allowlist, permission scoping (filesystem `read-write`/`read-only`/`none`, shell on/off, network on/off), memory scope (`global`/`agent`/`none`), response format (`text`/`markdown`/`json`), max cost and timeout budgets
- **Persona System** — Load system persona from `AGENTS.md`, `CLAUDE.md`, or explicit path; injects as the ReAct loop system message
- **Checkpointing + Resume** — Automatic per-turn checkpointing to SQLite; `agent_checkpoint` and `agent_resume` tools; durable workflow persistence via `TramAI WorkflowPersistence`
- **Token Compression** — TokenJuice engine compresses conversation history to stay within context windows

### Tools

| Tool | Description |
|------|-------------|
| `read_file` | Read files with line numbers, offset, and limit |
| `write_file` | Write/overwrite files with automatic directory creation |
| `search_files` | Regex content search across files with glob filtering |
| `edit_file` | Targeted find-and-replace edits (fuzzy match, unique-line safety) |
| `shell` | Execute shell commands with timeout, security enforcement |
| `git_diff` / `git_commit` / `git_status` / `git_log` | Full git workflow inside the agent loop |
| `web_search` / `web_fetch` | DuckDuckGo web search and HTML page fetching |
| `memory_save` / `memory_search` | Persistent SQLite key-value memory across sessions |
| `task_create` / `task_update` / `task_list` / `task_delete` | Kanban task board management |
| `scheduler_add` / `scheduler_list` / `scheduler_remove` | Cron-based job scheduling |
| `telegram_send` / `email_send` | Multi-channel delivery (Telegram Bot API, SMTP email) |
| `tts_say` | Text-to-speech via ElevenLabs or Edge TTS |
| `agent_create` / `agent_list` / `agent_get` / `agent_update` / `agent_delete` | In-agent custom agent CRUD |

### Orchestration (TramAI Workflows)

- **Parallel Agents** — Run N agents concurrently on the same goal, merge results
- **Branching** — Conditional step execution based on tool call patterns
- **Gates** — Human-in-the-loop approval checkpoints
- **Plugin Steps** — Custom step executors via `SpolaPlugin` interface
- **Durable Execution** — `WorkflowPersistence` with file-based checkpointing, lease management, and delay-wakeup scheduling

### MCP (Model Context Protocol)

- **MCP Server** — Expose all Spola tools as MCP tools via stdio (`:8091`) or SSE (`--mcp-port 8091 --mcp-transport sse`); tools-only mode requires no LLM API key
- **MCP Client** — Consume external MCP servers: connects via stdio, auto-discovers tools, registers them in the agent's tool registry with `mcp_{server}_{tool}` namespace; config persisted at `~/.spola/mcp-servers.json`

### Deployment

- **Docker** — Multi-stage build: Gradle 8.13 + JDK 21 → distroless JRE; exposes `:8082` and `:8091`; non-root user
- **CLI Modes** — `--api` (REST server), `--mcp` (MCP server), `--daemon` (scheduler daemon), one-shot, REPL
- **Observability** — OpenTelemetry tracing (configurable endpoint), Prometheus metrics (`/api/metrics`), agent run observer chain

---

## Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                        USER FACING                                 │
│  ┌─────────┐  ┌──────────────┐  ┌──────────┐  ┌─────────────┐    │
│  │  CLI    │  │  Web SPA     │  │  REST    │  │ MCP Client  │    │
│  │ picocli │  │  :8082/web/  │  │  :8082   │  │ (stdio/SSE) │    │
│  └────┬────┘  └──────┬───────┘  └────┬─────┘  └──────┬──────┘    │
│       │              │               │               │           │
├───────┴──────────────┴───────────────┴───────────────┴───────────┤
│                    SPOLA BACKEND CORE                            │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │              SpolaAgent (ReAct Loop)                      │    │
│  │  ┌──────────┐  ┌──────────┐  ┌───────────┐              │    │
│  │  │ TramAI   │  │ Tool     │  │ Observer  │              │    │
│  │  │ Provider │  │ Registry │  │ Chain     │              │    │
│  │  └──────────┘  └──────────┘  └───────────┘              │    │
│  └──────────────────────────────────────────────────────────┘    │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │              TramAI Orchestration Layer                   │    │
│  │  ParallelStep │ BranchStep │ GateStep │ Checkpointing    │    │
│  └──────────────────────────────────────────────────────────┘    │
│                                                                   │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐   │
│  │ SQLite   │  │ SQLite   │  │ SQLite   │  │ SQLite        │   │
│  │ Memory   │  │ Jobs     │  │ Kanban   │  │ Sessions      │   │
│  │ Store    │  │ Store    │  │ Store    │  │ Store         │   │
│  └──────────┘  └──────────┘  └──────────┘  └───────────────┘   │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │              MCP (Server + Client)                        │    │
│  │  ┌──────────┐  ┌────────────┐  ┌──────────────────┐     │    │
│  │  │ Stdio    │  │ SSE        │  │ External MCP     │     │    │
│  │  │ Transport│  │ Transport  │  │ Server Connection│     │    │
│  │  └──────────┘  └────────────┘  └──────────────────┘     │    │
│  └──────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────────┘
```

---

## Quick Links

| Resource | Description |
|----------|-------------|
| [QUICKSTART.md](QUICKSTART.md) | Get up and running in 5 minutes |
| [AGENTS.md](../../AGENTS.md) | The Spola persona / system prompt |
| [TESTING.md](../../TESTING.md) | Test suite overview |
| [ROADMAP.md](../../ROADMAP.md) | Development roadmap |
| [docs/specs/](../../docs/specs/) | Technical specifications |
| [docs/adr/](../../docs/adr/) | Architecture Decision Records |
| [docs/board/board.md](../../docs/board/board.md) | Project board with feature status |

### Architecture Documentation

- [ADR-002: ReAct Loop Design](../../docs/adr/adr-002-react-loop.md)
- [ADR-003: Tool System](../../docs/adr/adr-003-tool-system.md)
- [ADR-004: Memory System](../../docs/adr/adr-004-memory-system.md)
- [SPEC-001: Agent Loop Spec](../../docs/specs/spec-001-agent-loop.md)
- [SPEC-002: Built-in Tools](../../docs/specs/spec-002-tools.md)
- [SPEC-003: Persona System](../../docs/specs/spec-003-persona.md)
- [SPEC-004: CLI Interface](../../docs/specs/spec-004-cli.md)
