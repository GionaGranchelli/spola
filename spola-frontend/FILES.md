# FILES.md

## Purpose
Describe file management and transfer behavior for OpenClaw.

## Operations

### 1. Host File Access (Direct)
Accessing files directly on the host machine running the OpenClaw backend.
- **Pull**: Retrieve a file from a host path. Content is saved to the client's local downloads.
- **Push**: Write text content directly to a path on the host.

### 2. Session Uploads (Reference-based)
Storing files in the backend storage layer for reference by agents.
- **Upload**: Multipart upload to `~/.openclaw/uploads/{sessionId}/{fileId}`.
- **Metadata**: Files are tracked in the database with name, size, and mime-type.
- **Reference**: Messages can include `[file:id]` tokens or native `attachments` lists.
- **Agent Access**: Agents can use the `read_session_file` tool (via MCP) to read content.

## Implementation Details
- **Storage**: Local disk in `~/.openclaw/uploads` (prototype).
- **Database**: `FileEntity` table in SQLDelight for metadata.
- **Client**: Native file picker for uploads; automatic path resolution for host pulls.

## Requirements
- Explicit user intent for all transfers.
- Clear source and destination paths for host operations.
- Stable transfer IDs (UUIDs).
- Audit logging for every upload, pull, and push.

## Notes
- The UI distinguishes "Host Files" (absolute/relative paths) from "Session Files" (uploads).
- Large files should be handled via streaming or chunked reading (implemented in `read_session_file` tool).
