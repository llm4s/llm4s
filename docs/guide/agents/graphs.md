---
layout: page
title: Graphs
description: "Build your own workflow on the LLM4S graph runtime: typed state keys, nodes, routes, joins, supersteps, budgets, interrupts and recovery."
search_title: "Custom agent graphs in Scala with GraphBuilder"
nav_order: 9.5
parent: Agents
grand_parent: User Guide
---

# Graphs
{: .no_toc }

Define the nodes, routes and joins of a workflow yourself and run it on durable threads.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

The graph runtime in `org.llm4s.agent.graph` is what every `Agent` runs on, and you can build graphs on it directly
with `GraphBuilder`.

> **Experimental at 1.0.** The graph API on this page (`GraphBuilder`, `CompiledGraph`, `GraphRuntime`, state keys, nodes, routing and interrupts) is `@Experimental`: usable, but outside the 1.x compatibility promise, so it can change in a minor release, with a migration note. The run ids, `RunConfig` and its settings are part of the stable core of `llm4s-agent`, which is listed in [API Stability](../../reference/api-stability#tiers-in-the-code).

Use a graph when the shape of the work is known in advance:

| You want | Use |
|---|---|
| A model that routes each question to a specialist, which then owns the conversation | [Handoffs](handoffs) |
| A lead agent whose model decides when to farm out a bounded task and gets back a typed report | [Delegation](delegation) (`withSubagents`) |
| Explicit steps: fan-out over a list, parallel branches, a join, routing on your own conditions, a pause for a person at a step you choose | A graph |

Nodes are plain Scala functions. A node may call an agent, a model, a tool or nothing at all; the runtime only sees the
state updates and routes it returns. The [several agents in one graph](../../examples/cookbook/multi-agent-graph)
recipe puts agents in the nodes.

## The model

### State keys

A graph's state is a set of typed slots, each a `StateKey[A, U]`: `A` is the stored value, `U` the update a node emits
for it, and the key's update function folds an update into the current value. Three constructors cover most needs:

- `StateKey.replace[A](id, initial)`: the update is the next value; the last one applied wins.
- `StateKey.appending[A](id)`: a `Vector[A]` that collects every update, in commit order.
- `StateKey[A, U](id, initial)((current, update) => Result[A])`: any other fold. A `Left`, or a throw, fails the run
  with `GraphError.StateUpdateFailed`.

Each key needs a uPickle `ReadWriter` for its value and its update, because checkpoints store them as JSON. Keys are
compared by identity: build each key once and share the value.

A node reads the committed state through `ThreadState.get(key)`, which returns the key's initial value when nothing is
stored. A node changes it by returning a `StateUpdate`, an ordered list of `update(key, value)` and `remove(key)`
operations; `remove` makes later reads see the initial value again.

### Nodes, commands and routes

A node is a `GraphNode[I]`, `run(input: I, state: ThreadState, context: RunContext): NodeResult`, usually written as a
lambda. It returns one of:

- `NodeResult.Continue(command)`: a `Command` holds the state update and the routes to schedule next.
- `NodeResult.Suspend(update, question, resumeAt)`: pause for an answer (see [Interrupts](#interrupts-and-resuming)).
- `NodeResult.Fail(error)`: fail the run, leaving the thread for `recover`.
- `NodeResult.Block(update, error)`: end the run as a finished failure, as a guardrail does. The update commits, the
  thread stays usable, and `recover` refuses it.

`NodeResult.fromResult(result)` turns a `Result[Command]` into `Continue` or `Fail`.

Routes are built on the command and target nodes through their typed handles, never by name:

- `goto(target)` schedules a `NodeRef[Unit]`.
- `send(target, payload)` schedules a node with an input of its type.
- `fanOut(join, target, payloads)` schedules one task per payload, counted by a dynamic join.

A command with no routes and no outgoing edges ends that branch. The run completes when no branch has work left.

### Edges and joins

`b.edge(from, to)` is a static edge: every completed task of `from` also schedules `to`, before the routes in its
command.

A **static join**, `b.staticJoin(id, sources, target)`, schedules `target` once every source node has completed a task
since the join last released; each source counts once however many of its tasks complete. A **dynamic join**,
`b.dynamicJoin(id, target)`, is attached to a fan-out: `Command.fanOut(join, ...)` opens an activation recording every
child it schedules, and `target` is scheduled once all of them have completed. An empty fan-out releases at once.

If the graph runs out of work while a join still waits for an arrival nothing can make, the run fails with
`GraphError.UnsatisfiedJoin`, naming the join and what is missing.

### Supersteps

A run proceeds in supersteps. Each one:

1. Runs every ready task against the same committed state, concurrently. No task sees another's writes.
2. Checks each result: every update is to a key in the node's declared `writes`, and every route targets a node or join
   of this graph.
3. Applies the updates task by task in schedule order, and in emission order within a task, whatever order the tasks
   finished in.
4. Schedules the next superstep: each task's static edges, then its routes, then the targets of joins this commit
   released.

So two parallel tasks appending to one key always commit in the same order, and a task reading the state sees exactly
what the previous superstep committed. If a task fails, the superstep's updates are not applied.

## A complete example

This graph counts the words of a text one paragraph at a time, then routes on the total. It needs no model and no
API key.

```scala
import org.llm4s.agent.graph.*
import org.llm4s.types.Result

val words   = StateKey.appending[Int]("words")
val total   = StateKey.replace[Int]("total", 0)
val verdict = StateKey.replace[String]("verdict", "")

val b = GraphBuilder("word-count", "v1")

// one task per paragraph: all of them run in the same superstep
val measure = b.node[String]("measure", writes = Set(words)) { (paragraph, _, _) =>
  NodeResult.Continue(Command.empty.update(words, paragraph.split("\\s+").count(_.nonEmpty)))
}
val longDoc = b.node[Unit]("long", writes = Set(verdict)) { (_, _, _) =>
  NodeResult.Continue(Command.empty.update(verdict, "long"))
}
val shortDoc = b.node[Unit]("short", writes = Set(verdict)) { (_, _, _) =>
  NodeResult.Continue(Command.empty.update(verdict, "short"))
}
// runs once every measure task has committed, then picks a branch
val sum = b.node[Unit]("sum", writes = Set(total)) { (_, state, _) =>
  NodeResult.fromResult(state.get(words).map { counts =>
    val n = counts.sum
    Command.empty.update(total, n).goto(if n > 10 then longDoc else shortDoc)
  })
}
val measured = b.dynamicJoin("measured", sum)
// the entry node: clear the previous run's counts, then fan out
val split = b.node[String]("split", writes = Set(words)) { (text, _, _) =>
  NodeResult.Continue(Command.empty.remove(words).fanOut(measured, measure, text.split("\n\n").toVector))
}

val outcome: Result[RunResult[(Int, String)]] =
  for
    graph  <- b.compile(split)(state => state.get(total).flatMap(n => state.get(verdict).map(n -> _)))
    handle <- GraphRuntime.inMemory().start(ThreadId("doc-1"), graph, "One two three.\n\nFour five six seven.")
    done   <- handle.await()
  yield done

outcome match
  case Right(RunResult.Completed(_, (n, v), _)) => println(s"$n words: $v")   // 7 words: short
  case Right(other)                             => println(s"did not complete: $other")
  case Left(error)                              => println(s"refused: ${error.message}")
```

The run takes four supersteps: `split`, both `measure` tasks, `sum`, then `short`. The `compile` argument projects the
final state into the run's output.

State belongs to the thread and survives the run: a second `start` on `doc-1` begins at the entry node over the state
the first left. That is why `split` removes `words` before fanning out; without it, the second run's total would
include the first run's counts.

## Building and compiling

`GraphBuilder(id, version)` issues every handle. `b.node[I](id, writes, ...)(behaviour)` declares and implements a node
in one step. For nodes that route to each other in a cycle, `b.declare[I](id)` issues the handle first and
`b.implement(ref, writes, ...)(behaviour)` gives it its behaviour later. `version` names the graph's behaviour: bump it
when node logic changes incompatibly, because a checkpoint of a run in flight restores only into its own version.

A node's `writes` is the set of keys it may update or remove. A key that nodes read but none writes, such as one you
seed with `updateState`, is registered with `b.stateKey(key)`; reading an unregistered key is
`GraphError.UnknownStateKey`.

`b.compile(entry)(output)` validates the whole graph and returns `Result[CompiledGraph[I, O]]`, where `I` is the entry
node's input type. It reports every problem at once as `GraphError.InvalidGraph(graphId, problems)`:

- an empty graph id, node id, join id or state key id;
- a node declared twice, implemented twice, or declared and never implemented;
- a handle (node, entry or join source) issued by another builder;
- an edge naming an undeclared node;
- a join id used twice, or a static join with no sources;
- two different keys with the same id.

Other mistakes are caught when a node returns them, and fail the run: an update to a key outside the node's `writes`
(`GraphError.UndeclaredWrite`), and a route to a node or join of another graph, or two fan-outs to one join from one
task (`GraphError.InvalidRoute`). A route of the wrong type does not compile.

`CompiledGraph` is immutable and can be shared. `graph.toMermaid` renders its declared structure as a Mermaid
flowchart; routes a node chooses at run time are not drawn.

## Running

### Runtime and threads

A `GraphRuntime` runs compiled graphs on threads, each addressed by a `ThreadId`, and records a checkpoint per
superstep. `GraphRuntime.inMemory()` keeps them in memory; `new GraphRuntime(checkpointer)` uses a durable store (see
[durable checkpointers](durable-checkpointers)).

The thread's latest checkpoint decides which call is allowed:

| Latest checkpoint | Call | Otherwise refused with |
|---|---|---|
| none, `Completed` or `Failed` | `runtime.start(threadId, graph, input)` | `GraphError.IncompleteRun`, `GraphError.PendingInterrupts` |
| `Running` (the run stopped with work left) | `runtime.recover(threadId, graph)` | `GraphError.NothingToRecover` |
| `Suspended` | `runtime.resume(threadId, graph, answers)` | `GraphError.NotSuspended` |

A call on a thread another run holds is refused with `GraphError.ThreadBusy`. Each call returns a `RunHandle[O]` once
the run is admitted; the run itself executes on a virtual thread. `handle.await()` blocks until it ends,
`handle.status` reads `RunStatus` without blocking, and `handle.cancel()` stops it.

`await` returns `Right` with a `RunResult[O]`:

- `RunResult.Completed(state, output, supersteps)`;
- `RunResult.Suspended(state, interrupts, execution)`, with the `PendingInterrupt`s to answer;
- `RunResult.Failed(state, error)`, with the last committed state.

`runtime.history(threadId)` lists the thread's checkpoints, newest first. `fork` and `updateState` are covered in
[checkpoint history, fork and updateState](durable-checkpointers#checkpoint-history-fork-and-updatestate).

### Budgets

Each `start`, `recover` and `resume` takes a `RunConfig`, whose `budgets` limit that run:

```scala
import org.llm4s.agent.graph.{ RunBudgets, RunConfig }
import scala.concurrent.duration.*

val config = RunConfig(
  budgets = RunBudgets(maxSupersteps = 50, timeout = Some(2.minutes), maxConcurrency = 4)
)
```

- `maxSupersteps` (default 1000) counts the supersteps of one run. A run that reaches it with work still scheduled
  fails with `GraphError.SuperstepLimitExceeded`; the checkpoint stays `Running`, and `recover` continues with a fresh
  allowance. The error is not retryable, so nothing retries it for you: the limit guards against a graph
  that does not stop, and continuing past it is your call.
- `timeout` (default none) is measured from the run's claim on the thread. When it passes, the run stops as a cancel
  stops it and fails with `GraphError.DeadlineExceeded`; `recover` with a new budget continues it. Nodes can read the
  deadline as `context.deadline`.
- A cancel or a timeout interrupts the run's tasks, and `context.isCancelled` is `true` from then on, also in a node
  that caught the interrupt and cleared the flag: such a node is not retried, and whatever it returns is dropped, so
  `recover` runs it again. A node that ignores the interrupt altogether is given up on after twice the claim's `ttl`:
  the run ends `Cancelled` or `DeadlineExceeded` without it, nothing more of it is committed, and its thread's claim is
  released, though the node's own thread runs on until it returns.
- `maxConcurrency` (default 16) is how many tasks of one superstep run at once.
- `maxTokens` (default none) is enforced by an agent's model step. Nodes of your own graph do not read it.

`RunBudgets(...)` throws `IllegalArgumentException` for a value that is not positive; `RunBudgets.of(...)` returns a
`ValidationError` instead, for values read from outside.

### Without a runtime

`CompiledGraph` can also be driven by hand, with no checkpoints, events or superstep limit: `graph.start(input)` gives
an `Execution`, and `graph.step(threadId, execution, config)` runs one superstep and returns `Step.Next(execution)` or
`Step.Done(result)`. This is mostly useful in tests.

## Interrupts and resuming

A node pauses for an answer by returning `NodeResult.Suspend(update, question, resumeAt)`, where `resumeAt` is a
`ResumeRef[Q, A]` issued by `b.resumeNode[Q, A]` (or `b.declareResume`). Its update commits with the superstep, sibling
tasks finish and commit, and the run ends `RunResult.Suspended`, its thread `Suspended`. Each `PendingInterrupt`
carries the question as JSON. Answering it schedules the resume node with `Resumed(question, answer)`:

```scala
import org.llm4s.agent.graph.*
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

val draft    = StateKey.replace[String]("draft", "")
val approved = StateKey.replace[Boolean]("approved", false)

val b = GraphBuilder("publish", "v1")
// the continuation: receives the question it was asked and the answer given
val decide = b.resumeNode[String, Boolean]("decide", writes = Set(approved)) { (resumed, _, _) =>
  NodeResult.Continue(Command.empty.update(approved, resumed.answer))
}
val write = b.node[String]("write", writes = Set(draft)) { (topic, _, _) =>
  val text = s"A short note on $topic."
  NodeResult.Suspend(StateUpdate.update(draft, text), text, decide)
}

def interruptsOf(result: RunResult[Boolean]): Result[Vector[PendingInterrupt]] = result match
  case RunResult.Suspended(_, interrupts, _) => Right(interrupts)
  case other                                 => Left(ValidationError("run", s"expected a suspension, got $other"))

val runtime = GraphRuntime.inMemory()
val thread  = ThreadId("note-1")

val approval: Result[RunResult[Boolean]] =
  for
    graph   <- b.compile(write)(_.get(approved))
    first   <- runtime.start(thread, graph, "tides").flatMap(_.await())
    pending <- interruptsOf(first)
    answers  = pending.map(i => i.id -> decide.answer(true)).toMap
    second  <- runtime.resume(thread, graph, answers).flatMap(_.await())
  yield second
```

`ResumeRef.answer` encodes a typed answer. `resume` takes any non-empty subset of the pending interrupts; the
unanswered ones stay parked, and the run suspends again if nothing else can proceed. An answer that does not decode,
or an id that is not pending, is refused with `GraphError.InvalidResume` and changes nothing. A suspended task makes
no join arrival: its continuation stands in for it, so a join it belongs to stays closed until the continuation
completes.

To pause at a node without changing its code, use a static breakpoint: `RunConfig(interruptBefore = ...)` or
`interruptAfter`, answered with `Breakpoint.proceed`. See [breakpoints on any graph](human-review#on-any-graph).

## Failures and retries

A node fails by returning `NodeResult.Fail(error)` or by throwing. The run then ends
`RunResult.Failed(state, GraphError.NodeFailed(nodeId, taskId, cause))`, and its checkpoint stays `Running`. The
errors you are most likely to meet:

| Error | Cause | Next step |
|---|---|---|
| `NodeFailed` | a node failed or threw | `recover` |
| `Cancelled` | `handle.cancel()` | `recover` |
| `DeadlineExceeded` | the run's `timeout` passed | `recover` with a new budget |
| `SuperstepLimitExceeded` | `maxSupersteps` reached | `recover` |
| `UndeclaredWrite`, `InvalidRoute`, `UnknownStateKey`, `StateUpdateFailed`, `UnsatisfiedJoin` | a bug in the graph | fix the graph |
| `ThreadBusy`, `IncompleteRun`, `PendingInterrupts`, `NotSuspended`, `NothingToRecover` | the call does not fit the thread's state | nothing ran; see [Runtime and threads](#runtime-and-threads) |

`runtime.recover(threadId, graph)` continues a `Running` thread with no new input. Tasks that completed before the
failure keep their results and are not run again; failed and unstarted tasks run again.

To retry inside the run instead, give the node a `RetryPolicy`. A node that fails is run again against the same
committed state while its error passes `retryOn` and attempts remain, waiting `initialBackoff * backoffFactor^(n-1)`
(at most `maxBackoff`) between attempts. By default only transient errors are retried, by the library's retry rule
(`RetryPolicy.transientOnly`). A cancellation, a suspension and an update or route the runtime rejects are never
retried.

```scala
import org.llm4s.agent.graph.*
import org.llm4s.types.Result
import scala.concurrent.duration.*

// stands for your own call to a slow or flaky service
def lookUp(term: String): Result[String] = Right(s"definition of $term")

val definition = StateKey.replace[String]("definition", "")
val b          = GraphBuilder("glossary", "v1")
val define = b.node[String](
  "define",
  writes = Set(definition),
  retry = RetryPolicy(maxAttempts = 4, initialBackoff = 200.millis),
  cache = Some(CachePolicy(ttl = Some(10.minutes), maxEntries = 256))
) { (term, _, _) =>
  NodeResult.fromResult(lookUp(term).map(text => Command.empty.update(definition, text)))
}
```

`RetryPolicy.none`, one attempt, is the default for every node.

## Caching

A `CachePolicy` declares that a node's result depends only on its input, so a repeated input is answered from memory
without running the node, as `define` above does. The key is the node, its input schema version and the encoded input;
only `Continue` results are stored. The node must not read `ThreadState`, the clock or an external system that
changes, and should not rely on `emit` or `progress`, which a cache hit skips. The cache belongs to the
`CompiledGraph` instance and is shared by its runs. It is not checkpointed: another instance, another process or a
restart starts empty. `ttl` (default none) bounds how long an entry is served, and `maxEntries` (default 1024) evicts
the least recently used.

## Streaming events

A run records durable events in its thread's log: `RunStarted`, `TaskCompleted`, `CheckpointCommitted`,
`RunCompleted`, `RunSuspended`, `RunFailed` and the rest of `RunEvent`. `runtime.subscribe(threadId)(listener)`
replays them and delivers new ones as `StreamEvent.Durable(record)`, followed by live progress as `StreamEvent.Live`:

```scala
import org.llm4s.agent.graph.*
import org.llm4s.types.Result

def watch(runtime: GraphRuntime, thread: ThreadId): Result[Subscription] =
  runtime.subscribe(thread) {
    case StreamEvent.Durable(record) =>
      println(s"${record.seq} ${record.event.productPrefix} ${record.nodeId.getOrElse("")}")
    case StreamEvent.Disconnected(lastSeq, reason) => println(s"ended after $lastSeq: $reason")
    case _                                         => ()
  }
```

A subscription belongs to the thread, not the run: it keeps delivering later runs until you call `cancel()`. To be
sure of every live event of a run, pass `observer = Some(Observer(capacity, listener))` to `start`, `recover` or
`resume`; it is subscribed before the run begins.

A node sends its own events through its `RunContext`: `context.emit(name, version, payload)` is durable and commits
with the task, and `context.progress(...)` is live only. `EventType[A]` gives them a typed payload; see
[streaming your own graphs](streaming#your-own-graphs).

## An agent is a graph

`Agent.builder(...).build()` compiles the agent, its handoff targets and its middleware into one graph, `agent.loop.graph`
(a `CompiledGraph[AgentInput, TurnOutput]` whose id is the agent's id), and runs each turn on a thread of its own
`GraphRuntime`. That is why agent threads have checkpoints, `recover`, `resume`, breakpoints by node id
(`NodeId("assistant/model")`) and the same event log. To see the nodes:

```scala
println(agent.loop.graph.toMermaid)
```

To use agents inside your own graph, call them from a node, as the
[cookbook recipe](../../examples/cookbook/multi-agent-graph) does. An agent called from a node runs its own turn, on
its own runtime and thread, with its own `RunConfig`; cancelling the graph run interrupts the node, and with it a
blocking `agent.run`.

## See also

- [Several agents in one graph](../../examples/cookbook/multi-agent-graph): specialists in parallel and an editor
  behind a static join, runnable with no API key
- [Human review](human-review): breakpoints and approvals
- [Durable checkpointers](durable-checkpointers): stores, recovery after a crash, history, fork and retention
- [Streaming events](streaming): event types, replay and subscriptions across runtimes
- [Multi-agent orchestration](../patterns/multi-agent-orchestration): choosing between handoffs, delegation and a graph
