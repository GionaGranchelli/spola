# Spola CLI Reference

**Version:** 0.1.1 | **Binary:** `spola` (in `spola-backend-cli/build/install/spola/bin/spola`)

Run via Gradle during development:

```bash
./gradlew :spola-backend-cli:run --args="<flags-and-args>"
```

Or build a distribution and use the binary directly:

```bash
./gradlew :spola-backend-cli:installDist
./spola-backend-cli/build/install/spola/bin/spola <flags-and-args>
```

---

## Global Flags

These flags are available on the root `spola` command and affect all modes.

```
--model <name>           LLM model (default: gpt-4o)
--provider <name>        Provider: openai, anthropic, openai-compat, ollama, google (default: openai)
--config <path>          Path to YAML config file (default: ~/.spola/config.yaml)
--dir, --workdir <path>  Working directory (default: current)
--persona <path>         Path to AGENTS.md / CLAUDE.md persona file
--persona-name <name>    Name of active persona from ~/.spola/people/
--memory-db <path>       SQLite memory database path (default: ./.spola/memory.db)
--scheduler-db <path>    SQLite scheduler database path (default: ./.spola/scheduler.db)
--kanban-db <path>       SQLite kanban database path (default: ./.spola/kanban.db)
--jvm-index-db <path>    SQLite JVM project index path (default: ./.spola/jvm-index.db)
--max-turns <int>        Maximum agent turns (default: 25)
--api-key <key>          API key for REST API and MCP SSE auth (env: SPOLA_API_KEY)
--resume, --session-id   Resume a previous agent run by session ID
--insecure               Allow binding to 0.0.0.0 without API key
--unsafe                 Disable path restrictions (agent can read/write any file)
--tls-cert <path>        TLS certificate file (PEM)
--tls-key <path>         TLS private key file (PEM)
--mcp                    Run as MCP server instead of CLI
--mcp-port <port>        MCP SSE port (default: 8091)
--mcp-transport <type>   MCP transport: stdio or sse (default: stdio)
--mcp-host <host>        MCP SSE host (default: 127.0.0.1)
--api                    Run as REST API server instead of CLI
--api-port <port>        REST API port (default: 8082)
--daemon                 Run the scheduler daemon
--help                   Show help and exit
--version                Print version and exit
```

### Environment Variables

- `SPOLA_API_KEY` — API key for the LLM provider (used as fallback if `--api-key` not set)

### Config Precedence

CLI flags > config file (`~/.spola/config.yaml`) > defaults. Env var `SPOLA_API_KEY` is checked if neither `--api-key` nor config provides one.

---

## Modes

### One-Shot Mode

Pass a goal string as the argument. The agent processes it and exits.

```bash
./gradlew :spola-backend-cli:run --args="'explain what this project does'"
```

```bash
./spola-backend-cli/build/install/spola/bin/spola 'list all Kotlin files in src/'
```

With flags:

```bash
./spola-backend-cli/build/install/spola/bin/spola --model claude-sonnet-4 --provider anthropic 'refactor this to use sealed classes'
```

```bash
./spola-backend-cli/build/install/spola/bin/spola --workdir /path/to/project --unsafe 'find all TODO comments'
```

```bash
SPOLA_API_KEY=sk-... ./gradlew :spola-backend-cli:run --args="--provider openai-compat 'write a README'"
```

### REPL Mode

Omit the goal argument to enter interactive mode.

```bash
./spola-backend-cli/build/install/spola/bin/spola
```

```
Spola v0.1.1 — JVM Autonomous Coding Agent
Type your goal, or /help for commands.

> write a fibonacci function in Kotlin
```

REPL commands (type at the `>` prompt):

- `<goal>` — Send any text as a goal to the agent
- `/help` — Show all REPL commands
- `/tools` — List available tools with parameter descriptions
- `/memory` — Show stored memory entries
- `/history` — Show current conversation history
- `/persona` — Show the active persona
- `/clear` — Clear conversation history (starts fresh)
- `/model <name>` — Switch to a different model (e.g., `/model gpt-4o`)
- `/provider <name>` — Switch provider (e.g., `/provider anthropic`)
- `/exit` or `/quit` — Exit the REPL

Open a persona from a file:

```bash
./spola-backend-cli/build/install/spola/bin/spola --persona ./AGENTS.md
```

### API Server Mode

Start a REST API server:

```bash
./spola-backend-cli/build/install/spola/bin/spola --api --api-key my-secret
```

On a custom port:

```bash
./spola-backend-cli/build/install/spola/bin/spola --api --api-key my-secret --api-port 9000
```

