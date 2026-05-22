# T-404: Application Metrics

## Goal
Add application metrics support to Spola so operators can monitor performance, tool usage, and agent behavior.

## Background
Spola already has OpenTelemetry tracing (SpolaTracer) but zero metrics. This task adds a Prometheus-compatible `/api/metrics` endpoint and instrumented counters/gauges/histograms throughout the agent loop.

## Requirements

### 1. Metrics registry
- Create `SpolaMetrics` class in `dev.spola.metrics` package
- Uses `io.prometheus:simpleclient` (no OpenTelemetry metrics API — Prometheus is lighter for self-hosted agents)
- SimpleCounter, SimpleGauge, SimpleHistogram wrappers
- Default labels: `service="spola"`

### 2. Metrics to track

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `spola_agent_runs_total` | Counter | `status={success,fail}` | Total agent runs |
| `spola_agent_run_duration_seconds` | Histogram | — | Agent run duration (buckets: 0.1, 1, 5, 15, 60) |
| `spola_agent_turns_total` | Counter | — | Total ReAct loop turns |
| `spola_tool_calls_total` | Counter | `tool=...,status={success,fail}` | Tool call count |
| `spola_tool_call_duration_seconds` | Histogram | `tool=...` | Tool execution duration |
| `spola_llm_calls_total` | Counter | `provider=...,model=...` | LLM call count |
| `spola_llm_tokens_total` | Counter | `type={input,output}` | Total tokens processed |
| `spola_scheduler_jobs_executed_total` | Counter | — | Scheduler job executions |
| `spola_active_sessions` | Gauge | — | Currently active agent sessions |

### 3. `/api/metrics` endpoint
- `GET /api/metrics` — returns Prometheus text format (`text/plain; version=0.0.4`)
- Accessible without auth (operational healthcheck endpoint)
- Handles concurrent reads safely via `CollectorRegistry.defaultRegistry`
- Stima: **0.5h**

### 4. Integration points
- **SpolaAgent**: increment run counter + duration, turn counter, LLM call counter + tokens
- **Tool execution** (ToolRegistry or wrapper): increment tool call counter + duration histogram
- **SpolaTracerObserver** or new MetricsObserver: call metrics from observer hooks
- **SpolaFactory**: create SpolaMetrics and wire into agent + tools
- **SpolaInstance**: expose `metrics: SpolaMetrics`, close on shutdown

### 5. Configuration
- Add `metricsEnabled: Boolean = true` to `SpolaConfig`
- CLI flag: `--metrics-enabled` (default: true)
- When disabled, all metric calls are no-ops (check `SpolaMetrics.isEnabled`)

### 6. Tests
- SpolaMetrics records counter increments correctly
- SpolaMetrics histogram records observations
- `/api/metrics` returns valid Prometheus text format
- Disabled metrics produce no error and return empty /metrics
- All existing 143+ tests pass

## Files
```
spola-backend-core/src/main/kotlin/dev/spola/
├── metrics/
│   └── SpolaMetrics.kt           — NEW: metrics registry + wrappers

spola-backend-core/src/main/kotlin/dev/spola/
├── SpolaAgent.kt                  — MODIFY: wire metrics
├── SpolaFactory.kt                — MODIFY: create + wire SpolaMetrics
├── SpolaConfig.kt                 — MODIFY: add metricsEnabled flag

spola-backend-core/src/main/kotlin/dev/spola/api/
├── SpolaApiServer.kt              — MODIFY: add /api/metrics route

spola-backend-core/src/test/kotlin/dev/spola/metrics/
├── SpolaMetricsTest.kt            — NEW: metrics tests
├── MetricsEndpointTest.kt         — NEW: endpoint tests
```

## Dependencies
```kotlin
implementation("io.prometheus:simpleclient:0.16.0")
implementation("io.prometheus:simpleclient_httpserver:0.16.0")  // only if standalone HTTP
```

No — use simpleclient core only and render Prometheus text format manually via `CollectorRegistry.defaultRegistry.metricFamilySamples()`.
