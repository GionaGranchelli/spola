# Changelog

## [0.1.1] — 2026-05-24

### Features

- **Conversation Compactor** — Heuristic compaction engine that preserves system
  messages, pinned messages, and recent turns while summarizing older segments.
  Triggered by token-budget or message-count thresholds. Idempotent compaction
  with `ConversationCompactor` + `ConversationCompactionConfig`.
- **Pin Mechanism** — Pin messages by index or `/pin last` command. Pinned messages
  are persisted in SQLite and displayed with `[PIN]` marker in `/history`.
- **Token Display** — Cumulative token counters with per-turn metrics. Input,
  output, and thinking tokens displayed via SSE `token_usage` events.
- **TokenEstimator** — Character-to-token ratio (4:1), `estimateMessages()`,
  and `exceedsBudget()` utilities for context window management.
- **SSE token_usage Events** — 7-field `TokenUsageEventPayload` emitted during
  streaming in `StreamHandler.kt`.
- **Frontend Token UI** — Token usage display in ChatPage via `TokenUsageBar`
  composable (Spola frontend).

### Improvements

- **SpolaMetrics** — Added `spola_llm_tokens_total{type="thinking"}` counter label.
- **Tests** — `ConversationCompactorTest` (11 tests), `TokenEstimatorTest` (5 tests).
- **Frontend** — `rootDeposit` endpoint, worktree fixes, mode-aware `/worktree`
  commands (Spola frontend).

### Bug Fixes

- None in this release.
