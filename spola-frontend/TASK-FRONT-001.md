# OPENCLAW-FRONT: Adapt openclaw-app as Golem Dashboard

## Goal
Clean up openclaw-app and rewire it as a dashboard for Golem.

## Requirements

### 1. Clean up shared DTOs (shared/src/commonMain/.../models/Models.kt)
- REMOVE: `OpenClawAgentInfo`, `OpenClawOptions`, `OpenClawSessionSettings`, `PairingInfo`, `VoiceSettings`, `SynthesizeRequest`, `TranscriptionResponse`
- KEEP: `ChatSession`, `Message`, `MessageRole`, `ModelInfo`, `ProviderInfo`, `BashCommandRequest`, `BashCommandPreview`, `BashCommandResponse`, `CommandStreamEvent`, `CommandStreamType`, `CommandStatus`, `FileMetadata`, `AuditEvent`, `TrustState`, `SystemEvent`, `SystemEventType`, `StreamEvent`, `EventType`, `FileTransferRequest`, `FileTransferResponse`, `FileTransferDirection`, `SelectedSessionState`, `BackendMeta`, `TrustRotationResponse`
- ADD: Golem-specific DTOs matching Golem's `/api/` models
  - `AgentRunRequest(goal, model?, persona?)`
  - `AgentRunResponse(result, turns)`
  - `ScheduledJobResponse(id, name, goal, cronExpression, enabled, createdAt, nextRunAt)`
  - `HealthResponse(status, version)`
  - `ToolInfo(name, description, parameters)`

### 2. Rewire HTTP client (shared/src/commonMain/.../network/OpenClawClient.kt)
- Rename file to `GolemClient.kt`
- Change base URL default to `http://localhost:8082`
- Update all method calls to Golem's API endpoints
- Remove: `getOpenClawOptions()`, `getSessionOpenClawSettings()`, `setSessionOpenClawSettings()`, `transcribe()`, `synthesize()`, `pullFile()`, `pushFile()`
- Add: `runAgent(goal, model?)`, `getTools()`, `searchMemory(query)`, `deleteMemory(key)`

### 3. Create Golem-specific models DTOs file
- `shared/src/commonMain/kotlin/dev/golem/models/GolemModels.kt`
- Mirror Golem's backend ApiModels.kt

### 4. Update Decompose components (composeApp/.../decompose/)
- Rename package from `it.openclaw` to `dev.golem.app`
- Remove OpenClaw-specific logic from DefaultDashboardComponent, DefaultChatComponent
- Add Golem-native logic: agent runs, tool list, scheduler jobs view

### 5. Clean up composeApp UI (composeApp/.../App.kt)
- Rename package
- Replace OpenClaw settings pane with Golem settings (agent config, model, workspace)
- Keep dashboard shell, session pane, chat pane, terminal pane
- Add job list pane

### 6. Update build files
- Rename group from `it.openclaw` to `dev.golem`
- Update AGENTS.md

### 7. Compilation
- All modules must compile (shared, backend, composeApp)
- Backend module may be removed if not needed (Golem provides its own backend)

## Files to modify
```
shared/src/commonMain/kotlin/it/openclaw/models/Models.kt      — MODIFY
shared/src/commonMain/kotlin/it/openclaw/network/OpenClawClient.kt — MODIFY/RENAME
shared/src/commonMain/kotlin/                                    — NEW: dev/golem/models/
composeApp/src/commonMain/kotlin/it/openclaw/app/                — MODIFY all files
composeApp/src/desktopMain/kotlin/                              — MODIFY
backend/src/main/kotlin/it/openclaw/backend/                    — MAYBE DELETE
```
