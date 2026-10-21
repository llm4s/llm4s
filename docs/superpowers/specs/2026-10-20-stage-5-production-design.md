# Stage 5: one agent service on several machines, sliced

Stage 5 of #1266. Design record: `docs/design/typed-agent-runtime-design.md` §6 (row 5), §5.3 (the PostgreSQL adapter,
"do not queue", reversed here), §5.6 (tracing and metrics), and the Stage 5 rows of §4.9. Tombstone reclamation is
#2059. This document refines the stage and slices it, the way the Phase 4 spec
(`2026-10-19-phase-4-subagents-design.md`) sliced Stage 4.

## Intent

Stages 1-4 built an agent runtime that is durable on one machine: a thread's checkpoints and events are kept in one
SQLite file, a claim fences the run that holds a thread, and a crashed run is recovered when user code calls
`recover`. Stage 5 makes llm4s run as **one agent service on several machines that share threads through one
PostgreSQL database**:

- any instance can start, resume, stream, cancel or delete any thread;
- a run whose machine dies is picked up by another instance without user code noticing;
- a message for a thread that is busy is queued or interrupts the running turn, as the caller chooses;
- an operator can find threads, sees one trace for a run across machines, and has metrics for runs, claims and the
  queue;
- the database stays bounded: retention runs store-wide on a schedule, tombstones included.

**llm4s is a library first.** Stage 5 ships the *service kernel*: everything that touches the store's guarantees
(claims, fencing, the checkpoint format, the store contract suite). The HTTP server that exposes threads and runs is a
separate project built on the kernel; this repository ships only a small example service and a multi-instance
deployment of it, which is also the stage's exit test. (Decided 2026-10-20: the queue and worker must move with the
claim and fencing rules, so they live here; the HTTP layer is opinionated about web stacks and protocols, so it does
not.)

**Agent evaluation is not Stage 5.** The roadmap row's "full trace/evaluation integrations" splits: tracing and
metrics are here, because a multi-machine service cannot be run without them; the evaluation harness (fixed cases,
trajectory scoring, before/after comparison, CI gates; #410) becomes its own stage after this one (decided
2026-10-20). §6 gains that row.

### Goals

1. A PostgreSQL `Checkpointer` that passes `CheckpointerContract` unchanged, with versioned migrations that are safe
   when several instances start at once.
2. Commits made on one machine wake subscribers on every other machine promptly, without polling load growing with
   subscriptions times instances.
3. A thread registry: list and search threads by tenant, status, agent and metadata, and find runs whose claim has
   expired on an incomplete checkpoint.
