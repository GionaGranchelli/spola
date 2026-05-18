# Workflow Concepts

> **From ad-hoc agent conversations to repeatable, deterministic pipelines.**
> How users, agents, skills, and workflows fit together.
>
> **Status:** ⚠️ Product vision doc. Sections labeled "(Proposed)" describe future capabilities that are not yet implemented.
> See `YAML_WORKFLOW_REFERENCE.md` for the current schema contract.

## The Core Idea

A **workflow** is a definition of work + definition of done that turns a repetitive task into a **one-click execution**.

**Before workflows:** You tell the agent what to do each time. The agent decides the steps, the order, the tools. Every run is different.

**After workflows:** You write a YAML definition (or export from a built-in template). Every run follows the same steps, checks the same criteria, produces consistent results.

```
┌─────────────────────────────────────────────────────┐
│  User: "Review this project for security issues"    │
│                                                      │
│  Agent thinks:                                       │
│    "The user wants a security review.                │
│     I'll create a workflow definition...             │
│     ...and run it on the target project."            │
│                                                      │
│  Result: ~/.golem/workflows/security-scan.yaml       │
│  Execution: POST /api/workflows/run                 │
└─────────────────────────────────────────────────────┘
```

## The Three Roles

### 1. The User

The user **describes what they want** in natural language:

> "Every time I start a new Kotlin project, I want to check the build config, verify dependencies, scan for secrets, and generate a report. If any critical issue is found, stop and tell me."

The user does NOT need to:
- Know what a DAG is
- Write Kotlin code
- Rebuild Golem
- Understand checkpoint/resume mechanics

### 2. The Agent

The agent **runs workflow definitions, created in one of two ways**:

**Option A — Write YAML manually** and place it in `~/.golem/workflows/`:
```yaml
# ~/.golem/workflows/my-review.yaml
name: my-review
steps:
  - id: security-scan
    type: ai
    goal: "Scan {{params.target}} for secrets"
```

**Option B — Export a built-in template** and customize it:
```bash
golem workflow export code-review -o ~/.golem/workflows/my-review.yaml
```

Once registered, the agent runs the workflow:
```
User says:  "Review my Kotlin project"
                │
                ▼
Agent calls: workflow_run tool
                │
                ▼
Engine: POST /api/workflows/run {workflowName, goal, inputJson}
```

> **Note:** The agent has a `workflow_run` tool but no `workflow_create` tool yet. Creating new workflows from natural language is a planned feature.

### 3. The Workflow Engine

The engine **executes the YAML deterministically**:

```
my-review.yaml
    │
    ▼
YamlWorkflowParser → YamlWorkflowCompiler → Workflow<GolemState>
    │                      │                        │
    parse           resolve {{param}}         call golemAgentStep()
    validate        build DAG                 call gateStep()
    schema check    attach done conditions    same engine as Kotlin
```

The engine does NOT know the workflow came from YAML. It runs identically.

## The Skill → Workflow Bridge

This is the key connection the user described:

**Skills** are structured prompt knowledge (persona, tools, instructions).
**Workflows** are structured execution (steps, order, completion criteria).

Together they form:

```
SKILL.md                              WORKFLOW.yaml
┌──────────────────────┐             ┌──────────────────────┐
│ persona: "Kotlin     │             │ steps:               │
│   security expert"   │      ──►    │   - type: ai         │
│ tools: [jvm_scan,    │   skill     │     persona: {{skill}}│
│   jvm_symbol_search] │   ref       │     goal: "scan..."  │
│ instructions: ...    │             │   - type: ai         │
└──────────────────────┘             │     goal: "fix..."   │
                                     │ done:                │
                                     │   - no CRITICAL      │
                                     └──────────────────────┘
```

A skill can **reference** a workflow (`workflow: code-review`), and a workflow can **reference** a skill (`skill: kotlin-security`). The agent uses both to reason about what to do and how to do it.

## The Lifecycle of a Workflow