With TLS:

```bash
./spola-backend-cli/build/install/spola/bin/spola --api --api-key my-secret \
  --tls-cert /etc/ssl/certs/server.pem --tls-key /etc/ssl/private/server-key.pem
```

Allow unauthenticated access (not recommended for production):

```bash
./spola-backend-cli/build/install/spola/bin/spola --api --insecure
```

### Daemon Mode

Run the scheduler daemon (polls SQLite for due jobs):

```bash
./spola-backend-cli/build/install/spola/bin/spola --daemon
```

### MCP Server Mode

Expose Spola tools via the Model Context Protocol.

Stdio transport (for Claude Desktop, Cursor, etc.):

```bash
./spola-backend-cli/build/install/spola/bin/spola --mcp
```

SSE transport (for remote MCP clients):

```bash
./spola-backend-cli/build/install/spola/bin/spola --mcp --mcp-transport sse --mcp-port 9090 --api-key my-key
```

Bind to a specific host:

```bash
./spola-backend-cli/build/install/spola/bin/spola --mcp --mcp-transport sse --mcp-host 0.0.0.0
```

---

## Subcommands

### `spola config` — Manage Configuration

**Subcommands:** `show`, `path`, `init`

**`spola config show`** — Print the current effective configuration (merged from file + CLI flags):

```bash
./spola-backend-cli/build/install/spola/bin/spola config show
```

```
model: gpt-4o
provider: openai
workdir: /home/user/project
memory-db: ./.spola/memory.db
scheduler-db: ./.spola/scheduler.db
max-turns: 25
temperature: null
...
```

**`spola config path`** — Print the config file path being used:

```bash
./spola-backend-cli/build/install/spola/bin/spola config path
```

```
/home/user/.spola/config.yaml
```

**`spola config init`** — Create a default `~/.spola/config.yaml`:

```bash
./spola-backend-cli/build/install/spola/bin/spola config init
```

```
Created /home/user/.spola/config.yaml
```

If the file already exists, it prints an error and exits with code 1.

---

### `spola agent` — Manage Custom Agent Definitions

**Subcommands:** `list`, `show`, `create`, `update`, `delete`, `run`

Custom agents are stored in an SQLite database and optionally as YAML files in `.spola/agents/`. Each agent has a system prompt, preferred model/provider, and scoped permissions.

**`spola agent list`** — List all custom agents:

```bash
./spola-backend-cli/build/install/spola/bin/spola agent list
```

```
Custom Agents:
────────────────────────────────────────────────────────────
✅ my-coder — Kotlin Expert
  anthropic/claude-sonnet-4
  Expert Kotlin developer with full filesystem access
✅ reviewer — Code Reviewer
  openai/gpt-4o
  Conservative reviewer, cannot write files

2 agent(s)
```

**`spola agent show <id>`** — Show full agent definition:

```bash
./spola-backend-cli/build/install/spola/bin/spola agent show my-coder
```

```
Agent: my-coder (v1)
Name: Kotlin Expert
Description: Expert Kotlin developer with full filesystem access

── Model ──
Preferred: anthropic/claude-sonnet-4

── Permissions ──
Filesystem: read-write
Shell: true
...
── Instructions ──
You are an expert Kotlin developer...
```

**`spola agent create`** — Create a new agent:

```bash
./spola-backend-cli/build/install/spola/bin/spola agent create \
  --id my-coder \
  --name "Kotlin Expert" \
  --desc "Expert Kotlin developer" \
  --model claude-sonnet-4 \
  --provider anthropic \
  --system-prompt "You are an expert Kotlin developer. Prefer functional programming patterns."
```

```bash
./spola-backend-cli/build/install/spola/bin/spola agent create \
  --id read-only-reviewer \
  --name "Code Reviewer" \
  --model gpt-4o \
  --provider openai \
  --fs read-only \
  --shell false \
  --system-prompt "You are a conservative code reviewer. Never write code, only suggest changes."
```

All flags:

- `--id` (required) — Unique agent ID
- `--name` (required) — Human-readable name
- `--desc` — Short description
- `--system-prompt`, `-i` (required) — System prompt / persona
- `--model` (required) — Preferred model (e.g., `claude-sonnet-4`)
- `--provider` (required) — Preferred provider (`openai`, `anthropic`, `openai-compat`, `ollama`, `google`)
- `--temp` — Temperature (0.0–2.0)
- `--fallback-model` — Fallback model name
- `--fs` — Filesystem access: `read-write`, `read-only`, `none` (default: `read-write`)
- `--shell` — Shell access allowed: `true` or `false` (default: `true`)
- `--network` — Network access allowed: `true` or `false` (default: `true`)
- `--exec` — Execute mode: `auto`, `ask_first`, `never` (default: `auto`)
- `--memory` — Memory scope: `global`, `agent`, `none` (default: `global`)
- `--tags` — Comma-separated tags

