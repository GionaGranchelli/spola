# Golem — Getting Started

> **Golem is a JVM autonomous coding agent.** One agent loop, four interfaces: CLI, REST API, Web Dashboard, and MCP.

```bash
# Prerequisites: Java 21+
git clone https://github.com/nousresearch/golem.git
cd golem
./gradlew build
./gradlew :golem-cli:installDist
```

## Quick Start

```bash
# One-shot mode
export OPENAI_API_KEY="sk-..."
./golem-cli/build/install/golem-cli/bin/golem-cli "explain the architecture"

# Interactive REPL (ANSI colors, tool timeline, streaming)
./golem-cli/build/install/golem-cli/bin/golem-cli

# API server + Web Dashboard at http://localhost:8082/web/
./golem-cli/build/install/golem-cli/bin/golem-cli --api --api-key my-key
```

## Documentation

| Guide | Description |
|-------|-------------|
| [Quickstart Guide](docs/overview/QUICKSTART.md) | Full step-by-step: install, config, workflows, MCP |
| [README](docs/overview/README.md) | Features overview, architecture, screenshots |
| [CLI Reference](docs/cli/CLI.md) | All commands, flags, and options |
| [API Reference](docs/api/API.md) | REST API endpoints with curl examples |
| [Architecture](docs/architecture/ARCHITECTURE.md) | System design: ReAct loop, tools, providers, plugins |
| [Workflows](docs/workflows/WORKFLOWS.md) | Multi-agent orchestration with TramAI |
| [MCP](docs/mcp/MCP.md) | MCP Server + Client setup and usage |

## Test Suite

```bash
./gradlew :golem-core:test
```
