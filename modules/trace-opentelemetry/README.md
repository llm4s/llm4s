# llm4s-observability-otel

OpenTelemetry tracing backend for LLM4S. Exports spans via OTLP/gRPC to any OpenTelemetry
collector - Jaeger, Grafana Tempo, Datadog and similar APM backends.

## Quick Start

Add to your `build.sbt`:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-observability-otel" % "<version>"
```

The module registers itself for `opentelemetry` through a `META-INF/services` entry, so the
dependency is all it takes - select it with `TRACING_MODE`:

```bash
TRACING_MODE=opentelemetry  # alias: otel
OTEL_SERVICE_NAME=my-llm-service
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```

## Configuration

Read from the `llm4s.tracing.opentelemetry` block (see
[`OpenTelemetryConfig`](src/main/scala/org/llm4s/llmconnect/config/OpenTelemetryConfig.scala)):

| Key | Environment variable | Default |
|---|---|---|
| `serviceName` | `OTEL_SERVICE_NAME` | `"llm4s-agent"` - attached to every span as `service.name` |
| `endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` | `"http://localhost:4317"` |
| `headers` | not bound by environment (see below) | none |

`headers` sends additional HTTP headers with every OTLP export request - for example an
authentication token for a hosted collector. It is not read from `OTEL_EXPORTER_OTLP_HEADERS`;
bind one yourself in `application.conf` if you want a value from the environment:

```hocon
llm4s.tracing.opentelemetry.headers {
  Authorization = ${?OTEL_AUTH_HEADER}
}
```

With `TRACING_MODE=opentelemetry` (or `otel`), `llm4s-core` hands this block to
`OpenTelemetryTracingBackend` as `TracingSettings.extras`, which builds the config with
`OpenTelemetryConfig.fromExtras`.

See the [observability guide](../../docs/guide/observability/index.md#opentelemetry-integration)
for how this fits alongside the other tracing backends.
