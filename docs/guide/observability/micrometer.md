---
layout: page
title: Micrometer metrics
parent: Monitoring
grand_parent: User Guide
nav_order: 2
---

# Micrometer metrics
{: .no_toc }

Record LLM4S request, token, cost and error metrics in a Micrometer `MeterRegistry` you already have, so a Spring Boot,
Quarkus or plain JVM service shows them next to its own metrics.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## When to use it

Use `llm4s-observability-micrometer` when your application already ships metrics through Micrometer (to Prometheus,
Datadog, CloudWatch, OpenTelemetry or anything else a registry exports to) and you want the LLM metrics in the same
place. If you only want a Prometheus endpoint and have no Micrometer registry, use
[`llm4s-observability-prometheus`](index) instead.

The two modules are independent: neither depends on the other, and `llm4s-core` depends on neither. Core defines only
the `MetricsCollector` contract that every client accepts.

## Install

{: .note }
> Not yet published. The module exists in the build as of
> [#1466](https://github.com/llm4s/llm4s/issues/1466) and ships in the next release.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-observability-micrometer" % llm4sVersion
```

It depends on `llm4s-core` and `micrometer-core`. The Micrometer jars from 1.14 to 1.17 are class-file version 52, which any
JDK can load, so the module does not raise the JDK you need. The module is built against Micrometer 1.17 and uses only the stable instrument API
(`Counter`, `Timer`, `MeterRegistry`); your application's own Micrometer version is the one that runs.

## Quick start

Create a `MicrometerMetrics` over your registry and give it to the client:

```scala
import io.micrometer.core.instrument.MeterRegistry
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.metrics.MicrometerMetrics
import org.llm4s.model.ModelRegistryService

def clientWithMetrics(meterRegistry: MeterRegistry) =
  for {
    providerConfig  <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registryService
    metrics                    = new MicrometerMetrics(meterRegistry)
    client <- LLMConnect.getClient(providerConfig, metrics)
  } yield client
```

Every call through that client now records into `meterRegistry`. To read a value back, for example in a test:

```scala
val calls = meterRegistry.find("llm4s.requests").tag("status", "success").counter().count()
```

## What is recorded

| Meter | Type | Tags | Prometheus series |
|---|---|---|---|
| `llm4s.requests` | counter | `provider`, `model`, `status` | `llm4s_requests_total` |
| `llm4s.request.duration` | timer | `provider`, `model` | `llm4s_request_duration_seconds` |
| `llm4s.tokens` | counter | `provider`, `model`, `type` | `llm4s_tokens_total` |
| `llm4s.cost.usd` | counter | `provider`, `model` | `llm4s_cost_usd_total` |
| `llm4s.errors` | counter | `provider`, `error_type` | `llm4s_errors_total` |
| `llm4s.retries` | counter | `provider` | `llm4s_retries_total` |
| `llm4s.circuit.breaker.transitions` | counter | `provider`, `state` | `llm4s_circuit_breaker_transitions_total` |
| `llm4s.image.generations` | counter | `provider`, `model`, `operation`, `status` | `llm4s_image_generations_total` |
| `llm4s.images.generated` | counter | `provider`, `model` | `llm4s_images_generated_total` |
| `llm4s.image.generation.duration` | timer | `provider`, `model`, `operation` | `llm4s_image_generation_duration_seconds` |
| `llm4s.image.generation.cost.usd` | counter | `provider`, `model` | `llm4s_image_generation_cost_usd_total` |
| `llm4s.image.generation.errors` | counter | `provider`, `model`, `operation`, `error_type` | `llm4s_image_generation_errors_total` |

The last column is what a Micrometer `PrometheusMeterRegistry` exposes for the meter. Tag values:

- `status` is `success` or `error_<kind>`, and `error_type` is `<kind>`, where `<kind>` is one of `rate_limit`, `timeout`,
  `authentication`, `network`, `validation`, `service_error`, `execution_error`, `cancelled` or `unknown`.
- `type` is `input` or `output`.
- `operation` is `generate`, `edit` or `other`.
- `state` is `open`, `closed`, `half-open` or `other`.

The request timer is published with the same bucket boundaries as the Prometheus module (0.1, 0.5, 1, 2, 5, 10, 30, 60
and 120 seconds, and 1, 2, 5, 10, 30, 60, 120 and 300 seconds for image generation), so `_bucket` series line up.

## Dashboards built for the Prometheus module

If you exported `llm4s_*` series with `llm4s-observability-prometheus` and switch to this module with a Micrometer
`PrometheusMeterRegistry`, the same queries keep working. This is tested: `MicrometerPrometheusSeriesSpec` sends identical
calls to both collectors and compares the series they expose (names, label names, bucket boundaries and values).

Two differences:

- This module also records retries (`llm4s_retries_total`) and circuit-breaker transitions
  (`llm4s_circuit_breaker_transitions_total`), and counts `recordError` calls, which the Prometheus collector leaves as
  no-ops.
- A Micrometer timer also exposes a `_max` series. The Prometheus module has no equivalent.

## Tag cardinality

Tags are provider, model and values from a fixed set, so the number of meters stays small. Prompts, user ids and request
ids are never tags. Two guards apply to values the caller supplies:

- A null or blank provider or model is recorded as `unknown` (Micrometer rejects null tag values).
- An image operation or circuit-breaker state outside the sets above is recorded as `other`, so a stray value cannot create
  a new meter. A test sends a thousand distinct values and checks the meter count does not grow.

`provider` and `model` come from your configuration. If model names could be arbitrary strings in your setup, bound them
with Micrometer's own `MeterFilter.maximumAllowableTags`.

## Failures never reach the caller

Like every `MetricsCollector`, no method throws. If the registry rejects a meter, for example because a `MeterFilter` throws,
the problem is logged at WARN and the metric is dropped. Negative or non-finite amounts for tokens and cost are ignored
instead of corrupting a counter.

## Names and the OpenTelemetry GenAI conventions

The OpenTelemetry GenAI semantic conventions define metric names such as `gen_ai.client.operation.duration`. They are
still in Development status, and they are changing: the token metric `gen_ai.client.token.usage` has since been replaced
by `gen_ai.client.inference.usage.input_tokens` and `gen_ai.client.inference.usage.output_tokens`, and the conventions
have moved to their own repository, `open-telemetry/semantic-conventions-genai`. This module therefore keeps the stable
`llm4s.*` names above, which match the existing Prometheus series, and does not freeze names that are still moving. The
concepts map as follows, and a Micrometer `MeterFilter` can rename a meter if you need the OpenTelemetry names today:

| llm4s | OpenTelemetry GenAI |
|---|---|
| `provider` tag | `gen_ai.provider.name` |
| `model` tag | `gen_ai.request.model` |
| `llm4s.request.duration` | `gen_ai.client.operation.duration` |
| `llm4s.tokens` with `type=input` / `type=output` | `gen_ai.client.inference.usage.input_tokens` / `.output_tokens` |
| `error_type` tag | `error.type` |

## Spring Boot

The Spring Boot starter does not create this collector yet. The starter builds its client with `Llm4s.createClient`
from `llm4s-java-api`, which has no parameter for a `MetricsCollector`, and `JLlmClient`'s constructor is package-private,
so there is nothing for the starter to attach a collector to. Adding one is a new method on a published module's API and is
tracked in [#1466](https://github.com/llm4s/llm4s/issues/1466).

Until then, a Spring application written in Scala can create the collector over the injected registry and build the client
itself, as in the quick start:

```scala
val metrics = new MicrometerMetrics(meterRegistry) // meterRegistry is the Actuator's MeterRegistry bean
```
