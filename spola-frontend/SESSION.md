# SESSION.md

## Session concept
A session is the unit of conversation and execution.

## Session owns
- selected model
- selected provider
- selected OpenClaw agent/model settings (when provider is OpenClaw)
- chat history
- command history
- file operation history
- auth / pairing state

## Session lifecycle
- create
- resume
- update model
- update provider
- update OpenClaw agent/model settings
- send message
- run command
- transfer file
- archive or delete

## Notes
- Sessions should be easy to list and switch between.
- Session state should sync cleanly between backend and local cache.
- One session should not leak state into another.
