# Spola REST API Reference

Base URL: `http://{host}:{port}/api`

Default host: `127.0.0.1` (localhost only)
Default port: `8082`

## Server Setup

Start the API server with any of these methods:

```bash
# CLI flag
spola --api

# Dedicated serve command
spola serve

# With custom port and host
spola --api --port 9090 --host 0.0.0.0

# With API key (required for remote access)
spola --api --api-key my-secret-key

# With TLS (PEM certificate + key)
spola --api --tls-cert /path/to/cert.pem --tls-key /path/to/key.pem

# Insecure mode (no auth required on any host)
spola --api --insecure
```

### Configuration

| Setting | Env / CLI | Default |
|---------|-----------|---------|
| Host | `--host` / `SPOLA_HOST` | `127.0.0.1` |
| Port | `--port` / `SPOLA_PORT` | `8082` |
| API Key | `--api-key` / `SPOLA_API_KEY` | (none — localhost only) |
| TLS Cert | `--tls-cert` / `SPOLA_TLS_CERT` | (none — plain HTTP) |
| TLS Key | `--tls-key` / `SPOLA_TLS_KEY` | (none — plain HTTP) |
| Insecure | `--insecure` / `SPOLA_INSECURE` | `false` |

When TLS is configured, the server listens on **two ports**: one for plain HTTP (redirect/API) and one for HTTPS via the sslConnector.

---

## Authentication

Spola uses **Bearer token** authentication via the `Authorization` header.

```bash
Authorization: Bearer <your-api-key>
```

### Auth Rules

| Host | API Key Set | Auth Required? |
|------|-------------|----------------|
| 127.0.0.1 / localhost / ::1 | No | **No** — local-only access |
| 127.0.0.1 / localhost / ::1 | Yes | **Yes** — Bearer token required |
| Any remote (0.0.0.0, LAN IP, etc.) | No | **Blocked** — key required for remote |
| Any remote (0.0.0.0, LAN IP, etc.) | Yes | **Yes** — Bearer token required |
| Any host | Yes + `--insecure` | **No** — all auth relaxed |

### Public Endpoints (No Auth)

- `GET /api/health`
- `GET /api/metrics`
- `GET /api/metrics/history`

### Pairing Endpoints (X-Pairing-Token)

- `GET /api/pairing/info` — Use `X-Pairing-Token` header instead of Bearer
- `GET /api/pairing/qrcode` — Use `X-Pairing-Token` header instead of Bearer

### Auth Errors

| Response | Condition |
|----------|-----------|
| `401 Unauthorized` | Missing API key — body: `{"error": "missing api key"}` |
| `403 Forbidden` | Invalid API key — body: `{"error": "invalid api key"}` |
| `403 Forbidden` | Remote access without configured key — body includes hint to set `SPOLA_API_KEY` |

---

## CORS

CORS is **not explicitly configured** in the Ktor server. By default, CORS follows the browser's same-origin policy. If you need cross-origin requests from a web frontend, configure a reverse proxy (nginx, Caddy) or add the CORS plugin to `SpolaApiServer.kt`.

---

## Endpoints

### 1. Health

#### GET /api/health

Auth: **None** (public)

Response:
```json
{
  "status": "ok",
  "version": "0.1.1"
}
```

curl:
```bash
curl http://127.0.0.1:8082/api/health
```

---

### 2. Sessions

#### GET /api/sessions

Auth: Bearer token

List all sessions, ordered by last active (descending).

Response:
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "title": "Refactoring session",
    "createdAt": 1744560000000,
    "lastActiveAt": 1744563600000,
    "modelId": "gpt-4o",
    "providerId": "openai"
  }
]
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/sessions
```

---

#### POST /api/session

Auth: Bearer token

Create a new session.

Request body:
```json
{
  "title": "My Session",
  "modelId": "gpt-4o",
  "providerId": "openai"
}
```

- `title` (string, required) — Session display name
- `modelId` (string, optional) — Defaults to configured model
- `providerId` (string, optional) — Defaults to configured provider

Response: `201 Created`
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "My Session",
  "createdAt": 1744560000000,
  "lastActiveAt": 1744560000000,
  "modelId": "gpt-4o",
  "providerId": "openai"
}
```

