---
layout: page
title: Monitoring
nav_order: 10
parent: User Guide
has_children: false
---

# Monitoring LLM4S Applications
{: .no_toc }

Observability and monitoring for LLM4S applications in production, covering latency, provider reliability, token usage, and cost visibility.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Monitoring LLM applications differs from traditional service monitoring. Key concerns include:

- **Latency variability** - LLM calls range from milliseconds to minutes depending on model and prompt size
- **Provider reliability** - External API dependencies with rate limits and outages
- **Token usage & cost** - Direct correlation between usage and spend
- **Trace debugging** - Understanding multi-turn conversations and tool executions
- **Quality signals** - Beyond uptime: response relevance, hallucinations, guardrail triggers

LLM4S provides tracing infrastructure through multiple backends. Production monitoring typically combines tracing with your existing logging and alerting stack.

---

## Logging in Production

LLM4S uses SLF4J for logging. Configure your logging backend for structured JSON output in production environments.

### Logback Configuration

```xml
<!-- logback.xml -->
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
  </appender>

  <!-- LLM4S logs at INFO for operational visibility -->
  <logger name="org.llm4s" level="INFO"/>
  
  <!-- Reduce noise from HTTP clients -->
  <logger name="sttp.client3" level="WARN"/>
  
  <root level="WARN">
    <appender-ref ref="JSON"/>
  </root>
</configuration>
```

### What Gets Logged

At `INFO` level, LLM4S logs:

- Client initialization and shutdown
- Tracing backend connection status
- Configuration validation results

At `DEBUG` level (not recommended for production):

- Request/response payloads
- Token counts per request
- Tracing event details

---

## Tracing for Observability

LLM4S supports five tracing backends:

| Mode | Use Case | Configuration | Module |
|------|----------|---------------|--------|
| `langfuse` | Production LLM observability | `TRACING_MODE=langfuse` | `llm4s-observability` |
| `opentelemetry` | Integration with existing APM | `TRACING_MODE=opentelemetry` | `llm4s-observability-otel` |
| `console` | Local development/debugging | `TRACING_MODE=console` | `llm4s-core` |
| `noop` | Disabled | `TRACING_MODE=noop` | `llm4s-core` |
| `collector` | In-process queryable store | Programmatic (see below) | `llm4s-observability` |

All backends implement the `Tracing` trait and can be composed with `TracingComposer.combine()`.
`llm4s-core` holds the contract - `Tracing`, `TraceEvent`, `TracingComposer`, `TracingMode`, the
`TracingBackend` SPI - and builds only `console` and `noop` itself. Every other mode is a
`TracingMode.Named` (`TracingMode.Named("langfuse")`, `TracingMode.Named("opentelemetry")`) served
by a backend its module registers, so adding the dependency is all it takes:

```scala
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-observability"      % llm4sVersion, // Langfuse, trace collector/store, CostTracker
  "org.llm4s" %% "llm4s-observability-otel" % llm4sVersion  // OpenTelemetry
)
```

On `0.4.1` and earlier, Langfuse, the collector and `CostTracker` ship inside `llm4s-core`.

### Configuration

Core reads only `llm4s.tracing.mode`, and hands the selected mode's block to its backend as
`TracingSettings.extras`. Each block, with its variable bindings, ships in the `reference.conf`
of the module that reads it - `langfuse` in `llm4s-observability`, `opentelemetry` in
`llm4s-observability-otel` - so the keys and variables below are unchanged, and apply once the
module is on the classpath:

```hocon
llm4s {
  tracing {
    mode = ${?TRACING_MODE}
    
    langfuse {
      url       = ${?LANGFUSE_URL}
      publicKey = ${?LANGFUSE_PUBLIC_KEY}
      secretKey = ${?LANGFUSE_SECRET_KEY}
    }
    
    opentelemetry {
      serviceName = ${?OTEL_SERVICE_NAME}
      endpoint    = ${?OTEL_EXPORTER_OTLP_ENDPOINT}
    }
  }
}
```

### OpenTelemetry Integration

For teams with existing APM infrastructure (Jaeger, Grafana Tempo, Datadog), the `opentelemetry` mode (alias `otel`) exports traces via OTLP:

```bash
TRACING_MODE=opentelemetry
OTEL_SERVICE_NAME=my-llm-service
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```

