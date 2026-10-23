---
layout: page
title: Running agents on several machines
nav_order: 9
parent: Agents
grand_parent: User Guide
---

# Running agents on several machines
{: .no_toc }

Run one agent service as several instances that share threads through one PostgreSQL database: any instance can
start, follow, cancel or delete any thread, a run whose machine dies is picked up by another, and one database is the
whole deployment.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## The shape of it

llm4s ships the *service kernel*, not an HTTP server: everything that touches the store's guarantees - claims,
fencing, the run queue, the worker, retention - is in `llm4s-agent` (`org.llm4s.agent.service`) and the store modules,
and your service puts its own endpoints, authentication and protocol in front of it. Each instance runs:

- a **store** over the shared database (`PostgresCheckpointer`, `llm4s-agent-checkpoint-postgres`), which holds every
  thread's checkpoints and events and the **run queue**;
- a **`RunSubmitter`** that your endpoints call to queue a run, follow its events, cancel it or delete a thread;
- an **`AgentWorker`** that takes queued runs, runs them on your agents, recovers runs whose worker died, runs the
  retention sweep and drains on shutdown.

```
           HTTP / gRPC / your protocol
   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
   │ instance A   │ │ instance B   │ │ instance C   │
   │ RunSubmitter │ │ RunSubmitter │ │ RunSubmitter │
   │ AgentWorker  │ │ AgentWorker  │ │ AgentWorker  │
   └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
          └────────────────┼────────────────┘
                    PostgreSQL (one schema):
              checkpoints, events, claims, run queue
```

There is no Redis, broker or coordinator: instances never talk to each other, only to the database, and the database's
clock decides every claim, lease and retention age, so clock skew between machines changes nothing.

