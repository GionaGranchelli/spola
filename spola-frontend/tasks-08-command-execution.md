# Task 08: Command Execution

Add explicit, controlled command execution.

## Goal
Make bash execution safe, visible, and session-aware.

## Sub-tasks
- [ ] Add an explicit approval step before execution.
- [ ] Show a command preview before running.
- [ ] Stream stdout and stderr output.
- [ ] Track command status per session.
- [ ] Store command history.

## Test required
- Preview a command before running it.
- Run a command and verify output streams correctly.
- Cancel or reject a command safely.

## Acceptance criteria
- Commands are not executed implicitly.
- Approval step exists.
- Command status is visible.
- Output is traceable per session.

[<- Back to Tasks](tasks.md)