# Advanced Features

Checkpoints, Metrics, Tracing, TTS, Token Compression, Delivery, Skills, and Architect Mode.

---

## 1. Checkpoints

Checkpoints capture the full agent conversation state (plus a `git diff HEAD` snapshot) to SQLite at configurable intervals. They enable crash recovery and session resumption.

### Architecture

- **`CheckpointManager`** — The public API: save, load, resume, list, delete checkpoints. Serializes/deserializes conversation state to/from JSON. Computes `git diff HEAD` snapshot at each save.
- **`CheckpointStore`** — SQLite persistence layer using Exposed ORM. Tables: `checkpoints` (id, session_id, turn_number, conversation_json, created_at, diff).
- **`CheckpointTools`** — Three LLM-accessible tools: `checkpoint_save`, `checkpoint_list`, `checkpoint_resume`.

### Auto-Checkpointing

When `auto-checkpoint: true` (default), the agent saves a checkpoint after every ReAct loop turn. Each checkpoint includes the current conversation and a `git diff HEAD` (truncated to 50KB).

### Resume

Resume a session from CLI:

```bash
spola --resume <session-id>
```

This loads the most recent checkpoint's conversation and replays it into the agent context. The agent continues where it left off.

### Checkpoint Tools

Three tools available to the agent (and via the API):

- **`checkpoint_save(sessionId, turnNumber, conversationJson)`** — Save a raw JSON conversation as a checkpoint. Validates JSON before saving.
- **`checkpoint_list()`** — List all checkpoints with IDs, session IDs, turn numbers, and timestamps.
- **`checkpoint_resume(sessionId)`** — Load the most recent checkpoint for a given session ID.

### API Endpoints

- `GET /api/checkpoint` — List all checkpoints
- `GET /api/checkpoint/{id}/diff` — Get git diff for a specific checkpoint
- `GET /api/checkpoint/session/{sessionId}/diffs` — List checkpoints with diffs for a session
- `GET /api/checkpoint/resume/{session_id}` — Resume a session (returns messages)

### Managing Checkpoints

```kotlin
val manager = CheckpointManager.fromConfig(config)

// Save
val id = manager.save(sessionId, turnNumber, conversation)

// List
val checkpoints: List<CheckpointData> = manager.list()

// Load
val state: CheckpointState? = manager.load(sessionId)

// Delete old
manager.deleteOlderThan("2026-01-01T00:00:00")

// Delete session
manager.deleteForSession(sessionId)
```

### Edge Cases

- If `git` is not available or the directory is not a git repo, the diff field is `null` (non-blocking)
- Large diffs are truncated to 50KB
- Session IDs are auto-generated as 16-char UUIDs
- If the agent uses 0 turns (immediate answer), no checkpoint is saved

---

## 2. Metrics

Spola exposes Prometheus-compatible metrics via the `simpleclient` library. Metrics are enabled by default (`metrics-enabled: true`).

### What's Measured

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `spola_agent_runs_total` | Counter | `status` | Total agent runs (success/fail) |
| `spola_agent_turns_total` | Counter | — | Total ReAct loop turns |
| `spola_tool_calls_total` | Counter | `tool`, `status` | Total tool calls |
| `spola_llm_calls_total` | Counter | `provider`, `model` | Total LLM calls |
| `spola_llm_tokens_total` | Counter | `type` (input/output) | Total tokens processed |
| `spola_scheduler_jobs_executed_total` | Counter | — | Scheduler job executions |
| `spola_agent_run_duration_seconds` | Histogram | — | Run duration buckets: 0.1, 1, 5, 15, 60 sec |
| `spola_tool_call_duration_seconds` | Histogram | `tool` | Tool execution duration |
| `spola_active_sessions` | Gauge | — | Concurrent active sessions |

### Metrics Endpoint

```
GET /api/metrics
```

Returns Prometheus text format (`text/plain; version=0.0.4`):

```
# HELP spola_agent_runs_total Total number of agent runs.
# TYPE spola_agent_runs_total counter
spola_agent_runs_total{status="success"} 42
spola_agent_runs_total{status="fail"} 3
# HELP spola_active_sessions Currently active agent sessions.
# TYPE spola_active_sessions gauge
spola_active_sessions 2
```

