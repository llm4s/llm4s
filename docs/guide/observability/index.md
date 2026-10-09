---
layout: page
title: Monitoring
nav_order: 21
parent: User Guide
has_children: true
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

LLM4S logs through the SLF4J API and does not bring a logging backend: add one to your application, such as `libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.34"`. Without one, SLF4J logs nothing and prints a single warning. Configure the backend for structured JSON output in production environments.

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

### Capturing Provider Requests and Responses

To see the exact request body a client sent and the exact body that came back, opt in to
[Provider Exchange Logging](provider-exchange-logging): each completed call is handed to a sink, and a
ready-made sink appends JSON Lines to a file. It is off by default, and what it writes contains your
prompts and the model's answers, so read its privacy notes before enabling it.

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

// give the tracer to the agent: every run it makes is traced
val result = for {
  agent  <- Agent.builder("assistant", client).withTools(tools).withTracing(tracer).build()
  result <- agent.run("query")
} yield result

// retrieve all spans; an agent's runs are recorded as `graph.*` custom events,
// spans named `custom:graph.run_started`, `custom:graph.task_completed`, ...
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

  // built once per spec, with a scripted client and the tracer
  def agent = Agent.builder("assistant", client).withTools(tools).withTracing(tracer).build()
    .getOrElse(fail("agent build failed"))

  /** One span per graph task the agent's `call-tool` node completed: one per tool call. */
  def toolCalls = store.getSpans(tracer.traceId).filter { s =>
    s.name == "custom:graph.task_completed" &&
    s.attributes.get("nodeId").flatMap(_.asString).exists(_.endsWith("/call-tool"))
  }

  "agent" should "call a tool exactly once" in {
    agent.run("what is 6 * 7?") shouldBe a[Right[_, _]]

    toolCalls should have size 1
  }

  "agent" should "record no failed task on a valid query" in {
    agent.run("hello") shouldBe a[Right[_, _]]

    store.getSpans(tracer.traceId).map(_.name) should not contain "custom:graph.task_failed"
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
val agent  = Agent.builder("assistant", client).withTools(tools).withTracing(tracer).build()
agent.flatMap(_.run("query"))

// local span queries still work
val graphSpans = store.getSpans(collector.traceId).filter(_.name.startsWith("custom:graph."))
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

### What an agent run sends

An agent built with `Agent.builder(...).withTracing(tracing)` sends, for each run:

- `graph.*` custom events for the runtime's own events (run, task and checkpoint lifecycle), and
  `agent.*` custom events for the agent's durable events (`agent.model_call_completed`,
  `agent.tool_executed`, `agent.handed_off`, `agent.guardrail_blocked`), which carry no message content;
- a `TokenUsageRecorded` for each model call that reports usage;
- one `TraceEvent.AgentRunEnded` when the run ends: thread id, run id, the active agent, a status
  (`completed`, `suspended`, `step_limit_reached`, `blocked:<guardrail>`, `cancelled`, `timed_out`,
  `failed`), this turn's messages (from its user message on; empty when blocked, cancelled, timed
  out or failed) and the run's own `UsageSummary` - the model calls this run made, summed from its
  `agent.model_call_completed` events, not the thread's total, so per-run figures add up.

A run that crashes without a terminal event is traced as `ErrorOccurred`, with a WARN, and has no
`AgentRunEnded`; it is traced as soon as the run's last event has been, with no fixed delay.
Message content reaches tracing only in `AgentRunEnded.messages`; a blocked turn's content is not in
it. (The kernel's own `TracingSubscriber`, used for graphs that are not agents,
names agent events `graph.custom`; `withTracing` on an agent names them `agent.*`.)

| Backend | What `AgentRunEnded` shows |
|---------|----------------------------|
| Langfuse | One trace per run (trace id = run id), grouped in a session per thread (session id = thread id); input is the first user message, output the last assistant message; metadata holds the agent, status and usage; one span per message |
| OpenTelemetry | An `INTERNAL` span "Agent Run" with thread, run, agent, status, message count and `gen_ai.usage.*` totals |
| `TraceCollector` | A `SpanKind.AgentCall` span with the same attributes, token totals as `input_tokens`/`output_tokens`, and no cost |
| Console | A multi-line "Agent Run Ended" block: agent, status, thread, run, message count, token totals |

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
import scala.concurrent.duration.*

