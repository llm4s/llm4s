# llm4s-observability-prometheus

Prometheus metrics backend for LLM4S. Records requests, tokens, cost, errors and latency for LLM
and image-generation calls through `MetricsCollector`, and serves them on a `/metrics` HTTP
endpoint for Prometheus to scrape.

This covers metrics, not traces - for span-level tracing, see
[`llm4s-observability`](../observability/README.md) (Langfuse) or
[`llm4s-observability-otel`](../trace-opentelemetry/README.md) (OpenTelemetry).

## Quick Start

Add to your `build.sbt`:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-observability-prometheus" % "<version>"
```

Enable it:

```hocon
llm4s {
  metrics {
    enabled = true
    prometheus {
      enabled = true
      port = 9090
    }
  }
}
```

```scala
import org.llm4s.config.MetricsConfigLoader

for
  (collector, endpointOpt) <- MetricsConfigLoader.default()
yield
  // pass `collector` to the code that makes LLM calls; it is a no-op collector
  // if metrics are disabled, so this is safe to wire up unconditionally
  ()
```

When enabled, loading starts the HTTP endpoint immediately; stop it with `endpoint.stop()`. Other
code should receive the `MetricsCollector` by dependency injection rather than read these keys
itself.

## Configuration

Read from the `llm4s.metrics` block (see
[`MetricsConfigLoader`](src/main/scala/org/llm4s/config/MetricsConfigLoader.scala)):

| Key | Default |
|---|---|
| `llm4s.metrics.enabled` | `false` - metrics collection is off unless asked for, since enabling it starts an HTTP server |
| `llm4s.metrics.prometheus.enabled` | `true` - only consulted when `llm4s.metrics.enabled = true` |
| `llm4s.metrics.prometheus.port` | `9090` - port for the `/metrics` endpoint Prometheus scrapes |

See the [observability guide](../../docs/guide/observability/index.md) for how Prometheus metrics
relate to tracing, and its current limitations.
