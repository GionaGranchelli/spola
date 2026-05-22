# Spola CLI — Deep Technical Analysis

**Date:** 2026-05-14
**Analysts:** OpenAI Codex CLI, GitHub Copilot CLI
**Scope:** spola-backend-cli module + core REPL + provider system (15 source files)

## TL;DR

The CLI has **7 critical bugs**, **6 critical design gaps**, and **missing terminal support**. It needs a dedicated rewrite or a substantial refactor to be production-ready. The current implementation uses `BufferedReader.readLine()` for input — no JLine, no raw mode, no key handling. Slash commands are a naive `when` block. Switching model/provider destroys and recreates the entire agent instance.

---

## 1. Terminal Handling — CRITICAL

| Aspect | Current State | Problem |
|--------|--------------|---------|
| **Input library** | None. `System.in.bufferedReader().readLine()` | No raw mode, no escape sequence parsing |
| **Arrow keys** | Produce literal `^[[A`, `^[[B`, `^[[C`, `^[[D` | User sees escape codes, not history navigation |
| **Tab completion** | Not implemented | No command/arg completion |
| **History** | None | Up arrow produces escape codes |
| **Line editing** | Only what the cooked terminal driver gives | No Ctrl+Left/Right, no Home/End |
| **Ctrl+C** | Kills JVM ungracefully | No shutdown hook, no cleanup |
| **Multi-line paste** | `readLine()` reads one line | 49/50 lines become separate goals |
| **ANSI output** | Hardcoded escape constants | No terminfo check, no Windows fallback |

**Root cause:** No terminal library was added. The REPL was built using only Java stdlib, which is insufficient for interactive terminal applications.

**Fix:** Add JLine or Lanterna dependency. JLine provides: history (Up/Down), tab completion, line editing, multi-line paste detection (bracket paste `\e[200~`), raw mode, and Ctrl+C handling — all from one dependency.

---

## 2. REPL Slash Commands — COMPREHENSIVE AUDIT

### Command Table

| Command | Status | Issue |
|---------|--------|-------|
| `/exit`, `/quit` | OK | Breaks loop, cleanup via finally |
| `/help` | OK | Hardcoded string, needs updating |
| `/tools` | OK | Lists tool registry |
| `/memory` | OK | Lists memory entries |
| `/persona` | OK | Prints persona text |
| `/history` | OK | Prints conversation (truncated to 200 chars) |
| **`/clear`** | **BROKEN** | Only prints message, does NOT clear conversation |
| **`/model <name>`** | **DANGEROUS** | No validation, can switch to nonexistent models |
| **`/provider <name>`** | **CRITICAL** | Can crash the entire REPL |
| **`/session <id>`** | **LISTED BUT ABSENT** | No `when` branch — falls through to AI agent |
| **`/providers`** | **MISSING** | Falls through to AI agent as goal |
| **`/models`** | **MISSING** | Falls through to AI agent as goal |

### `/clear` Bug

```kotlin
trimmed == "/clear" -> {
    println("${ANSI_GREEN}Conversation cleared.${ANSI_RESET}")
    // NEVER calls anything to clear the agent's conversation
}
```

The agent's in-memory conversation (`SpolaAgent` has a `mutableListOf<ChatMessage>` that accumulates messages) is never reset. The message is a lie.

**Fix:** Add `fun clearConversation()` to `SpolaAgent.kt` and call it here.

### `/provider <name>` Can Crash the REPL

```kotlin
trimmed.startsWith("/provider ") -> {
    val providerName = trimmed.removePrefix("/provider ").trim()
    // ... config copy ...
    instance.close()  // Closes OLD instance
    instance = SpolaFactory.create(config = currentConfig)  // Can throw!
    // If create() throws, 'instance' now holds a CLOSED reference
    // The 'finally' block tries to close it again — double-close
}
```

`SpolaFactory.create()` calls `ProviderStore.get(providerName)`, which calls `ProviderStore.resolve()`. For unknown providers, this throws:
```
IllegalStateException("Unsupported or unconfigured provider: $providerName")
```

This exception is **NOT caught** by any try/catch in the slash handler. The `when` block is inside a `try {} finally {}` (line 69-157), but there's no catch clause. The exception propagates up through coroutine scopes and can leave the REPL in a broken state with a closed `instance` before the `finally` block.

**Fix:** Wrap `SpolaFactory.create()` in a try/catch. On failure, restore the old config and `currentConfig` and print an error. Do NOT close the old instance until the new one is ready.

### `/model <name>` — No Validation

