# SESSION.md

## Session concept
A session is the unit of conversation and execution.

## Session owns
- selected model
- selected provider
- selected Spola Client agent/model settings (when provider is Spola Client)
- chat history
- command history
- file operation history
- auth / pairing state

## Session lifecycle
- create
- resume
- update model
- update provider
- update Spola Client agent/model settings
- send message
- run command
- transfer file
- archive or delete

## Notes
- Sessions should be easy to list and switch between.
- Session state should sync cleanly between backend and local cache.
- One session should not leak state into another.