### History Endpoint

```
GET /api/metrics/history
```

Returns a JSON array of periodic snapshots showing counter values over time. Snapshots are recorded every 30 seconds (at most 120 points).

### MetricsObserver

An `AgentRunObserver` decorator that records metrics as agent events fire:

- `started` — Increments active sessions, starts timing
- `thinking` — Records turn
- `complete` — Records success duration, decrements active sessions
- `error` — Records failure duration, decrements active sessions
- `onToolCall` / `onToolResult` — Records tool call duration + success/fail
- `onLlmCall` / `onLlmResult` — Records provider, model, and token counts

### Config

```yaml
metrics-enabled: true   # Enable/disable all metrics (default: true)
```

When disabled, all recording methods are no-ops and no metrics are registered with the Prometheus registry.

---

## 3. Tracing

Spola supports distributed tracing via OpenTelemetry (OTLP/gRPC). When enabled, spans are created for agent runs, turns, LLM calls, and tool executions.

### Architecture

- **`SpolaTracer`** — Creates and manages OpenTelemetry span lifecycle. When `otelEnabled` is true and `otelEndpoint` is set, it creates an OTLP gRPC exporter with a `BatchSpanProcessor`. Otherwise uses `OpenTelemetry.noop()` — zero overhead.
- **`SpolaTracerObserver`** — An `AgentRunObserver` decorator that bridges agent events to the tracer's span API.

### Span Hierarchy

```
spola.run (root span)
  ├── spola.turn (child of root, attribute: spola.turn.number)
  │   ├── spola.llm.call (child of turn, attributes: gen_ai.system, gen_ai.request.model)
  │   └── spola.tool.execution (child of turn, attributes: spola.tool.name, spola.tool.call_id)
```

### Span Attributes

| Span | Attributes |
|------|------------|
| `spola.run` | `spola.service.name` |
| `spola.turn` | `spola.turn.number` |
| `spola.llm.call` | `gen_ai.system`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens` |
| `spola.tool.execution` | `spola.tool.name`, `spola.tool.call_id`, `spola.tool.success`, `spola.tool.duration_ms` |

### Config

```yaml
otel-enabled: true
otel-endpoint: http://localhost:4317
otel-service-name: spola-prod
```

- **`otel-enabled`** — Enable/disable tracing (default: `false`)
- **`otel-endpoint`** — OTLP gRPC endpoint (required when enabled)
- **`otel-service-name`** — Service name for trace identification (default: `"spola"`)

### Exporter Configuration

The OTLP exporter uses:
- gRPC transport
- 30-second connection timeout
- 5-second batch schedule delay
- Batch span processor

### Edge Cases

- If tracing is disabled, all span methods are no-ops (zero allocation overhead)
- If the endpoint is unreachable, spans are buffered and dropped after the batch processor timeout
- Spans include no user data (conversation content, file paths) — only metadata
- The tracer is `AutoCloseable` and shuts down the SDK provider on close

---

## 4. TTS (Text-to-Speech)

Spola can synthesize speech from text using either Edge TTS (free, local) or ElevenLabs (cloud, high quality).

### TtsProvider Interface

```kotlin
interface TtsProvider {
    val name: String
    suspend fun synthesize(text: String, voice: String? = null): ByteArray
}
```

### EdgeTtsProvider

- **Name**: `"edge"`
- **How it works**: Calls the `edge-tts` Python CLI tool via `ProcessBuilder`. The CLI writes audio to stdout, which is captured as a byte array.
- **Fallback**: If `edge-tts` is not installed, generates a 200ms, 440Hz sine wave beep WAV file (audible confirmation even without TTS).
- **Output format**: WAV
- **Prerequisite**: `pip install edge-tts`

### ElevenLabsTtsProvider

- **Name**: `"elevenlabs"`
- **How it works**: Calls the ElevenLabs REST API (`POST /v1/text-to-speech/{voiceId}`) with the `eleven_monolingual_v1` model.
- **Default voice**: `21m00Tcm4TlvDq8ikWAM` (Rachel)
- **Output format**: MP3
- **Requires**: API key in config or environment

### TTS Tool

The `tts_say` tool is registered for the agent:

```kotlin
tts_say(
    text: string,        // required — text to synthesize
    voice: string?,      // optional — provider-specific voice ID
    provider: string?,   // optional — "elevenlabs" or "edge" override
    output_path: string? // optional — custom output path
)
```

- Audio files are saved to `~/.spola/audio/tts_{timestamp}_{provider}.{ext}`
- ElevenLabs produces `.mp3`, Edge produces `.wav`
- The provider is auto-selected: ElevenLabs if `elevenlabsApiKey` is configured, otherwise Edge

### Config

```yaml
tts:
  provider: edge                        # "edge" or "elevenlabs"
  elevenlabs-api-key: ${ELEVENLABS_API_KEY}
  elevenlabs-voice-id: 21m00Tcm4TlvDq8ikWAM
