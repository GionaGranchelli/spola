# Golem CLI Redesign — Architecture & Roadmap (Revised)

**Date:** 2026-05-14
**Status:** Design spec — revised after Codex review
**Based on:** `docs/research/cli-deep-analysis.md` + Codex CLI review of v1 plan
**Previous verdict:** FAIL (5 HIGH findings — module cycle, conversation semantics, dead `--resume`, duplicate CheckpointManager, reconfigure scope)

---

## Design Goals

1. **Production-grade REPL** — arrow keys work, tab completion, history, multi-line paste, Ctrl+C
2. **Self-documenting commands** — `/providers`, `/models`, `/status`, consistent help
3. **Safe model/provider switching** — validation at switch time, no crashes, conversation preserved
4. **Pluggable command architecture** — extensible via open interface + registry
5. **Graceful startup/shutdown** — signal handling, cleanup, session persistence

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│  golem-core                                                     │
│                                                                │
│  ProviderStore  ProviderResolver  ModelStore  GolemAgent       │
│  CheckpointManager  GolemInstance  GolemFactory                │
│  runOneShot()  [Runner.kt]                                      │
└────────────────────────────────┬────────────────────────────────┘
                                 │ GolemAgent / GolemInstance APIs
                                 │ (no terminal concerns)
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│  golem-cli                                                      │
│                                                                │
│  ReplEngine  SlashCommandRegistry  GolemTerminal (JLine)        │
│  HelpCommand  ModelCommand  ProviderCommand  ...                │
│  Completers  ReplContext                                        │
│  runRepl() ← MOVED HERE from golem-core                         │
│  Main.kt (picocli entry)  SchedulerCommands  ...                │
└─────────────────────────────────────────────────────────────────┘
```

**Key change from v1:** `runRepl()` moves entirely into `golem-cli`. `golem-core` exposes `runOneShot()` for one-shot mode only. Core provides instance/agent APIs; CLI owns terminal interaction. This eliminates the module cycle.

---

## Key Design Decisions

### D1: REPL Belongs in golem-cli

**Decision:** Move `runRepl()` entirely out of `Runner.kt` in golem-core into a new `ReplEngine` class in golem-cli. Keep `runOneShot()` in core for API consumers.

**Rationale:** 
- `golem-cli` depends on `golem-core` (build.gradle.kts:15) — core cannot import CLI classes
- Terminal handling (JLine) belongs to the CLI module
- Core stays dependency-free of JLine, picocli, and terminal concerns
- API consumers (MCP, REST API, daemon) don't need `runRepl()`

**Migration:**
1. Keep `fun runOneShot(goal, config, onOutput)` in `Runner.kt` — unchanged
2. Remove `fun runRepl(config)` from `Runner.kt` — move to CLI
3. `ReplEngine.run(ctx: ReplContext)` becomes the new REPL loop
4. `Main.kt` calls `ReplEngine.run(config)` instead of `runRepl(config)`

### D2: Explicit REPL Conversation Model

**Decision:** `GolemAgent.run()` currently wipes conversation at the start of every goal (`conversation.clear()` at line 54). For the REPL, we need append-only semantics. Create a `ReplSession` abstraction that lives outside the agent.

**Design:**

The key insight: `GolemAgent.run()` mutates its internal `conversation` throughout the ReAct loop — assistant tool-call messages, tool results, and final assistant text are all appended internally. A REPL session cannot just prepend history and get a final string back. The session must own the transcript.

```kotlin
class ReplSession(
    private val persona: String,
    private val checkpointManager: CheckpointManager?,
) {
    private val conversation = mutableListOf<ChatMessage>()
    private var turnNumber = 0
    
    init {
        conversation.add(SystemMessage(persona))
    }
    
    /** Append a user turn and run the agent. Agent appends to the session's transcript. */
    suspend fun runTurn(
        goal: String,
        agent: GolemAgent,
    ): String {
        turnNumber++
        conversation.add(UserMessage(goal))
        // runFull() mutates 'conversation' in-place, appending all intermediate
        // tool-call, tool-result, and final assistant messages
        val result = agent.runFull(persona, goal, conversation)
        return result
    }
    
    fun clear() { 
        val system = conversation.first()  // keep persona
        conversation.clear()
        conversation.add(system)
        turnNumber = 0
    }
    
    fun snapshot(): List<ChatMessage> = conversation.toList()
    fun lastGoal(): String? = /* last UserMessage content */
    
    suspend fun save(sessionId: String, checkpoint: CheckpointManager) {
        val json = serializeConversation(conversation)
        checkpoint.save(sessionId, turnNumber, json)
    }
    
    suspend fun load(sessionId: String, checkpoint: CheckpointManager) {
        val loaded = checkpoint.loadConversation(sessionId)
        if (loaded != null) {
            conversation.clear()
            conversation.addAll(loaded)
            turnNumber = conversation.size  // approximate
        }
    }
}
```

**Change to GolemAgent:** Add `suspend fun runFull(persona, goal, transcript: MutableList<ChatMessage>)` that:
1. Skips `conversation.clear()` — uses the provided transcript as the conversation
2. Runs the ReAct loop, mutating the passed transcript in-place (appending tool-call, tool-result, assistant messages)
3. Returns the final text (same as current `run()`)

The existing `run(persona, goal)` remains unchanged for one-shot mode.

**Change to GolemAgent:** Add `suspend fun runFull(persona, goal, preloadedConversation)` that skips `conversation.clear()` and uses the provided list instead. The existing `run(persona, goal)` method remains unchanged for one-shot mode.

**Result:** The REPL is multi-turn. `/history` shows the real history. `/clear` actually clears. Sessions save/load correctly. Provider switch preserves the session.

### D3: Reuse CheckpointManager (Don't Create ConversationStore)

**Decision:** Phase 4 reuses the existing `CheckpointManager` for session persistence. No new `ConversationStore` abstraction.

`CheckpointManager` already has (from source):
- `fun save(sessionId: String, messages: List<ChatMessage>)`
- `fun loadConversation(sessionId: String): List<ChatMessage>?`
- `fun list(): List<CheckpointEntry>`
- `fun deleteForSession(sessionId: String)`

**What's missing:** A simple REPL session listing. Most of the infrastructure exists.

### D4: Simple Open Interface for SlashCommand (not sealed)

**Decision:** Use a plain `interface` (not `sealed interface`) for SlashCommand.

```kotlin
interface SlashCommand {
    val name: String
    val aliases: List<String>
    val description: String
    val usage: String
    val hidden: Boolean
    
