# Golem CLI Reference

**Version:** 0.1.0 | **Binary:** `golem-cli` (in `build/install/golem-cli/bin/golem-cli`)

Run via Gradle during development:

```bash
./gradlew :golem-cli:run --args="<flags-and-args>"
```

Or build a distribution and use the binary directly:

```bash
./gradlew :golem-cli:installDist
./build/install/golem-cli/bin/golem-cli <flags-and-args>
```

---

## Global Flags

These flags are available on the root `golem` command and affect all modes.

```
--model <name>           LLM model (default: gpt-4o)
--provider <name>        Provider: openai, anthropic, openai-compat, ollama, google (default: openai)
--config <path>          Path to YAML config file (default: ~/.golem/config.yaml)
--dir, --workdir <path>  Working directory (default: current)
--persona <path>         Path to AGENTS.md / CLAUDE.md persona file
--persona-name <name>    Name of active persona from ~/.golem/people/
--memory-db <path>       SQLite memory database path (default: ./.golem/memory.db)
--scheduler-db <path>    SQLite scheduler database path (default: ./.golem/scheduler.db)
--kanban-db <path>       SQLite kanban database path (default: ./.golem/kanban.db)
--jvm-index-db <path>    SQLite JVM project index path (default: ./.golem/jvm-index.db)
--max-turns <int>        Maximum agent turns (default: 25)
--api-key <key>          API key for REST API and MCP SSE auth (env: GOLEM_API_KEY)
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

- `GOLEM_API_KEY` — API key for the LLM provider (used as fallback if `--api-key` not set)

### Config Precedence

CLI flags > config file (`~/.golem/config.yaml`) > defaults. Env var `GOLEM_API_KEY` is checked if neither `--api-key` nor config provides one.

---

## Modes

### One-Shot Mode

Pass a goal string as the argument. The agent processes it and exits.

```bash
./gradlew :golem-cli:run --args="'explain what this project does'"
```

```bash
./build/install/golem-cli/bin/golem-cli 'list all Kotlin files in src/'
```

With flags:

```bash
./build/install/golem-cli/bin/golem-cli --model claude-sonnet-4 --provider anthropic 'refactor this to use sealed classes'
```

```bash
./build/install/golem-cli/bin/golem-cli --workdir /path/to/project --unsafe 'find all TODO comments'
```

```bash
GOLEM_API_KEY=sk-... ./gradlew :golem-cli:run --args="--provider openai-compat 'write a README'"
```

### REPL Mode

Omit the goal argument to enter interactive mode.

```bash
./build/install/golem-cli/bin/golem-cli
```

```
Golem v0.1.0 — JVM Autonomous Coding Agent
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
./build/install/golem-cli/bin/golem-cli --persona ./AGENTS.md
```

### API Server Mode

Start a REST API server:

```bash
./build/install/golem-cli/bin/golem-cli --api --api-key my-secret
```

On a custom port:

```bash
./build/install/golem-cli/bin/golem-cli --api --api-key my-secret --api-port 9000
```

With TLS:

```bash
./build/install/golem-cli/bin/golem-cli --api --api-key my-secret \
  --tls-cert /etc/ssl/certs/server.pem --tls-key /etc/ssl/private/server-key.pem
```

Allow unauthenticated access (not recommended for production):

```bash
./build/install/golem-cli/bin/golem-cli --api --insecure
```

### Daemon Mode

Run the scheduler daemon (polls SQLite for due jobs):

```bash
./build/install/golem-cli/bin/golem-cli --daemon
```

### MCP Server Mode

Expose Golem tools via the Model Context Protocol.

Stdio transport (for Claude Desktop, Cursor, etc.):

```bash
./build/install/golem-cli/bin/golem-cli --mcp
```

SSE transport (for remote MCP clients):

```bash
./build/install/golem-cli/bin/golem-cli --mcp --mcp-transport sse --mcp-port 9090 --api-key my-key
```

Bind to a specific host:

```bash
./build/install/golem-cli/bin/golem-cli --mcp --mcp-transport sse --mcp-host 0.0.0.0
```

---

## Subcommands

### `golem config` — Manage Configuration

**Subcommands:** `show`, `path`, `init`

**`golem config show`** — Print the current effective configuration (merged from file + CLI flags):

```bash
./build/install/golem-cli/bin/golem-cli config show
```

```
model: gpt-4o
provider: openai
workdir: /home/user/project
memory-db: ./.golem/memory.db
scheduler-db: ./.golem/scheduler.db
max-turns: 25
temperature: null
...
```

**`golem config path`** — Print the config file path being used:

```bash
./build/install/golem-cli/bin/golem-cli config path
```

```
/home/user/.golem/config.yaml
```

**`golem config init`** — Create a default `~/.golem/config.yaml`:

```bash
./build/install/golem-cli/bin/golem-cli config init
```

```
Created /home/user/.golem/config.yaml
```

If the file already exists, it prints an error and exits with code 1.

---

### `golem agent` — Manage Custom Agent Definitions

**Subcommands:** `list`, `show`, `create`, `update`, `delete`, `run`

Custom agents are stored in an SQLite database and optionally as YAML files in `.golem/agents/`. Each agent has a system prompt, preferred model/provider, and scoped permissions.

**`golem agent list`** — List all custom agents:

```bash
./build/install/golem-cli/bin/golem-cli agent list
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

