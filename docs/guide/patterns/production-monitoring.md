---
layout: page
title: Production Monitoring
description: "Monitor LLM4S applications in production: Langfuse or OpenTelemetry tracing, Prometheus or Micrometer metrics, agent runtime metrics and cost tracking."
search_title: "Monitor LLM applications in production: metrics and alerts"
parent: Real-World Application Patterns
grand_parent: Operations
nav_order: 3
---

# Production Monitoring
{: .no_toc }

In production you need two views: traces, to see what one request or agent run did, and metrics, to see whether all
of them are healthy and what they cost. LLM4S ships both as modules you add, configured rather than hand-written.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

---

## What ships

| Need | Component | Module |
|---|---|---|
| Traces of LLM calls and agent runs in an LLM tool | Langfuse tracing backend (`TRACING_MODE=langfuse`) | `llm4s-observability` |
| Traces in your existing APM | OpenTelemetry tracing backend (`TRACING_MODE=opentelemetry`), GenAI semantic conventions | `llm4s-observability-otel` |
| Request, token, cost, error and latency metrics | `PrometheusMetrics` and its `/metrics` endpoint | `llm4s-observability-prometheus` |
| The same metrics in an existing Micrometer registry | `MicrometerMetrics` | `llm4s-observability-micrometer` |
| Runs, claims, commits and run queue metrics of the agent runtime | `MeteredCheckpointer`, and an `AgentWorker` given the collector | `llm4s-agent` |
| Spend and token totals in process, with no infrastructure | `CostTracker` | `llm4s-observability` |
| Inspecting traces in tests or in process | the trace collector (`collector` mode) | `llm4s-observability` |

All tracing backends implement the `Tracing` contract in core and can be combined with `TracingComposer.combine`;
all metrics backends implement `MetricsCollector` and can be combined with `MetricsCollector.compose`. The
[Monitoring guide](../observability/) documents each one.

## Tracing an agent

Core reads `llm4s.tracing.mode` (bound to `TRACING_MODE`) and hands the selected backend its block of configuration;
adding the backend's module is all it takes to make the mode available. An agent built with `withTracing` records each
run's model calls, tool calls and token usage, and ends each run with one `TraceEvent.AgentRunEnded`.

```scala
import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMClient
import org.llm4s.trace.Tracing
import org.llm4s.types.Result

def tracedAgent(client: LLMClient): Result[Agent] = for {
  settings <- Llm4sConfig.tracing()   // llm4s.tracing.*, e.g. TRACING_MODE=langfuse with LANGFUSE_* set
  tracing  <- Tracing.fromSettings(settings)
  agent <- Agent.builder("assistant", client)
             .withTracing(tracing)
             .build()
} yield agent
```

`Tracing.fromSettings` returns an error when the backend is missing or cannot start. If tracing must never stop the
application, say so here: `Tracing.fromSettings(settings).getOrElse(new NoOpTracing())`, logging the error first. What each mode records, and
which settings keep message content out of traces, is in
[Data Protection](../data-protection#logging-and-tracing-defaults).

## Metrics and alerts

Metrics are off until you ask for them. With `llm4s-observability-prometheus` on the classpath and
`llm4s.metrics.enabled = true`, `MetricsConfigLoader.default()` returns the collector and starts the endpoint; pass the
collector to `LLMConnect.getClient(providerConfig, metrics)` and every call is counted by provider and model. The
[metrics section](../observability/#metrics) has the configuration, the series and example alert queries (error
share, p95 latency, rate-limit errors, spend per hour).

For an agent service, wrap the shared store in `MeteredCheckpointer(store.checkpointer, metrics)` and give the
`AgentWorker` the same collector: you then get run outcomes and durations, claim and commit health, and run queue depth,
age and dead letters ([agent runtime metrics](../observability/#agent-runtime-metrics)).

`ReliableClient` retries are visible as extra requests: each attempt is one call to the wrapped client. See
[Error Recovery](error-recovery).

## Cost

- **Per process:** `CostTracker.create()` is a `MetricsCollector`; pass it (or compose it with Prometheus) to
  `LLMConnect.getClient`, then read `totalCost`, `byModel` or `summary`.
- **Per conversation:** `AgentResult.usage` carries the token usage accumulated over the thread, per model, and a traced agent sends each model call's
  usage to its tracing backend as `TokenUsageRecorded`.
- **Fleet-wide:** the Prometheus series include estimated spend by model.
- **Reducing it:** [Cost controls](../cost-controls) and [Caching](../caching).

## Health checks

Validate configuration at startup and expose readiness separately from liveness; the
[health checks section](../observability/#health-checks) shows how. `llm4s-doctor` checks an application's
configuration and providers from its own classpath: see [Doctor](../../reference/doctor).

## See also

- [Monitoring](../observability/) and [Micrometer metrics](../observability/micrometer)
- [Capturing provider requests and responses](../observability/provider-exchange-logging)
- [Production Deployment](../production-deployment)