Accepts literally any string: `"asdfghjkl"`, `"gpt-4-fake-fake"`, `""` (blank is rejected), `"   "`. The invalid model name causes `provider.complete(request)` to fail on the first LLM call — silent until the user types a goal.

### `/session <id>` — Listed in Help but Not Implemented

The help text (Runner.kt line 169) shows:
```
/session <id>     Load a session
```

But there is no `when` branch for this command. Typing `/session abc123` falls through to the `else` branch and gets sent to the AI agent as a goal. The agent responds with something like "I can help you with that session."

### `/providers` and `/models` — Don't Exist

These fall through to the agent. The user sees the AI agent responding with its capabilities instead of a structured list of available providers/models.

---

## 3. Provider/Model System

### Supported Providers

| Provider Name | Backend Class | Required Env Var | Default Model | Default Base URL |
|---|---|---|---|---|
| `openai` | OpenAiProvider | `OPENAI_API_KEY` | `gpt-4o` | OpenAI default |
| `anthropic` | AnthropicProvider | `ANTHROPIC_API_KEY` | `claude-sonnet-4-20250514` | Anthropic default |
| `openai-compat` | OpenAiProvider | `OPENAI_COMPAT_API_KEY` → `OPENAI_API_KEY` | `gpt-4o` | `http://localhost:8090/v1` |
| `ollama` | OpenAiProvider | None (noop key `"ollama"`) | `llama3` | `http://localhost:11434/v1` |
| `google` | OpenAiProvider | `GOOGLE_API_KEY` | `gemini-2.5-pro` | `https://generativelanguage.googleapis.com/v1beta/openai` |

**Key insight:** Anthropic is the only provider using `AnthropicProvider`. All others use `OpenAiProvider` with different base URLs (openai-compat, ollama, google are all OpenAI-compatible REST APIs).

### Resolution Flow

```
SpolaFactory.create(config)
  → AgentFactory.create(config)
    → ProviderResolver.resolveFromConfig(config)
      → ProviderStore.fromEnvironment().get(config.provider)
        → resolves env vars → returns ProviderConfig
      → ProviderResolver.resolveNamed(providerConfig, modelName)
        → constructs TramAI provider (OpenAiProvider / AnthropicProvider)
        → returns Pair(ModelProvider, modelName)
    → SpolaAgent(provider, effectiveModel, ...)
```

### What Would It Take to List Providers?

**Trivial.** The 5 provider names are hardcoded in `ProviderStore.kt:18-66`. A `/providers` command can:
```kotlin
fun listProviders(): List<String> = listOf("openai", "anthropic", "openai-compat", "ollama", "google")
```

### What Would It Take to List Models?

**Impossible without API calls.** Spola/TramAI has no model discovery mechanism. Models are arbitrary strings passed to the provider API. Options:
- **Static mapping** — create a hardcoded map of provider → known models (fragile, goes stale)
- **API query** — call each provider's list models endpoint (vendor-specific: OpenAI has `/v1/models`, Anthropic doesn't expose one)
- **Live test** — try a lightweight call like `{"model":"<name>","max_tokens":1}` and check if it 200s

Recommendation: Start with a static mapping for common providers, add live provider API query later.

---

## 4. Validation Gaps — COMPLETE INVENTORY

| Location | Input | What's Accepted | Risk |
|----------|-------|-----------------|------|
| `Main.kt:49` | `--model` | Any string | Silent failure at first LLM call |
| `Main.kt:57` | `--provider` | Any string | Crash if provider name unknown |
| `Main.kt:65` | `--workdir` | Any string | Path may not exist |
| `Main.kt:79` | `--max-turns` | Any int | Negative ints accepted |
| `Runner.kt:98-107` | `/model <name>` | Any non-blank string | Silent at switch time, fails at first goal |
| `Runner.kt:110-120` | `/provider <name>` | Any non-blank string | **Can crash the REPL** |
| `Runner.kt:122-152` | Goal text | Everything not matching a slash command | No length limit, no dangerous content filter |
| `AgentCommands.kt` | `--fs`, `--shell`, `--network`, `--exec`, `--memory` | Any string | No enum validation, silent `.toBoolean()` fallback |
| `McpCommand.kt:54-71` | Port | Int | No port range validation |
| `WorkflowCommands.kt:95-96` | `--agents` | Comma-separated string | No validation against AgentStore |

---

## 5. Error Handling — Critical Gap

### Error Propagation

