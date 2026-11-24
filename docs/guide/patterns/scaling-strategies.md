---
layout: page
title: Scaling Strategies
description: "Scale LLM4S applications: caching, local rate limits, batched embedding, parallel delegation and a run queue with workers on several machines."
search_title: "Scale LLM applications: caching, rate limits, batching"
parent: Real-World Application Patterns
grand_parent: Operations
nav_order: 4
---

# Scaling Strategies
{: .no_toc }

An LLM application scales on three things: how many model calls it makes, how fast the provider lets it make them,
and how many machines can run its agents. LLM4S ships a component for each; none needs a cache server, a broker or a
coordinator of its own.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

---

## Make fewer calls

| Technique | Component | Guide |
|---|---|---|
| Never embed the same text twice | `CachedEmbeddingClient` with `InMemoryEmbeddingCache`, or your own `EmbeddingCache` for a shared store | [Caching](../caching#3-caching-embeddings) |
| Answer a near-identical prompt from an earlier answer | `CachingLLMClient` with `CacheConfig.create(similarityThreshold, ttl, maxSize)` | [Caching](../caching#4-caching-model-responses), [cookbook recipe](../../examples/cookbook/caching) |
| Pay less for a long, repeated prompt prefix | Provider-side prompt caching | [Cost controls](../cost-controls#provider-side-prompt-caching) |
| Spend fewer thinking tokens | Reasoning effort and thinking budgets | [Cost controls](../cost-controls#thinking-and-reasoning-effort) |
| Send less history | `ContextWindowMiddleware` prunes what is sent to the model, never what the thread stores | [Context window pruning](../context-window-pruning) |

The completion cache is approximate: it costs an embedding call on every request and a hit can be a false hit. Read
[choosing between them](../caching#2-choosing-between-them) before turning it on.

## Stay within the provider's limits

`ReliableClient` keeps a local token bucket when its `RateLimitConfig` is enabled: a call over the limit fails at once
with a local `RateLimitError` (its origin `RateLimitOrigin.LocalThrottle`) instead of reaching the provider, and the
retry policy retries it like any rate limit. A 429 that still arrives from the provider is retried after its
`Retry-After`.

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.reliability.{ RateLimitConfig, ReliabilityConfig, ReliableClient }

def throttled(client: LLMClient): ReliableClient =
  new ReliableClient(
    client,
    "openai-main",
    ReliabilityConfig.default.withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = 500, burstCapacity = 50))
  )
```

The bucket is per `ReliableClient` instance, so it limits one process. Share one instance across the process, and
divide the provider's quota between processes. See the [reliability guide](../reliability) and
[Error Recovery](error-recovery).

## Do more at once

- **Ingesting documents.** The RAG pipeline embeds in batches and loads documents in parallel:
  `RAGConfig.withBatchSize(n)` and `withParallelism(n)`; `rag.ingestAsync(loader)` and `syncAsync` run on your
  `ExecutionContext`. See [Document Loaders](../document-loaders#feeding-a-loader-to-rag).
- **Independent subtasks of one request.** A lead agent's [subagents](../agents/delegation) run on child threads,
  bounded by `DelegationConfig.withMaxConcurrentChildren`. A fixed fan-out is an
  [agent graph](../../examples/cookbook/multi-agent-graph), whose parallel nodes run in one superstep.
- **Tool calls.** The tool calls of one model message run in parallel, bounded by `RunBudgets.maxConcurrency` in the
  `RunConfig` you pass to `run`; see [parallel tool calls](../agents/#parallel-tool-calls).

## Run agents on several machines

For an agent service behind a load balancer, LLM4S ships the service kernel in `llm4s-agent`: a run queue in the
shared PostgreSQL store (`llm4s-agent-checkpoint-postgres`), a `RunSubmitter` your endpoints call, and an
`AgentWorker` on every instance that takes queued runs, recovers runs whose machine died, and drains on shutdown.
Instances talk only to the database. Add instances to add capacity; the queue depth and wait metrics tell you when.

[Running agents on several machines](../agents/multiple-machines) has the configuration, the worker loop and the
example deployment; [Durable Checkpointers](../agents/durable-checkpointers) covers the store.

## Measure first

Before scaling, measure where the time and money go: request latency and spend by model from
[metrics](../observability/#metrics), and queue depth and wait from the
[agent runtime metrics](../observability/#agent-runtime-metrics).

## See also

- [Caching](../caching), [Cost controls](../cost-controls)
- [Production Monitoring](production-monitoring)
- [Production Deployment](../production-deployment)
