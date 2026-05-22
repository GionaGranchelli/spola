# Spola Workflow System — Complete Analysis

## 1. What Is Implemented — Complete Inventory

### Core Workflow Engine (28 source files)

| File | Location | Role |
|---|---|---|
| `SpolaState.kt` | `dev.spola.workflow` | State flowing through workflows: goal, config, agentDef, conversation, turnCount, intermediateResults, result, workflowNestingDepth |
| `WorkflowExecutionModels.kt` | `dev.spola.workflow` | `WorkflowExecutionStatus` (7-state enum), `WorkflowExecutionRecord`, `NewWorkflowExecution`, `WorkflowBootRecovery`, `WorkflowExecutionInput` |
| `WorkflowExecutionStore.kt` | `dev.spola.workflow` | Interface + `SqliteWorkflowExecutionStore` with `workflow_executions` table (21 columns, 6 indexes) |
| `WorkflowExecutionService.kt` | `dev.spola.workflow` | Orchestrates execution lifecycle: enqueue → runExecution → approveExecution → getExecution → requestCancel |
| `WorkflowDispatcher.kt` | `dev.spola.workflow` | `AsyncWorkflowDispatcher` — polls for QUEUED, runs with semaphore concurrency limits |
| `WorkflowDispatcherConfig.kt` | `dev.spola.workflow` | Config: enabled, pollIntervalMs (5s), batchSize (10), globalMaxConcurrent (4), perUserMaxConcurrent (2) |
| `WorkflowTemplateRegistry.kt` | `dev.spola.workflow` | Registry + `registerBuiltInTemplates()` + `registerYamlWorkflows()` |
| `WorkflowDefinitionStore.kt` | `dev.spola.workflow` | In-memory store for workflow definitions (NOT persisted) |
| `WorkflowSteps.kt` | `dev.spola.workflow` | `spolaAgentStep()` — wraps SpolaAgent.run as TramAI step |
| `TeamWorkflowSteps.kt` | `dev.spola.workflow` | Multi-agent steps: parallel, JVM templates, branching, human_approval gate |
| `JvmWorkflowTemplates.kt` | `dev.spola.workflow` | Built-in JVM debug/refactor/migration templates |
| `SpolaWorkflowObserver.kt` | `dev.spola.workflow` | Bridges observer → SSE, Prometheus, OpenTelemetry, chat |
| `SpolaWorkflowStateCodec.kt` | `dev.spola.workflow` | Jackson codec for checkpoint serialization |
| `WorkflowChatService.kt` | `dev.spola.workflow` | Chat notifications for workflow events |
| `WorkflowKanbanService.kt` | `dev.spola.workflow` | Triggers workflows on kanban transitions |
| `WorkflowSchedulerService.kt` | `dev.spola.workflow` | Bridges scheduled jobs → workflow executions |
| `WorkflowTools.kt` | `dev.spola.workflow` | `workflow_run` LLM tool |
| `WorkflowCreateTools.kt` | `dev.spola.workflow` | `workflow_create`/`workflow_delete` tools |
| `WorkflowExport.kt` | `dev.spola.workflow.yaml` | Exports built-in templates to YAML |
| `WorkflowDefinition.kt` | `dev.spola.workflow.yaml` | YAML data model classes |
| `YamlWorkflowParser.kt` | `dev.spola.workflow.yaml` | Jackson YAML parser |
| `YamlWorkflowLoader.kt` | `dev.spola.workflow.yaml` | File discovery + registration |
| `WorkflowParameterResolver.kt` | `dev.spola.workflow.yaml` | `{{params.X}}`, `{{state.goal}}`, `{{step.X.output}}` resolution |
| `YamlWorkflowCompiler.kt` | `dev.spola.workflow.yaml` | YAML → TramAI Workflow DSL |
| `YamlWorkflowStepRunner.kt` | `dev.spola.workflow.yaml` | Shell command execution |
| `YamlWorkflowDagSorter.kt` | `dev.spola.workflow.yaml` | Topological sort (Kahn's algorithm) |
| `DoneConditionEvaluator.kt` | `dev.spola.workflow.yaml` | 9 condition evaluators + LLM judge |

### API Routes

| Route | Method | Purpose |
|---|---|---|
| `/workflows` | GET | List registered templates |
| `/workflows/run` | POST | Enqueue execution |
| `/workflows/executions` | GET | List executions |
| `/workflows/executions/{id}` | GET | Get execution |
| `/workflows/executions/{id}/approve` | POST | Approve waiting execution |
| `/scheduler/jobs/{id}/executions` | GET | List by scheduler job |
| `/kanban/tasks/{id}/executions` | GET | List by kanban task |
| `/sessions/{id}/executions` | GET | List by session |

### CLI Commands

| Command | Purpose |
|---|---|
| `spola workflow run <name> <goal>` | Run a workflow |
| `spola workflow list` | List available workflows |
| `spola workflow export <name>` | Export template as YAML |
| `spola workflow approve <exec-id>` | Approve a WAITING_APPROVAL execution |
| `spola team run --agents A,B --goal G` | Run parallel agents |

### Built-in Templates

| Template | Description |
|---|---|
| `code-review` | 3 parallel reviewers (security, style, test) + summary |
| `jvm-debug` | Diagnose + fix-and-verify |
| `jvm-refactor` | Overview-and-impact + plan-and-verify |
| `jvm-migration` | Catalog-and-window + module-apply-and-verify |

---

## 2. Capabilities

### Execution Lifecycle

- Enqueue workflows with goal, params, trigger source
- Run with full TramAI engine (checkpoints, observer, stop policy)
- 7 statuses: QUEUED, RUNNING, WAITING_APPROVAL, CANCEL_REQUESTED, COMPLETED, FAILED, CANCELLED
- Approve WAITING_APPROVAL executions — patch checkpoint, resume
- Boot recovery — RUNNING/CANCEL_REQUESTED → FAILED on restart

### YAML Workflow Definitions

Place `.yaml` files in `~/.spola/workflows/`:

```yaml
name: my-workflow
version: "1"
params:
  target:
    type: string
    default: "src/"
steps:
  - id: analyze
    type: ai
    goal: "Analyze {{params.target}} for {{state.goal}}"
    persona: "You are an expert."
  - id: compile
    type: shell
    command: "./gradlew build"
    depends_on: [analyze]
    on_error: continue
done:
  - condition: all_steps_passed
```

### Step Types

| Type | Description |
|---|---|
| `ai` | Full SpolaAgent ReAct loop |
| `shell`/`local` | Shell command with retries, timeout, env |
| `parallel_agents` | Multiple agents concurrently |
| `human_approval` | Pause, wait for manual approval |
| `composite` | Nested sub-workflow via `workflow_ref` |

### Done Conditions

`output_has_content`, `output_contains`/`output_not_contains`, `markdown_valid`, `all_agents_completed`, `all_steps_passed`, `no_critical_blockers`, `report_generated`, `llm_judge`

---

## 3. Known Gaps

### Process Engine NOT Implemented

The `docs/process/PROCESS_ENGINE.md` spec describes plugin steps like `compile_project`, `run_tests`, `git_commit`, `telegram_notify`, `mcp_tool` — **none exist**. Only the YAML workflow engine is implemented.

### Other Gaps

- Definition store is in-memory (lost on restart)
- `PermissionEnforcer` not wired to YAML shell steps
- No RBAC for workflow execution
- Nesting depth defaults to 1 (disabled)
- No end-to-end REST API tests
- No tests for approve resume, dispatcher concurrency, scheduler

---

## 4. Test Coverage

### What IS tested (extensively)

- YAML parsing, DAG sorting, parameter resolution — 100+ tests
- Done conditions (9 types) — all verified
- Shell execution, failures, retries, timeouts
- Export/re-parse round-trip
- Kanban service, codec, team steps

### What is NOT tested

- Approve resume path, dispatcher polling, scheduler
- Workflow observers, create/delete tools
- Full HTTP request cycle, composite workflows
- Daemon mode integration
