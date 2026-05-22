# Spola Dashboard CRUD Plan

Target frontend: `spola-backend-core/src/main/resources/web/index.html` (`1727` lines, vanilla JS SPA)

This revision is based on the actual API server wiring in `spola-backend-core/src/main/kotlin/dev/spola/api/SpolaApiServer.kt:132-157`, the route files under `spola-backend-core/src/main/kotlin/dev/spola/api/routes/`, and the DTOs in `spola-backend-core/src/main/kotlin/dev/spola/api/ApiModels.kt`.

The main correction is scope: several dashboard screens already exist in `index.html`, but some of the APIs they call do not exist at all, and one visible route mismatch is still blocking proper CRUD in the Tools tab.

## Phase 0: Backend Infrastructure

These are the backend changes that must exist before the blocked frontend flows can work.

### 0.1 Add `enabled` to `GET /api/tools`

Why first:
- The current Tools UI can list tools, but it cannot render current toggle state from `GET /api/tools`.
- `GET /api/tools/{name}` already exposes `enabled`, but the list response does not.

Files:
- `spola-backend-core/src/main/kotlin/dev/spola/api/ApiModels.kt`
- `spola-backend-core/src/main/kotlin/dev/spola/api/routes/ToolRoutes.kt`

Current API shape:
- `GET /api/tools` -> `ToolsResponse`
- `ToolsResponse.tools[]` -> `ToolSchemaResponse { name, description, parameters }`
- `ToolSchemaResponse` is defined at `ApiModels.kt:89-93`
- The list conversion is `Tool.toSchemaResponse()` at `ApiModels.kt:281-289`

Required backend change:
- Add `val enabled: Boolean` to `ToolSchemaResponse`
- Update `Tool.toSchemaResponse()` to accept enabled state, or move list mapping into `ToolRoutes.kt`
- Update `apiToolRoutes()` list handler to include `toolRegistry.isEnabled(tool.name)`

Frontend dependency:
- `index.html:1032-1058` (`loadTools()`)
- `index.html:548-553` (Tools tab container)

Request/response after change:
- `GET /api/tools`
```json
{
  "tools": [
    {
      "name": "memory_search",
      "description": "Search memory entries",
      "enabled": true,
      "parameters": {
        "type": "object",
        "properties": {},
        "required": []
      }
    }
  ]
}
```

Line estimate:
- Backend: `10-20` lines

### 0.2 Build config read/save infrastructure and expose `/api/config`

Why first:
- The Settings tab currently calls `GET /api/config` at `index.html:1546`, but no `/api/config` route is registered in `SpolaApiServer.kt:144-155`.
- `SpolaConfig` is only a data class in `spola-backend-core/src/main/kotlin/dev/spola/SpolaConfig.kt:8-60`.
- There is no general Spola YAML config loader/saver in `spola-backend-core`.

Files:
- Create `spola-backend-core/src/main/kotlin/dev/spola/config/SpolaConfigFileStore.kt`
- Create `spola-backend-core/src/main/kotlin/dev/spola/api/routes/ConfigRoutes.kt`
- Update `spola-backend-core/src/main/kotlin/dev/spola/api/ApiModels.kt`
- Update `spola-backend-core/src/main/kotlin/dev/spola/api/SpolaApiServer.kt`
- Reuse Jackson YAML dependency already present in `SkillParser.kt` and `SkillLoader.kt`

Scope for this phase:
- `GET /api/config`: read-only effective config for the dashboard
- `POST /api/config/save`: persist editable fields to YAML

Recommended response model:
- Add explicit DTOs instead of serializing `SpolaConfig` directly
- Include only fields the dashboard can actually round-trip
- Include `effectiveConfigPath` because the frontend already expects it