```
User: /provider bogus
  → SpolaFactory.create() → ProviderResolver.resolveFromConfig()
    → ProviderStore.get("bogus") → ProviderStore.resolve("bogus")
      → throw IllegalStateException("Unsupported provider: bogus")
  → UNCAUGHT in slash handler
  → Propagates out of the while(true) loop
  → finally block tries instance.close() on already-closed instance
```

| Scenario | Caught Where | User Experience |
|----------|-------------|-----------------|
| Invalid `/provider` | **NOT CAUGHT** | REPL crash / stuck state |
| Invalid `/model` | Caught at LLM call time | "Error: ..." message, REPL continues |
| Wrong API key | Caught at LLM call time | "Error: 401 ..." or similar |
| Provider network down | Caught at LLM call time | "Error: Connection refused" |
| Tool execution failure | Caught in SpolaAgent | Retried once, then error sent to LLM |
| Top-level exception | Main.kt line 205-209 | stderr + exit code 1 |

### Key Issue: No "Test Connection" Step

Provider credentials and model names are never validated at creation time. The `ModelProvider` object is created successfully even with invalid API keys or nonexistent models. The first `provider.complete(request)` call will fail, but only after the user has typed a goal and waited for the spinner.

---

## 6. Architecture Issues

### Model/Provider Switch Destroys Everything

The `/model` and `/provider` handlers:
1. Close the entire `SpolaInstance` (plugins, memory store, index watchers, observers)
2. Create a brand new one from scratch

This destroys:
- **Conversation history** — `SpolaAgent`'s in-memory `mutableListOf<ChatMessage>` is lost
- **Tool registries** — rebuilt from scratch
- **Plugin state** — plugins are unloaded and reloaded
- **Memory store** — closed and reopened
- **JVM index watcher** — restarted
- **Observer chains** — rebuilt

**Fix:** Add `reconfigure(provider: String, model: String)` to `SpolaInstance` that swaps the LLM provider/model without touching the rest. SpolaAgent should hold a mutable `ModelProvider` reference.

### No Session Persistence Between Mode Switches

- There's no way to save a conversation and resume later
- `/session <id>` is listed but not implemented
- CheckpointStore exists (`CheckpointStore`, `CheckpointManager`) but is not exposed to the REPL

---

## 7. Picocli Subcommand Surface Audit

### Registered Subcommands

| Command | File | Quality | Issues |
|---------|------|---------|--------|
| `spola` (root) | Main.kt | OK | Well-structured picocli setup |
| `scheduler` | SchedulerCommands.kt | Good | Proper CRUD, stores working |
| `pairing` | PairingCommands.kt | Missing source | Command registered but no src found |
| `agent` | AgentCommands.kt | Good | CRUD + run, 6 subcommands |
| `workflow` | WorkflowCommands.kt | Moderate | 4 hardcoded workflows, no extensibility |
| `team` | WorkflowCommands.kt | Moderate | No agent ID validation |
| `mcp` | McpCommand.kt | Buggy | Remove command creates duplicates |
| `skill` | SkillCommand.kt | Good | Validates file existence |
| `project` | ProjectCommands.kt | Missing source | Command registered but no src found |

### McpRemoveCommand Duplicate Bug

When removing an MCP server, the code does:
```kotlin
configs.filterNot { it.name == mcpName }.forEach { manager.addServer(it) }
```

`addServer()` APPENDS to the config file. So after the first removal, all remaining servers are appended again, creating duplicates. The next removal doubles them again.

**Fix:** McpClientManager needs a `removeServer(name)` method that rewrites the config file, not one that re-adds everything.

### Missing Global Flags

- No `--verbose` or `--debug` flag
- No `--quiet` flag
- `--help` is provided by picocli's `mixinStandardHelpOptions = true` on root command but individual subcommands may not have it consistently

### Missing Subcommands

- `spola config` — view current config
- `spola doctor` — diagnostic: test API keys, check provider connectivity
- `spola models` — list available models (would need provider API)
- `spola providers` — list 5 known provider types

---

## 8. Multi-line Input

**Broken.** `BufferedReader.readLine()` reads until `\n`. Long pastes produce N separate goals.

