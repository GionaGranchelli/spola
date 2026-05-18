# SPEC-004: CLI / REPL Interface

- **Status:** draft
- **Owner:** maintainer
- **Last updated:** 2026-05-11

## Functional Requirements

1. CLI entry point: `golem [--persona <file>] [--model <name>] [--provider <name>] [--dir <path>]`
2. Interactive REPL mode: show prompt, read user input, execute agent, print response
3. One-shot mode: `golem "write a README for this project"`
4. Support commands: `/exit`, `/memory`, `/tools`, `/help`

## REPL Commands

| Command | Description |
|---------|-------------|
| `/exit` or `/quit` | Exit the REPL |
| `/memory` | List all stored memory entries |
| `/tools` | List available tools with descriptions |
| `/help` | Show available commands |
| Any other text | Sent to the agent as a goal |

## Acceptance Criteria

- [ ] CLI starts and shows prompt
- [ ] One-shot mode processes a single goal and exits
- [ ] REPL processes multiple goals in a session
- [ ] /memory lists stored facts
- [ ] /tools shows tool list
- [ ] /exit terminates the process