**`spola agent update <id>`** — Update an existing agent:

```bash
./spola-backend-cli/build/install/spola/bin/spola agent update my-coder --name "Kotlin Pro" --model gpt-4o
```

```bash
./spola-backend-cli/build/install/spola/bin/spola agent update reviewer --enable false
```

**`spola agent delete <id>`** — Delete an agent:

```bash
./spola-backend-cli/build/install/spola/bin/spola agent delete my-coder
```

```bash
./spola-backend-cli/build/install/spola/bin/spola agent delete old-agent --rm
```

**`spola agent run <id> <goal>`** — Run a custom agent with a goal:

```bash
./spola-backend-cli/build/install/spola/bin/spola agent run my-coder 'review the build.gradle.kts files'
```

```bash
./spola-backend-cli/build/install/spola/bin/spola agent run reviewer 'check for security issues in src/'
```

---

### `spola process` — Deterministic Process Workflows

**Subcommands:** `list`, `run`, `status`, `cancel`, `approve`, `reject`

Processes are deterministic DAGs (e.g., compile → test → git_commit → human_approval) where AI fills variables within typed steps.

**`spola process list`** — List available process templates:

```bash
./spola-backend-cli/build/install/spola/bin/spola process list
```

```
Available process templates: code-review, deploy, refactor
```

**`spola process run <template> <goal>`** — Run a process template:

```bash
./spola-backend-cli/build/install/spola/bin/spola process run code-review 'review the auth module'
```

```bash
./spola-backend-cli/build/install/spola/bin/spola process run deploy --project :spola-backend-cli 'build and deploy'
```

**`spola process status <run-id>`** — Check process status:

```bash
./spola-backend-cli/build/install/spola/bin/spola process status proc_abc123
```

```
Run: proc_abc123
  template: code-review
  status: RUNNING
  step: compile_project
```

**`spola process cancel <run-id>`** — Cancel a running process:

```bash
./spola-backend-cli/build/install/spola/bin/spola process cancel proc_abc123
```

```
Cancelled run proc_abc123
```

**`spola process approve <run-id>`** — Approve a gate decision:

```bash
./spola-backend-cli/build/install/spola/bin/spola process approve proc_abc123 --notes "Looks good, proceed"
```

**`spola process reject <run-id>`** — Reject a gate decision:

```bash
./spola-backend-cli/build/install/spola/bin/spola process reject proc_abc123 --notes "Needs more tests"
```

---

### `spola project` — JVM Project Intelligence

**Subcommands:** `scan`, `overview`, `symbol`

**`spola project scan`** — Force a full JVM project reindex:

```bash
./spola-backend-cli/build/install/spola/bin/spola project scan
```

```
Indexed 3 module(s) in /home/user/project
:spola-backend-core | sources=2 tests=1 deps=12
:spola-backend-cli | sources=1 tests=1 deps=8
:spola-backend-api | sources=1 tests=1 deps=5
```

Scan a specific directory:

```bash
./spola-backend-cli/build/install/spola/bin/spola --workdir /path/to/other-project project scan
```

**`spola project overview`** — Print the JVM module tree:

```bash
./spola-backend-cli/build/install/spola/bin/spola project overview
```

```
Project: /home/user/project
Modules (3):
- :spola-backend-core (root)
  path: .
  sources: src/main/kotlin
  tests: src/test/kotlin
- :spola-backend-cli
  path: spola-backend-cli
  sources: src/main/kotlin
- :spola-backend-api
  path: spola-backend-api
```

**`spola project symbol <name>`** — Lookup a JVM symbol:

```bash
./spola-backend-cli/build/install/spola/bin/spola project symbol SpolaAgent
```

```
:spola-backend-core CLASS SpolaAgent SpolaAgent.kt:25:1
:spola-backend-core FUNCTION SpolaAgent.run SpolaAgent.kt:42:1
```

Filter by module:

```bash
./spola-backend-cli/build/install/spola/bin/spola project symbol ToolRegistry --module :spola-backend-core
```

Filter by kind:

```bash
./spola-backend-cli/build/install/spola/bin/spola project symbol run --kind function
```

---

### `spola workflow` — Multi-Step Workflows

**Subcommands:** `run`

