# Workflows

Welcome to the Golem workflow system. Pick the document that matches what you need.

## Doc Status Overview

| Doc | Status | What it covers |
|-----|--------|----------------|
| `YAML_WORKFLOW_REFERENCE.md` | ✅ **Current** — Schema matches code | Complete schema, step types, template variables, done conditions, API, CLI |
| `WORKFLOWS.md` | ✅ **Current** — Engine docs match code | Existing Kotlin DSL engine: GolemState, observer, codec, step types, templates |
| `WORKFLOW_CONCEPTS.md` | ⚠️ **Vision** — Aspirational sections labeled | What workflows are, how users and agents use them, skill→workflow bridge, DoW/DoD |
| `YAML_WORKFLOW_SYSTEM.md` | ✅ **Current** — Architecture matches code, all features described are shipped | Architecture overview, compilation pipeline, done mechanism, error handling |
| `WORKFLOW_SYSTEM_DESIGN_IT.md` | 🔮 **Proposal** — Production-ready design (Italian) | Target architecture, persistence, execution pipeline, migration plan |

## If you're new

**[WORKFLOW_CONCEPTS.md](./WORKFLOW_CONCEPTS.md)** — What workflows are, how users and agents create them, the skill→workflow bridge, and the Definition of Work + Definition of Done vision.

## If you want the YAML schema

**[YAML_WORKFLOW_REFERENCE.md](./YAML_WORKFLOW_REFERENCE.md)** — Complete schema, step types with implementation status, template variables, done conditions, examples, API calls, error handling, and deferred features.

## If you want the architecture design

**[YAML_WORKFLOW_SYSTEM.md](./YAML_WORKFLOW_SYSTEM.md)** — Implementation status (all files exist), compilation pipeline, done condition mechanism, error handling strategy, and deferred items.

## If you want the existing Kotlin DSL engine

**[WORKFLOWS.md](./WORKFLOWS.md)** — The current TramAI-based Kotlin DAG engine: step types, GolemState, observer, checkpoint/resume, process engine comparison.

## If you want the Italian design document

**[WORKFLOW_SYSTEM_DESIGN_IT.md](./WORKFLOW_SYSTEM_DESIGN_IT.md)** — Production-ready workflow system design (Italian). Some "current state" items are now shipped (execution persistence, chat hooks, scheduler/kanban triggers).

## Quick Reference

```
┌─────────────────────────────────────────────────────────────┐
│                                                              │
│  User "I need a code review for each PR"                     │
│       │                                                      │
│       ▼                                                      │
│  User writes ~/.golem/workflows/code-review.yaml             │
│       │                                                      │
│       ▼                                                      │
│  YamlWorkflowParser → YamlWorkflowCompiler → DAG Engine      │
│       │                                                      │
│       ▼                                                      │
│  POST /api/workflows/run → QUEUED → RUNNING → COMPLETED     │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## What works today

- YAML-based workflow definitions (ai, parallel_agents, human_approval steps)
- Per-step done conditions (8 condition types)
- Template variables (`{{params.X}}`, `{{state.goal}}`, `{{step.X.output}}`)
- Export built-in templates to YAML (`golem workflow export <name>`)
- SQLite execution persistence with status tracking (QUEUED→RUNNING→COMPLETED/FAILED)
- Background dispatcher with semaphore concurrency
- Scheduler and Kanban workflow triggers
- Chat session integration
- API: list definitions, run, get execution, list by session/scheduler/kanban
- CLI: run, list, export workflows
- Agent tool: `workflow_run`

## What's in progress / deferred

*(Nothing — all MVP items implemented)*

See [docs/workflows/YAML_WORKFLOW_REFERENCE.md](./YAML_WORKFLOW_REFERENCE.md) for deferred features (runtime upload, visual editor, etc.).
