# Spola LangGraph Demo — Walkthrough

> **What this shows:** Spola's compile-time-safe, type-checked state graphs (built on Kotlin sealed classes) vs LangGraph's Python dynamic state approach. Spola catches graph errors at compile time — LangGraph discovers them at runtime.

## Prerequisites

- **Docker** — Running daemon (`docker info` succeeds)
- **curl** — For API requests
- **Python 3** — For JSON formatting (optional, but used in the demo script)

## Quick Start

```bash
cd spola-backend/
./scripts/demo/01-langgraph-intro.sh
```

The script runs everything automatically. Below is a step-by-step explanation.

---

## Step-by-Step Walkthrough

### 1. Start Spola + PostgreSQL

```bash
docker compose up -d
```

This starts two containers:
- **PostgreSQL 16 Alpine** — Persistence layer for workflow executions, agent memory, and kanban state
- **Spola Backend API** — The Spola process engine on port 8082

Expected output:

```
[+] Running 3/3
 ✔ Network spola-backend_default       Created
 ✔ Container postgres                  Healthy
 ✔ Container spola-backend-api         Started
```

### 2. Verify Health

```bash
curl http://localhost:8082/api/health
```

Expected output (example):

```json
{"status":"UP","components":{"db":{"status":"UP"},"workflows":{"status":"UP","count":4}}}
```

### 3. List Available Workflows

```bash
curl http://localhost:8082/api/workflows
```

This lists all registered YAML and built-in workflows. The demo ships with at least:

| Workflow | Description |
|----------|-------------|
| `code-review` | Multi-reviewer code review with parallel agents |
| `jvm-debug` | Diagnose and fix JVM build/test failures |
| `security-scan` | Quick security scan of Kotlin files |

### 4. Run a Workflow

```bash
curl -X POST http://localhost:8082/api/workflows/run \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${SPOLA_API_KEY:-demo-key}" \
  -d '{
    "workflowName": "code-review",
    "goal": "Review all Kotlin source files for quality and correctness",
    "inputJson": "{\"files\":\"**/*.kt\",\"reviewers\":[\"security-reviewer\",\"style-reviewer\"]}"
  }'
```

Expected response (HTTP 202):

```json
{
  "executionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

**Key difference from LangGraph:** This call is type-validated before execution. The YAML is compiled into a typed `Workflow<SpolaState, String>` DAG at registration time. Invalid edges produce a compile-time error — not a runtime traceback.

### 5. Poll / Stream Execution

```bash
# Poll execution status
curl http://localhost:8082/api/workflows/executions/{executionId}

# Subscribe to SSE stream
curl http://localhost:8082/api/workflows/{executionId}/stream
```

The workflow transitions through:

```
QUEUED ──► RUNNING ──► COMPLETED
                │
                ├──► WAITING_APPROVAL (if human gate hit)
                └──► FAILED (if done conditions not met)
```

**LangGraph comparison:** LangGraph nodes are Python functions called in sequence. Spola's steps are typed nodes (AI, shell, gate, parallel, HTTP, MCP) with **replay policies** — each declares whether it's PURE, IDEMPOTENT, or NON_REPLAYABLE, enabling safe resume from crash.

### 6. View Results

```bash
curl http://localhost:8082/api/workflows/executions/{executionId}
```

The response includes the final result, intermediate step outputs, and execution metadata.

---

## What Makes Spola Different from LangGraph

| Aspect | Spola (Kotlin/JVM) | LangGraph (Python) |
|--------|-------------------|-------------------|
| **State typing** | Compile-time sealed class. Compiler checks every transition. | Dynamic dict. Edges validated at runtime. |
| **Step types** | 12 built-in: AI, shell, gate, HTTP, MCP, plugin, branch, parallel, delay, local, composite, hermes | Node functions — you write everything manually. |
| **Replay safety** | Per-step `ReplayPolicy` (PURE, IDEMPOTENT, EXTERNALLY_IDEMPOTENT, NON_REPLAYABLE) | No built-in replay model. |
| **Checkpointing** | Automatic at every step boundary via `WorkflowCheckpointStore` | Manual save/load. |
| **Human gates** | First-class `gateStep` with approve/reject API | Manual implementation with `interrupt()`. |
| **YAML workflows** | Hot-reloadable YAML → compiled DAG. No rebuild. | No YAML DSL — all Python. |
| **Parallel agents** | Built-in `parallel_agents` step type | Manual with `concurrent.futures`. |
| **Deployment** | JAR. Embeddable in any JVM app. | Python runtime. |

## What to Try Next

1. **Edit a YAML workflow** — Modify `~/.spola/workflows/code-review.yaml` and restart Spola to see hot-reload
2. **Add a human gate** — Insert a `human_approval` step between review and summary
3. **Chain workflows** — Use the `composite` step type to nest workflows
4. **Write your own YAML** — Create a workflow in `~/.spola/workflows/my-workflow.yaml` and run it via the API
5. **Export a built-in workflow** — `spola workflow export jvm-debug -o ~/.spola/workflows/`
6. **Kotlin DSL workflow** — Write a typed sealed-class workflow in Kotlin; see `docs/spola-state-graphs.md` for the full DSL reference

---

## Troubleshooting

| Problem | Likely Cause | Fix |
|---------|-------------|-----|
| `docker compose up -d` fails | Docker not running | Run `docker info` to check |
| Health endpoint returns 503 | PostgreSQL not ready | Wait 10–15s; check `docker compose logs postgres` |
| Workflow run returns 401 | API key not set | Export `SPOLA_API_KEY` or pass `Authorization` header |
| Workflow hangs at WAITING_APPROVAL | Human gate activated | Approve via `POST /api/workflows/executions/{id}/approve` |
| Execution not found | Wrong execution ID | List executions via `GET /api/workflows/executions` |
