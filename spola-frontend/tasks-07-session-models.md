# Task 07: Session-Scoped Models

Bind models to sessions and persist the selection.

## Goal
Let each session choose and remember its own model.

## Sub-tasks
- [ ] Add model selection per session.
- [ ] Persist selected model in the backend and local cache.
- [ ] Load the session's model on open.
- [ ] Reflect model changes in the UI.
- [ ] Ensure different sessions can use different models.

## Test required
- Select different models for different sessions.
- Restart the app and verify each selection remains correct.

## Acceptance criteria
- Each session stores its selected model.
- Model selection updates backend and UI.
- Session opens with its assigned model.

[<- Back to Tasks](tasks.md)