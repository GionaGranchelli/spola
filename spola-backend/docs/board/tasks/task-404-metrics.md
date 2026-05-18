# T-404: Application Metrics

## Goal
Add application metrics support to Golem so operators can monitor performance, tool usage, and agent behavior.

## Background
Golem already has OpenTelemetry tracing (GolemTracer) but zero metrics. This task adds a Prometheus-compatible `/api/metrics` endpoint and instrumented counters/gauges/histograms throughout the agent loop.

## Requirements

### 1. Metrics registry
- Create `GolemMetrics` class in `dev.spola.metrics` package
- Uses `io.prometheus:simpleclient` (no OpenTelemetry metrics API — Prometheus is lighter for self-hosted agents)
- SimpleCounter, SimpleGauge, SimpleHistogram wrappers
- Default labels: `service="golem"`

### 2. Metrics to track

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `golem_agent_runs_total` | Counter | `status={success,fail}` | Total agent runs |
| `golem_agent_run_duration_seconds` | Histogram | — | Agent run duration (buckets: 0.1, 1, 5, 15, 60) |
| `golem_agent_turns_total` | Counter | — | Total ReAct loop turns |
| `golem_tool_calls_total` | Counter | `tool=...,status={success,fail}` | Tool call count |
| `golem_tool_call_duration_seconds` | Histogram | `tool=...` | Tool execution duration |
| `golem_llm_calls_total` | Counter | `provider=...,model=...` | LLM call count |
| `golem_llm_tokens_total` | Counter | `type={input,output}` | Total tokens processed |
| `golem_scheduler_jobs_executed_total` | Counter | — | Scheduler job executions |
| `golem_active_sessions` | Gauge | — | Currently active agent sessions |

### 3. `/api/metrics` endpoint
- `GET /api/metrics` — returns Prometheus text format (`text/plain; version=0.0.4`)
- Accessible without auth (operational healthcheck endpoint)
- Handles concurrent reads safely via `CollectorRegistry.defaultRegistry`
- Stima: **0.5h**

### 4. Integration points
- **GolemAgent**: increment run counter + duration, turn counter, LLM call counter + tokens
- **Tool execution** (ToolRegistry or wrapper): increment tool call counter + duration histogram
- **GolemTracerObserver** or new MetricsObserver: call metrics from observer hooks
- **GolemFactory**: create GolemMetrics and wire into agent + tools
- **GolemInstance**: expose `metrics: GolemMetrics`, close on shutdown

### 5. Configuration
- Add `metricsEnabled: Boolean = true` to `GolemConfig`
- CLI flag: `--metrics-enabled` (default: true)
- When disabled, all metric calls are no-ops (check `GolemMetrics.isEnabled`)

### 6. Tests
- GolemMetrics records counter increments correctly
- GolemMetrics histogram records observations
- `/api/metrics` returns valid Prometheus text format
- Disabled metrics produce no error and return empty /metrics
- All existing 143+ tests pass

## Files
```
golem-core/src/main/kotlin/dev/golem/
├── metrics/
│   └── GolemMetrics.kt           — NEW: metrics registry + wrappers

golem-core/src/main/kotlin/dev/golem/
├── GolemAgent.kt                  — MODIFY: wire metrics
├── GolemFactory.kt                — MODIFY: create + wire GolemMetrics
├── GolemConfig.kt                 — MODIFY: add metricsEnabled flag

golem-core/src/main/kotlin/dev/golem/api/
├── GolemApiServer.kt              — MODIFY: add /api/metrics route

golem-core/src/test/kotlin/dev/golem/metrics/
├── GolemMetricsTest.kt            — NEW: metrics tests
├── MetricsEndpointTest.kt         — NEW: endpoint tests
```

## Dependencies
```kotlin
implementation("io.prometheus:simpleclient:0.16.0")
implementation("io.prometheus:simpleclient_httpserver:0.16.0")  // only if standalone HTTP
```

No — use simpleclient core only and render Prometheus text format manually via `CollectorRegistry.defaultRegistry.metricFamilySamples()`.
