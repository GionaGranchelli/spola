# GAPS.md

## Keep
- KMP monorepo structure (`shared`, `backend`, `composeApp`)
- Compose Multiplatform + Decompose
- Ktor backend
- SQLDelight persistence
- Session/message/model DTO direction
- SSE-based streaming direction
- Pairing concept

## Refactor
- Replace implicit session assumptions with real session selection.
- Split chat UI and terminal UI more cleanly.
- Move execution logic behind explicit capability APIs.
- Persist selected model per session, not only in memory.
- Make client state more than a global `AppContext` singleton.
- Separate transport contracts from UI orchestration.

## Build next
1. session list and creation flow
2. per-session model selection + persistence
3. authenticated pairing + trust storage
4. explicit command approval flow
5. file pull/push APIs and UI
6. clean streaming contract for chat and command output
7. error/loading/empty states across the app

## Current reality
The app is already a usable prototype, but it is still closer to a single-session chat/shell demo than a multi-session OpenClaw control surface.

## Target state
A session-centric client where each session can independently choose a model, chat, run commands, and transfer files through a secure backend control plane.