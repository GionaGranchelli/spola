# NEXT.md

## 1. Make sessions real
- add session list UI
- create/select session
- persist session selection
- stop relying on default session assumptions

## 2. Make models session-scoped
- persist selected model per session
- load model on session open
- update backend session model endpoint
- reflect model in UI

## 3. Separate command execution
- add explicit command approval step
- show command preview before running
- stream stdout/stderr separately if possible
- record command history per session

## 4. Add file control
- define pull/push API contracts
- implement backend file endpoints
- add UI entry points for file actions

## 5. Tighten security
- replace ad hoc pairing payload handling with real trust flow
- store pairing securely on device
- make auth state explicit

## 6. Clean the UI
- split chat, terminal, and files into distinct areas
- add loading/empty/error states
- improve session navigation

## Suggested order
Do 1 and 2 first, then 3, then 5, then 4 if you want the app to feel real sooner.

## Goal
Move from prototype to a genuine session-based OpenClaw client with model-aware state and controlled execution.