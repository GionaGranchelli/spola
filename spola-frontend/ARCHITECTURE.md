# ARCHITECTURE.md

## Overview
OpenClaw is a Kotlin Multiplatform app with a desktop-hosted backend. It is designed as a session-centric control surface for chat, model selection, bash command execution, and file transfer.

## Goals
- Session-aware chat
- Per-session model selection
- Secure command execution
- Pull/push file workflows
- Compose Multiplatform client
- Ktor-based backend host
- Offline/local persistence via SQLDelight

## Proposed structure

### `shared`
Shared contract and infrastructure layer.
- DTOs and serialization models
- API client helpers
- persistence schema and queries
- common utilities for session/message state

### `backend`
Desktop host and execution layer.
- Ktor server
- session management
- model registry and routing
- bash command execution
- file upload/download endpoints
- SSE or streaming channel for tokens/output
- secure pairing and auth

### `composeApp`
UI layer.
- session list
- model picker
- chat view
- terminal view
- file actions UI
- pairing flow

## Core domain concepts
- `ChatSession`, owns model selection and conversation state
- `Message`, user/assistant/system content
- `ModelInfo`, describes available models and capabilities
- `CommandRequest`, describes shell execution intent
- `FileTransferRequest`, describes pull/push actions
- `SessionAuth`, describes pairing and trust state

## Runtime flow
1. User opens the app.
2. Local cache loads sessions and messages.
3. Backend availability is checked.
4. User selects or creates a session.
5. Session resolves a model.
6. Messages stream through backend.
7. Terminal commands run with explicit approval.
8. File operations are routed through the backend and persisted locally.

## Transport
- Ktor HTTP API for standard requests
- SSE or streaming for token/output delivery
- Local persistence for cache and session metadata

## Recommended implementation order
1. session DTOs and APIs
2. model selection APIs
3. command execution APIs
4. file transfer APIs
5. pairing/auth flow
6. UI wiring

## Design principles
- Keep contracts stable
- Prefer clear boundaries over clever abstractions
- Make unsafe actions explicit
- Favor incremental delivery