**`spola workflow run <name> <goal>`** — Run a predefined workflow:

```bash
./spola-backend-cli/build/install/spola/bin/spola workflow run code-review 'review the new API endpoint'
```

```bash
./spola-backend-cli/build/install/spola/bin/spola workflow run jvm-debug 'investigate the NullPointerException in UserService'
```

```bash
./spola-backend-cli/build/install/spola/bin/spola workflow run jvm-refactor 'extract the authentication logic into a separate module'
```

```bash
./spola-backend-cli/build/install/spola/bin/spola workflow run jvm-migration 'migrate from RxJava to coroutines'
```

Built-in workflows: `code-review`, `jvm-debug`, `jvm-refactor`, `jvm-migration`

---

### `spola team` — Parallel Agent Teams

**Subcommands:** `run`

**`spola team run`** — Run a team of agents in parallel:

```bash
./spola-backend-cli/build/install/spola/bin/spola team run \
  --agents my-coder,reviewer \
  --goal 'implement a new REST endpoint for user profiles'
```

---

### `spola skill` — Reusable Agent Skills

**Subcommands:** `list`, `run`, `install`

Skills are YAML files defining a reusable agent persona + behavior. Stored in `~/.spola/skills/`.

**`spola skill list`** — List all installed skills:

```bash
./spola-backend-cli/build/install/spola/bin/spola skill list
```

```
Installed Skills (/home/user/.spola/skills):
────────────────────────────────────────────────────────────
• analyze-dependency — Analyze Maven/Gradle dependency trees [jvm]
• generate-test — Generate unit tests for a Kotlin class [testing]

2 skill(s)
```

**`spola skill run <name> <goal>`** — Run a skill:

```bash
./spola-backend-cli/build/install/spola/bin/spola skill run generate-test 'create tests for UserService.kt'
```

```bash
./spola-backend-cli/build/install/spola/bin/spola skill run analyze-dependency 'check for conflicts in the build graph'
```

**`spola skill install <path>`** — Install a skill YAML file:

```bash
./spola-backend-cli/build/install/spola/bin/spola skill install ~/Downloads/my-skill.yaml
```

```
✅ Installed skill 'my-skill' to /home/user/.spola/skills/my-skill.yaml
```

---

### `spola persona` — Persona Pocket

**Subcommands:** `list`, `show`, `sync`

Personas are Markdown files with YAML frontmatter in `~/.spola/people/`.

**`spola persona list`** — List all stored personas:

```bash
./spola-backend-cli/build/install/spola/bin/spola persona list
```

```
Personas:
────────────────────────────────────────────────────────────
  senior-dev (active)
    Role: Senior Kotlin Developer
    Summary: 15 years JVM experience, expert in coroutines
  architect
    Role: System Architect
    Summary: Focuses on modular design and clean architecture

2 persona(s)
```

**`spola persona show <name>`** — Show full persona details:

```bash
./spola-backend-cli/build/install/spola/bin/spola persona show senior-dev
```

```
Name: senior-dev
Role: Senior Kotlin Developer
Tags: kotlin, jvm, coroutines
Summary: 15 years JVM experience
--- Body ---
You are a senior Kotlin developer...
```

**`spola persona sync`** — Sync personas from `~/.spola/people/` to the SQLite store:

```bash
./spola-backend-cli/build/install/spola/bin/spola persona sync
```

```
Syncing from: /home/user/.spola/people
Sync complete.
Total personas: 3
```

Activate a persona by name when starting Spola:

```bash
./spola-backend-cli/build/install/spola/bin/spola --persona-name senior-dev 'review this code'
```

---

### `spola mcp` — Manage MCP Client Connections

**Subcommands:** `add`, `list`, `remove`, `reconnect`

MCP client connections allow Spola to consume tools from external MCP servers (e.g., a local Playwright server, a database MCP server).

**`spola mcp add <name>`** — Add an MCP server connection:

Stdio transport:

```bash
./spola-backend-cli/build/install/spola/bin/spola mcp add my-playwright \
  --cmd "npx" --args @playwright/mcp
```

SSE transport:

```bash
./spola-backend-cli/build/install/spola/bin/spola mcp add remote-server \
  --url "http://localhost:8091/mcp"
```

Add disabled (connect later):

```bash
./spola-backend-cli/build/install/spola/bin/spola mcp add db-server \
  --cmd "node" --args /path/to/server.js --disabled
```

**`spola mcp list`** — List all configured MCP servers:

```bash
./spola-backend-cli/build/install/spola/bin/spola mcp list
```

