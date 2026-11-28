---
layout: page
title: Running agents on several machines
description: "Run an LLM4S agent service as several instances sharing threads through one PostgreSQL database, with a run queue, workers and recovery of dead runs."
search_title: "Run LLM agents on several machines with PostgreSQL"
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

{: .note }
> **Experimental at 1.0.** The API on this page (shared checkpointers, run claims, the run queue and worker in `agent.service`) is `@Experimental`: usable, but outside the 1.x compatibility promise, so it can change in a minor release, with a migration note. The stable core of `llm4s-agent` - building an agent, running, continuing and recovering a thread, handoffs and guardrails - is listed in [API Stability](../../reference/api-stability#tiers-in-the-code).

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
  thread's checkpoints and events and the **run queue**, and, if your agents keep files or memories across threads,
  their [long-term store](durable-checkpointers#long-term-memory-on-the-same-database) (`store.longTermStore()`);
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
import org.llm4s.config.{ CheckpointerConfigLoader, RuntimeConfigLoader }

for
  store    <- CheckpointerConfigLoader.open()           // close it when the process stops
  runtime  <- RuntimeConfigLoader.runtime(store.checkpointer)
  queue    <- store.runQueue                            // the queue lives in the same schema
yield (store, runtime, queue)
```

- Instances that start together migrate the schema once, under an advisory lock.
- Every store call returns within `connectionTimeout + callTimeout`. Keep that well inside the claim's
  `ttl - renewEvery` (below), so a slow database makes a run lose its claim cleanly rather than hang.
- Each instance holds up to `maxPoolSize` connections plus one listening connection (commits and queue entry closes
  are announced with `LISTEN`/`NOTIFY`, so a run committed on one machine reaches a subscriber on another at once,
  and `submitter.await` wakes when the entry closes rather than polling). Size the database's `max_connections` for
  every instance.

The store's details - fencing, notification, the registry - are in
[Durable Checkpointers](durable-checkpointers#the-postgresql-store).

## The queue and the workers

A run is **submitted** to the queue and **executed** by whichever instance's worker takes it. The queue entry, not the
instance that accepted the request, owns the run: it records each attempt's `RunId` before the attempt touches the
thread, so a run is attempted at least once and applied at most once, however many workers try it.

```scala
import org.llm4s.agent.{ Agent, AgentId }
import org.llm4s.agent.graph.ThreadId
import org.llm4s.agent.service.{ AgentWorker, QueuedAgent, QueuedInput, RunSubmitter }
import org.llm4s.config.{ RunQueueConfigLoader, WorkerConfigLoader }

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
AgentId.of("assistant").flatMap { agentId =>
  submitter.submit(ThreadId("t-42"), agentId, QueuedInput.query("Summarise the report"), Some(tenant))
} // -> Right(Ticket(entryId, threadId, position, existing))
```

- **Every instance runs the same agents under the same ids.** A worker takes only entries for the agent ids it was
  built with; a typed agent goes in as `QueuedAgent.typed(boundAgent)`.
- **A queued query is stored as you submit it.** `QueuedInput.query(text)` is in the queue before any guardrail has
  seen it, and the worker that takes it runs the agent's input guardrails before the turn's first checkpoint. To keep
  what a guardrail would refuse or rewrite out of the queue as well, admit the query on the submitting instance and
  queue the result: `agent.admit(threadId, text).map(QueuedInput.admitted)`. The entry then holds the query as the
  guardrails left it - or, for a refused one, only the refusing guardrail's name - and the worker does not run them
  again. See [Admission](middleware#admission).
- **Following a run** is `submitter.follow(tenant, entryId, after, limit, wait)`, a page at a time from a thread
  sequence number: it waits for the next commit on whichever machine makes it, and resuming from the last number seen
  repeats nothing. `submitter.await(tenant, entryId, timeout)` waits for the entry to close.
- **A machine that dies mid-run** leaves its entry's lease and its thread's claim to expire. Another worker then takes
  the entry, records a new attempt and recovers the thread from its last checkpoint; a follower sees the recovered
  run's events continue the same log, with no gap and no repeat. Recovery takes about the longer of the lease
  and claim `ttl`s.
- **Attempts are bounded** by `llm4s.agent.queue.maxAttempts` (an attempt a drain cut short is not counted; see
  Shutdown drains below); an entry that runs out is closed `failed` (a dead letter), and so is one no worker's graph version can run for `unrunnableAfter`. A worker that cannot read the
  entry's thread spends an attempt each time, so a thread that never reads dead-letters its entry too. The sweep
  closes `failed` (`Unrunnable`) a thread's head that no worker has taken within `unrunnableAfter` of its
  submission, such as one for an agent id no worker serves; `queue.allDepths()` shows every agent's open entries,
  served or not. An entry whose stored form this build cannot read (written by a newer release, or by hand) is closed
  `failed` when a take reaches it, and the take goes on to the next.
- **Queues are bounded**: a tenant may have `maxOpenPerThread` open entries on one thread, and a queued input (its
  history included) may be `maxInputBytes` long; a submission past either is refused with
  `QueueError.ThreadQueueFull` or `QueueError.InputTooLarge`.
- **Submission keys** make a retried request idempotent: the same `submissionKey` from the same tenant returns the
  first entry's ticket.

```hocon
llm4s.agent {
  runtime.claims { ttl = 30 seconds, renewEvery = 10 seconds }    # label: LLM4S_RUNTIME_LABEL
  queue {
    maxAttempts = 5
    ticketLifetime = 24 hours          # how long a closed entry stays readable
    lease { ttl = 30 seconds, renewEvery = 10 seconds }
    maxOpenPerThread = 1000            # open entries one tenant may have on one thread
    maxInputBytes = 4 MiB              # the longest queued input, its history included
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
the lease to lapse. An attempt a drain cuts short costs its entry nothing: the entry's `maxAttempts` rises by one
(in the in-memory, SQLite and PostgreSQL queues; a custom `RunQueue` still charges it), so a run longer than `drain.grace` survives any number of rolling deploys. A worker that
crashes gives nothing back, and that attempt does count: `maxAttempts` is what dead-letters an entry whose run crashes
its worker every time. Give the process `grace + exitTimeout` plus a margin to stop (Kubernetes'
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

**Abandon instead of recover.** A run whose worker died is recovered by the next worker to take its entry. If it
should not be - the tool call it died in would only hang again - cancel the entry, or have the thread's owner call
`agent.abandon(threadId, reason)` once the dead worker's claim has lapsed (while a worker runs it, the thread is
`ThreadBusy`). The thread is settled at once and is free for its next turn; the entry closes
`Failed(FailureReason.Abandoned)` when a worker next takes it, with nothing run again, even if the thread has taken
other turns by then, and the entries queued behind it run after it. See
[Tool Side Effects](index#tool-side-effects) in the agents guide.

**A message for a busy thread** - one with an open entry or a live run of the submitter's tenant - follows the
submission's `policy`. Another tenant's entries and runs on the same thread id are never reported: they do not make
the thread busy, an `Interrupt` goes ahead of its own tenant's entries only, and a ticket's `position` counts only its
own tenant's entries. A submission to a thread another tenant already owns is refused with `GraphError.TenantMismatch`.

| `BusyThreadPolicy` | What happens |
|---|---|
| `Reject` (default) | Refused with `GraphError.ThreadBusy`; the caller retries later. |
| `Enqueue` | Queued behind the thread's entries; runs after them, in submission order. |
| `Interrupt` | Queued ahead of them. The running queued run stops at its next superstep boundary: the model call in flight is cut, tool calls already started finish and keep their results, unstarted ones get a synthetic "interrupted" result, so the history stays valid for the provider. Its entry closes `interrupted` and the new message runs next. Cancelling the interrupting entry before it starts withdraws the stop, so the running one carries on. |

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
sit under the tool call that started them. The spans of the run's events - its model calls, tool calls and
`graph.*` and `agent.*` events - carry no message content, tool argument or tool result in either backend, and no
guardrail reason: a blocked run's failure names only the guardrail. Any other failure's error message is recorded as
it is, and the events that begin a run carry its `tenantId` and `principal`. The turn's messages are another matter:
OpenTelemetry records only how many there were, but Langfuse records them, as the trace's input and output and as one
span per message. [Agent tracing and metrics](../observability/agents#content) says how to drop them before they are
sent.

## Metrics

Give the worker and a `MeteredCheckpointer` the same `MetricsCollector` (`PrometheusMetrics` from
`llm4s-observability-prometheus`, or `MicrometerMetrics` from `llm4s-observability-micrometer`):

```scala
import org.llm4s.agent.metrics.MeteredCheckpointer
import org.llm4s.config.RuntimeConfigLoader
import org.llm4s.metrics.PrometheusMetrics

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
| Queue | `queue.wait`, `queue.depth`, `queue.oldest.seconds`, `queue.attempts`, `queue.plans`, `queue.deferrals`, `queue.lease.expiries`, `queue.lease.lapses`, `queue.lease.losses`, `queue.closed` (by outcome and reason; dead letters are `failed` / `attempts_exhausted`, abandoned runs `failed` / `abandoned`, guardrail-blocked turns `failed` / `blocked`) |
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
  ago, and reclaims closed queue entries older than `llm4s.agent.queue.ticketLifetime`. Entries are reclaimed even
  when the checkpoint sweep fails. A thread that cannot be pruned is logged and skipped, and tried again on the next
  pass.
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
  its runs drain. A cancel of such an entry whose input never ran is acted on at once. An entry for a thread another
  agent created (its graph id differs, not just its version) is closed `failed` (`Unrunnable`) as soon as it is taken.
- **A new store schema version or checkpoint format: full stop.** Stop every instance, then start the new release.
  The first new instance migrates the schema; an old instance that meets the newer schema refuses to open with
  `GraphError.UnsupportedStoreSchema` and writes nothing, and one still running fails each write with the same
  error, so a mixed fleet fails safe rather than corrupting threads.

The release notes and the [migration guide](../../reference/migration) say when a release changes either.
A store schema change does not roll: there are no expand-then-contract migrations, so every one is a full stop.

## Limits

- **One database.** The SPI is open to another store, but PostgreSQL is the only multi-machine store shipped.
- **No HTTP server product.** Endpoints, authentication, Agent Protocol, A2A and AG-UI belong to the service you build
  on the kernel; the example service shows one shape and authenticates nothing (its `X-Tenant-Id` header is not auth).
- **Not yet:** semantic search in the shared long-term store, cron and webhooks, versioned assistants, background
  subagents. See the [parity matrix](../../reference/agent-platform-parity) for where each went. (Encryption at rest is
  [here](durable-checkpointers#encrypting-what-the-store-holds): wrap the store, its queue and its long-term store
  on every instance, with the same keys.)

## See also

- [Durable Checkpointers](durable-checkpointers): claims, fencing, recovery, the PostgreSQL store, retention and the
  thread registry.
- [Streaming events](streaming): the event log a follower reads.
- [Agent platform parity](../../reference/agent-platform-parity): llm4s against LangGraph Platform and Deep Agents.
- The design records of the multi-machine kernel, §4.50 to §4.61, in
  [`docs/design/typed-agent-runtime`](https://github.com/llm4s/llm4s/tree/main/docs/design/typed-agent-runtime).
