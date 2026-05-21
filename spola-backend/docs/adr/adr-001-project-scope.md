# ADR-001: Spola — Autonomous Coding Agent on the JVM

- **Status:** accepted
- **Owner:** maintainer
- **Last updated:** 2026-05-11
- **Related specs:** SPEC-001 (Agent Loop), SPEC-002 (Tools), SPEC-003 (Memory), SPEC-004 (CLI)

## Context

Hermes Agent and Claude Code demonstrate the power of autonomous AI coding agents:
they read files, run shell commands, search codebases, and persist context across
sessions — all driven by an LLM-powered ReAct loop.

The TramAI library provides excellent JVM infrastructure for LLM interaction (providers,
tool calling, structured output) but deliberately avoids becoming an agent framework.
Building an agent requires composing these primitives into an autonomous loop.

## Decision

Create **Spola** — a standalone JVM-based autonomous coding agent — as a separate
project that consumes TramAI as a library dependency. Spola is not a TramAI module;
it is an independent application built on TramAI's stable API.

## Architecture

Spola follows a layered architecture:

```
┌──────────────────────────────────────┐
│          spola-backend-cli (REPL)            │  ← UI layer
├──────────────────────────────────────┤
│   spola-backend-core                         │
│  ├─ AgentLoop (ReAct)                │  ← Core loop
│  ├─ ToolRegistry                     │  ← Tool infrastructure
│  ├─ tools/ (File, Shell, Search)     │  ← Built-in tools
│  ├─ MemoryStore (SQLite)             │  ← Persistent memory
│  ├─ PersonaLoader (AGENTS.md)        │  ← Persona system
│  └─ SpolaConfig                      │  ← Configuration
├──────────────────────────────────────┤
│          TramAI (library)             │  ← LLM infrastructure
└──────────────────────────────────────┘
```

## Non-Goals

- Becoming a TramAI module (Spola is an application, not a library)
- Replacing Claude Code or Hermes (Spola is JVM-native for Spring/Kotlin teams)
- Distributed execution (future concern)

## Design Principles

1. **Tools are functions** — each tool is a typed function `(args) -> result`, not a class hierarchy
2. **Memory is explicit** — no hidden agent state; memory tools are first-class tools
3. **Persona is a file** — AGENTS.md/CLAUDE.md loaded at startup, injectable
4. **TramAI is the LLM layer** — Spola uses TramAI for providers and tool calling, not for orchestration
5. **Testability first** — every component has a test proving its behavior
