# Task 06: Core Sessions

Build real session management for Spola Client.

## Goal
Make sessions first-class across UI, backend, and persistence.

## Sub-tasks
- [ ] Implement session list UI.
- [ ] Implement session creation and selection.
- [ ] Persist selected session locally.
- [ ] Remove dependence on a default hardcoded session.
- [ ] Ensure messages are stored and displayed under the correct session.

## Test required
- Create multiple sessions and restart the app.
- Verify the selected session persists.
- Verify session history stays isolated.

## Acceptance criteria
- Session list is visible.
- User can create and switch sessions.
- Session state survives restart.
- No hardcoded default session is needed for normal use.

[<- Back to Tasks](tasks.md)