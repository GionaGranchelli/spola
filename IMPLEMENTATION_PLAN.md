# LangSmith-lite Workflow Monitoring Dashboard — Implementation Plan

## Overview
Build a workflow monitoring dashboard in the Spola KMP frontend, backed by new REST APIs on the Spola backend. The dashboard provides an execution list, live SSE streaming of step transitions, checkpoint state visualization, token/timing metrics, manual approval controls, and replay controls.

---

## Phase 1: Backend API Additions (spola-backend-core)

### 1a. API Models — `WorkflowExecutionModels.kt`
Add serializable DTOs for:
- `WorkflowCheckpointResponse` — id, stepName, timestamp, state summary
- `WorkflowStepMetrics` — stepName, inputTokens, outputTokens, durationMs, status
- `WorkflowMetricsResponse` — list of WorkflowStepMetrics per execution
- `GateDecisionRequest` — executionId, stepName, approved, reason
- `ResumeRequest` — checkpointKey (optional, defaults to latest)

### 1b. New Endpoints — `WorkflowRoutes.kt`
Add these routes:

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/workflows/executions/{id}/checkpoints` | List checkpoint chain for an execution |
| GET | `/api/workflows/executions/{id}/metrics` | Token usage & timing per step |
| POST | `/api/workflows/executions/{id}/resume` | Resume execution from latest checkpoint |
| POST | `/api/gates/{executionId}/{stepName}/decide` | Approve or reject a gate |

### 1c. Service Methods — `WorkflowExecutionService.kt`
Add:
- `getCheckpointHistory(executionId)` — query checkpoints from persistence
- `resumeFromCheckpoint(executionId)` — resume execution via existing TramAI resume mechanism
- `decideGate(executionId, stepName, approved)` — approve or reject gate step

### 1d. Checkpoint History — New `CheckpointHistoryStore.kt` (optional)
If checkpoints are stored per execution in SQLite, add a table. Otherwise reuse existing checkpoint manager's `sessionId`-based queries.

---

## Phase 2: Frontend (spola-frontend)

### 2a. Shared Models — `Models.kt`
Add monitoring-specific DTOs:
- `WorkflowCheckpointResponse` — mirroring backend
- `WorkflowStepMetricsResponse` — list per execution
- `WorkflowStreamEvent` — for SSE consumption (step_status, step_complete, gate_pending, etc.)
- `GateDecisionRequest` — for gate approve/reject

### 2b. API Client — `WorkflowClient.kt` (extend existing)
Add methods:
- `getWorkflowCheckpoints(executionId)` → GET `/api/workflows/executions/{id}/checkpoints`
- `getWorkflowMetrics(executionId)` → GET `/api/workflows/executions/{id}/metrics`
- `resumeWorkflow(executionId)` → POST `/api/workflows/executions/{id}/resume`
- `decideGate(executionId, stepName, approved)` → POST `/api/gates/{executionId}/{stepName}/decide`
- `streamWorkflowEvents(executionId)` → SSE flow via Ktor SSE client to `GET /api/workflows/{id}/stream`

### 2c. UI — `MonitoringPage.kt`
New page component under Workflows tab OR new 7th tab "Monitoring". Two sub-views:

**View 1: Execution List**
- Table/card list of all executions with status badges, duration, step count
- Color-coded status: green (completed), red (failed), yellow (running), blue (queued), orange (waiting_approval)
- Last execution per workflow highlighted
- Click to drill into execution detail

**View 2: Execution Detail / Live Stream**
- Replaces main content when an execution is selected
- Header: execution id, workflow name, status badge, overall duration
- **Step Timeline**: vertical timeline showing each step, its status, and duration
- **Live SSE Feed**: auto-connects for RUNNING/QUEUED executions, shows real-time step transitions
- **Checkpoint Viewer**: expandable JSON tree of checkpoint state
- **Metrics Panel**: token usage per step (input/output/thinking), timing breakdown
- **Gate Controls**: "Approve" / "Reject" buttons when execution is WAITING_APPROVAL
- **Replay Controls**: "Resume from Checkpoint" button

### 2d. Navigation — `NavigationTab.kt`
Add `Monitoring` tab option, or embed monitoring inside the existing Workflows tab as a sub-view.

### 2e. AppShell — `AppShell.kt`
Add Monitoring page to the tab routing when using a separate tab.

---

## Phase 3: Build & Verify

1. `./gradlew :composeApp:compileKotlinDesktop` — Desktop compilation check
2. `./gradlew :spola-backend-cli:compileKotlin` — Backend compilation check
3. Manual review of all new files

---

## Files to Create/Modify Summary

### Create:
- `spola-frontend/composeApp/src/commonMain/kotlin/dev/spola/app/app/pages/MonitoringPage.kt`

### Modify (Backend):
- `spola-backend/spola-backend-core/src/main/kotlin/dev/spola/workflow/WorkflowExecutionModels.kt`
- `spola-backend/spola-backend-core/src/main/kotlin/dev/spola/api/routes/WorkflowRoutes.kt`
- `spola-backend/spola-backend-core/src/main/kotlin/dev/spola/workflow/WorkflowExecutionService.kt`

### Modify (Frontend):
- `spola-frontend/shared/src/commonMain/kotlin/dev/spola/app/models/Models.kt`
- `spola-frontend/shared/src/commonMain/kotlin/dev/spola/app/network/WorkflowClient.kt`
- `spola-frontend/shared/src/commonMain/kotlin/dev/spola/app/network/SpolaClient.kt`
- `spola-frontend/composeApp/src/commonMain/kotlin/dev/spola/app/app/navigation/NavigationTab.kt`
- `spola-frontend/composeApp/src/commonMain/kotlin/dev/spola/app/app/AppShell.kt`