// Trace custom events
tracing.traceEvent(TraceEvent.CustomEvent("cache_hit", ujson.Obj("key" -> "query_123")))

// Trace token usage explicitly
tracing.traceTokenUsage(usage, model = "gpt-4o", operation = "completion")

// Trace a finished agent run (an agent built withTracing does this itself, once per run)
tracing.traceEvent(TraceEvent.AgentRunEnded(threadId, runId, agent, status, messages, usage))

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
  duration = 150.millis,
  embeddingTokens = Some(128),
  llmPromptTokens = None,
  llmCompletionTokens = None,
  totalCostUsd = Some(0.0001)
)
```

---

## Metrics

Tracing records what happened inside one request. Metrics count what happens across all of them: requests, tokens, cost, errors and latency, per provider and model. They are cheap enough to alert on, and they answer "is a provider failing right now?" faster than a trace search.

The contract is `MetricsCollector` in `llm4s-core` (`@Stable`): `observeRequest`, `addTokens` and `recordCost`, plus calls for retries, circuit-breaker transitions, errors and image generation. Every chat client calls the collector it was built with, and collectors are expected not to throw. The default is `MetricsCollector.noop`, so metrics cost nothing until you pass one in. `llm4s-observability-prometheus` provides the implementation, `PrometheusMetrics`, and the HTTP endpoint that serves it, `PrometheusEndpoint`.

### Add the module

```scala
libraryDependencies += "org.llm4s" %% "llm4s-observability-prometheus" % llm4sVersion
```

On `0.4.1` and earlier this code ships inside `llm4s-core`; the [migration note](../../reference/migration.md#slice-6-llm4s-observability-prometheus---prometheus-leaves-core) has the details. Package names are unchanged.

### Turn it on from configuration

Metrics are off unless asked for, because turning them on starts an HTTP server. To turn them on:

```hocon
llm4s {
  metrics {
    enabled = true
    prometheus {
      enabled = true
      port    = 9090
    }
  }
}
```

The module's `reference.conf` ships `enabled = false`, `prometheus.enabled = true` and `port = 9090`, so setting only `enabled = true` is enough: the Prometheus backend is used on port `9090`. `MetricsConfigLoader.default()` reads the block and returns `Result[(MetricsCollector, Option[PrometheusEndpoint])]`:

| `llm4s.metrics` | Collector | Endpoint |
|---|---|---|
| `enabled = false` (the default) | `MetricsCollector.noop` | none, no server is started |
| `enabled = true`, `prometheus.enabled = false` | `MetricsCollector.noop` | none |
| `enabled = true` | `PrometheusMetrics` | started on `prometheus.port`; `Left(ConfigurationError)` if the port is taken |

Pass the collector to the client when you build it:

```scala
import org.llm4s.config.{Llm4sConfig, MetricsConfigLoader}
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.model.ModelRegistryService

val application = for {
  providerConfig  <- Llm4sConfig.defaultProvider()
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  configured <- MetricsConfigLoader.default()
  (metrics, endpoint) = configured
  client <- LLMConnect.getClient(providerConfig, metrics).left.map { error =>
    endpoint.foreach(_.stop()) // release the server if client creation fails
    error
  }
} yield (client, endpoint)
```

On success, retain the returned endpoint alongside the client and call `endpoint.foreach(_.stop())` during application shutdown. The collector and endpoint from the loader share the same registry.

`LlmClientOptions(metrics = ...)` is the same thing through the options overload of `LLMConnect.getClient`. The endpoint that `MetricsConfigLoader` starts stays up until you call `PrometheusEndpoint.stop()`, which is safe to call twice.

### Or build the pieces yourself

If you already own a `PrometheusRegistry`, create the collector on it and start the endpoint on the same registry:

```scala
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.llm4s.metrics.{ PrometheusEndpoint, PrometheusMetrics }

val registry = new PrometheusRegistry()
val metrics  = new PrometheusMetrics(registry)

val endpoint: Option[PrometheusEndpoint] =
  PrometheusEndpoint.start(9090, registry) match { // Result[PrometheusEndpoint]; serves /metrics
    case Right(ep) => Some(ep)
    case Left(error) =>
      // the port could not be bound: report this as a startup failure rather than
      // running with metrics configured but nothing to scrape
      None
  }
