# DATA-FLOW.md

## Purpose
Describe the main data path through OpenClaw.

## Flow
1. Client opens app.
2. Local cache loads sessions and messages.
3. Backend availability is checked.
4. User selects a session or creates one.
5. Session resolves a model.
6. Messages are sent to backend.
7. Backend streams assistant output back.
8. User may run commands or transfer files.
9. Results are persisted locally and reflected in UI.

## Key boundaries
- UI collects intent.
- Shared layer defines contracts.
- Backend performs execution.
- Local cache mirrors useful state.

## Notes
The app should stay understandable if you trace one session from UI to backend and back.