    suspend fun execute(args: List<String>, ctx: ReplContext): ReplResult
}
```

This allows anyone to implement the interface and register commands. The registry is a `Map<String, SlashCommand>` built at startup. No sealed restrictions.

### D5: Thread sessionId Through Actual Execution

**Decision:** Fix the broken `--resume` / `sessionId` path first (Phase 0):

`GolemInstance.run(persona, goal)` currently ignores `config.sessionId`. Fix:
- `GolemInstance` carries `sessionId` as state
- `GolemAgent.run()` passes `sessionId` to `CheckpointManager`
- `--resume <id>` on CLI actually loads and continues a session

### D6: reconfigure() Updates All Model-Dependent State

**Decision:** Model is captured at tool registry build time in `ProvenanceTools.kt:12`. Changing only `GolemAgent.effectiveModel` leaves stale data in provenance tools.

**Fix:** Have `GolemInstance.reconfigure()` call `ToolRegistryFactory.rebuildModelDependentTools(model)` which unregisters and re-registers `provenance_*` tools with the new model string. Provenance tools capture model at registration time (`ProvenanceTools.kt:12`); the simplest fix is to rebuild them, not to introduce a supplier-based API.

### D7: /models Is Advisory, Not a Gate

**Decision:** `/models` lists known models for the current provider. It does NOT reject unknown model names. For `ollama` and `openai-compat`, arbitrary models are legitimate. Validation is a best-effort live probe, not a hard gate.

### D8: Fix Lifecycle Ownership

**Decision:** `GolemInstance.close()` must own and close everything it creates:
- `CheckpointManager` / `CheckpointStore`
- `SqliteGolemJobStore` (scheduler store)
- `JvmIndexCoordinator` (file watcher)
- `PluginLoader.shutdownPlugins()`

Currently only plugins and memory store are closed. Add the rest.

---

## File Map (Revised)

### New Files (in golem-cli)

| File | Path | Purpose |
|------|------|---------|
| `ReplEngine.kt` | `golem-cli/.../cli/` | Core REPL loop — replaces `runRepl()` |
| `GolemTerminal.kt` | `golem-cli/.../cli/terminal/` | JLine wrapper |
| `SlashCommand.kt` | `golem-cli/.../cli/command/` | Open interface + registry |
| `ReplContext.kt` | `golem-cli/.../cli/` | Shared mutable REPL state |
| `ReplSession.kt` | `golem-cli/.../cli/` | Multi-turn conversation manager |
| `HelpCommand.kt` | `golem-cli/.../cli/command/` | `/help` — auto-generated from registry |
| `ModelCommand.kt` | `golem-cli/.../cli/command/` | `/model` — switch + advisory validation |
| `ProviderCommand.kt` | `golem-cli/.../cli/command/` | `/provider` — switch + validate env |
| `ProvidersCommand.kt` | `golem-cli/.../cli/command/` | `/providers` — list 5 known types |
| `ModelsCommand.kt` | `golem-cli/.../cli/command/` | `/models` — advisory list per provider |
| `ClearCommand.kt` | `golem-cli/.../cli/command/` | `/clear` — clears ReplSession |
| `SessionCommand.kt` | `golem-cli/.../cli/command/` | `/session` — save/load/list/delete |
| `StatusCommand.kt` | `golem-cli/.../cli/command/` | `/status` — current provider, model, session |
| `RetryCommand.kt` | `golem-cli/.../cli/command/` | `/retry` — rerun last turn after failure |
| `SlashCommandCompleter.kt` | `golem-cli/.../cli/terminal/` | JLine completer for `/commands` |
| `ToolCompleter.kt` | `golem-cli/.../cli/terminal/` | JLine completer for tool names |
| `ProviderCompleter.kt` | `golem-cli/.../cli/terminal/` | JLine completer for provider names |
| `DoctorCommand.kt` | `golem-cli/.../cli/picocli/` | `golem doctor` picocli subcommand |

### New Files (in golem-core)

| File | Path | Purpose |
|------|------|---------|
| `ModelStore.kt` | `golem-core/.../agent/` | Advisory model listing per provider |

### Modified Files

| File | Change |
|------|--------|
| `Runner.kt` | Remove `runRepl()`; keep `runOneShot()` only |
| `GolemAgent.kt` | Add `runFull(persona, goal, conversation)` — skip clear, use provided list |
| `GolemAgent.kt` | Change `val provider` → `var provider`; add `reconfigure()` |
| `GolemAgent.kt` | Change `val effectiveModel` → `var effectiveModel` |
| `GolemAgent.kt` | Add `clearConversation()` |
| `GolemInstance.kt` | Add `reconfigure(providerName, modelName)` |
| `GolemInstance.kt` | Carry and expose `sessionId` |
| `GolemInstance.kt` | Fix `close()` — close all owned resources |
| `GolemInstance.kt` | Add `run(persona, goal, observer, sessionId)` overload |
| `GolemFactory.kt` | Add `createProvider(providerName, modelName)` — no agent |
| `ProviderStore.kt` | Add `listProviders(): List<ProviderInfo>` |
| `ProviderResolver.kt` | Add `validateProvider(providerName)` |
| `ProviderResolver.kt` | Add `validateModel(providerName, modelName)` — best-effort |
| `ToolRegistryFactory.kt` | Expose method to rebuild model-dependent tools |
| `ProvenanceTools.kt` | Look up model dynamically, don't capture at build |
| `CheckpointManager.kt` | Add `listSessions(): List<String>` if missing |
| `McpClientManager.kt` | Add `removeServer(name)` — fix duplicate bug |
| `Main.kt` | Call `ReplEngine.run(config)` instead of `runRepl(config)` |
| `Main.kt` | Wire `--resume` into ReplEngine for session load |
| `build.gradle.kts` (golem-cli) | Add JLine dependency |

---

## Missing Slash Commands (beyond the analysis)

| Command | Description | Priority |
|---------|-------------|----------|
| `/status` | Current provider, model, session id, workdir, checkpoint mode | HIGH |
| `/config` | Active resolved config (effective values) | MEDIUM |
| `/retry` | Rerun the last user goal after a transient failure | MEDIUM |
| `/doctor` | Provider/env/connectivity diagnostics | LOW |
| `/session list` | List saved sessions from CheckpointManager | HIGH |
| `/session save <name>` | Save current REPL session | HIGH |
| `/session load <name>` | Load a saved session | HIGH |
| `/session delete <name>` | Delete a saved session | MEDIUM |
| `/session new` | Start fresh, save current automatically | MEDIUM |
| `/checkpoints` | View saved checkpoint IDs/turns | LOW |

---

## Risk Register (Updated)

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| JLine version conflict | Low | Medium | Minimal transitive deps; test in isolation |
| JLine fails in headless CI | Low | Low | Check `System.console()` before JLine init; fallback to BufferedReader |
| ReplSession conversation diverges from agent's internal state | Medium | High | Agent internal conversation is deprecated in REPL mode; `runFull()` uses external conversation only |
| CheckpointManager.save/load has incompatible format | Low | Medium | Read existing format; write adapter if needed |
| Provider switch with live CheckpointManager state | Low | Medium | CheckpointManager is stateless (SQLite-backed); safe to keep open |
| Provenance model dependency creates staleness | Low | Medium | Dynamic lookup from GolemInstance.config |

---

## Phased Roadmap (Revised)

### Phase 0 — Emergency Fixes + Foundation (2 days)

No terminal changes. Fix bugs, add wire, lay foundation.

| Task | Area | Description |
|------|------|-------------|
| TASK-001 | Runner.kt | Wrap `/model` and `/provider` `GolemFactory.create()` in try/catch |
| TASK-002 | GolemAgent.kt, Runner.kt | Fix `/clear`: add `clearConversation()`, call it |
| TASK-003 | Runner.kt | Add `/session` stub: print "Not implemented yet" |
| TASK-004 | Runner.kt, ProviderStore.kt | Add `/providers` command: print 5 known providers |
| TASK-005 | Runner.kt, ProviderStore.kt | Add `/models` command: print advisory model list |
| TASK-006 | McpClientManager.kt | Fix MCP remove: add `removeServer(name)` |
| TASK-007 | Runner.kt | Validate `/provider <name>` against known list |
| **TASK-008** | **GolemInstance.kt** | **Wire `config.sessionId` into `GolemInstance.run()`** — fix dead `--resume` |
| **TASK-009** | **GolemInstance.kt** | **Fix lifecycle: `close()` must close watcher, checkpoint store, scheduler store** |
| **TASK-010** | **GolemAgent.kt** | **Add `runFull(persona, goal, preloadedConversation)` for REPL mode** |
| | Tests | Unit tests for all 10 fixes |

### Phase 1 — Command Registry (1-2 days)

Refactor slash commands out of Runner.kt into open interface + registry. Can land before or parallel with JLine.

| Task | File | Description |
|------|------|-------------|
| TASK-011 | SlashCommand.kt | Open interface: `name`, `aliases`, `description`, `usage`, `execute(args, ctx)` |
| TASK-012 | SlashCommandRegistry.kt | Registry: `Map<String, SlashCommand>`, `register()`, `find()`, `listAll()` |
| TASK-013 | ReplContext.kt | Data class: terminal, instance, config, session, registry, observer |
| TASK-014 | ReplSession.kt | Multi-turn conversation manager using `runFull()`; owns transcript |
| TASK-015 | HelpCommand.kt | `/help` auto-generated from registry |
| TASK-016 | ModelCommand.kt | `/model <name>` — advisory validation only |
| TASK-017 | ProviderCommand.kt | `/provider <name>` — validates against known set |
| TASK-018 | ProvidersCommand.kt | `/providers` |
| TASK-019 | ModelsCommand.kt | `/models` — advisory list |
| TASK-020 | ClearCommand.kt | `/clear` — delegates to ReplSession.clear() |
| TASK-021 | StatusCommand.kt | `/status` — current provider, model, session id |
| TASK-022 | RetryCommand.kt | `/retry` — rerun last goal from ReplSession |
| TASK-023 | ToolsCommand.kt | `/tools` — list tool registry (migrated from Runner.kt) |
| TASK-024 | MemoryCommand.kt | `/memory` — list memory entries (migrated from Runner.kt) |
| TASK-025 | PersonaCommand.kt | `/persona` — print persona (migrated from Runner.kt) |
| TASK-026 | HistoryCommand.kt | `/history` — show conversation (migrated from Runner.kt) |
| TASK-027 | ExitCommand.kt | `/exit`, `/quit` — graceful exit (migrated from Runner.kt) |
| TASK-028 | ReplEngine.kt | New REPL loop: init context, register all commands, read → parse → dispatch |
| TASK-029 | Runner.kt | Remove `runRepl()` body; keep `runOneShot()` unchanged |
| TASK-030 | Main.kt | Call `ReplEngine.run(config, sessionId)` instead of `runRepl(config)` |
| | Tests | SlashCommandRegistryTest, ReplSessionTest |

### Phase 2 — Terminal Layer (JLine) (3-4 days)

After command registry is stable, add JLine for input handling.

| Task | File | Description |
|------|------|-------------|
| TASK-026 | build.gradle.kts | Add JLine dependency |
| TASK-027 | GolemTerminal.kt | JLine LineReader + history file (`~/.golem/history`) + bracket paste |
| TASK-028 | SlashCommandCompleter.kt | Completer for `/` commands |
| TASK-029 | ToolCompleter.kt | Completer for tool names |
| TASK-030 | ProviderCompleter.kt | Completer for provider names |
| TASK-031 | ReplEngine.kt | Wire JLine into read loop; fallback to BufferedReader in headless |
| TASK-032 | ReplEngine.kt | Ctrl+C handler: cancel current agent run, return to prompt |
| TASK-033 | ReplEngine.kt | EOF (Ctrl+D) handler: graceful exit |
| TASK-034 | ReplEngine.kt | Multi-line paste detection via bracket paste |
| | Tests | Headless fallback test |

### Phase 3 — Provider/Model Overhaul (3-4 days)

Mutable provider, session-safe switching, validation.

| Task | File | Description |
|------|------|-------------|
| TASK-035 | GolemAgent.kt | `val provider` → `var provider` |
| TASK-036 | GolemAgent.kt | `val effectiveModel` → `var effectiveModel` |
| TASK-037 | GolemAgent.kt | `reconfigure(newProvider, newModel)` |
| TASK-038 | GolemInstance.kt | `reconfigure(providerName, modelName)` — swaps provider, preserves session |
| TASK-039 | GolemInstance.kt | `run(persona, goal, observer, sessionId)` — threaded sessionId |
| TASK-040 | ProviderStore.kt | `listProviders(): List<ProviderInfo>` |
| TASK-041 | ProviderResolver.kt | `validateProvider(providerName, providerStore)` |
| TASK-042 | ProviderResolver.kt | `validateModel(providerName, modelName)` — best-effort |
| TASK-043 | ModelStore.kt | Advisory model list per provider |
| TASK-044 | ToolRegistryFactory.kt | `rebuildModelDependentTools(model)` — fix provenance staleness |
| TASK-045 | ProvenanceTools.kt | Dynamic model lookup from GolemInstance |
| TASK-046 | GolemInstance.kt | `close()` closes all owned resources |
| TASK-047 | ModelCommand.kt | Wire advisory validation |
| TASK-048 | ProviderCommand.kt | Wire strict validation + env var check |
| | Tests | GolemAgentTest, ProviderResolverTest |

### Phase 4 — Session Persistence (1-2 days)

Reuse CheckpointManager, not a new ConversationStore.

| Task | File | Description |
|------|------|-------------|
| TASK-049 | SessionCommand.kt | `/session list` — calls checkpoint.list() |
| TASK-050 | SessionCommand.kt | `/session save <name>` — ReplSession.save(checkpoint) |
| TASK-051 | SessionCommand.kt | `/session load <name>` — ReplSession.load(checkpoint) |
| TASK-052 | SessionCommand.kt | `/session delete <name>` — checkpoint.deleteForSession() |
| TASK-053 | SessionCommand.kt | `/session new` — save current, clear session |
| TASK-054 | ReplEngine.kt | On startup with `--resume <id>`, auto-load session |
| TASK-055 | CheckpointManager.kt | Add `listSessions()` if missing |
| | Tests | SessionCommandTest |

### Phase 5 — Polish (1-2 days)

| Task | File | Description |
|------|------|-------------|
| TASK-056 | ReplEngine.kt | Shutdown hook: `Runtime.addShutdownHook` closes GolemInstance |
| TASK-057 | GolemTerminal.kt | Flush history file on shutdown |
| TASK-058 | Main.kt, DoctorCommand.kt | `golem doctor` picocli subcommand |
| TASK-059 | Main.kt | Global `--verbose` flag |
| TASK-060 | ReplEngine.kt | `/doctor` slash command (calls same diagnostic logic) |
| TASK-061 | ReplEngine.kt | `/checkpoints` slash command |
| TASK-062 | ReplEngine.kt | `/config` slash command |
| | Tests | DoctorCommandTest |

---

## Dependency Graph (Revised)

```
Phase 0 ───── no deps, fixes + foundation
   │
   ├──► Phase 1 ─── command registry (no JLine needed)
   │       │
   │       ├──► Phase 2 ─── JLine terminal layer
   │       │
   │       └──► Phase 3 ─── provider/model overhaul ──► Phase 4 ─── session persistence
   │                                                         ↑
   │                                                  (serial after Phase 3 for 1-person team)
   │
   └──► Phase 5 ─── polish (shutdown, doctor, config)