**`golem agent show <id>`** — Show full agent definition:

```bash
./build/install/golem-cli/bin/golem-cli agent show my-coder
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

**`golem agent create`** — Create a new agent:

```bash
./build/install/golem-cli/bin/golem-cli agent create \
  --id my-coder \
  --name "Kotlin Expert" \
  --desc "Expert Kotlin developer" \
  --model claude-sonnet-4 \
  --provider anthropic \
  --system-prompt "You are an expert Kotlin developer. Prefer functional programming patterns."
```

```bash
./build/install/golem-cli/bin/golem-cli agent create \
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

**`golem agent update <id>`** — Update an existing agent:

```bash
./build/install/golem-cli/bin/golem-cli agent update my-coder --name "Kotlin Pro" --model gpt-4o
```

```bash
./build/install/golem-cli/bin/golem-cli agent update reviewer --enable false
```

**`golem agent delete <id>`** — Delete an agent:

```bash
./build/install/golem-cli/bin/golem-cli agent delete my-coder
```

```bash
./build/install/golem-cli/bin/golem-cli agent delete old-agent --rm
```

**`golem agent run <id> <goal>`** — Run a custom agent with a goal:

```bash
./build/install/golem-cli/bin/golem-cli agent run my-coder 'review the build.gradle.kts files'
```

```bash
./build/install/golem-cli/bin/golem-cli agent run reviewer 'check for security issues in src/'
```

---

### `golem process` — Deterministic Process Workflows

**Subcommands:** `list`, `run`, `status`, `cancel`, `approve`, `reject`

Processes are deterministic DAGs (e.g., compile → test → git_commit → human_approval) where AI fills variables within typed steps.

**`golem process list`** — List available process templates:

```bash
./build/install/golem-cli/bin/golem-cli process list
```

```
Available process templates: code-review, deploy, refactor
```

**`golem process run <template> <goal>`** — Run a process template:

```bash
./build/install/golem-cli/bin/golem-cli process run code-review 'review the auth module'
```

```bash
./build/install/golem-cli/bin/golem-cli process run deploy --project :golem-cli 'build and deploy'
```

**`golem process status <run-id>`** — Check process status:

```bash
./build/install/golem-cli/bin/golem-cli process status proc_abc123
```

```
Run: proc_abc123
  template: code-review
  status: RUNNING
  step: compile_project
```

**`golem process cancel <run-id>`** — Cancel a running process:

```bash
./build/install/golem-cli/bin/golem-cli process cancel proc_abc123
```

```
Cancelled run proc_abc123
```

**`golem process approve <run-id>`** — Approve a gate decision:

```bash
./build/install/golem-cli/bin/golem-cli process approve proc_abc123 --notes "Looks good, proceed"
```

**`golem process reject <run-id>`** — Reject a gate decision:

```bash
./build/install/golem-cli/bin/golem-cli process reject proc_abc123 --notes "Needs more tests"
```

---

### `golem project` — JVM Project Intelligence

**Subcommands:** `scan`, `overview`, `symbol`

**`golem project scan`** — Force a full JVM project reindex:

```bash
./build/install/golem-cli/bin/golem-cli project scan
```

```
Indexed 3 module(s) in /home/user/project
:golem-core | sources=2 tests=1 deps=12
:golem-cli | sources=1 tests=1 deps=8
:golem-api | sources=1 tests=1 deps=5
```

Scan a specific directory:

```bash
./build/install/golem-cli/bin/golem-cli --workdir /path/to/other-project project scan
```

**`golem project overview`** — Print the JVM module tree:

