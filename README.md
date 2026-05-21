# Spola — Autonomous Coding Agent for the JVM

[![Build](https://img.shields.io/badge/build-passing-brightgreen.svg)](#)
[![Version](https://img.shields.io/badge/version-0.1.0-blue.svg)](#)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-21+-orange.svg)](https://adoptium.net)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3-purple.svg)](https://kotlinlang.org)

> **Spola is a Kotlin/JVM autonomous coding agent platform** with a hand-rolled ReAct loop, deterministic process orchestration, tool-calling, memory, and multi-interface delivery via CLI, REST API, Web Dashboard, and MCP.

---

## ✨ Why Spola

Spola is designed to be **agentic, deterministic, and embeddable**:

- **Agent-first runtime** with a robust ReAct loop (think → act → observe).
- **Deterministic process engine** for typed DAG workflows (e.g., compile → test → git commit → human approval).
- **Multi-interface product surface**: CLI, REST, MCP, Web.
- **JVM-native + embeddable core** — integrate directly into existing Kotlin/Java systems.
- **Production-minded controls**: scoped permissions, checkpointing, observability, plugin support.

---

## 🧠 Core Capabilities

### Agent Runtime
- ReAct loop with tool execution and retry handling.
- Persona loading (`AGENTS.md`/`CLAUDE.md`/custom files).
- Checkpoint + resume for long-running workflows.
- Custom agent definitions (model/provider/tool/policy scoped).

### Tooling Surface
- Filesystem: read/write/search/edit
- Shell + Git workflows
- Web search/fetch
- Memory save/search
- Scheduler & Kanban task tools
- Notifications (Telegram/Email)
- TTS
- JVM project intelligence tools (symbol search, context packing, dependency tracing)

### Orchestration
- Parallel agents
- Conditional branching
- Human approval gates
- Durable workflow persistence

---

## 🧩 Architecture at a Glance

```text
User Interfaces
 ├─ CLI / REPL
 ├─ REST API
 ├─ Web Dashboard
 └─ MCP (stdio/SSE)

Spola Core (Kotlin/JVM)
 ├─ SpolaAgent (ReAct loop)
 ├─ Tool Registry
 ├─ Provider Resolver (OpenAI/Anthropic/Ollama/Google/openai-compat)
 ├─ Workflow Engine (deterministic DAGs)
 ├─ Memory / Sessions / Jobs / Kanban (SQLite-backed)
 ├─ MCP Server + MCP Client
 ├─ Plugin Loader
 └─ Observability (metrics + tracing)
```
🏗️ Repository Structure
```text
spola-backend/
├─ spola-backend-core/   # Agent engine, tools, memory, API, MCP, workflows
├─ spola-backend-cli/    # CLI entrypoint + subcommands
└─ docs/                 # Architecture, ADRs, specs, quickstart

spola-frontend/
├─ shared/               # KMP shared DTOs/state/network/db
├─ backend/              # Ktor service layer for client
└─ composeApp/           # Desktop + Android UI
```

## 🚀 Quickstart
1) Prerequisites

   Java 21

   Gradle wrapper (already in repo)

2) Build
```bash
./gradlew build
```

3) Run one-shot goal
```bash
./gradlew :spola-backend-cli:run --args="'summarize this repository architecture'"
```
4) Start REPL
```bash
./gradlew :spola-backend-cli:installDist
./spola-backend-cli/build/install/spola/bin/spola
```
5) Start API server
```bash
./spola-backend-cli/build/install/spola/bin/spola --api --api-key your-secret
```
6) Run MCP server
```bash
./spola-backend-cli/build/install/spola/bin/spola --mcp
```

## 🔌 Integration Patterns

Spola can plug into your existing systems through:

    Embedded JVM library inside existing Kotlin/Java applications

    REST API for service-to-service orchestration

    MCP Server/Client for tool federation with external MCP ecosystems

    Daemon mode for background scheduler/process automation

    Plugin JARs for custom in-house tools

## 🔒 Security & Governance

    Per-agent permission scoping:

        filesystem (read-write / read-only / none)

        shell (on/off)

        network (on/off)

    API key enforcement for API mode (unless explicitly insecure)

    TLS support for API endpoints

    Human approval gates available in deterministic workflows

## 📈 Observability

    Prometheus metrics endpoint

    OpenTelemetry tracing support

    Agent observer hooks for status, tool calls, LLM calls/results, errors

## 🗺️ Roadmap Ideas (Optional section)

    Richer web dashboard workflows

    Expanded team/multi-agent planning patterns

    Stronger enterprise policy bundles

    Additional first-party integrations

## 🤝 Contributing

    Fork + branch

    Make focused changes with tests

    Run build/tests

    Submit PR with architecture/behavior notes

## 📚 Docs

    docs/overview/QUICKSTART.md

    docs/overview/README.md

    docs/architecture/ARCHITECTURE.md

    docs/specs/

    docs/adr/

