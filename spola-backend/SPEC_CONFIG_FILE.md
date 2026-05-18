# SPEC: Config File for Golem (golem.yaml)

## Status

**Accepted** — May 2026 (after Codex + Gemini review)

## Executive Summary

Golem currently has **no user-facing configuration file**. All settings are hardcoded in `GolemConfig` data class defaults or passed as CLI flags and environment variables. Adding or changing a provider requires editing Kotlin code and recompiling.

This spec adds `~/.golem/config.yaml` — a YAML config file that overlays on top of CLI flags. This matches what Hermes (`config.yaml`), OpenClaw, and other agent frameworks provide: a single editable file where users define models, providers, directories, and delivery settings without touching code.

## Design Decisions

1. **YAML format** — consistent with the two existing YAML loaders in the project (`SkillLoader`, `AgentLoader`). Jackson YAML 2.18.3 is already a dependency of `golem-core`.
2. **CLI flags win over config file** — CLI arg `--model Qwen3` overrides the config file's `model:` value. Config file values override hardcoded defaults in `GolemConfig`.
3. **No breaking changes** — all existing CLI flags and env vars continue to work unchanged. The config file is purely additive.
4. **Custom providers in config** — a `providers:` section lets users define API-compatible endpoints (llama.cpp, Ollama, custom OpenAI-compat, etc.) without code changes. These extend but do NOT replace the 5 built-in providers in `ProviderStore`.
5. **Secrets via env var only** — all sensitive values (api keys, tokens, passwords) should be set via environment variables, not stored in the config file. The YAML format supports `${SECRET_VAR}` syntax to reference env vars at load time.

## Config File Format

### Default location: `~/.golem/config.yaml`

Overridable via `--config /path/to/config.yaml` CLI flag.

```yaml
# ~/.golem/config.yaml — Golem agent configuration
# Security: chmod 600 this file if you store secrets in it.
# Prefer environment variables for secrets: ${ENV_VAR_NAME}

# Model / provider (default provider if no --provider CLI flag given)
model: Qwen3-30B-A3B-Instruct-2507-UD-IQ3_XXS.gguf
provider: llamaccp

# Directories
workdir: ~/Development/golem
memory-db: ~/.golem/data/memory.db
scheduler-db: ~/.golem/data/scheduler.db
kanban-db: ~/.golem/data/kanban.db
checkpoint-db: ~/.golem/data/checkpoint.db
jvm-index-db: ~/.golem/data/jvm-index.db
sessions-db: ~/.golem/data/sessions.db

# Agent behavior
max-turns: 25
temperature: 0.7
max-tokens: 4096
persona: ~/AGENTS.md

# API security — use ${VAR} to reference env vars
api-key: ${GOLEM_API_KEY}
insecure: false
unsafe: false

# Delivery (Telegram, Email) — use ${VAR} for tokens and passwords
telegram-bot-token: ${TELEGRAM_BOT_TOKEN}
email:
  smtp-host: smtp.privateemail.com
  smtp-port: 587
  username: ${EMAIL_USERNAME}
  password: ${EMAIL_PASSWORD}
  from: ${EMAIL_FROM}

# TTS
tts:
  provider: edge
  elevenlabs-api-key: ${ELEVENLABS_API_KEY}
  elevenlabs-voice-id: 21m00Tcm4TlvDq8ikWAM

# Custom providers — define new or override existing
providers:
  llamaccp:
    type: openai-compat
    base-url: http://localhost:8090/v1
    api-key: noop
    # models list is informational only — not enforced at runtime

  ollama-local:
    type: openai-compat
    base-url: http://localhost:11434/v1
    api-key: ollama

  my-groq:
    type: openai-compat
    base-url: https://api.groq.com/openai/v1
    api-key: ${GROQ_API_KEY}
```

### File Format Specification

#### Top-level keys