// retain the handle: endpoint.foreach(_.stop()) at application shutdown (safe to call twice)
```

### What a call records

- **Every call**, successful or not, records one request with its latency and its outcome. A failure is classified into an error kind (`rate_limit`, `timeout`, `authentication`, `network`, `validation`, `service_error`, `execution_error`, `cancelled` or `unknown`) from the error the client returned.
- **Tokens and cost are recorded only on success**, and only when the completion carries them: tokens when the provider reported usage, cost when the client attached an estimated cost. A call with neither adds nothing to those series.
- **A streamed call is recorded once, when the stream has finished**, with the total latency, not as it goes.
- **Embedding calls are not recorded, and no RAG- or reranking-specific series exists.** The chat calls that RAG and the LLM reranker make through an instrumented client are recorded as ordinary requests - `RAG`'s answer generation and `LLMReranker`'s scoring both go through the client's `complete` - so that traffic does show up in the request series under the provider and model. The Cohere reranker calls Cohere's API directly, not through an LLM client, and is not recorded. Image generation is recorded through `InstrumentedImageGenerationClient`, which wraps an image client.

### The series

| Series | Type | Labels |
|---|---|---|
| `llm4s_requests_total` | counter | `provider`, `model`, `status` (`success` or `error_<kind>`) |
| `llm4s_tokens_total` | counter | `provider`, `model`, `type` (`input` or `output`) |
| `llm4s_cost_usd_total` | counter | `provider`, `model` |
| `llm4s_errors_total` | counter | `provider`, `error_type` |
| `llm4s_request_duration_seconds` | histogram | `provider`, `model`; buckets 0.1, 0.5, 1, 2, 5, 10, 30, 60 and 120 seconds |
| `llm4s_image_generations_total` | counter | `provider`, `model`, `operation`, `status` |
| `llm4s_images_generated_total` | counter | `provider`, `model` |
| `llm4s_image_generation_duration_seconds` | histogram | `provider`, `model`, `operation` |
| `llm4s_image_generation_cost_usd_total` | counter | `provider`, `model` |
| `llm4s_image_generation_errors_total` | counter | `provider`, `model`, `operation`, `error_type` |

After one successful call and one rate-limited call to `gpt-4o`, the chat series on `/metrics` look like this (comment lines left out; the second call took 90 seconds):

```text
llm4s_cost_usd_total{model="gpt-4o",provider="openai"} 0.0021
llm4s_errors_total{error_type="rate_limit",provider="openai"} 1.0
llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="0.1"} 0
llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="0.5"} 0
llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="1.0"} 0
llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="2.0"} 1
llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="5.0"} 1
llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="10.0"} 1
llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="30.0"} 1
llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="60.0"} 1
llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="120.0"} 2
llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="+Inf"} 2
llm4s_request_duration_seconds_count{model="gpt-4o",provider="openai"} 2
llm4s_request_duration_seconds_sum{model="gpt-4o",provider="openai"} 91.5
llm4s_requests_total{model="gpt-4o",provider="openai",status="error_rate_limit"} 1.0
llm4s_requests_total{model="gpt-4o",provider="openai",status="success"} 1.0
llm4s_tokens_total{model="gpt-4o",provider="openai",type="input"} 120.0
llm4s_tokens_total{model="gpt-4o",provider="openai",type="output"} 30.0
```

### Scrape it and alert on it

Point Prometheus at the endpoint:

```yaml
scrape_configs:
  - job_name: 'llm4s'
    static_configs:
      - targets: ['localhost:9090']
```

Queries to start from (standard PromQL over the series above):

```promql
# Requests per second, by provider
sum by (provider) (rate(llm4s_requests_total[5m]))

# Share of requests that failed
sum(rate(llm4s_requests_total{status!="success"}[5m])) / sum(rate(llm4s_requests_total[5m]))

# 95th percentile latency, by provider
histogram_quantile(0.95, sum by (le, provider) (rate(llm4s_request_duration_seconds_bucket[5m])))

# Rate-limit errors per second
sum(rate(llm4s_errors_total{error_type="rate_limit"}[5m]))

