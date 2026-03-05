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

| Mode | Use Case | Configuration |
|------|----------|---------------|
| `langfuse` | Production LLM observability | `TRACING_MODE=langfuse` |
| `opentelemetry` | Integration with existing APM | `TRACING_MODE=opentelemetry` |
| `console` | Local development/debugging | `TRACING_MODE=console` |
| `noop` | Disabled | `TRACING_MODE=noop` |
| `collector` | In-process queryable store | Programmatic (see below) |

All backends implement the `Tracing` trait and can be composed with `TracingComposer.combine()`.

### Configuration

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

For teams with existing APM infrastructure (Jaeger, Grafana Tempo, Datadog), the `opentelemetry` mode exports traces via OTLP:

```bash
TRACING_MODE=opentelemetry
OTEL_SERVICE_NAME=my-llm-service
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```

This requires the `llm4s-trace-opentelemetry` module:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-trace-opentelemetry" % llm4sVersion
```

---

## In-Process Trace Collection

`TraceCollectorTracing` + `InMemoryTraceStore` provide a fully queryable trace store
that runs entirely within the JVM — no external service required. Recorded spans can be
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
val langfuse  = LangfuseTracing(langfuseConfig)

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

```bash
TRACING_MODE=langfuse
LANGFUSE_PUBLIC_KEY=<your-langfuse-public-key>
LANGFUSE_SECRET_KEY=<your-langfuse-secret-key>
LANGFUSE_URL=https://cloud.langfuse.com  # or self-hosted URL
```

### Tracing API

LLM4S exposes a `Tracing` trait for custom instrumentation:

```scala
import org.llm4s.trace.{Tracing, TraceEvent}

// Trace custom events
tracing.traceEvent(TraceEvent.CustomEvent("cache_hit", ujson.Obj("key" -> "query_123")))

// Trace token usage explicitly
tracing.traceTokenUsage(usage, model = "gpt-4o", operation = "completion")

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

- **No built-in Prometheus metrics** - Use `InMemoryTraceStore` to read span data and push counters/histograms to your Prometheus client, or implement a custom `TraceStore` that writes directly to a metrics registry
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