```

### Provider Selection Logic

At `registerTtsTool()` time:
1. If `elevenlabsApiKey` is set in config → default to `ElevenLabsTtsProvider`
2. Otherwise → default to `EdgeTtsProvider`
3. The tool's `provider` parameter can override the default per-call

### Error Handling

- ElevenLabs 401 → "Unauthorized — check API key"
- ElevenLabs 422 → "Unprocessable entity" with response body
- Edge TTS CLI not found → generates beep WAV (no crash)
- Edge TTS exit code != 0 → throws `TtsException` with stderr output

---

## 5. Token Compression (TokenJuice)

TokenJuice compresses verbose tool output before it enters the LLM context window, reducing token consumption and keeping the agent focused on relevant information.

### Architecture

- **`TokenJuice`** — Singleton compression engine. Applies a pipeline of compression strategies to tool output.
- **`TokenJuiceRule`** — Associates a tool name pattern (`*`-glob) with a list of `CompressionStrategy` values.
- **`BuiltinRules`** — Predefined rules for common tools.

### Compression Strategies

| Strategy | Description |
|----------|-------------|
| `STRIP_ANSI` | Remove ANSI escape sequences (terminal color codes) |
| `DEDUP_LINES` | Remove consecutive duplicate lines |
| `SUMMARIZE_STATS` | Extract `+N/-N` diff stats from git-like output |
| `GROUP_BY_PREFIX` | Group lines by common prefix, show count for large groups |
| `ERROR_ONLY` | Keep only lines matching "error", "fail", or "FAILED" |
| `SMART_TRUNCATE` | Keep first 70% + last 30% with truncation notice |

### Built-in Rules

| Tool | Strategies |
|------|------------|
| `git_diff` | SUMMARIZE_STATS + SMART_TRUNCATE |
| `git_status` | SUMMARIZE_STATS |
| `git_log` | SMART_TRUNCATE |
| `git_commit` | SUMMARIZE_STATS |
| `shell` | STRIP_ANSI + DEDUP_LINES + SMART_TRUNCATE |
| `read_file` | SMART_TRUNCATE |
| `search_files` | GROUP_BY_PREFIX + SMART_TRUNCATE |
| `write_file` | SMART_TRUNCATE |
| `web_fetch` | STRIP_ANSI + SMART_TRUNCATE |
| `web_search` | SMART_TRUNCATE |
| `memory_search` | SUMMARIZE_STATS |
| `task_list` | SUMMARIZE_STATS |
| `edit_file` | SUMMARIZE_STATS |
| `scheduler_list` | SUMMARIZE_STATS |

### When Compression Triggers

- Non-truncation strategies (`STRIP_ANSI`, `DEDUP_LINES`, `SUMMARIZE_STATS`, `GROUP_BY_PREFIX`, `ERROR_ONLY`) apply to all output regardless of size
- `SMART_TRUNCATE` only activates when output exceeds 4000 characters
- After compression, a `[TokenJuice: -N chars]` footer is appended
- If the compressed output + footer is not shorter than the original, the original is kept

### Implementation Detail

Compression is wired into the agent's tool result handling. After a tool executes, `maybeCompressToolResult()` applies TokenJuice:

```kotlin
internal fun maybeCompressToolResult(
    toolName: String,
    result: ToolResult,
    compressionEnabled: Boolean,
): ToolResult {
    val compressed = TokenJuice.compact(toolName, result.output, compressionEnabled)
    if (compressed == result.output) return result
    val saved = result.output.length - compressed.length
    val finalOutput = "$compressed\n\n[TokenJuice: -${saved} chars]"
    return if (finalOutput.length < result.output.length) {
        ToolResult(result.success, finalOutput)
    } else {
        result
    }
}
```

### Config

```yaml
compression-enabled: true  # Set false to bypass all compression
```

Disable at runtime with `--no-compression` (CLI flag, when added).

### Smart Truncation Algorithm

`smartTruncate(text, maxChars=4000, headRatio=0.7)`:
- Reserves 60 characters for the truncation notice
- Keeps first 70% of usable chars (head), last 30% (tail)
- Inserts `... [N more chars truncated]` between head and tail

---

## 6. Delivery (Telegram & Email)

Spola can send messages via Telegram Bot API and SMTP email. These are available as agent tools and as process engine step types.

### Telegram

**Config:**

```yaml
telegram-bot-token: ${TELEGRAM_BOT_TOKEN}
```

Falls back to `TELEGRAM_BOT_TOKEN` environment variable.

**Tool: `telegram_send`**

```
telegram_send(
    chat_id: string,   // required — numeric ID or @channelusername
    text: string       // required — message text (max 4096 chars)
)
```

- Messages are sent with `parse_mode: Markdown`
- 401 response → "Unauthorized — check bot token"
- 400 response → includes error body
- Timeout: 15 seconds

### Email

**Config:**

```yaml
email:
  smtp-host: smtp.gmail.com
  smtp-port: 587
  username: ${EMAIL_USERNAME}
  password: ${EMAIL_PASSWORD}
  from: spola@example.com
