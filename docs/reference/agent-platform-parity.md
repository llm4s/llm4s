---
layout: page
title: Agent Platform Parity
parent: Reference
nav_order: 23
---

# Agent platform parity: LangGraph Platform, Deep Agents and llm4s
{: .no_toc }

How llm4s's agent runtime compares with LangGraph Platform and Deep Agents after Stage 5 ([#2129](https://github.com/llm4s/llm4s/issues/2129)),
capability by capability, and where each gap went.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Scope and sources

- **LangGraph Platform** was renamed **LangSmith Deployment** in October 2025 ([announcement](https://changelog.langchain.com/announcements/ann_NXVDQVGqbwBqW),
  [product page](https://www.langchain.com/langsmith/deployment)); its server, once "LangGraph Server", is now
  **Agent Server** ([docs](https://docs.langchain.com/langsmith/agent-server)). This page keeps the older name, as the
  Stage 5 spec does. It is a hosted or licensed server product, not open source.
- **Deep Agents** (`deepagents`, MIT, Python and JS) is an agent harness built on LangGraph
  ([repository](https://github.com/langchain-ai/deepagents), [overview](https://docs.langchain.com/oss/python/deepagents/overview)).
  It is a library; deployed to LangSmith Deployment (or the newer Managed Deep Agents,
  [docs](https://docs.langchain.com/langsmith/managed-deep-agents-overview)), it gets every platform capability
  ([going to production](https://docs.langchain.com/oss/python/deepagents/going-to-production)). "Inherits" below means that.
- **llm4s** is a library: the agent runtime (`llm4s-agent`), the store modules and the Stage 5 service kernel
  (`org.llm4s.agent.service`). The HTTP server product is a separate project (Stage 5 spec, Decisions 2); this
  repository ships an example service ([Running agents on several machines](../guide/agents/multiple-machines)).

The comparison was checked against each project's documentation on 2026-10-23. **Where it went** names the
destination of each llm4s gap: a later stage, an issue, the separate HTTP server project, or out of scope. Items
marked *deferred* are the Stage 5 spec's Decisions 11 ("deferred, as later issues"); each gets its own issue when its
work is picked up.

## Durable execution and scale-out

| Capability | LangGraph Platform | Deep Agents | llm4s | Where it went |
|---|---|---|---|---|
| Durable checkpoint store | Postgres by default, MongoDB, or a custom saver; OSS savers for memory, SQLite, Postgres, and a partner Redis saver ([checkpointer](https://docs.langchain.com/langsmith/configure-checkpointer), [persistence](https://docs.langchain.com/oss/python/langgraph/persistence)) | Any LangGraph checkpointer; inherits | In-memory, SQLite and PostgreSQL `Checkpointer`s, all passing one contract suite (`CheckpointerContract`); chosen in `application.conf` | Parity. Another database: out of scope for Stage 5 (the SPI stays open) |
| Several instances sharing threads | Stateless API servers and queue workers; Postgres for durable data and the queue, **Redis** for signalling, cancellation and stream pub/sub ([architecture](https://docs.langchain.com/langsmith/agent-server), [scaling](https://docs.langchain.com/langsmith/agent-server-scale)) | Inherits | Instances share one Postgres schema: run queue, workers, claims and notification (`LISTEN`/`NOTIFY`) all in the database, no Redis or broker | Parity, with less infrastructure |
| Recovering a run whose worker died | Worker heartbeats in Redis; a sweeper re-queues lapsed runs every 2 minutes; resumes from the last checkpoint ([resilience](https://docs.langchain.com/langgraph-platform/scalability-and-resilience)) | Inherits | The queue entry's lease and the thread's claim expire by the database's clock; another worker records a new attempt and recovers from the last checkpoint. At least once, applied at most once, by `RunId` | Parity |
| Bounded retries, dead letters | `BG_JOB_MAX_RETRIES` (default 3) ([variables](https://docs.langchain.com/langsmith/env-var-self-hosted)) | Inherits | `llm4s.agent.queue.maxAttempts`; an exhausted entry closes `failed`, counted as a dead letter | Parity |
| Graceful shutdown | Unfinished runs re-queued after a grace period ([variables](https://docs.langchain.com/langsmith/env-var-self-hosted)) | Inherits | `AgentWorker.shutdown()` drains within `drain.grace`, then cancels and gives entries back for another instance at once | Parity |
| Background runs: submit, poll, join | Runs API: create, poll, `wait`, `join` ([architecture](https://docs.langchain.com/langsmith/agent-server)) | Inherits | `RunSubmitter.submit` (ticket), `entry`, `await`; submission keys for idempotent retries | Parity in the kernel; HTTP endpoints: the HTTP server project |
| Streaming and rejoining a stream | Several stream modes; thread streams resume by `Last-Event-ID`; a run's stream only if created `stream_resumable`, kept 120 s in Redis ([streaming](https://docs.langchain.com/langsmith/streaming), [join](https://docs.langchain.com/langsmith/agent-server-api/thread-runs/join-run-stream.md)) | Inherits | `RunSubmitter.follow` from any thread sequence number, on any machine, for as long as retention keeps the events; the example service serves it as SSE with `Last-Event-ID` | Parity (durable replay is longer-lived) |
| Cancel from any instance | `runs.cancel` with `interrupt` or `rollback` ([cancel](https://docs.langchain.com/langgraph-platform/cancel-run)) | Inherits | `RunSubmitter.cancel` for an entry or a whole thread; the holding worker stops it at its next lease renewal and settles the thread | Parity, except `rollback`: deleting a cancelled run's checkpoints is not offered (fork from an earlier checkpoint instead) |
| Busy thread ("double texting") | `reject`, `enqueue` (default), `interrupt`, `rollback` ([double texting](https://docs.langchain.com/langsmith/double-texting)) | Inherits | `Reject` (default), `Enqueue`, `Interrupt` at the next superstep boundary, started tool calls finishing and the history kept provider-valid | Parity, except `rollback` (as above) |
| Thread search | By status and metadata, sorted ([threads](https://docs.langchain.com/langsmith/use-threads)) | Inherits | Thread registry: by tenant, status, agent, metadata and update time, paged; incomplete threads | Parity |
| Retention | `checkpointer.ttl` (`delete` or `keep_latest`), `store.ttl`, swept every 5 minutes ([TTL](https://docs.langchain.com/langsmith/configure-ttl)) | Inherits | Store-wide sweep on the worker's schedule: by age, checkpoint and event counts; tombstones reclaimed with a defined answer for readers (`ThreadDeleted`); closed entries reclaimed | Parity |
| Rolling upgrades | Shutdown draining is documented; schema migration behaviour of Agent Server across versions is not ([standalone](https://docs.langchain.com/langsmith/deploy-standalone-server)) | Inherits | Rolling within one schema and checkpoint format; otherwise a full stop, an old instance refusing a newer schema | Expand-then-contract migrations: with the 1.0 compatibility baseline |
| Encryption at rest | AES keys for checkpoints and JSON fields; custom handlers with per-tenant keys (beta) ([encryption](https://docs.langchain.com/langsmith/custom-encryption)) | Inherits | None in the store (database-level encryption applies) | *Deferred*: checkpoint encryption at rest |
| External durability engine | No | No | No (the store is the durability layer) | Out of scope for Stage 5; Temporal integration is [#876](https://github.com/llm4s/llm4s/issues/876) |

## Observability and operations

| Capability | LangGraph Platform | Deep Agents | llm4s | Where it went |
|---|---|---|---|---|
| Tracing | LangSmith by default; OpenTelemetry and Datadog export ([variables](https://docs.langchain.com/langsmith/env-var-self-hosted)) | Inherits; traces to LangSmith | OpenTelemetry span tree and Langfuse nesting from the agent's tracing; child runs under their `task` call | Parity |
| One trace across machines | LangSmith `langsmith-trace` / `baggage` headers, opt-in ([distributed tracing](https://docs.langchain.com/langsmith/agent-server-distributed-tracing)) | Inherits | W3C `TraceContext` on the submission, continued by every attempt, recorded on every checkpoint | Parity (standard W3C headers) |
| Metrics | Prometheus `GET /metrics`; Datadog ([metrics](https://docs.langchain.com/langsmith/self-hosted-agent-server-metrics)) | Inherits | Runs, claims, commits, child tasks, queue and sweep metrics through Prometheus or Micrometer | Parity |
| Cron / scheduled runs | Thread-bound or stateless cron jobs ([cron](https://docs.langchain.com/langsmith/cron-jobs)) | Inherits | None | *Deferred*: cron |
| Webhooks on completion | `webhook` on runs and crons ([webhooks](https://docs.langchain.com/langsmith/use-webhooks)) | Inherits | None (a caller can `await` a ticket) | *Deferred*: webhooks |
| Evaluation | LangSmith datasets and experiments; trajectory evaluators in `agentevals` ([trajectory evals](https://docs.langchain.com/langsmith/trajectory-evals)) | The same tooling | None in the runtime | **Stage 6**: agent evaluation ([#410](https://github.com/llm4s/llm4s/issues/410)) |

## Serving and deployment

| Capability | LangGraph Platform | Deep Agents | llm4s | Where it went |
|---|---|---|---|---|
| HTTP API for threads and runs | Agent Server, a superset of Agent Protocol ([Agent Protocol](https://www.langchain.com/blog/agent-protocol-interoperability-for-llm-agents)) | Inherits | An example service (`modules/deploy-service`); the kernel is transport-neutral | The separate HTTP server project (Decisions 2) |
| A2A, MCP and AG-UI endpoints | MCP at `/mcp`, A2A at `/a2a/{assistant_id}` ([MCP](https://docs.langchain.com/langsmith/server-mcp), [A2A](https://docs.langchain.com/langsmith/server-a2a)) | Inherits | MCP client and server in `llm4s-mcp`, not exposed per agent; no A2A or AG-UI | *Deferred*: A2A, AG-UI; the server's endpoints: the HTTP server project |
| Authentication | Custom auth handlers with owner filters; none by default when self-hosted ([auth](https://docs.langchain.com/langsmith/auth)) | Inherits | Every registry, queue and cancel call is tenant-scoped; authentication is the server's | The HTTP server project |
| Versioned assistants | Assistants: graph plus config, every update a version, rollback by activating one ([assistants](https://docs.langchain.com/langsmith/assistants)) | Inherits | None: an agent is code, named by its id; graph versions are checked before a worker takes a thread | *Deferred*: versioned assistants |
| Deployment | Cloud, Hybrid, self-hosted, or a standalone server that needs a license key and Postgres plus Redis ([deployment](https://docs.langchain.com/langsmith/deployment), [standalone](https://docs.langchain.com/langsmith/deploy-standalone-server)) | MIT library; deploy through LangSmith Deployment or Managed Deep Agents | MIT library in your own service; a Kubernetes overlay running three instances on one Postgres | Parity for self-hosting; no hosted offering (out of scope) |

## Agent harness

| Capability | LangGraph Platform | Deep Agents | llm4s | Where it went |
|---|---|---|---|---|
| Human in the loop | `interrupt()` resumed with `Command(resume=...)` ([HITL](https://docs.langchain.com/langsmith/add-human-in-the-loop)) | `interrupt_on` per tool: approve, edit, reject ([HITL](https://docs.langchain.com/oss/python/deepagents/human-in-the-loop.md)) | Typed approvals and questions, concurrent interrupts, partial resume, breakpoints, edit and reject | Parity |
| Subagents | Graph composition | `task` tool, general-purpose subagent, parallel calls ([subagents](https://docs.langchain.com/oss/python/deepagents/subagents)) | `task` and typed `TypedAgent[I, O]` delegation, isolated child threads, bounded parallel children, nested streams | Parity |
| Background (async) subagents | - | Async subagents (beta) over Agent Protocol ([async subagents](https://docs.langchain.com/oss/python/deepagents/async-subagents)) | None: a `task` call waits for its child | *Deferred*: async subagents |
| Planning, files, context, skills, memory, sandbox | - | Todos, virtual filesystem backends, summarisation and offload, skills, `AGENTS.md`, sandboxed `execute` ([backends](https://docs.langchain.com/oss/python/deepagents/backends.md), [context](https://docs.langchain.com/oss/python/deepagents/context-engineering.md), [skills](https://docs.langchain.com/oss/python/deepagents/skills.md), [sandboxes](https://docs.langchain.com/oss/python/deepagents/sandboxes.md)) | The Deep Agents harness profile (Stage 3): todos, virtual files and composite backends, summarisation and offload, Agent Skills, instruction memory, workspace sandbox `execute` | Parity |
| Long-term memory store | Postgres-backed `Store` with semantic search ([semantic search](https://docs.langchain.com/langsmith/semantic-search)) | `StoreBackend` for files across threads | Long-term store SPI and semantic-memory hooks (Stage 3); `llm4s-memory-postgres` for agent memory; no shared Postgres `LongTermStore` for the harness | *Deferred*: a Postgres `LongTermStore` |
| Compensating tool actions | No | No | No (idempotency keys guard re-execution) | *Deferred*: compensating tool actions |

## Summary

On durable execution and scale-out, llm4s matches LangGraph Platform's capabilities with one database and no Redis,
and is a library you run rather than a server you license. The gaps are the server product (HTTP API, auth,
protocol endpoints: a separate project), platform conveniences the spec deferred (cron, webhooks, versioned
assistants, encryption at rest, A2A, AG-UI, async subagents, a Postgres long-term store, compensating actions),
`rollback` on cancel and double texting, and evaluation, which is Stage 6.
