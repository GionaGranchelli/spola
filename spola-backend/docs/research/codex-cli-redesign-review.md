# Codex Review: Spola CLI Redesign Plan (v1)

**Reviewer:** OpenAI Codex CLI (gpt-5.4)
**Plan reviewed:** `.hermes/plans/2026-05-14_spola-backend-cli-redesign.md` (v1)
**Date:** 2026-05-14

## Verdict

**FAIL** — direction is right, but 5 HIGH findings must be resolved before implementation.

## Critical Findings

### HIGH — Module cycle
**v1 plan:** Put `ReplEngine`, `SlashCommand`, terminal classes in spola-backend-cli, but keep `runRepl()` in core with delegation to CLI classes.
**Problem:** `spola-backend-cli` depends on `spola-backend-core` (build.gradle.kts:15), not the reverse. Core cannot import CLI classes.
**Fix (applied):** Move `runRepl()` entirely into spola-backend-cli. Core keeps only `runOneShot()`.

### HIGH — Conversation wipes on every run
**v1 plan:** Assumed REPL is multi-turn, but `SpolaAgent.run()` at SpolaAgent.kt:54 calls `conversation.clear()` at the start of every goal.
**Problem:** `/history`, `/clear`, `/session`, and "preserve conversation across provider switches" are all underspecified. Every goal starts fresh.
**Fix (applied):** `ReplSession` abstraction lives outside the agent in spola-backend-cli. `SpolaAgent.runFull(persona, goal, preloadedConversation)` skips `clear()` and uses the provided list. Agent's internal conversation is deprecated in REPL mode.

### HIGH — `--resume` is parsed but inert
**v1 plan:** Phase 4 introduces new session machinery.
**Problem:** `Main.kt:153` parses `--resume`, `SpolaConfig.kt:65` stores `sessionId`, but `SpolaInstance.kt:26` calls `agent.run(persona, goal, observer)` without passing any session id. The feature exists in config but is never threaded into execution.
**Fix (applied):** Phase 0 includes wiring `config.sessionId` into actual execution (TASK-008). Phase 4 reuses existing CheckpointManager.

### HIGH — Phase 4 duplicates CheckpointManager
**v1 plan:** Introduces `ConversationStore` + new `saveConversation/loadConversation` methods.
**Problem:** `CheckpointManager.kt:49` already has `save`, `loadConversation`, `list`, `deleteForSession`, `listForSession`. The plan was creating redundant scope.
**Fix (applied):** Phase 4 reuses CheckpointManager. No new ConversationStore.

### HIGH — `reconfigure()` scope is too narrow
**v1 plan:** Only changes `SpolaAgent.provider` and `SpolaAgent.effectiveModel`.
**Problem:** Provenance tools capture the model string at registry build time (ToolRegistryFactory.kt:69, ProvenanceTools.kt:12). A model switch without rebuilding these leaves stale metadata in provenance exports.
**Fix (applied):** `reconfigure()` also calls `ToolRegistryFactory.rebuildModelDependentTools(model)`. Provenance tools use dynamic lookup from SpolaInstance.

## Medium Findings

### Medium — Resource leak on instance recreation
**Problem:** `SpolaInstance.close()` only shuts down plugins and closes memory. It does NOT close the file watcher (`JvmIndexCoordinator`), scheduler store (`SqliteSpolaJobStore`), or checkpoint store (`CheckpointStore`). Every `/model` or `/provider` switch leaks these.
**Fix (applied):** `SpolaInstance.close()` now owns and closes all resources (TASK-009).

### Medium — "Sealed interface" vs "pluggable" contradiction
**Problem:** Plan said "pluggable command architecture" but proposed `sealed interface` — which is closed by definition.
**Fix (applied):** Changed to plain `interface SlashCommand` — open for implementation.

### Medium — `/models` hard-reject breaks ollama/openai-compat
**Problem:** v1 plan proposed hardcoded `ModelStore` that rejects unknown models. This breaks `ollama` and `openai-compat` where arbitrary custom model names are legitimate.
**Fix (applied):** `/models` is advisory. Lists known models, does NOT reject unknown names.

## Missing Commands (added to plan)

- `/status` — current provider, model, session id, workdir
- `/config` — active resolved config
- `/doctor` — provider/env/connectivity diagnostics
- `/retry` — rerun last goal after transient failure
- `/session list/save/load/delete/new` — full session CRUD
- `/checkpoints` — view saved checkpoint IDs

## Phase Audit (updated in plan)

| Phase | v1 Estimate | Revised Estimate | Reason |
|-------|-------------|------------------|--------|
| 0 | 1-2 days | **2 days** | Added sessionId wiring + lifecycle fix + runFull() |
| 1 | 2-3 days | **1-2 days** | Move to Phase 1 after Phase 0; no JLine dependency |
| 2 | 2-3 days | **3-4 days** | More realistic for cross-platform terminal testing |
| 3 | 2-3 days | **3-4 days** | Added tool registry rebuild, lifecycle, dynamic model lookup |
| 4 | 2-3 days | **1-2 days** | Smaller after reusing CheckpointManager |
| 5 | 1-2 days | **1-2 days** | Moved doctor to picocli; reconfigure belongs in Phase 3 |
| **Total** | **9-15 days** | **11-16 days** | More realistic estimates after deeper analysis |
