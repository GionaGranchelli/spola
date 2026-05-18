# API.md

## Purpose
This document defines the intended backend surface for OpenClaw.

## Core endpoints

### Meta
- `GET /meta` backend runtime diagnostics (`version`, `buildTime`, `pid`)

### System
- `GET /system/stream` stream system events (`SESSIONS_CHANGED`, `TRUST_CHANGED`)

### Sessions
- `GET /sessions` list sessions
- `POST /session` create or update a session
- `GET /session/{id}` fetch session details
- `DELETE /session/{id}` delete a session and its messages

### Models
- `GET /models` list available models (Ollama + OpenClaw agents as models)
- `POST /session/{id}/model` set model for a session

### Providers
- `GET /providers` list available chat providers
- `POST /session/{id}/provider` set provider for a session

### OpenClaw provider settings
- `GET /openclaw/options` list available OpenClaw agents from `~/.openclaw/openclaw.json`
- `GET /session/{id}/openclaw` fetch per-session OpenClaw settings
- `POST /session/{id}/openclaw` update per-session OpenClaw settings (`agentId`, `modelId`, `mode`, `thinking`)

### Chat
- `POST /session/{id}/message` send a message
- `GET /session/{id}/stream` stream assistant output (SSE)

### Commands
- `POST /bash/preview` create command preview and approval id
- `POST /bash/approve` approve a pending command
- `POST /bash` execute an approved command
- `GET /bash/{id}/stream` stream command output (SSE)
- `POST /command/{id}/input` (Future) send stdin to a running process

### Files
- `GET /session/{id}/files` list files uploaded to a specific session
- `POST /session/{id}/upload` upload a file to a session (multipart)
- `DELETE /session/{id}/files/{fileId}` (Plan) delete an uploaded file
- `GET /files/{id}/content` download/read raw file content
- `GET /files/root` get current host file root
- `POST /files/pull` pull a file from host system to client (direct path)
- `POST /files/push` push a file from client to host system (direct path)

### Pairing
- `POST /pairing/request` start pairing
- `POST /pairing/confirm` confirm trust
- `POST /trust/rotate` rotate active trust token
- `POST /trust/revoke` revoke active trust token

## Notes
- Streaming should be explicit and observable via SSE.
- Unsafe operations (bash, file writes) should require clear user intent and audit logging.
- Session IDs and File IDs are stable UUIDs.
- `attachments` in chat messages are references to stored file IDs.
