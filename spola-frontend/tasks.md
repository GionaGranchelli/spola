# Tasks

## Task 1: Make sessions real
**Description**: Add real session listing, creation, selection, and persistence. Remove reliance on a default session.

**Test required**:
- Create multiple sessions and reopen the app.
- Verify the selected session persists.
- Verify messages are shown under the correct session.

**Acceptance criteria**:
- Session list is visible in the UI.
- User can create and switch sessions.
- Session state survives restart.
- No hardcoded default session is required for normal use.

## Task 2: Make models session-scoped
**Description**: Allow each session to choose and persist its own model.

**Test required**:
- Select different models for different sessions.
- Restart app and verify selections remain correct.

**Acceptance criteria**:
- Each session stores its selected model.
- Model selection updates backend and UI.
- Session opens with its assigned model.

## Task 3: Separate command execution
**Description**: Add explicit command approval and clearer command execution flow.

**Test required**:
- Preview a command before running it.
- Run a command and verify output streams correctly.
- Cancel or reject a command safely.

**Acceptance criteria**:
- Commands are not executed implicitly.
- Approval step exists.
- Command status is visible.
- Output is traceable per session.

## Task 4: Add file control
**Description**: Implement pull/push file APIs and UI entry points.

**Test required**:
- Pull a file from host.
- Push a file to host.
- Confirm errors are reported cleanly.

**Acceptance criteria**:
- File operations are exposed in the UI.
- Backend supports file transfer endpoints.
- File actions are auditable and explicit.

## Task 5: Tighten security
**Description**: Replace ad hoc pairing handling with explicit trust and secure storage.

**Test required**:
- Pair a device successfully.
- Restart and confirm trust state persists securely.
- Revoke pairing and verify access is removed.

**Acceptance criteria**:
- Pairing is explicit and revocable.
- Trust state is stored securely.
- Unsafe actions are gated by auth/approval.

## Task 6: Clean the UI
**Description**: Improve layout, navigation, and empty/error/loading states.

**Test required**:
- Open app with no sessions.
- Trigger loading and error states.
- Use chat, terminal, and file areas separately.

**Acceptance criteria**:
- UI is easy to navigate.
- Chat and terminal are clearly separated.
- Empty/loading/error states are handled.
- The app feels like a control surface, not a demo.