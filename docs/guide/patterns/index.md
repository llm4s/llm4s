---
layout: page
title: Real-World Application Patterns
description: "Production patterns for LLM applications in Scala: multi-agent orchestration, enterprise RAG, monitoring, scaling, error recovery and security."
search_title: "Production LLM application patterns in Scala"
parent: Operations
nav_order: 706
grand_parent: User Guide
has_children: true
---

# Real-World Application Patterns
{: .no_toc }

The questions every production LLM application meets - how agents work together, how retrieval scales, how failures
are survived, what to monitor, how to secure keys and data - and the LLM4S component that answers each. Each page is
short: when to use the pattern, the shipped component, a minimal example, and links to the full guide and a runnable
sample.
{: .fs-6 .fw-300 }

---

## The patterns

| Challenge | Page | Shipped components |
|---|---|---|
| Several agents need to work together | [Multi-Agent Orchestration](multi-agent-orchestration) | `Handoff.to` / `Handoff.toId`, `withSubagents` and `TypedAgent`, `GraphBuilder` |
| Answering over a large document set | [RAG for Enterprise](rag-enterprise) | `RAG` with `withPgHybrid`, loaders and `sync`, reranking, permission-based RAG, RAGAS evaluation |
| Seeing what production is doing | [Production Monitoring](production-monitoring) | Langfuse and OpenTelemetry tracing, `PrometheusMetrics`, `MicrometerMetrics`, `MeteredCheckpointer`, `CostTracker` |
| Handling more traffic for less money | [Scaling Strategies](scaling-strategies) | `CachingLLMClient`, `CachedEmbeddingClient`, `RateLimitConfig`, `AgentWorker` and the run queue |
| Surviving provider failures | [Error Recovery](error-recovery) | `ReliableClient`, `RetryPolicy`, `CircuitBreakerConfig`, provider fallback, `agent.recover` |
| Protecting keys and data | [Security Best Practices](security-best-practices) | `apiKeyFile`, `VaultApiKeyProvider`, `AwsSecretsManagerApiKeyProvider`, guardrails, encrypted checkpoints, the workspace sandbox |

## Combining them

- **A support desk with departments.** A triage agent hands off to department agents
  ([handoffs](multi-agent-orchestration#routing-with-handoffs)), each answering from its own documents
  ([RAG](rag-enterprise)), every client wrapped in `ReliableClient` with a fallback provider
  ([Error Recovery](error-recovery)).
- **An internal knowledge assistant.** A hybrid PostgreSQL pipeline kept current with `sync`, retrieval filtered by the
  asking user ([permission-based RAG](../permission-based-rag)), PII masked on the way in and out
  ([guardrails](security-best-practices#untrusted-input-and-output)), and RAGAS scores tracked over time.
- **An agent service behind an API.** Agents on a shared PostgreSQL store with a run queue and a worker on every
  instance ([scaling](scaling-strategies#run-agents-on-several-machines)), checkpoints encrypted per tenant
  ([security](security-best-practices#data-at-rest-and-data-retention)), runs traced and run queue metrics scraped
  ([monitoring](production-monitoring)).

## Start from a running example

- The [Cookbook](../../examples/cookbook) recipes run with no API key: fallback between providers, caching, a
  multi-agent graph, document Q&A, guardrails, memory.
- The [example agent service](../agents/multiple-machines#the-example-deployment) is a deployable agent service with a
  run queue, a worker and server-sent events.
- [Production Deployment](../production-deployment) covers configuration, secrets, containers and Kubernetes.
- [Reference Architecture](../reference-architecture) puts llm4s behind an AI gateway: secret injection, egress
  policy, failover, quotas and spend.
