# Spola Client Roadmap

Spola Client is a Kotlin Multiplatform client for a session-based control plane: chat with different models, run bash commands, and pull/push files through a secure backend host.

## Product vision
- **Session-centric**: every chat session is first-class.
- **Model-aware**: each session can choose its own model.
- **Controlled execution**: bash commands are explicit and approval-based.
- **File-capable**: pull/push files through the backend.
- **Secure**: pairing, trust, and auditability are built in.
- **Cross-platform**: Android, iOS, Desktop via Compose Multiplatform.

## Documentation set
- `AGENTS.md` - working rules and repo priorities
- `ARCHITECTURE.md` - system shape and boundaries
- `API.md` - intended backend surface
- `MODEL.md` - model concepts and behavior
- `SESSION.md` - session lifecycle and ownership
- `COMMANDS.md` - command execution rules
- `FILES.md` - file transfer behavior
- `SECURITY.md` - trust and safety posture
- `DATA-FLOW.md` - end-to-end runtime path
- `GAPS.md` - current gap analysis
- `NEXT.md` - recommended implementation order
- `.copilot/copilot-instructions.md` - coding guidance

## Phases

### Phase 1: Core contracts
Define the data model for sessions, messages, models, commands, files, and trust.
- sessions and messages
- per-session model selection
- command and file transfer DTOs
- persistence schema updates

### Phase 2: Backend control plane
Make the desktop backend the real execution host.
- session APIs
- model routing
- streaming chat output
- command execution endpoints
- file transfer endpoints
- pairing/auth flow

### Phase 3: Frontend session UX
Make the app feel like a real multi-session client.
- session list and creation
- model picker per session
- chat panel
- terminal panel
- file actions panel
- clear loading/error states

### Phase 4: Security and trust
Harden the control plane.
- secure pairing
- stored trust state
- explicit approvals for unsafe actions
- audit trail for execution

### Phase 5: Polish and sync
Make it pleasant and dependable.
- local cache sync
- better streaming UX
- navigation cleanup
- persistence recovery
- error handling and empty states

## Immediate priorities
1. remove default-session assumptions
2. persist session/model selection
3. separate chat from shell execution
4. add file transfer contracts
5. lock down pairing and approval flow

## Current status
The repo is already a prototype with good scaffolding, KMP structure, SQLDelight, Ktor, and basic streaming. It is not yet a full multi-session Spola Client.

## Goal
Turn the prototype into a secure session-based app where each session can independently chat, choose a model, run commands, and transfer files.