This requires the `llm4s-observability-otel` module:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-observability-otel" % llm4sVersion
```

The module registers itself for `opentelemetry` through a `META-INF/services` entry, so the
dependency is all it takes. Its backend builds an `OpenTelemetryConfig` from the
`llm4s.tracing.opentelemetry` block with `OpenTelemetryConfig.fromExtras`.

### Adding a Tracing Backend

A backend is a `TracingBackend` - the extension point `llm4s-observability` and
`llm4s-observability-otel` use - so adding one needs no change to llm4s:

```scala
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.TracingSettings
import org.llm4s.trace.{ Tracing, TracingMode }
import org.llm4s.trace.spi.TracingBackend
import org.llm4s.types.Result

// A class with a public no-arg constructor - not an object: ServiceLoader instantiates it
final class DatadogTracingBackend extends TracingBackend:
  val mode: TracingMode = TracingMode.Named("datadog")
  def create(settings: TracingSettings): Result[Tracing] =
    settings.extras.get("apiKey") match
      case Some(key) => Right(new DatadogTracing(key, settings.extras.getOrElse("site", "datadoghq.com")))
      case None      => Left(ConfigurationError("llm4s.tracing.datadog.apiKey is not set"))
```

The backend's settings live under `llm4s.tracing.<mode>`, and it reads them from
`settings.extras`: that block for the selected mode, flattened to strings keyed by path within it
(`site`, `tags.team`). Only the selected mode's block is read. Ship the defaults and variable
bindings in the backend module's own `reference.conf`, which HOCON merges with the application's:

```hocon
llm4s.tracing.datadog {
  site   = "datadoghq.com"
  site   = ${?DD_SITE}
  apiKey = ${?DD_API_KEY}
}
```

Declare it in `src/main/resources/META-INF/services/org.llm4s.trace.spi.TracingBackend`:

```
com.example.DatadogTracingBackend
```

`TRACING_MODE=datadog` then selects it. `Tracing.fromSettings(settings)` returns a
`ConfigurationError` when no backend serves the configured mode, and the backend's own error when
`create` fails; `Tracing.create(settings)` logs either and falls back to `NoOpTracing`. Where
services files do not survive packaging (some shaded jars), register the backend explicitly:
`Tracing.fromSettings(settings, TracingBackends.of(new DatadogTracingBackend))`.

---

## In-Process Trace Collection

`TraceCollectorTracing` + `InMemoryTraceStore` provide a fully queryable trace store
that runs entirely within the JVM — no external service required. They ship in
`llm4s-observability` (`org.llm4s.trace`, `org.llm4s.trace.store`, `org.llm4s.trace.model`). Recorded spans can be
filtered, paginated, and serialized to JSON.

This is the primary backend for **unit testing** and for **in-process analytics** (latency
breakdowns, cache hit rates, token cost per span kind).

### Quick Start

```scala
import org.llm4s.trace._
import org.llm4s.trace.store._
import org.llm4s.trace.model._

val store  = InMemoryTraceStore()
// apply returns Result[TraceCollectorTracing]; InMemoryTraceStore never fails
val tracer = TraceCollectorTracing(store).getOrElse(sys.error("tracing init failed"))

// pass tracer to any agent run
agent.run("query", tools, tracing = tracer)

// retrieve all spans for this run
val spans = store.getSpans(tracer.traceId)
```

### Querying Traces

`TraceStore.queryTraces` accepts a `TraceQuery` with optional filters and cursor-based
pagination. All filters combine with AND semantics.

```scala
import java.time.Instant

// recent traces
val page = store.queryTraces(
  TraceQuery.withTimeRange(Instant.now.minusSeconds(3600), Instant.now)
)

// failed traces only
val errors = store.queryTraces(TraceQuery.withStatus(SpanStatus.Error(""))).traces

// by metadata tag (e.g. experiment grouping)
val traceIds = store.searchByMetadata("experiment", "v2")

// combined filter with pagination
val q = TraceQuery(
  startTimeFrom = Some(Instant.now.minusSeconds(3600)),
  status        = Some(SpanStatus.Error("")),
  limit         = 10
)
val first = store.queryTraces(q)
if (first.hasNext) {
  val second = store.queryTraces(q.copy(cursor = first.nextCursor))
}
```

### Span Analytics

Every `Span` carries `startTime`, `endTime`, `kind`, `status`, and typed `attributes`,
making it straightforward to compute aggregates without an external system.

```scala
val allSpans = store.getSpans(tracer.traceId)