Minimum read shape to support the current Settings UI:
```json
{
  "effectiveConfigPath": "/home/user/.spola/config.yaml",
  "model": "gpt-4o",
  "provider": "openai",
  "maxTurns": 25,
  "temperature": null,
  "maxTokens": null,
  "workingDirectory": ".",
  "personaPath": null,
  "memoryDbPath": "./.spola/memory.db",
  "schedulerDbPath": "./.spola/scheduler.db",
  "kanbanDbPath": "./.spola/kanban.db",
  "checkpointDbPath": "./.spola/checkpoint.db",
  "jvmIndexDbPath": "./.spola/jvm-index.db",
  "jvmIndexAutoRefresh": true,
  "apiKey": null,
  "pairingToken": null,
  "telegramBotToken": null,
  "emailSmtpHost": null,
  "emailSmtpPort": 587,
  "emailUsername": null,
  "emailFrom": null,
  "ttsProvider": "edge",
  "elevenlabsVoiceId": "21m00Tcm4TlvDq8ikWAM",
  "otelEnabled": false,
  "otelEndpoint": null,
  "otelServiceName": "spola",
  "metricsEnabled": true,
  "pluginsEnabled": true,
  "pluginsDir": "...",
  "agentsDir": "...",
  "agentsDbPath": "./.spola/agents.db",
  "defaultAgentId": null,
  "sessionsDbPath": "./.spola/sessions.db",
  "compressionEnabled": true,
  "autoCheckpoint": true,
  "insecure": false
}
```

Notes:
- The current Settings UI references keys that do not exist in `SpolaConfig`, including `unsafe`, `email.*`, `tts.*`, `architectMode.enabled`, `architectMode.architectModel`, `architectMode.architectProvider`, `architectMode.editorModel`, `architectMode.editorProvider`, and renamed fields like `workdir` instead of `workingDirectory`.
- Phase 1 must normalize the frontend field map to real `SpolaConfig` fields before save can work.

Line estimate:
- Backend: `140-220` lines

### 0.3 Build providers config REST API

Why first:
- Providers tab calls `GET /api/providers` at `index.html:1201` and `POST /api/provider/test` at `index.html:1263`.
- No provider routes are registered in `SpolaApiServer.kt:144-155`.

Files:
- Create `spola-backend-core/src/main/kotlin/dev/spola/api/routes/ProviderRoutes.kt`
- Update `spola-backend-core/src/main/kotlin/dev/spola/api/ApiModels.kt`
- Update `spola-backend-core/src/main/kotlin/dev/spola/api/SpolaApiServer.kt`
- Extend the new config store from `Phase 0.2`
- Inspect `spola-backend-core/src/main/kotlin/dev/spola/factory/ProviderResolver.kt` and `spola-backend-core/src/main/kotlin/dev/spola/agent/ProviderConfig.kt` when implementing, since provider definitions should align with runtime resolution

Scope for this phase:
- `GET /api/providers`
- `POST /api/providers`
- `DELETE /api/providers/{name}`
- `POST /api/provider/test`

Recommended API shape:
- `GET /api/providers`
```json
{
  "providers": [
    {
      "name": "openai",
      "type": "openai",
      "baseUrl": null,
      "models": ["gpt-4o"],
      "isBuiltin": true
    }
  ]
}
```
- `POST /api/provider/test`
```json
{
  "baseUrl": "http://localhost:8090/v1",
  "apiKey": "..."
}
```
```json
{
  "success": true,
  "status": 200,
  "error": null
}
```

Line estimate:
- Backend: `120-200` lines

## Phase 1: Frontend — Features with Complete Backend

Start with the flows whose backend already works end-to-end today.

### 1.1 Chat tab: load real session history on session switch

Files:
- `spola-backend-core/src/main/resources/web/index.html`

Existing frontend anchors:
- Session selector: `index.html:530-535`
- Session loading: `index.html:789-829`
- Session switching: `index.html:831-834`
- Message renderer helpers: `index.html:836-936`

Verified backend:
- `GET /api/sessions` -> bare array of `SessionInfo`
- `POST /api/session` -> `SessionInfo`
- `GET /api/session/{id}` -> `SessionInfo`
- `DELETE /api/session/{id}` -> `204 No Content`
- `GET /api/session/{id}/messages` -> bare array of `CheckpointMessageResponse { role, content }`
- `POST /api/session/{id}/model` -> `SessionInfo`
- `POST /api/session/{id}/run` -> `AgentRunResponse { result, turns }`
- `POST /api/session/{id}/run/stream` -> SSE

Verified shapes:
- `SessionInfo`: `ApiModels.kt:240-248`
- `CheckpointMessageResponse`: `ApiModels.kt:212-216`
- `AgentRunResponse`: `ApiModels.kt:35-39`

