# Codex Review: Spola CLI Redesign Plan (v2)

**Reviewer:** OpenAI Codex CLI (gpt-5.4)
**Plan reviewed:** `.hermes/plans/2026-05-14_spola-backend-cli-redesign.md` (v2 — revised)
**Date:** 2026-05-14

## Verdict

**CONDITIONAL** — all 5 previous HIGH findings resolved. 3 MEDIUM remaining (documented below, all fixed in plan).

## Resolved Findings (from v1 FAIL)

| # | Finding | Status | Evidence |
|---|---------|--------|----------|
| HIGH #1 — Module cycle | **RESOLVED** | `runRepl()` moves entirely into spola-backend-cli. Runner.kt keeps only `runOneShot()`. Core → CLI dependency direction is correct. |
| HIGH #2 — Conversation wipe | **RESOLVED** | `ReplSession` owns the transcript. `runFull(persona, goal, transcript: MutableList<ChatMessage>)` mutates the caller's list in-place. Agent's internal conversation is bypassed in REPL mode. |
| HIGH #3 — Dead `--resume` | **RESOLVED** | Phase 0/TASK-008 wires `config.sessionId` into execution. `SpolaAgent.run(..., sessionId)` already exists — only `SpolaInstance.run()` needed the forwarding. |
| HIGH #4 — CheckpointManager duplicate | **RESOLVED** | Phase 4 reuses `CheckpointManager`. No new `ConversationStore`. Plan now uses correct `save(sessionId, turn, json)` signature. |
| HIGH #5 — reconfigure scope | **RESOLVED** | `reconfigure()` calls `ToolRegistryFactory.rebuildModelDependentTools(model)` to unregister/re-register provenance tools with new model. No vague "supplier-based lookup." |

## Remaining Findings (MEDIUM)

| # | Finding | Fix Applied |
|---|---------|-------------|
| MEDIUM #1 — `runFull()` must mutate transcript in-place, not append an `AssistantMessage` after the call | ReplSession design updated: `runFull()` mutates the caller-owned `MutableList<ChatMessage>` throughout the ReAct loop, appending tool-call, tool-result, and assistant messages. Session just reads `result` from the return value. |
| MEDIUM #2 — `CheckpointManager.save()` has a `turn` parameter, plan was citing nonexistent 2-arg signature | All references updated to `save(sessionId, turnNumber, json)`. `ReplSession` tracks `turnNumber`. |
| MEDIUM #3 — Dynamic model lookup from SpolaInstance doesn't fit construction order | Replaced with explicit `ToolRegistryFactory.rebuildModelDependentTools(model)` — unregisters and re-registers provenance tools with the new model string. |

## Additional Improvements Made

- Phase 1 now explicitly lists `/tools`, `/memory`, `/persona`, `/history`, `/exit`, `/quit` as migration tasks (TASK-023 through TASK-027)
- Phases 3 and 4 marked as serial (not parallel) for 1-person team
- Effort revised from 9-15 days to **12-17 days** for 67 tasks
- `ConsoleObserver` to be redefined in spola-backend-cli (private in Runner.kt, needs to move)
- `SpolaInstance.close()` must own all resources: file watcher, scheduler store, checkpoint store

## Architecture Fit

The module boundary is now correct: terminal and REPL concerns in `spola-backend-cli`, agent/session/provider APIs in `spola-backend-core`.

The remaining architectural hole: the execution primitive is still "run one goal" not "advance an external transcript." The `ReplSession` + `runFull()` design closes this gap for the REPL path but does not change the existing `run()` API for one-shot and API consumers.

## Recommendations

If starting implementation:
1. Begin Phase 0 (emergency fixes) and TASK-008 (wire sessionId) — no terminal/architectural dependencies
2. Do lifecycle ownership refactoring (TASK-009, TASK-046) before Phase 3 reconfigure work
3. Move `runRepl()` out of Runner.kt early — simplifies all subsequent phases
4. Watch for `CheckpointManager.save(sessionId, turn, json)` — the `turn` parameter is an Int, not derived from the conversation list