```

Each field also falls back to an environment variable: `EMAIL_SMTP_HOST`, `EMAIL_SMTP_PORT`, `EMAIL_USERNAME`, `EMAIL_PASSWORD`, `EMAIL_FROM`.

**Tool: `email_send`**

```
email_send(
    to: string,        // required — recipient email
    subject: string,   // required — email subject
    body: string       // required — plain text body
)
```

- Uses STARTTLS (port 587, `mail.smtp.starttls.enable: true`)
- Connection timeout: 10 seconds
- Message timeout: 15 seconds
- Validates that `to` contains `@`

### Process Engine Integration

The `telegram_notify` executor is a built-in process engine step type that uses the same Telegram configuration to send notifications as part of deterministic workflow DAGs.

### Edge Cases

- Telegram message limited to 4096 characters (tool validates and rejects longer messages)
- Email requires all four SMTP fields (host, username, password, from) — missing any produces a clear error message
- Delivery tools do NOT expose credentials in error messages
- Network timeouts produce distinct `HttpTimeoutException` error messages

---

## 7. Skills

Skills are reusable agent capabilities defined as YAML files in `~/.spola/skills/`. A skill defines a focused agent persona, a set of allowed tools, and filesystem restrictions — enabling purpose-built agents for common tasks.

### SkillDefinition

```kotlin
data class SkillDefinition(
    val name: String = "",                    // Derived from filename if blank
    val description: String = "",
    val version: Int = 1,
    val prompt: String = "",                  // System prompt for the agent persona
    val tools_allowed: List<String> = [],     // Empty = all tools available
    val filesystem_access: String = "read-write",  // "read-write", "read-only", "none"
    val tags: List<String> = [],
)
```

### Skill YAML Format

```yaml
# ~/.spola/skills/code-review.yaml
name: code-review
description: Review code changes for bugs, style issues, and security concerns
version: 1
promtext: |
  You are a senior code reviewer. Analyze the provided code changes
  for correctness, style, performance, and security issues.
  Provide specific, actionable feedback.
tools_allowed:
  - read_file
  - search_files
  - git_diff
  - git_log
filesystem_access: read-only
tags:
  - code
  - review
  - quality
