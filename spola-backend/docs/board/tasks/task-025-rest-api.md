# TASK-025: Golem REST API Server

## Goal
Add a REST API server mode to Golem that exposes all agent capabilities via HTTP.

## Requirements

### 1. HTTP server (Ktor, port 8082 by default)
Add a new module `golem-server` (or integrate into golem-core) with Ktor embedded server.

### 2. Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/health | Health check → `{"status":"ok","version":"0.1.0"}` |
| POST | /api/agent/run | Run agent one-shot with a goal |
| GET | /api/jobs | List scheduled jobs |
| POST | /api/jobs | Create a scheduled job |
| DELETE | /api/jobs/{id} | Remove a scheduled job |

### 3. Agent run endpoint
POST /api/agent/run
```json
Request: {"goal": "refactor this file", "model": "gpt-4o", "persona": null}
Response: {"result": "the agent's final output", "turns": 5}
```

### 4. CLI integration
```bash
golem --api --api-port 8082
```

### 5. Dependencies
- `ktor-server-cio` (already present)
- `ktor-server-content-negotiation` + `ktor-serialization-kotlinx-json`
- Kotlinx serialization

### 6. Tests
- Health endpoint test
- Agent run endpoint test (mock LLM)
- Jobs CRUD endpoint tests
- All 57 existing tests must still pass

## Files to create
```
golem-core/src/main/kotlin/dev/golem/api/
├── GolemApiServer.kt        — Ktor server setup + routing
├── ApiModels.kt             — Request/response DTOs
└── AgentRunHandler.kt       — POST /api/agent/run handler

golem-core/src/test/kotlin/dev/golem/api/
└── GolemApiServerTest.kt    — Endpoint tests

golem-core/src/main/resources/api/openapi.yaml  — API docs (optional)
```
