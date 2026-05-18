# Configuration Reference

Golem is configured through a YAML file at `~/.golem/config.yaml` combined with CLI flags. The system uses a **three-layer merge** where CLI flags always win.

## Three-Layer Merge

Configuration is resolved in this priority order (later overrides earlier):

1. **Code defaults** — Hardcoded in `GolemConfig` data class
2. **Config file** — `~/.golem/config.yaml` (or `--config <path>`)
3. **CLI flags** — Explicitly-provided flags override everything

Merge logic: if a CLI flag was **not explicitly provided**, the config file value (or default) is used. This means `--model gpt-5` overrides config, but omitting `--model` uses whatever the config file says.

## All Configuration Keys

### Core Agent

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `model` | `model` | `"gpt-4o"` | LLM model identifier |
| `provider` | `provider` | `"openai"` | Provider name (`openai`, `anthropic`, `openai-compat`) |
| `workingDirectory` | `workdir` | `"."` | Working directory for file operations |
| `maxTurns` | `max-turns` | `25` | Maximum ReAct loop turns before forced stop |
| `temperature` | `temperature` | `null` | LLM temperature override (null = provider default) |
| `maxTokens` | `max-tokens` | `null` | Max output tokens (null = provider default) |
| `apiKey` | `api-key` | `null` | API key; falls back to `GOLEM_API_KEY` env var |

### Persona

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `personaPath` | `persona` | `null` | Path to AGENTS.md / CLAUDE.md persona file |
| `activePersonaName` | `persona-name` | `null` | Name of active persona from `~/.golem/people/` |

### Database Paths

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `memoryDbPath` | `memory-db` | `"./.golem/memory.db"` | SQLite memory store |
| `schedulerDbPath` | `scheduler-db` | `"./.golem/scheduler.db"` | Scheduler job store |
| `kanbanDbPath` | `kanban-db` | `"./.golem/kanban.db"` | Kanban board store |
| `checkpointDbPath` | `checkpoint-db` | `"./.golem/checkpoint.db"` | Checkpoint snapshots |
| `jvmIndexDbPath` | `jvm-index-db` | `"./.golem/jvm-index.db"` | JVM project index |
| `sessionsDbPath` | `sessions-db` | `"./.golem/sessions.db"` | Session persistence |
| `agentsDbPath` | `agents-db` | `"./.golem/agents.db"` | Custom agent definitions |
| `personaDbPath` | *(not in YAML)* | `"~/.golem/persona.db"` | Persona Pocket SQLite store |

### Delivery

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `telegramBotToken` | `telegram-bot-token` | `null` | Telegram bot token (env: `TELEGRAM_BOT_TOKEN`) |
| `emailSmtpHost` | `email.smtp-host` | `null` | SMTP server hostname (env: `EMAIL_SMTP_HOST`) |
| `emailSmtpPort` | `email.smtp-port` | `587` | SMTP port (env: `EMAIL_SMTP_PORT`) |
| `emailUsername` | `email.username` | `null` | SMTP username (env: `EMAIL_USERNAME`) |
| `emailPassword` | `email.password` | `null` | SMTP password (env: `EMAIL_PASSWORD`) |
| `emailFrom` | `email.from` | `null` | From address (env: `EMAIL_FROM`) |

### TTS

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `ttsProvider` | `tts.provider` | `"edge"` | TTS provider: `"edge"` or `"elevenlabs"` |
| `elevenlabsApiKey` | `tts.elevenlabs-api-key` | `null` | ElevenLabs API key |
| `elevenlabsVoiceId` | `tts.elevenlabs-voice-id` | `"21m00Tcm4TlvDq8ikWAM"` | ElevenLabs voice ID (Rachel) |

### Observability

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `otelEnabled` | `otel-enabled` | `false` | Enable OpenTelemetry tracing |
| `otelEndpoint` | `otel-endpoint` | `null` | OTLP gRPC endpoint (e.g., `http://localhost:4317`) |
| `otelServiceName` | `otel-service-name` | `"golem"` | Service name for traces |
| `metricsEnabled` | `metrics-enabled` | `true` | Enable Prometheus metrics |

### Compression

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `compressionEnabled` | `compression-enabled` | `true` | Enable TokenJuice context compression |

### Checkpoints

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `autoCheckpoint` | `auto-checkpoint` | `true` | Auto-save checkpoint every turn |

### Plugins

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `pluginsEnabled` | `plugins-enabled` | `true` | Enable plugin loading |
| `pluginsDir` | `plugins-dir` | `"~/.golem/plugins"` | Directory for plugin JARs |

### Agents

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `agentsDir` | `agents-dir` | `"~/.golem/agents"` | Custom agent definitions |
| `defaultAgentId` | `default-agent-id` | `null` | Default agent for new sessions |

### JVM Index

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `jvmIndexAutoRefresh` | `jvm-index-auto-refresh` | `true` | Auto-refresh project index |

### Architect Mode

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `architectMode.enabled` | `architect-mode.enabled` | `false` | Enable two-phase architect mode |
| `architectMode.architectModel` | `architect-mode.architect-model` | `"gpt-4o-mini"` | Model for architect (planning) phase |
| `architectMode.architectProvider` | `architect-mode.architect-provider` | `"openai"` | Provider for architect phase |
| `architectMode.editorModel` | `architect-mode.editor-model` | `"gpt-4o"` | Model for editor (implementation) phase |
| `architectMode.editorProvider` | `architect-mode.editor-provider` | `"openai"` | Provider for editor phase |

