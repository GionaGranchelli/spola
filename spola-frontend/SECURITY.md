# SECURITY.md

## Purpose
Define the security posture for Spola Client.

## Core rules
- Never run commands implicitly.
- Require clear user approval for unsafe actions.
- Pairing must be explicit and revocable.
- Store secrets securely on device.
- Separate trusted backend actions from untrusted UI input.

## Threats to consider
- unauthorized command execution
- session hijacking
- model/session confusion
- file exfiltration
- stale pairing tokens

## Minimum safeguards
- authenticated sessions
- short-lived pairing or revocation path
- clear command preview before execution
- explicit file path confirmation
- audit trail for commands and file ops

## Notes
Security should be visible in the UX, not hidden behind implementation details.