```
Configured MCP Servers:
────────────────────────────────────────────────────────────
✅ my-playwright
   Transport: stdio
   Command: npx @playwright/mcp

⛔ db-server
   Transport: sse
   URL: http://localhost:3000/mcp

2 server(s)
```

**`spola mcp remove <name>`** — Remove an MCP server:

```bash
./spola-backend-cli/build/install/spola/bin/spola mcp remove my-playwright
```

```
✅ Removed MCP server 'my-playwright'
```

**`spola mcp reconnect <name>`** — Reconnect to a disconnected server:

```bash
./spola-backend-cli/build/install/spola/bin/spola mcp reconnect db-server
```

```
✅ Reconnected to MCP server 'db-server'
```

---

### `spola scheduler` — Scheduled Jobs

**Subcommands:** `add`, `list`, `remove`

Requires the daemon (`--daemon`) to be running to execute scheduled jobs.

**`spola scheduler add`** — Add a scheduled job:

```bash
./spola-backend-cli/build/install/spola/bin/spola scheduler add \
  --name "daily-health-check" \
  --cron "0 8 * * *" \
  'check all services are running and report'
```

```bash
./spola-backend-cli/build/install/spola/bin/spola scheduler add \
  --name "weekly-report" \
  --cron "0 9 * * 1" \
  --disabled \
  'generate the weekly status report'
```

**`spola scheduler list`** — List scheduled jobs:

```bash
./spola-backend-cli/build/install/spola/bin/spola scheduler list
```

```
job_abc | daily-health-check | enabled=true | next=2026-05-15T08:00:00 | cron=0 8 * * *
job_def | weekly-report | enabled=false | next=2026-05-18T09:00:00 | cron=0 9 * * 1
```

**`spola scheduler remove <id>`** — Remove a scheduled job:

```bash
./spola-backend-cli/build/install/spola/bin/spola scheduler remove job_def
```

```
Removed job job_def
```

---

### `spola pairing` — Pairing Info

**Subcommands:** `info`, `qrcode`

Used to pair Spola with the OpenClaw mobile app.

**`spola pairing info`** — Print connection details for pairing:

```bash
./spola-backend-cli/build/install/spola/bin/spola pairing info
```

```
=== Spola Connection Info ===
Host: 192.168.1.42
Port: 8082
Token: a1b2c3d4-e5f6-...
...
```

**`spola pairing qrcode`** — Generate a QR code PNG for pairing:

```bash
./spola-backend-cli/build/install/spola/bin/spola pairing qrcode
```

```
QR code saved to /home/user/qrcode.png
```

Save to a custom path:

```bash
./spola-backend-cli/build/install/spola/bin/spola pairing qrcode --output ~/Desktop/pair.png
```

---

### `spola remote` — Connect to Remote Server

Connect to a remote Spola API server and interact via an interactive session.

```bash
./spola-backend-cli/build/install/spola/bin/spola remote 192.168.1.42:8082 --api-key my-key
```

With TLS:

```bash
./spola-backend-cli/build/install/spola/bin/spola remote spola.example.com:443 --api-key my-key --tls
```

Once connected:

```
Connecting to http://192.168.1.42:8082...
Server: {"status":"ok","version":"0.1.1"}
Connected (session: sess_abc)
> review the code in the auth module
```

---

## Exit Codes

- `0` — Success
- `1` — Error (agent failure, invalid args, config not found, etc.)

The `main()` function in `Main.kt` explicitly calls `exitProcess(exitCode)` — non-zero exit codes terminate the JVM.

---

## Quick Reference

### Common Workflows

```bash
# One-shot
./gradlew :spola-backend-cli:run --args="'my goal'"

# REPL
./spola-backend-cli/build/install/spola/bin/spola

# API server
./spola-backend-cli/build/install/spola/bin/spola --api --api-key secret

# MCP server
./spola-backend-cli/build/install/spola/bin/spola --mcp

# Daemon (scheduler)
./spola-backend-cli/build/install/spola/bin/spola --daemon

# Custom persona
./spola-backend-cli/build/install/spola/bin/spola --persona ./AGENTS.md

# Run a custom agent
./spola-backend-cli/build/install/spola/bin/spola agent run my-coder 'some goal'

# Check config
./spola-backend-cli/build/install/spola/bin/spola config show

# Project intelligence
./spola-backend-cli/build/install/spola/bin/spola project scan

# Workflow
./spola-backend-cli/build/install/spola/bin/spola workflow run code-review 'review this PR'

# Skill
./spola-backend-cli/build/install/spola/bin/spola skill run generate-test 'test UserService'
```