```

**Note for 1-person team:** Phases 3 and 4 are serial, not parallel. Provider/model overhaul modifies GolemAgent/GolemInstance internals that ReplSession depends on for session preservation across switches. Do Phase 4 only after Phase 3 is stable.

---

## Acceptance Criteria

- [ ] Arrow keys navigate history (JLine), do not produce escape codes
- [ ] Tab completion works for slash commands, provider names, tool names
- [ ] Multi-line paste preserves all lines as one goal
- [ ] `/provider <invalid>` shows error, does not crash REPL
- [ ] `/model <invalid>` shows advisory warning, does not crash
- [ ] `/model ollama/custom-model` works (not rejected by hardcoded list)
- [ ] `/clear` actually clears conversation
- [ ] `/providers` shows 5 provider names with env var requirements
- [ ] `/models` shows advisory model list for current provider
- [ ] `/status` shows provider, model, session id, workdir
- [ ] `/session save` + `/session load` preserves full conversation
- [ ] `--resume <id>` loads previous session on startup
- [ ] Switching model preserves conversation history
- [ ] Switching provider validates env vars first
- [ ] Ctrl+C exits gracefully without corrupting SQLite DBs
- [ ] History persists across REPL sessions in `~/.golem/history`
- [ ] MCP remove does not duplicate remaining servers
- [ ] `golem doctor` tests API keys and provider connectivity
- [ ] All existing tests pass after each phase
- [ ] Distribution runs without Gradle (`./golem-cli/build/install/golem-cli/bin/golem-cli`)

---

## Estimation Summary

| Phase | Tasks | Effort |
|-------|-------|--------|
| Phase 0 — Emergency fixes + foundation | 10 | 2 days |
| Phase 1 — Command registry | 20 | 2-3 days |
| Phase 2 — Terminal layer (JLine) | 9 | 3-4 days |
| Phase 3 — Provider/model overhaul | 14 | 3-4 days |
| Phase 4 — Session persistence | 7 | 1-2 days |
| Phase 5 — Polish | 7 | 1-2 days |
| **Total** | **67 tasks** | **12-17 days** |
