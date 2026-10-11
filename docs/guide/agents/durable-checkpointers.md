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
(`renewEvery`, 10 seconds by default; it must be shorter than `ttl`). The store judges expiry by its own clock.
When the store has a single clock - a database server's, or one process's - the runtimes need not agree on the
time. An embedded store that each process opens itself, such as SQLite on a shared file, judges expiry by the
clock of whichever process grants or renews the claim, so clock skew between hosts shortens or lengthens the
effective `ttl`. Fencing (below) still keeps every write safe; skew costs liveness - a live run taken over early,
a dead one held longer - and can repeat side effects. Keep hosts' clocks synchronised.

While one run holds the thread, every other `start`, `recover`, `resume` or `deleteThread` on it, from any
runtime, is refused with `GraphError.ThreadBusy`, which names the holding run:

```scala
b.recover(threadId, graph) match
  case Left(GraphError.ThreadBusy(_, latestCheckpoint, Some(holder))) =>
    // run `holder` is live: retry once it has suspended or completed
  case other => ()
```

Nothing is accepted or discarded: the caller retries. A thread that has no checkpoint yet cannot be checked for
its tenant, so a refusal there does not name the holder.

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

`recover` reads the dead run's pending writes again once its own claim is granted, so a task that finished in
the last moments before the takeover is reused, not run again.

## Fencing: a run that lost its claim records nothing

A process that was only paused - a long GC, a frozen VM - may wake up after another runtime took its thread over.
Every commit a run makes carries its claim's token, and the store refuses any commit whose token is not the
thread's current claim, with `GraphError.StaleClaim`. Such a run therefore cannot record anything after its
successor started: it ends with `GraphError.CheckpointWriteFailed(StaleClaim)`, and the thread's state and event
log are its successor's.

A run whose renewal is the first to learn that its claim is gone stops at the start of its next superstep with
the same error, so a run with `Durability.Async` or `Durability.OnExit`, whose commits are deferred, does not go
on executing supersteps whose results would be refused.

### Limits

- Its tasks that were already running still finish, so their external side effects can happen in both runs, and
  the successor runs them again. That is the at-least-once boundary every run has; make such tools idempotent. A
  node that writes to a system able to fence can pass the token on: it is `context.position.fencingToken`.
- Until it ends, such a run's live progress events (`StreamEvent.Live`, which are never stored) still reach the
  subscribers of its own runtime. Its durable events are refused with its commits.
- A process that dies holds its threads for up to `ttl`; so does a store that fails to release a claim.

## Subscriptions across runtimes

A subscription sees every commit on its thread, made through any runtime or process that shares the store, not
only through its own: in order, without gap or duplicate, as for its own runtime's commits. Each live subscription
watches the store with `Checkpointer.awaitEventsAfter`. A store that is told of commits returns at once. A store that
is not, such as SQLite, is polled every `WatchPolicy.pollInterval` (250 ms by default):

```scala
val runtime = GraphRuntime(store, watch = WatchPolicy(pollInterval = 100.millis))
```