A complete, runnable example is the [example agent service](#the-example-deployment); the stage's exit test runs three
of it against one Postgres and kills one mid-run.

## The store

Every instance opens the same schema. With `llm4s-agent-checkpoint-postgres` on the classpath, configuration alone
chooses it:

```hocon
llm4s.agent.checkpointer {
  kind = "postgres"                                # or LLM4S_CHECKPOINTER
  postgres {
    url    = "jdbc:postgresql://db:5432/app"       # or LLM4S_CHECKPOINTER_POSTGRES_URL
    user   = "agents"                              # or LLM4S_CHECKPOINTER_POSTGRES_USER
    # password: LLM4S_CHECKPOINTER_POSTGRES_PASSWORD, never in a checked-in file
    schema = "agents"
    callTimeout = 5 seconds
    connectionTimeout = 5 seconds
    maxPoolSize = 10
  }
}
```

```scala
for
  store    <- CheckpointerConfigLoader.open()           // close it when the process stops
  runtime  <- RuntimeConfigLoader.runtime(store.checkpointer)
  queue    <- store.runQueue                            // the queue lives in the same schema
yield (store, runtime, queue)
```

- Instances that start together migrate the schema once, under an advisory lock.
- Every store call returns within `connectionTimeout + callTimeout`. Keep that well inside the claim's
  `ttl - renewEvery` (below), so a slow database makes a run lose its claim cleanly rather than hang.
- Each instance holds up to `maxPoolSize` connections plus one listening connection (commits are announced with
  `LISTEN`/`NOTIFY`, so a run committed on one machine reaches a subscriber on another at once). Size the database's
  `max_connections` for every instance.

The store's details - fencing, notification, the registry - are in
[Durable Checkpointers](durable-checkpointers#the-postgresql-store).

## The queue and the workers

A run is **submitted** to the queue and **executed** by whichever instance's worker takes it. The queue entry, not the
instance that accepted the request, owns the run: it records each attempt's `RunId` before the attempt touches the
thread, so a run is attempted at least once and applied at most once, however many workers try it.

```scala
// every instance
for
  agent     <- Agent.builder("assistant", client).withRuntime(runtime).build()
  settings  <- RunQueueConfigLoader.load()              // llm4s.agent.queue
  workerCfg <- WorkerConfigLoader.load()                // llm4s.agent.worker
  worker    <- AgentWorker(queue, store.checkpointer, Seq(QueuedAgent(agent)), workerCfg, settings, metrics)
  submitter  = new RunSubmitter(queue, store.checkpointer, settings, metrics)
yield
  worker.start()
  sys.addShutdownHook { worker.shutdown(); store.close() }   // drain, then close
  submitter

// an endpoint, on any instance
submitter.submit(ThreadId("t-42"), AgentId("assistant"), QueuedInput.query("Summarise the report"), Some(tenant))
// -> Ticket(entryId, threadId, position, existing)
```

- **Every instance runs the same agents under the same ids.** A worker takes only entries for the agent ids it was
  built with; a typed agent goes in as `QueuedAgent.typed(boundAgent)`.
- **Following a run** is `submitter.follow(tenant, entryId, after, limit, wait)`, a page at a time from a thread
  sequence number: it waits for the next commit on whichever machine makes it, and resuming from the last number seen
  repeats nothing. `submitter.await(tenant, entryId, timeout)` waits for the entry to close.
- **A machine that dies mid-run** leaves its entry's lease and its thread's claim to expire. Another worker then takes
  the entry, records a new attempt and recovers the thread from its last checkpoint; a follower sees the recovered
  run's events continue the same log, with no gap and no repeat. Recovery takes about the longer of the lease
  and claim `ttl`s.
- **Attempts are bounded** by `llm4s.agent.queue.maxAttempts`; an entry that runs out is closed `failed` (a dead
  letter), and so is one no worker's graph version can run for `unrunnableAfter`.
- **Submission keys** make a retried request idempotent: the same `submissionKey` from the same tenant returns the
  first entry's ticket.

```hocon
llm4s.agent {
  runtime.claims { ttl = 30 seconds, renewEvery = 10 seconds }    # label: LLM4S_RUNTIME_LABEL
  queue {
    maxAttempts = 5
    ticketLifetime = 24 hours          # how long a closed entry stays readable
    lease { ttl = 30 seconds, renewEvery = 10 seconds }
  }
  worker {
    concurrency = 4                    # runs one worker has at once
    pollInterval = 1 second
    drain { grace = 30 seconds, exitTimeout = 10 seconds }
    label = ${?LLM4S_WORKER_LABEL}     # the pod or host name: it names the lease holder in logs
  }
}
```

**Shutdown drains.** `worker.shutdown()` stops taking entries, lets running runs reach rest within `drain.grace`,
then cancels the rest and gives their entries back, so another instance recovers them at once instead of waiting for
the lease to lapse. Give the process `grace + exitTimeout` plus a margin to stop (Kubernetes'
`terminationGracePeriodSeconds`).

**Readiness is `worker.health()`**: the worker is running, its loop is alive and the store answers. Keep liveness on
the process alone: restarting instances because the database is down turns an outage into a crash loop.

## Cancel and the busy-thread policy

**Cancel from any machine** with `submitter.cancel(tenant, CancelTarget.Entry(entryId), note)`, or
`CancelTarget.Thread(threadId)` for every open entry of a thread. An entry not yet started closes at once; a running
one is stopped by the worker holding it, on whichever machine, when it next renews the entry's lease (so within
`lease.renewEvery`): the run is interrupted, its thread settled, and the entry closes `cancelled` - or with the run's
own outcome if it finished first. `submitter.deleteThread(runtime, tenant, threadId, timeout)` cancels the thread's
entries, waits for them to close, and then deletes it.

**A message for a busy thread** - one with an open entry or a live run - follows the submission's `policy`:

| `BusyThreadPolicy` | What happens |
|---|---|
| `Reject` (default) | Refused with `GraphError.ThreadBusy`; the caller retries later. |
| `Enqueue` | Queued behind the thread's entries; runs after them, in submission order. |
| `Interrupt` | Queued ahead of them. The running queued run stops at its next superstep boundary: the model call in flight is cut, tool calls already started finish and keep their results, unstarted ones get a synthetic "interrupted" result, so the history stays valid for the provider. Its entry closes `interrupted` and the new message runs next. |

A run started directly (`agent.run`, outside the queue) is never interrupted or adopted by a worker: it is its
owner's, who calls `recover` after a crash. Pending approvals or questions are not a busy thread: answer them with a
`QueuedInput.Resume`.

## Tracing

A run is **one trace across every machine that executes it.** Pass the caller's position as the submission's
`traceContext` (for HTTP, parse the request's W3C `traceparent` header with `TraceContext.parse`); the entry stores
it, and every attempt continues it - the first, and a recovery on another machine after a crash. Each checkpoint
records the trace it belongs to (`Checkpoint.trace`), and span ids are derived from the trace id and the run's
identity, so two machines name the same spans without coordinating.

Spans are emitted by the agent's tracing: build the agent `withTracing(tracing)` with a backend from
`llm4s-observability-otel` (`TRACING_MODE=opentelemetry`) or `llm4s-observability` (Langfuse). Delegated child runs
sit under the tool call that started them. No message content goes into a span attribute.

## Metrics

Give the worker and a `MeteredCheckpointer` the same `MetricsCollector` (`PrometheusMetrics` from
`llm4s-observability-prometheus`, or `MicrometerMetrics` from `llm4s-observability-micrometer`):

```scala
val metrics      = new PrometheusMetrics(registry)
val checkpointer = MeteredCheckpointer(store.checkpointer, metrics)   // runs, claims, commits, child tasks
val runtime      = RuntimeConfigLoader.runtime(checkpointer)
// AgentWorker(..., metrics) and new RunSubmitter(..., metrics): the queue
```

| Area | Metrics (`llm4s.agent.*`; Prometheus writes `_` for `.`) |
|---|---|
| Runs | `runs.started`, `runs.ended` (by outcome), `run.duration` |
| Claims | `claims` (acquired, taken over, busy, failed), `claim.renewals` (renewed, lost, failed) |
| Commits | `commit.duration` |
| Queue | `queue.wait`, `queue.depth`, `queue.oldest.seconds`, `queue.attempts`, `queue.plans`, `queue.deferrals`, `queue.lease.expiries`, `queue.lease.lapses`, `queue.lease.losses`, `queue.closed` (by outcome and reason; dead letters are `failed` / `attempts_exhausted`) |
| Child tasks | `tasks.started`, `tasks.ended`, `task.duration` |
| Retention | `sweep.passes`, `sweep.threads.examined`, `sweep.tombstones.reclaimed`, `sweep.entries.reclaimed` |
| Shutdown | `worker.drained` |

Labels take values from fixed sets (and a graph's id), never thread ids or content. Alert on `queue.oldest.seconds`
(work waiting with no worker taking it), `queue.lease.expiries` (machines dying mid-run) and dead letters.

## Retention

The database stays bounded only if something removes old data. The worker runs the store-wide retention sweep on a
schedule, one instance at a time:

```hocon
llm4s.agent.worker.sweep {
  enabled = true
  every = 5 minutes
  batchSize = 100
  tombstoneAge = 7 days                                      # unset: tombstones are kept
  retention { maxAge = 30 days, maxCheckpoints = 20, maxEvents = 10000 }   # unset keys remove nothing
}
```

- Each pass prunes every thread by `retention`, reclaims the tombstones of threads deleted more than `tombstoneAge`
  ago, and reclaims closed queue entries older than `llm4s.agent.queue.ticketLifetime`.
- A thread with an open queue entry is still pruned, but keeps the newest checkpoint each of the entry's attempts
  committed and its tombstone, so a run in flight can always be recovered once.
- **A reader of a reclaimed thread** - a client resuming an old event stream - is answered `GraphError.ThreadDeleted`,
  before and after reclamation, and also once a new thread reuses the id: it is never shown the new thread's events
  as a continuation of the old one. Its correct action is to read from sequence `0` to follow whatever is under the id
  now. A ticket whose entry was reclaimed answers `QueueError.TicketExpired`.

Ages are judged by the database's clock against the time it committed each row, never a producer's timestamp.

## The example deployment

`modules/deploy-service` is an example agent service on this kernel (unpublished; copy it rather than depend on it):
submit a run, follow it over Server-Sent Events from a sequence number, cancel it, read and list threads, with
readiness, Prometheus metrics and SIGTERM draining. `deploy/overlays/postgres` runs three replicas of it against one
Postgres, with a PodDisruptionBudget. [`deploy/README.md`](https://github.com/llm4s/llm4s/blob/main/deploy/README.md)
has the endpoints, the environment variables and the `kubectl` steps.

Its exit test, `MultiMachineExitSpec` in `modules/it` (`sbt testIntegration`), starts three processes of it on one
schema and checks, through their HTTP endpoints: a run submitted through one instance, executed by another, streamed
live through a second and replayed from any sequence number through a third; the executing instance killed with
`kill -9` and the run recovered elsewhere with an unbroken stream, in one trace; `Enqueue`, `Interrupt` and a
cross-machine cancel; and a reclaimed tombstone answering `ThreadDeleted`.

## Upgrades

Whether instances can be replaced one at a time depends on what the release changes:

- **Same store schema and checkpoint format: rolling upgrade.** Replace instances one at a time. Each old instance
  drains on SIGTERM and gives back what it could not finish; new instances take it. If the agent's graph changed, a
  run in flight at the old graph version is passed over by new workers until an old one takes it, and closed `failed`
  if none does within `llm4s.agent.worker.unrunnableAfter` (1 hour by default): keep at least one old instance until
  its runs drain.
- **A new store schema version or checkpoint format: full stop.** Stop every instance, then start the new release.
  The first new instance migrates the schema; an old instance that meets the newer schema refuses to open with
  `GraphError.UnsupportedStoreSchema` and writes nothing, so a mixed fleet fails safe rather than corrupting threads.

The release notes and the [migration guide](../../reference/migration) say when a release changes either.
Expand-then-contract migrations, which would let schema changes roll too, are planned with the 1.0 compatibility
baseline.

## Limits

- **One database.** The SPI is open to another store, but PostgreSQL is the only multi-machine store shipped.
- **No HTTP server product.** Endpoints, authentication, Agent Protocol, A2A and AG-UI belong to the service you build
  on the kernel; the example service shows one shape and authenticates nothing (its `X-Tenant-Id` header is not auth).
- **Not yet:** a shared Postgres long-term memory store, cron and webhooks, versioned assistants, checkpoint encryption
  at rest, background subagents. See the [parity matrix](../../reference/agent-platform-parity) for where each went.

## See also

- [Durable Checkpointers](durable-checkpointers): claims, fencing, recovery, the PostgreSQL store, retention and the
  thread registry.
- [Streaming events](streaming): the event log a follower reads.
- [Agent platform parity](../../reference/agent-platform-parity): llm4s against LangGraph Platform and Deep Agents.
- `docs/design/typed-agent-runtime/` 4.50-4.61: the Stage 5 design records.
