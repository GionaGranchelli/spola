# SPEC-003: Persona System

- **Status:** draft
- **Owner:** maintainer
- **Last updated:** 2026-05-11

## Problem

The agent needs a system persona that defines its behavior, tone, and constraints.
This should be configurable via a file (AGENTS.md / CLAUDE.md) in the project
root, consistent with the Hermes/Claude Code convention.

## Functional Requirements

1. Load AGENTS.md from the working directory at startup
2. Load CLAUDE.md as fallback if AGENTS.md not found
3. Use a built-in default persona if neither file exists
4. The persona is injected as the system message in the ReAct loop
5. The persona file path is configurable via CLI flag or environment variable

## Default Persona

```
You are Golem, a JVM-based autonomous coding agent.
You help users build, debug, and understand Java/Kotlin projects.
You have access to tools: read, write, search files, run shell commands,
and maintain memory across sessions.

Guidelines:
- Be concise and precise
- Verify your work: after writing files, suggest compilation or test runs
- Use shell for: builds, tests, git operations
- Use read_file for: understanding existing code
- Use write_file for: creating or modifying code
- Use memory for: remembering user preferences and project conventions
```

## Acceptance Criteria

- [ ] Agent loads AGENTS.md when present
- [ ] Agent falls back to CLAUDE.md when AGENTS.md absent
- [ ] Agent uses default persona when neither file exists
- [ ] Persona is injected as the first system message
- [ ] CLI flag --persona overrides the file path