### Security

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `insecure` | `insecure` | `false` | Allow binding to 0.0.0.0 without API key |
| `unsafe` | `unsafe` | `false` | Disable path restrictions (can read/write any file) |
| `tlsCertPath` | `tls-cert-path` | `null` | TLS certificate PEM file path |
| `tlsKeyPath` | `tls-key-path` | `null` | TLS private key PEM file path |

### Other

| Key | Config File Key | Default | Description |
|-----|----------------|---------|-------------|
| `pairingToken` | `pairing-token` | `null` | Pairing token for remote connections |
| `sessionId` | *(CLI only)* | `null` | Session ID for checkpoint resume (`--resume`) |

## Custom Providers

Define custom LLM providers in your config file under the `providers` key. Each provider has a `type` (provider type), `base-url` (API endpoint), `api-key`, and `models` list.

```yaml
providers:
  my-ollama:
    type: openai-compat
    base-url: http://localhost:11434/v1
    api-key: ""
    models:
      - llama3.2
  my-groq:
    type: openai-compat
    base-url: https://api.groq.com/openai/v1
    api-key: ${GROQ_API_KEY}
    models:
      - mixtral-8x7b-32768
```

Use `--provider my-ollama --model llama3.2` to select a custom provider.

## Environment Variable Substitution

Config values support `${VAR}` syntax — the placeholder is replaced with the value of the environment variable at load time. If the variable is unset, it resolves to empty string.

```yaml
api-key: ${GOLEM_API_KEY}
telegram-bot-token: ${TELEGRAM_BOT_TOKEN}
```

This is the recommended way to handle secrets — never hardcode API keys in `config.yaml`.

## CLI Commands

```
golem config show   — Print the current effective configuration (merged)
golem config path   — Print the config file path
golem config init   — Create a default ~/.golem/config.yaml
```

`golem config init` will refuse to overwrite an existing config file.

## CLI Flags

| Flag | Maps To | Default |
|------|---------|---------|
| `--model` | `model` | `gpt-4o` |
| `--provider` | `provider` | `openai` |
| `--config` | Config path | `~/.golem/config.yaml` |
| `--dir` / `--workdir` | `workingDirectory` | `.` |
| `--persona` | `personaPath` | `null` |
| `--persona-name` | `activePersonaName` | `null` |
| `--max-turns` | `maxTurns` | `25` |
| `--memory-db` | `memoryDbPath` | `./.golem/memory.db` |
| `--scheduler-db` | `schedulerDbPath` | `./.golem/scheduler.db` |
| `--kanban-db` | `kanbanDbPath` | `./.golem/kanban.db` |
| `--jvm-index-db` | `jvmIndexDbPath` | `./.golem/jvm-index.db` |
| `--api-key` | `apiKey` | `null` |
| `--api-port` | (API server) | `8082` |
| `--mcp-port` | (MCP server) | `8091` |
| `--insecure` | `insecure` | `false` |
| `--unsafe` | `unsafe` | `false` |
| `--resume` / `--session-id` | `sessionId` | `null` |
| `--tls-cert` | `tlsCertPath` | `null` |
| `--tls-key` | `tlsKeyPath` | `null` |

## Security

- The config file can contain API keys and tokens. It should be readable only by the owning user.
- Golem checks file permissions at startup. If the file is world-readable, it prints a warning and suggests `chmod 600`.
- Prefer environment variables (`${VAR}`) for secrets over hardcoded values.
- The `insecure` flag disables API key enforcement (use with caution).
- The `unsafe` flag removes all filesystem path restrictions from the agent.

## Complete Example Config

```yaml
# ~/.golem/config.yaml
# Use ${VAR} syntax for secrets — they resolve from environment variables.

# --- Core Agent ---
model: gpt-4o
provider: openai
workdir: /home/user/my-project
max-turns: 50
temperature: 0.3
max-tokens: 4096
persona: ./AGENTS.md
api-key: ${GOLEM_API_KEY}

# --- Database Paths ---
memory-db: ./.golem/memory.db
scheduler-db: ./.golem/scheduler.db
kanban-db: ./.golem/kanban.db
checkpoint-db: ./.golem/checkpoint.db
jvm-index-db: ./.golem/jvm-index.db
sessions-db: ./.golem/sessions.db

# --- Delivery (Telegram) ---
telegram-bot-token: ${TELEGRAM_BOT_TOKEN}

# --- Delivery (Email) ---
email:
  smtp-host: smtp.gmail.com
  smtp-port: 587
  username: ${EMAIL_USERNAME}
  password: ${EMAIL_PASSWORD}
  from: golem-agent@example.com

# --- TTS ---
tts:
  provider: elevenlabs
  elevenlabs-api-key: ${ELEVENLABS_API_KEY}
  elevenlabs-voice-id: 21m00Tcm4TlvDq8ikWAM

# --- Observability ---
otel-enabled: true
otel-endpoint: http://localhost:4317
otel-service-name: golem-prod
metrics-enabled: true

# --- Features ---
compression-enabled: true
auto-checkpoint: true
plugins-enabled: true
jvm-index-auto-refresh: true

# --- Security ---
insecure: false
unsafe: false

# --- Architect Mode ---
architect-mode:
  enabled: true
  architect-model: gpt-4o-mini
  architect-provider: openai
  editor-model: gpt-4o
  editor-provider: openai

# --- Custom Providers ---
providers:
  local-llama:
    type: openai-compat
    base-url: http://localhost:11434/v1
    api-key: ""
    models:
      - llama3.2
  anthropic-sonnet:
    type: anthropic
    api-key: ${ANTHROPIC_API_KEY}
    models:
      - claude-sonnet-4-20250514
```