Frontend work:
- Replace the placeholder-only `switchSession(id)` at `index.html:831-834`
- Fetch `/api/session/${id}/messages`
- Render each message via existing role styling instead of building a new renderer
- Keep the existing `api()` helper (`index.html:749-762`), `escapeHtml()` (`index.html:1180-1183`) only for any HTML-string paths, and current loading bar/spinner patterns

Line estimate:
- Frontend: `35-60` lines

### 1.2 Memory tab: add delete action

Files:
- `spola-backend-core/src/main/resources/web/index.html`

Existing frontend anchors:
- Memory tab markup: `index.html:555-565`
- Memory CSS: `index.html:265-283`
- `loadMemory()`: `index.html:1061-1087`

Verified backend:
- `GET /api/memory?q=...` -> `MemoryEntriesResponse { entries, query }`
- `DELETE /api/memory/{key}` -> `DeleteMemoryResponse { deleted, key, message }`

Verified shapes:
- `MemoryEntriesResponse`: `ApiModels.kt:109-113`
- `MemoryEntryResponse`: `ApiModels.kt:115-121`
- `DeleteMemoryResponse`: `ApiModels.kt:123-128`

Frontend work:
- Add a delete button per `.mem-card`
- Use `encodeURIComponent(key)` in the path
- After delete, call `loadMemory()` again
- Reuse the existing spinner and error rendering pattern already used in `loadMemory()`

Line estimate:
- Frontend: `25-45` lines

### 1.3 Scheduler tab: create and delete jobs

Files:
- `spola-backend-core/src/main/resources/web/index.html`

Existing frontend anchors:
- Scheduler tab container: `index.html:567-572`
- Scheduler CSS: `index.html:285-318`
- `loadJobs()`: `index.html:1090-1116`

Verified backend:
- `GET /api/jobs` -> `JobsResponse { jobs }`
- `POST /api/jobs` with `CreateJobRequest { name, cronExpression, goal, enabled }` -> created `ScheduledJobResponse`
- `DELETE /api/jobs/{id}` -> `DeleteJobResponse { removed, id, message }`

Verified shapes:
- `CreateJobRequest`: `ApiModels.kt:51-57`
- `JobsResponse`: `ApiModels.kt:59-62`
- `ScheduledJobResponse`: `ApiModels.kt:64-74`
- `DeleteJobResponse`: `ApiModels.kt:76-81`

Frontend work:
- Add an inline create form above `#jobs-list`
- Submit through `api('/api/jobs', { method: 'POST', body: JSON.stringify(...) })`
- Add delete buttons on job cards
- Do not add edit/enable toggle in this phase because no `PUT /api/jobs/{id}` exists

Frontend patterns to keep:
- `api()`
- `escapeHtml()`
- `<span class="loading-spinner"></span>` for load state

Line estimate:
- Frontend: `70-110` lines

### 1.4 New Agents tab: list/create/update/delete/run

Files:
- `spola-backend-core/src/main/resources/web/index.html`

Verified backend:
- `GET /api/agents` -> bare array of `AgentDefinitionResponse`
- `GET /api/agents/{id}` -> `AgentDefinitionResponse`
- `POST /api/agents` with `AgentCreateRequest` -> `201 Created` + `AgentDefinitionResponse`
- `PUT /api/agents/{id}` with `AgentUpdateRequest` -> `AgentDefinitionResponse`
- `DELETE /api/agents/{id}` -> `204 No Content` or `404 { error }`
- `POST /api/agents/run` with `AgentRunAgentRequest { agentId, goal }` -> `{ agentId, result }`

Verified shapes:
- `AgentDefinitionResponse`: `ApiModels.kt:310-331`
- `AgentCreateRequest`: `ApiModels.kt:333-354`
- `AgentUpdateRequest`: `ApiModels.kt:356-375`
- `AgentRunAgentRequest`: `ApiModels.kt:29-33`

Frontend work:
- Add sidebar entry and new tab container in `index.html`
- Follow the same load-on-tab-switch pattern used by `switchTab()` at `index.html:731-746`
- Use a list + modal pattern similar in size to the existing Providers modal block (`index.html:591-622`)
- Start with the fields that actually exist in the DTOs; do not invent provider/profile fields

Line estimate:
- Frontend: `180-260` lines