| Key | Type | Maps to | Default |
|-----|------|---------|---------|
| `model` | string | `GolemConfig.model` | `"gpt-4o"` |
| `provider` | string | `GolemConfig.provider` | `"openai"` |
| `workdir` | string | `GolemConfig.workingDirectory` | `"."` |
| `memory-db` | string | `GolemConfig.memoryDbPath` | `"./.golem/memory.db"` |
| `scheduler-db` | string | `GolemConfig.schedulerDbPath` | `"./.golem/scheduler.db"` |
| `kanban-db` | string | `GolemConfig.kanbanDbPath` | `"./.golem/kanban.db"` |
| `checkpoint-db` | string | `GolemConfig.checkpointDbPath` | `"./.golem/checkpoint.db"` |
| `jvm-index-db` | string | `GolemConfig.jvmIndexDbPath` | `"./.golem/jvm-index.db"` |
| `sessions-db` | string | `GolemConfig.sessionsDbPath` | `"./.golem/sessions.db"` |
| `max-turns` | int | `GolemConfig.maxTurns` | `25` |
| `temperature` | double? | `GolemConfig.temperature` | `null` |
| `max-tokens` | int? | `GolemConfig.maxTokens` | `null` |
| `persona` | string? | `GolemConfig.personaPath` | `null` |
| `api-key` | string? | `GolemConfig.apiKey` | env `GOLEM_API_KEY` |
| `insecure` | bool | `GolemConfig.insecure` | `false` |
| `unsafe` | bool | `GolemConfig.unsafe` | `false` |
| `telegram-bot-token` | string? | `GolemConfig.telegramBotToken` | `null` |
| `email` | object? | (see below) | `null` |
| `tts` | object? | (see below) | `null` |
| `providers` | map | custom provider definitions | `{}` |

#### `email:` sub-keys

| Key | Type | Maps to |
|-----|------|---------|
| `smtp-host` | string | `GolemConfig.emailSmtpHost` |
| `smtp-port` | int | `GolemConfig.emailSmtpPort` (default: 587) |
| `username` | string | `GolemConfig.emailUsername` |
| `password` | string | `GolemConfig.emailPassword` |
| `from` | string | `GolemConfig.emailFrom` |

#### `tts:` sub-keys

| Key | Type | Maps to |
|-----|------|---------|
| `provider` | string | `GolemConfig.ttsProvider` (default: `"edge"`) |
| `elevenlabs-api-key` | string? | `GolemConfig.elevenlabsApiKey` |
| `elevenlabs-voice-id` | string | `GolemConfig.elevenlabsVoiceId` (default: `"21m00Tcm4TlvDq8ikWAM"`) |

#### `providers:` entries

Each key is a provider name (e.g., `llamaccp`, `ollama-local`, `my-groq`). The value has:

| Sub-key | Type | Description |
|---------|------|-------------|
| `type` | string | Provider type passed to `ProviderConfig.provider`. One of: `openai`, `anthropic`, `openai-compat`, `google`. |
| `base-url` | string | API base URL (e.g., `http://localhost:8090/v1`). Required for `openai-compat`. |
| `api-key` | string? | API key. If omitted, resolved from env var matching the type (e.g., `OPENAI_API_KEY` for `openai`). Supports `${ENV_VAR}` syntax. |
| `models` | string[] | Optional list of model IDs — stored in `ProviderConfig` for documentation; not enforced at runtime. |

### Loading Order (CLI Wins)

```
Hardcoded defaults (GolemConfig data class defaults)
         ↓
Config file  (~/.golem/config.yaml — overlays on defaults)
         ↓
CLI flags    (--model, --provider, --dir, etc. — overlays on config file)
         ↓
Final GolemConfig
```

The rationale: CLI flags are explicit user intent for a single run. Config file is the persistent baseline.

**File existence behavior:**
- Default location `~/.golem/config.yaml` doesn't exist → silently continue with defaults
- Explicit `--config /path/to/custom.yaml` doesn't exist → log warning on stderr, continue with defaults (fail gracefully)
- Explicit `--config /path/to/custom.yaml` exists but fails to parse → log error on stderr, continue with defaults

## Custom Provider Resolution

When `GolemConfig.provider` is set to a name like `"llamaccp"`:

1. `ProviderStore.fromEnvironment()` is called with `customProviders` map from config file
2. `ProviderStore.get("llamaccp")` checks `customProviders` first → finds the YAML entry
3. Returns `ProviderConfig(provider="openai-compat", baseUrl="http://localhost:8090/v1", apiKey="noop")`
4. `ProviderResolver.resolveNamed()` receives this `ProviderConfig` and creates the TramAI `OpenAiProvider`