// total milliseconds spent in LLM calls
val llmMs = allSpans
  .filter(_.kind == SpanKind.LlmCall)
  .flatMap(s => s.endTime.map(e => e.toEpochMilli - s.startTime.toEpochMilli))
  .sum

// prompt tokens across the run
val promptTokens = allSpans
  .filter(_.kind == SpanKind.LlmCall)
  .flatMap(_.attributes.get("prompt_tokens").flatMap(_.asLong))
  .sum

// semantic cache hit rate
val cacheSpans = allSpans.filter(_.kind == SpanKind.Cache)
val hitRate = if (cacheSpans.isEmpty) 0.0
  else cacheSpans.count(
    _.attributes.get("hit").flatMap(_.asBoolean).contains(true)
  ).toDouble / cacheSpans.size
```

### Deterministic Agent Testing

Wire `TraceCollectorTracing` in ScalaTest specs to assert on recorded spans without
mocking external services or parsing console output.

```scala
import org.llm4s.trace.model.SpanKind
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

class AgentBehaviourSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  val store  = InMemoryTraceStore()
  val tracer = TraceCollectorTracing(store).getOrElse(fail("tracing init failed"))

  override def afterEach(): Unit = store.clear()

  "agent" should "call the calculator tool exactly once" in {
    agent.run("what is 6 * 7?", tools, tracing = tracer)

    val toolSpans = store.getSpans(tracer.traceId)
      .filter(_.kind == SpanKind.ToolCall)

    toolSpans should have size 1
    toolSpans.head.attributes("tool_name").asString shouldBe Some("calculator")
  }

  "agent" should "record no errors on a valid query" in {
    agent.run("hello", tools, tracing = tracer)

    store.getSpans(tracer.traceId)
      .filter(_.status.isInstanceOf[SpanStatus.Error]) shouldBe empty
  }
}
```

### Composing with External Backends

`TracingComposer.combine()` fans events out to multiple backends simultaneously.
Use this to keep a local in-process snapshot while also forwarding to Langfuse or OpenTelemetry.

```scala
val store     = InMemoryTraceStore()
val collector = TraceCollectorTracing(store).getOrElse(sys.error("tracing init failed"))
// LangfuseConfigLoader reads llm4s.tracing.langfuse whatever TRACING_MODE selects
val langfuse  = LangfuseTracing.from(LangfuseConfigLoader.default().getOrElse(sys.error("bad langfuse config")))

val tracer = TracingComposer.combine(collector, langfuse)
agent.run("query", tools, tracing = tracer)

// local span queries still work
val cacheSpans = store.getSpans(collector.traceId).filter(_.kind == SpanKind.Cache)
```

### Span JSON Round-Trip

`TraceModelJson` serialises and deserialises every span type losslessly via `ujson`.
Use this to write spans to disk, ship them to a custom HTTP endpoint, or reload them
for offline analysis.

```scala
import org.llm4s.trace.model.TraceModelJson._

val json   = span.toJson                      // ujson.Value
val parsed = TraceModelJson.parseSpan(json)   // Result[Span]

parsed match {
  case Right(s)    => println(s.name)
  case Left(error) => println(s"Parse error: ${error.field} — ${error.reason}")
}
```

---

## Langfuse Monitoring Workflow

Langfuse provides purpose-built LLM observability. When configured, LLM4S automatically traces:

### What Gets Captured

| Event Type | Description | Data Included |
|------------|-------------|---------------|
| **Traces** | Top-level request lifecycle | Query, final output, duration |
| **Generations** | Each LLM call | Model, prompt, completion, tokens, latency |
| **Spans** | Tool executions, retrieval ops | Tool name, input/output, duration |
| **Events** | Custom markers | User-defined metadata |

### Trace Structure Example

A typical RAG query produces this hierarchy:

```
Trace: "RAG Query Processing"
├── Span: "Document Retrieval" (200ms)
│   └── Event: "Retrieved 5 documents"
├── Generation: "Context Synthesis" (1,200ms)
│   ├── Model: gpt-4o
│   ├── Prompt tokens: 1,234
│   └── Completion tokens: 456
└── Event: "Response Delivered"
```

### Environment Setup

Langfuse is served by `llm4s-observability`:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-observability" % llm4sVersion
```

