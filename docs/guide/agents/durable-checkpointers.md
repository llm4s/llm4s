---
layout: page
title: Durable Checkpointers
nav_order: 6
parent: Agents
grand_parent: User Guide
---

# Durable Checkpointers
{: .no_toc }

Share one checkpoint store between runtimes and processes, recover a run whose process died, inspect, fork and
correct a thread's checkpoint history, prune it, and test a store of your own against the contract every store
must meet.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## What a checkpointer holds

Every agent turn and every graph run executes on a `GraphRuntime`, which stores its threads in a
`Checkpointer` (`org.llm4s.agent.graph`). For each thread the store keeps:

- its checkpoint history, as versioned JSON: each checkpoint is the graph's state and what was left to run at
  one superstep boundary, and the latest says what happens next;
- the pending writes of tasks that finished in the latest checkpoint's superstep, so `recover` does not run them
  again;
- the durable event log, numbered `1, 2, 3, ...` per thread;
- the **claim** of the run that holds the thread, if one does.

Two stores ship with LLM4S:

- `InMemoryCheckpointer`, the one `GraphRuntime.inMemory()` and `Agent.builder` use. Its threads live as long as
  its instance.
- `SqliteCheckpointer`, in `llm4s-agent-checkpoint-sqlite`: one SQLite file that survives a restart and that
  processes on one host can share. See [The SQLite store](#the-sqlite-store).

Both meet the same contract, described below.

## Runs claim their thread

Several `GraphRuntime`s may share one store, in one process or many. A thread may still run only one run at a
time, and the store is what enforces it. Before a run does anything, it asks the store for a **claim**: a lease
on the thread with an expiry and a **fencing token**. While the run executes, it renews the claim, and it
releases the claim when it ends.

```scala
import org.llm4s.agent.graph.*
import scala.concurrent.duration.*

val store = InMemoryCheckpointer()

// two runtimes over one store - in a real deployment, one per process
val a = GraphRuntime(store, claims = ClaimPolicy(ttl = 30.seconds, renewEvery = 10.seconds))
val b = GraphRuntime(store)
```

`ClaimPolicy` sets how long a claim lasts (`ttl`, 30 seconds by default) and how often a running run renews it
(`renewEvery`, 10 seconds by default, counted from the moment the claim is granted; it must be shorter than `ttl`).
The store judges expiry by its own clock, and the run judges its own lease too (see
[Fencing](#fencing-a-run-that-lost-its-claim-stops-and-records-nothing) below).
When the store has a single clock - a database server's, or one process's - the runtimes need not agree on the
time. An embedded store that each process opens itself, such as SQLite on a shared file, judges expiry by the
clock of whichever process grants or renews the claim, so clock skew between hosts shortens or lengthens the
effective `ttl`. Fencing (below) still keeps every write safe; skew costs liveness - a live run taken over early,
a dead one held longer - and can repeat side effects. Keep hosts' clocks synchronised.

`ClaimPolicy` can also carry a `label` naming the runtime to an operator - a host, pod or process name:
`ClaimPolicy.default.withLabel("worker-7")`. The runtime then logs the run id with it, at INFO, whenever it is
granted a claim (`Run 'r1' (runtime 'worker-7') claimed thread 't' with token 1`), and in the WARN lines for a
failed renewal or a lost claim, so the run a `ThreadBusy` names can be traced to the runtime holding it. Without a
label the grant is logged at DEBUG. The label goes nowhere else: not to the store, and never into an error another
caller - another tenant's included - gets.

While one run holds the thread, every other `start`, `recover`, `resume`, `deleteThread` or `updateState` on it,
and every `fork` onto it as the target, from any runtime, is refused with `GraphError.ThreadBusy`, which names the
holding run:

```scala
b.recover(threadId, graph) match
  case Left(GraphError.ThreadBusy(_, latestCheckpoint, GraphError.BusyHolder.Run(holder))) =>
    // run `holder` is live: retry once it has suspended or completed
  case other => ()
```

A refusal up front accepts and discards nothing, so `ThreadBusy` is a `RecoverableError`: the caller retries, and generic retry
code (`RetryPolicy`, `ErrorRecovery`) does too. `holder` is a `GraphError.BusyHolder`:

- `Run(runId)`, the holding run, when it is of the caller's tenant: a run of the same runtime with the caller's
  tenant, or a claim the store recorded for the caller's tenant - each claim records its run's
  `RunConfig.tenantId` - and the thread's latest checkpoint, if it has one, is the caller's tenant's too;
- `Unnamed`, when another run holds the thread but cannot be named: its claim is another tenant's, or recorded no
  tenant (a claim a SQLite file held when it was upgraded to schema 5), or a change's write was refused as stale
  naming only the claim that replaced it;
- `Lapsed`, when it is the call's own claim that was lost - a `fork`, `updateState` or `deleteThread` whose lease
  passed, or whose store named no current claim, before its write was known to land - and no other run is known
  to hold the thread. A write not yet sent was never applied, but one already in the store when the loss was found
  may still land, fenced by the token: read the thread before retrying. A retry can still be refused, by the
  call's own claim until its release lands or by a run that took the thread over meanwhile.

## Recovering a run whose process died

A process that dies stops renewing its claims. Once a claim has expired, another runtime can take the thread
over: `recover` there continues the run from its last checkpoint, reusing the pending writes of the tasks that
had finished, so they are not run again.

```scala
// process B, after process A died mid-run: refused until A's claim expires, then admitted
b.recover(threadId, graph).flatMap(_.await())
```

So `recover` tells a live run from a dead one by its claim. It does not wait: until the dead run's claim
expires - at most `ttl` after its process stopped renewing - `recover` is refused with `ThreadBusy`, and you
retry. `resume` and `start` are refused the same way while another runtime holds the thread.

A `recover` whose claim replaced another run's expired, unreleased claim records that run's id on its
`RunEvent.RunRecovered` event, as `takenOverFrom`, so the thread's log shows the takeover. That run may have been
paused rather than dead, and still executing. It is recorded even when it is the recovering run's own id - a
`RunId` reused with `RunConfig.withRunId` - since the expired claim was still another, earlier run's;
`takenOverFrom` is `None` when the thread was free. It is `None` too when the claim replaced was another tenant's,
or recorded no tenant: the log belongs to the thread's tenant, and never names another tenant's run. A thread's
id can be claimed by another tenant's run - one admitted before the thread existed, say - and such a claim can lapse
unreleased; the owner's recovery then replaces it without naming it.

So a run of another tenant is never named to the thread's tenant: not by `ThreadBusy`, also in the moment between
its claim and its first checkpoint, not by `takenOverFrom`, and not by `GraphError.ClaimLost`, whose `current` is
only the fencing token of the claim that replaced the run's, a per-thread counter that names no run or tenant.

`recover` reads the dead run's pending writes again once its own claim is granted, so a task that finished in
the last moments before the takeover is reused, not run again.

## Fencing: a run that lost its claim stops and records nothing

A process that was only paused - a long GC, a frozen VM - may wake up after another runtime took its thread over.
Every commit a run makes carries its claim's token, and the store refuses any commit whose token is not the
thread's current claim, with `GraphError.StaleClaim`. Such a run therefore cannot record anything after its
successor started, and the thread's state and event log are its successor's.

A run does not wait for a refused commit to learn that its claim is gone. It judges its own lease: the lease ends
`ttl` after the moment, by the runtime's monotonic clock (`System.nanoTime`), just before it sent the request that
granted or last renewed the claim. The store started its own `ttl` no earlier than that, so the run's lease ends no
later than the store's - provided the store judges expiry by one clock that does not run faster than the runtime's
monotonic clock and is not stepped forward. A constant offset between the two clocks does not matter. A store clock
that runs fast or is stepped forward (an NTP step on `Clock.systemUTC()`), or a store whose processes each judge
expiry by their own skewed clock (SQLite shared between processes), can expire the claim while the run still judges
it live, so another runtime can take the thread over early. The run then learns of it from its next refused renewal
or commit - within `renewEvery` - and stops; fencing keeps every write safe, but tasks it was already running can
repeat side effects (see [Limits](#limits)). A heartbeat that never waits on the store checks the lease,
so a run whose renewals keep failing, or hang - its store unreachable, or partitioned from it - still notices. A
renewal that fails, or a store clock that throws, is retried at the next beat while the lease lasts.

The run finds its claim lost when its lease passes with no renewal landing, when a renewal is refused with
`StaleClaim`, or when a commit is. It then stops at once, in every durability mode:

- its running tasks are interrupted, as `cancel` interrupts them;
- it commits nothing more - not even `RunFailed` - and drops what it had not yet written: the queue of
  `Durability.Async` and the buffer of `Durability.OnExit`. From the moment the loss is found, a commit the run
  has not yet begun is skipped, even one of its outcome while it is finishing. A commit begins, or is skipped, in
  one atomic step with that check, so the only commits of the run that can reach the store after the loss are ones
  that had already begun when it was found. An `Async` commit already in the store is waited for
  at most `ttl`; one still there then is left to finish on its own. It is fenced by the run's token, so it lands
  only if nobody has claimed the thread since. If it lands, it lands after the run has ended: its events are an
  orphan of the run, not part of it. They are in the thread's log, and reach the thread's subscribers in sequence
  and before any later run's events, but never the run's own listeners or its tracing (see [Limits](#limits)).
  The same holds for a commit that has landed but that this runtime has not yet handed to its subscribers, because
  it is still reading, from the store, events committed elsewhere that one of them has not seen yet: a commit
  reaches every subscription of the thread at once, or none, so all of them, and a replay, agree on it;
- it releases its claim, which is a no-op if another run holds the thread. It does not wait for a renewal that
  may still be in the store, and the release runs on a thread of its own, waited for only until `2 × ttl` after
  the loss, so the run's result and the thread's release in this runtime never wait on a store that hangs; a
  release that never lands lets the claim lapse after `ttl`;
- it ends with `GraphError.ClaimLost(threadId, token, current)`, where `current` is the thread's current claim
  when the store named one;
- its end is bounded whatever it was doing: `await()` returns, and the thread is free in this runtime, within
  `2 × ttl` of the loss being found, plus only local work that never calls the store. The loss is found when the
  lease passes - at most `ttl` after the last renewal that landed was sent - or when a renewal or commit is refused,
  so the bound is at most `3 × ttl` after the last renewal that landed. If the run has not begun to end by
  `2 × ttl` after the loss - its thread stuck in a store call that ignores interrupts, such as a `Sync` commit,
  `OnExit`'s exit commit, or the commit of its outcome, which no stop interrupts - it is ended without its thread:
  it fails with `ClaimLost`, its thread is freed in this runtime and its claim released as above. When the stuck
  call does return, it changes nothing: the run has its one result, and its thread is not freed again. A write it
  makes that lands then is an orphan, as above. The same bound holds while the run is admitted: if the commit
  that claims the thread for `start`, `recover` or `resume` hangs in the store and the claim is found lost
  meanwhile, the call returns `ClaimLost` within `2 × ttl` of the loss, with the thread freed and the claim
  released as above. The run never starts. If that commit lands later, it is fenced by the token: once the
  release has landed, or another run has taken the thread over, the store refuses it. But one that lands first -
  the store still holds the claim, since nobody took it over - leaves the admission's `Running` checkpoint and its
  `RunStarted` (or `RunRecovered` or `RunResumed`) event in the thread although the call returned `ClaimLost`, and
  no run continues it: `recover` does, once the claim is released. A claim's commit that the store refuses as stale - the claim lapsed, or another runtime took it over,
  before it landed - is a loss found then: the call returns `ClaimLost`, never `CheckpointWriteFailed`, with the
  claim released and the thread freed. If the heartbeat declared the loss meanwhile, the call still returns one
  `ClaimLost`, naming the current claim the first of the two to find the loss named.
  `fork`, `updateState` and `deleteThread` are bounded the same way while they hold their claim: if it is found
  lost while the change waits on the store - its next call queued behind a hung renewal in a store that serialises
  its calls, say - the call returns `ThreadBusy` naming no checkpoint within `2 × ttl` of the loss, with the thread
  freed in this runtime and the claim released as above. A change is not a run, so it never returns `ClaimLost`:
  retry it once the thread is free. A change's write - its commit, or the store delete - that has not begun when
  the loss is found never reaches the store, and the change returns `ThreadBusy` as for a commit the store refuses
  as stale. A write the store refuses as stale is itself a loss found then, as a run's is: the change returns
  `ThreadBusy` within `2 × ttl` of it, and gives its claim back as above, without waiting on the store. One already
  in the store may still land, fenced by the token. A `deleteThread` that lands after its
  call was given up on ends this runtime's subscriptions to the thread at once only if the call has not yet freed
  the thread; otherwise they learn of the delete from the store, as another runtime's do (see
  [Deleting a thread](#deleting-a-thread)).

`ClaimLost` is not a store outage (`CheckpointWriteFailed`). After it, `recover` may be attempted once the thread
is free:

- while a successor holds the thread, `recover` is refused with `ThreadBusy`: retry later;
- if a successor took over and finished the work, the thread's state shows it, and `recover` reports
  `NothingToRecover` (or the thread is suspended or completed, as the successor left it);
- if the lease merely lapsed - renewals failed until it passed, and nobody took the thread over - the run released
  its claim and its last durable checkpoint is still `Running`, so `recover` continues it as after a crash.

### Limits

- A stale worker can still act until it notices, and while its tasks see their interrupt. The loss is noticed as
  the run's own lease passes, or as soon as a renewal or commit is refused - within `renewEvery` of a takeover that a
  store clock running ahead allowed early. A task already
  running then may finish an external side effect before it checks its interrupt, and a tool that ignores
  interrupts runs to its end. Such a side effect can happen in both the stale run and its successor, concurrently.
  That is the at-least-once boundary every run has: make such tools idempotent. A tool or node that writes to a
  system able to fence can pass the claim's token on: it is `context.position.fencingToken` in a node and
  `context.run.position.fencingToken` in a tool, and it grows with every claim, so the system can refuse a token
  lower than the highest it has seen.
- Until the loss is noticed, such a run's live progress events (`StreamEvent.Live`, which are never stored) still
  reach the subscribers of its own runtime; from then on its tasks send none, even a task that ignores the interrupt
  and outlives the run. Its durable events are refused or dropped with its commits. A live event carries its run's
  claim token (`StreamEvent.Live.token`), so a subscription that has been handed a newer claim's commit drops an
  older claim's progress, and a run's observer and its run-scoped streams never deliver another claim's, even under
  the same `RunId`.
- A `ClaimLost` run's own event stream - `AgentRun.subscribe`, `Agent.stream`, its observer and its tracing - ends
  at the run's end, which never waits on a hung store. An `Async` write the run abandoned may still land after
  that; it is then visible only through the thread's log - a thread subscription (`GraphRuntime.subscribe`) or a
  replay - never as one of the run's events, even to a run-scoped subscription made later that replays the run.
  That holds for the next run on the thread too, even if it reuses the lost run's `RunId`: a run's own events are
  those committed under its claim's fencing token (`EventRecord.token`), so a late write that lands while it is being
  admitted is never delivered to its observer, its stream or its tracing.
- A run that has begun committing its outcome finishes that commit: a loss found during it does not interrupt it.
  The commit is still fenced, so it lands only if nobody has taken the thread over. The run's end does not wait for
  it past `2 × ttl` of the loss: if the commit returns first the run ends with its outcome (or `ClaimLost`, if the
  store refused it), and otherwise with `ClaimLost`.
- Commits on one thread are written one at a time, in this runtime, so that subscribers see them in order. A commit
  hung in the store holds up later commits on its thread - including a new run's claim commit there, until the
  store returns, which the store's own bound on a call limits (see [When the store hangs](#when-the-store-hangs)) -
  but never another thread's. A subscription's read of the store that hangs holds up only that
  subscription, and, for a read of events another runtime committed before this runtime's commit, that thread's
  commits: never another thread's runs, their admission or release, nor a lost run's bounded end.
- A process that dies holds its threads for up to `ttl`; so does a store that fails to release a claim - except to
  its own runtime's `deleteThread`, which tries the release once more first, so `Agent.run(query)` can still forget
  the thread of a failed turn. A run that ends with its claim still held waits up to `ttl` for a renewal still in
  the store, so that none lands after its release; if the renewal is still there then, the run releases on a thread
  of its own without waiting, and its result is set at once. A run whose claim was lost does not wait for its
  renewal at all. `fork`, `updateState` and a `deleteThread` that did not delete release the claim they hold the
  same way, so a renewal hung in a store that serialises its calls cannot keep them from returning.

## When the store hangs

Every store must return from each call within a bound it documents, and fail a call that reaches it, changing
nothing ([#1919](https://github.com/llm4s/llm4s/issues/1919)). `SqliteCheckpointer`'s is twice its `busyTimeout`,
plus the time its own SQL takes (see [The SQLite store](#the-sqlite-store)); `InMemoryCheckpointer` never waits on
anything but its own brief lock. A store's bound should be well inside `ttl - renewEvery`, the time a renewal has
to land, so that a slow call never costs a run its claim.

The runtime bounds two waits itself, both by `2 × ttl`, so that a store that breaks the contract cannot hold a
run's result or its thread:

- **A run that lost its claim** ends within `2 × ttl` of the loss, whatever it was doing, with `ClaimLost` (see
  [Fencing](#fencing-a-run-that-lost-its-claim-stops-and-records-nothing)). So does an admission, with `ClaimLost`,
  and a `fork`, `updateState` or `deleteThread`, with `ThreadBusy`.
- **A run's closing wait while it holds its claim.** An `Async` run waits for its queued commits before it returns,
  and an `OnExit` run makes its one exit commit. If a commit stays in the store for `2 × ttl` with none returning,
  the run ends with `GraphError.CommitTimedOut(threadId, after)`, without waiting for it: `await()` returns, its
  thread is free in this runtime, and its claim is released on a thread of its own, which nothing waits for - a
  store stuck on a commit may serialise the release behind it. A drain that keeps making progress is never cut
  short, however long it takes in all. The commit is an orphan, as a lost run's late write is: it is fenced by the
  run's claim, so once the release lands the store refuses it, but it can land first. Its events then reach the
  thread's subscribers, never the run's own listeners or its tracing. So after `CommitTimedOut`, read the thread:
  its latest checkpoint is either the run's last durable one, still `Running` - `recover` continues the run - or the
  run's outcome.

  The bound is twice `ttl` rather than a setting of its own. A store that keeps claims alive already returns well
  inside `ttl`, since a renewal must land within `ttl - renewEvery` and a store that serialises its calls queues
  renewals behind its commits; the runtime's bound is a backstop for a store that breaks the contract, and the same
  `2 × ttl` that bounds a lost run's end.

Every other call made while the claim holds - an admission's claim commit, a `Sync` run's commits, its outcome's
included, a change's write, and the release of a claim that was not lost - is waited for as long as it takes:
only the store's bound limits it. Nor can the runtime free what a call stuck in the store holds itself. While the
call lasts:

- **its thread stays alive.** A run thread, or the virtual thread on which an admission, `fork`, `updateState` or
  `deleteThread` makes its store calls, stays inside the call after the runtime has given it up - and an `Async`
  run's writer thread with it. Only the run's result, its slot and its claim are freed;
- **it holds up the thread's later commits.** Commits on one thread are written one at a time in a runtime, so a
  commit, or a hand-over's read of events another runtime committed, holds up every later commit on that thread
  in that runtime until it returns - a new run's claim commit included - but never another thread's;
- **it can hold up subscriptions.** A `GraphRuntime.subscribe` made while a run's claim commit is in the store, on
  a thread with nothing of that run handed over yet - an admission given up on after a lost claim included - waits
  for that commit to return, or to be cancelled, before it delivers its replay; a run-scoped subscription
  (`RunHandle.subscribe`, `AgentRun.subscribe`) never does. And with the store watch disabled
  (`WatchPolicy.disabled`), a replay made while a write a run gave up on is still in the store stops at the first
  event another runtime committed after what this runtime has handed over; it gets the rest when this runtime
  next commits on the thread, and a replay made once the write has returned has them all.

## Deleting a thread

`GraphRuntime.deleteThread(threadId, config)` (`Agent.forget`) claims the thread, checks its tenant under that
claim, and then has the store delete it with the claim's token, renewing the claim while the store works, as a run
does. The store's delete is fenced like a commit: it
deletes only while the token is the thread's current claim, atomically. So a runtime that stalls past its claim's
`ttl` between the claim and the delete - a long GC pause, a slow store - while another runtime takes the thread over
deletes nothing of that run: the call returns `ThreadBusy` (or `TenantMismatch`, if the run is another tenant's).

A deleted thread's id names a new thread, but the new thread does not reuse the old event numbers: it numbers its
events on past the deleted thread's, one number skipped. A subscription follows a thread, not its id, so a
subscription to the deleted thread ends with `Disconnected(lastSeq, DisconnectReason.ThreadDeleted)` once it has
delivered what it had queued, and never delivers the new thread's events as if they continued the old log:

- in the deleting runtime, every subscription made before `deleteThread` returns - before the delete, or while it
  is in flight - ends at once;
- in another runtime sharing the store, a subscription ends when it next reads the store: when its watch next polls,
  or, with `WatchPolicy.disabled`, only when its own runtime next commits on the thread;
- in another runtime, a subscription that has delivered no event yet - still at `afterSeq = 0`, as one to a thread
  that never numbered an event always is - cannot be told from a new subscription, so it follows the new thread
  from its first event instead of ending - if the new thread is its tenant's; otherwise it ends with
  `TenantMismatch` before that event ([Tenants](streaming#tenants)).

Subscribe again from `afterSeq = 0` to follow the new thread from its first event.

There is no unfenced delete: to delete a thread outside a runtime, claim it and delete with the claim's token.

### Limits

- A deleted thread that numbered any event leaves a small record in the store for good: its next event number, and
  nothing of its data. It is what lets a reader of the deleted log - in any process, however late - be told that the
  thread was deleted, and a new thread of the id be numbered past it. Nothing removes it, `prune` included, so a store
  grows by one such record per deleted thread; `Agent.run(query)` forgets the thread of every failed or cancelled
  one-shot turn, so a long-lived store gains one per such turn. Deleting the same id again, with no event since,
  adds nothing.

## Subscriptions across runtimes

A subscription sees every commit on its thread, made through any runtime or process that shares the store, not
only through its own: durable events in `seq` order, without gap or duplicate, as for its own runtime's commits.
(Where another runtime's commit falls among this runtime's live events is not fixed: it depends on whether the
subscription's initial replay, its store watch or a hand-over's gap read delivers it first; see
[Ordering across runtimes](streaming#ordering-across-runtimes).) Each live subscription
watches the store with `Checkpointer.awaitEventsAfter`. A store that is told of commits returns at once. A store that
is not, such as SQLite, is polled every `WatchPolicy.pollInterval` (250 ms by default):

```scala
val runtime = GraphRuntime(store, watch = WatchPolicy(pollInterval = 100.millis))
```

Only durable events cross runtimes; live progress is never stored. A thread deleted through any runtime ends its
subscriptions with `ThreadDeleted`, with the exceptions [Deleting a thread](#deleting-a-thread) lists. See
[Subscriptions across runtimes](streaming#subscriptions-across-runtimes) for the delivery and back-pressure rules.

## The SQLite store

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent-checkpoint-sqlite" % llm4sVersion // same version as llm4s-agent
```

`SqliteCheckpointer` keeps every thread - its latest checkpoint, pending writes, event log and claim - in one
SQLite database file. Open it with a path, hand it to a `GraphRuntime` (or to `Agent.builder` through
`withRuntime`), and close it when the process is done with it:

```scala
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.sqlite.SqliteCheckpointer
import java.nio.file.Path

for
  store   <- SqliteCheckpointer.open(Path.of("/var/lib/my-app/runs.db"))
  runtime  = GraphRuntime(store)
  handle  <- runtime.start(threadId, graph, input)
  result  <- handle.await()
yield result
```

`open` creates the file if it does not exist (its directory must), and brings it to the current schema version.
Any number of stores may open one file at the same moment, also a new one: each waits up to `busyTimeout` for the
others. The path may hold any character the file system allows, `?` and spaces included. It must be on a local
disk: a Windows UNC path (`\\server\share\runs.db`) becomes a `file://server/share/runs.db` URI, which the
native libraries bundled with sqlite-jdbc refuse (they are built without `SQLITE_ALLOW_URI_AUTHORITY`), and WAL
does not work on a network file system anyway (see [Sharing one file between processes](#sharing-one-file-between-processes)).
An open interrupted while it waits for a busy file returns a `CancelledError` and closes its connection.
`SqliteCheckpointer.open(path, clock)` and `open(path, clock, config)` take the clock claims expire by and a
`SqliteCheckpointerConfig`.

### Restart and recovery

Everything a run commits is in the file, so a process that stops - crashes, is killed, or is redeployed - loses
only the work in flight. A new process opens the same file and continues:

```scala
// after a restart: the same file, a new store, a new runtime, the same graph
for
  store  <- SqliteCheckpointer.open(Path.of("/var/lib/my-app/runs.db"))
  events <- store.eventsAfter(threadId, 0L, 100)     // the stopped run's event log, as it was committed
  handle <- GraphRuntime(store).recover(threadId, graph)
  result <- handle.await()
yield result
```

`recover` reuses the pending writes of the tasks that had completed, so they are not run again; only the tasks
that were running or not yet started run. A run that was killed still holds its claim until the claim expires
(`ttl`, 30 seconds by default), so `recover` is refused with `ThreadBusy` until then; retry it. A run that
failed - a node returned `NodeResult.Fail` - released its claim as it ended, and `recover` is admitted at once.
Event numbers continue where the stopped run's log ended: none is reused, and a subscriber that had seen events
`1..n` resubscribes with `afterSeq = n`.

The sample `org.llm4s.samples.durable.SqliteRestartRecoveryExample` shows the whole sequence:
`sbt "samples/runMain org.llm4s.samples.durable.SqliteRestartRecoveryExample"`.

### Sharing one file between processes

Several stores - in one process or in several on the same host - can open one file, each with its own connection,
and the runtimes over them exclude each other through claims exactly as runtimes over one store do. Every write is
one `BEGIN IMMEDIATE` transaction, in which the claim's token and the thread's version are checked and the commit
applied, so two stores cannot both pass the checks.

- **WAL mode.** The store switches the file to SQLite's write-ahead log. Readers - `latest`, `eventsAfter`, a
  subscriber's replay - never wait for a writer, and a writer never waits for readers. WAL needs memory shared
  between the processes that open the file, so keep the file on a local disk, not on a network file system.
- **One writer at a time.** SQLite admits one writing transaction per file. The store's writes are short, so a
  write that finds another in progress waits for it - up to `SqliteCheckpointerConfig.busyTimeout`, 5 seconds by
  default - and only then fails, with a `ProcessingError` and nothing changed. Raise it if many processes write to
  one file at once: `SqliteCheckpointer.open(path, clock, SqliteCheckpointerConfig(busyTimeout = 15.seconds))`.
- **Writes take turns within a process.** SQLite does not queue a waiting writer. It sleeps and tries again, and
  gets the lock only if it wakes between two of the other connection's transactions. A store beside one that
  commits back to back could therefore wait out its whole `busyTimeout`, even though each write was short. The
  stores in one JVM that open the same file take turns through a fair lock for the file, first come, first served,
  before each write. A write waits for the writes queued ahead of it, up to `busyTimeout`, and then up to
  `busyTimeout` again for SQLite's lock if another process holds it. Writes from different processes are still
  ordered by SQLite's polling, so a process that writes steadily can starve another process's write. Raise
  `busyTimeout` there. A write interrupted while it waits for its turn returns a `CancelledError`.
- **Every call is bounded.** A call waits for the store's own earlier calls, and a write then for its turn at the
  file, up to `busyTimeout` from the call's start, and a write then up to `busyTimeout` again for SQLite's lock: so
  every call returns within `2 × busyTimeout`, plus the time its own SQL takes, failing with a `ProcessingError` and
  nothing changed once it reaches a limit. That SQL itself cannot be timed out: sqlite-jdbc's native calls ignore
  interrupts, so a disk that stalls inside a write holds that call, and the store's later calls fail once they have
  waited `busyTimeout` for it. With the default 5 seconds and the default `ttl` of 30 seconds, the bound is well
  inside `ttl - renewEvery`; keep it so if you raise either (see [When the store hangs](#when-the-store-hangs)).
- **Durable commits.** `synchronous = FULL`: a commit that returned survives a crash or a power loss.
- **Calls within one store are serialised.** A store is one connection; it is safe to share between threads, and
  a busy runtime gains from a store of its own rather than from sharing another runtime's.
- **Virtual threads.** sqlite-jdbc makes its native calls inside `synchronized` methods, so on JDK 21 a call from
  a virtual thread - as the runtime's tasks are - pins its carrier thread for as long as the call lasts: up to
  `busyTimeout` while it waits for another connection's write. Calls within one store are serialised, so each store
  pins at most one carrier at a time; keep `busyTimeout` short if many stores in one process write to busy files.

### Watching the file

SQLite cannot tell one connection that another has committed, so a subscription over a `SqliteCheckpointer` sees the
commits of other stores on the file by reading the event log, once per `WatchPolicy.pollInterval`. Each read is a
short indexed query, and in WAL mode it never waits for another connection's writer. It can wait for its own store,
though: the calls on one `SqliteCheckpointer` share its one connection and are serialised behind its lock, so a
watch's read waits while that store is committing - including a commit that is itself waiting, up to
`busyTimeout`, for another connection's write. A process's own runtime still delivers its own commits at once. Each live subscription reads once per interval, so where many subscriptions watch one file, raise
the interval. A subscription whose store is closed under it ends with `Disconnected(lastSeq, ReplayFailed(...))`,
from which the next process resubscribes.

### Clocks

A claim's expiry is judged by the clock of the store that grants or renews it - `Clock.systemUTC()` unless you pass
one. Stores on one host share that host's clock. Processes on different hosts cannot share one SQLite file safely
anyway (see WAL above), but if host clocks still differ - containers with skewed clocks, a clock step - a claim
lasts longer or shorter than `ttl` as other processes see it. Fencing keeps every write safe regardless. A store
clock running ahead lets another process take over a live run early; that run then finds its commits or renewals
refused and stops, but tasks it was already running can repeat side effects (see
[Limits](#limits)). A store clock running behind holds a dead run's thread longer. A run's own lease, judged by its
monotonic clock, does not protect against a store clock that runs ahead: the run learns of the takeover only from its
next refused renewal or commit.

### Schema versions

The file records its schema version: 2 since the fenced delete (which added `first_seq`, where a deleted thread's id
continues its event numbering), 3 and 4 for effect records, and 5 since claims record their tenant (`claim_tenant`,
with `claim_tenant_token`, the token of the claim the tenant belongs to). A claim a version-4 file holds when it is
upgraded has no tenant recorded, so it matches no tenant: its holder is named to nobody, not even its own tenant,
and a recovery that takes it over records no `takenOverFrom`. Such a claim is no older than its `ttl`, so this lasts
at most that long. Stop every process running an older build before opening its file with this one: an
older process that already has the file open is not refused, and until it stops its deletes are not fenced and drop
the record a deleted thread's id numbers on from. Opening a file at an older version upgrades it in one transaction, and
opening a file written by a newer build is refused with a `ProcessingError`, so an older process never misreads
it. Checkpoints inside keep their own format version, as with every store, and are migrated when read.

### What it stores

Only data: checkpoints, pending writes and events as JSON, never closures or class names, so a file can be read by
any build that knows the graph. Its tables are prefixed `llm4s_checkpoint_`, so the file can hold other tables
too. It keeps every checkpoint of a thread's history, as the next section describes: `history` pages through it
newest first by an index, so a long history costs a page, not a scan, and `prune` and `deleteThread` remove from it
in one transaction each, safely beside other stores writing the file. `prune` reads the thread's whole history and
event log inside its transaction, so on a very long log it holds the file for a while and other stores' writes wait,
up to `busyTimeout`; prune regularly, or off-peak, rather than once after months.

## Checkpoint history, fork and updateState

A store keeps every checkpoint a thread's runs commit, not only the latest. `history` lists them newest first, a
page at a time, and `checkpoint` reads one by id. Each is data; restore one with the graph to read its typed
state:

```scala
val latestPage = runtime.history(threadId)                         // up to 100, newest first
val nextPage   = runtime.history(threadId, before = Some(latestPage.toOption.get.last.id))
val state      = runtime.checkpoint(threadId, id).flatMap(c => graph.restore(c.snapshot)).map(_.state)
```

Checkpoint ids are `<runId>/<n>`, where `n` counts along the thread, so an id never repeats on a thread even
when one `RunId` serves several runs.

**`fork`** starts a new thread from any checkpoint of the history. The new thread's first checkpoint carries
that checkpoint's state, scheduled work, parked interrupts and status, and its event log begins with a
`RunEvent.ThreadForked`. Continue it as its status says: `start` after `Completed` or `Failed`, `resume` after
`Suspended`, `recover` after `Running`. The source thread is only read and is left unchanged.

```scala
runtime.fork(threadId, checkpointId, ThreadId("what-if")).flatMap { _ =>
  runtime.start(ThreadId("what-if"), graph, "a different question")
}
```

A fork never copies pending writes, so `recover` on a fork of a `Running` checkpoint runs that checkpoint's tasks
again, in the new thread. The target must be a new thread id (`ThreadExists` otherwise).

**`updateState`** applies a typed `StateUpdate` to the thread's latest checkpoint and commits the result as a new
checkpoint, recorded with a `RunEvent.StateUpdated`. Use it to correct a thread's state, for example before
`resume`. It is optimistic: you name the checkpoint you read, and it is refused with `CheckpointConflict` if the
thread has moved on since. Only the state changes - the status, scheduled tasks and parked interrupts stay - so
the same `start`, `recover` or `resume` continues the thread afterwards.

```scala
for
  latest  <- runtime.history(threadId, limit = 1).flatMap(_.headOption.toRight(ValidationError("thread", "no checkpoint yet")))
  updated <- runtime.updateState(threadId, graph, latest.id, StateUpdate.update(notes, "corrected"), asNode = Some(writer))
  handle  <- runtime.resume(threadId, graph, answers)
yield handle
```

With `asNode`, the update must stay within that node's declared write set, as if the node had returned it. The
thread is held, with a claim in the store, while it is updated, so a live run refuses it with `ThreadBusy`. If
that claim lapses, or another runtime takes it over, before the update's commit lands, the store refuses the commit
and `updateState` returns `ThreadBusy` too, with nothing applied: retry it. So does a claim found lost before the
commit begins: the commit is never sent. And if the claim is found lost while the update waits on a store that
hangs, `updateState` returns `ThreadBusy` within `2 × ttl` of the loss rather than waiting (see
[Fencing](#fencing-a-run-that-lost-its-claim-stops-and-records-nothing)). `fork` does the same for its target.
Neither is a run, so neither returns `ClaimLost`. Pending
writes of tasks that already completed are carried over, so `recover` still does not run them again. They apply
after your update when the thread is recovered, so a carried write to a key you edited can overwrite your edit on
`recover`. To be sure an edit stands, update a `Running` thread only in keys its completed tasks did not write.

Tool idempotency keys are derived from the thread, the checkpoint id, the superstep, the task and the call id.
`updateState` moves the thread on by one superstep, and checkpoint ids count along the thread, so no later call
reproduces an earlier key, whatever `RunId` you reuse.

A fork copies the checkpoint's snapshot as it is. Once [#1739](https://github.com/llm4s/llm4s/issues/1739) lands,
tool calls already issued at the forked checkpoint record their idempotency keys in the snapshot (in their
`ToolTask`, `ApprovalRequest` or `ToolQuestionRequest`), so in the new thread they keep the source thread's keys;
only model calls the fork makes afterwards derive new keys, since the key includes the thread. This is intended: a
fork that re-runs a call the source already issued - `recover` of a `Running` checkpoint, or `resume` of a parked
approval or question - is de-duplicated against the source's call by its key instead of performing the side effect
a second time.

{: .warning }
> `forget` (`deleteThread`) starts a thread id afresh. Reusing both the thread id and a `RunId` after it repeats
> the earlier checkpoint ids and supersteps, and so the earlier tool idempotency keys. Use a fresh `RunId` (the
> default) for runs on a forgotten thread id.

### A guardrail Block leaves no trace in the history

When a node blocks a run (`NodeResult.Block`, as a guardrail does), the run's closing `Failed` checkpoint
**retracts its turn**: every checkpoint after the thread's last `Completed` or `Failed` one is removed from the
history in the same commit - including the checkpoints of earlier runs of the same turn, such as one that
suspended for review. Nothing of the blocked turn stays readable through those checkpoints with `history`,
`checkpoint` or `fork`. Events carry no message content and stay in the log.

The retraction removes the turn's *earlier* checkpoints. The closing `Failed` checkpoint itself - the thread's
latest, which `history`, `checkpoint`, `fork` and the next `start` read - holds whatever the blocking node's own
`Block` update leaves in the state. `Agent`'s output guardrail commits `MessageUpdate.RemoveTurn` there, and an
input Block blocks before the loop stores the turn, so with `Agent` nothing of the turn remains. **A custom blocking node must remove the content in its `Block` update** the same way, or the closing
checkpoint keeps it.

One window remains before the guard runs. The model step commits the answer, and the output guardrail runs in the
next superstep, so between those two commits `history`, `checkpoint` and `fork` can read the unguarded answer. The
Block retracts it from this thread afterwards, but a fork made in that window is another thread and keeps its copy;
the fork's copy of the open turn is guarded when the fork continues that turn.

## Retention

The history grows with every superstep. `prune` removes what a `RetentionPolicy` does not keep, by age or by
size, alongside `compactEvents`:

```scala
runtime.prune(threadId, RetentionPolicy(maxAge = Some(30.days), maxCheckpoints = Some(50), maxEvents = Some(10000)))
```

- `maxAge` removes checkpoints and events older than it, by the store's clock;
- `maxCheckpoints` keeps the newest checkpoints, and `maxEvents` the newest events.

The latest checkpoint and its pending writes are always kept, so pruning never changes what the thread does next.
Pruning a checkpoint also removes the events recorded before the oldest checkpoint kept; `maxAge` and `maxEvents`
may remove more events than that, including those of checkpoints still kept, the latest checkpoint's among them.
Removing events raises the replay floor (`ReplayUnavailable`) as `compactEvents` does. `prune` needs no claim, so it
can run while a run executes; call it on a schedule that suits you.

`maxAge` compares the store's clock with the `createdAt` and event timestamps the committing runtimes stamped by
theirs, so on a store shared by several hosts, clock skew between them shifts the effective age by that much. A
runtime's `clock` stamps what it writes - checkpoints' `createdAt`, events' timestamps - and the store's clock judges
claim expiry and retention; give a runtime and its store the same clock, as `GraphRuntime.inMemory(clock)` does for
its `InMemoryCheckpointer`.

## Writing a store

A store implements `Checkpointer`: `claim`, `renew` and `release` for claims; `commit`, `latest`, `history`,
`checkpoint`, `eventsAfter`, `compactEvents`, `prune` and `deleteThread` for the data. `awaitEventsAfter`, which
subscriptions use to watch the store, has a default that polls `eventsAfter`. The Scaladoc of `Checkpointer` states
the contract. In short:

- grant at most one live claim per thread, by the store's clock, and replace an expired one;
- record each claim's tenant (`ClaimRequest.tenantId`, returned as `RunClaim.tenantId`), and name a claim's holder
  to a later claim - as `BusyHolder.Run` in its `ThreadBusy`, or as its `RunClaim.takenOverFrom` - only when that
  claim is of the same tenant (`None` and `Some` differ); otherwise refuse with `BusyHolder.Unnamed` and leave
  `takenOverFrom` empty. A claim whose tenant the store cannot tell - stored before it recorded tenants - is of no
  tenant;
- give every claim a token greater than every token issued for that thread before, also after `deleteThread`;
- apply a `Commit` entirely or not at all, and only with the current claim's token (`StaleClaim`, checked first);
- delete a thread only with the current claim's token, checked in the same lock or transaction as the delete
  (`StaleClaim` otherwise, deleting nothing); a thread with no claim is refused too. With a `fence` of
  `(other, otherToken)`, delete only if `otherToken` is also `other`'s current claim, in that same lock or
  transaction, the thread's own claim checked first (`StaleClaim` for `other` otherwise, deleting nothing);
- keep, after `deleteThread`, where the thread's event numbering ended - a tombstone with no data, for a thread that
  numbered any event - and number the next thread of the id from one past the deleted thread's next number;
  refuse `eventsAfter`/`awaitEventsAfter` after a number of the deleted thread with `ThreadDeleted`
  (`Checkpointer.deleted(afterSeq, firstSeq)` is the rule), and read from `0`, or from just before the current
  thread's first event, from that first event. A store that wakes `awaitEventsAfter` on commits wakes it on a
  delete too;
- accept a new checkpoint only over the latest (`CheckpointConflict`) and with an id not already in the history,
  and pending writes only for it;
- keep every superseded checkpoint in the history until `prune`, a commit's `retractTurn` or `deleteThread`
  removes it, and keep pending writes for the latest only;
- on `retractTurn`, remove every checkpoint after the newest `Completed` or `Failed` one before adding the new one;
- number events contiguously inside the commit, and never reuse a number, also across `deleteThread`;
- record on each event the token of the commit that stored it (`EventRecord.committed(threadId, seq, commit.token,
  draft)`): a run's subscriptions tell its events from an earlier run's of the same `RunId` by it;
- release only with the current token, and treat any other as a no-op;
- return from `awaitEventsAfter` what `eventsAfter` would: at once if there is something, and otherwise within about
  its `timeout`. A store that can be told of commits, such as a database with a notification channel, overrides it
  to return as soon as one lands. It must then wake for commits made through every store over the same storage;
- tolerate the reading thread being interrupted at any point. Cancelling a subscription interrupts its watch, which
  may be in the middle of `eventsAfter` or `awaitEventsAfter`: that one call may fail (`CancelledError`), but the
  store must stay usable by every other thread. A driver whose I/O goes through a
  `java.nio.channels.InterruptibleChannel` (a `FileChannel` or `SocketChannel`) closes the channel when the thread is
  interrupted mid-read, so a store sharing one such connection between threads must read on a connection of its own
  for the watch, or replace a connection an interrupt closed;
- return from every call within a bound you document - a configured timeout - whatever the storage is doing:
  waiting for a lock, another connection's write, the store's own earlier calls or the network. A call that reaches
  it fails, changing nothing; `awaitEventsAfter` returns within its `timeout` plus the bound. Say which calls, if
  any, you cannot bound, and why. Keep the bound well inside `ttl - renewEvery` (see
  [When the store hangs](#when-the-store-hangs)).

### Testing it with the contract suite

```scala
// same version as llm4s-agent; test scope only
libraryDependencies += "org.llm4s" %% "llm4s-agent-testkit" % llm4sVersion % Test
```

`CheckpointerContract` is the contract as a ScalaTest suite. Mix it into a spec and say how to make a store:

```scala
import org.llm4s.agent.graph.Checkpointer
import org.llm4s.agent.testkit.CheckpointerContract
import org.scalatest.flatspec.AnyFlatSpec

class MyCheckpointerContractSpec extends AnyFlatSpec with CheckpointerContract:
  protected def newCheckpointer(clock: java.time.Clock): Checkpointer =
    MyCheckpointer.open(freshDatabase(), clock)
```

It runs every case against your store: commits, conflicts, event numbering, compaction, `deleteThread` (fenced by
the current token and by the fence thread's, and a recreated thread numbered on past the deleted one, whose log a reader cannot continue), round
trips, the checkpoint history (paging, reads by id, unique ids, retraction and pruning by age, count and event
count), claims and fencing (also from many threads at once), each claim's tenant (a holder named only to a claim of
its own tenant, also across a reopen), and two or more `GraphRuntime`s contending over one
store - a live run refused elsewhere, renewal, takeover after expiry without re-running completed tasks, a stale
run's commits refused, only one of several runtimes admitted to recover a thread, `updateState` and `fork` fenced
against a live run, a blocked turn leaving nothing in the history, and another tenant's claim on a thread never
revealing its run to the thread's tenant through `ThreadBusy`, `RunRecovered.takenOverFrom` or `ClaimLost`. It also checks change notification:
`awaitEventsAfter` itself, and subscriptions in one runtime that receive another runtime's commits in order, mid-run,
across a restart, and only until they are cancelled or the thread is deleted. `InMemoryCheckpointer` passes it.

Four hooks fit it to a durable store:

```scala
class MyCheckpointerContractSpec extends AnyFlatSpec with CheckpointerContract:
  protected def newCheckpointer(clock: java.time.Clock): Checkpointer = MyCheckpointer.open(freshDatabase(), clock)

  // close the store and open the same storage again, as a restarted process would
  override protected def reopen(store: Checkpointer, clock: ManualClock): Checkpointer =
    store match
      case mine: MyCheckpointer =>
        mine.close()
        MyCheckpointer.open(mine.location, clock)
      case other => other
```

- **`reopen`** runs the cases that check a live claim, its token and the fencing of a stale holder survive a
  restart. It defaults to the same instance.
- **`sibling`** opens another store over the same storage, as another process would, while the first stays open:
  `MyCheckpointer.open(mine.location, clock)`. The change-notification cases put each runtime on a store of its own
  through it. It defaults to the same instance.
- **`boundedStore`** gives the case that checks the bound on every call a store configured with a short one, the
  bound it documents, and a way to make the storage busy from outside, as another process holding a write would:
  `CheckpointerContract.BoundedStore(store, bound, busy)`. The case makes many calls at once while the storage is
  busy, and fails if one takes longer than `bound` plus `boundGrace` (500 ms). It defaults to `None`, which skips the
  case: right for a store whose storage nothing outside it can hold, as `InMemoryCheckpointer`'s.
- **`advanceStoreClock`** is how the suite expires claims. By default it moves the `ManualClock` it handed to
  `newCheckpointer`, so the store must judge expiry by that `clock`. A store that can only use a clock of its own,
  such as a database server's `now()`, overrides it to make claims expire as if that clock had moved - by moving
  every stored expiry back by `by`, for example - and sets `exactExpiry = false`, so that `expiresAt` is checked by
  its effect rather than against the `ManualClock`.

## See also

- [Streaming events](streaming): subscribing to a thread's events, which a store numbers and replays.
- `docs/design/typed-agent-runtime/`: §4.16 (`4.16-stage-2-slice-1-the-durable-checkpointer-contract.md`), the
  design of claims and fencing; §4.19 (`4.19-stage-2-slice-3-checkpoint-history-fork-and-retention.md`), history,
  fork, `updateState` and retention; and §4.21 (`4.21-stage-2-slice-6-live-subscriptions-across-runtimes.md`),
  subscriptions across runtimes.
- [Migration guide](../../reference/migration#stage-2-migration-durable-execution): what changed for code that
  implemented `Checkpointer` or built a `Commit`.
