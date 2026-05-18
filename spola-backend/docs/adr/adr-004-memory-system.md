# ADR-004: Memory System Design

- **Status:** accepted
- **Owner:** maintainer
- **Last updated:** 2026-05-11

## Context

Effective coding agents remember facts across sessions: user preferences, project
conventions, environment details. This requires persistent storage.

## Decision

Use **SQLite via Exposed** for the memory store. SQLite requires no server, no
configuration, and is embedded in the JVM process.

Schema:
```sql
CREATE TABLE memory_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_memory_key ON memory_entries(key);
```

Two tools expose memory to the agent:
- `memory_save(key, value)` — upserts a memory entry
- `memory_search(query)` — full-text search on key and value

## Non-Goals

- Vector embeddings (future enhancement)
- Cross-session chat history (only durable facts are stored)
- Distributed memory (single-process for MVP)