```bash
./build/install/golem-cli/bin/golem-cli project overview
```

```
Project: /home/user/project
Modules (3):
- :golem-core (root)
  path: .
  sources: src/main/kotlin
  tests: src/test/kotlin
- :golem-cli
  path: golem-cli
  sources: src/main/kotlin
- :golem-api
  path: golem-api
```

**`golem project symbol <name>`** — Lookup a JVM symbol:

```bash
./build/install/golem-cli/bin/golem-cli project symbol GolemAgent
```

```
:golem-core CLASS GolemAgent GolemAgent.kt:25:1
:golem-core FUNCTION GolemAgent.run GolemAgent.kt:42:1
```

Filter by module:

```bash
./build/install/golem-cli/bin/golem-cli project symbol ToolRegistry --module :golem-core
```

Filter by kind:

```bash
./build/install/golem-cli/bin/golem-cli project symbol run --kind function
```

---

### `golem workflow` — Multi-Step Workflows

**Subcommands:** `run`

**`golem workflow run <name> <goal>`** — Run a predefined workflow:

```bash
./build/install/golem-cli/bin/golem-cli workflow run code-review 'review the new API endpoint'
```

```bash
./build/install/golem-cli/bin/golem-cli workflow run jvm-debug 'investigate the NullPointerException in UserService'
```

```bash
./build/install/golem-cli/bin/golem-cli workflow run jvm-refactor 'extract the authentication logic into a separate module'
```

```bash
./build/install/golem-cli/bin/golem-cli workflow run jvm-migration 'migrate from RxJava to coroutines'
```

Built-in workflows: `code-review`, `jvm-debug`, `jvm-refactor`, `jvm-migration`

---

### `golem team` — Parallel Agent Teams

**Subcommands:** `run`

**`golem team run`** — Run a team of agents in parallel:

```bash
./build/install/golem-cli/bin/golem-cli team run \
  --agents my-coder,reviewer \
  --goal 'implement a new REST endpoint for user profiles'
```

---

### `golem skill` — Reusable Agent Skills

**Subcommands:** `list`, `run`, `install`

Skills are YAML files defining a reusable agent persona + behavior. Stored in `~/.golem/skills/`.

**`golem skill list`** — List all installed skills:

```bash
./build/install/golem-cli/bin/golem-cli skill list
```

```
Installed Skills (/home/user/.golem/skills):
────────────────────────────────────────────────────────────
• analyze-dependency — Analyze Maven/Gradle dependency trees [jvm]
• generate-test — Generate unit tests for a Kotlin class [testing]

2 skill(s)
```

**`golem skill run <name> <goal>`** — Run a skill:

```bash
./build/install/golem-cli/bin/golem-cli skill run generate-test 'create tests for UserService.kt'
```

```bash
./build/install/golem-cli/bin/golem-cli skill run analyze-dependency 'check for conflicts in the build graph'
```

**`golem skill install <path>`** — Install a skill YAML file:

```bash
./build/install/golem-cli/bin/golem-cli skill install ~/Downloads/my-skill.yaml
```

```
✅ Installed skill 'my-skill' to /home/user/.golem/skills/my-skill.yaml
```

---

### `golem persona` — Persona Pocket

**Subcommands:** `list`, `show`, `sync`

Personas are Markdown files with YAML frontmatter in `~/.golem/people/`.

**`golem persona list`** — List all stored personas:

```bash
./build/install/golem-cli/bin/golem-cli persona list
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

**`golem persona show <name>`** — Show full persona details:

```bash
./build/install/golem-cli/bin/golem-cli persona show senior-dev
```

```
Name: senior-dev
Role: Senior Kotlin Developer
Tags: kotlin, jvm, coroutines
Summary: 15 years JVM experience
--- Body ---
You are a senior Kotlin developer...
```

**`golem persona sync`** — Sync personas from `~/.golem/people/` to the SQLite store:

```bash
./build/install/golem-cli/bin/golem-cli persona sync
```

```
Syncing from: /home/user/.golem/people
Sync complete.
Total personas: 3
```

Activate a persona by name when starting Golem:

```bash
./build/install/golem-cli/bin/golem-cli --persona-name senior-dev 'review this code'
```

---

### `golem mcp` — Manage MCP Client Connections

**Subcommands:** `add`, `list`, `remove`, `reconnect`

MCP client connections allow Golem to consume tools from external MCP servers (e.g., a local Playwright server, a database MCP server).

**`golem mcp add <name>`** — Add an MCP server connection:

Stdio transport:

```bash
./build/install/golem-cli/bin/golem-cli mcp add my-playwright \
  --cmd "npx" --args @playwright/mcp
