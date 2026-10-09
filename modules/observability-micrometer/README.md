# llm4s-observability-micrometer

A `MetricsCollector` that records LLM4S request, token, cost, retry and error metrics in a Micrometer `MeterRegistry`.
Use it when your application already has a registry (Spring Boot's, Quarkus's, or your own) and you want the LLM metrics
next to the rest.

**Tier:** Beta. **Depends on:** `llm4s-core` and `micrometer-core`. It does not depend on `llm4s-observability-prometheus`.

## Install

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-observability-micrometer" % llm4sVersion
```

Not yet published: it ships in the next release.

## Use

```scala
val metrics = new MicrometerMetrics(meterRegistry)
val client  = LLMConnect.getClient(providerConfig, metrics) // needs a ModelRegistryService in scope
```

The meters, their tags and how they relate to the Prometheus module's series are in the
[Micrometer metrics guide](../../docs/guide/observability/micrometer.md).

## What it provides

- `MicrometerMetrics(registry)`, a `MetricsCollector` implementing all eight contract methods.
- `MicrometerMetrics.Names`, the meter names as constants.

The meter names mirror the series of `llm4s-observability-prometheus` (`llm4s.requests` is exported as
`llm4s_requests_total`), including the duration buckets, so dashboards carry over. Tag values come from fixed sets (plus
provider and model), no method throws, and negative or non-finite amounts are ignored.

## Not included

- **Spring Boot wiring.** The starter builds its client through `llm4s-java-api`, which has no parameter for a
  `MetricsCollector`. See the guide for the limitation and the interim approach.
- **OpenTelemetry GenAI metric names.** Those conventions are still in Development status and are being renamed; the guide
  explains the mapping.
- **Tracing.** Spans are the job of `llm4s-observability-otel`.

## Tests

```bash
sbt observabilityMicrometer/test
```

`MicrometerMetricsSpec` checks each method against a `SimpleMeterRegistry`. `MicrometerPrometheusSeriesSpec` sends the same
calls to `PrometheusMetrics` and to this collector behind a `PrometheusMeterRegistry`, and compares the series they expose.

## See also

- [`llm4s-observability-prometheus`](../observability-prometheus), the Prometheus client and `/metrics` endpoint
- [`llm4s-observability`](../observability), tracing backends and the cost tracker