```bash
TRACING_MODE=langfuse
LANGFUSE_PUBLIC_KEY=<your-langfuse-public-key>
LANGFUSE_SECRET_KEY=<your-langfuse-secret-key>
LANGFUSE_URL=https://cloud.langfuse.com  # or self-hosted URL
```

Both keys are required. With either missing, `Tracing.fromSettings` returns a
`ConfigurationError` naming what is unset - e.g. `llm4s.tracing.langfuse.publicKey
(LANGFUSE_PUBLIC_KEY)` - and `Tracing.create` logs that error and falls back to `NoOpTracing`.
Without `llm4s-observability` on the classpath, the error names the artifact to add instead.
To read the Langfuse settings whatever `TRACING_MODE` selects (for example to combine Langfuse with
another tracer), use `LangfuseConfigLoader.default()` from `org.llm4s.config`.

### Tracing API

LLM4S exposes a `Tracing` trait for custom instrumentation:

```scala
import org.llm4s.trace.{Tracing, TraceEvent}

// Trace custom events
tracing.traceEvent(TraceEvent.CustomEvent("cache_hit", ujson.Obj("key" -> "query_123")))

// Trace token usage explicitly
tracing.traceTokenUsage(usage, model = "gpt-4o", operation = "completion")

// Trace an agent state snapshot (the agent does this after each step)
tracing.traceEvent(agentState.toTraceEvent)

// Trace costs
tracing.traceCost(
  costUsd = 0.0234,
  model = "gpt-4o",
  operation = "completion",
  tokenCount = 1500,
  costType = "total"
)

// Trace RAG operations
tracing.traceRAGOperation(
  operation = "search",
  durationMs = 150,
  embeddingTokens = Some(128),
  llmPromptTokens = None,
  llmCompletionTokens = None,
  totalCostUsd = Some(0.0001)
)
```

---

## Health Checks

### Startup Validation

Use `client.validate()` during application startup to fail fast on misconfiguration:

```scala
val clientResult = for {
  config <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(config)
  _      <- client.validate()
} yield client

clientResult match {
  case Left(error) =>
    logger.error(s"LLM client validation failed: $error")
    System.exit(1)
  case Right(client) =>
    logger.info("LLM client ready")
}
```

### Readiness Probes

For Kubernetes deployments, include LLM connectivity in readiness checks:

```scala
// Integrate with your framework's health check mechanism
def isLLMReady(): Boolean =
  client.validate().isRight
```

### Liveness vs Readiness

- **Liveness**: Application process is healthy (standard JVM checks)
- **Readiness**: Can serve LLM requests (includes `client.validate()`)

Separating these prevents pod restarts during temporary provider outages.

---

## Provider Reliability Signals

### Failures to Monitor

| Signal | Meaning | Action |
|--------|---------|--------|
| `429 Too Many Requests` | Rate limited | Back off, check quotas |
| `503 Service Unavailable` | Provider outage | Failover or queue |
| Connection timeout | Network issue | Retry with backoff |
| Response timeout | Slow generation | Increase timeout or reduce prompt |

### Logging Provider Failures

```scala
import org.llm4s.error._

def logProviderFailure(error: LLMError): Unit = error match {
  case rle: RateLimitError =>
    logger.warn(s"Rate limited: ${rle.message}, retry after: ${rle.retryAfter.getOrElse("unknown")}s")
    
  case ServiceError(message, httpStatus, provider, requestId) =>
    logger.error(s"Provider API error [$httpStatus]: $message")
    
  case NetworkError(message, cause, endpoint) =>
    logger.error(
      s"Network failure from $endpoint: $message" +
        cause.map(c => s" - ${c.getMessage}").getOrElse("")
    )
    
  case timeout: TimeoutError =>
    logger.warn(s"Request timeout: ${timeout.message}")
    
  case other =>
    logger.error(s"LLM error: $other")
}
```

### Alerting Thresholds

Recommended alert conditions for LLM services:

