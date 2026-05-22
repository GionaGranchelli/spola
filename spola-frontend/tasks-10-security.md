# Task 10: Security and Trust

Harden pairing, authentication, and approvals.

## Goal
Make trust explicit, revocable, and secure.

## Sub-tasks
- [ ] Replace ad hoc pairing handling with a real trust flow.
- [ ] Store pairing securely on device.
- [ ] Make auth state explicit.
- [ ] Gate unsafe actions behind approvals.
- [ ] Add revocation handling.

## Test required
- Pair a device successfully.
- Restart and confirm trust state persists securely.
- Revoke pairing and verify access is removed.

## Acceptance criteria
- Pairing is explicit and revocable.
- Trust state is stored securely.
- Unsafe actions are gated by auth/approval.

[<- Back to Tasks](tasks.md)