**Fix options:**
1. **JLine's bracket paste mode** — detects `\e[200~` / `\e[201~` escape sequences that modern terminals wrap pasted text with. Reads the entire paste as one input.
2. **Delimiter mode** — enter `.` alone on a line to end multi-line input.
3. **Multi-line detection** — if input ends with `\` or starts with triple backticks, read more lines.

---

## 9. Exit/Cleanup

| Aspect | Status | Issue |
|--------|--------|-------|
| Shutdown hook | **MISSING** | No `Runtime.addShutdownHook` anywhere |
| SIGTERM | **NOT HANDLED** | JVM killed, SQLite WAL may lose data |
| Ctrl+C | **NOT HANDLED** | Same as SIGTERM |
| Instance cleanup | OK via `finally` | But only on normal exit |
| Scheduler daemon | Partial | Has `finally` with awaitCancellation but no signal handler |
| SQLite WAL flush | **MISSING** | No close/flush on unexpected exit |

---

## 10. Prioritized Action Plan

### Tier 1 — Critical Bugs (fix immediately)

| # | Item | Effort | File(s) | Fix |
|---|------|--------|---------|-----|
| 1 | `/provider <bad>` crash | 30min | Runner.kt | Wrap `SpolaFactory.create()` in try/catch in slash handler |
| 2 | `/clear` is a no-op | 30min | Runner.kt, SpolaAgent.kt | Add `clearConversation()` method, call it |
| 3 | `/session` falls through to AI | 15min | Runner.kt | Add `when` branch with usage/error message |
| 4 | MCP remove creates duplicates | 1h | McpCommand.kt, McpClientManager.kt | Add `removeServer()` to client manager |

### Tier 2 — Validation (fix before next release)

| # | Item | Effort | File(s) | Fix |
|---|------|--------|---------|-----|
| 5 | Validate `/provider` against known list | 30min | Runner.kt, ProviderStore.kt | Check before config copy |
| 6 | Add `/providers` command | 30min | Runner.kt, ProviderStore.kt | Expose hardcoded list |
| 7 | Add `/models` command (static) | 1h | Runner.kt | Hardcoded model map per provider |
| 8 | Validate `--provider` at picocli parse time | 30min | Main.kt | Add `completionCandidates` or custom converter |
| 9 | Fix AgentCommands enum validation | 1h | AgentCommands.kt | Add regex/enum validation on --fs, --shell, etc. |

### Tier 3 — Terminal Library (high impact, moderate effort)

| # | Item | Effort | Description |
|---|------|--------|-------------|
| 10 | Add JLine dependency | 30min | Add to build.gradle.kts |
| 11 | Replace `readLine()` with JLine LineReader | 2h | History, tab completion, line editing |
| 12 | Implement slash command completion | 2h | Complete `/help`, `/model`, `/provider`, etc. |
| 13 | Implement model/provider tab completion | 1h | Dynamic candidates from known lists |
| 14 | Add multi-line paste detection | 30min | JLine bracket paste mode |
| 15 | Add Ctrl+C graceful exit | 1h | Shutdown hook + signal handler |

### Tier 4 — Architecture (significant refactors)

| # | Item | Effort | Description |
|---|------|--------|-------------|
| 16 | Make SpolaAgent provider/model mutable | 4h | Add `reconfigure()` method |
| 17 | Preserve conversation on model/provider switch | 2h | Save/restore conversation list |
| 18 | Add graceful shutdown hook | 1h | Register JVM shutdown hook |
| 19 | Add `spola doctor` command | 3h | Test API keys, provider connectivity |

### Tier 5 — Polish

| # | Item | Effort | Description |
|---|------|--------|-------------|
| 20 | Add `--verbose`/`--debug` global flag | 1h | Increase log output |
| 21 | Add `spola config` command | 2h | View current effective config |
| 22 | Implement `/session <id>` properly | 4h | Load from CheckpointStore |
| 23 | Add `spola providers` subcommand | 1h | CLI-level listing |
| 24 | Add `spola models` subcommand | 2h | CLI-level listing (static) |

---

## Summary

| Category | Count | Severity |
|----------|-------|----------|
| Critical bugs | 4 | REPL crash, no-op commands, data corruption |
| Missing commands | 3 | `/providers`, `/models`, `/session` |
| Validation gaps | 12+ | Every user input point is unvalidated |
| Terminal gaps | 6 | No JLine, no history, no tab, no multi-line, no Ctrl+C |
| Architecture issues | 2 | Full instance recreation, conversation loss |
| Subcommand bugs | 2 | MCP duplicates, missing sources |
| Total actionable items | 24 | From 30min fixes to 4h refactors |

**Recommended first steps:**
1. Fix the 4 critical bugs (Tier 1) — 2-3 hours
2. Add validation and missing commands (Tier 2) — 3-4 hours
3. Add JLine (Tier 3) — 6-8 hours for full implementation
4. Architecture refactors (Tier 4) — 7-10 hours
