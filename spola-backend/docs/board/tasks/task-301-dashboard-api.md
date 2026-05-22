# SPOLA-BACKEND-API: Dashboard Support Endpoints

## Goal
Add the missing REST API endpoints that a frontend dashboard needs.

## Requirements

### 1. SSE streaming for agent runs
- `POST /api/agent/run/stream` — like `/api/agent/run` but returns SSE stream
- Events: `token` (LLM token output), `tool_call` (tool being executed), `tool_result` (tool result), `status` (status updates), `error`, `complete` (final result)
- Use Ktor SSE (already available)
- Stima: **2h**

### 2. Tools listing endpoint
- `GET /api/tools` — list all registered tools with their parameters and descriptions
- Returns the same JSON schema format used by MCP tools/list
- Stima: **0.5h**

### 3. Memory browsing endpoint
- `GET /api/memory` — list all memory entries
- `GET /api/memory?q=<query>` — search memory
- `DELETE /api/memory/{key}` — delete a memory entry
- Stima: **0.5h**

### 4. Agent status endpoint
- `GET /api/agent/status` — returns config info (model, provider, tool count, running state)
- Stima: **0.5h**

### 5. Update all 80 tests to pass
- Stima: **0.5h**

## Files to modify
```
spola-backend-core/src/main/kotlin/dev/spola/api/
├── SpolaApiServer.kt     — add new routes
├── ApiModels.kt          — add new DTOs
├── StreamHandler.kt      — NEW: SSE streaming handler

spola-backend-core/src/test/kotlin/dev/spola/api/
└── SpolaApiServerTest.kt — add new endpoint tests
```