### 1.5 New Workflows tab: list and run

Files:
- `spola-backend-core/src/main/resources/web/index.html`

Verified backend:
- `GET /api/workflows` -> map payload:
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
- `POST /api/workflows/run` with `WorkflowRunRequest { workflowName, goal }` -> `{ workflowName, result }`

Verified shape:
- `WorkflowRunRequest`: `ApiModels.kt:384-388`

Frontend work:
- Add sidebar entry and new tab
- Render simple cards
- Add a modal or inline form for `goal`
- Show returned `result` in a `<pre>` or assistant-style card

Line estimate:
- Frontend: `70-110` lines

### 1.6 New Checkpoints tab: list, filter, diff view, resume preview

Files:
- `spola-backend-core/src/main/resources/web/index.html`

Verified backend:
- `GET /api/checkpoint` -> `CheckpointListResponse { checkpoints }`
- `GET /api/checkpoint/{id}/diff` -> `CheckpointDiffResponse { id, sessionId, turnNumber, createdAt, diff }`
- `GET /api/checkpoint/session/{sessionId}/diffs` -> `CheckpointListResponse { checkpoints }`
- `GET /api/checkpoint/resume/{session_id}` -> `CheckpointResumeResponse { sessionId, messageCount, messages }`

Verified shapes:
- `CheckpointListResponse`: `ApiModels.kt:191-194`
- `CheckpointItemResponse`: `ApiModels.kt:196-203`
- `CheckpointDiffResponse`: `ApiModels.kt:218-225`
- `CheckpointResumeResponse`: `ApiModels.kt:205-210`

Frontend work:
- Add sidebar entry and new tab
- Reuse `state.sessions` as the session filter source
- Show diff in `<pre>` with `escapeHtml(diff || '')`
- Keep resume as read-only preview in this phase; actual chat-session replacement can stay deferred

Line estimate:
- Frontend: `100-150` lines

### 1.7 New Delivery tab: test Telegram and email sends

Files:
- `spola-backend-core/src/main/resources/web/index.html`

Verified backend:
- `POST /api/deliver/telegram` with `TelegramSendRequest { chatId, text }` -> `DeliveryResponse { success, message }`
- `POST /api/deliver/email` with `EmailSendRequest { to, subject, body }` -> `DeliveryResponse { success, message }`

Verified shapes:
- `TelegramSendRequest`: `ApiModels.kt:170-174`
- `EmailSendRequest`: `ApiModels.kt:176-181`
- `DeliveryResponse`: `ApiModels.kt:183-187`

Frontend work:
- Add sidebar entry and tab
- Two simple forms with inline results
- Use the existing button/loading/error text conventions

Line estimate:
- Frontend: `80-120` lines

## Phase 2: Features Requiring New Backend + Frontend

Group these by dependency because each UI is currently blocked by missing server support.

### 2.A Depends on Phase 0.1: Tools tab true toggle CRUD

Files:
- Backend: `spola-backend-core/src/main/kotlin/dev/spola/api/ApiModels.kt`
- Backend: `spola-backend-core/src/main/kotlin/dev/spola/api/routes/ToolRoutes.kt`
- Frontend: `spola-backend-core/src/main/resources/web/index.html`

Verified backend already present:
- `POST /api/tools/{name}/toggle` -> `{ name, enabled }`
- `PUT /api/tools/{name}/toggle` with `ToolToggleRequest { enabled }` -> `{ name, enabled }`
- `GET /api/tools/{name}` -> `ToolDetailResponse { name, description, parameters[], enabled }`

Verified shapes:
- `ToolDetailResponse`: `ApiModels.kt:445-450`
- `ToolToggleRequest`: `ApiModels.kt:452-455`

Frontend work after Phase 0.1:
- Extend `loadTools()` at `index.html:1032-1058`
- Render a checkbox/toggle using the existing `.toggle-switch` CSS already defined in the file
- Prefer `PUT /api/tools/{name}/toggle` so the UI can set explicit state

Line estimate:
- Backend: included in `0.1`
- Frontend: `35-60` lines

### 2.B Depends on Phase 0.2: Settings tab repair and save

Files:
- Backend: new config store and route files from `0.2`
- Frontend: `spola-backend-core/src/main/resources/web/index.html`

