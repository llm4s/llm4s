# Enhanced Tracing: New Use Cases

This document lists the concrete use cases unlocked by the `add-enhanced-tracing` implementation
(`TraceCollectorTracing`, `InMemoryTraceStore`, `TraceStore`, `TraceModel`). All of these were
impossible before — the previous tracing infrastructure (`ConsoleTracing`, `LangfuseTracing`,
`NoOpTracing`) was write-only: events were emitted outward with no return path.

## Background

The existing `Tracing` trait and its implementations are pure fire-and-forget sinks. Once a
`TraceEvent` is emitted, the data is gone from the process. There was no way to read back, query,
or act on trace data within the JVM.

The new `TraceCollectorTracing` + `TraceStore` layer adds structured, queryable, in-process
trace storage as a standard `Tracing` implementation — composable with any existing backend via
`TracingComposer.combine()`.

---

## Use Cases

### 1. In-Process Trace Retrieval by ID

`InMemoryTraceStore.getTrace(traceId)` and `getSpans(traceId)` return structured data after
agent execution completes.

```scala
val store = InMemoryTraceStore()
val spans: Result[List[Span]] = for {
  tracer <- TraceCollectorTracing(store)
  _      <- agent.run("query", tools, tracing = tracer)
} yield store.getSpans(tracer.traceId)
```

### 2. Deterministic Agent Testing

Wire `TraceCollectorTracing(InMemoryTraceStore())` in tests and assert directly on recorded
spans — no mocking of external systems, no console output parsing.

```scala
val store  = InMemoryTraceStore()
val tracer = TraceCollectorTracing(store).getOrElse(fail("tracing init failed"))
agent.run("query", tools, tracing = tracer)

val toolSpans = store.getSpans(tracer.traceId).filter(_.kind == SpanKind.ToolCall)
toolSpans should have size 2
toolSpans.head.attributes("tool_name").asString shouldBe Some("calculator")
```

### 3. Time-Range Queries

Retrieve all traces recorded within a time window.

```scala
val query = TraceQuery.withTimeRange(Instant.now.minusSeconds(3600), Instant.now)
val page  = store.queryTraces(query)
```

### 4. Status-Based Filtering

Surface failed traces across all runs without touching external systems.

```scala
val query  = TraceQuery.withStatus(SpanStatus.Error(""))
val errors = store.queryTraces(query).traces
```

### 5. Metadata-Based Trace Lookup (Experiment Grouping)

Tag traces at creation with arbitrary key-value metadata, then retrieve them by tag. Enables
A/B experiment grouping entirely within the process.

```scala
val trace   = Trace.start(Map("experiment" -> "v2", "user_id" -> userId))
// ...
val results = store.searchByMetadata("experiment", "v2")  // List[TraceId]
```

### 6. Latency Measurement Per Span Kind

Every `Span` carries `startTime` and `endTime`. Filter by `SpanKind` and compute durations.

```scala
store.getSpans(traceId)
  .filter(_.kind == SpanKind.ToolCall)
  .flatMap(s => s.endTime.map(e => e.toEpochMilli - s.startTime.toEpochMilli))
  .sum  // total milliseconds spent in tool calls
```

### 7. Structured Error Attribution

`SpanStatus.Error(message)` makes failed spans first-class queryable objects, with their error
message, timing, and all typed attributes.

```scala
store.getSpans(traceId)
  .collect { case s if s.status.isInstanceOf[SpanStatus.Error] => s }
  .map(s => s.attributes("error_type").asString)
```

### 8. Cache Hit Rate Measurement

`Cache` spans carry `hit: Boolean`, `similarity`, and `threshold`. Compute cache hit rate across
a session. Directly supports the semantic caching feature.

```scala
val cacheSpans = store.getSpans(traceId).filter(_.kind == SpanKind.Cache)
val hitRate    = cacheSpans.count(
  _.attributes.get("hit").flatMap(_.asBoolean).contains(true)
).toDouble / cacheSpans.size
```

### 9. Per-Kind Cost and Token Breakdowns

`LlmCall` spans carry `prompt_tokens`, `completion_tokens`; `Embedding` spans carry
`input_count`; `Rag` spans carry `total_cost_usd`. Sum them per operation type.

```scala
val totalPromptTokens = store.getSpans(traceId)
  .filter(_.kind == SpanKind.LlmCall)
  .flatMap(_.attributes.get("prompt_tokens").flatMap(_.asLong))
  .sum
```

### 10. Pluggable Storage Backend

`TraceStore` is a trait. Implement it backed by PostgreSQL, Redis, DynamoDB, or any store
without modifying any tracing code. `InMemoryTraceStore` is the reference implementation.

```scala
class PostgresTraceStore(ds: DataSource) extends TraceStore {
  override def saveTrace(trace: Trace): Unit = // ...
  override def queryTraces(query: TraceQuery): TracePage = // ...
  // ...
}
```

### 11. Span and Trace JSON Round-Trip

`TraceModelJson` provides both serialization and deserialization for the first time. Enables
writing spans to disk and reloading them, or forwarding structured span data to a custom HTTP
backend.

```scala
val json   = span.toJson                      // ujson.Value
val parsed = TraceModelJson.parseSpan(json)   // Result[Span]

// Handle the result
parsed match {
  case Right(s)    => println(s.name)
  case Left(error) => println(s"Parse error: ${error.field} — ${error.reason}")
}
```

### 12. Trace Deletion and Lifecycle Management

`store.deleteTrace(traceId)` removes a trace and all its spans atomically. `store.clear()` wipes
everything. Enables test fixture cleanup between cases and on-demand removal of sensitive traces.

```scala
override def afterEach(): Unit = store.clear()
```

### 13. Dataset Construction for LLM Evaluation

Query `LlmCall` spans filtered by metadata tag to collect structured `(input, output, model,
token count)` records. This is a direct prerequisite for the datasets and evaluators planned in
later phases of the Observability & Evaluation initiative.

```scala
store.searchByMetadata("dataset", "eval-set-v1")
  .flatMap(traceId => store.getSpans(traceId).filter(_.kind == SpanKind.LlmCall))
  .map(s => EvalRecord(
    model          = s.attributes("model").asString.get,
    completionId   = s.attributes("completion_id").asString.get,
    promptTokens   = s.attributes("prompt_tokens").asLong.get
  ))
```

### 14. Sub-Span Event Timeline

`Span.events: List[SpanEvent]` allows point-in-time annotations within a single span, each with
a timestamp and typed attributes — without creating child spans.

```scala
span
  .withEvent(SpanEvent("cache-lookup-started"))
  .withEvent(SpanEvent("chunks-retrieved", Map("count" -> SpanValue.LongValue(5L))))
```

---


