# COMMANDS.md

## Purpose
Describe how bash command execution should behave in OpenClaw.

## Principles
- Commands are explicit user actions.
- Commands should be shown before execution when possible.
- Output should stream in real time.
- Success, failure, and cancellation should be visible.
- Dangerous actions should be clearly labeled.

## Required data
- command id
- session id
- shell command
- working directory
- environment overrides, if any
- approval state
- execution status

## Suggested states
- pending
- approved
- running
- completed
- failed
- canceled

## Notes
Keep command execution boring, traceable, and safe.