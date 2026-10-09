# llm4s-observability

Tracing backends for LLM4S: Langfuse, and an in-process trace collector (`TraceCollectorTracing`,
`InMemoryTraceStore`) that needs no external service and is handy for testing agents, plus
`CostTracker` (`org.llm4s.metrics`).

The Langfuse backend sends spans to [Langfuse](https://langfuse.com) (cloud or self-hosted) for
production LLM observability: prompts, completions, tool calls and cost.

## Quick Start

Add to your `build.sbt`:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-observability" % "<version>"
```

Select the backend and provide your Langfuse credentials:

```bash
TRACING_MODE=langfuse
LANGFUSE_PUBLIC_KEY=<your-langfuse-public-key>
LANGFUSE_SECRET_KEY=<your-langfuse-secret-key>
```

`publicKey` and `secretKey` are required; the backend refuses to start without them. Everything
else below has a default.

## Configuration

Read from the `llm4s.tracing.langfuse` block (see
[`LangfuseConfig`](src/main/scala/org/llm4s/llmconnect/config/LangfuseConfig.scala) and
[`LangfuseConfigKeys`](src/main/scala/org/llm4s/config/LangfuseConfigKeys.scala)):

| Key | Environment variable | Default |
|---|---|---|
| `url` | `LANGFUSE_URL` | `https://cloud.langfuse.com/api/public/ingestion` (Langfuse Cloud) |
| `publicKey` | `LANGFUSE_PUBLIC_KEY` | none - required |
| `secretKey` | `LANGFUSE_SECRET_KEY` | none - required |
| `env` | `LANGFUSE_ENV` | `"production"` |
| `release` | `LANGFUSE_RELEASE` | `"1.0.0"` |
| `version` | `LANGFUSE_VERSION` | `"1.0.0"` |

For a self-hosted instance, set `LANGFUSE_URL` to its ingestion endpoint.

With `TRACING_MODE=langfuse`, `llm4s-core` hands this block to `LangfuseTracingBackend`
automatically. To read it independently of `TRACING_MODE` - for example to combine Langfuse with
another backend via `TracingComposer.combine` - use
[`LangfuseConfigLoader`](src/main/scala/org/llm4s/config/LangfuseConfigLoader.scala):

```scala
import org.llm4s.config.LangfuseConfigLoader
import org.llm4s.trace.{ ConsoleTracing, LangfuseTracing, TracingComposer }

for
  langfuse <- LangfuseConfigLoader.default()
yield TracingComposer.combine(LangfuseTracing.from(langfuse), new ConsoleTracing())
```

See the [observability guide](../../docs/guide/observability/index.md#langfuse-monitoring-workflow)
for the full Langfuse monitoring workflow, including what gets captured and a trace structure
example.

## In-process trace collector

`TraceCollectorTracing` records spans into a `TraceStore` inside the JVM - `InMemoryTraceStore`
ships with the module - where they can be queried, filtered and serialized to JSON. It needs no
external service and no configuration, which makes it the backend for unit-testing agents and
for in-process analytics. Build one with `TraceCollectorTracing(InMemoryTraceStore())`, which
returns a `Result`.

See [In-Process Trace Collection](../../docs/guide/observability/index.md#in-process-trace-collection)
in the observability guide for querying, span analytics and deterministic agent testing.
