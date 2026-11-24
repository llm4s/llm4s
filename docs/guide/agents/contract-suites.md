---
layout: page
title: Store Contract Suites
description: "Test your own Checkpointer, RunQueue, LongTermStore or FileBackend with llm4s-agent-testkit: the ScalaTest contract suites, their hooks and what each one checks."
nav_order: 9.7
parent: Agents
grand_parent: User Guide
---

# Store contract suites
{: .no_toc }

`llm4s-agent-testkit` holds the test suites every store behind the agent runtime must pass. Write a store of your
own, mix the suite in, say how to make a store, and every case the shipped stores pass runs against yours.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## The module

```sbt
// same version as llm4s-agent; test scope only
libraryDependencies += "org.llm4s" %% "llm4s-agent-testkit" % llm4sVersion % Test
```

It depends on `llm4s-agent` and ScalaTest, and requires JDK 21, as `llm4s-agent` does. Everything is in
`org.llm4s.agent.testkit`. Each suite is a trait over ScalaTest's `AnyFlatSpecLike`: a spec extends `AnyFlatSpec`,
mixes the trait in and implements its one or two abstract members.

| Suite | For an implementation of | You implement |
|---|---|---|
| `CheckpointerContract` | `Checkpointer` | `newCheckpointer(clock)` |
| `RunQueueContract` | `RunQueue`, the queue that lives in the same database as a checkpointer | `newStores(clock)` |
| `DelegationContract` | `Checkpointer`, for parent and subagent threads | `newCheckpointer()` |
| `GuardedContentContract` | `Checkpointer` and its `RunQueue`, for what a guardrail refused | `newStores()` |
| `LongTermStoreContract` | `LongTermStore` | `newStore(limits)`, `appliedKeys(store)` |
| `FileBackendContract` | `FileBackend` | `newBackend()` |
| `FileToolsContract` | `FileBackend`, through the seven file tools as a model calls them | `newBackend()` |
| `ReplayContract` | any external target whose effects `recover` may run again | `newSubject()` |

`CheckpointerContract` and `RunQueueContract` carry no stability annotation; the other six are `@Experimental`.
The stores LLM4S ships pass the same suites: `InMemoryCheckpointer`, `SqliteCheckpointer` and
`PostgresCheckpointer` with their run queues, `InMemoryLongTermStore` and the PostgreSQL long-term store, and the
file backends.

## A checkpointer

