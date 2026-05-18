# ADR-002: ReAct Loop Design

- **Status:** accepted
- **Owner:** maintainer
- **Last updated:** 2026-05-11

## Context

An autonomous coding agent needs a loop that:
1. Takes a user goal and a system persona
2. Sends the conversation to an LLM with available tool definitions
3. If the LLM returns a tool call → executes the tool → appends result → goto 2
4. If the LLM returns text → that's the final answer

This is the ReAct (Reasoning + Acting) pattern, used by Claude Code, Hermes, and
all modern coding agents.

## Decision

Implement a **turn-based ReAct loop** with:

- **Max 25 turns** (configurable via `maxTurns`)
- **Tool registry** maps tool names to executable functions
- **Conversation history** accumulates as a mutable list of `ChatMessage`
- **Tool schemas** auto-generated from tool metadata (name, description, parameters)
- **Idempotent tool execution** — tools that fail are retried once before giving up

Loop pseudocode:
```
messages = [system(persona), user(goal)]
for turn in 1..maxTurns:
    response = llm.complete(messages, tools=schemas)
    if response.isText: return response.text
    if response.isToolCall:
        result = execute(response.toolCall)
        messages += toolResult(result)
        continue
throw MaxTurnsExceeded(maxTurns)
```

## Consequences

- Simple, predictable, easy to test
- No streaming support in MVP (add in phase 2)
- Tools must be idempotent or tolerate replay
- LLM provider choice is delegated to TramAI's model routing
