# SPOLA CLIENT-FRONT: Adapt spola-frontend as Spola Client

## Goal
Clean up spola-frontend and rewire it as a dashboard for Spola.

## Requirements

### 1. Clean up shared DTOs (shared/src/commonMain/.../models/Models.kt)
- REMOVE: `SpolaAgentInfo`, `SpolaOptions`, `SpolaSessionSettings`, `PairingInfo`, `VoiceSettings`, `SynthesizeRequest`, `TranscriptionResponse`
- KEEP: `ChatSession`, `Message`, `MessageRole`, `ModelInfo`, `ProviderInfo`, `BashCommandRequest`, `BashCommandPreview`, `BashCommandResponse`, `CommandStreamEvent`, `CommandStreamType`, `CommandStatus`, `FileMetadata`, `AuditEvent`, `TrustState`, `SystemEvent`, `SystemEventType`, `StreamEvent`, `EventType`, `FileTransferRequest`, `FileTransferResponse`, `FileTransferDirection`, `SelectedSessionState`, `BackendMeta`, `TrustRotationResponse`
- ADD: Spola-specific DTOs matching Spola's `/api/` models
  - `AgentRunRequest(goal, model?, persona?)`
  - `AgentRunResponse(result, turns)`
  - `ScheduledJobResponse(id, name, goal, cronExpression, enabled, createdAt, nextRunAt)`
  - `HealthResponse(status, version)`
  - `ToolInfo(name, description, parameters)`

### 2. Rewire HTTP client (shared/src/commonMain/.../network/SpolaClient.kt)
- Rename file to `SpolaClient.kt`
- Change base URL default to `http://localhost:8082`
- Update all method calls to Spola's API endpoints
- Remove: `getSpolaOptions()`, `getSessionSpolaSettings()`, `setSessionSpolaSettings()`, `transcribe()`, `synthesize()`, `pullFile()`, `pushFile()`
- Add: `runAgent(goal, model?)`, `getTools()`, `searchMemory(query)`, `deleteMemory(key)`

### 3. Create Spola-specific models DTOs file
- `shared/src/commonMain/kotlin/dev/spola/models/SpolaModels.kt`
- Mirror Spola's backend ApiModels.kt

### 4. Update Decompose components (composeApp/.../decompose/)
- Rename package from `it.spola` to `dev.spola.app`
- Remove Spola Client-specific logic from DefaultDashboardComponent, DefaultChatComponent
- Add Spola-native logic: agent runs, tool list, scheduler jobs view

### 5. Clean up composeApp UI (composeApp/.../App.kt)
- Rename package
- Replace Spola Client settings pane with Spola settings (agent config, model, workspace)
- Keep dashboard shell, session pane, chat pane, terminal pane
- Add job list pane

### 6. Update build files
- Rename group from `it.spola` to `dev.spola`
- Update AGENTS.md

### 7. Compilation
- All modules must compile (shared, backend, composeApp)
- Backend module may be removed if not needed (Spola provides its own backend)

## Files to modify
```
shared/src/commonMain/kotlin/dev/spola/app/models/Models.kt      — MODIFY
shared/src/commonMain/kotlin/dev/spola/app/network/SpolaClient.kt — MODIFY/RENAME
shared/src/commonMain/kotlin/                                    — NEW: dev/spola/models/
composeApp/src/commonMain/kotlin/dev/spola/app/app/                — MODIFY all files
composeApp/src/desktopMain/kotlin/                              — MODIFY
backend/src/main/kotlin/dev/spola/app/backend/                    — MAYBE DELETE
```
