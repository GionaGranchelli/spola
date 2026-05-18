# Task 03: Ktor Backend Implementation

Build the core Ktor server that runs on the desktop host.

## Goal
Implement the central API and logic for chat handling and bash command execution.

## Sub-tasks
- [x] Implement Ktor server initialization with custom host/port settings.
- [x] Set up the backend SQLite database (via Exposed/SQLDelight).
- [x] Create API endpoints for:
    - `GET /models` - List available LLM models.
    - `POST /session` - Create or update a chat session.
    - `GET /sessions` - List existing chat sessions.
- [x] Implement Server-Sent Events (SSE) for:
    - Real-time LLM token streaming.
    - Live bash command stdout/stderr streaming.
- [x] Integrate basic LLM interaction (simulated or via an existing provider like Ollama).

[<- Back to Roadmap](roadmap.md)