| Metric | Warning | Critical |
|--------|---------|----------|
| Error rate | > 1% | > 5% |
| P95 latency | > 10s | > 30s |
| Rate limit events | > 10/min | > 50/min |
| Daily token spend | > 80% budget | > 95% budget |

---

## Token & Cost Monitoring

### Token Usage Tracking

LLM4S traces token usage through the tracing infrastructure. With Langfuse, usage appears automatically in the dashboard.

For programmatic access:

```scala
completion.usage match {
  case Some(usage) =>
    logger.info(
      s"Tokens - prompt: ${usage.promptTokens}, " +
      s"completion: ${usage.completionTokens}, " +
      s"total: ${usage.totalTokens}"
    )
    
    // Trace for aggregation
    tracing.traceTokenUsage(usage, model, "completion")
    
  case None =>
    logger.warn("Token usage not available from provider")
}
```

### Cost Estimation

Pricing varies by provider and changes over time. For cost tracking:

- **Langfuse** automatically calculates costs when model pricing is configured in its dashboard
- **Provider billing APIs** offer authoritative usage and spend data
- **Manual tracking** can use `tracing.traceCost()` with your own pricing logic

```scala
// Track cost through tracing (pricing logic is your responsibility)
tracing.traceCost(
  costUsd = estimatedCost,
  model = "gpt-4o",
  operation = "completion",
  tokenCount = usage.totalTokens,
  costType = "total"
)
```

### Budget Awareness

Use context budget methods to prevent runaway costs:

```scala
import org.llm4s.agent.{AgentState, ContextWindowConfig}
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.HeadroomPercent

// Get available tokens considering model limits and safety margin
val budget = client.getContextBudget(HeadroomPercent.Standard)
val config = ContextWindowConfig(maxTokens = Some(budget))

// AgentState.pruneConversation uses a default token counter (words * 1.3)
// or accepts a custom tokenCounter function for more accurate estimation
// Build agent state with conversation + tool registry
val state = AgentState(conversation, ToolRegistry.empty)
val prunedState =
  AgentState.pruneConversation(
    state,
    config
  )
```

---

## Production Checklist

Before deploying, verify monitoring coverage:

### Tracing

- [ ] `TRACING_MODE` set to `langfuse` or `opentelemetry`
- [ ] Tracing credentials configured and tested
- [ ] Traces visible in dashboard (test with sample request)

### Logging

- [ ] Structured JSON logging enabled
- [ ] Log levels appropriate (`INFO` for `org.llm4s`)
- [ ] Logs shipping to aggregation system

### Alerting

- [ ] Error rate alerts configured
- [ ] Latency (P95/P99) alerts configured
- [ ] Rate limit event alerts configured
- [ ] Cost/budget alerts configured

### Health

- [ ] `client.validate()` called on startup
- [ ] Readiness probe includes LLM connectivity
- [ ] Graceful degradation for provider outages

---

## Known Limitations

Current monitoring limitations in LLM4S:

- **Prometheus metrics cover LLM and image-generation calls, not traces** - `PrometheusMetrics` (`llm4s-observability-prometheus`) records requests, tokens, cost, errors and latency through `MetricsCollector`; for span-level data, read `InMemoryTraceStore` and push to your Prometheus client, or implement a custom `TraceStore` that writes directly to a metrics registry
- **No automatic cost aggregation** - Langfuse provides this via its dashboard; for in-process aggregation, sum `cost_usd` attributes from `SpanKind.Rag` and `SpanKind.LlmCall` spans in `InMemoryTraceStore`
- **No real-time streaming metrics** - Streaming completions are traced on completion, not in-flight
- **Guardrail metrics require custom tracing** - Add `traceEvent` calls in guardrail implementations; the resulting spans are then queryable via `InMemoryTraceStore`

These are tracked in the [Production Readiness Roadmap](../../reference/roadmap.md).

---

## Related Documentation

- [In-Process Tracing Use Cases](enhanced-tracing-use-cases.md) - Full catalogue of `TraceCollectorTracing` + `InMemoryTraceStore` scenarios
- [Langfuse Workflow Patterns](../../langfuse-workflow-patterns.md) - Detailed trace event sequences
- [Configuration Guide](../../getting-started/configuration.md) - Complete configuration reference
- [Roadmap](../../reference/roadmap.md) - Planned observability improvements
