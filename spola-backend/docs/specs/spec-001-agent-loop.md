# SPEC-001: Autonomous Agent Loop

- **Status:** draft
- **Owner:** maintainer
- **Last updated:** 2026-05-11
- **Related ADRs:** ADR-001, ADR-002

## Problem

Provide a ReAct-style autonomous loop that lets an LLM drive tool execution
toward a user goal, with a system persona providing context and guardrails.

## Functional Requirements

1. The loop must accept a system persona and a user goal as strings
2. The loop must present available tools to the LLM with name, description, and parameter schema
3. The loop must execute tool calls returned by the LLM and feed results back
4. The loop must detect when the LLM produces a final text response and return it
5. The loop must enforce a configurable max turn limit (default 25)
6. Tool execution failures must be reported back to the LLM, not silently ignored
7. The loop must support at least OpenAI-compatible and Anthropic providers via TramAI

## Non-Goals

- Streaming responses (phase 2)
- Multi-agent coordination (future)
- Visual/HTML output (CLI-only for MVP)

## Acceptance Criteria

- [x] Agent runs a 3-turn ReAct sequence with a mock LLM and passes
- [x] Agent returns text response when LLM stops calling tools
- [x] Agent enforces maxTurns and throws MaxTurnsExceededException
- [x] Tool errors are returned to the LLM as tool result messages
- [x] Full integration test: agent reads a file, processes it, writes result
