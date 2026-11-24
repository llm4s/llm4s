---
layout: page
title: Error Recovery
description: "Recover from LLM failures with LLM4S: ReliableClient retries, circuit breaker and deadline, falling back between providers, and recovering agent runs."
search_title: "LLM error recovery: retries, fallback, circuit breaker"
parent: Real-World Application Patterns
grand_parent: Operations
nav_order: 5
---

# Error Recovery
{: .no_toc }

Providers time out, rate-limit and go down. LLM4S ships the retry, circuit breaker, deadline and rate limit as one
wrapper, `ReliableClient`, so an application only decides the policy and what to do when a call still fails.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

---

## What fails, and what to do

| Failure | Shipped answer |
|---|---|
| A timeout, a 429 or a 5xx | Retry with backoff: `ReliableClient` with a `RetryPolicy`. A rate limit's `Retry-After` is honoured. |
| A provider that keeps failing | Fail fast: the circuit breaker in `ReliableClient` (`CircuitBreakerConfig`), then fall back |
| A call that must not wait forever | `ReliabilityConfig.withDeadline`, which bounds a call including its retries |
| Bursts that exceed your quota | A local token bucket: `RateLimitConfig` |
| The whole provider is down | Ask a second provider: the [fallback recipe](../../examples/cookbook/fallback) |
| An agent run that failed mid-turn | `agent.recover(threadId)` on a [durable checkpointer](../agents/durable-checkpointers) |
| A bad request, a refused key, an invalid model | Do not retry: these are not transient, and `ReliableClient` returns them at once |

Every one of these returns a `Result`; nothing throws. The error types and how to match on them are in
[Error Handling](../error-handling).

## Retries, circuit breaker and deadline

`ReliableClient` wraps any `LLMClient` and is an `LLMClient` itself, so it goes anywhere the wrapped client would,
including `Agent.builder`. Build it once and keep it: the circuit breaker counts failures across calls.

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.reliability.{ CircuitBreakerConfig, ReliabilityConfig, ReliableClient, RetryPolicy }

import scala.concurrent.duration._

def resilient(client: LLMClient): ReliableClient = {
  val config = ReliabilityConfig(
    retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 4, baseDelay = 500.millis, maxDelay = 8.seconds),
    circuitBreaker = CircuitBreakerConfig(failureThreshold = 5, recoveryTimeout = 30.seconds),
    deadline = Some(45.seconds)
  )
  ReliableClient(client, "openai-main", config)
}
```

- Only transient errors are retried: a 5xx, a 429, a 408 and the other recoverable errors. A client error is returned
  on the first attempt.
- `RetryPolicy` also has `linearBackoff`, `fixedDelay`, `noRetry` and `custom`.
- `ReliabilityConfig.default` is three exponential attempts, a breaker that opens after five failures for 30 seconds,
  no local rate limit and a five-minute deadline.
- Each attempt is a separate call to the wrapped client, so a metrics-enabled client records every attempt.

The [reliability guide](../reliability) covers each policy, the breaker's states, tool call timeouts and
troubleshooting.

## Falling back to another provider

When the primary still fails after its retries, or its breaker is open, ask a second provider. Wrap each in its own
`ReliableClient`, so each has its own breaker, and try the second on a `Left` from the first. The runnable
[fall back between providers](../../examples/cookbook/fallback) recipe is exactly that, in about twenty lines, and runs
with no API key. A cheaper model as the fallback, or a cached answer from a
[caching client](../caching#4-caching-model-responses), are the same pattern with a different second step.

## Recovering agent runs

An agent's provider, tool or middleware failure ends the run with `Left(GraphError...)`. Two things help:

- **Give the agent a `ReliableClient`.** Transient provider errors are then retried inside the model step, and the
  run never sees them.
- **Use a durable checkpointer.** The thread survives the failure, and `agent.recover(threadId)` continues the run,
  re-running only failed or unstarted work, after a crash or a restart too. On several machines the run queue does
  this for you: a worker takes over a run whose worker died. See
  [Durable Checkpointers](../agents/durable-checkpointers) and
  [Running agents on several machines](../agents/multiple-machines).

A guardrail block (`AgentStatus.Blocked`), a step limit (`StepLimitReached`) and a token budget (`TokenLimitReached`)
are outcomes, not errors: the turn completed and the thread is usable.

## See also

- [Reliable LLM Calling](../reliability)
- [Error Handling](../error-handling)
- [Fallback cookbook recipe](../../examples/cookbook/fallback)
- [Monitoring](../observability/): retry and request metrics