# Estimated spend over the last hour, by model
sum by (model) (increase(llm4s_cost_usd_total[1h]))
```

### Send metrics to more than one place

`MetricsCollector.compose` forwards every `MetricsCollector` method, the two image-generation ones (`observeImageGeneration` and `recordImageGenerationCost`) included, to each collector it is given, so Prometheus can run next to `CostTracker` (in `llm4s-observability`) or a collector of your own, and a composed collector handed to `InstrumentedImageGenerationClient` records image metrics in every child. A collector that throws does not stop the others:

```scala
import org.llm4s.metrics.MetricsCollector

val both = MetricsCollector.compose(metrics, myCollector)
// LLMConnect.getClient(providerConfig, both)
```

### Limits

- **Retries, circuit-breaker transitions and `ReliableClient`'s error events have no series of their own.** `ReliableClient` takes its own `collector` argument and reports those three kinds of event to it, but `PrometheusMetrics` does not implement them. The request series still see every attempt: `ReliableClient` calls the wrapped client once per attempt, and a metrics-enabled client records each of those calls, so a call that fails twice and then succeeds adds three requests - two `error_*`, one `success` - and three latency observations. Request and error rates are per attempt, not per logical call.
- **The latency buckets are fixed** (0.1 to 120 seconds); there is no way to configure them.
- **Counters live in the process.** A restart starts them from zero, which is what `rate()` and `increase()` expect.
- **The endpoint has no authentication and no TLS**: llm4s starts it with only a port and a registry. Keep it off the public network and let your network policy decide who can scrape it.
- **Labels are provider and model.** The number of series grows with the number of distinct models you call, not with traffic.

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
    logger.warn(s"Rate limited: ${rle.message}, retry after: ${rle.retryAfter.fold("unknown")(_.toString)}")
    
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
import org.llm4s.agent.{Agent, ContextWindowConfig}
import org.llm4s.agent.graph.middleware.ContextWindowMiddleware
import org.llm4s.types.HeadroomPercent

// Get available tokens considering model limits and safety margin
val budget = client.getContextBudget(HeadroomPercent.Standard)
val config = ContextWindowConfig(maxTokens = Some(budget))

// ContextWindowMiddleware uses a default token counter (words * 1.3)
// or accepts a custom tokenCounter function for more accurate estimation
val agent = Agent.builder("assistant", client)
  .withMiddleware(new ContextWindowMiddleware(config))
  .build()
```

---

## Production Checklist

Before deploying, verify monitoring coverage:

### Tracing

- [ ] `TRACING_MODE` set to `langfuse` or `opentelemetry`
- [ ] Tracing credentials configured and tested
- [ ] Traces visible in dashboard (test with sample request)

### Metrics

- [ ] `llm4s.metrics.enabled = true`, and Prometheus scrapes the `/metrics` endpoint
- [ ] The endpoint is reachable only from your monitoring network
- [ ] Error-ratio and latency alerts use `llm4s_requests_total` and `llm4s_request_duration_seconds`

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

- **Prometheus metrics cover LLM and image-generation calls, not traces** (see [Metrics](#metrics)) - `PrometheusMetrics` (`llm4s-observability-prometheus`) records requests, tokens, cost, errors and latency through `MetricsCollector`; for span-level data, read `InMemoryTraceStore` and push to your Prometheus client, or implement a custom `TraceStore` that writes directly to a metrics registry
- **No automatic cost aggregation** - Langfuse provides this via its dashboard; for in-process aggregation, sum `cost_usd` attributes from `SpanKind.Rag` and `SpanKind.LlmCall` spans in `InMemoryTraceStore`
- **No real-time streaming metrics** - Streaming completions are traced on completion, not in-flight
- **Guardrail metrics require custom tracing** - Add `traceEvent` calls in guardrail implementations; the resulting spans are then queryable via `InMemoryTraceStore`

These are tracked in the [Production Readiness Roadmap](../../reference/roadmap.md).

---

## Related Documentation

- [Provider Exchange Logging](provider-exchange-logging) - Capture raw provider requests and responses for debugging
- [In-Process Tracing Use Cases](enhanced-tracing-use-cases.md) - Full catalogue of `TraceCollectorTracing` + `InMemoryTraceStore` scenarios
- [Langfuse Workflow Patterns](../../langfuse-workflow-patterns.md) - Detailed trace event sequences
- [Configuration Guide](../../getting-started/configuration.md) - Complete configuration reference
- [Roadmap](../../reference/roadmap.md) - Planned observability improvements
