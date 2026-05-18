# OpenClaw App

Kotlin Multiplatform client for OpenClaw.

## What it is
A session-based app for:
- chatting with different models per session
- running bash commands through a controlled backend
- pulling and pushing files
- syncing local state across devices

## Modules
- `shared` - contracts, DTOs, persistence, shared networking
- `backend` - desktop host backend
- `composeApp` - multiplatform UI

## Current direction
Backend-first, UI-thin, session-centric.

## Next milestones
1. session API
2. per-session model selection
3. command execution
4. file transfer
5. secure pairing

## Development
Use Gradle as the source of truth for building and running the app.