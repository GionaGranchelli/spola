# File Upload Design for OpenClaw App

This document explains how to implement file upload support for an application that interacts with OpenClaw, with a focus on making files available to the agent in a safe, scalable, and practical way.

## Goal

The core goal is:

- let a user upload a file into the app
- persist the file somewhere stable
- make the file readable by the assistant when needed
- avoid pushing raw binary data through chat unless the file is tiny

The right model is usually not “send the file into the message”, but “store the file and send a reference”.

---

## Recommended Architecture

### 1. Upload endpoint

Create an API endpoint that accepts file uploads from the frontend.

Typical responsibilities:

- validate file size and type
- store the file in a backend storage layer
- generate a unique file ID
- save metadata in a database
- return a reference to the uploaded file

Example response:

```json
{
  "fileId": "file_abc123",
  "name": "report.md",
  "mimeType": "text/markdown",
  "size": 8421
}
```

### 2. Storage layer

Use one of these options:

- local disk for prototypes
- object storage like S3 or GCS for production
- database only for very small files, usually not ideal

Best practice is to store the file content separately from the chat message.

### 3. Metadata store

Keep metadata in a database table such as:

- file ID
- original filename
- MIME type
- size
- checksum
- storage path or object key
- owner/session/conversation ID
- upload timestamp
- access scope

This makes it possible to list, inspect, and secure files later.

### 4. Agent-accessible file tools

Expose tool-like functions for the assistant:

- `list_files(conversationId)`
- `get_file_metadata(fileId)`
- `read_file(fileId, offset, limit)`
- `download_file(fileId)`
- `delete_file(fileId)`

For text files, paging is important. For binary files, the assistant should generally inspect metadata or a rendered preview rather than raw bytes.

---

## Best Pattern: Reference, Not Payload

Instead of embedding the file directly in the message, send a reference.

Example:

```json
{
  "message": "Please review this file.",
  "attachments": [
    {
      "fileId": "file_abc123",
      "name": "report.md",
      "mimeType": "text/markdown",
      "size": 8421
    }
  ]
}
```

The assistant can then use a file-reading tool to inspect the content.

This is better because:

- it scales to large files
- it works for binary files
- it avoids huge chat payloads
- it supports repeated access
- it keeps access control centralized

---

## Suggested API Design

A simple backend API might look like this:

### Upload file

`POST /files`

Accept multipart form data.

Response:

```json
{
  "fileId": "file_abc123",
  "name": "notes.txt",
  "mimeType": "text/plain",
  "size": 1240
}
```

### Get metadata

`GET /files/:id`

Response:

```json
{
  "fileId": "file_abc123",
  "name": "notes.txt",
  "mimeType": "text/plain",
  "size": 1240,
  "createdAt": "2026-03-13T10:30:00Z"
}
```

### Read content in chunks

`GET /files/:id/content?offset=1&limit=2000`

Return text content by line or character range, depending on implementation.

### Delete file

`DELETE /files/:id`

Optional, but useful for cleanup.

---

## Handling Text Files

Text files are the easiest case.

Recommended behavior:

- detect text MIME types
- read them as UTF-8
- chunk content for large files
- preserve line numbers if possible
- allow paging by offset/limit

Good examples:

- `.md`
- `.txt`
- `.json`
- `.csv`
- `.yaml`
- `.log`

For large text files, chunking matters. The assistant should be able to request the next chunk when needed.

---

## Handling Binary Files

Binary files should not be shoved into chat.

Examples:

- images
- PDFs
- audio
- video
- archives
- spreadsheets with complex formatting

For these, store the original file and provide one or more of the following:

- metadata
- preview text
- extracted OCR text
- thumbnails or rendered previews
- download link or signed URL

If you want the assistant to reason about binary content, convert it into something readable first.

---

## Security Considerations

File upload support needs basic safeguards.

### Access control

Only allow access to files within the correct user/session/conversation scope.

### Size limits

Enforce upload limits to prevent abuse and accidental huge uploads.

### Type validation

Check MIME type and extension, but do not trust either alone.

### Virus scanning

If your app handles user uploads from untrusted sources, consider scanning.

### Signed URLs

If using object storage, signed URLs should be short-lived and scoped.

### Audit logs

Log:

- who uploaded the file
- when it was uploaded
- who accessed it
- when it was deleted

---

## Practical UX Flow

A nice user flow is:

1. user uploads file
2. frontend shows upload complete state
3. app stores and registers file metadata
4. user sends a message referencing the file
5. assistant reads the file through a tool
6. assistant responds with analysis or changes

This feels natural and avoids making the user manage raw links.

---

## If You Want Real OpenClaw Integration

If the goal is to connect this to OpenClaw specifically, the app should likely do one of these:

- send a message containing a file reference
- expose a tool that OpenClaw can call to read the file
- provide a signed URL if the assistant runtime supports fetching it

The exact shape depends on how your app is wired into OpenClaw, but the principle stays the same: **store first, reference second, read on demand**.

---

## Minimal Implementation Strategy

If you want the simplest version first:

1. support text files only
2. save uploads to local disk
3. store metadata in SQLite/Postgres
4. expose `read_file(fileId, offset, limit)`
5. attach file references to conversation messages

Then later add:

- object storage
- binary preview support
- OCR / extraction for PDFs
- access control improvements
- cleanup jobs

---

## Example Data Model

A basic file table:

```sql
CREATE TABLE files (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  conversation_id TEXT,
  original_name TEXT NOT NULL,
  mime_type TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  storage_key TEXT NOT NULL,
  checksum TEXT,
  created_at TEXT NOT NULL,
  deleted_at TEXT
);
```

---

## Example Tool Contract

A simple assistant-facing tool contract could be:

```json
{
  "name": "read_file",
  "description": "Read a text file by chunks",
  "input": {
    "fileId": "string",
    "offset": "number",
    "limit": "number"
  }
}
```

Returned content could look like:

```json
{
  "fileId": "file_abc123",
  "offset": 1,
  "limit": 2000,
  "content": "..."
}
```

---

## Bottom Line

The best implementation is:

- upload file to storage
- save metadata in a DB
- pass a file reference in the chat
- let the assistant read the file through a tool

That gives you a design that is simple, secure, and scalable.