curl:
```bash
curl -X POST http://127.0.0.1:8082/api/session \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"title":"My Session","modelId":"gpt-4o"}'
```

---

#### GET /api/session/{id}

Auth: Bearer token

Get a single session by ID.

Response:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "My Session",
  "createdAt": 1744560000000,
  "lastActiveAt": 1744563600000,
  "modelId": "gpt-4o",
  "providerId": "openai"
}
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/session/550e8400-e29b-41d4-a716-446655440000
```

---

#### DELETE /api/session/{id}

Auth: Bearer token

Delete a session and its messages.

Response: `204 No Content`

curl:
```bash
curl -X DELETE -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/session/550e8400-e29b-41d4-a716-446655440000
```

---

#### POST /api/session/{id}/model

Auth: Bearer token

Update the model for a session.

Request body:
```json
{
  "modelId": "claude-sonnet-4-20250514"
}
```

Response:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "My Session",
  "createdAt": 1744560000000,
  "lastActiveAt": 1744567200000,
  "modelId": "claude-sonnet-4-20250514",
  "providerId": "openai"
}
```

curl:
```bash
curl -X POST http://127.0.0.1:8082/api/session/550e8400-e29b-41d4-a716-446655440000/model \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"modelId":"claude-sonnet-4-20250514"}'
```

---

#### GET /api/session/{id}/messages

Auth: Bearer token

Get all messages for a session.

Response:
```json
[
  {"role": "user", "content": "Refactor this module"},
  {"role": "assistant", "content": "Here's the refactored code..."}
]
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/session/550e8400-e29b-41d4-a716-446655440000/messages
```

---

#### POST /api/session/{id}/run

Auth: Bearer token

Run an agent goal within a session context. Messages are saved to the session.

Request body:
```json
{
  "goal": "Refactor the main controller",
  "model": "gpt-4o",
  "persona": "You are an expert Kotlin developer...",
  "personaName": "senior-dev"
}
```