Current frontend problems to fix:
- Calls nonexistent `GET /api/config` at `index.html:1546`
- Save path is only a console stub at `index.html:1681-1718`
- Multiple frontend field keys do not match `SpolaConfig`

Required frontend cleanup:
- Replace fake nested config accesses like `config.email?.smtpHost` and `config.tts?.provider` with real DTO fields
- Rename `workdir` -> `workingDirectory`
- Rename `persona` -> `personaPath`
- Rename `memoryDb` -> `memoryDbPath`, `schedulerDb` -> `schedulerDbPath`, `agentsDb` -> `agentsDbPath`, etc.
- Remove unsupported fields unless they are explicitly added to the new config DTO

Frontend patterns:
- Keep `api()`
- Keep loading spinner pattern
- Keep `data-key` collection approach in `saveSettings()`, but align keys to real DTO names

Line estimate:
- Backend: included in `0.2`
- Frontend: `90-150` lines

### 2.C Depends on Phase 0.3: Providers tab CRUD

Files:
- Backend: new provider route/model files from `0.3`
- Frontend: `spola-backend-core/src/main/resources/web/index.html`

Current frontend anchors:
- Providers tab markup: `index.html:579-624`
- `loadProviders()`: `index.html:1196-1233`
- `testProvider()`: `index.html:1249-1278`
- `deleteProvider()`: `index.html:1287-1292`

Frontend work after backend exists:
- Keep the existing modal structure
- Wire create, test, and delete to real endpoints
- Reuse existing provider cards instead of redesigning the tab

Line estimate:
- Backend: included in `0.3`
- Frontend: `60-110` lines

## Deferred

These items should be explicitly out of scope for this plan revision.

### Skills dashboard UI

Why deferred:
- The skill system exists (`SkillCatalog`, `SkillRepository`, `SkillIndexer`, `SkillTools`, `ToolRegistry.activateSkill()`), but there is no REST surface today.
- It is buildable, but it is not required to deliver CRUD for the existing dashboard tabs.

Relevant source:
- `spola-backend-core/src/main/kotlin/dev/spola/skill/SkillCatalog.kt`
- `spola-backend-core/src/main/kotlin/dev/spola/skill/SkillRepository.kt`
- `spola-backend-core/src/main/kotlin/dev/spola/skill/SkillIndexer.kt`
- `spola-backend-core/src/main/kotlin/dev/spola/skill/SkillTools.kt`
- `spola-backend-core/src/main/kotlin/dev/spola/Tool.kt`

### Personas dashboard UI

Why deferred:
- `PersonaLoader` exists, but it only loads a single persona file or fallback text.
- There is no persona store, no persona CRUD, and no REST API to support a dashboard feature.

Relevant source:
- `spola-backend-core/src/main/kotlin/dev/spola/persona/PersonaLoader.kt`

### Jobs update/toggle

Why deferred:
- Only `GET /api/jobs`, `POST /api/jobs`, and `DELETE /api/jobs/{id}` exist.
- No `PUT /api/jobs/{id}` or toggle endpoint exists, so full job update CRUD is out of scope for Phase 1.

### Resume-from-checkpoint into live chat state

Why deferred:
- `GET /api/checkpoint/resume/{session_id}` returns message payloads, but the dashboard has no session replacement flow yet.
- Read-only inspection is enough for the first checkpoints tab.

### Pairing screen changes

Why deferred:
- Pairing routes are already present and the current pairing screen works against them.
- This plan is about dashboard CRUD, not connection UX.

## Delivery Order

1. Phase 0.1 so the Tools tab can become actionable.
2. Phase 0.2 because Settings is currently calling a nonexistent API and needs a real config store.
3. Phase 1.1 through 1.3 to finish CRUD in tabs that already exist.
4. Phase 1.4 through 1.7 to expose working backend domains already registered in `SpolaApiServer`.
5. Phase 0.3 plus Phase 2.C to repair Providers.
6. Phase 2.A and 2.B to finish Tools and Settings after their backend dependencies land.

## Rough Line Budget

- Phase 0 backend: `270-440` lines
- Phase 1 frontend: `560-855` lines
- Phase 2 frontend: `185-320` lines

That keeps the work concentrated in the existing SPA and a small number of route/model files, without assuming APIs that the codebase does not actually expose today.
