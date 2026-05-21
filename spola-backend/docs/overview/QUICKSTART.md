# Spola Quickstart

From zero to running Spola in 5 minutes.

## Prerequisites

- **Java 21** (Temurin recommended)
- **Gradle** (wrapped in the project — no global install needed)

```bash
# Install SDKMAN if you don't have it
curl -s "https://get.sdkman.io" | bash
source ~/.sdkman/bin/sdkman-init.sh

# Install and activate Java 21
sdk install java 21.0.7-tem
sdk use java 21.0.7-tem
```

## 1. Clone & Build

```bash
git clone <your-repo-url> ~/Development/spola
cd ~/Development/spola

# Build everything (tests included)
./gradlew build
```

First build downloads dependencies and compiles both `spola-backend-core` and `spola-backend-cli`. Expect 30-60s.

## 2. One-Shot Mode (Quickest Way to Try)

Send a single goal to the agent. It processes it and exits:

```bash
./gradlew :spola-backend-cli:run --args="'write a one-line haiku about Kotlin'"
```

Set your API key inline:

```bash
SPOLA_API_KEY=sk-... ./gradlew :spola-backend-cli:run --args="'what files are in this project?'"
```

Choose a different model/provider:

```bash
./gradlew :spola-backend-cli:run --args="--provider anthropic --model claude-sonnet-4 'explain gradle multi-module projects'"
```

## 3. Interactive REPL

Build the distribution, then run the binary directly for an interactive session:

```bash
./gradlew :spola-backend-cli:installDist
./spola-backend-cli/build/install/spola/bin/spola
```

You'll see:

```
Spola v0.1.0 — JVM Autonomous Coding Agent
Type your goal, or /help for commands.

>
```

Type a goal (e.g., `list all Kotlin files in this project`). The agent will show tool calls with a spinner, then display results.

### REPL Commands

- `<goal>` — Send a goal to the agent
- `/help` — Show all commands
- `/tools` — List available tools
- `/memory` — Show stored memory entries
- `/history` — Show conversation history
- `/persona` — Show the current persona
- `/clear` — Clear conversation
- `/model <name>` — Switch model (e.g., `/model gpt-4o`)
- `/provider <name>` — Switch provider (e.g., `/provider anthropic`)
- `/exit` or `/quit` — Exit the REPL

## 4. Config File

Spola auto-detects `~/.spola/config.yaml` at startup. Create a default one:

```bash
./spola-backend-cli/build/install/spola/bin/spola config init
```

The generated file:

```yaml
# ~/.spola/config.yaml
model: gpt-4o
provider: openai
workdir: .
memory-db: ./.spola/memory.db
scheduler-db: ./.spola/scheduler.db
kanban-db: ./.spola/kanban.db
checkpoint-db: ./.spola/checkpoint.db
jvm-index-db: ./.spola/jvm-index.db
max-turns: 25
insecure: false
unsafe: false
tts:
  provider: edge
  elevenlabs-voice-id: 21m00Tcm4TlvDq8ikWAM
providers: {}
```

Set `SPOLA_API_KEY` as an environment variable (or add `api-key` to config.yaml). CLI flags override config file values.

## 5. API Server

Start a REST API server on port 8082:

```bash
./spola-backend-cli/build/install/spola/bin/spola --api --api-key my-secret-key
```

With TLS:

```bash
./spola-backend-cli/build/install/spola/bin/spola --api --api-key my-secret-key \
  --tls-cert /path/to/cert.pem --tls-key /path/to/key.pem
```

## 6. MCP Server

Expose Spola tools to any MCP client (e.g., a Claude Desktop integration):

```bash
# stdio transport (default)
./spola-backend-cli/build/install/spola/bin/spola --mcp

# SSE transport on port 8091
./spola-backend-cli/build/install/spola/bin/spola --mcp --mcp-transport sse --mcp-port 8091 --api-key my-key
```

## 7. Daemon Mode

Run the scheduler and background daemon:

```bash
./spola-backend-cli/build/install/spola/bin/spola --daemon
```

## 8. Set a Persona Pass a custom persona file (e.g., `AGENTS.md` or `CLAUDE.md`):

```bash
./gradlew :spola-backend-cli:run --args="--persona ./AGENTS.md 'review this pull request'"
```

Or activate a named persona from `~/.spola/people/`:

```bash
./spola-backend-cli/build/install/spola/bin/spola --persona-name senior-dev
```

List available personas:

```bash
./spola-backend-cli/build/install/spola/bin/spola persona list
```

## 9. Verify It Works

```bash
cd ~/Development/spola
./gradlew :spola-backend-cli:run --args="'run pwd in shell and tell me the current directory'"
```

Expected output — agent runs `pwd`, reads the result, and reports the directory path.

## Next Steps

- [Architecture](../architecture/ARCHITECTURE.md) — module map, data flow, design decisions
- [CLI Reference](../cli/CLI.md) — every command, flag, and subcommand
- `spola agent create` — define custom agents with scoped permissions
- `spola skill install` — install reusable skill YAML files
- `spola project scan` — index a JVM project for symbol-level intelligence
- `spola workflow run code-review` — run a multi-step deterministic workflow