When the provider name is a built-in like `"openai"`, it's NOT found in `customProviders`, so `ProviderStore` falls through to its existing env-var-based resolution. Complete backward compatibility.

## Security Considerations

1. **Prefer env vars over file secrets** — The example config uses `${VAR}` syntax for all sensitive fields. The config loader will resolve `${ENV_VAR_NAME}` patterns at load time.
2. **File permissions** — If users do put secrets in the file, recommend `chmod 600 ~/.golem/config.yaml`. The implementation should emit a warning on startup if the file is world-readable.
3. **No `.gitignore` needed** — The default location `~/.golem/` is in the user's home directory, outside any git repo.
4. **`${VAR}` resolution** — The config loader will scan all string values for `${...}` patterns and replace with the corresponding env var value before returning. Unknown vars resolve to empty string.

## Implementation Plan

### Phase 1: Config file loading (core)

**Key threading path for custom providers:**

```
GolemCli.call() / buildConfig()
  └── ConfigLoader.loadConfigFile(path) → GolemConfigFile
  └── ConfigLoader.mergeConfig(file, cliConfig) → GolemConfig (with configFilePoviders stored separately)

GolemFactory.create(config, configFileProviders?)
  └── ProviderStore.fromEnvironment(customProviders = configFileProviders)
  └── AgentFactory.create(config, memoryStore, provider=null, ...)
       └── ProviderResolver.resolveFromConfig(config, providerStore)
            └── providerStore.get(config.provider)  ← checks customProviders first
                 └── returns ProviderConfig → resolveNamed() creates TramAI provider
```

**Files to create:**

- `golem-core/src/main/kotlin/dev/golem/config/ConfigLoader.kt` — YAML deserialization of config file, using Jackson YAML + KotlinModule (following `SkillLoader` pattern). Contains:
  - `data class GolemConfigFile` — mirrors the YAML structure, all fields nullable, annotated with `@JsonIgnoreProperties(ignoreUnknown = true)`. YAML key `type` maps to `provider` field in the converted `ProviderConfig` (not stored as `type`).
  - `fun loadConfigFile(path: String): GolemConfigFile?` — reads and parses. Returns `null` silently if file doesn't exist; logs warning if file exists but parse fails.
  - `fun resolveEnvVars(value: String): String` — replaces `${VAR}` patterns with env var values.
  - `fun mergeConfig(file: GolemConfigFile, cli: GolemConfig): GolemConfig` — three-layer merge: defaults → config file → CLI (CLI wins). Config file fields that are `null` don't override defaults. Calls `resolveEnvVars()` on all string fields before merging.
  - `fun providerConfigsFromFile(file: GolemConfigFile): Map<String, ProviderConfig>` — converts the `providers:` YAML section into a map of provider name → `ProviderConfig`. The YAML `type` sub-key becomes `ProviderConfig.provider`.

**Files to modify:**

- `ProviderStore.kt` — add `customProviders: Map<String, ProviderConfig>` parameter to constructor and `fromEnvironment()`. In `resolve()`, check `customProviders` first before falling through to the env-var `when` block.
- `GolemFactory.kt`:
  - `create()` — accept optional `configFileProviders: Map<String, ProviderConfig>? = null`, pass to `ProviderStore.fromEnvironment(customProviders = configFileProviders ?: emptyMap())`
  - `createFromAgentDefinition()` — accept same parameter, pass to `ProviderStore`
- `AgentFactory.kt`:
  - `create()` — no changes needed (it receives a `ModelProvider` from `GolemFactory`, not a `ProviderStore`)
  - `createFromAgentDefinition()` — **must be updated** to accept custom providers. Currently creates its own `ProviderStore.fromEnvironment()` (line 151). Change to accept an optional `ProviderStore` or `Map<String, ProviderConfig>`.
- `Main.kt`:
  - Add `--config` CLI option to `GolemCli`
  - Refactor `GolemCli.call()` and `buildConfig()` to share a single helper that: (a) loads config file, (b) merges with CLI args, (c) returns `GolemConfig`
  - Pass `configFileProviders` alongside `GolemConfig` when calling `GolemFactory.create()`
  - Update `GolemApiServer` constructor call to pass `configFileProviders`
