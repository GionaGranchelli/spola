# ADR-003: Tool System Design

- **Status:** accepted
- **Owner:** maintainer
- **Last updated:** 2026-05-11

## Context

The agent loop needs to expose tools to the LLM. Tools must be:
1. Self-describing (name, description, parameter schema)
2. Runnable (callable by name with typed arguments)
3. Observable (errors, duration, output size)

## Decision

Define a tool as a data class + exec function:

```kotlin
data class Tool(
    val name: String,
    val description: String,
    val parameters: JsonSchema,
    val execute: suspend (Map<String, JsonNode>) -> ToolResult,
)

data class ToolResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
)
```

Parameter schema uses a minimal JSON Schema subset (type, description, required fields).
This is auto-converted to TramAI's tool schema format for LLM provider calls.

## Built-in Tools (MVP)

| Tool | Description |
|------|-------------|
| `read_file` | Read file contents with line numbers |
| `write_file` | Write/overwrite file content |
| `search_files` | Grep/RG-like content search |
| `shell` | Execute shell command (argv mode) |
| `memory_save` | Save fact to persistent memory |
| `memory_search` | Search past facts |

## Design Decisions

- **No class hierarchy** — tools are plain objects in a `Map<String, Tool>`
- **Tool schemas are JSON** — tools declare their own schema; the agent loop converts to provider format
- **Output is string** — tool results are always flattened to strings before feeding back to the LLM
- **No streaming tool output** — tool results are complete before returning to the loop