```
CREATE ──────────────────────────────────────────────────────
│ User: "I need a code-review workflow for my team"
│ Agent: Generates ~/.golem/workflows/code-review.yaml
│        Registers it in the WorkflowTemplateRegistry
│        Becomes available via GET /api/workflows
│
USE ─────────────────────────────────────────────────────────
│ User: "Review my project at ~/Development/openclaw-app"
│ Agent: calls workflow_run tool
│        {"workflowName":"code-review", "goal":"Review my project", "inputJson":"{\"target\":\"...\"}"}
│
│ Engine: YamlWorkflowCompiler.compile() → DAG
│         AsyncWorkflowDispatcher picks it up
│         Executes steps with checkpoint/resume
│         Returns result or fails with error
│
ITERATE ─────────────────────────────────────────────────────
│ User: "Add a dependency check step"
│ Agent: Reads ~/.golem/workflows/code-review.yaml
│        Adds new step
│        Workflow updated — no rebuild needed
│
SHARE ───────────────────────────────────────────────────────
│ User shares ~/.golem/workflows/code-review.yaml
│ Another Golem instance copies it → it just works
│ Same workflow, different projects
```

## Definition of Work + Definition of Done

### Definition of Work (What to do)

```yaml
steps:
  - id: security-scan
    type: ai
    persona: "Kotlin security specialist"
    goal: "Scan {{params.target}} for hardcoded secrets, injection vulnerabilities, and insecure dependencies"
    
  - id: dependency-audit
    type: ai
    persona: "Dependency manager"
    goal: "Check build.gradle.kts in {{params.target}} for outdated or vulnerable dependencies"
    depends_on: [security-scan]    # could run in parallel, but explicit
```

### Definition of Done (When to stop)

```yaml
steps:
  - id: security-scan
    type: ai
    ...
    done:                           # <-- per-step completion criteria
      - condition: output_has_content   # step result is non-blank
      - condition: output_contains "CRITICAL|HIGH"   # output matches regex

done:                               # <-- global completion criteria (evaluated after all steps)
  - condition: all_steps_passed
  - condition: report_generated
```

**Supported condition types:** See `YAML_WORKFLOW_REFERENCE.md` for the full list. Each condition is a name + optional regex value. Unknown conditions pass silently (logged as warning).

## Project Portability

The same workflow runs on any project via parameters:

```yaml
params:
  target:
    type: string
    required: true
    description: "Path to the project root"
  files:
    type: string
    default: "**/*.kt"
    description: "File pattern to scan"
```

The agent resolves `{{params.target}}` at runtime:

```
POST /api/workflows/run
{"workflowName":"code-review", "goal":"Review openclaw-app", "inputJson":"{\"target\":\"~/Development/openclaw-app\"}"}
```

Same workflow definition → different target every time.

## What This Enables

| Scenario | Before | After |
|----------|--------|-------|
| New project type | Agent guesses steps, might miss things | Workflow ensures consistency |
| Team onboarding | Each dev describes process differently | Shared YAML = shared process |
| Compliance | Manual checklist, easy to skip | Blocking done conditions enforce rules |
| CI/CD integration | Custom scripts per project | Same workflow, different params |
| Agent skills | Skill only knows persona | Skill references workflow + knows execution |
| Debugging | "What did the agent do last time?" | Checkpointed execution with full trace |

## Summary

```
┌─────────────────────────────────────────────────────────┐
│                                                          │
│  ┌──────────┐   describes    ┌──────────┐   creates    ┌┴──────────────┐
│  │  User    │ ───────────────►│  Agent   │ ────────────►│  Workflow     │
│  │          │                 │          │              │  (YAML file)  │
│  └──────────┘                 └──────────┘              └───────────────┘
│       │                           │                            │
│       │ "do the thing"            │ "I have a workflow         │
│       │──────────────────────────►│  for this"                 │
│       │                           │    │                       │
│       │                           │    ▼                       │
│       │                           │  POST /api/workflows/run   │
│       │                           │    │                       │
│       │                           │    ▼                       │
│       │                           │  ┌──────────────────┐      │
│       │                           │  │  DAG Engine      │      │
│       │                           │  │  (checkpoint,    │      │
│       │                           │  │   parallel,      │      │
│       │                           │  │   resume)        │      │
│       │                           │  └──────────────────┘      │
│       │                           │    │                       │
│       │◄───────────────────────────────┘                       │
│       │  result / error                                        │
└─────────────────────────────────────────────────────────┘
```
