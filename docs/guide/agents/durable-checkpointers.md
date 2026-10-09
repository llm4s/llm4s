---
layout: page
title: Durable Checkpointers
nav_order: 6
parent: Agents
grand_parent: User Guide
---

# Durable Checkpointers
{: .no_toc }

Share one checkpoint store between runtimes and processes, recover a run whose process died, and test a store
of your own against the contract every store must meet.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## What a checkpointer holds

Every agent turn and every graph run executes on a `GraphRuntime`, which stores its threads in a
`Checkpointer` (`org.llm4s.agent.graph`). For each thread the store keeps:

- the latest checkpoint, as versioned JSON: the graph's state and what is left to run;
- the pending writes of tasks that finished in the current superstep, so `recover` does not run them again;
- the durable event log, numbered `1, 2, 3, ...` per thread;
- the **claim** of the run that holds the thread, if one does.

`InMemoryCheckpointer` is the only store today, and the one `GraphRuntime.inMemory()` and `Agent.builder`
use. A SQLite store is coming in [#1701](https://github.com/llm4s/llm4s/issues/1701). This page describes the
contract all stores share, so it applies unchanged when a durable one arrives.

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

## Writing a store

A store implements `Checkpointer`: `claim`, `renew` and `release` for claims; `commit`, `latest`, `eventsAfter`,
`compactEvents` and `deleteThread` for the data. The Scaladoc of `Checkpointer` states the contract. In short:

- grant at most one live claim per thread, by the store's clock, and replace an expired one;
- give every claim a token greater than every token issued for that thread before, also after `deleteThread`;
- apply a `Commit` entirely or not at all, and only with the current claim's token (`StaleClaim`, checked first);
- accept a new checkpoint only over the latest (`CheckpointConflict`), and pending writes only for it;
- number events contiguously inside the commit, and never reuse a number;
- release only with the current token, and treat any other as a no-op.

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
trips, claims and fencing (also from many threads at once), and two or more `GraphRuntime`s contending over one
store - a live run refused elsewhere, renewal, takeover after expiry without re-running completed tasks, a stale
run's commits refused, and only one of several runtimes admitted to recover a thread. `InMemoryCheckpointer`
passes it.

Two hooks fit it to a durable store:

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
- **`advanceStoreClock`** is how the suite expires claims. By default it moves the `ManualClock` it handed to
  `newCheckpointer`, so the store must judge expiry by that `clock`. A store that can only use a clock of its own,
  such as a database server's `now()`, overrides it to make claims expire as if that clock had moved - by moving
  every stored expiry back by `by`, for example - and sets `exactExpiry = false`, so that `expiresAt` is checked by
  its effect rather than against the `ManualClock`.

## See also

- [Streaming events](streaming): subscribing to a thread's events, which a store numbers and replays.
- `docs/design/typed-agent-runtime-design.md` §4.16, the design of claims and fencing.
- [Migration guide](../../reference/migration#stage-2-migration-durable-execution): what changed for code that
  implemented `Checkpointer` or built a `Commit`.