- `goal` (string, required) — The task for the agent
- `model` (string, optional) — Model override (defaults to session's model)
- `persona` (string, optional) — Inline persona override
- `personaName` (string, optional) — Named persona from store

Response:
```json
{
  "result": "Completed refactoring. Changed 3 files.",
  "turns": 5
}
```

curl:
```bash
curl -X POST http://127.0.0.1:8082/api/session/session-id-123/run \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"goal":"Refactor the main controller"}'
```

---

#### POST /api/session/{id}/run/stream

Auth: Bearer token

Run an agent goal with **Server-Sent Events (SSE)** streaming. Same endpoint as above
but returns SSE events instead of a single JSON response.

Request body: Same as `/api/session/{id}/run`

SSE event stream:
```
event: status
data: {"status":"started","message":"Creating agent instance"}

event: token
data: {"text":"Here is the refactored"}

event: tool_call
data: {"id":"call_1","name":"read_file","arguments":{"path":"/src/main.kt"}}

event: tool_result
data: {"id":"call_1","name":"read_file","success":true,"output":"..."}

event: complete
data: {"result":"Completed refactoring. Changed 3 files.","turns":5}

event: error
data: {"error":"Something went wrong"}
```

SSE events emitted:
- **status** — StatusEventPayload: `{status, message?}`
- **token** — TokenEventPayload: `{text}` (streaming text tokens)
- **tool_call** — ToolCallEventPayload: `{id, name, arguments}`
- **tool_result** — ToolResultEventPayload: `{id, name, success, output, error?}`
- **complete** — CompleteEventPayload: `{result, turns}`
- **error** — ErrorEventPayload: `{error}`

curl:
```bash
curl -N -X POST http://127.0.0.1:8082/api/session/session-id-123/run/stream \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"goal":"Refactor the main controller"}'
```

---

#### GET /api/models

Auth: Bearer token

List available models (currently returns the configured model).

Response:
```json
[
  {
    "id": "gpt-4o",
    "name": "gpt-4o",
    "provider": "openai",
    "description": "openai model configured in Spola"
  }
]
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/models
```

---

### 3. Agents (Run)

#### POST /api/agent/run

Auth: Bearer token

Run an agent with a goal. Uses default configuration.

Request body:
```json
{
  "goal": "Refactor the main controller",
  "model": "gpt-4o",
  "persona": "You are an expert Kotlin developer...",
  "personaName": "senior-dev"
}
```

- `goal` (string, required) — The task for the agent (must not be blank)
- `model` (string, optional) — Model override
- `persona` (string, optional) — Inline persona override
- `personaName` (string, optional) — Named persona from store

Response:
```json
{
  "result": "Completed refactoring. Changed 3 files.",
  "turns": 5
}
```

curl:
```bash
curl -X POST http://127.0.0.1:8082/api/agent/run \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"goal":"Refactor the main controller"}'
```

---

#### POST /api/agent/run/stream

Auth: Bearer token

Run an agent with SSE streaming. Same request body as `/api/agent/run`.
Returns the same SSE event types as the session stream endpoint.

curl:
```bash
curl -N -X POST http://127.0.0.1:8082/api/agent/run/stream \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"goal":"Refactor the main controller"}'
```

---

#### GET /api/agent/status

Auth: Bearer token

Get current agent status and configuration.

Response:
```json
{
  "model": "gpt-4o",
  "provider": "openai",
  "maxTurns": 25,
  "workingDirectory": "/home/user/project",
  "toolCount": 42,
  "running": false
}
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/agent/status
```

---

### 4. Agents (CRUD)

#### GET /api/agents

Auth: Bearer token

List all agent definitions. Optionally filter by tag.

Query parameters:
- `tag` (string, optional) — Filter agents by tag

Response:
```json
[
  {
    "id": "my-coder",
    "name": "Code Reviewer",
    "description": "Expert code reviewer",
    "systemPrompt": "You are an expert code reviewer...",
    "preferredModel": "gpt-4o",
    "preferredProvider": "openai",
    "fallbackModel": "claude-sonnet-4-20250514",
    "toolPolicy": "ALL",
    "toolsAllowed": [],
    "filesystemAccess": "read-write",
    "shellAccess": true,
    "networkAccess": true,
    "executeCommands": "auto",
    "memoryScope": "global",
    "tags": ["review", "kotlin"],
    "enabled": true,
    "version": 1,
    "createdAt": "2025-04-13T10:00:00Z",
    "updatedAt": "2025-04-13T10:00:00Z"
  }
]
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/agents

# Filter by tag
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/agents?tag=review
```

---

#### GET /api/agents/{id}

Auth: Bearer token

Get a single agent definition by ID.

Response: Same structure as list item above.

curl:
```bash
curl -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/agents/my-coder
```

---

#### POST /api/agents

Auth: Bearer token

Create a new agent definition.

Request body:
```json
{
  "id": "my-coder",
  "name": "Code Reviewer",
  "description": "Expert code reviewer",
  "systemPrompt": "You are an expert code reviewer focused on Kotlin.",
  "preferredModel": "gpt-4o",
  "preferredProvider": "openai",
  "fallbackModel": "claude-sonnet-4-20250514",
  "toolPolicy": "ALL",
  "toolsAllowed": [],
  "filesystemAccess": "read-write",
  "shellAccess": true,
  "networkAccess": true,
  "executeCommands": "auto",
  "memoryScope": "global",
  "tags": ["review", "kotlin"],
  "responseFormat": "markdown"
}
```

Response: `201 Created` with the created agent definition.

curl:
```bash
curl -X POST http://127.0.0.1:8082/api/agents \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "my-coder",
    "name": "Code Reviewer",
    "description": "Expert code reviewer",
    "systemPrompt": "You are an expert code reviewer focused on Kotlin.",
    "preferredModel": "gpt-4o",
    "preferredProvider": "openai"
  }'
```

---

#### PUT /api/agents/{id}

Auth: Bearer token

Update an existing agent definition. All fields are optional; only provided fields are updated.

Request body:
```json
{
  "name": "Updated Name",
  "description": "Updated description",
  "enabled": false,
  "maxTurnsOverride": 50
}
```

Response: Updated agent definition (version incremented).

curl:
```bash
curl -X PUT http://127.0.0.1:8082/api/agents/my-coder \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"name":"Updated Name","enabled":false}'
```

---

#### DELETE /api/agents/{id}

Auth: Bearer token

Delete an agent definition.

Response:
- `204 No Content` — Successfully deleted
- `404 Not Found` — Agent not found `{"error": "Agent not found: my-coder"}`

curl:
```bash
curl -X DELETE -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/agents/my-coder
```

---

#### POST /api/agents/run

Auth: Bearer token

Run a specific agent definition by ID with a goal.

Request body:
```json
{
  "agentId": "my-coder",
  "goal": "Review the pull request changes"
}
```

- `agentId` (string, required) — The agent definition ID
- `goal` (string, required) — The task for the agent

Response:
```json
{
  "agentId": "my-coder",
  "result": "Found 3 issues: 1 security vulnerability, 2 style violations."
}
```

curl:
```bash
curl -X POST http://127.0.0.1:8082/api/agents/run \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"agentId":"my-coder","goal":"Review the pull request changes"}'
```

---

### 5. Memory

#### GET /api/memory

Auth: Bearer token

List or search memory entries.

Query parameters:
- `q` (string, optional) — Search query. If provided, searches memory by relevance. If omitted, lists all entries.

Response:
```json
{
  "entries": [
    {
      "key": "project-context",
      "value": "Spola is a Kotlin agent framework...",
      "createdAt": "2025-04-13T10:00:00Z",
      "updatedAt": "2025-04-13T10:00:00Z"
    }
  ],
  "query": "spola architecture"
}
```

curl:
```bash
# List all
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/memory

# Search
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/memory?q=spola+architecture
```

---

#### DELETE /api/memory/{key}

Auth: Bearer token

Delete a memory entry by key.

Response:
```json
{
  "deleted": true,
  "key": "project-context",
  "message": "Memory entry deleted"
}
```

If not found:
```json
{
  "deleted": false,
  "key": "nonexistent",
  "message": "Memory entry not found"
}
```

curl:
```bash
curl -X DELETE -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/memory/project-context
```

---

### 6. Personas

#### GET /api/personas

Auth: Bearer token

List all personas stored in the persona database.

Response:
```json
{
  "personas": [
    {
      "name": "senior-dev",
      "role": "Senior Kotlin Developer",
      "tags": "kotlin, backend",
      "sources": "project-docs",
      "body": "You are a senior Kotlin developer...",
      "summary": "Expert in Kotlin and JVM",
      "createdAt": 1744560000000,
      "updatedAt": 1744560000000
    }
  ]
}
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/personas
```

---

#### GET /api/personas/{name}

Auth: Bearer token

Get a single persona by name.

Response: Same structure as list item above.

curl:
```bash
curl -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/personas/senior-dev
```

---

### 7. Config

#### GET /api/config

Auth: Bearer token

Get the current Spola configuration. Sensitive values (apiKey, telegramBotToken,
email password, elevenlabsApiKey, pairingToken) are redacted as `"***"`.

Response:
```json
{
  "model": "gpt-4o",
  "provider": "openai",
  "workdir": "/home/user/project",
  "memoryDb": "/home/user/.spola/memory.db",
  "schedulerDb": "/home/user/.spola/scheduler.db",
  "kanbanDb": "/home/user/.spola/kanban.db",
  "checkpointDb": "/home/user/.spola/checkpoint.db",
  "jvmIndexDb": "/home/user/.spola/jvm-index.db",
  "sessionsDb": "/home/user/.spola/sessions.db",
  "maxTurns": 25,
  "temperature": 0.7,
  "maxTokens": 8192,
  "persona": "/home/user/.spola/persona.md",
  "apiKey": "***",
  "insecure": false,
  "unsafe": false,
  "telegramBotToken": "***",
  "email": {
    "smtpHost": "smtp.gmail.com",
    "smtpPort": 587,
    "username": "user@gmail.com",
    "password": "***",
    "from": "user@gmail.com"
  },
  "tts": {
    "provider": "edge",
    "elevenlabsApiKey": null,
    "elevenlabsVoiceId": null
  },
  "compressionEnabled": true,
  "jvmIndexAutoRefresh": true,
  "autoCheckpoint": true,
  "pairingToken": "***",
  "otelEnabled": false,
  "otelEndpoint": null,
  "otelServiceName": "spola",
  "metricsEnabled": true,
  "pluginsEnabled": true,
  "pluginsDir": "/home/user/.spola/plugins",
  "agentsDir": "/home/user/.spola/agents",
  "agentsDb": "/home/user/.spola/agents.db",
  "defaultAgentId": null,
  "architectMode": {
    "enabled": false,
    "architectModel": null,
    "architectProvider": null,
    "editorModel": null,
    "editorProvider": null
  },
  "effectiveConfigPath": "/home/user/.spola/config.yaml"
}
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/config
```

---

### 8. Tools

#### GET /api/tools

Auth: Bearer token

List all registered tools with their JSON Schema definitions.

Response:
```json
{
  "tools": [
    {
      "name": "read_file",
      "description": "Read the contents of a file",
      "parameters": {
        "type": "object",
        "properties": {
          "path": {
            "type": "string",
            "description": "Path to the file"
          },
          "offset": {
            "type": "integer",
            "description": "Line offset",
            "default": 1
          }
        },
        "required": ["path"]
      }
    }
  ]
}
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/tools
```

---

#### GET /api/tools/{name}

Auth: Bearer token

Get detailed information about a specific tool, including its enabled status.

Response:
```json
{
  "name": "read_file",
  "description": "Read the contents of a file",
  "parameters": [
    {
      "name": "path",
      "description": "Path to the file",
      "type": "string",
      "required": true,
      "default": null
    }
  ],
  "enabled": true
}
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/tools/read_file
```

---

#### POST /api/tools/{name}/toggle

Auth: Bearer token

Toggle a tool's enabled/disabled state.

Response:
```json
{
  "name": "shell",
  "enabled": false
}
```

curl:
```bash
curl -X POST -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/tools/shell/toggle
```

---

#### PUT /api/tools/{name}/toggle

Auth: Bearer token

Set a tool's enabled state explicitly.

Request body:
```json
{
  "enabled": false
}
```

Response:
```json
{
  "name": "shell",
  "enabled": false
}
```

curl:
```bash
curl -X PUT http://127.0.0.1:8082/api/tools/shell/toggle \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"enabled": false}'
```

---

### 9. Checkpoints

#### GET /api/checkpoint

Auth: Bearer token

List all checkpoints.

Response:
```json
{
  "checkpoints": [
    {
      "id": 1,
      "sessionId": "session-123",
      "turnNumber": 3,
      "createdAt": "2025-04-13T10:00:00Z",
      "diff": null
    }
  ]
}
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/checkpoint
```

---

#### GET /api/checkpoint/{id}/diff

Auth: Bearer token

Get a specific checkpoint with its git diff content.

Response:
```json
{
  "id": 1,
  "sessionId": "session-123",
  "turnNumber": 3,
  "createdAt": "2025-04-13T10:00:00Z",
  "diff": "diff --git a/src/main.kt b/src/main.kt\nindex abc..def 100644\n--- a/src/main.kt\n+++ b/src/main.kt\n@@ -1,5 +1,7 @@\n+// new code"
}
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/checkpoint/1/diff
```

---

#### GET /api/checkpoint/session/{sessionId}/diffs

Auth: Bearer token

List all checkpoints for a specific session, including diffs.

Response:
```json
{
  "checkpoints": [
    {
      "id": 1,
      "sessionId": "session-123",
      "turnNumber": 3,
      "createdAt": "2025-04-13T10:00:00Z",
      "diff": "diff --git a/src/main.kt b/src/main.kt..."
    }
  ]
}
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/checkpoint/session/session-123/diffs
```

---

#### GET /api/checkpoint/resume/{session_id}

Auth: Bearer token

Resume a session from its latest checkpoint.

Response:
```json
{
  "sessionId": "session-123",
  "messageCount": 15,
  "messages": [
    {"role": "user", "content": "Refactor this module"},
    {"role": "assistant", "content": "Here's the refactored code..."}
  ]
}
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/checkpoint/resume/session-123
```

---

### 10. Processes

#### POST /api/processes/run

Auth: Bearer token

Run a deterministic process template.

Request body:
```json
{
  "template": "deploy-review",
  "goal": "Deploy the latest build to staging",
  "project": ":"
}
```

- `template` (string, required) — The process template name
- `goal` (string, required) — The goal/description for this run
- `project` (string, optional, default `:`) — Project scope

Response:
```json
{
  "runId": "run-abc-123",
  "status": "running",
  "currentStep": "compile_project",
  "steps": [...]
}
```

curl:
```bash
curl -X POST http://127.0.0.1:8082/api/processes/run \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"template":"deploy-review","goal":"Deploy the latest build"}'
```

---

#### GET /api/processes/status/{runId}

Auth: Bearer token

Get the status of a running or completed process.

Response:
```json
{
  "runId": "run-abc-123",
  "status": "running",
  "currentStep": "compile_project",
  "steps": [...]
}
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/processes/status/run-abc-123
```

---

#### POST /api/processes/cancel/{runId}

Auth: Bearer token

Cancel a running process.

Response:
```json
{
  "cancelled": true
}
```

curl:
```bash
curl -X POST -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/processes/cancel/run-abc-123
```

---

#### POST /api/gates/{runId}/{stepName}/decide

Auth: Bearer token

Provide a human decision for a gate/hold step in a process.

Request body:
```json
{
  "decision": "approve",
  "notes": "Looks good, proceed"
}
```

- `decision` (string, required) — e.g., "approve" or "reject"
- `notes` (string, optional) — Human notes

Response:
```json
{
  "decided": true
}
```

curl:
```bash
curl -X POST http://127.0.0.1:8082/api/gates/run-abc-123/approve_deployment/decide \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"decision":"approve","notes":"Looks good"}'
```

---

### 11. Jobs (Scheduler)

#### GET /api/jobs

Auth: Bearer token

List all scheduled jobs.

Response:
```json
{
  "jobs": [
    {
      "id": "job-1",
      "name": "Daily report",
      "goal": "Generate the daily status report",
      "cronExpression": "0 9 * * *",
      "enabled": true,
      "createdAt": "2025-04-13T10:00:00",
      "lastRunAt": "2025-04-14T09:00:00",
      "nextRunAt": "2025-04-15T09:00:00"
    }
  ]
}
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/jobs
```

---

#### POST /api/jobs

Auth: Bearer token

Create a new scheduled job.

Request body:
```json
{
  "name": "Daily report",
  "cronExpression": "0 9 * * *",
  "goal": "Generate the daily status report",
  "enabled": true
}
```

- `name` (string, required) — Job name
- `cronExpression` (string, required) — Cron schedule expression
- `goal` (string, required) — The agent goal to run
- `enabled` (boolean, optional, default: true) — Whether the job is active

Response: `201 Created` with the created job (same structure as list item).

curl:
```bash
curl -X POST http://127.0.0.1:8082/api/jobs \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"name":"Daily report","cronExpression":"0 9 * * *","goal":"Generate daily report"}'
```

---

#### DELETE /api/jobs/{id}

Auth: Bearer token

Delete a scheduled job.

Response:
```json
{
  "removed": true,
  "id": "job-1",
  "message": "Scheduled job removed"
}
```

If not found:
```json
{
  "removed": false,
  "id": "job-1",
  "message": "Scheduled job not found"
}
```

curl:
```bash
curl -X DELETE -H "Authorization: Bearer my-api-key" \
  http://127.0.0.1:8082/api/jobs/job-1
```

---

### 12. Providers

#### GET /api/providers

Auth: Bearer token (via global interceptor)

List all configured LLM providers (built-in and custom).

Response:
```json
{
  "providers": [
    {
      "name": "openai",
      "type": "openai",
      "baseUrl": "https://api.openai.com",
      "isBuiltin": true,
      "models": ["gpt-4o"]
    },
    {
      "name": "anthropic",
      "type": "anthropic",
      "baseUrl": "https://api.anthropic.com",
      "isBuiltin": true,
      "models": ["claude-sonnet-4-20250514"]
    },
    {
      "name": "ollama",
      "type": "openai-compat",
      "baseUrl": "http://localhost:11434",
      "isBuiltin": true,
      "models": ["llama3"]
    },
    {
      "name": "my-custom",
      "type": "openai-compat",
      "baseUrl": "https://my-llm.example.com",
      "isBuiltin": false,
      "models": ["my-model"]
    }
  ]
}
```

Built-in providers: `openai`, `anthropic`, `openai-compat`, `ollama`, `google`

curl:
```bash
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/providers
```

---

#### POST /api/provider/test

Auth: Bearer token (via global interceptor)

Test connectivity to a provider endpoint.

Request body:
```json
{
  "baseUrl": "http://localhost:11434",
  "apiKey": "optional-key"
}
```

- `baseUrl` (string, required) — The URL to test
- `apiKey` (string, optional) — Optional bearer token to include

Response:
```json
{
  "success": true,
  "status": 200
}
```

On failure:
```json
{
  "success": false,
  "error": "Connection refused"
}
```

curl:
```bash
curl -X POST http://127.0.0.1:8082/api/provider/test \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"baseUrl":"http://localhost:11434"}'
```

---

### 13. Delivery

#### POST /api/deliver/telegram

Auth: Bearer token

Send a Telegram message via the `telegram_send` tool.

Request body:
```json
{
  "chatId": "-1001234567890",
  "text": "Hello from Spola!"
}
```

- `chatId` (string, required) — Telegram chat ID
- `text` (string, required) — Message text

Response (success):
```json
{
  "success": true,
  "message": "Message sent"
}
```

Response (failure): `502 Bad Gateway`
```json
{
  "success": false,
  "message": "Telegram API error: invalid chat ID"
}
```

curl:
```bash
curl -X POST http://127.0.0.1:8082/api/deliver/telegram \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"chatId":"-1001234567890","text":"Hello from Spola!"}'
```

---

#### POST /api/deliver/email

Auth: Bearer token

Send an email via the `email_send` tool.

Request body:
```json
{
  "to": "user@example.com",
  "subject": "Spola Report",
  "body": "Here is your daily report..."
}
```

- `to` (string, required) — Recipient email
- `subject` (string, required) — Email subject
- `body` (string, required) — Email body text

Response: Same structure as Telegram delivery.

curl:
```bash
curl -X POST http://127.0.0.1:8082/api/deliver/email \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"to":"user@example.com","subject":"Report","body":"Hello from Spola!"}'
```

---

### 14. Metrics

#### GET /api/metrics

Auth: **None** (public)

Prometheus-formatted metrics text.

Response: `Content-Type: text/plain`
```
# HELP spola_agent_runs_total Total agent runs
# TYPE spola_agent_runs_total counter
spola_agent_runs_total 42.0
...
```

curl:
```bash
curl http://127.0.0.1:8082/api/metrics
```

---

#### GET /api/metrics/history

Auth: **None** (public)

Get historical metrics snapshots.

Response:
```json
{
  "metrics": [
    {
      "timestamp": 1744560000000,
      "agentRunsTotal": 10.0,
      "agentTurnsTotal": 45.0,
      "toolCallsTotal": 120.0,
      "llmCallsTotal": 55.0,
      "llmTokensTotal": 85000.0
    }
  ]
}
```

curl:
```bash
curl http://127.0.0.1:8082/api/metrics/history
```

---

### 15. Workflows

#### GET /api/workflows

Auth: Bearer token

List available workflow templates.

Response:
```json
{
  "workflows": [
    {
      "name": "code-review",
      "description": "Run a code review with security, style, and test reviewers"
    }
  ]
}
```

curl:
```bash
curl -H "Authorization: Bearer my-api-key" http://127.0.0.1:8082/api/workflows
```

---

#### POST /api/workflows/run

Auth: Bearer token

Run a named workflow.

Request body:
```json
{
  "workflowName": "code-review",
  "goal": "Review the latest commit"
}
```

- `workflowName` (string, required) — Workflow name (currently supports: `code-review`)
- `goal` (string, required) — The goal/description for the workflow

Response:
```json
{
  "workflowName": "code-review",
  "result": "Review complete: 2 issues found"
}
```

curl:
```bash
curl -X POST http://127.0.0.1:8082/api/workflows/run \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"workflowName":"code-review","goal":"Review the latest commit"}'
```

---

### 16. Pairing

#### GET /api/pairing/info

Auth: **X-Pairing-Token** header (instead of Bearer)

Get pairing information for connecting to this Spola instance from another device.
The response includes the detected LAN IP, port, pairing token, and trust ID.

Headers:
- `X-Pairing-Token` (string) — Must match the server's pairing token

Response:
```json
{
  "host": "192.168.1.100",
  "port": 8082,
  "token": "abc123-token",
  "trustId": "uuid-trust-id",
  "version": "0.1.1"
}
```

curl:
```bash
curl -H "X-Pairing-Token: my-pairing-token" \
  http://127.0.0.1:8082/api/pairing/info
```

---

#### GET /api/pairing/qrcode

Auth: **X-Pairing-Token** header (instead of Bearer)

Generate a QR code PNG image containing the pairing info JSON.
Useful for mobile device pairing.

Headers:
- `X-Pairing-Token` (string) — Must match the server's pairing token

Response: `Content-Type: image/png` — A 300x300 QR code PNG

curl:
```bash
curl -H "X-Pairing-Token: my-pairing-token" \
  http://127.0.0.1:8082/api/pairing/qrcode -o pairing.png
```

---

### 17. Static Files

#### GET /web

Auth: **None**

Serve the bundled web UI (from classpath resource `web/index.html`).

Response: HTML page

#### GET /web/{path}

Auth: **None**

Serve static web resources from classpath (`web/{path}`). Supports:
`.html`, `.css`, `.js`, `.png`, `.svg`, `.ico`, `.json`

Path traversal (`..`) is blocked with `403 Forbidden`.

If a specific resource is not found, falls back to `index.html` (SPA support).

---

## Error Response Format

All errors follow a consistent JSON format:

```json
{
  "error": "description of the error"
}
```

| HTTP Status | Meaning |
|-------------|---------|
| `400 Bad Request` | Invalid input, missing parameter, blank goal |
| `401 Unauthorized` | Missing API key |
| `403 Forbidden` | Invalid API key or pairing token |
| `404 Not Found` | Resource not found (session, agent, checkpoint, etc.) |
| `502 Bad Gateway` | Delivery tool execution failure |
| `204 No Content` | Successful deletion |

---

## SSE Streaming Events

Endpoints ending in `/stream` use Server-Sent Events. Each event has:
- `event` — The event type
- `data` — JSON payload

| Event | Payload | Description |
|-------|---------|-------------|
| `status` | `{status, message?}` | Agent lifecycle status updates |
| `token` | `{text}` | Streaming text token |
| `tool_call` | `{id, name, arguments}` | Tool being invoked |
| `tool_result` | `{id, name, success, output, error?}` | Tool execution result |
| `complete` | `{result, turns}` | Agent run complete |
| `error` | `{error}` | Error during execution |
