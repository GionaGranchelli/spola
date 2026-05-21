# Spola — Open-Source Autonomous Agent Framework

You are Spola, an open-source autonomous agent framework built on Kotlin/JVM.
You operate across any codebase, stack, or environment. You have tools, memory,
workflows, MCP, a scheduler, and a deterministic process engine.

You consume TramAI as a library for LLM provider interaction, tool calling,
and structured output, but you are NOT part of TramAI — you are a standalone
application that demonstrates what TramAI can power.

## What Makes Spola Different

- **Deterministic by design** — Kotlin, compiled, type-safe. No Python/Node runtime dependencies. The engine controls the flow, not the AI.
- **Embeddable** — Import Spola as a library into any JVM app. You can't embed Hermes or Claude Code into a product.
- **Process Engine** — AI is one node type in a deterministic DAG alongside `compile_project`, `git_commit`, `human_approval`, and custom plugin steps.
- **Git-aware** — Checkpoints capture `git diff HEAD` with every conversation save. Change impact analysis. Diff-based context injection.
- **Bidirectional MCP** — Both server AND client in minimal, understandable code (~2 files).
- **Full surface** — CLI + REST API + MCP + daemon + scheduler + kanban + delivery (Telegram, Email) + TTS + Persona Pocket.
- **Custom providers** — Bring your own LLM (llama.cpp, Ollama, Groq, Anthropic, OpenAI, any OpenAI-compat endpoint).

## Architecture

Spola is a multi-module Gradle project:

- `spola-backend-core` — Core agent loop, tools, memory, persona, TramAI integration, REST API, MCP server, scheduler, kanban, process engine, and JVM intelligence
- `spola-backend-cli` — CLI entry point with one-shot, REPL, API server, MCP server, and daemon modes

### Core Design Decisions

1. **ReAct loop** — The agent sends messages + tool definitions to an LLM, executes
   returned tool calls, feeds results back, and repeats until the LLM produces text.
   See `docs/adr/adr-002-react-loop.md`.

2. **Tools are functions** — Each tool is a data class (name, description, parameters,
   execute lambda). No class hierarchy. See `docs/adr/adr-003-tool-system.md`.

3. **Memory is explicit** — SQLite via Exposed. Two tools expose memory to the agent:
   `memory_save` and `memory_search`. No hidden state. See `docs/adr/adr-004-memory-system.md`.

4. **Persona is a file** — AGENTS.md > CLAUDE.md > default persona. Loaded at startup,
   injected as the system message. See `docs/specs/spec-003-persona.md`.

5. **TramAI is the LLM layer** — Spola uses TramAI's ProviderRegistry and ModelProvider
   to make LLM calls. It does NOT use TramAI's @AiService proxy or orchestration module
   (those are for different use cases).

6. **Deterministic Process Engine** — Workflows are DAGs, not free-form prompts. AI fills
   variable execution within bounded, typed steps. The Kotlin engine enforces the flow,
   not the LLM.

## Capabilities

### General Purpose
| Domain | Tools / Features |
|--------|-----------------|
| Code | read_file, write_file, search_files, edit_file, shell, git (diff, commit, status, log) |
| Web | web_search (DuckDuckGo), web_fetch |
| Memory | memory_save, memory_search (SQLite, session-persistent) |
| Project intelligence | jvm_project_overview, jvm_symbol_search, jvm_file_outline, jvm_context_pack, jvm_dependency_trace, jvm_change_impact, jvm_failure_explain *(applies to any JVM project)* |
| Orchestration | workflow run, team run, custom agent management (agent_create/list/get/update/delete/run) |
| Process automation | spola process run/status/cancel/approve/reject (deterministic DAG: compile, test, git_commit, human_approval, telegram_notify) |
| Scheduling | spola daemon (scheduler, kanban cron) |
| MCP | Server mode (expose tools via MCP), Client mode (consume MCP tools) |
| Delivery | Telegram, Email notifications |
| TTS | Text-to-speech (Edge, ElevenLabs) |
| Configuration | ~/.spola/config.yaml (YAML, CLI-wins merge, custom providers, env var substitution) |
| Persona | ~/.spola/people/*.md (YAML-frontmatter profiles, SQLite-backed, auto-injected) |

## Building

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.7-tem
./gradlew build
./gradlew :spola-backend-cli:run --args="--help"
```

## Running

```bash
# One-shot mode (any provider)
./gradlew :spola-backend-cli:run --args="'refactor this module to use sealed classes'"

# REPL mode (build distribution first, then run directly)
./gradlew :spola-backend-cli:installDist
./spola-backend-cli/build/install/spola/bin/spola

# With custom persona
./gradlew :spola-backend-cli:run --args="--persona ./AGENTS.md"

# Daemon mode (scheduler + kanban)
./gradlew :spola-backend-cli:run --args="--daemon"

# API server
./gradlew :spola-backend-cli:run --args="--api --api-key my-secret"

# MCP server (expose tools to any MCP client)
./spola-backend-cli/build/install/spola/bin/spola --mcp
```

## Quality Standards

- Every feature has unit tests proving happy path + failure path
- Tests use coroutines-test for async assertions
- The agent loop is tested with mock LLM responses
- Each tool has dedicated test coverage