- `GolemApiServer.kt` — accept `configFileProviders` in constructor, pass to `GolemFactory` and `ToolRegistryFactory`
- `ToolRegistryFactory.kt` — accept `configFileProviders` parameter and pass it through to factory methods
- `ArchitectMode.kt` — update the private `resolveProvider()` method to accept an optional `ProviderStore` with custom providers, or pass the configured `ProviderStore` instance

### Phase 2: Config file management (CLI)

**Files to create:**
- `golem-cli/src/main/kotlin/dev/golem/cli/ConfigCommand.kt` — `golem config` subcommand with:
  - `golem config show` — print current effective config (merged)
  - `golem config path` — print config file path
  - `golem config init` — create default config file at `~/.golem/config.yaml`

### Phase 3: Tests

- `ConfigLoaderTest.kt` — unit tests for YAML parsing, merge logic, edge cases (missing file, empty file, partial config, CLI override precedence), `${VAR}` resolution, file permission warning
- `ProviderStoreWithConfigTest.kt` — verify custom providers resolve correctly from config file entries, fallback to env vars for unknown names
- `MainTest.kt` — update existing test for `--config` flag, test `--config /nonexistent` warning
- `ArchitectModeWithConfigTest.kt` — verify custom providers work in architect mode

## Testing Strategy

| Level | What | How |
|-------|------|-----|
| Unit | YAML parsing | Load valid/invalid/missing config files, verify `GolemConfigFile` fields |
| Unit | Merge logic | CLI + config + defaults precedence; partial config files (only model set) |
| Unit | Custom provider resolution | Config with `providers.llamaccp {...}` resolves to correct `ProviderConfig` |
| Unit | Env var resolution | `${VAR}` patterns in config values resolve to actual env vars |
| Integration | CLI + config file | `--config test.yaml --model X` verifies model is X, not the file's value |
| Integration | Full agent with custom provider | Start agent with `--config` pointing to a local llama.cpp, run a simple query |

## Prerequisites

- Java 21, Gradle multi-module project
- Jackson YAML 2.18.3 already in `golem-core/build.gradle.kts`
- `SkillLoader.kt` and `AgentLoader.kt` as reference implementations for YAML loading
- `ProviderStore.kt` and `ProviderResolver.kt` as the provider resolution entry points to extend
- `GolemConfig` data class with all fields as `val` (immutable) — merge via `.copy()`

## Acceptance Criteria

- [ ] `~/.golem/config.yaml` is loaded automatically if it exists (no `--config` needed)
- [ ] `--config /path/to/config.yaml` overrides the default location
- [ ] CLI flags override config file values for the same key
- [ ] `--config /nonexistent.yaml` logs a warning and continues with defaults
- [ ] Custom providers defined in `providers:` section are usable via `--provider llamaccp` or `provider: llamaccp` in config
- [ ] Built-in providers (`openai`, `anthropic`, `ollama`, etc.) continue to work via env vars only, unaffected by config file
- [ ] `${ENV_VAR}` syntax resolved in all string values (api-key, email fields, etc.)
- [ ] Config file with overly permissive permissions (world-readable) emits a startup warning
- [ ] All existing CLI flags and env vars continue to work unchanged
- [ ] No compilation errors, all existing tests pass
- [ ] `golem config show` prints the effective config (merged)
- [ ] `golem config init` creates a default `~/.golem/config.yaml`
- [ ] `golem workflow run` and `golem agent run` respect the config file (via `buildConfig()`)
- [ ] A real integration test: start Golem with `--config test-config.yaml --provider llamaccp` and run a query

## Risk Register

| Risk | Impact | Mitigation |
|------|--------|------------|
| YAML parse error crashes startup | Agent won't start | Wrap in try/catch, log warning, continue with defaults |
| Config file has unknown keys | Silent misconfiguration | Jackson `@JsonIgnoreProperties(ignoreUnknown = true)` on `GolemConfigFile` |
| ProviderStore env var logic duplicated in config loader | Two code paths diverge | Config file provider entries flow through the same `ProviderResolver.resolveNamed()` as env-var entries — single path |
| `${VAR}` not expanded in all string fields | Secrets leak in logs | `resolveEnvVars()` applied to all string values during merge, before storing in `GolemConfig` |
| `buildConfig()` and `call()` diverge | Inconsistent behavior | Refactor to single shared helper function |