[Durable Checkpointers](durable-checkpointers#writing-a-store) says what a `Checkpointer` must do, and
[Testing it with the contract suite](durable-checkpointers#testing-it-with-the-contract-suite) covers
`CheckpointerContract` in full: the cases it runs and the hooks (`reopen`, `sibling`, `boundedStore`,
`advanceStoreClock`) that fit it to a durable store. In short:

```scala
import org.llm4s.agent.graph.Checkpointer
import org.llm4s.agent.testkit.CheckpointerContract
import org.scalatest.flatspec.AnyFlatSpec

class MyCheckpointerContractSpec extends AnyFlatSpec with CheckpointerContract:
  protected def newCheckpointer(clock: java.time.Clock): Checkpointer =
    MyCheckpointer.open(freshDatabase(), clock)
```

Each case gets a new store from `newCheckpointer` and uses thread ids of its own, so the storage need not be empty
of other threads.

### Its run queue

A store that offers a `RunQueue` runs `RunQueueContract` as well. `newStores` returns the checkpointer and the queue
that lives in the same database. This is the in-memory store's spec:

```scala
import org.llm4s.agent.graph.InMemoryCheckpointer
import org.llm4s.agent.testkit.RunQueueContract
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Clock

class InMemoryRunQueueContractSpec extends AnyFlatSpec with RunQueueContract {
  protected def newStores(clock: Clock): RunQueueContract.Stores = {
    val store = InMemoryCheckpointer(clock)
    RunQueueContract.Stores(store, store.runQueue)
  }
}
```

It checks submission and the per-thread run order, cancel and interrupt, tenant scoping, leases (one live lease per
thread, takeover with a greater token once a lease expires, renewal and release), attempts and `maxAttempts`,
closes and `awaitClosed`, and that many takes raced at once yield one lease. A durable store overrides `reopen` and
`sibling`, as for `CheckpointerContract`.

### Delegation

`DelegationContract` runs a parent agent delegating to a subagent on your store, through each state the child
thread can be in when the parent's `recover` runs the `task` call again: no checkpoint (the child starts), `Running`
(it continues from its checkpoint), `Suspended` (its interrupts surface again), `Completed` or `Failed` (the outcome
is returned with no run). It takes a fresh store per case:

```scala
import org.llm4s.agent.graph.{ Checkpointer, InMemoryCheckpointer }
import org.llm4s.agent.testkit.DelegationContract
import org.scalatest.flatspec.AnyFlatSpec

class InMemoryDelegationContractSpec extends AnyFlatSpec with DelegationContract:
  protected def newCheckpointer(): Checkpointer = new InMemoryCheckpointer()
```

### What a guardrail refused

`GuardedContentContract` runs guarded agents on your store and checks that the reason a guardrail gave is in nothing
the store was handed and nothing it gives back: each `Commit` is recorded on its way in, so a row written and then
retracted is seen too, and checkpoint history, the event log and the thread's children are read back afterwards. It
also checks that `EncryptingCheckpointer` over your store hands it no error text of a failed run's outcome. It takes
a fresh store and its queue per case:

```scala
import org.llm4s.agent.graph.InMemoryCheckpointer
import org.llm4s.agent.testkit.{ GuardedContentContract, RunQueueContract }
import org.scalatest.flatspec.AnyFlatSpec

class InMemoryGuardedContentContractSpec extends AnyFlatSpec with GuardedContentContract:
  protected def newStores(): RunQueueContract.Stores =
    val store = new InMemoryCheckpointer()
    RunQueueContract.Stores(store, store.runQueue)
```

### Time

Claims and leases expire by the store's clock. By default a store must judge expiry by the `clock` it is given: the
suites hand it a `ManualClock` (also in the testkit), a `java.time.Clock` that moves only when `advance(by)` or
`set(to)` is called. A store that can only use a clock of its own, such as a database server's `now()`, overrides
`advanceStoreClock` and sets `exactExpiry` to `false`, in `CheckpointerContract` and `RunQueueContract` alike.

## A long-term store

`LongTermStoreContract` takes a fresh, empty store with the limits the case asks for, and a way to count the
applied keys the store holds, read from its storage. This is the in-memory store's spec:

```scala
import org.llm4s.agent.harness.{ InMemoryLongTermStore, LongTermStore, StoreLimits }
import org.llm4s.agent.testkit.LongTermStoreContract
import org.scalatest.flatspec.AnyFlatSpec

class InMemoryLongTermStoreContractSpec extends AnyFlatSpec with LongTermStoreContract:
  protected def newStore(limits: StoreLimits): LongTermStore = InMemoryLongTermStore(limits)
  protected def appliedKeys(store: LongTermStore): Int = store match
    case inMemory: InMemoryLongTermStore => inMemory.appliedKeys
    case other                           => fail(s"not the in-memory store: $other")
```

It checks items (`put`, `get`, `list` and `delete` through the replay guard, values read back exactly as written,
namespaces and tenants kept apart), the bounds on keys, items and namespaces, `modify` and its recorded outcome,
fencing by token, and concurrent writers. Two hooks fit it to a store:

- **`sibling(store)`** returns another instance over the same storage, as a second machine opens it, so the
  concurrency cases run through two instances. It defaults to the same instance.
- **`matchesSubstrings`** says whether `search` matches as the shipped stores do: the query, case-insensitively, as
  a substring of the key or of any string in the value, in key order. A store that documents another match sets it
  to `false`, and the substring cases are not run.

[Long-term memory on the same database](durable-checkpointers#long-term-memory-on-the-same-database) describes the
PostgreSQL store that passes it.

## A file backend

`FileBackendContract` runs a `FileBackend`'s operations as tool calls of a real agent run, and
`FileToolsContract` runs the seven file tools over it by their public names and arguments. A backend that passes
the first should pass the second. Both take the same members; this is the thread-state backend's spec:

```scala
import org.llm4s.agent.harness.{ FileBackend, StateFileBackend }
import org.llm4s.agent.testkit.FileBackendContract
import org.scalatest.flatspec.AnyFlatSpec

class StateFileBackendContractSpec extends AnyFlatSpec with FileBackendContract:
  protected def newBackend(): FileBackend = StateFileBackend(maxBytes = 200)
  // path and content are 80 bytes each: two fit, the third is over
  override protected def boundedBatch: Option[Vector[(String, String)]] =
    Some(Vector("/a" -> "x" * 78, "/b" -> "x" * 78, "/c" -> "x" * 78))
```

- **`boundedBatch`** gives writes that the backend accepts one by one, in order, until the last, which is over its
  bound; the suite then checks that exactly that write is refused. `None`, the default, is for a backend with no
  bound a test can reach.
- **`tenantScoped`** is `true` for a backend that keeps a tree per tenant; the suite then checks that two tenants
  see separate trees. It defaults to `false`.

## Replay after a crash

A store or backend that changes something outside the thread's state (a database row, a file on disk) must also
behave when `recover` runs its effect again. `ReplayContract` checks that: it runs a one-task graph that performs
two effects, crashes the run at each point around an effect's intent, the effect itself, its recorded outcome and
the task's commit, recovers on a new runtime, and compares the target's state and the outcomes with a run that
never crashed. Run it once per `ReplaySupport` class the target offers, one spec each.

You implement `newSubject()`, a `ReplayContract.Subject` that performs the effects through the target's own guarded
code path. The testkit has the subject for two kinds of target:

- `LongTermStoreContract.CounterSubject(name, store, applied)` for a `LongTermStore`:

  ```scala
  class LongTermStoreReplaySpec extends AnyFlatSpec with ReplayContract:
    protected def newSubject(): ReplayContract.Subject =
      val store = InMemoryLongTermStore()
      LongTermStoreContract.CounterSubject("a long-term store's transactional modify", store, () => store.appliedKeys)
  ```

- `FileBackendContract.EditSubject` for an external `FileBackend`.

`ReplayContract` runs on `InMemoryCheckpointer` unless you override `newCheckpointer(clock)`; that store must judge
claims by the `clock` it is given.

## See also

- [Durable Checkpointers](durable-checkpointers): the stores LLM4S ships, and what a store must guarantee
- [Running agents on several machines](multiple-machines): the run queue these suites check
- [Testing with the Testkit](../testing-with-the-testkit): `llm4s-testkit`, the scripted `LLMClient` for testing an
  application rather than a store