Only durable events cross runtimes; live progress is never stored. See
[Subscriptions across runtimes](streaming#subscriptions-across-runtimes) for the delivery and back-pressure rules.

## The SQLite store

{: .note }
> Not yet published. `llm4s-agent-checkpoint-sqlite` exists in the build as of
> [#1701](https://github.com/llm4s/llm4s/issues/1701) but ships in the next release.

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
lasts longer or shorter than `ttl` as other processes see it. Fencing keeps every write safe regardless; skew only
changes when a dead run's thread can be taken over.

### Schema versions

The file records its schema version. Opening a file at an older version upgrades it in one transaction, and
opening a file written by a newer build is refused with a `ProcessingError`, so an older process never misreads
it. Checkpoints inside keep their own format version, as with every store, and are migrated when read.

### What it stores

Only data: checkpoints, pending writes and events as JSON, never closures or class names, so a file can be read by
any build that knows the graph. Its tables are prefixed `llm4s_checkpoint_`, so the file can hold other tables
too. It keeps every checkpoint of a thread's history, as the next section describes: `history` pages through it
newest first by an index, so a long history costs a page, not a scan, and `prune` and `deleteThread` remove from it
in one transaction each, safely beside other stores writing the file.

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
thread is held, with a claim in the store, while it is updated, so a live run refuses it with `ThreadBusy`. Pending
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
theirs, so on a store shared by several hosts, clock skew between them shifts the effective age by that much.

## Writing a store

A store implements `Checkpointer`: `claim`, `renew` and `release` for claims; `commit`, `latest`, `history`,
`checkpoint`, `eventsAfter`, `compactEvents`, `prune` and `deleteThread` for the data. `awaitEventsAfter`, which
subscriptions use to watch the store, has a default that polls `eventsAfter`. The Scaladoc of `Checkpointer` states
the contract. In short:

- grant at most one live claim per thread, by the store's clock, and replace an expired one;
- give every claim a token greater than every token issued for that thread before, also after `deleteThread`;
- apply a `Commit` entirely or not at all, and only with the current claim's token (`StaleClaim`, checked first);
- accept a new checkpoint only over the latest (`CheckpointConflict`) and with an id not already in the history,
  and pending writes only for it;
- keep every superseded checkpoint in the history until `prune`, a commit's `retractTurn` or `deleteThread`
  removes it, and keep pending writes for the latest only;
- on `retractTurn`, remove every checkpoint after the newest `Completed` or `Failed` one before adding the new one;
- number events contiguously inside the commit, and never reuse a number;
- release only with the current token, and treat any other as a no-op;
- return from `awaitEventsAfter` what `eventsAfter` would: at once if there is something, and otherwise within about
  its `timeout`. A store that can be told of commits, such as a database with a notification channel, overrides it
  to return as soon as one lands. It must then wake for commits made through every store over the same storage;
- tolerate the reading thread being interrupted at any point. Cancelling a subscription interrupts its watch, which
  may be in the middle of `eventsAfter` or `awaitEventsAfter`: that one call may fail (`CancelledError`), but the
  store must stay usable by every other thread. A driver whose I/O goes through a
  `java.nio.channels.InterruptibleChannel` (a `FileChannel` or `SocketChannel`) closes the channel when the thread is
  interrupted mid-read, so a store sharing one such connection between threads must read on a connection of its own
  for the watch, or replace a connection an interrupt closed.

### Testing it with the contract suite

{: .note }
> Not yet published. `llm4s-agent-testkit` exists in the build as of
> [#1700](https://github.com/llm4s/llm4s/issues/1700) but ships in the next release.

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

It runs every case against your store: commits, conflicts, event numbering, compaction, `deleteThread`, round
trips, the checkpoint history (paging, reads by id, unique ids, retraction and pruning by age, count and event
count), claims and fencing (also from many threads at once), and two or more `GraphRuntime`s contending over one
store - a live run refused elsewhere, renewal, takeover after expiry without re-running completed tasks, a stale
run's commits refused, only one of several runtimes admitted to recover a thread, `updateState` and `fork` fenced
against a live run, and a blocked turn leaving nothing in the history. It also checks change notification:
`awaitEventsAfter` itself, and subscriptions in one runtime that receive another runtime's commits in order, mid-run,
across a restart, and only until they are cancelled. `InMemoryCheckpointer` passes it.

Three hooks fit it to a durable store:

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
- **`advanceStoreClock`** is how the suite expires claims. By default it moves the `ManualClock` it handed to
  `newCheckpointer`, so the store must judge expiry by that `clock`. A store that can only use a clock of its own,
  such as a database server's `now()`, overrides it to make claims expire as if that clock had moved - by moving
  every stored expiry back by `by`, for example - and sets `exactExpiry = false`, so that `expiresAt` is checked by
  its effect rather than against the `ManualClock`.

## See also

- [Streaming events](streaming): subscribing to a thread's events, which a store numbers and replays.
- `docs/design/typed-agent-runtime-design.md` §4.16, the design of claims and fencing, §4.19, history, fork,
  `updateState` and retention, and §4.21, subscriptions across runtimes.
- [Migration guide](../../reference/migration#stage-2-migration-durable-execution): what changed for code that
  implemented `Checkpointer` or built a `Commit`.
