# SPEC-002: Built-in Tools

- **Status:** draft
- **Owner:** maintainer
- **Last updated:** 2026-05-11
- **Related ADRs:** ADR-003

## Problem

The agent loop needs tools to be useful. Without filesystem and shell access,
the agent cannot modify code, run builds, or inspect projects.

## Tool Definitions

### read_file

Read a file from the filesystem with optional line range.

```kotlin
read_file(path: String, offset: Int = 1, limit: Int = 500) -> String
```

Returns content with line numbers, truncated at limit lines. Errors:
- FileNotFound → "File not found: <path>"
- SecurityViolation → "Access denied: <path> (path outside allowed directories)"

### write_file

Write content to a file, creating directories if needed.

```kotlin
write_file(path: String, content: String) -> "OK (NN bytes written)"
```

Errors:
- SecurityViolation → "Access denied: <path>"
- IOException → specific error message

### search_files

Search file contents by regex pattern.

```kotlin
search_files(pattern: String, path: String = ".", file_glob: String? = null) -> String
```

Returns matches with file paths and line numbers, max 50 results.

### shell

Execute a shell command in argv mode (no shell injection).

```kotlin
shell(command: List<String>, workdir: String? = null, timeout: Int = 30) -> String
```

Returns stdout on success (truncated at 50KB), stderr on failure.

### memory_save

Save a fact to persistent memory.

```kotlin
memory_save(key: String, value: String) -> "Saved"
```

### memory_search

Search memory by key prefix or content.

```kotlin
memory_search(query: String) -> String
```

Returns formatted list of matching entries.

## Acceptance Criteria

- [ ] read_file reads existing file content
- [ ] read_file returns error for non-existent file
- [ ] write_file creates new files with correct content
- [ ] write_file overwrites existing files
- [ ] search_files finds matching content by regex
- [ ] shell executes commands and returns stdout
- [ ] shell returns stderr on non-zero exit
- [ ] shell enforces timeout
- [ ] memory_save/memory_search persist across store lifecycle