```

SSE transport:

```bash
./build/install/golem-cli/bin/golem-cli mcp add remote-server \
  --url "http://localhost:8091/mcp"
```

Add disabled (connect later):

```bash
./build/install/golem-cli/bin/golem-cli mcp add db-server \
  --cmd "node" --args /path/to/server.js --disabled
```

**`golem mcp list`** — List all configured MCP servers:

```bash
./build/install/golem-cli/bin/golem-cli mcp list
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

**`golem mcp remove <name>`** — Remove an MCP server:

```bash
./build/install/golem-cli/bin/golem-cli mcp remove my-playwright
```

```
✅ Removed MCP server 'my-playwright'
```

**`golem mcp reconnect <name>`** — Reconnect to a disconnected server:

```bash
./build/install/golem-cli/bin/golem-cli mcp reconnect db-server
```

```
✅ Reconnected to MCP server 'db-server'
```

---

### `golem scheduler` — Scheduled Jobs

**Subcommands:** `add`, `list`, `remove`

Requires the daemon (`--daemon`) to be running to execute scheduled jobs.

**`golem scheduler add`** — Add a scheduled job:

```bash
./build/install/golem-cli/bin/golem-cli scheduler add \
  --name "daily-health-check" \
  --cron "0 8 * * *" \
  'check all services are running and report'
```

```bash
./build/install/golem-cli/bin/golem-cli scheduler add \
  --name "weekly-report" \
  --cron "0 9 * * 1" \
  --disabled \
  'generate the weekly status report'
```

**`golem scheduler list`** — List scheduled jobs:

```bash
./build/install/golem-cli/bin/golem-cli scheduler list
```

```
job_abc | daily-health-check | enabled=true | next=2026-05-15T08:00:00 | cron=0 8 * * *
job_def | weekly-report | enabled=false | next=2026-05-18T09:00:00 | cron=0 9 * * 1
```

**`golem scheduler remove <id>`** — Remove a scheduled job:

```bash
./build/install/golem-cli/bin/golem-cli scheduler remove job_def
```

```
Removed job job_def
```

---

### `golem pairing` — Pairing Info

**Subcommands:** `info`, `qrcode`

Used to pair Golem with the OpenClaw mobile app.

**`golem pairing info`** — Print connection details for pairing:

```bash
./build/install/golem-cli/bin/golem-cli pairing info
```

```
=== Golem Connection Info ===
Host: 192.168.1.42
Port: 8082
Token: a1b2c3d4-e5f6-...
...
```

**`golem pairing qrcode`** — Generate a QR code PNG for pairing:

```bash
./build/install/golem-cli/bin/golem-cli pairing qrcode
```

```
QR code saved to /home/user/qrcode.png
```

Save to a custom path:

```bash
./build/install/golem-cli/bin/golem-cli pairing qrcode --output ~/Desktop/pair.png
```

---

### `golem remote` — Connect to Remote Server

Connect to a remote Golem API server and interact via an interactive session.

```bash
./build/install/golem-cli/bin/golem-cli remote 192.168.1.42:8082 --api-key my-key
```

With TLS:

```bash
./build/install/golem-cli/bin/golem-cli remote golem.example.com:443 --api-key my-key --tls
```

Once connected:

```
Connecting to http://192.168.1.42:8082...
Server: {"status":"ok","version":"0.1.0"}
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
./gradlew :golem-cli:run --args="'my goal'"

# REPL
./build/install/golem-cli/bin/golem-cli

# API server
./build/install/golem-cli/bin/golem-cli --api --api-key secret

# MCP server
./build/install/golem-cli/bin/golem-cli --mcp

# Daemon (scheduler)
./build/install/golem-cli/bin/golem-cli --daemon

# Custom persona
./build/install/golem-cli/bin/golem-cli --persona ./AGENTS.md

# Run a custom agent
./build/install/golem-cli/bin/golem-cli agent run my-coder 'some goal'

# Check config
./build/install/golem-cli/bin/golem-cli config show

# Project intelligence
./build/install/golem-cli/bin/golem-cli project scan

# Workflow
./build/install/golem-cli/bin/golem-cli workflow run code-review 'review this PR'

# Skill
./build/install/golem-cli/bin/golem-cli skill run generate-test 'test UserService'
```
