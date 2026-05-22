# Tool Attention — Evaluation for Spola Integration

**Source:** [Tool Attention: A Framework for Efficient Tool Selection in LLM Agents](https://arxiv.org/abs/2604.21816) (arxiv:2604.21816, April 2026)

**Reviewer:** OpenAI Codex CLI (gpt-5.4) — read actual source code, not plan summaries

**Status:** Nice-to-have — viable concept, but marginal gains on Spola's current tool surface

---

## The Concept

Tool Attention replaces the "inject ALL tool schemas every turn" pattern with a two-phase retrieval approach:

1. **Summaries** — tiny descriptor for every tool (name + one-line description)
2. **Full schemas** — only for the top-k tools semantically relevant to the current query

The paper reports ~90% token reduction on a simulated 120-tool benchmark.

---

## Spola's Actual Baseline (measured from source)

Codex audited every registered tool in the codebase (`Tool.kt`, `SpolaAgent.kt`, `ToolRegistryFactory.kt`, `SkillTools.kt`, `McpClientManager.kt`):

| Metric | Raw | Notes |
|--------|-----|-------|
| Built-in tools | ~52 | Not 60+ as assumed in the plan |
| Full schema payload (all tools) | ~5,200 chars = ~1,300-1,500 tokens | Not 20k-40k |
| Summary pool (all tools) | ~4,900 chars = ~1,200-1,400 tokens | ~787 was the plan's estimate |
| Top-10 full schemas | ~7,400 chars = ~1,850-2,100 tokens | ~4.7k was the plan's estimate |
| Max first-turn savings | ~40-45% | Not 85-90% |

**Why the gap:** The paper's headline savings come from a simulated 120-tool benchmark. Spola has ~52 tools, and many are simple (1-2 string parameters). Tool schemas in Spola are compact by design.

---

## Gaps Found

### HIGH — Missing metadata layer

The plan assumed `sideEffectLevel`, capability labels, and auth scopes for the state gate. Spola's `Tool` data class only has:
```kotlin
data class Tool(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    val execute: suspend (Map<String, Any>) -> ToolResult,
)
```

The state gate cannot function without a metadata layer first. This is a prerequisite, not a Phase 4 detail.

### HIGH — Unbounded retry loop

The proposed hallucination rejection gate calls `callLlm()` recursively inside a single turn. The outer `maxTurns` guard never fires because the recursion doesn't count as a new turn. If the model keeps calling a summarized-but-inactive tool, it loops indefinitely.

### HIGH — Token estimates off

Plan claimed 20k-40k total, so 90% reduction was benchmarked against a fictional baseline. Real baseline is ~5.1k-5.8k tokens. Best-case savings on first turn: ~40-45%.

### MEDIUM — Runtime registry changes

Spola's tool registry changes at runtime:
- Skill activation (`activateSkill` in `SkillTools.kt`)
- MCP tool registration/deregistration (`McpClientManager.kt`)
- Agent-scoped tool filtering (`ToolRegistryFactory.kt`)

A startup-only embedding index is insufficient. The index must recompute or invalidate on every registry change.

### MEDIUM — No prompt cache abstraction

The plan assumed a "summary pool in a cached prompt prefix." Spola's `ModelRequest` only has `messages` + `tools`. There is no provider-neutral prompt-cache control, and `ConcurrentHashMap` iteration (used by `ToolRegistry.schemas()`) provides non-deterministic tool ordering — bad for stable prefix caching.

### MEDIUM — Duplicates existing gating

`ToolRegistryFactory` already filters tools by agent scope (`networkAccess`, etc.) before tools reach `SpolaAgent`. The Tool Attention state gate would re-do this filtering at a different layer.

---

## Recommended Architecture

Rather than a middleware wrapper with speculative metadata, Codex recommended:

```
ToolSelectionStrategy (interface)
  └─ IntentBasedSelector (embedding + threshold + topK)
       └─ returns List<String> of active tool names

ToolSchemaProvider (interface)
  └─ LazySchemaProvider (full schemas on demand, sorted deterministically)

ToolDescriptor (metadata layer, optional extension of Tool or parallel registry)
  └─ schemaHash, tags, riskLevel, availabilityCheck
```

**Key difference from the plan:** No two-phase (summary + schema) split in V1. Just "rank tools, send top-k full schemas." This avoids exposing non-callable tool names and cuts the miss rate sharply.

---

## Safe Retry Design

Replace recursive `callLlm()` with a bounded in-turn recovery:

1. Reject once with compact machine-readable error listing active tools
2. Broaden `topK` (topK * 2, then topK * 3)
3. If it misses again, send full enabled registry for that turn + record metric
4. If repeated across turns, auto-disable Tool Attention for the session

---

## Recommended Phasing

| Phase | What | Effort |
|-------|------|--------|
| 0 | Instrumentation + baseline token counting on real runs | 0.25d |
| 1 | `ToolSelectionStrategy` interface + deterministic tool ordering | 0.5d |
| 2 | Simple top-k full-schema routing (no summary pool) | 0.5d |
| 3 | Bounded rejection + progressive broadening fallback | 0.5d |
| 4 | Dynamic embedding cache for skills/MCP runtime changes | 0.5d |
| 5 | Summary-pool prompt-caching optimization (only if justified) | 0.5d |
| | **Total** | **2.75d** |

---

## Conclusion

**Viable but low priority.** The paper's central insight (semantic tool routing is better than bulk injection) is sound, but Spola's tool surface is too small for the paper's headline savings. The marginal gains (~40% first-turn) don't justify the complexity at current scale.

**Revisit when:**
- Spola's tool surface grows past 80-100 tools (from skills, MCP servers, plugins)
- Provider prompt caching becomes a first-class TramAI abstraction
- A real-world token profiling run shows that tool schemas dominate per-turn cost (currently, conversation history dwarfs tool schemas in most sessions)
