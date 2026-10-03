# Run API and event dispatch - design (#1277)

Slice 1 of [#1271](https://github.com/llm4s/llm4s/issues/1271), Stage 0 of the typed agent runtime
([#1266](https://github.com/llm4s/llm4s/issues/1266)). Module `llm4s-agent`, package
`org.llm4s.agent.graph`. Slices 2 ([#1278](https://github.com/llm4s/llm4s/issues/1278), `AgentTool`)
and 3 ([#1279](https://github.com/llm4s/llm4s/issues/1279), `AgentMiddleware`) build on the types
defined here.

## Goal

Give the graph runtime its public run API before Stage 1 builds `Agent.run` on it:

- `RunContext`, `RunConfig` and `RunBudgets` replace `NodeContext` and `compile(maxSupersteps)`.
- `RunHandle` (`await`, `status`, `cancel`, `subscribe`) replaces synchronous runs; cancellation and
  deadlines, implemented by thread interruption in #1270, get a public API.
- An ordered dispatcher per subscriber, with bounded queues and disconnection of lagging subscribers,
  replaces delivery on the committing thread.
- Runtime events reach core's tracing SPI through a bridge in `llm4s-agent`; core does not change.
- Locks a virtual thread can take stop pinning its carrier.

No module is frozen; source breaks are recorded in the CHANGELOG and the design doc, with no shims.

## Decisions

| Question | Decision |
|---|---|
| Who runs a run | Admission and the claim commit run on the caller's thread; the run loop then runs on a runtime-owned virtual thread. Entry points return `Result[RunHandle[O]]`; a `Left` means no run exists and the thread is unchanged. |
| Dependencies | Captured by node closures when the graph is built. `RunContext` carries identities (position, tenant, principal, metadata), and per-tenant or per-thread resources come from captured resolvers keyed by those identities. No `RunDependencies` bag, no context type parameter. Restore rebinds because checkpoints are data and the caller supplies a freshly built graph. |
| Tenant | Part of the thread's identity: recorded on every checkpoint and checked at admission (`TenantMismatch`). `principal` is recorded on run events, never checked. |
| Lagging subscribers | Durable overflow disconnects with the last delivered `seq`; live overflow drops and reports `LiveGap(n)`. Capacity is per subscription. |
| Tracing boundary | `RunEvent` stays in `llm4s-agent`. `TracingSubscriber` projects durable events onto the existing `TraceEvent.CustomEvent`. No new core `TraceEvent` cases until Stage 1 shows a need. |
| Budgets | Per run only, in `RunConfig`; no graph-level defaults. Deadline expiry is `DeadlineExceeded`, distinct from `Cancelled`, and recoverable. |
| In-memory runs | `GraphRuntime.inMemory()` is the in-memory runtime; `CompiledGraph.run` is removed. `step` and `resume(execution, ...)` stay as the low-level stepping API. |

## Types

```scala
// GraphIds.scala, beside ThreadId and RunId
opaque type TenantId  = String
opaque type Principal = String

final case class RunBudgets private (
  maxSupersteps:  Int,                    // > 0
  timeout:        Option[FiniteDuration], // > 0 when set
  maxConcurrency: Int                     // > 0
)
object RunBudgets:
  def apply(maxSupersteps: Int = 1000, timeout: Option[FiniteDuration] = None, maxConcurrency: Int = 16): RunBudgets
  def of(maxSupersteps: Int = 1000, timeout: Option[FiniteDuration] = None, maxConcurrency: Int = 16): Result[RunBudgets]
  val default: RunBudgets
  // withMaxSupersteps, withTimeout (FiniteDuration or Option), withMaxConcurrency
```

`apply` throws `IllegalArgumentException` for a non-positive value (`require`, as a programming
error); `of` returns `Left(ValidationError)`. `copy` is private.

```scala
final case class RunConfig private (
  runId:     RunId,
  tenantId:  Option[TenantId],
  principal: Option[Principal],
  budgets:   RunBudgets,
  metadata:  Map[String, String]
)
object RunConfig:
  def apply(
    runId: RunId = RunId.random(),
    tenantId: Option[TenantId] = None,
    principal: Option[Principal] = None,
    budgets: RunBudgets = RunBudgets.default,
    metadata: Map[String, String] = Map.empty
  ): RunConfig
  // withRunId, withTenantId, withPrincipal, withBudgets, withMetadata
```

`RunConfig()` is evaluated per call (default arguments), so each call gets a fresh `RunId`.
`RunId.random()` is new: a random UUID string. `TenantId` and `Principal` follow the existing id
pattern (`apply`, `value`).

```scala
final case class RunPosition(
  threadId:     ThreadId,
  runId:        RunId,
  checkpointId: String,   // the checkpoint whose frontier this task runs in
  taskId:       TaskId,
  nodeId:       NodeId,
  superstep:    Int
)

final class RunContext private[graph] (
  val config:   RunConfig,
  val position: RunPosition,
  sink:         NodeEventSink
):
  def emit(name: String, version: Int, payload: ujson.Value): Unit
  def progress(payload: ujson.Value): Unit
  /** The task thread's interrupt flag, read without clearing it. */
  def isCancelled: Boolean

trait GraphNode[I]:
  def run(input: I, state: ThreadState, context: RunContext): NodeResult
```

`NodeContext` is deleted. Fencing tokens (Stage 2) and the tool-call id (slice 2's `ToolContext`) are
not part of `RunPosition`.

### Tenant on checkpoints

`Checkpoint` gains `tenantId: Option[String]`, and `Checkpoint.CurrentFormat` is incremented, with a
migration that reads earlier checkpoints as `tenantId = None`. Every checkpoint a run writes records
that run's tenant. Admission compares the run's `tenantId` with the latest checkpoint's and refuses a
difference with `GraphError.TenantMismatch(threadId, expected: Option[String], actual: Option[String])`;
`None` and `Some` differ. A thread with no checkpoint accepts any tenant.

`RunEvent.RunStarted`, `RunRecovered` and `RunResumed` gain `principal: Option[String]` and
`tenantId: Option[String]`.

### Low-level stepping API

`CompiledGraph.run` is removed. The stepping calls take the thread and config, so the runtime can
build each task's `RunContext`:

```scala
def step(threadId: ThreadId, execution: Execution, config: RunConfig): Step[O]
def resume(execution: Execution, answers: Map[InterruptId, ujson.Value]): Result[Execution] // unchanged
```

`step` uses `config.budgets.maxConcurrency` for the superstep's task executor and checks no
superstep limit (callers driving `step` own their loop). Outside a `GraphRuntime`, `emit` and
`progress` are no-ops and `checkpointId` is `""`.

## Running a run

```scala
final class GraphRuntime(checkpointer: Checkpointer, clock: Clock = Clock.systemUTC()):
  def start[I, O](threadId: ThreadId, graph: CompiledGraph[I, O], input: I,
                  config: RunConfig = RunConfig(), durability: Durability = Durability.Sync): Result[RunHandle[O]]
  def recover[I, O](graph: CompiledGraph[I, O], threadId: ThreadId,
                    config: RunConfig = RunConfig(), durability: Durability = Durability.Sync): Result[RunHandle[O]]
  def resume[I, O](graph: CompiledGraph[I, O], threadId: ThreadId, answers: Map[InterruptId, ujson.Value],
                   config: RunConfig = RunConfig(), durability: Durability = Durability.Sync): Result[RunHandle[O]]
  def subscribe(threadId: ThreadId, afterSeq: Long = 0L, capacity: Int = 1024)(
    listener: StreamEvent => Unit
  ): Result[Subscription]

object GraphRuntime:
  def inMemory(clock: Clock = Clock.systemUTC()): GraphRuntime   // over a new InMemoryCheckpointer

enum RunStatus:
  case Running, Completed, Suspended, Failed

trait RunHandle[O]:
  def threadId: ThreadId
  def runId: RunId
  def status: RunStatus
  def await(): Result[RunResult[O]]
  def cancel(): Unit
  def subscribe(capacity: Int = 1024)(listener: StreamEvent => Unit): Result[Subscription]
```

`Durability` stays a parameter of each call, after `config`: it is a storage choice, not a budget or an
identity, so `RunConfig` does not carry it. The `runId` parameter goes; it is `config.runId`.

### Admission (caller's thread)

In order; any `Left` leaves the thread unchanged:

1. In-process exclusivity: a thread with a live run in this runtime fails with `ThreadBusy`.
2. Read the latest checkpoint and check its status (`IncompleteRun`, `PendingInterrupts`,
   `NothingToRecover`, `NotSuspended`), exactly as today.
3. Tenant check (`TenantMismatch`).
4. Restore, decode the answers (`resume`), re-check reused pending writes (`recover`).
5. Commit the claim (`ThreadBusy` on a lost claim, `CheckpointWriteFailed` otherwise).

The thread stays in the runtime's `active` set from step 1 until the run thread exits - not until
admission returns - so `recover` cannot mistake a live run's `Running` checkpoint for an abandoned one.
A `Left` at steps 2-5 releases it immediately.

### Execution (run thread)

After the claim the run loop runs on a new virtual thread owned by the runtime, named
`llm4s-run-<threadId>`. All of #1270's cancellation semantics are unchanged, applied to that thread:
supersteps run in a bounded Ox scope (`budgets.maxConcurrency`), every task is forked, an interrupted
task records nothing, a cancelled run commits `RunCancelled`, leaves its checkpoint `Running`, and
returns `Failed(Cancelled(threadId, lastCheckpoint))`. The superstep limit is
`budgets.maxSupersteps`, counted per run.

- `cancel()` interrupts the run thread. It is idempotent and a no-op once the run has ended. A run
  that has already submitted its completed or suspended checkpoint still finishes with that outcome.
- **Deadline.** When `budgets.timeout` is set, the deadline is fixed at admission (claim commit). On
  expiry the runtime records the cause `Expired` and interrupts the run thread. The cancellation path
  then commits `RunEvent.RunTimedOut` instead of `RunCancelled` and returns
  `Failed(GraphError.DeadlineExceeded(threadId, lastCheckpoint))`. `cancel()` records `Cancelled`.
  The first cause recorded wins (an atomic compare-and-set), so a race between them reports exactly
  one. `DeadlineExceeded` is recoverable: `recover` with a new budget continues without re-running
  finished work.
- **`await()`** blocks until the run ends and returns `Right(result)`; the result is retained, so
  every call returns the same value. If the awaiting thread is interrupted, `await` returns
  `Left(CancelledError)` with the interrupt flag still set and the run continues; only `cancel()`
  stops a run.
- **`status`** is non-blocking: `Running` until the result is set, then the result's case.
- **`handle.subscribe`** subscribes to the thread from the sequence number just before this run's
  claim event, so it replays this run from its start whenever it is called.

## Event dispatch

```scala
enum StreamEvent:
  case Durable(record: EventRecord)
  case Live(threadId: String, runId: String, taskId: String, nodeId: String, payload: ujson.Value)
  /** `dropped` live events were discarded at this point because the queue was full. */
  case LiveGap(dropped: Int)
  /** The subscription has ended; always the last event. Resubscribe with `afterSeq = lastSeq`. */
  case Disconnected(lastSeq: Long, reason: DisconnectReason)

enum DisconnectReason:
  case Lagging
  case ListenerFailed(cause: Throwable)
  case ReplayFailed(error: LLMError)
```

- **One dispatcher per subscription:** a bounded queue of `capacity` entries drained by its own
  virtual thread. The listener is only ever called on that thread, sequentially, in order - never on
  a task, run, writer or committing thread.
- **Commit and delivery are decoupled.** The commit path offers events to each subscriber's queue
  without blocking. Commit and hand-off happen under one lock, so each queue receives events in
  commit order.
- **Durable overflow:** a durable event that does not fit marks the subscriber disconnected; nothing
  further is queued for it. The dispatcher delivers what is already queued (contiguous), then
  `Disconnected(lastSeq, Lagging)`, where `lastSeq` is the last durable `seq` delivered, and stops.
- **Live overflow:** a live event is accepted only while at least two slots are free, so the gap
  marker always fits; otherwise it is dropped and counted. The next accepted event (live or durable)
  is preceded by `LiveGap(n)`.
- **Replay, then switch.** `subscribe` validates `capacity > 0` and returns at once; replay runs on
  the dispatcher thread. It reads pages (`eventsAfter`, 500 at a time) until a page is empty, then,
  under the hub lock, reads one final page and joins the live set. Durable events are de-duplicated
  by `seq`, so a commit landing during the switch is delivered exactly once. Live events begin after
  the switch. A failed read ends the subscription with `Disconnected(lastSeq, ReplayFailed(error))`.
- **A throwing listener** ends its subscription with `Disconnected(lastSeq, ListenerFailed(cause))`
  (today the exception is swallowed). `lastSeq` excludes the event that threw.
- **`Subscription.cancel()`** stops the dispatcher; nothing further is delivered, not even
  `Disconnected`.
- Unchanged guarantees: ascending `seq`, no gaps or duplicates, delivery only after the commit that
  numbered an event, in every durability mode.

## Tracing bridge

```scala
object TracingSubscriber:
  def attach(runtime: GraphRuntime, threadId: ThreadId, tracing: Tracing, afterSeq: Long = 0L): Result[Subscription]
```

Each `Durable` event becomes `TraceEvent.CustomEvent(s"graph.$name", data, record.timestamp)` where
`name` is the `RunEvent` case in snake case (`graph.run_started`, `graph.task_failed`,
`graph.run_timed_out`, `graph.custom`), and `data` holds `threadId`, `runId`, `seq`, `checkpointId`,
`taskId`, `nodeId` and the event's fields. Failures are `CustomEvent`s too: `ErrorOccurred` needs a
`Throwable`, which the event log does not keep. `Live`, `LiveGap` are not traced; `Disconnected` is
logged at WARN. A `Left` from `tracing.traceEvent` is logged at WARN and the subscription continues.

## Carrier pinning

Every lock a task or run thread can take moves from `synchronized` to
`java.util.concurrent.locks.ReentrantLock`: the commit lock, the `active` set, `EventHub`,
`OnExitCommitter`, `TaskSink` and `InMemoryCheckpointer` - every `synchronized` in
`org.llm4s.agent.graph`. A source check in a spec (no `synchronized` under `agent/graph`) keeps it so.

## Migration

One CHANGELOG entry, and the same list in the design doc section:

- `NodeContext` → `RunContext`; `context.superstep` → `context.position.superstep`.
- `compile(entry, maxSupersteps)` → `compile(entry)`; limits are `RunBudgets` in `RunConfig`.
- `CompiledGraph.run(input)` → `GraphRuntime.inMemory().start(threadId, graph, input).flatMap(_.await())`.
- `step(execution)` → `step(threadId, execution, config)`.
- `GraphRuntime.start/recover/resume(..., runId, durability)` → `(..., config, durability)`, returning
  `Result[RunHandle[O]]`; cancel with `handle.cancel()` rather than by interrupting the caller.
- `subscribe` gains `capacity`; listeners run on a dispatcher thread; a throwing listener is
  disconnected; `StreamEvent` gains `LiveGap` and `Disconnected`.
- `ToolLoop` and every graph spec move to the new signatures.

## Design doc changes (`docs/design/typed-agent-runtime-design.md`)

- New **§4.6 "Stage 0: run API and event dispatch (#1277)"**, in the style of §4.2-4.5 (contract
  decisions, runtime decisions, limits). Carry-forward becomes §4.7, durable workflow API §4.8; fix
  cross-references.
- Carry-forward rows owned by #1271 are reassigned: run API, cancellation, dispatcher and pinning
  rows close (owned by #1277); `AgentTool` row → #1278; `AgentMiddleware` row → #1279.
- §4's sketch: `Result[RunHandle[O]]` from `start/resume/recover`; `RunContext(config, position)` with
  `emit`, `progress`, `isCancelled`; no `RunDependencies` or `ThreadInterruption`.
- §2.3: dependencies are rebound by resolvers keyed by run identity, not from a bag in `RunContext`.
- §9 decision log: the dependency decision (resolvers keyed by identity; no bag, no context type
  parameter, and why) and the tenant-as-thread-identity decision.

## Tests

- **`RunHandleSpec`:** each admission refusal is a `Left` with the thread unchanged; `cancel`
  mid-superstep; `cancel` after the run ended is a no-op; interrupting the awaiting thread does not
  cancel the run; `await` returns the same result each time; `status` transitions; `handle.subscribe`
  after the end replays the run; a second `start` on a live thread is `ThreadBusy` until the run
  thread exits.
- **`RunBudgetsSpec`:** a timeout stops every sibling, reports `DeadlineExceeded` with
  `RunTimedOut`, and `recover` then completes without re-running finished work; `maxConcurrency` is
  honoured (latch-counting nodes); the superstep limit is per run; `cancel` racing a deadline reports
  exactly one cause; `RunBudgets.of` rejects non-positive values.
- **`TenantSpec`:** mismatch refused by `start`, `recover` and `resume`; `None` vs `Some` differ;
  an earlier-format checkpoint loads as `None`; principal recorded, not checked.
- **`RebindSpec`:** suspend with a graph built on client A, resume with one built on client B - the
  work reaches B; a resolver keyed by thread id resolves the same resource after `recover`.
- **`EventDispatchSpec`:** a lagging subscriber is disconnected with the right `lastSeq` and resumes
  with no gap; `LiveGap` counts drops; a throwing listener is disconnected; a commit during the
  replay-to-live switch is delivered exactly once; a commit never waits on a blocked listener;
  listeners never run on a task thread; no `synchronized` remains under `agent/graph`.
- **`TracingSubscriberSpec`:** the projection's names and data; a backend returning `Left` does not
  end the subscription.
- Existing specs (`GraphRuntimeSpec`, `CancellationSpec`, `SuspendSpec`, `RestoreSpec`,
  `SuperstepSpec`, `JoinSpec`, `CheckpointFormatSpec`, tool-loop specs) are ported; durable
  cancellation goes through `handle.cancel`, in-memory cases through `GraphRuntime.inMemory`.
- Coverage for `llm4s-agent` stays at or above its floor.