```

### SkillLoader

- Scans `~/.spola/skills/` for `.yaml` and `.yml` files
- Filename stem (without extension) becomes the skill name if `name` is blank
- Invalid YAML files are skipped with a warning
- Also supports `writeToFile()` and `deleteFile()` for programmatic management

### Skill Tools

Two tools available to the agent:

- **`skill_list(tag: string?)`** — List all installed skills, optionally filtered by tag
- **`skill_run(skill_name: string, goal: string)`** — Run a skill as a sub-agent with a specific goal

When `skill_run` executes, it:
1. Loads the skill definition from YAML
2. Creates a new `SpolaInstance` via `SpolaFactory`
3. Overrides the agent persona with the skill's prompt
4. Runs the goal as a one-shot agent request
5. Returns the result

### CLI Commands

```bash
spola skill list                    # List all installed skills
spola skill run <name> "<goal>"     # Run a skill with a goal
spola skill install <path>          # Install a skill YAML file
```

### Skill Properties

- **`prompt`** — Complete system prompt that defines the agent's role, constraints, and output format. Replaces the default persona entirely.
- **`tools_allowed`** — If empty, the agent can use all tools. If specified, only listed tools are available (though the current implementation doesn't enforce this restriction — the skill sub-agent gets the full tool registry).
- **`filesystem_access`** — Intended constraint level (`read-write`, `read-only`, `none`). Displayed in listings but not enforced by the current implementation.
- **`tags`** — Used for filtering in `skill_list(tag: ...)`.

---

## 8. Architect Mode

Architect mode implements a **two-phase planning-and-execution** pattern using separate LLM models for each phase.

### How It Works

**Phase 1 — Architect (Planning):**
- Uses a cheaper/faster model (default: `gpt-4o-mini`)
- Gets a restricted tool set: **read-only** tools only
  - Can read files, search, use git_diff/status/log, and browse the web
  - Cannot write files, edit files, run shell commands, or commit/push git
- Produces a detailed implementation plan

**Phase 2 — Editor (Implementation):**
- Uses a more powerful model (default: `gpt-4o`)
- Gets **full** tool access: read, write, edit, shell, git, web
- Receives the architect's entire plan as system context
- Implements the plan faithfully

### ArchitectConfig

```kotlin
data class ArchitectConfig(
    val architectModel: String = "gpt-4o-mini",
    val architectProvider: String = "openai",
    val editorModel: String = "gpt-4o",
    val editorProvider: String = "openai",
    val enabled: Boolean = false,
)
```

### ArchitectMode Prompt Injection

The architect phase appends to the persona:

```
# ARCHITECT MODE — PHASE 1: PLANNING
You are in the **Architect Phase**. Your role is to analyze the request,
research the codebase, and produce a detailed implementation plan.

## Constraints
- You CAN read, search, and explore files to understand the codebase.
- You CAN use git_diff to see current changes.
- You CAN use web_search to look up documentation or APIs.
- You CANNOT write or edit files.
- You CANNOT execute shell commands.
- You CANNOT commit or push to git repositories.

## Required Output
1. **Files to create** — with path and purpose
2. **Files to modify** — with path and what changes are needed
3. **Implementation order** — dependencies between changes
4. **Key design decisions** — alternatives considered and rationale
```

The editor phase appends:

```
# ARCHITECT MODE — PHASE 2: IMPLEMENTATION
You are in the **Editor Phase**. You have received a detailed plan from the Architect.
Your job is to implement it faithfully.

## The Architect's Plan
{full plan text}

## Instructions
- Implement the plan step by step.
- You have full access to all tools: read, write, edit, shell, git.
- If you discover issues with the plan, adapt but communicate your changes.
- When done, provide a summary of what was implemented.
```

### Config

```yaml
architect-mode:
  enabled: true
  architect-model: gpt-4o-mini
  architect-provider: openai
  editor-model: gpt-4o
  editor-provider: openai
```

### Return Value

`ArchitectRunResult` contains both phases' output:

```kotlin
class ArchitectRunResult(
    val plan: String,           // Architect's step-by-step plan
    val implementation: String, // Editor's execution result
)
```

### Provider Resolution

Both phases use `ProviderStore.fromEnvironment()` to resolve credentials. Custom providers defined in `config.yaml` (`providers` section) are available. This means the architect and editor can use different providers entirely (e.g., architect on Ollama, editor on OpenAI).

### Use Cases

- **Large refactors** — Architect researches the codebase first, then the editor implements
- **Complex feature development** — Architect designs the approach, editor handles the implementation details
- **Security-sensitive changes** — Architect identifies all files that need changes before any writes occur
- **Cost optimization** — Use a cheap model for planning and an expensive one only for implementation