4. A durable run queue and a worker that takes runs from it, recovers abandoned runs, and drains on shutdown.
5. Cancel from any machine, and a busy-thread policy per submission: reject (today's behaviour), enqueue, or
   interrupt.
6. Store-wide retention on a schedule, with tombstones reclaimed under a defined contract for readers of a reclaimed
   id (#2059).
7. The store, its connection pool, claims, watching, the queue and the worker configured from `application.conf`.
8. One trace per run across machines and child runs; metrics for runs, claims, takeovers, commits and the queue.
9. An example service and a deployment of three instances on one Postgres that survives one being killed mid-run.

### Non-goals (this stage)

- The HTTP server product (threads/runs endpoints, auth, Agent Protocol, A2A, AG-UI). A separate project; #1459's
  reference apps are its seed.
- The evaluation harness (its own stage, above).
- A shared Postgres `LongTermStore` for instruction and semantic memory: a follow-up once the Postgres module exists.
- Cron, webhooks, versioned "assistants", checkpoint encryption at rest, background (async) subagents, an external
  durability engine (Temporal, DBOS, Restate), compensating tool actions. Each is a later issue.
- Another database. The SPI stays open to one; Stage 5 proves Postgres.

### Anti-goals (failures even if it works)

- **A run lost or applied twice.** A submitted run may be *attempted* more than once (at-least-once), but its effect
  on the thread is applied at most once, and it is never silently dropped.
- **Infrastructure beyond Postgres.** No Redis, no message broker, no ZooKeeper: one database is the whole
  deployment.
- **A web framework or JDBC in a library module that does not need it.** `llm4s-agent` gains no HTTP and no JDBC
  dependency; core gains nothing agent-shaped (CLAUDE.md, Architecture rules).
- **A store call that can hang.** Every call stays within the documented bound (`Checkpointer.scala:76-83`), kept
  well inside `ttl - renewEvery`, also when the database is slow, partitioned or failing over.
- **A reader shown another thread's events as its own**, after a delete and a reuse of the id, before or after
  reclamation.
- **A shim.** Nothing is frozen before the MiMa baseline: SPI changes break, with a CHANGELOG entry and a migration
  note, and no `@deprecated` overload (CLAUDE.md, Status).

### Exit criteria (replacing §6 row 5's)

In `modules/it` (`@Docker` tier), three example-service processes share one Postgres:

- a run started through instance A is executed by whichever worker takes it, streamed live through instance B, and
  its events replay from any sequence number through instance C;
- instance A is killed (`kill -9`) mid-run; another worker recovers the run after its claim expires, and the
  stream continues without gaps or duplicates;
- a second message to the busy thread is queued under `Enqueue` and runs after the first, and under `Interrupt`
  stops the first at its next superstep boundary (started tool calls finish) and runs in its place, leaving a provider-valid history;
- a cancel sent through instance C stops a run executing on instance A;
- one trace covers the run across the two machines that executed it;
- a retention sweep reclaims tombstones past their age, and a reader of a reclaimed id gets the documented answer.

Every new module has build, `hooks/pre-commit`, docs, a coverage floor, a Codecov flag and CI upload, a migration
note and `modules/it` tier wiring (CLAUDE.md, Adding a module). A parity matrix (`docs/reference/`) compares the
result with LangGraph Platform and Deep Agents capability by capability, naming what is out of scope and where it
went.

## What exists today (checked against the code, 2026-10-20)

- **The SPI is ready for a networked store.** `Checkpointer` (`graph/Checkpointer.scala:112-281`) has claim, renew,
  release, an all-or-nothing `commit` checking the claim token, an optional fence on another thread's claim, the
  parent checkpoint (optimistic version) and consecutive event numbers; `latest`, `history`, `checkpoint`,
  `eventsAfter`, `awaitEventsAfter`, `children`, `compactEvents`, `prune(RetentionPolicy)` and a fenced
  `deleteThread` that leaves a tombstone kept forever. Its doc already names Postgres `LISTEN/NOTIFY` for
  `awaitEventsAfter` (:199-202).
- **The contract suite fits a database clock.** `CheckpointerContract` (`modules/agent-testkit`, ~2.5k lines) takes
  `newCheckpointer(clock)` and hooks `reopen`, `sibling`, `contenders`, `advanceStoreClock`, `exactExpiry` and
  `boundedStore`; the last three exist for a store whose server judges expiry. Cases may share one database if they
  use their own thread ids.
- **SQLite** (`modules/agent-checkpoint-sqlite`, ~1.4k lines): schema 6 in a version table with hand-rolled steps
  in one transaction, refusing a newer schema; checkpoint format 8 upgraded at read time; one connection, `BEGIN
  IMMEDIATE`, a per-file `WriteGate`; claims judged by the process's injected `Clock`; change notification by
  polling.
- **Claims work across processes; nothing drives them.** `ClaimPolicy` (ttl 30s, renewEvery 10s), takeover after
  expiry, `ThreadBusy` while live, tested across processes with `kill -9` (`SqliteCrossProcessSpec`). There is no
  queue, worker, scheduler or sweeper, and the SPI cannot list threads or find abandoned ones. Only user code calls
  `recover`; the only automatic recovery is a parent reattaching its children.
- **§5.3 says "do not queue"**: a second answer to a busy thread gets retryable `ThreadBusy`. Stage 5 reverses
  this (Decisions, 1).
- **Postgres in the repo**: `org.postgresql:postgresql` and HikariCP, declared per module; schemas created with
  `IF NOT EXISTS` and no version table (`PostgresMemoryStore`, `PgSchemaManager`); a transaction-scoped
  `pg_advisory_xact_lock` pattern; `create(dataSource, ownsDataSource)` for a shared pool. CI's `@Docker` tier runs
  a `pgvector/pgvector:pg16` service container; specs gate on `POSTGRES_*`.
- **Deployment**: `modules/deploy-service` is `/health` and `/llm-check` on cask; `deploy/` has kustomize overlays
  with one replica. No threads/runs API, no store-aware readiness, no claim release on SIGTERM.
- **Observability**: run events reach tracing as flat `graph.<event>` custom events; Langfuse makes one trace per
  `AgentRunEnded` with children linked only by metadata; OTel makes one parentless span per event. Core's
  `MetricsCollector` has no run, claim, queue or child-task metrics, and `llm4s-agent` does not use it.
- **Config**: `HarnessConfigLoader` reads `llm4s.agent.harness`; the checkpointer choice, `ClaimPolicy`,
  `WatchPolicy` and SQLite settings have no loader, and the SQLite module has no `reference.conf`.

## Placement

| What | Where |
|---|---|
| SPI additions: thread registry queries, `RunQueue`, the busy-thread policy, the store backend SPI | `llm4s-agent` (`org.llm4s.agent.graph`, `org.llm4s.agent.service`) |
| Worker, submission API, cancel requests, drain | `llm4s-agent` (`org.llm4s.agent.service`); no JDBC, no HTTP |
| Contract cases for the registry, the queue and reclamation | `llm4s-agent-testkit` |
| PostgreSQL store and queue | new `llm4s-agent-checkpoint-postgres` (`modules/agent-checkpoint-postgres`), on core, `llm4s-agent`, the Postgres driver and HikariCP |
| SQLite and in-memory implementations of every SPI addition | `llm4s-agent-checkpoint-sqlite`, `llm4s-agent` |
| OTel span tree | `llm4s-observability-otel` (`modules/trace-opentelemetry`); Langfuse nesting in `llm4s-observability` |
| Example service | `modules/deploy-service` (unpublished), `deploy/` overlays |
| Multi-instance exit test | `modules/it`, `@Docker` tier |

Core changes, if any, are core-neutral (no agent concept enters core) and are named in their slice.

## Slice map

Umbrella: #2129. Each slice has one issue, one design record (`docs/design/typed-agent-runtime/4.5x-stage-5-...`) and one PR.

**Store**
- **S1** #2130 PostgreSQL checkpointer: `llm4s-agent-checkpoint-postgres`, versioned migrations, passes the contract (4.50)
- **S2** #2131 Cross-machine change notification: `LISTEN/NOTIFY` behind `awaitEventsAfter` (4.51; after S1)
- **S3** #2132 Thread registry: thread metadata, list and search, incomplete threads (4.52; after S1)
- **S4** Store-wide retention and tombstone reclamation (#2059) (4.53; after S3)
- **S5** #2133 Store configuration: a backend SPI chosen in `application.conf`, pools and policies (4.54; after S1)

**Service kernel**
- **W1** #2134 Run submission and the durable run queue (4.55; after S3)
- **W2** #2135 Workers: take runs, recover abandoned ones, drain on shutdown (4.56; after W1)
- **W3** #2136 Cancel from any machine and the busy-thread policy: reject, enqueue, interrupt (4.57; after W2)

**Observability**
- **O1** #2137 One trace per run across machines and child runs (4.58; any time, W1 for propagation through the queue)
- **O2** #2138 Run, claim, queue and child-task metrics (4.59; after W2)

**Integration**
- **P1** #2139 Example service and a three-instance deployment (4.60; after W3, S5)
- **P2** #2140 Exit test, guide and parity matrix (4.61; after every other slice)

### Parallel lanes

1. **From the start:** S1; O1's span tree (not its queue propagation).
2. **After S1:** S2, S3 and S5 in parallel.
3. **After S3:** S4 and W1.
4. **After W1:** W2; then W3 and O2.
5. **Last:** P1, then P2.

The critical path is S1 → S3 → W1 → W2 → W3 → P1 → P2. S1 is reviewed first: every later slice runs its contract
cases against it.

## Rules every slice must satisfy

1. **Postgres decides time.** Claim expiry, retention age and queue leases are judged by the database's `now()`,
   never a host's clock, so skew between machines changes nothing. Retention compares that `now()` against a
   **server-recorded commit time** the store stamps on every checkpoint and event row in the commit's transaction,
   never against the producer-stamped `Checkpoint.createdAt` or event timestamps (which a skewed worker would make
   too old or too young); those stay as the producer's record. The contract tests a skewed producer explicitly.
   (In-memory and SQLite keep their injected `Clock`, which is also their commit time.) Durations are `FiniteDuration`, points `Instant` (CLAUDE.md, API rules).
2. **Every store call is bounded**: a statement timeout, a socket timeout and the pool's connection timeout, each
   set from the store's documented bound; a call that exceeds it fails, it never waits. A thread interrupt during a
   call returns or replaces the pooled connection, leaving the store usable (`Checkpointer.scala:208-218`).
3. **Fencing is never weakened.** Every write the queue, worker, registry or sweeper makes to a thread is fenced by
   that thread's claim (or, for a child, its parent's, as K1 #2045 fixed), or is a store-wide operation the contract
   names as claim-free (`prune`, `compactEvents`, reclamation).
4. **At least once, applied at most once; the queue entry is the unit of recovery.** A submitted run is one queue
   entry, and the entry, not a scan of threads, owns its recovery. Each attempt has its own `RunId` (a reused
   `RunId` names two runs, `GraphRuntime`), and the entry records it durably **before** the attempt touches the
   thread. An attempt decides what to do from whether any of the entry's recorded `RunId`s ever committed on the
   thread, never from an attempt count or from the thread's latest checkpoint alone (a direct run on the thread after
   an attempt died would replace the latest one). The `Checkpointer` answers this with a run-id lookup (the latest
   checkpoint committed by any of a set of `RunId`s on a thread; named in W1). If none committed, the input was never
   admitted (an earlier attempt died before its first commit), so the attempt starts it. If one did, the input was
   admitted: the attempt recovers the thread when that run's checkpoint is the thread's latest and `Running`, and
   otherwise closes the entry from the thread's state without running anything (its run reached rest, or the thread
   moved on through a direct `recover`, which applied the same input once). Recording before admission and
   classifying by run id closes the crash windows, so a submitted input is applied once however many attempts run,
   with no transaction spanning the queue and the commit. The lookup must not lose a committed run while its entry
   is open: the retention sweep never prunes a thread that has an open entry. The entry is the durable record of its
   runs and survives retention pruning older checkpoints once it is closed. A thread run directly, outside the queue, is never adopted by a
   worker: its owner calls `recover`, as today. (My call, from the builder check: it is what keeps a cancel
   sticking, rule 4's idempotency surviving pruning, and ticket waiters answered.)
5. **One run per thread.** The claim, not the queue, decides who runs a thread. Several queue entries for one
   thread run in submission order, one at a time.
6. **No new infrastructure** beyond Postgres, and no JDBC or HTTP dependency in `llm4s-agent` or core.
7. **The in-memory and SQLite stores implement every SPI addition** and pass the same contract cases, so a single
   machine and a test keep working with no database server.
8. **Contract first.** Each SPI addition lands with its `llm4s-agent-testkit` cases, run against every store.
9. **Tenants.** Every registry, queue and cancel operation is tenant-scoped as claims are: a request names another
   tenant's thread or run only to that tenant.
10. **Content stays out of durable rows** where events already keep it out (Phase 4 rule 9): queue entries hold the
    run's input, which is the caller's to store, but no metric, trace attribute or log line carries message content.

## Store slices

### S1: PostgreSQL checkpointer (4.50)

A new published module, `llm4s-agent-checkpoint-postgres`, with `PostgresCheckpointer` passing
`CheckpointerContract` unchanged.

- **Schema and migrations.** A version table and ordered steps, as SQLite's (`SqliteSchema`), applied in one
  transaction under a transaction-scoped advisory lock, so instances starting together migrate once; a database
  whose schema is newer than the build is refused with a typed error, as SQLite refuses one. No migration library
  (none is used in the repo).
- **Upgrades** (decided 2026-10-21). A rolling upgrade works across releases that change neither the schema nor the
  checkpoint format. A release that changes either needs every instance stopped before the first new one starts; the
  release notes and the deployment guide (P2) say so, and an old instance that meets the newer schema fails with the
  typed error above rather than corrupting anything. Expand-then-contract migrations, so that old and new builds can
  share a database, come with the 1.0 baseline, once the formats stop moving. The schema lives in a configurable Postgres schema (`llm4s` by default) so it can
  share a database.
- **Commit.** One transaction: lock the thread row (`SELECT ... FOR UPDATE`; a commit fenced by another thread's
  claim locks both rows in a fixed order, so two commits cannot deadlock), check the claim token and the fence, check
  the parent checkpoint, allocate the event numbers from the thread row's next number, insert, update. Tokens come
  from one store-wide sequence, so they increase across releases, takeovers and deletes. The isolation level and
  locking scheme are the builder's, provided the contract passes under real concurrent transactions (the contract's
  `contenders` cases, run with more contenders than SQLite needed).
- **Clock.** Claims and retention use `now()` (rule 1); the contract's `advanceStoreClock` / `exactExpiry` hooks are
  implemented for the IT database.
- **Pool.** HikariCP, owned or caller-supplied (`create(dataSource, ownsDataSource)`, as `PgVectorStore`).
- **Tests.** The contract runs in `modules/it` (`@Docker`, the existing pgvector service container); pure logic
  (statement building, schema steps) is unit-tested in the module. A cross-process `kill -9` case mirrors
  `SqliteCrossProcessSpec`.
- **Change notification** is polling in S1 (the SPI default); S2 replaces it.

### S2: cross-machine change notification (4.51)

`PostgresCheckpointer.awaitEventsAfter` wakes on commits made by any store over the same database: a commit
`NOTIFY`s its thread inside its transaction (so the notification is delivered only if it commits); one dedicated,
non-pooled listening connection per store fans notifications out to waiting callers. Lost or reconnected listening
connections fall back to polling under the caller's `WatchPolicy` until listening resumes, and a wake-up re-reads
from the store (a notification is a hint, never data). Load is one connection per store, not one per subscriber.
Contract: a commit through store A wakes a waiter on store B within a bound the slice names.

### S3: thread registry (4.52)

The SPI gains what an operator, a worker and a server need to find threads:

- **Thread metadata**: a small caller-set string map on the thread (set at start, changed with a fenced update),
  for searching, not for content.
- **List and search**: by tenant, status (idle, running, suspended, failed, completed, deleted), agent (graph id),
  metadata equality and updated-since, paged by keyset, newest first. Root threads by default; children through
  the existing `children`.
- **Incomplete threads**: threads whose latest checkpoint is incomplete and that no live claim holds, oldest first,
  for operators. It is not the worker's recovery query (rule 4: workers recover queue entries): a cancelled run,
  a drained one and a crashed one all look alike here, and only the queue entry says which should run again.

In-memory, SQLite and Postgres implement it; the contract gains cases. A store with many threads answers within the
call bound (indexed in Postgres and SQLite). Whether the status is stored or derived is the builder's.

### S4: store-wide retention and tombstone reclamation (#2059) (4.53)

A store-wide sweep, claim-free like `prune`, that applies a `RetentionPolicy` across threads (by age and count,
paged so each call stays within the bound) and reclaims tombstones older than a configured age.

**The reader contract for a reclaimed id** is the design work. Requirement: a reader of a deleted thread's log, or
of a new thread reusing the id, is never shown the new thread's events as a continuation of the old one, before or
after reclamation. Recommendation (the builder may choose another that meets the requirement): give each thread
incarnation a store-unique generation, carried in event cursors and `RunPosition`; a reader presenting a cursor of an
earlier generation gets `ThreadDeleted` while the tombstone exists and a typed "reclaimed" refusal after, never the
new thread's events. Child ids derive from the parent call's idempotency key, so a reclaimed child id is not reused
by a later call (#2059).

The sweep is a library call; W2's worker runs it on a schedule (one instance at a time, by advisory lock or a lease
row).

### S5: store configuration (4.54)

Choosing and configuring the store from `application.conf`, following the tracing backend pattern (CLAUDE.md,
Tracing and metrics): an SPI (`CheckpointerBackend`, a `class` in `META-INF/services`) that each store module
provides and that parses its own `llm4s.agent.checkpointer.<kind>` block; core-free, in `llm4s-agent`. Settings:
`llm4s.agent.checkpointer.kind` (`memory` | `sqlite` | `postgres`), each store's block (SQLite path; Postgres URL,
user, password through `${?VAR}`, schema, pool size and timeouts), and `ClaimPolicy` and `WatchPolicy`. Each store
module ships its `reference.conf` block and a `<Name>CheckpointerBackendSpec`. A missing backend module fails only
the load that names it, with an error naming the module.

## Service kernel slices

### W1: run submission and the durable run queue (4.55)

A `RunQueue` SPI and a submission API in `org.llm4s.agent.service`:

- **Submit** a start, a resume (answers) or a recover for a thread, with the agent's id, the tenant, a busy-thread
  policy (W3) and optional trace context (O1). It returns a ticket naming the entry and its queue position; the
  entry records each attempt's `RunId` as it starts (rule 4). `RunConfig.runId` already lets the worker choose
  each attempt's id.
- **Entries** are durable, per thread FIFO, with a lease (taken, renewed, expired), attempts and a terminal state
  (done, failed, cancelled). Taking an entry is skip-locked in Postgres so workers do not contend.
- **Wait and follow**: a caller waits for a ticket's terminal state or follows its run's events from any machine
  (through `awaitEventsAfter`).
- **Entry retention**: a terminal entry is kept for a ticket lifetime (configurable, a default named in the slice),
  then reclaimed, input and attempt history included, by the store-wide retention sweep (S4's, run by W2). An open
  entry is never reclaimed. Waiting on or following a reclaimed ticket returns a typed "ticket expired" answer,
  distinct from "no such ticket" only where the store can tell them apart (the slice says which). This keeps the
  queue inside the bounded-database goal.
- A `RunQueue` is implemented by the in-memory store, SQLite and Postgres, in the same database as the checkpoints;
  correctness rests on rule 4 (idempotency by `RunId`), not on a transaction spanning the queue and the commit, so the
  SPI stays separate from `Checkpointer`. (My call; a builder who finds the shared transaction simpler in all three
  stores may take it, keeping the SPIs separate.)
- **What an entry carries**: the queue runs `Agent`s and `TypedAgent[I, O]`s, the two entry points whose inputs
  have codecs (a `String` query with optional history, and `I`'s), and resume answers in their JSON form, as
  `resume` takes them. A raw `CompiledGraph` is not queued in Stage 5 (my call: its input type has no required
  codec).

### W2: workers (4.56)

An `AgentWorker` that runs queued runs on a `GraphRuntime`:

- **Agents by id**: the worker is built with the agents it can run; an entry naming another agent is left for a
  worker that can (or fails after a bound, named in the slice).
- **Concurrency**: at most N runs at once per worker; each run holds its thread's claim (renewed as today) and its
  queue lease.
- **Recovery**: a crashed worker's entries come back when their leases expire, and the worker that takes one
  recovers its thread (rule 4), so a run whose machine died continues without user code. The claim and the lease
  expire on the same database clock; a thread whose claim is still live is left for a later attempt.
- **Retention**: the worker runs S4's sweep on a schedule, one instance at a time.
- **Drain**: on shutdown it stops taking entries and lets running runs reach a suspension or their end within a grace
  period, then cancels the rest. A cancelled run's claim and lease are released only once the run has actually
  exited (cancellation is cooperative, and a provider or tool may ignore interruption), so another worker recovers it
  at once without overlapping its side effects. A run that has not exited by a second bound keeps its claim and
  lease; the process exits, renewal stops, and they expire after their ttl, as after a crash.
- **Health**: a readiness answer that checks the store and the worker's loop, for P1's probes.

### W3: cancel from any machine, and the busy-thread policy (4.57)

- **Cancel**: a durable cancel request for a run or a thread, visible to the worker that holds the claim (through S2's
  notifications, or with its claim renewal, at the builder's choice), which cancels the run as `AgentRun.cancel` does
  locally, waits for the run to actually exit, and closes the entry from how it ended: `cancelled` when the
  cancellation stopped it, otherwise the run's own outcome (a cancel that arrives while the run is already committing
  a completed, suspended or failed result is a no-op, `GraphRuntime`, and the ticket reports that result). Either
  way the entry is closed, so no worker runs it again. The thread stays as a local cancel leaves it
  (`Running`, recoverable by an explicit `recover` or a new submission). A cancel of a queued entry closes it
  before it runs.
- **Busy-thread policy** (reverses §5.3's "do not queue"; decided 2026-10-20), per submission:
  - `Reject`: today's `ThreadBusy`.
  - `Enqueue`: the entry waits behind the thread's running and queued entries (W1's FIFO).
  - `Interrupt` (decided 2026-10-21): the running run stops at its next safe point and the new entry runs next. The
    safe point is the next superstep boundary: the model call in flight is cancelled, tool calls that have already
    started run to completion and their results are kept, and tool calls that have not started are not started.
    The turn is then closed so the history stays provider-valid: every tool call without a result gets a synthetic
    "interrupted" result (the one-result-per-call rule of §5.3), and the new input follows. A tool call is never cut
    off mid-flight, so no side effect is half-recorded; the cost is that a long tool call delays the interrupt. The
    interrupting entry goes ahead of the thread's queued entries, which stay queued in their order behind it. The
    interrupted entry closes `interrupted`, not `cancelled`, and is not retried. This is the frontier
    edit #1750 asks for; W3 either builds on #1750 or closes it.
  - A pending interrupt (an approval or question) on the thread is not a busy thread: `start` stays refused while
    interrupts are pending (§5.3), whatever the policy; a resume is what answers it.
- §5.3's bullet is rewritten, with this decision in §9 (Decision log).

## Observability slices

### O1: one trace per run, across machines and child runs (4.58)

OTel spans form a tree, `invoke_agent` (the run) over model calls and `execute_tool` calls (names already aligned with
the GenAI conventions in D4 #2051), with each child run's span under the tool call that started it. Trace context is
carried in the child's origin and in queue entries (W1), so a run executed by another worker, or recovered on another
machine, continues its submitter's trace: the continued run's span takes the propagated context as its remote
parent, so it has the original trace id. A span link alone does not preserve the trace id and would split the run
into several traces, so links are only supplemental (for example, from a recovery attempt to the attempt it
replaces). Langfuse nests
child runs under their parent's trace instead of linking by metadata. No message content in attributes (rule 10).

### O2: run, claim, queue and child-task metrics (4.59)

Metrics for runs (started, ended by status, duration), claims (acquired, renewed, lost, taken over), commits
(latency, conflicts), the queue (depth, wait time, lease expiries, attempts) and child tasks (started, ended,
duration), exported through the existing Prometheus and Micrometer modules. Placement constraint: no agent concept in
core, and `observability-prometheus` / `observability-micrometer` keep their current dependencies unless the slice
justifies one; the slice chooses between a generic extension of `MetricsCollector` and an agent-side recorder the
backends adapt (the Phase 4 spec deferred "a `MetricsCollector` projection of agent events" here).

## Integration slices

### P1: example service and a three-instance deployment (4.60)

`modules/deploy-service` grows into a small example service on its existing cask stack: a handful of endpoints
(submit a run, follow its events over SSE from a sequence number, cancel, read a thread, list threads), an
`AgentWorker` in the same process, readiness from W2's health answer, and SIGTERM draining. `deploy/` gains a
Postgres-backed overlay with three replicas, database settings and secrets. It is an example, documented as such: the
HTTP server product is a separate project.

### P2: exit test, guide and parity matrix (4.61)

- The exit criteria above, as a `modules/it` `@Docker` suite running three service processes on one Postgres.
- A guide, "Running agents on several machines" (`docs/guide/agents/`), including how to upgrade (S1's rule: rolling
  within one schema and checkpoint format, otherwise a full stop), and the durable-checkpointers guide updated.
- The parity matrix (`docs/reference/`), from the 2026-10-20 framework survey: capability, LangGraph Platform, Deep
  Agents, llm4s, and where each gap went (a later stage, an issue, or out of scope).
- `typed-agent-runtime-design.md` §6: row 5 updated and marked done; a new row for the evaluation stage.

## Breaking changes (pre-1.0, no shims, one migration note)

Expected: `Checkpointer` gains registry, reclamation and run-id methods (every store implements them); event cursors
and `RunPosition` may gain a generation (S4); §5.3's
busy-thread behaviour becomes a policy (W3, default `Reject`, so existing callers see no change). Each breaking slice
adds its CHANGELOG line under `### Breaking changes` and its section under a "Production adapters (Stage 5)"
heading in `docs/reference/migration.md`.

## Stability tiers

New top-level public types are `@Experimental` (Beta) for this stage; members added to an existing type
(`Checkpointer`) take that type's tier. `sbt stabilityTierCheck` enforces the annotation where a module is frozen.

## Decisions (2026-10-20)

1. **Busy threads may queue or interrupt** (the user, 2026-10-20), reversing §5.3's "do not queue"; `Reject` stays
   the default.
2. **The HTTP server is a separate project**; this repository ships the service kernel and an example (the user,
   2026-10-20).
3. **Evaluation is its own stage** after Stage 5 (the user, 2026-10-20).
4. **Hand-rolled migrations**, not Flyway or Liquibase (my call: the repo uses neither, and SQLite's scheme works).
5. **Separate `RunQueue` SPI**, correct by `RunId` idempotency (my call; W1).
6. **The database clock** for claims, leases and retention in Postgres (my call; rule 1).
7. **The queue entry owns recovery** (rule 4); workers never adopt threads run outside the queue, and a cancel
   closes its entry (my call, from the builder check).
8. **The queue runs `Agent` and `TypedAgent`**, not raw graphs (my call; W1).
9. **Rolling upgrades only within one schema and checkpoint format** (the user, 2026-10-21); a release that changes
   either is a full stop, and expand-then-contract migrations come with the 1.0 baseline (S1).
10. **`Interrupt` stops at the next superstep boundary** (the user, 2026-10-21): started tool calls finish, unstarted
    ones get synthetic "interrupted" results, the in-flight model call is cancelled, and the interrupting entry runs
    ahead of the thread's queued entries (W3).
11. **Deferred, as later issues**: a Postgres `LongTermStore`, cron, webhooks, versioned assistants, encryption at
   rest, async subagents, A2A, AG-UI, an external durability engine, compensating tool actions (my calls).

## Delivery

As Phase 3 and Phase 4: an umbrella issue for Stage 5 with one issue per slice; subagents implement one slice per PR
in a worktree; the main session merges (squash) when Codex is clean on the head SHA and CI is green; review findings
are fixed at their root, and fixes to a merged slice go to its own follow-up issue. The spec PR merges before the
first slice's; slice branches drop the spec commits by rebasing onto `main`.
