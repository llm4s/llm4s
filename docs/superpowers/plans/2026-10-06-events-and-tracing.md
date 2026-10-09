# Events and Tracing Implementation Plan (#1329)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agent runs stream model output and report typed, content-free durable events through the graph runtime's event channels; `TraceEvent.AgentStateUpdated` is replaced by a run-end `AgentRunEnded` that every tracing backend understands.

**Architecture:** The kernel (`org.llm4s.agent.graph`) gains named live events, a typed `EventType[A]` codec/extractor, and an `Observer` subscribed at admission so no live event is missed. The tool loop emits agent events (`org.llm4s.agent.events`) through those channels. `Agent`/`AgentRun` expose run-scoped subscription and `stream*`; tracing moves to an agent-layer `AgentTracing` that ends each run with `TraceEvent.AgentRunEnded`. fs2/ZIO wrap a shared blocking buffer.

**Tech Stack:** Scala 3.7.1, JDK 21 virtual threads, upickle, ScalaTest, cats-effect 3 + fs2, ZIO 2, sbt.

**Spec:** `docs/superpowers/specs/2026-10-06-events-and-tracing-design.md` (read it first). Background: `docs/design/typed-agent-runtime-design.md` §4.6 (run API and event dispatch), §4.13 (agent loop cutover).

## Global Constraints

- Every commit: `git commit -s` (DCO sign-off) and end the message with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Pre-1.0, nothing frozen: replaced API is deleted, no shims, no `@deprecated`.
- Durable agent events carry no message content (assistant text, thinking, tool arguments, tool results, guardrail reasons). Content goes only in live events and in `AgentRunEnded.messages`.
- Event names match `[a-z0-9_.]{1,64}`; agent events are `agent.<snake_case>`, version 1.
- Use `Result[A]`; no `try/catch/finally` outside safety code; no infix operators; no `sys.env`/`System.getenv`.
- No `synchronized` in `org.llm4s.agent.graph` (a source check in `TracingSubscriberSpec` enforces it); use `ReentrantLock` and `withLock`.
- A duration is a `FiniteDuration`, carried on the wire with `org.llm4s.util.DurationJson.millisRW`.
- `llm4s-observability` and `llm4s-observability-otel` must not depend on `llm4s-agent`; core must not import `org.llm4s.agent`.
- A new top-level public type in `llm4s-core` needs `@Stable` (`sbt stabilityTierCheck`); `AgentRunEnded` is a case of the already-annotated `TraceEvent`, so it needs nothing.
- Run `sbt scalafmtAll` before each commit.

### Refinements to the spec, found while planning

- `Completion` has no `finishReason`, so `ModelCallCompleted` has no `finishReason` field.
- The model step needs its attempt number for `TextDelta`/`ThinkingDelta`, so `ModelStep.next` takes `call: ModelCall` (`context: RunContext`, `agent: AgentId`, `attempt: Int`) rather than a bare `RunContext`.
- `TokenUsage` has no upickle codec, so the durable payload carries `CallUsage` (plain ints) with `fromTokenUsage`/`toTokenUsage`.
- A Block is recognised for tracing by this run's durable `agent.guardrail_blocked` event, not by reading the thread's latest checkpoint, which a later run may already have replaced.

## Review Focus

1. **A subscriber that arrives late** (`AgentRun.subscribe` after `start`) gets durable events replayed from the run's start and only later live events - it must not hang, duplicate, or see another run's events. Test: Task 5, `AgentRunSubscribeSpec` "late subscriber".
2. **Two runs on one thread back-to-back with `withTracing`**: run 2 must not be traced as run 1's `AgentRunEnded`, and run 1's tracing must still complete. Test: Task 7, `AgentRunTracingSpec` "two runs on one thread".
3. **The kernel's own `TaskFailed`/`RunFailed` messages are durable**: a guardrail whose reason quotes the user's text puts that text in the log through the *kernel* event, not an agent event. This predates #1329; the content test checks `agent.*` payloads, and the limit is documented (Task 11). Test: Task 4, `AgentEventsSpec` "agent payloads carry no content".
4. **A streaming client that sends a tool call and no text** must produce no `TextDelta` and the same `Completion` as non-streaming. Test: Task 3, `AgentStreamingSpec` "tool-call-only stream".
5. **Interrupting an fs2/ZIO stream mid-run** must cancel the run and release the subscriber thread (listener blocked in the buffer). Test: Tasks 8 and 9, "interrupting the stream cancels the run".

---

## File Structure

**Kernel (`modules/agent/src/main/scala/org/llm4s/agent/graph/`)**
- Create `EventType.scala` - `EventType[A]`: name, version, codec; `emit`, `progress`, `unapply`.
- Create `Observer.scala` - `Observer(capacity, listener)`.
- Modify `RunEvent.scala` - `StreamEvent.Live` gains `name`, `version`.
- Modify `GraphNode.scala` - `NodeEventSink.progress(name, version, payload)`.
- Modify `RunConfig.scala` - `RunContext.progress(name, version, payload)`.
- Modify `EventHub.scala` - `observe(...)`: a dispatcher that joins the live set at once, with no replay.
- Modify `GraphRuntime.scala` - `observer` on `start`/`recover`/`resume`/`startNew`; sink forwards name/version.
- Modify `RunHandle.scala` - `RunHandle.observation: Option[Subscription]`.

**Agent events (`modules/agent/src/main/scala/org/llm4s/agent/events/`)**
- Create `AgentEventPayloads.scala` - payload case classes and enums.
- Create `AgentEvents.scala` - the `EventType` vals.

**Tool loop and agent**
- Modify `graph/toolloop/ToolLoop.scala` - `ModelStep`/`ModelCall`, streaming `fromClient`, attempts, event emission.
- Create `RunScope.scala` (in `org.llm4s.agent`) - run-scoped listener that cancels itself after the terminal event.
- Modify `AgentRun.scala` - `subscribe`; tracing via `AgentTracing`.
- Create `AgentTracing.scala` - per-run tracing, `AgentRunEnded`.
- Create `AgentEventBuffer.scala` - `private[llm4s]` blocking bridge for fs2/ZIO.
- Modify `Agent.scala` - `stream`, `streamResume`, `streamRecover`.
- Modify `AgentBuilder.scala` - `withStreaming()`.

**Core and backends**
- Modify `modules/core/src/main/scala/org/llm4s/trace/TraceEvent.scala`, `Tracing.scala`, `ConsoleTracing.scala`.
- Modify `modules/observability/src/main/scala/org/llm4s/trace/LangfuseTracing.scala`, `TraceCollector.scala`.
- Modify `modules/trace-opentelemetry/src/main/scala/org/llm4s/trace/OpenTelemetryTracing.scala`.

**Effect wrappers**
- Modify `modules/llm4s-effect/src/main/scala/org/llm4s/effect/cats/AgentIO.scala`; create `AgentStreamItem.scala` beside it.
- Modify `modules/llm4s-zio/src/main/scala/org/llm4s/zio/AgentZ.scala`; create `AgentStreamItem.scala` beside it.

**Samples, docs** - see Tasks 10 and 11.

---

### Task 1: Named live events and `EventType`

**Files:**
- Create: `modules/agent/src/main/scala/org/llm4s/agent/graph/EventType.scala`
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/RunEvent.scala` (the `StreamEvent.Live` case)
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/GraphNode.scala` (`NodeEventSink`)
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/RunConfig.scala` (`RunContext.progress`)
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/GraphRuntime.scala` (`TaskSink.progress`, near line 865)
- Modify tests: `modules/agent/src/test/scala/org/llm4s/agent/graph/EventDispatchSpec.scala`, `GraphRuntimeSpec.scala` (every `progress(` call and `StreamEvent.Live(` pattern)
- Test: `modules/agent/src/test/scala/org/llm4s/agent/graph/EventTypeSpec.scala`

**Interfaces:**
- Produces:
  - `StreamEvent.Live(threadId: String, runId: String, taskId: String, nodeId: String, name: String, version: Int, payload: ujson.Value)`
  - `RunContext.progress(name: String, version: Int, payload: ujson.Value): Unit`
  - `final class EventType[A]` with `name: String`, `version: Int`, `emit(context: RunContext, value: A): Unit`, `progress(context: RunContext, value: A): Unit`, `unapply(event: StreamEvent): Option[A]`, `encode(value: A): ujson.Value`, `decode(payload: ujson.Value): Option[A]`
  - `EventType.apply[A: ReadWriter](name: String, version: Int): EventType[A]` (throws `IllegalArgumentException` for an invalid name or `version < 1`)

- [ ] **Step 1: Write the failing test**

Create `EventTypeSpec.scala`:

```scala
package org.llm4s.agent.graph

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.time.Instant
import java.util.concurrent.{ CountDownLatch, CopyOnWriteArrayList, TimeUnit }
import scala.jdk.CollectionConverters.*

class EventTypeSpec extends AnyFlatSpec with Matchers:

  final case class Ping(n: Int) derives ReadWriter
  private val ping = EventType[Ping]("test.ping", 1)

  private def durable(name: String, version: Int, payload: ujson.Value): StreamEvent =
    StreamEvent.Durable(
      EventRecord("t", 1L, "r", None, None, None, Instant.EPOCH, RunEvent.Custom(name, version, payload))
    )
  private def live(name: String, version: Int, payload: ujson.Value): StreamEvent =
    StreamEvent.Live("t", "r", "task", "node", name, version, payload)

  "EventType" should "match a durable custom event of its name and version" in {
    ping.unapply(durable("test.ping", 1, ujson.Obj("n" -> 3))) shouldBe Some(Ping(3))
  }

  it should "match a live event of its name and version" in {
    ping.unapply(live("test.ping", 1, ujson.Obj("n" -> 4))) shouldBe Some(Ping(4))
  }

  it should "not match another name or version" in {
    ping.unapply(durable("test.pong", 1, ujson.Obj("n" -> 3))) shouldBe None
    ping.unapply(live("test.ping", 2, ujson.Obj("n" -> 3))) shouldBe None
  }

  it should "return None for a payload that does not decode" in {
    ping.unapply(live("test.ping", 1, ujson.Str("nope"))) shouldBe None
  }

  it should "not match kernel events or markers" in {
    ping.unapply(StreamEvent.LiveGap(2)) shouldBe None
    ping.unapply(
      StreamEvent.Durable(EventRecord("t", 1L, "r", None, None, None, Instant.EPOCH, RunEvent.RunCompleted))
    ) shouldBe None
  }

  it should "refuse an invalid name or version" in {
    an[IllegalArgumentException] should be thrownBy EventType[Ping]("Bad Name", 1)
    an[IllegalArgumentException] should be thrownBy EventType[Ping]("ok.name", 0)
  }

  it should "deliver emit as durable and progress as live through a run" in {
    val runtime = GraphRuntime.inMemory()
    val b       = GraphBuilder("event-type", "1")
    val entry = b.node[Unit]("only") { (_, _, ctx) =>
      ping.emit(ctx, Ping(1))
      ping.progress(ctx, Ping(2))
      NodeResult.Continue(Command.empty)
    }
    val graph = b.compile(entry)(_ => Right(())).fold(e => fail(e.message), identity)
    val seen  = new CopyOnWriteArrayList[Ping]()
    val done  = new CountDownLatch(1)
    val run = runtime
      .start(ThreadId("t1"), graph, (), observer = Some(Observer(16, {
        case ping(p) => seen.add(p): Unit
        case StreamEvent.Durable(r) if r.event == RunEvent.RunCompleted => done.countDown()
        case _ => ()
      })))
      .fold(e => fail(e.message), identity)
    run.await().isRight shouldBe true
    done.await(5, TimeUnit.SECONDS) shouldBe true
    seen.asScala.toSet shouldBe Set(Ping(1), Ping(2))
  }
```

The last test uses Task 2's `observer`. Until Task 2 lands, write it with `runtime.subscribe(ThreadId("t1"))` before `start` (a thread subscription made before the run joins the live set once its empty replay ends; add `Thread.sleep(200)` after subscribing for now), and switch it to `observer` in Task 2 Step 6.

Check `GraphBuilder`'s node/compile signatures against `GraphRuntimeSpec.scala` and copy the form it uses for a one-node graph if this sketch differs.

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt "agent/testOnly org.llm4s.agent.graph.EventTypeSpec"`
Expected: compile failure - `EventType` not found, `StreamEvent.Live` takes 5 arguments.

- [ ] **Step 3: Implement**

`RunEvent.scala`, the `Live` case:

```scala
  /**
   * Live-only progress from [[RunContext.progress]]: never persisted, never replayed, no `seq`.
   * `name` and `version` identify the payload, as for [[RunEvent.Custom]].
   */
  case Live(
    threadId: String,
    runId: String,
    taskId: String,
    nodeId: String,
    name: String,
    version: Int,
    payload: ujson.Value
  )
```

`GraphNode.scala`, `NodeEventSink`:

```scala
  def progress(name: String, version: Int, payload: ujson.Value): Unit
```
and in `NodeEventSink.none`: `def progress(name: String, version: Int, payload: ujson.Value): Unit = ()`.

`RunConfig.scala`, `RunContext`:

```scala
  /** Offers live progress named `name`, version `version`; `payload` is snapshotted at the call. */
  def progress(name: String, version: Int, payload: ujson.Value): Unit = sink.progress(name, version, payload)
```
Update the class Scaladoc: "`progress` is live-only ... and carries a name and version like `emit`".

`GraphRuntime.scala`, `TaskSink.progress`:

```scala
      def progress(name: String, version: Int, payload: ujson.Value): Unit =
        hub.live(
          threadId,
          StreamEvent.Live(threadId.value, runId.value, task.id.value, task.node.value, name, version, ujson.copy(payload))
        )
```

Create `EventType.scala`:

```scala
package org.llm4s.agent.graph

import org.slf4j.LoggerFactory
import upickle.default.ReadWriter

import scala.util.Try

/**
 * A typed event: a payload type `A` with a stable `name` and `version`, sent durably with [[emit]]
 * (a [[RunEvent.Custom]], committed with the task) or live with [[progress]] (a
 * [[StreamEvent.Live]]), and matched on either with [[unapply]]:
 *
 * {{{
 * val Ping = EventType[Ping]("my.ping", 1)
 * runtime.subscribe(threadId) {
 *   case Ping(p) => println(p)
 *   case _       => ()
 * }
 * }}}
 *
 * `unapply` is `None` for another name or version, for a kernel event, and for a payload that does
 * not decode (logged at DEBUG), so a listener never throws on an event it does not know.
 */
final class EventType[A] private (val name: String, val version: Int, codec: ReadWriter[A]):

  def encode(value: A): ujson.Value = upickle.default.writeJs(value)(using codec)

  def decode(payload: ujson.Value): Option[A] =
    Try(upickle.default.read[A](payload)(using codec)).fold(
      e =>
        EventType.logger.debug(s"Event '$name' v$version did not decode: ${e.getMessage}")
        None,
      Some(_)
    )

  /** A durable event: committed with the task, delivered after the commit, replayed later. */
  def emit(context: RunContext, value: A): Unit = context.emit(name, version, encode(value))

  /** A live event: delivered at once to current subscribers, never stored. */
  def progress(context: RunContext, value: A): Unit = context.progress(name, version, encode(value))

  def unapply(event: StreamEvent): Option[A] = event match
    case StreamEvent.Durable(record) =>
      record.event match
        case RunEvent.Custom(n, v, payload) if n == name && v == version => decode(payload)
        case _                                                           => None
    case StreamEvent.Live(_, _, _, _, n, v, payload) if n == name && v == version => decode(payload)
    case _                                                                       => None

  override def toString: String = s"EventType($name, v$version)"

object EventType:
  private val logger    = LoggerFactory.getLogger(classOf[EventType[?]])
  private val ValidName = "[a-z0-9_.]{1,64}".r

  /** An event type; an invalid `name` (`[a-z0-9_.]{1,64}`) or a `version` below 1 is a programming error. */
  def apply[A](name: String, version: Int)(using codec: ReadWriter[A]): EventType[A] =
    require(ValidName.matches(name), s"event name '$name' must match [a-z0-9_.]{1,64}")
    require(version >= 1, s"event version must be at least 1, was $version")
    new EventType(name, version, codec)
```

Then update the existing kernel specs: in `EventDispatchSpec.scala` and `GraphRuntimeSpec.scala` replace each `context.progress(payload)` with `context.progress("test.progress", 1, payload)` and each `StreamEvent.Live(a, b, c, d, p)` pattern/constructor with `StreamEvent.Live(a, b, c, d, _, _, p)` (pattern) or `StreamEvent.Live(a, b, c, d, "test.progress", 1, p)` (constructor). Find them with `grep -n 'progress(\|StreamEvent.Live' modules/agent/src/test/scala/org/llm4s/agent/graph/{EventDispatchSpec,GraphRuntimeSpec}.scala`.

- [ ] **Step 4: Run the tests**

Run: `sbt "agent/testOnly org.llm4s.agent.graph.EventTypeSpec org.llm4s.agent.graph.EventDispatchSpec org.llm4s.agent.graph.GraphRuntimeSpec org.llm4s.agent.graph.TracingSubscriberSpec"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
sbt scalafmtAll
git add modules/agent
git commit -s -m "feat(agent)!: named live events and typed EventType (#1329)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `Observer` - subscribe at admission

**Files:**
- Create: `modules/agent/src/main/scala/org/llm4s/agent/graph/Observer.scala`
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/EventHub.scala` (`observe`, `Dispatcher` gains `preJoined`)
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/GraphRuntime.scala` (`start`, `startNew`, `startOn`, `recover`, `resume`, `exclusively`)
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/RunHandle.scala` (`observation`)
- Test: `modules/agent/src/test/scala/org/llm4s/agent/graph/ObserverAdmissionSpec.scala`

**Interfaces:**
- Consumes: Task 1's `StreamEvent.Live` shape.
- Produces:
  - `final case class Observer(capacity: Int, listener: StreamEvent => Unit)`
  - `GraphRuntime.start[I, O](threadId, graph, input, config = RunConfig(), durability = Durability.Sync, observer: Option[Observer] = None): Result[RunHandle[O]]`, and the same trailing `observer` on `recover` and `resume`
  - `private[agent] def startNew[I, O](threadId, graph, input, config, existing: => LLMError, observer: Option[Observer] = None)`
  - `RunHandle.observation: Option[Subscription]` - the observer's subscription, `None` without one

- [ ] **Step 1: Write the failing test**

Create `ObserverAdmissionSpec.scala`:

```scala
package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import scala.jdk.CollectionConverters.*

class ObserverAdmissionSpec extends AnyFlatSpec with Matchers:

  private val Tick = EventType[Int]("test.tick", 1)

  /** One node that sends a live tick as its very first action, then completes. */
  private def eager =
    val b = GraphBuilder("eager", "1")
    val entry = b.node[Unit]("only") { (_, _, ctx) =>
      Tick.progress(ctx, 1)
      NodeResult.Continue(Command.empty)
    }
    b.compile(entry)(_ => Right(())).fold(e => fail(e.message), identity)

  private final class Recorder:
    val events = new CopyOnWriteArrayList[StreamEvent]()
    val ended  = new CountDownLatch(1)
    val listener: StreamEvent => Unit = e =>
      events.add(e)
      e match
        case StreamEvent.Durable(r) if r.event == RunEvent.RunCompleted => ended.countDown()
        case _                                                         => ()

  "An observer" should "see the claim event and the first live event of the run" in {
    val runtime = GraphRuntime.inMemory()
    val rec     = Recorder()
    val handle = runtime
      .start(ThreadId("o1"), eager, (), observer = Some(Observer(64, rec.listener)))
      .fold(e => fail(e.message), identity)
    handle.await().isRight shouldBe true
    rec.ended.await(5, TimeUnit.SECONDS) shouldBe true
    val seen = rec.events.asScala.toVector
    seen.collectFirst { case StreamEvent.Durable(r) => r.event } shouldBe Some(RunEvent.RunStarted(None, None))
    seen.collect { case Tick(n) => n } shouldBe Vector(1)
    handle.observation.foreach(_.cancel())
  }

  it should "see every run's first live event, repeatedly (no race with the run thread)" in {
    val runtime = GraphRuntime.inMemory()
    (1 to 50).foreach { i =>
      val rec = Recorder()
      val handle = runtime
        .start(ThreadId(s"race-$i"), eager, (), observer = Some(Observer(64, rec.listener)))
        .fold(e => fail(e.message), identity)
      handle.await()
      rec.ended.await(5, TimeUnit.SECONDS) shouldBe true
      rec.events.asScala.collect { case Tick(n) => n }.toVector shouldBe Vector(1)
      handle.observation.foreach(_.cancel())
    }
  }

  it should "leave no subscription when admission is refused" in {
    val runtime = GraphRuntime.inMemory()
    val first   = runtime.start(ThreadId("busy"), eager, ()).fold(e => fail(e.message), identity)
    first.await()
    val rec = Recorder()
    // a completed thread accepts a new start, so refuse with an invalid capacity instead
    runtime.start(ThreadId("busy"), eager, (), observer = Some(Observer(1, rec.listener))) match
      case Left(_: ValidationError) => ()
      case other                    => fail(s"expected a ValidationError, got $other")
    // the thread is free: a later run admits, and the refused observer never hears of it
    runtime.start(ThreadId("busy"), eager, ()).flatMap(_.await()).isRight shouldBe true
    Thread.sleep(100)
    rec.events.isEmpty shouldBe true
  }

  it should "work for recover and resume" in {
    // recover: a node that fails on its first run only, so recover completes the thread
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    val b     = GraphBuilder("flaky", "1")
    val entry = b.node[Unit]("only") { (_, _, ctx) =>
      Tick.progress(ctx, calls.incrementAndGet())
      if calls.get == 1 then NodeResult.Fail(ValidationError("x", "first run fails"))
      else NodeResult.Continue(Command.empty)
    }
    val graph   = b.compile(entry)(_ => Right(())).fold(e => fail(e.message), identity)
    val runtime = GraphRuntime.inMemory()
    runtime.start(ThreadId("rec"), graph, ()).flatMap(_.await())
    val rec = Recorder()
    val handle = runtime
      .recover(ThreadId("rec"), graph, observer = Some(Observer(64, rec.listener)))
      .fold(e => fail(e.message), identity)
    handle.await().isRight shouldBe true
    rec.ended.await(5, TimeUnit.SECONDS) shouldBe true
    rec.events.asScala.collect { case Tick(n) => n }.toVector shouldBe Vector(2)
    handle.observation.foreach(_.cancel())
  }
```

Note: `RunEvent.RunStarted(None, None)` assumes the default `RunConfig` has no tenant or principal, which is true.

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt "agent/testOnly org.llm4s.agent.graph.ObserverAdmissionSpec"`
Expected: compile failure - `Observer` not found, `observer` is not a parameter.

- [ ] **Step 3: Implement**

`Observer.scala`:

```scala
package org.llm4s.agent.graph

/**
 * A listener subscribed to a run's thread during admission - after the claim commits and before
 * the run thread starts - so it receives every durable event of the run from its claim, and every
 * live event, which a subscription made after `start` returns can miss. `capacity` is the
 * subscription's queue size, at least 2, as for [[GraphRuntime.subscribe]]. The subscription is
 * thread-scoped like any other: it keeps delivering later runs until cancelled, through
 * [[RunHandle.observation]].
 */
final case class Observer(capacity: Int, listener: StreamEvent => Unit)
```

`EventHub.scala`:

1. Add to `EventHub`:

```scala
  /**
   * A dispatcher that joins the thread's live set now, with no replay, and is not yet running: it
   * queues everything committed or sent on the thread from this moment. The caller holds the thread
   * exclusively, so the next commit is its run's claim. [[Observation.start]] starts it;
   * [[Observation.abandon]] removes it, delivering nothing.
   */
  def observe(threadId: ThreadId, capacity: Int, listener: StreamEvent => Unit): Observation =
    val dispatcher = new Dispatcher(threadId, afterSeq = 0L, capacity, listener, preJoined = true)
    withLock(hubLock)(join(threadId, dispatcher))
    new Observation(threadId, dispatcher)

  final class Observation private[EventHub] (threadId: ThreadId, dispatcher: Dispatcher):
    /** Starts delivery; `lastSeq` is what a `Disconnected` names if nothing durable is delivered. */
    def start(lastSeq: Long): Subscription =
      dispatcher.startObserved(lastSeq)
      dispatcher

    def abandon(): Unit = dispatcher.cancel()
```

2. `Dispatcher` gains a constructor parameter `preJoined: Boolean = false`, and:

```scala
    /** Starts a pre-joined dispatcher: it never replays, and has been in the live set since `observe`. */
    def startObserved(lastSeq: Long): Unit =
      lastDeliveredSeq = lastSeq
      start()
```

3. In `run()`, skip replay and the switch for a pre-joined dispatcher:

```scala
        val ready  = if preJoined then Right(()) else replay().flatMap(_ => switchToLive())
        val ending = ready.fold(identity, _ => drain())
```

`cancel()` on a dispatcher whose thread was never started already works: `thread` is `None`, so it only sets `cancelled` and leaves the live set.

`RunHandle.scala`:
- In `trait RunHandle[O]`, add:

```scala
  /** The subscription of the [[Observer]] this run was admitted with; `None` without one. Cancel it when done. */
  def observation: Option[Subscription]
```

- `DefaultRunHandle` gains a constructor parameter `val observation: Option[Subscription]` (last). Check every other `RunHandle` implementation (`grep -rn "extends RunHandle" modules`) and add `def observation: Option[Subscription] = None` where needed, test doubles included.

`GraphRuntime.scala`:
- Add `observer: Option[Observer] = None` as the last parameter of `start`, `recover` and `resume`, and `observer: Option[Observer] = None` on `startNew`; thread it through `startOn` into `exclusively`.
- `exclusively(threadId, config, observer)(admit)`:

```scala
  private def exclusively[I, O](threadId: ThreadId, config: RunConfig, observer: Option[Observer])(
    admit: StopSignal => Result[Run[I, O]]
  ): Result[RunHandle[O]] = admission(threadId) {
    observer.filter(_.capacity < 2) match
      case Some(o) =>
        Left(ValidationError("capacity", s"must be at least 2 (one slot is reserved for a LiveGap), was ${o.capacity}"))
      case None =>
        val reserving = config.tenantId.map(_.value)
        val holder = withLock(activeLock) {
          val existing = active.get(threadId.value)
          if existing.isEmpty then active.update(threadId.value, reserving)
          existing
        }
        holder match
          case Some(holderTenant) => busy(threadId, config, holderTenant)
          case None =>
            val signal = StopSignal()
            // joined before the claim commits, so it receives the claim and everything after it
            val observation = observer.map(o => hub.observe(threadId, o.capacity, o.listener))
            var launched    = false
            Using.resource(new AutoCloseable {
              def close(): Unit = if !launched then
                observation.foreach(_.abandon())
                release(threadId)
            }) { _ =>
              admit(signal).map { run =>
                val subscription = observation.map(_.start(run.claimSeq - 1))
                val handle = DefaultRunHandle[O](
                  threadId,
                  run.runId,
                  run.claimSeq,
                  signal,
                  (afterSeq, capacity, listener) => subscribe(threadId, afterSeq, capacity)(listener),
                  subscription
                )
                handle.launch(() => run.execute(), run.crashed, () => release(threadId), run.deadline)
                launched = true
                handle
              }
            }
  }
```

Keep the existing Scaladoc of `exclusively` and add: "An `observer` joins the event hub before the claim commits and is started once the run is admitted; a refused admission abandons it, delivering nothing."

Update the `GraphRuntime` class Scaladoc paragraph on `subscribe` to mention `observer`.

- [ ] **Step 4: Run the tests**

Run: `sbt "agent/testOnly org.llm4s.agent.graph.*"`
Expected: PASS, `ObserverAdmissionSpec` included.

- [ ] **Step 5: Switch `EventTypeSpec`'s run test to `observer`** (drop the `subscribe` + `sleep` stand-in from Task 1) and run `sbt "agent/testOnly org.llm4s.agent.graph.EventTypeSpec"`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
sbt scalafmtAll
git add modules/agent
git commit -s -m "feat(agent): an Observer subscribed at admission sees every event of the run (#1329)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Agent event vocabulary, streaming model step, model and handoff events

**Files:**
- Create: `modules/agent/src/main/scala/org/llm4s/agent/events/AgentEventPayloads.scala`
- Create: `modules/agent/src/main/scala/org/llm4s/agent/events/AgentEvents.scala`
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/toolloop/ToolLoop.scala` (`ModelStep`, `callModel`, the model node at lines ~445-494)
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/AgentBuilder.scala` (`withStreaming`, `loopAgent`)
- Modify: every implementation of `ModelStep` in tests: find with `grep -rln "ModelStep" modules/agent/src/test`
- Test: `modules/agent/src/test/scala/org/llm4s/agent/AgentStreamingSpec.scala`

**Interfaces:**
- Consumes: `EventType` (Task 1).
- Produces (package `org.llm4s.agent.events`):
  - `final case class CallUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int, thinkingTokens: Option[Int])`, `CallUsage.fromTokenUsage(u: TokenUsage): CallUsage`, `def toTokenUsage: TokenUsage`
  - `final case class ModelCallCompleted(agent: String, model: String, attempts: Int, toolCalls: Int, usage: Option[CallUsage])`
  - `enum ToolExecutionOutcome { Succeeded, Errored, Denied, Rejected, NeedsApproval, Asked }`
  - `final case class ToolExecuted(agent: String, toolCallId: String, tool: String, duration: FiniteDuration, outcome: ToolExecutionOutcome)`
  - `final case class HandedOff(from: String, to: String)`
  - `enum GuardrailPhase { Input, Output }`
  - `final case class GuardrailBlock(guardrail: String, phase: GuardrailPhase)`
  - `final case class ModelCallStarted(agent: String, attempt: Int)`
  - `final case class TextDelta(attempt: Int, text: String)`, `final case class ThinkingDelta(attempt: Int, text: String)`
  - `final case class ToolCallStarted(toolCallId: String, tool: String, arguments: ujson.Value)`
  - `final case class ToolResult(toolCallId: String, content: String, isError: Boolean)`
  - `object AgentEvents` with vals `ModelCallCompleted`, `ToolExecuted`, `HandedOff`, `GuardrailBlocked` (type `EventType[GuardrailBlock]`), `ModelCallStarted`, `TextDelta`, `ThinkingDelta`, `ToolCallStarted`, `ToolResult`; and `val durable: Set[String]` (the four durable names)
- Produces (package `org.llm4s.agent.graph.toolloop`):
  - `final class ModelCall(val context: RunContext, val agent: AgentId, val attempt: Int)` with `textDelta(text: String): Unit`, `thinkingDelta(text: String): Unit`
  - `trait ModelStep { def next(messages: Vector[Message], tools: ToolSet, call: ModelCall): Result[Completion] }`
  - `ModelStep.fromClient(client: LLMClient, options: CompletionOptions = CompletionOptions(), streaming: Boolean = false): ModelStep`
- Produces (`AgentBuilder`): `def withStreaming(): AgentBuilder`

- [ ] **Step 1: Write the failing test**

Create `AgentStreamingSpec.scala` in `modules/agent/src/test/scala/org/llm4s/agent/`:

```scala
package org.llm4s.agent

import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ModelRequest }
import org.llm4s.error.NetworkError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

class AgentStreamingSpec extends AnyFlatSpec with Matchers:

  private val usage = Some(TokenUsage(promptTokens = 5, completionTokens = 3, totalTokens = 8))

  /** Streams `chunks` of text, then returns the completion of their concatenation. */
  final private class Streaming(chunks: Seq[String], thinking: Seq[String] = Nil, toolCalls: List[ToolCall] = Nil)
      extends LLMClient:
    val streamed = new AtomicInteger(0)
    val completed = new AtomicInteger(0)
    private def completion =
      val text = chunks.mkString
      Completion("c", 0L, text, "test-model", AssistantMessage(Option(text).filter(_.nonEmpty), toolCalls), toolCalls, usage)
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      completed.incrementAndGet()
      Right(completion)
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      streamed.incrementAndGet()
      thinking.foreach(t => onChunk(StreamedChunk("c", None, thinkingDelta = Some(t))))
      chunks.foreach(c => onChunk(StreamedChunk("c", Some(c))))
      toolCalls.foreach(tc => onChunk(StreamedChunk("c", None, toolCall = Some(tc))))
      Right(completion)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024

  private final class Collected:
    val events = new CopyOnWriteArrayList[StreamEvent]()
    val listener: StreamEvent => Unit = e => events.add(e): Unit
    def all: Vector[StreamEvent] = events.asScala.toVector

  private def streamed(agent: Agent, query: String): (Result[AgentResult], Collected) =
    val c = Collected()
    val result = agent.stream(ThreadId(java.util.UUID.randomUUID().toString), query)(c.listener).flatMap(_.await())
    (result, c)

  "A streaming agent" should "send the answer's text as TextDelta events, in order" in {
    val client = Streaming(Seq("Hel", "lo", " world"))
    val agent  = Agent.builder("assistant", client).withStreaming().build().fold(e => fail(e.message), identity)
    val (result, c) = streamed(agent, "hi")
    result.map(_.answer) shouldBe Right(Some("Hello world"))
    c.all.collect { case AgentEvents.TextDelta(d) => d.text } shouldBe Vector("Hel", "lo", " world")
    c.all.collect { case AgentEvents.TextDelta(d) => d.attempt }.distinct shouldBe Vector(1)
    client.streamed.get shouldBe 1
    client.completed.get shouldBe 0
  }

  it should "send thinking as ThinkingDelta" in {
    val agent = Agent
      .builder("assistant", Streaming(Seq("ok"), thinking = Seq("hmm")))
      .withStreaming()
      .build()
      .fold(e => fail(e.message), identity)
    val (_, c) = streamed(agent, "hi")
    c.all.collect { case AgentEvents.ThinkingDelta(d) => d.text } shouldBe Vector("hmm")
  }

  it should "send ModelCallStarted before the first delta and ModelCallCompleted after the call" in {
    val agent = Agent.builder("assistant", Streaming(Seq("a", "b"))).withStreaming().build().fold(e => fail(e.message), identity)
    val (_, c) = streamed(agent, "hi")
    val kinds = c.all.collect {
      case AgentEvents.ModelCallStarted(s)   => s"started:${s.agent}:${s.attempt}"
      case AgentEvents.TextDelta(_)          => "delta"
      case AgentEvents.ModelCallCompleted(m) => s"completed:${m.model}:${m.attempts}:${m.usage.map(_.totalTokens)}"
    }
    kinds.head shouldBe "started:assistant:1"
    kinds.last shouldBe "completed:test-model:1:Some(8)"
  }

  it should "number attempts when a model wrapper retries" in {
    val failures = new AtomicInteger(0)
    final class FlakyOnce extends LLMClient:
      private val inner = Streaming(Seq("x"))
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = inner.complete(c, o)
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        f(StreamedChunk("c", Some("partial")))
        if failures.getAndIncrement() == 0 then Left(NetworkError("dropped", None, "test"))
        else inner.streamComplete(c, o, f)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    val retry = new AgentMiddleware:
      val id = MiddlewareId("retry")
      override def wrapModelCall(request: ModelRequest, context: RunContext)(
        next: ModelRequest => Result[AssistantMessage]
      ): Result[AssistantMessage] = next(request).orElse(next(request))
    val agent = Agent
      .builder("assistant", FlakyOnce())
      .withStreaming()
      .withMiddleware(retry)
      .build()
      .fold(e => fail(e.message), identity)
    val (result, c) = streamed(agent, "hi")
    result.map(_.answer) shouldBe Right(Some("x"))
    c.all.collect { case AgentEvents.ModelCallStarted(s) => s.attempt } shouldBe Vector(1, 2)
    c.all.collect { case AgentEvents.TextDelta(d) => d.attempt -> d.text } shouldBe
      Vector(1 -> "partial", 2 -> "partial", 2 -> "x")
    c.all.collect { case AgentEvents.ModelCallCompleted(m) => m.attempts } shouldBe Vector(2)
  }

  it should "send no TextDelta for a tool-call-only stream, and complete as without streaming" in {
    val call = ToolCall("call-1", "handoff_to_nobody", ujson.Obj())
    // a tool call the agent does not know: the loop records an error result and asks again;
    // the second answer is text
    val client = new LLMClient:
      private val n = new AtomicInteger(0)
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = Left(NetworkError("unused", None, "t"))
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        if n.getAndIncrement() == 0 then
          f(StreamedChunk("c", None, toolCall = Some(call)))
          Right(Completion("c", 0L, "", "m", AssistantMessage(None, Seq(call)), List(call), usage))
        else
          f(StreamedChunk("c", Some("done")))
          Right(Completion("c", 0L, "done", "m", AssistantMessage("done"), Nil, usage))
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    val agent = Agent.builder("assistant", client).withStreaming().build().fold(e => fail(e.message), identity)
    val (result, c) = streamed(agent, "hi")
    result.map(_.answer) shouldBe Right(Some("done"))
    c.all.collect { case AgentEvents.TextDelta(d) => d.text } shouldBe Vector("done")
  }

  "A non-streaming agent" should "call complete and send no deltas" in {
    val client = Streaming(Seq("Hel", "lo"))
    val agent  = Agent.builder("assistant", client).build().fold(e => fail(e.message), identity)
    val (result, c) = streamed(agent, "hi")
    result.map(_.answer) shouldBe Right(Some("Hello"))
    client.completed.get shouldBe 1
    client.streamed.get shouldBe 0
    c.all.collect { case AgentEvents.TextDelta(d) => d } shouldBe empty
    c.all.collect { case AgentEvents.ModelCallCompleted(m) => m.attempts } shouldBe Vector(1)
  }

  "A handoff" should "be reported as a durable HandedOff event" in {
    val target = Agent.builder("physics", Streaming(Seq("E=mc^2")))
    val call   = ToolCall("call-1", "handoff_to_physics", ujson.Obj())
    val root = new LLMClient:
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        Right(Completion("c", 0L, "", "m", AssistantMessage(None, Seq(call)), List(call), usage))
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    val agent = Agent
      .builder("triage", root)
      .withHandoffs(Handoff.to("physics", target, "physics"))
      .build()
      .fold(e => fail(e.message), identity)
    val (_, c) = streamed(agent, "what is energy?")
    c.all.collect { case AgentEvents.HandedOff(h) => h } shouldBe Vector(org.llm4s.agent.events.HandedOff("triage", "physics"))
  }
```

`agent.stream` is Task 5. Until then, in this task, write the helper `streamed` with the kernel directly: build the agent with `.withRuntime(runtime)` on a `GraphRuntime.inMemory()` you hold, subscribe with `runtime.subscribe(threadId)(c.listener)` *before* `agent.run(threadId, query)`, sleep 200 ms after subscribing, and wait for the run's terminal durable event before reading `c.all`. Task 5 Step 5 replaces the helper body with `agent.stream`. Check `NetworkError`'s constructor (`grep -n "case class NetworkError" -A4 modules/core/src/main/scala/org/llm4s/error/*.scala`) and adjust the three call sites if it differs.

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt "agent/testOnly org.llm4s.agent.AgentStreamingSpec"`
Expected: compile failure - `org.llm4s.agent.events` and `withStreaming` not found.

- [ ] **Step 3: Create the vocabulary**

`AgentEventPayloads.scala`:

```scala
package org.llm4s.agent.events

import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.util.DurationJson
import upickle.default.{ macroRW, ReadWriter }

import scala.concurrent.duration.FiniteDuration

// Durable payloads: committed with the task that emitted them, replayable. They carry no message content.

/** A model call's token usage, as plain numbers. */
final case class CallUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int, thinkingTokens: Option[Int])
    derives ReadWriter:
  def toTokenUsage: TokenUsage = TokenUsage(promptTokens, completionTokens, totalTokens, thinkingTokens)

object CallUsage:
  def fromTokenUsage(u: TokenUsage): CallUsage =
    CallUsage(u.promptTokens, u.completionTokens, u.totalTokens, u.thinkingTokens)

/** `agent`'s model call returned; `attempts` counts the calls its model wrappers made, the last one succeeding. */
final case class ModelCallCompleted(agent: String, model: String, attempts: Int, toolCalls: Int, usage: Option[CallUsage])
    derives ReadWriter

/** How a tool call ended for the loop. */
enum ToolExecutionOutcome derives ReadWriter:
  /** The tool returned `Success`. */
  case Succeeded

  /** An error result: the tool's `Error`, a failed argument check, an unknown tool, or a thrown exception. */
  case Errored

  /** A middleware denied the call (an error result starting `Denied:`). */
  case Denied

  /** A reviewer rejected the call at approval. */
  case Rejected

  /** The call suspended for approval. */
  case NeedsApproval

  /** The tool suspended with a question. */
  case Asked

/** One tool call's outcome; `duration` is the middleware chain's, zero for a call refused before it. */
final case class ToolExecuted(
  agent: String,
  toolCallId: String,
  tool: String,
  duration: FiniteDuration,
  outcome: ToolExecutionOutcome
)

object ToolExecuted:
  given ReadWriter[ToolExecuted] =
    import DurationJson.millisRW
    macroRW

/** The active agent changed from `from` to `to`. */
final case class HandedOff(from: String, to: String) derives ReadWriter

/** Where a guardrail blocked the turn. */
enum GuardrailPhase derives ReadWriter:
  case Input, Output

/** A guardrail blocked the turn; the reason is on `AgentStatus.Blocked`, never in the log. */
final case class GuardrailBlock(guardrail: String, phase: GuardrailPhase) derives ReadWriter

// Live payloads: delivered to current subscribers only, never stored. They may carry content.

/** `agent`'s model is being called; a higher `attempt` in the same task discards the earlier attempt's text. */
final case class ModelCallStarted(agent: String, attempt: Int) derives ReadWriter

/** A chunk of the model's answer, for `attempt`. */
final case class TextDelta(attempt: Int, text: String) derives ReadWriter

/** A chunk of the model's thinking, for `attempt`. */
final case class ThinkingDelta(attempt: Int, text: String) derives ReadWriter

/** A tool call passed its argument checks and is about to run through the middleware chain. */
final case class ToolCallStarted(toolCallId: String, tool: String, arguments: ujson.Value) derives ReadWriter

/** The result the loop recorded for a call, as the model will see it. */
final case class ToolResult(toolCallId: String, content: String, isError: Boolean) derives ReadWriter
```

`AgentEvents.scala`:

```scala
package org.llm4s.agent.events

import org.llm4s.agent.graph.EventType

/**
 * The events an agent run sends, each an [[org.llm4s.agent.graph.EventType]] to match a
 * `StreamEvent` with:
 *
 * {{{
 * agent.stream(threadId, "Explain monads") {
 *   case AgentEvents.TextDelta(d)          => print(d.text)
 *   case AgentEvents.ToolExecuted(t)       => println(s"${t.tool}: ${t.outcome} in ${t.duration}")
 *   case _                                 => ()
 * }
 * }}}
 *
 * Durable (stored, replayed, no content): [[ModelCallCompleted]], [[ToolExecuted]], [[HandedOff]],
 * [[GuardrailBlocked]]. Live (current subscribers only): [[ModelCallStarted]], [[TextDelta]],
 * [[ThinkingDelta]], [[ToolCallStarted]], [[ToolResult]].
 */
object AgentEvents:
  val ModelCallCompleted: EventType[org.llm4s.agent.events.ModelCallCompleted] =
    EventType("agent.model_call_completed", 1)
  val ToolExecuted: EventType[org.llm4s.agent.events.ToolExecuted] = EventType("agent.tool_executed", 1)
  val HandedOff: EventType[org.llm4s.agent.events.HandedOff]       = EventType("agent.handed_off", 1)
  val GuardrailBlocked: EventType[GuardrailBlock]                  = EventType("agent.guardrail_blocked", 1)

  val ModelCallStarted: EventType[org.llm4s.agent.events.ModelCallStarted] = EventType("agent.model_call_started", 1)
  val TextDelta: EventType[org.llm4s.agent.events.TextDelta]               = EventType("agent.text_delta", 1)
  val ThinkingDelta: EventType[org.llm4s.agent.events.ThinkingDelta]       = EventType("agent.thinking_delta", 1)
  val ToolCallStarted: EventType[org.llm4s.agent.events.ToolCallStarted]   = EventType("agent.tool_call_started", 1)
  val ToolResult: EventType[org.llm4s.agent.events.ToolResult]             = EventType("agent.tool_result", 1)

  /** The names of the durable agent events. */
  val durable: Set[String] = Set(ModelCallCompleted, ToolExecuted, HandedOff, GuardrailBlocked).map(_.name)
```

- [ ] **Step 4: Streaming `ModelStep` and attempts in `ToolLoop.scala`**

Replace `ModelStep` (lines ~52-63):

```scala
/** One invocation of an agent's model: the task's [[RunContext]], the agent, and this attempt's number (from 1). */
final class ModelCall private[toolloop] (val context: RunContext, val agent: AgentId, val attempt: Int):
  def textDelta(text: String): Unit     = AgentEvents.TextDelta.progress(context, events.TextDelta(attempt, text))
  def thinkingDelta(text: String): Unit = AgentEvents.ThinkingDelta.progress(context, events.ThinkingDelta(attempt, text))

/** The model call: the conversation so far, and the tools on offer, to the model's completion. */
trait ModelStep:
  def next(messages: Vector[Message], tools: ToolSet, call: ModelCall): Result[Completion]

object ModelStep:

  /**
   * Calls `client` with `options`, its `tools` replaced by the loop's
   * [[org.llm4s.agent.graph.tool.ToolSet.toolFunctions]]. With `streaming`, it calls
   * `streamComplete`, sending each chunk's text as a `TextDelta` and its thinking as a
   * `ThinkingDelta`, and returns the accumulated completion; otherwise `complete`.
   */
  def fromClient(client: LLMClient, options: CompletionOptions = CompletionOptions(), streaming: Boolean = false)
    : ModelStep =
    (messages, tools, call) =>
      val withTools = options.withTools(tools.toolFunctions)
      if !streaming then client.complete(Conversation(messages), withTools)
      else
        client.streamComplete(
          Conversation(messages),
          withTools,
          chunk =>
            chunk.thinkingDelta.filter(_.nonEmpty).foreach(call.thinkingDelta)
            chunk.content.filter(_.nonEmpty).foreach(call.textDelta)
        )
```

Add imports at the top of `ToolLoop.scala`: `import org.llm4s.agent.events` and `import org.llm4s.agent.events.AgentEvents`, and `import java.util.concurrent.atomic.AtomicInteger`.

Replace `callModel`:

```scala
  /**
   * The model wrappers' innermost function: `model.next`, guarded, since the stack guards only its
   * hooks. Each invocation is an attempt, numbered from 1 by `attempts` (one counter per model task)
   * and announced live with `ModelCallStarted`. It refuses to call the model while the thread is
   * interrupted, returning `Left(CancelledError)`, so a wrapper that retries never calls a cancelled
   * model again. A NonFatal throw is `Left`; a thrown cancellation - a bare `InterruptedException`
   * too - restores the interrupt flag and is `Left(CancelledError)`.
   */
  private def callModel(model: ModelStep, agent: AgentId, context: RunContext, attempts: AtomicInteger)(
    request: ModelRequest
  ): Result[Completion] =
    if Thread.currentThread().isInterrupted then Left(CancelledError("model"))
    else
      val attempt = attempts.incrementAndGet()
      AgentEvents.ModelCallStarted.progress(context, events.ModelCallStarted(agent.value, attempt))
      attempt(model.next(request.messages, request.tools, ModelCall(context, agent, attempt))) match
        case Right(result) => result
        case Left(thrown) =>
          CancelledError.fromThrowable(thrown, "model") match
            case Some(cancellation) =>
              Thread.currentThread().interrupt()
              Left(cancellation)
            case None => Failure[Completion](thrown).toResult
```

Rename the local `attempt` (the `Int`) to `n` if it shadows the private `attempt(body)` helper; the helper must stay callable.

In the model node (lines ~447-494):

```scala
          for
            history  <- state.get(messages)
            _        <- Message.validateConversation(history.map(_.message).toList)
            transfer <- state.get(LoopKeys.transfer)
            view     <- sent(agent.id, history, transfer, root, preserves)
            request  = ModelRequest(prompt ++ view, nodes.offered)
            attempts = AtomicInteger(0)
            completion <- stack.wrapModelCall(request, context)(callModel(agent.model, agent.id, context, attempts))
            assistant = completion.message
            _ <- assistant.validate
          yield
            AgentEvents.ModelCallCompleted.emit(
              context,
              events.ModelCallCompleted(
                agent.id.value,
                completion.model,
                attempts.get,
                assistant.toolCalls.size,
                completion.usage.map(events.CallUsage.fromTokenUsage)
              )
            )
            ...  // unchanged up to the handoff case
              case Vector(transfer) if calls.size == 1 =>
                val target = handoffs(transfer.name)
                AgentEvents.HandedOff.emit(context, events.HandedOff(agent.id.value, target.agent.id.value))
                appended
                  ...
```

Keep `ModelCallCompleted` before the routing `match`, so it is emitted for every outcome of a successful call (final answer, tool batch, handoff, mixed batch).

Update every `ModelStep` in test sources to the new three-argument form: `(messages, tools, _) => ...` for lambdas, `def next(messages: Vector[Message], tools: ToolSet, call: ModelCall)` for classes. Find them: `grep -rn "ModelStep" modules/agent/src/test modules/it/src modules/samples/src`.

- [ ] **Step 5: `AgentBuilder.withStreaming()`**

Add a constructor field `streaming: Boolean` after `tracing`, threaded through `copy` and the companion `apply` (default `false`). Then:

```scala
  /**
   * Streams the model's answer: each model call uses `streamComplete`, and its text and thinking
   * reach subscribers as live `AgentEvents.TextDelta` and `ThinkingDelta` events. Off by default.
   * It changes nothing stored, so it is not part of the graph's version: a thread runs on with
   * streaming switched on or off.
   */
  def withStreaming(): AgentBuilder = copy(streaming = true)
```

In `loopAgent`: `LoopAgent(agentId, ModelStep.fromClient(client, options, streaming), toolSet)`. Do **not** add `streaming` to `canonical` (the fingerprint). A handoff target's builder uses its own `streaming` flag, as its own client and options already are.

- [ ] **Step 6: Run the tests**

Run: `sbt "agent/test"`
Expected: PASS, `AgentStreamingSpec` included (on the Step 1 kernel-subscribe helper).

- [ ] **Step 7: Commit**

```bash
sbt scalafmtAll
git add modules/agent modules/it modules/samples
git commit -s -m "feat(agent)!: agent events, streaming model step and attempts (#1329)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Tool and guardrail events

**Files:**
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/toolloop/ToolLoop.scala` (`Pipeline`, `boundary`, `input` and `finish` nodes, approval node)
- Test: `modules/agent/src/test/scala/org/llm4s/agent/AgentEventsSpec.scala`

**Interfaces:**
- Consumes: Task 3's `AgentEvents.ToolExecuted`, `ToolCallStarted`, `ToolResult`, `GuardrailBlocked`, `events.ToolExecutionOutcome`, `events.GuardrailPhase`.
- Produces: no new public API. `Pipeline` gains a constructor parameter `agent: AgentId`.

- [ ] **Step 1: Write the failing test**

Create `AgentEventsSpec.scala`. Use the `Scripted` client and the `echoTool` from `modules/agent/src/test/scala/org/llm4s/trace/AgentRunTracingSpec.scala` (copy them into this spec; they are `private`). Collect events with `GraphRuntime.inMemory()` + `.withRuntime(runtime)` + `runtime.subscribe(threadId)(listener)` before the run (sleep 200 ms), waiting for the run's terminal durable event; Task 5 Step 5 moves it to `agent.stream`.

```scala
  "A tool call" should "send ToolCallStarted and ToolResult live, and ToolExecuted durable" in {
    // model: call echo("hello"), then answer "done"
    val (result, c) = collect(agentWith(Scripted(Right(toolCallCompletion), Right(answer("done"))), echoTool), "go")
    result.map(_.answer) shouldBe Right(Some("done"))
    c.all.collect { case AgentEvents.ToolCallStarted(s) => s.tool -> s.arguments } shouldBe
      Vector("echo" -> ujson.Obj("message" -> "hello"))
    c.all.collect { case AgentEvents.ToolResult(r) => r.toolCallId -> r.isError } shouldBe Vector("call-1" -> false)
    val executed = c.all.collect { case AgentEvents.ToolExecuted(t) => t }
    executed.map(t => (t.agent, t.tool, t.toolCallId, t.outcome)) shouldBe
      Vector(("assistant", "echo", "call-1", ToolExecutionOutcome.Succeeded))
    executed.head.duration should be >= scala.concurrent.duration.Duration.Zero
    c.durable.collect { case AgentEvents.ToolExecuted(t) => t }.size shouldBe 1 // it is durable
  }

  it should "report an unknown tool as Errored with no ToolCallStarted" in { /* model calls "nope", then answers */ }

  it should "report a middleware denial as Denied" in {
    // a middleware whose wrapToolCall returns ToolOutcome.Error("Denied: not today")
  }

  it should "report NeedsApproval, then Rejected after a reject" in {
    // withMiddleware(ApprovalMiddleware.unlessReadOnly); run suspends; resume with result.reject(...)
    // first run: ToolExecuted(NeedsApproval); resumed run: ToolExecuted(Rejected), ToolResult isError
  }

  "A guardrail" should "send GuardrailBlocked(Input) for an input block" in {
    // GuardrailMiddleware(input = Seq(new LengthCheck(1, 3))) and query "far too long"
    // status Blocked; events contain GuardrailBlock("LengthCheck"-or-its-name, GuardrailPhase.Input)
  }

  it should "send GuardrailBlocked(Output) for an output block" in {
    // GuardrailMiddleware(output = Seq(new LengthCheck(1, 3))) and an answer "much too long"
  }

  /** Every durable event of `threadId`'s log, replayed from the start. */
  private def replayed(runtime: GraphRuntime, threadId: ThreadId): Vector[EventRecord] =
    val records = new CopyOnWriteArrayList[EventRecord]()
    val sub = runtime
      .subscribe(threadId, afterSeq = 0L) {
        case StreamEvent.Durable(r) => records.add(r): Unit
        case _                      => ()
      }
      .fold(e => fail(e.message), identity)
    Thread.sleep(300) // replay of an in-memory log is immediate; this only lets the dispatcher drain
    sub.cancel()
    records.asScala.toVector

  "Durable agent events" should "be stored once per committed task, and none for a failed task" in {
    val runtime = GraphRuntime.inMemory()
    val client  = Scripted(Left(NetworkError("down", None, "test")), Right(answer("done")))
    val agent   = Agent.builder("assistant", client).withRuntime(runtime).build().fold(e => fail(e.message), identity)
    agent.run(ThreadId("once"), "go").isLeft shouldBe true
    agent.recover(ThreadId("once")).map(_.answer) shouldBe Right(Some("done"))
    val completed = replayed(runtime, ThreadId("once")).collect {
      case r @ EventRecord(_, _, _, _, _, _, _, RunEvent.Custom(AgentEvents.ModelCallCompleted.name, _, _)) => r
    }
    completed.size shouldBe 1
  }

  "Agent payloads" should "carry no content" in {
    val marker  = "SECRET-42"
    val runtime = GraphRuntime.inMemory()
    val call    = ToolCall("call-1", "echo", ujson.Obj("message" -> marker))
    val client = Scripted(
      Right(Completion("c1", 0L, "", "test-model", AssistantMessage(None, Seq(call)), List(call), usage)),
      Right(answer(s"the answer is $marker"))
    )
    val agent = Agent
      .builder("assistant", client)
      .withTools(new ToolRegistry(Seq(echoTool)))
      .withRuntime(runtime)
      .build()
      .fold(e => fail(e.message), identity)
    agent.run(ThreadId("content"), s"tell me $marker").map(_.answer) shouldBe Right(Some(s"the answer is $marker"))
    val agentPayloads = replayed(runtime, ThreadId("content")).collect {
      case EventRecord(_, _, _, _, _, _, _, RunEvent.Custom(name, _, payload)) if AgentEvents.durable(name) => payload
    }
    agentPayloads should not be empty
    agentPayloads.foreach(p => p.render() should not include marker)
  }
```

Write each test body in full, following the first one: build the client with `Scripted(...)` responses, assert with `collect { case AgentEvents.X(x) => ... }`. For the guardrail name, use the `name` the guardrail reports (`grep -n "def name" modules/agent/src/main/scala/org/llm4s/agent/guardrails/builtin/LengthCheck.scala`). `c.durable` is `c.all.collect { case d: StreamEvent.Durable => d }`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt "agent/testOnly org.llm4s.agent.AgentEventsSpec"`
Expected: FAIL - no `ToolCallStarted`, `ToolResult`, `ToolExecuted` or `GuardrailBlocked` events are sent.

- [ ] **Step 3: Implement the tool events in `Pipeline`**

1. Construct the pipeline with the agent: `val pipeline = Pipeline(agent.id, tools, stack, nodes.approval, nodes.askRefs)`, and add `agent: AgentId` as `Pipeline`'s first parameter.

2. Give `record` the outcome and duration, and send the events from it - every result the loop records passes through it:

```scala
    def error(task: ToolTask, message: String, context: RunContext): NodeResult =
      record(task, message, isError = true, context, outcomeOfError(message), Duration.Zero)

    private def outcomeOfError(message: String): ToolExecutionOutcome =
      if message.startsWith("Denied:") then ToolExecutionOutcome.Denied else ToolExecutionOutcome.Errored

    private def record(
      task: ToolTask,
      content: String,
      isError: Boolean,
      context: RunContext,
      outcome: ToolExecutionOutcome,
      duration: FiniteDuration
    ): NodeResult =
      AgentEvents.ToolResult.progress(context, events.ToolResult(task.call.id, content, isError))
      executed(task, context, outcome, duration)
      NodeResult.Continue(
        Command.empty.update(results, ToolResult(task.assistantMessageId, task.call.id, content, isError))
      )

    private def executed(task: ToolTask, context: RunContext, outcome: ToolExecutionOutcome, duration: FiniteDuration)
      : Unit =
      AgentEvents.ToolExecuted.emit(
        context,
        events.ToolExecuted(agent.value, task.call.id, task.call.name, duration, outcome)
      )
```

`error` now takes the `RunContext`; update its callers (the unknown-tool case in the call-tool node, the approval node's `Reject` and unknown tool, `answered`, `checked`'s `refused`, and `outcome`). For the approval node's `Reject`, call `record(task, s"Rejected: $reason", isError = true, context, ToolExecutionOutcome.Rejected, Duration.Zero)` (make `record` non-private for that, or add `def rejected(task, reason, context)`). Pass the context into `checked` so that `refused` can reach `error`.

3. Time the chain and announce it. In `execute` and in `answered`, around `stack.wrapToolCall(...)`:

```scala
      AgentEvents.ToolCallStarted.progress(context, events.ToolCallStarted(call.id, tool.spec.name, call.arguments))
      val started = System.nanoTime()
      val chain   = stack.wrapToolCall(ToolCallRequest(tool.spec, call), toolContext)(innermost(...)(...))
      val took    = (System.nanoTime() - started).nanos
      outcome(tool, task, call, approved, chain, context, took)               // answered: resumed = true too
```

4. In `outcome` (now taking `context` and `took: FiniteDuration`):
- `Success` with allowed writes: build the `ToolResult` command as today, and call `AgentEvents.ToolResult.progress(...)` and `executed(task, context, ToolExecutionOutcome.Succeeded, took)` before returning it (keep the user's `update`; do not route through `record`, which builds an empty command).
- `Error(message)`: `record(task, message, isError = true, context, outcomeOfError(message), took)`.
- `NeedsApproval` that suspends: `executed(task, context, ToolExecutionOutcome.NeedsApproval, took)` before `suspend(...)`. Its two error branches go through `record` with `Errored`.
- `Ask` that suspends: `executed(task, context, ToolExecutionOutcome.Asked, took)` before `NodeResult.Suspend(...)`.
- `Fatal` branches: no event (the task fails or is cancelled; nothing it emits is committed).

Suspension commits a task's custom events with its pending write (`GraphRuntime.scala` line ~731 commits `sink.customEvents` for every task result), so `NeedsApproval`/`Asked` events are stored when the run suspends. Confirm by reading the commit code near line 725 before relying on it; if a suspended task's custom events are not committed, emit those two outcomes live with `ToolResult` only and note it in Task 11's docs.

- [ ] **Step 4: Implement the guardrail event**

Change `boundary` to take the phase and context:

```scala
  private def boundary(error: LLMError, update: StateUpdate, context: RunContext, phase: GuardrailPhase): NodeResult =
    error match
      case cancelled: CancelledError => NodeResult.Fail(cancelled)
      case other =>
        other match
          case blocked: GuardrailBlocked =>
            AgentEvents.GuardrailBlocked.emit(context, GuardrailBlock(blocked.guardrail, phase))
          case _ => ()
        NodeResult.Block(update, other)
```

Import `org.llm4s.agent.graph.middleware.GuardrailBlocked` and `org.llm4s.agent.events.{ GuardrailBlock, GuardrailPhase }`. In the `input` node pass `context, GuardrailPhase.Input` to both `boundary` calls; in `finish` pass `context, GuardrailPhase.Output`. A Block commits its task's custom events (`GraphRuntime.scala` line ~753), so the event is durable.

- [ ] **Step 5: Run the tests**

Run: `sbt "agent/test"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
sbt scalafmtAll
git add modules/agent
git commit -s -m "feat(agent): tool execution and guardrail block events (#1329)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Run-scoped `AgentRun.subscribe` and `Agent.stream*`

**Files:**
- Create: `modules/agent/src/main/scala/org/llm4s/agent/RunScope.scala`
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/AgentRun.scala`
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/Agent.scala`
- Modify: `AgentStreamingSpec.scala`, `AgentEventsSpec.scala` (helpers to `agent.stream`)
- Test: `modules/agent/src/test/scala/org/llm4s/agent/AgentRunSubscribeSpec.scala`

**Interfaces:**
- Consumes: `Observer`, `RunHandle.observation` (Task 2).
- Produces:
  - `private[agent] final class RunScope(runId: RunId, listener: StreamEvent => Unit, onEnd: () => Unit = () => ())` - a `StreamEvent => Unit` passing on only this run's events and every `LiveGap`/`Disconnected`; after this run's terminal durable event it calls `onEnd` and cancels its subscription; `attach(s: Subscription): Unit`; `RunScope.terminal(event: RunEvent): Boolean`
  - `AgentRun.subscribe(capacity: Int = 1024)(listener: StreamEvent => Unit): Result[Subscription]`
  - `Agent.stream(threadId: ThreadId, query: String, config: RunConfig = RunConfig(), history: Seq[Message] = Nil)(listener: StreamEvent => Unit): Result[AgentRun]`
  - `Agent.streamResume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig = RunConfig())(listener: StreamEvent => Unit): Result[AgentRun]`
  - `Agent.streamRecover(threadId: ThreadId, config: RunConfig = RunConfig())(listener: StreamEvent => Unit): Result[AgentRun]`
  - `Agent.StreamCapacity: Int = 1024`

- [ ] **Step 1: Write the failing test**

Create `AgentRunSubscribeSpec.scala`:

```scala
  "agent.stream" should "deliver the run's events, ending with its terminal event" in {
    val agent = Agent.builder("assistant", DeterministicFakeLLMClient(answer("hi"))).build().fold(e => fail(e.message), identity)
    val c     = Collected()
    val run   = agent.stream(ThreadId("s1"), "hello")(c.listener).fold(e => fail(e.message), identity)
    run.await().isRight shouldBe true
    eventually(c.terminalSeen shouldBe true)
    c.all.collect { case StreamEvent.Durable(r) => r.runId }.distinct shouldBe Vector(run.runId.value)
    c.all.last match
      case StreamEvent.Durable(r) => r.event shouldBe RunEvent.RunCompleted
      case other                  => fail(s"last event was $other")
  }

  it should "deliver nothing of a later run on the same thread" in {
    val agent = ...
    val c     = Collected()
    val first = agent.stream(ThreadId("s2"), "one")(c.listener).flatMap(_.await()).fold(e => fail(e.message), identity)
    eventually(c.terminalSeen shouldBe true)
    val second = agent.run(ThreadId("s2"), "two").fold(e => fail(e.message), identity)
    Thread.sleep(200)
    c.all.collect { case StreamEvent.Durable(r) => r.runId }.toSet shouldBe Set(first.runId.value)
  }

  "AgentRun.subscribe" should "replay a late subscriber's durable events from the run's start" in {
    val gate  = new CountDownLatch(1)
    // a client that waits on `gate` before answering, so the run is still going when we subscribe
    val agent = ...
    val run   = agent.start(ThreadId("s3"), "hello").fold(e => fail(e.message), identity)
    val c     = Collected()
    run.subscribe()(c.listener).isRight shouldBe true
    gate.countDown()
    run.await()
    eventually(c.terminalSeen shouldBe true)
    c.all.collectFirst { case StreamEvent.Durable(r) => r.event } shouldBe Some(RunEvent.RunStarted(None, None))
    c.all.collect { case StreamEvent.Durable(r) => r.seq } shouldBe sorted
  }

  "agent.streamResume" should "deliver the resumed run's events" in { /* suspend on ApprovalMiddleware.unlessReadOnly, then streamResume with result.approve(...) */ }

  "agent.streamRecover" should "deliver the recovered run's events" in { /* a client that fails once, then answers */ }

  "agent.stream" should "refuse a blank query without subscribing" in {
    val c = Collected()
    agent.stream(ThreadId("s6"), "  ")(c.listener).isLeft shouldBe true
    Thread.sleep(100)
    c.all shouldBe empty
  }
```

`Collected` records events into a `CopyOnWriteArrayList` and sets `terminalSeen` on `RunCompleted`/`RunSuspended`/`RunFailed`/`RunCancelled`/`RunTimedOut`. Use `org.scalatest.concurrent.Eventually` for `eventually`. Fill each sketched body in full, following the first test.

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt "agent/testOnly org.llm4s.agent.AgentRunSubscribeSpec"`
Expected: compile failure - `stream` and `subscribe` not found.

- [ ] **Step 3: `RunScope`**

```scala
package org.llm4s.agent

import org.llm4s.agent.graph.*

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

/**
 * One run's view of its thread's subscription: passes on the run's durable and live events, every
 * `LiveGap` and a `Disconnected`, and nothing of another run. After the run's terminal durable event
 * it calls `onEnd` and cancels the subscription it is attached to (from the listener, so the cancel
 * neither waits nor interrupts).
 */
final private[agent] class RunScope(runId: RunId, listener: StreamEvent => Unit, onEnd: () => Unit = () => ())
    extends (StreamEvent => Unit):
  private val subscription = new AtomicReference[Option[Subscription]](None)
  private val ended        = new AtomicBoolean(false)

  def apply(event: StreamEvent): Unit = event match
    case StreamEvent.Durable(record) if record.runId == runId.value =>
      listener(event)
      if RunScope.terminal(record.event) then end()
    case StreamEvent.Durable(_)                                => ()
    case live: StreamEvent.Live if live.runId == runId.value   => listener(event)
    case _: StreamEvent.Live                                   => ()
    case other                                                 => listener(other)

  /** Attaches the subscription to cancel at the end; cancels it at once if the run has already ended. */
  def attach(s: Subscription): Unit =
    subscription.set(Some(s))
    if ended.get then s.cancel()

  private def end(): Unit =
    if ended.compareAndSet(false, true) then
      onEnd()
      subscription.get.foreach(_.cancel())

private[agent] object RunScope:
  def terminal(event: RunEvent): Boolean = event match
    case _: RunEvent.RunSuspended | _: RunEvent.RunFailed                     => true
    case RunEvent.RunCompleted | RunEvent.RunCancelled | RunEvent.RunTimedOut => true
    case _                                                                    => false
```

- [ ] **Step 4: `AgentRun.subscribe` and `Agent.stream*`**

In `AgentRun`:

```scala
  /**
   * Subscribes `listener` to this turn's events: its durable events replayed from the turn's start,
   * then live; its live events from now on (a live event sent before this call is missed - use
   * [[Agent.stream]] to receive every one). Run-scoped: nothing of another run on the thread is
   * delivered, and the subscription ends itself after the turn's terminal event. A `Disconnected`
   * reaches the listener only if it fell behind (`Lagging`) or threw.
   */
  def subscribe(capacity: Int = Agent.StreamCapacity)(listener: StreamEvent => Unit): Result[Subscription] =
    val scope = RunScope(runId, listener)
    handle.subscribe(capacity)(scope).map { s =>
      scope.attach(s)
      s
    }
```

`AgentRun.apply` gains `scope: Option[RunScope]`: when present, `handle.observation.foreach(scope.attach)`. Remove `ends` from the companion (now `RunScope.terminal`) and use `RunScope.terminal` in `TracedRun` until Task 7 replaces it.

In `Agent`:

```scala
  /**
   * [[start]], with `listener` subscribed before the turn begins, so it receives every event of the
   * turn - live text deltas included - until the turn's terminal event. A refused start (a blank
   * query, a busy thread, ...) is `Left`, and the listener hears nothing.
   */
  def stream(threadId: ThreadId, query: String, config: RunConfig = RunConfig(), history: Seq[Message] = Nil)(
    listener: StreamEvent => Unit
  ): Result[AgentRun] =
    startWith(threadId, query, config, history, Some(listener))

  /** [[startResume]] with `listener` subscribed first; see [[stream]]. */
  def streamResume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig = RunConfig())(
    listener: StreamEvent => Unit
  ): Result[AgentRun] =
    val (observer, scope) = observed(config, listener)
    runtime.resume(threadId, loop.graph, answers, config, observer = Some(observer)).map(agentRun(_, Some(scope)))

  /** [[startRecover]] with `listener` subscribed first; see [[stream]]. */
  def streamRecover(threadId: ThreadId, config: RunConfig = RunConfig())(listener: StreamEvent => Unit)
    : Result[AgentRun] =
    val (observer, scope) = observed(config, listener)
    runtime.recover(threadId, loop.graph, config, observer = Some(observer)).map(agentRun(_, Some(scope)))

  /** An observer whose listener is `listener` scoped to the run `config` starts. */
  private def observed(config: RunConfig, listener: StreamEvent => Unit): (Observer, RunScope) =
    val scope = RunScope(config.runId, listener)
    (Observer(Agent.StreamCapacity, scope), scope)
```

Refactor `start` into `startWith(threadId, query, config, history, listener: Option[StreamEvent => Unit])`, passing `observer` into `runtime.start`/`runtime.startNew`; `start` calls it with `None`. `agentRun(handle, scope)` passes `scope` to `AgentRun.apply`. `RunConfig.runId` is the run's id (check: `grep -n "runId" modules/agent/src/main/scala/org/llm4s/agent/graph/GraphRuntime.scala | head` - the `Run` takes it from `config`); if the runtime assigns another id, build the `RunScope` lazily from the handle instead (`Observer(capacity, e => scopeRef.get.foreach(_(e)))`, with events before the handle exists queued by the dispatcher, which is not started until admission returns).

In `object Agent`: `val StreamCapacity: Int = 1024` with Scaladoc "Queue size of a `stream*` or `subscribe` subscription".

- [ ] **Step 5: Move the earlier specs' helpers to `agent.stream`**

In `AgentStreamingSpec` and `AgentEventsSpec`, replace the kernel-subscribe helper with:

```scala
  private def streamed(agent: Agent, query: String): (Result[AgentResult], Collected) =
    val c      = Collected()
    val result = agent.stream(ThreadId(java.util.UUID.randomUUID().toString), query)(c.listener).flatMap(_.await())
    eventually(c.terminalSeen shouldBe true)
    (result, c)
```
and drop the explicit runtimes where they were only for subscribing (keep them where a test replays the log).

- [ ] **Step 6: Run the tests**

Run: `sbt "agent/test"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
sbt scalafmtAll
git add modules/agent
git commit -s -m "feat(agent): run-scoped AgentRun.subscribe and Agent.stream, streamResume, streamRecover (#1329)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `TraceEvent.AgentRunEnded` replaces `AgentStateUpdated` in core and every backend

**Files:**
- Modify: `modules/core/src/main/scala/org/llm4s/trace/TraceEvent.scala`, `Tracing.scala` (Scaladoc near line 46), `ConsoleTracing.scala` (line ~113)
- Modify: `modules/observability/src/main/scala/org/llm4s/trace/LangfuseTracing.scala` (lines ~157-162, ~285-305, `traceConversation` ~512-590)
- Modify: `modules/observability/src/main/scala/org/llm4s/trace/TraceCollector.scala` (line ~188)
- Modify: `modules/trace-opentelemetry/src/main/scala/org/llm4s/trace/OpenTelemetryTracing.scala` (lines ~112, ~194)
- Modify samples: `modules/samples/src/main/scala/org/llm4s/samples/basic/BasicLLMCallingWithTrace.scala`, `EnhancedTracingExample.scala`, `LangfuseSampleTraceRunner.scala`, `modules/samples/src/main/scala/org/llm4s/samples/util/TracingUtil.scala`
- Modify tests: `modules/core/src/test/scala/org/llm4s/trace/{TraceEventSpec,ConsoleTracingSpec,NoOpTracingSpec,TracingSpec,TracingEdgeCasesSpec}.scala`, `modules/observability/src/test/scala/org/llm4s/trace/{LangfuseTracingEdgeCasesSpec,TraceCollectorTracingSpec,TraceCollectorPropertySpec}.scala`, `modules/trace-opentelemetry/src/test/scala/org/llm4s/trace/OpenTelemetryTracingBackendSpec.scala`, `modules/it/src/test/scala/org/llm4s/trace/OpenTelemetryTracingSpec.scala`, `modules/samples/src/test/scala/org/llm4s/samples/basic/TracingExampleTest.scala`

**Interfaces:**
- Produces (`org.llm4s.trace.TraceEvent`):

```scala
  case class AgentRunEnded(
    threadId: String,
    runId: String,
    agent: String,
    status: String,
    messages: Seq[Message],
    usage: UsageSummary,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent
```
  with `eventType = "agent_run_ended"` and a `toJson` without messages.

- [ ] **Step 1: Write the failing tests**

In `TraceEventSpec.scala`, replace the `AgentStateUpdated` tests with:

```scala
  "AgentRunEnded" should "serialise a flat summary without messages" in {
    val usage = UsageSummary().add("m", TokenUsage(10, 5, 15), None)
    val e = TraceEvent.AgentRunEnded(
      "thread-1", "run-1", "assistant", "completed",
      Seq(UserMessage("hi"), AssistantMessage("hello")), usage, Instant.EPOCH
    )
    e.eventType shouldBe "agent_run_ended"
    val json = e.toJson
    json("thread_id").str shouldBe "thread-1"
    json("run_id").str shouldBe "run-1"
    json("agent").str shouldBe "assistant"
    json("status").str shouldBe "completed"
    json("message_count").num shouldBe 2
    json("input_tokens").num shouldBe 10
    json("output_tokens").num shouldBe 5
    json.obj.contains("messages") shouldBe false
  }
```

In `TraceCollectorTracingSpec.scala`, replace the `AgentStateUpdated` span test with one asserting an `AgentRunEnded` becomes one span of `SpanKind.AgentCall` named as other spans are, with attributes `thread_id`, `run_id`, `agent`, `status`, `message_count`, `input_tokens`, `output_tokens`.

In `LangfuseTracingEdgeCasesSpec.scala`, replace the conversation test: an `AgentRunEnded` with a user and an assistant message produces a batch whose `trace-create` has `id == runId`, `sessionId == threadId`, `input` = the user message, `output` = the assistant message, `metadata.status`, `metadata.agent`, and one `span-create` per message. Check how the existing spec captures the batch it sends (a stub HTTP sender or a capturing subclass) and reuse that.

In `OpenTelemetryTracingBackendSpec.scala`, replace the `AgentStateUpdated` mapping test: `getSpanKind(AgentRunEnded(...)) == SpanKind.INTERNAL`, and `mapEventToAttributes` gives name `"Agent Run"` with `thread_id`, `run_id`, `agent`, `status`, `message_count` and `gen_ai.usage.input_tokens`/`output_tokens`.

Update `ConsoleTracingSpec`, `NoOpTracingSpec`, `TracingSpec`, `TracingEdgeCasesSpec`, `TraceCollectorPropertySpec`, `it`'s `OpenTelemetryTracingSpec` and `TracingExampleTest`: every `AgentStateUpdated(...)` becomes an `AgentRunEnded(...)` with equivalent intent, and assertions on `logCount`/`log_count` are removed. Find them: `grep -rn "AgentStateUpdated" modules`.

- [ ] **Step 2: Run to verify failure**

Run: `sbt "core/testOnly org.llm4s.trace.*"`
Expected: compile failure - `AgentRunEnded` not found.

- [ ] **Step 3: Implement in core**

`TraceEvent.scala`: delete `AgentStateUpdated` (and its Scaladoc) and add, importing `UsageSummary`:

```scala
  /**
   * An agent run ended. Emitted once per run, from the run's own final state, by the agent's
   * tracing; it carries the turn's conversation for backends that record it (Langfuse turns it into
   * a trace with one span per message). A blocked turn has been removed from the thread by then, so
   * a blocked run carries no messages.
   *
   * @param threadId the conversation's thread; backends use it as the session
   * @param runId    this run
   * @param agent    the agent active when the run ended
   * @param status   `completed`, `suspended`, `step_limit_reached`, `blocked:<guardrail>`,
   *                 `cancelled`, `timed_out` or `failed`
   * @param messages this turn's messages, from its user message on; empty when blocked, cancelled,
   *                 timed out or failed. Not included in [[toJson]], which stays a flat summary.
   * @param usage    the thread's token usage and cost, per model
   */
  case class AgentRunEnded(
    threadId: String,
    runId: String,
    agent: String,
    status: String,
    messages: Seq[Message],
    usage: UsageSummary,
    timestamp: Instant = Instant.now()
  ) extends TraceEvent {
    def eventType: String = "agent_run_ended"
    def toJson: ujson.Value = ujson.Obj(
      "event_type"    -> eventType,
      "timestamp"     -> timestamp.toString,
      "thread_id"     -> threadId,
      "run_id"        -> runId,
      "agent"         -> agent,
      "status"        -> status,
      "message_count" -> messages.size,
      "input_tokens"  -> usage.inputTokens.toDouble,
      "output_tokens" -> usage.outputTokens.toDouble,
      "total_cost"    -> usage.totalCost.toDouble
    )
  }
```

`Tracing.scala`: replace the Scaladoc sentence that mentions `AgentStateUpdated` / `AgentState#toTraceEvent` with: "An agent run reports its end with [[TraceEvent.AgentRunEnded]]; the agent's per-step events arrive as `CustomEvent`s named `graph.*` and `agent.*`."

`ConsoleTracing.scala`, replacing the `AgentStateUpdated` case:

```scala
        case e: TraceEvent.AgentRunEnded =>
          println()
          printSubHeader("AGENT RUN ENDED", BLUE)
          println(s"${GRAY}Timestamp: ${e.timestamp}$RESET")
          println(s"${BLUE}Agent: ${e.agent}  Status: ${e.status}$RESET")
          println(s"${BLUE}Thread: ${e.threadId}  Run: ${e.runId}$RESET")
          println(s"${BLUE}Messages: ${e.messages.size}  Tokens: ${e.usage.inputTokens} in / ${e.usage.outputTokens} out$RESET")
          println()
```

- [ ] **Step 4: Implement in the backends**

`LangfuseTracing.scala`:
- `traceEvent`: `case e: TraceEvent.AgentRunEnded => traceConversation(e)` (every run, messages or not), then `case other => traceSingleEvent(other)`.
- Delete the `AgentStateUpdated` branch of `traceSingleEvent` (~line 285).
- `traceConversation(run: TraceEvent.AgentRunEnded)`: `traceId = run.runId`; `sessionId = run.threadId`; `"name" -> "LLM4S Agent Run"`; input/output from `run.messages` as today; metadata:

```scala
          "metadata" -> ujson.Obj(
            "framework"     -> "llm4s",
            "agent"         -> run.agent,
            "status"        -> run.status,
            "thread_id"     -> run.threadId,
            "message_count" -> run.messages.size,
            "input_tokens"  -> run.usage.inputTokens.toDouble,
            "output_tokens" -> run.usage.outputTokens.toDouble,
            "total_cost"    -> run.usage.totalCost.toDouble
          ),
```
  and `"input" -> ...getOrElse("")`, `"output" -> ...getOrElse("")` (a blocked run has neither). Child spans per message as today. Remove the `session-${System.currentTimeMillis()}` line.

`TraceCollector.scala`, replacing the `AgentStateUpdated` case:

```scala
      case TraceEvent.AgentRunEnded(threadId, runId, agent, status, messages, usage, ts) =>
        Span(
          spanId = spanId,
          traceId = traceId,
          parentSpanId = None,
          name = spanName,
          kind = SpanKind.AgentCall,
          startTime = ts,
          endTime = Some(ts),
          status = SpanStatus.Ok,
          attributes = Map(
            "thread_id"     -> SpanValue.StringValue(threadId),
            "run_id"        -> SpanValue.StringValue(runId),
            "agent"         -> SpanValue.StringValue(agent),
            "status"        -> SpanValue.StringValue(status),
            "message_count" -> SpanValue.LongValue(messages.size.toLong),
            "input_tokens"  -> SpanValue.LongValue(usage.inputTokens),
            "output_tokens" -> SpanValue.LongValue(usage.outputTokens)
          )
        )
```

`OpenTelemetryTracing.scala`: in `getSpanKind` replace `_: TraceEvent.AgentStateUpdated` with `_: TraceEvent.AgentRunEnded`; replace the `AgentStateUpdated` attributes case with:

```scala
      case e: TraceEvent.AgentRunEnded =>
        (
          "Agent Run",
          Attributes
            .builder()
            .put(TraceAttributes.EventType, "agent-run")
            .put("thread_id", e.threadId)
            .put("run_id", e.runId)
            .put("agent", e.agent)
            .put("status", e.status)
            .put("message_count", e.messages.size.toLong)
            .put("gen_ai.usage.input_tokens", e.usage.inputTokens)
            .put("gen_ai.usage.output_tokens", e.usage.outputTokens)
            .build()
        )
```
Check the attribute key constants the file already uses for `gen_ai.usage.*` and use them.

- [ ] **Step 5: Samples**

In `BasicLLMCallingWithTrace.scala`, `EnhancedTracingExample.scala`, `LangfuseSampleTraceRunner.scala` and `util/TracingUtil.scala`, each hand-built `TraceEvent.AgentStateUpdated(status, messageCount, logCount, messages, ...)` becomes:

```scala
TraceEvent.AgentRunEnded(
  threadId = "sample-thread",
  runId = java.util.UUID.randomUUID().toString,
  agent = "sample-agent",
  status = "completed",
  messages = messages,      // the sample's conversation, as it passed before
  usage = UsageSummary()    // or one built from the sample's completion usage, where it has one
)
```
These samples are about the backends, so building the event directly is right (spec, Samples).

- [ ] **Step 6: Run the tests**

Run: `sbt "core/testOnly org.llm4s.trace.*" "observability/test" "traceOpentelemetry/test" "samples/test" "it/Test/compile"`
Then `grep -rn "AgentStateUpdated\|toTraceEvent" modules` - Expected: no hits.
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
sbt scalafmtAll
git add modules/core modules/observability modules/trace-opentelemetry modules/samples modules/it
git commit -s -m "feat(trace)!: TraceEvent.AgentRunEnded replaces AgentStateUpdated in core and every backend (#1329)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: `AgentTracing` - per-run tracing that ends with `AgentRunEnded`

**Files:**
- Create: `modules/agent/src/main/scala/org/llm4s/agent/AgentTracing.scala`
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/AgentRun.scala` (replace `TracedRun`)
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/AgentBuilder.scala` (Scaladoc of `withTracing`)
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/TracingSubscriber.scala` (Scaladoc: kernel graphs; `toTrace` made `private[agent]`)
- Test: `modules/agent/src/test/scala/org/llm4s/trace/AgentRunTracingSpec.scala`

**Interfaces:**
- Consumes: `RunScope` (Task 5), `AgentEvents` (Tasks 3-4), `TraceEvent.AgentRunEnded` (Task 6), `TracingSubscriber.toTrace`.
- Produces:
  - `final private[agent] class AgentTracing(handle: RunHandle[TurnOutput], root: AgentId, tracing: Tracing)` with `detach(): Unit` (the old `TracedRun` contract: waits up to 5 s for the run's last event to be traced)
  - `private[agent] object AgentRun.turnMessages(state: ThreadState): Result[Vector[Message]]` - the messages from the last `UserMessage` on

- [ ] **Step 1: Write the failing tests**

Rewrite the class Scaladoc of `AgentRunTracingSpec` to: "`withTracing` traces each run's durable events as `graph.*`/`agent.*` custom events, its model calls' usage as `TokenUsageRecorded`, and ends each run with one `AgentRunEnded`." Keep its helpers, and add:

```scala
  "A traced run" should "end with one AgentRunEnded carrying the turn's messages" in {
    val tracing = Recording()
    val agent   = traced(Scripted(Right(toolCallCompletion), Right(answer("done"))), tracing)
    val first   = agent.run(ThreadId("t1"), "first").fold(e => fail(e.message), identity)
    val ended   = tracing.all.collect { case e: TraceEvent.AgentRunEnded => e }
    ended.size shouldBe 1
    ended.head.threadId shouldBe "t1"
    ended.head.runId shouldBe first.runId.value
    ended.head.agent shouldBe "assistant"
    ended.head.status shouldBe "completed"
    ended.head.messages.head shouldBe UserMessage("first")
    ended.head.messages.last shouldBe AssistantMessage("done")
    ended.head.usage.inputTokens shouldBe 40 // two calls of 20
  }

  it should "send only the turn's messages on the second turn" in {
    // run(t2, "one") then run(t2, "two"): the second AgentRunEnded's messages start with UserMessage("two")
  }

  it should "trace usage as TokenUsageRecorded per model call" in {
    // two model calls: two TokenUsageRecorded(usage, "test-model", "agent_completion")
  }

  it should "trace agent events as agent.* custom events" in {
    // names include "agent.model_call_completed" and "agent.tool_executed"
  }

  it should "complete without anyone calling await" in {
    val tracing = Recording()
    val agent   = traced(Scripted(Right(answer("done"))), tracing)
    agent.start(ThreadId("t5"), "go").fold(e => fail(e.message), identity) // never awaited
    eventually(tracing.all.collect { case e: TraceEvent.AgentRunEnded => e }.size shouldBe 1)
  }

  it should "trace a blocked turn with no messages and status blocked:<guardrail>" in {
    // an output guardrail blocks "SECRET-42"; AgentRunEnded.status == s"blocked:${name}", messages empty,
    // and no traced event's toJson().render() contains "SECRET-42" except none at all
  }

  it should "trace a failed run as failed, with ErrorOccurred" in {
    // client Left(NetworkError): AgentRunEnded status "failed", messages empty; one ErrorOccurred
  }

  it should "trace a cancelled run as cancelled" in {
    // a client that blocks until interrupted; start, cancel, await; status "cancelled"
  }

  it should "keep two runs on one thread apart" in {
    val tracing = Recording()
    val agent   = traced(Scripted(Right(answer("first answer")), Right(answer("second answer"))), tracing)
    val one     = agent.run(ThreadId("t9"), "one").fold(e => fail(e.message), identity)
    val two     = agent.run(ThreadId("t9"), "two").fold(e => fail(e.message), identity)
    eventually(tracing.all.collect { case e: TraceEvent.AgentRunEnded => e }.size shouldBe 2)
    val ended = tracing.all.collect { case e: TraceEvent.AgentRunEnded => e }
    ended.map(_.runId) shouldBe Vector(one.runId.value, two.runId.value)
    ended.map(_.messages.head) shouldBe Vector(UserMessage("one"), UserMessage("two"))
    ended.map(_.messages.last) shouldBe Vector(AssistantMessage("first answer"), AssistantMessage("second answer"))
  }
```

`Recording` must also record `ErrorOccurred` events: make its `traceError` add `TraceEvent.ErrorOccurred(error, context)` to `events`. Fill the sketched bodies in full following the first test. `failing = true` must still not fail the run (existing test - keep it).

- [ ] **Step 2: Run to verify failure**

Run: `sbt "agent/testOnly org.llm4s.trace.AgentRunTracingSpec"`
Expected: FAIL - no `AgentRunEnded` is traced.

- [ ] **Step 3: Implement `AgentTracing`**

```scala
package org.llm4s.agent

import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.toolloop.{ LoopKeys, Messages, TurnOutcome, TurnOutput }
import org.llm4s.error.CancelledError
import org.llm4s.llmconnect.model.{ Message, UsageSummary }
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.slf4j.LoggerFactory

import java.util.concurrent.{ CompletableFuture, TimeUnit, TimeoutException }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.concurrent.duration.*
import scala.util.{ Failure, Try }

/**
 * One run's tracing. Subscribes to the run's events from its claim (durable replay; live events are
 * not traced) and traces each as `TracingSubscriber` does - `graph.*` and `agent.*` custom events -
 * plus each model call's usage as `TokenUsageRecorded(usage, model, "agent_completion")`. On the
 * run's terminal event it takes the run's result from its handle (set just after the closing
 * commit) and traces one [[TraceEvent.AgentRunEnded]]; a failed run also traces `ErrorOccurred`.
 * Tracing failures are logged at WARN and never fail the run.
 */
final private[agent] class AgentTracing(handle: RunHandle[TurnOutput], root: AgentId, tracing: Tracing):
  private val delivered    = new CompletableFuture[Unit]()
  private val detached     = new AtomicBoolean(false)
  private val blockedBy    = new AtomicReference[Option[String]](None)
  private val subscription = new AtomicReference[Option[Subscription]](None)

  private val scope = RunScope(handle.runId, onEvent, () => ())

  handle
    .subscribe()(scope)
    .fold(
      _ => delivered.complete(()): Unit,
      s =>
        subscription.set(Some(s))
        scope.attach(s)
    )

  private def onEvent(event: StreamEvent): Unit = event match
    case StreamEvent.Durable(record) =>
      trace(TracingSubscriber.toTrace(record))
      event match
        case AgentEvents.ModelCallCompleted(m) =>
          m.usage.foreach(u => trace(TraceEvent.TokenUsageRecorded(u.toTokenUsage, m.model, "agent_completion")))
        case AgentEvents.GuardrailBlocked(g) => blockedBy.set(Some(g.guardrail))
        case _                               => ()
      if RunScope.terminal(record.event) then
        ended(record.event)
        delivered.complete(()): Unit
    case StreamEvent.Disconnected(lastSeq, reason) =>
      AgentTracing.logger.warn(
        s"Tracing of run ${handle.runId.value} on ${handle.threadId.value} ended after seq $lastSeq: $reason"
      )
      delivered.complete(()): Unit
    case _ => ()

  /** Traces the run's AgentRunEnded from its own result, which the run thread sets just after the closing commit. */
  private def ended(terminal: RunEvent): Unit =
    val result = handle.await()
    val state  = result.toOption.map(_.state)
    val usage  = state.flatMap(_.get(LoopKeys.usage).toOption).getOrElse(UsageSummary())
    val active = state.flatMap(_.get(LoopKeys.activeAgent).toOption.flatten).getOrElse(root)
    val (status, messages) = (terminal, result) match
      case (RunEvent.RunCompleted, Right(RunResult.Completed(s, out, _))) =>
        val status = out.outcome match
          case TurnOutcome.Completed        => "completed"
          case TurnOutcome.StepLimitReached => "step_limit_reached"
        (status, AgentRun.turnMessages(s).getOrElse(Vector.empty))
      case (_: RunEvent.RunSuspended, Right(s: RunResult.Suspended)) =>
        ("suspended", AgentRun.turnMessages(s.state).getOrElse(Vector.empty))
      case (RunEvent.RunCancelled, _) => ("cancelled", Vector.empty)
      case (RunEvent.RunTimedOut, _)  => ("timed_out", Vector.empty)
      case (_: RunEvent.RunFailed, _) =>
        blockedBy.get match
          case Some(guardrail) => (s"blocked:$guardrail", Vector.empty)
          case None =>
            result match
              case Right(RunResult.Failed(_, error)) => tracing.traceError(new RuntimeException(error.message), "agent run"): Unit
              case Left(error)                       => tracing.traceError(new RuntimeException(error.message), "agent run"): Unit
              case _                                 => ()
            ("failed", Vector.empty)
      case _ => ("failed", Vector.empty)
    trace(TraceEvent.AgentRunEnded(handle.threadId.value, handle.runId.value, active.value, status, messages, usage))

  private def trace(event: TraceEvent): Unit =
    Try(tracing.traceEvent(event)).toEither.flatMap(_.left.map(e => new RuntimeException(e.message))).left.foreach { e =>
      AgentTracing.logger.warn(s"Tracing ${event.eventType} of run ${handle.runId.value} failed: ${e.getMessage}")
    }

  /** Waits up to the drain time for the run's last event to be traced, then detaches. */
  def detach(): Unit =
    if detached.compareAndSet(false, true) then
      CancelledError.catchInterrupt(Try(delivered.get(AgentTracing.Drain.toMillis, TimeUnit.MILLISECONDS))) match
        case Left(_) => Thread.currentThread().interrupt()
        case Right(Failure(_: TimeoutException)) =>
          AgentTracing.logger.warn(
            s"Tracing of run ${handle.runId.value} on ${handle.threadId.value} did not deliver the run's last event within ${AgentTracing.Drain}; its trace may be incomplete"
          )
        case Right(_) => ()
      subscription.get.foreach(_.cancel())

private[agent] object AgentTracing:
  private val logger = LoggerFactory.getLogger(classOf[AgentTracing])

  /** How long `detach` waits, after the run ends, for its last event to be traced. */
  val Drain: FiniteDuration = 5.seconds
```

Notes for the implementer:
- `EventType.unapply` takes a `StreamEvent`, which is why the code matches `event`, not `record`.
- `traceError` takes a `Throwable`; wrapping the `LLMError` message keeps today's behaviour of not passing a raw `LLMError`. If `LLMError` has a `toThrowable`/`cause`, prefer it (`grep -n "def " modules/core/src/main/scala/org/llm4s/error/LLMError.scala`).
- The `trace` helper's `Try` exists because a backend may throw; keep it minimal.
- `TracingSubscriber.toTrace` becomes `private[agent]` (it is `private` now).

In `AgentRun`:
- Replace `TracedRun` with `AgentTracing`: `AgentRun.apply` builds `tracing.map(AgentTracing(handle, root, _))`, `await` calls `detach()` as before.
- Add to the companion:

```scala
  /** The current turn's messages: from the last user message on. */
  def turnMessages(state: ThreadState): Result[Vector[Message]] =
    state.get(Messages.key).map { stored =>
      val all = stored.map(_.message)
      all.lastIndexWhere { case _: UserMessage => true; case _ => false } match
        case -1 => all
        case at => all.drop(at)
    }
```

`AgentBuilder.withTracing` Scaladoc: "Traces each run to `tracing`: its events as `graph.*` and `agent.*` custom events, each model call's usage as `TokenUsageRecorded`, and its end as one `AgentRunEnded`."

`TracingSubscriber` Scaladoc: add "An `Agent` built `withTracing` traces through `AgentTracing` instead, which adds usage and `AgentRunEnded`; use `attach` for graphs of your own."

- [ ] **Step 4: Run the tests**

Run: `sbt "agent/test"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
sbt scalafmtAll
git add modules/agent
git commit -s -m "feat(agent): trace each run's usage and end it with AgentRunEnded (#1329)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: `AgentEventBuffer` and fs2 `AgentIO.stream`

**Files:**
- Create: `modules/agent/src/main/scala/org/llm4s/agent/AgentEventBuffer.scala`
- Create: `modules/llm4s-effect/src/main/scala/org/llm4s/effect/cats/AgentStreamItem.scala`
- Modify: `modules/llm4s-effect/src/main/scala/org/llm4s/effect/cats/AgentIO.scala`
- Test: `modules/agent/src/test/scala/org/llm4s/agent/AgentEventBufferSpec.scala`, `modules/llm4s-effect/src/test/scala/org/llm4s/effect/cats/AgentIOStreamSpec.scala`

**Interfaces:**
- Consumes: `Agent.stream`, `streamResume`, `streamRecover`, `AgentRun` (Task 5).
- Produces:
  - `final private[llm4s] class AgentEventBuffer(capacity: Int)` with `listener: StreamEvent => Unit` (blocks while full), `take(): Either[LLMError, Option[StreamEvent]]` (blocks; `Right(None)` after the run's terminal event; `Left` on a `Lagging`/`ListenerFailed` disconnect), `close(): Unit` (wakes a blocked `take` and drops the rest)
  - `enum AgentStreamItem { case Event(event: StreamEvent); case Done(result: AgentResult) }` (package `org.llm4s.effect.cats`)
  - `AgentIO.stream(threadId: ThreadId, query: String, config: RunConfig = RunConfig()): Stream[F, AgentStreamItem]`, `streamResume(threadId, answers, config = RunConfig())`, `streamRecover(threadId, config = RunConfig())`

- [ ] **Step 1: Write the failing tests**

`AgentEventBufferSpec.scala`:

```scala
  "AgentEventBuffer" should "hand over events in order and end after the terminal event" in {
    val buffer = AgentEventBuffer(8)
    val started  = durable(1, RunEvent.RunStarted(None, None))
    val finished = durable(2, RunEvent.RunCompleted)
    buffer.listener(started)
    buffer.listener(finished)
    buffer.take() shouldBe Right(Some(started))
    buffer.take() shouldBe Right(Some(finished))
    buffer.take() shouldBe Right(None)
  }

  it should "fail on a Lagging disconnect" in {
    val buffer = AgentEventBuffer(8)
    buffer.listener(StreamEvent.Disconnected(3L, DisconnectReason.Lagging))
    buffer.take().isLeft shouldBe true
  }

  it should "block the listener while full, until taken" in {
    val buffer = AgentEventBuffer(1)
    buffer.listener(durable(1, RunEvent.RunStarted(None, None)))
    val second = new Thread(() => buffer.listener(durable(2, RunEvent.RunCompleted)))
    second.start()
    Thread.sleep(100)
    second.isAlive shouldBe true
    buffer.take()
    second.join(1000)
    second.isAlive shouldBe false
  }

  it should "wake a blocked take when closed" in {
    val buffer = AgentEventBuffer(4)
    val result = new java.util.concurrent.atomic.AtomicReference[Any](null)
    val taker  = new Thread(() => result.set(buffer.take()))
    taker.start()
    Thread.sleep(50)
    buffer.close()
    taker.join(1000)
    result.get shouldBe Right(None)
  }
```
(`durable(seq, event)` builds a `StreamEvent.Durable(EventRecord("t", seq, "r", None, None, None, Instant.EPOCH, event))`.)

`AgentIOStreamSpec.scala` (follow `AgentIOSpec.scala`'s style and its `Fixtures.scala` clients; `cats.effect.unsafe.implicits.global`):

```scala
  "AgentIO.stream" should "emit the run's events, then Done with its result" in {
    val agentIO = AgentIO[IO](agentAnswering("hello"))
    val items   = agentIO.stream(ThreadId("f1"), "hi").compile.toVector.unsafeRunSync()
    items.last match
      case AgentStreamItem.Done(r) => r.answer shouldBe Some("hello")
      case other                   => fail(s"last item was $other")
    items.init.forall { case AgentStreamItem.Event(_) => true; case _ => false } shouldBe true
    items.collect { case AgentStreamItem.Event(StreamEvent.Durable(r)) => r.event }.last shouldBe RunEvent.RunCompleted
  }

  it should "fail with the run's error" in {
    // a client returning Left(NetworkError): the stream raises LLMException
  }

  it should "fail at once for a refused start" in {
    // a blank query: the stream raises LLMException wrapping the ValidationError
  }

  it should "cancel the run when the stream is interrupted" in {
    // a client that blocks until interrupted; take the first item then interrupt the stream
    // (stream.take(1).compile.drain then check); afterwards agent.recover(threadId) succeeds
    // (the thread is free and Running), proving the run was cancelled, not left executing
  }
```

- [ ] **Step 2: Run to verify failure**

Run: `sbt "agent/testOnly org.llm4s.agent.AgentEventBufferSpec" "llm4sEffect/testOnly org.llm4s.effect.cats.AgentIOStreamSpec"`
Expected: compile failure.

- [ ] **Step 3: Implement `AgentEventBuffer`**

```scala
package org.llm4s.agent

import org.llm4s.agent.graph.*
import org.llm4s.error.{ LLMError, ValidationError }

import java.util.concurrent.locks.ReentrantLock
import scala.annotation.tailrec

/**
 * A bounded hand-over from a run-scoped listener, called on a subscription's dispatcher thread, to
 * one consumer - the bridge under the fs2 and ZIO streams. The listener blocks while the buffer is
 * full, so a slow consumer backs up only its own subscription, which the runtime then disconnects
 * as lagging. `take` returns the next event, `Right(None)` after the run's terminal event or
 * [[close]], or `Left` when the subscription was disconnected (fell behind, or failed).
 */
final private[llm4s] class AgentEventBuffer(capacity: Int):
  private val lock     = new ReentrantLock()
  private val notEmpty = lock.newCondition()
  private val notFull  = lock.newCondition()
  private val queue    = new java.util.ArrayDeque[StreamEvent]()
  private var finished = false
  private var failure: Option[LLMError] = None

  val listener: StreamEvent => Unit = event =>
    lock.lockInterruptibly()
    try
      while queue.size >= capacity && !finished do notFull.await()
      if !finished then
        event match
          case StreamEvent.Disconnected(lastSeq, reason) =>
            failure = Some(ValidationError("events", s"the event subscription ended after seq $lastSeq: $reason"))
            finished = true
          case _ => queue.add(event)
        notEmpty.signalAll()
    finally lock.unlock()

  def take(): Either[LLMError, Option[StreamEvent]] = ...
  def close(): Unit = ...
```

`try/finally` is banned outside safety packages; use the repo's lock helper instead. Find it: `grep -rn "def withLock" modules/agent/src/main/scala` and use `withLock(lock) { ... }`; for an interruptible wait use `Condition.await()` inside it (it throws `InterruptedException`, which propagates out of the listener and ends the subscription as `ListenerFailed` - correct when the subscription is being cancelled). Write `take`:

```scala
  def take(): Either[LLMError, Option[StreamEvent]] = withLock(lock) {
    @tailrec def next(): Either[LLMError, Option[StreamEvent]] =
      Option(queue.poll()) match
        case Some(event) =>
          notFull.signalAll()
          event match
            case StreamEvent.Durable(r) if RunScope.terminal(r.event) => finished = true
            case _                                                    => ()
          Right(Some(event))
        case None if finished => failure.toLeft(None)
        case None =>
          notEmpty.await()
          next()
    next()
  }

  def close(): Unit = withLock(lock) {
    finished = true
    queue.clear()
    notEmpty.signalAll()
    notFull.signalAll()
  }
```
A terminal event marks the buffer finished when it is taken, so the following `take` returns `Right(None)`. The listener uses the same `withLock` form.

- [ ] **Step 4: Implement `AgentIO.stream`**

`AgentStreamItem.scala` (cats):

```scala
package org.llm4s.effect.cats

import org.llm4s.agent.AgentResult
import org.llm4s.agent.graph.StreamEvent

/** An item of an agent's event stream: each of the run's events, then its result. */
enum AgentStreamItem:
  case Event(event: StreamEvent)
  case Done(result: AgentResult)
```

In `AgentIO` (trait), add:

```scala
  /**
   * One turn on `threadId`, as a stream: every event of the turn ([[Agent.stream]]), then
   * `Done(result)`. A refused start or a failed turn raises [[LLMException]]; so does a consumer that
   * falls so far behind that the subscription disconnects. Interrupting the stream cancels the turn
   * and returns once it has ended, leaving the thread for `recover`.
   */
  def stream(threadId: ThreadId, query: String, config: RunConfig = RunConfig()): Stream[F, AgentStreamItem]
  def streamResume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig = RunConfig()): Stream[F, AgentStreamItem]
  def streamRecover(threadId: ThreadId, config: RunConfig = RunConfig()): Stream[F, AgentStreamItem]
```

In `Impl`:

```scala
    def stream(threadId: ThreadId, query: String, config: RunConfig): Stream[F, AgentStreamItem] =
      streaming(listener => agent.stream(threadId, query, config)(listener))

    def streamResume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig): Stream[F, AgentStreamItem] =
      streaming(listener => agent.streamResume(threadId, answers, config)(listener))

    def streamRecover(threadId: ThreadId, config: RunConfig): Stream[F, AgentStreamItem] =
      streaming(listener => agent.streamRecover(threadId, config)(listener))

    /**
     * Starts the turn with a buffer's listener (uncancelable, so a turn is never started and forgotten),
     * releases it by cancelling the turn and awaiting its end, and emits the buffer's events then `Done`.
     */
    private def streaming(start: (StreamEvent => Unit) => Result[AgentRun]): Stream[F, AgentStreamItem] =
      val acquire = F.blocking {
        val buffer = AgentEventBuffer(StreamBufferSize)
        start(buffer.listener).map(run => (buffer, run))
      }.flatMap(raised)
      Stream
        .bracketCase(acquire) { case ((buffer, run), exit) =>
          exit match
            case Resource.ExitCase.Succeeded => F.delay(buffer.close())
            case _ =>
              F.blocking {
                run.cancel()
                run.await()
                buffer.close()
              }.void
        }
        .flatMap { (buffer, run) =>
          Stream
            .repeatEval(F.interruptible(buffer.take()).flatMap(raised))
            .unNoneTerminate
            .map(AgentStreamItem.Event(_))
            ++ Stream.eval(F.interruptible(run.await()).flatMap(raised)).map(AgentStreamItem.Done(_))
        }
```

With `private val StreamBufferSize = 256` in `object AgentIO`. Imports: `fs2.Stream`, `cats.effect.kernel.Resource`, `org.llm4s.agent.{ AgentEventBuffer, ... }`, `org.llm4s.agent.graph.StreamEvent`. Update the trait's Scaladoc to mention `stream*`.

- [ ] **Step 5: Run the tests**

Run: `sbt "agent/testOnly org.llm4s.agent.AgentEventBufferSpec" "llm4sEffect/test"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
sbt scalafmtAll
git add modules/agent modules/llm4s-effect
git commit -s -m "feat(effect): AgentIO.stream, streamResume, streamRecover as fs2 streams of run events (#1329)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: ZIO `AgentZ.stream`

**Files:**
- Create: `modules/llm4s-zio/src/main/scala/org/llm4s/zio/AgentStreamItem.scala`
- Modify: `modules/llm4s-zio/src/main/scala/org/llm4s/zio/AgentZ.scala`
- Test: `modules/llm4s-zio/src/test/scala/org/llm4s/zio/AgentZStreamSpec.scala`

**Interfaces:**
- Consumes: `AgentEventBuffer` (Task 8), `Agent.stream*` (Task 5).
- Produces:
  - `enum AgentStreamItem { case Event(event: StreamEvent); case Done(result: AgentResult) }` (package `org.llm4s.zio`)
  - `AgentZ.stream(threadId: ThreadId, query: String, config: RunConfig = RunConfig()): ZStream[Any, LLMError, AgentStreamItem]`, `streamResume(...)`, `streamRecover(...)` - same parameters as `AgentIO`'s

- [ ] **Step 1: Write the failing test**

`AgentZStreamSpec.scala`, the same four cases as `AgentIOStreamSpec` (events then `Done`; run failure fails the stream with the `LLMError`; refused start fails at once; interrupting the stream cancels the run - check with `recover`). Follow `AgentZSpec.scala`'s test framework and runtime setup exactly (it may be ScalaTest with `Unsafe.unsafe` or ZIO Test).

- [ ] **Step 2: Run to verify failure**

Run: `sbt "llm4sZio/testOnly org.llm4s.zio.AgentZStreamSpec"`
Expected: compile failure.

- [ ] **Step 3: Implement**

`AgentStreamItem.scala` (zio): as Task 8's, in package `org.llm4s.zio`.

In `AgentZ` (trait): the three `stream*` signatures returning `ZStream[Any, LLMError, AgentStreamItem]`, with the Scaladoc of Task 8 adapted ("fails the stream with the `LLMError`"; "interrupting the stream cancels the turn").

In the implementation:

```scala
    private def streaming(start: (StreamEvent => Unit) => Result[AgentRun]): ZStream[Any, LLMError, AgentStreamItem] =
      val acquire =
        ZIO.attemptBlocking {
          val buffer = AgentEventBuffer(StreamBufferSize)
          start(buffer.listener).map(run => (buffer, run))
        }.orDie.flatMap(ZIO.fromEither(_))
      ZStream
        .acquireReleaseExitWith(acquire) { case ((buffer, run), exit) =>
          if exit.isSuccess then ZIO.succeed(buffer.close())
          else
            ZIO.attemptBlocking {
              run.cancel()
              run.await()
              buffer.close()
            }.ignore
        }
        .flatMap { (buffer, run) =>
          ZStream
            .repeatZIOOption(
              ZIO.attemptBlockingInterrupt(buffer.take()).orDie.flatMap {
                case Right(Some(event)) => ZIO.succeed(event)
                case Right(None)        => ZIO.fail(None)
                case Left(error)        => ZIO.fail(Some(error))
              }
            )
            .map(AgentStreamItem.Event(_))
            ++ ZStream.fromZIO(ZIO.attemptBlockingInterrupt(run.await()).orDie.flatMap(ZIO.fromEither(_)))
              .map(AgentStreamItem.Done(_))
        }
```

with `private val StreamBufferSize = 256`. Check `acquireReleaseExitWith`'s exact name and the `Exit` predicate in the ZIO version on the classpath (`grep -n "zio" project/Deps.scala`); `ZStream.acquireReleaseExitWith` exists in ZIO 2.

- [ ] **Step 4: Run the tests**

Run: `sbt "llm4sZio/test"`
Expected: PASS (including `StreamFiberLeakSpec`).

- [ ] **Step 5: Commit**

```bash
sbt scalafmtAll
git add modules/llm4s-zio
git commit -s -m "feat(zio): AgentZ.stream, streamResume, streamRecover as ZStreams of run events (#1329)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Streaming samples

**Files:**
- Create: `modules/samples/src/main/scala/org/llm4s/samples/streaming/StreamingAgentExample.scala`
- Create: `modules/samples/src/main/scala/org/llm4s/samples/streaming/StreamingWithToolsExample.scala`
- Create: `modules/samples/src/main/scala/org/llm4s/samples/streaming/EventCollectionExample.scala`
- Create: `modules/samples/src/main/scala/org/llm4s/samples/catseffect/AgentStreamIOExample.scala`
- Create: `modules/samples/src/main/scala/org/llm4s/samples/zio/AgentStreamZIOExample.scala`

**Interfaces:**
- Consumes: everything above. Each sample loads its client the way existing samples do (`Llm4sConfig.defaultProvider()`, `Llm4sConfig.modelRegistryService()`, `given ModelRegistryService`, `LLMConnect.getClient`) - copy that block from `modules/samples/src/main/scala/org/llm4s/samples/streaming/StreamingWithProgressExample.scala`.

- [ ] **Step 1: `StreamingAgentExample`** - `withStreaming()`; print `TextDelta` text inline; on a `ModelCallStarted` with `attempt > 1` print "\n[retrying]\n"; after `await`, print the usage from `ModelCallCompleted`. Header Scaladoc: what it shows and `sbt "samples/runMain org.llm4s.samples.streaming.StreamingAgentExample"`.

```scala
package org.llm4s.samples.streaming

import org.llm4s.agent.Agent
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.slf4j.LoggerFactory

/**
 * An agent whose answer streams as it is generated: `withStreaming()` makes each model call stream,
 * and `agent.stream` hands every event of the turn to a listener - text deltas live, model and tool
 * events as they commit.
 *
 * To run: sbt "samples/runMain org.llm4s.samples.streaming.StreamingAgentExample"
 */
object StreamingAgentExample:
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val result = for
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)
      agent  <- Agent.builder("assistant", client).withSystemPrompt("You are concise.").withStreaming().build()
      run <- agent.stream(ThreadId("streaming-sample"), "Explain monads in three sentences.") {
        case AgentEvents.ModelCallStarted(s) if s.attempt > 1 => print("\n[retrying]\n")
        case AgentEvents.TextDelta(d)                         => print(d.text)
        case AgentEvents.ModelCallCompleted(m) =>
          println(s"\n\n[${m.model}: ${m.usage.map(u => s"${u.totalTokens} tokens").getOrElse("no usage")}]")
        case _ => ()
      }
      done <- run.await()
    yield done
    result.fold(e => logger.error("Failed: {}", e.formatted), r => logger.info("Status: {}", r.status))
```

- [ ] **Step 2: `StreamingWithToolsExample`** - an agent with `BuiltinTools.core` (from `llm4s-agent-tools`; check `samples` depends on it: `grep -n "agentTools" build.sbt`) asked "What is 17 * 23, and what time is it in UTC?"; print `ToolCallStarted` (tool and arguments), `ToolResult` (content, truncated to 200 chars), and `ToolExecuted` (outcome, `duration.toMillis` ms).

- [ ] **Step 3: `EventCollectionExample`** - build the agent with `.withRuntime(runtime)` on a `GraphRuntime.inMemory()` you hold; `agent.stream` into a `Vector` builder; after `await`, print the count of live vs durable events; then replay with `runtime.subscribe(threadId, afterSeq = 0)` into a second collector until the terminal event (use a `CountDownLatch`), cancel it, and print each replayed durable event's `seq` and name (`RunEvent.Custom(name, ...)` or the kernel event's `productPrefix`) - showing the replay holds structure and no content.

- [ ] **Step 4: `AgentStreamIOExample`** - `IOApp.Simple`: `LLMClientIO`'s agent constructor (see `modules/samples/src/main/scala/org/llm4s/samples/catseffect/` for the existing pattern), `.stream(ThreadId(...), "...")`, `collect { case AgentStreamItem.Event(AgentEvents.TextDelta(d)) => d.text }` printed with `evalMap(IO.print)`, and the `Done` result printed at the end.

- [ ] **Step 5: `AgentStreamZIOExample`** - the ZIO equivalent, following the existing samples in `modules/samples/src/main/scala/org/llm4s/samples/zio/`.

- [ ] **Step 6: Compile**

Run: `sbt "samples/compile" "samples/test"`
Expected: success.

- [ ] **Step 7: Commit**

```bash
sbt scalafmtAll
git add modules/samples
git commit -s -m "docs(samples): agent streaming, tool events, event replay, fs2 and ZIO stream samples (#1329)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Docs, design record, CHANGELOG, follow-up issue

**Files:**
- Modify: `docs/guide/agents/streaming.md` (rewrite)
- Modify: `docs/guide/observability/index.md`
- Modify: `docs/guide/agents/index.md`, `README.md`, `docs/migrations/0x-to-1x.md`, `docs/reference/migration.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/design/typed-agent-runtime-design.md` (new §4.14 after §4.13; §4.9 table row; §4.13 "Limits")
- Modify: `CLAUDE.md` ("Streaming Events" section)

- [ ] **Step 1: `docs/guide/agents/streaming.md`** - replace the "being rebuilt" page. Keep the front matter (`title: Streaming Events`, `nav_order: 5`, parents). Sections:
  1. *Streaming a turn* - `withStreaming()` and `agent.stream(threadId, query)(listener)`, the `StreamingAgentExample` snippet.
  2. *The events* - two tables (durable, live) with each `AgentEvents` member, its fields, and when it is sent, copied from the spec's decision 3.
  3. *Durable vs live, and the content rule* - durable events are stored, replayed, and carry no content; live events carry content and are never stored; why (guardrail Blocks remove a blocked turn).
  4. *Attempts* - a higher `attempt` in the same task discards the earlier text.
  5. *`stream*` vs `subscribe`* - `stream`/`streamResume`/`streamRecover` see everything; `AgentRun.subscribe` replays durable events but misses earlier live ones; both are run-scoped.
  6. *Falling behind* - `LiveGap(n)` for dropped live events; `Disconnected(lastSeq, Lagging)` and resubscribing with `GraphRuntime.subscribe(threadId, afterSeq = lastSeq)`.
  7. *Replay* - `GraphRuntime.subscribe(threadId, afterSeq = 0)` and `EventCollectionExample`.
  8. *fs2 and ZIO* - `AgentIO.stream`, `AgentZ.stream`, `Event | Done`, cancellation.
  9. *Your own graphs* - `EventType[A]`, `emit`, `progress`.
  10. *Limits* - Java and Kotlin streams are not yet available (link the follow-up issue from Step 6); the kernel's `TaskFailed`/`RunFailed` events carry error messages, which may quote content if a guardrail's or tool's error does.

- [ ] **Step 2: `docs/guide/observability/index.md`** - replace `AgentStateUpdated` with `AgentRunEnded`: what an agent run sends (`graph.*`/`agent.*` custom events, `TokenUsageRecorded`, one `AgentRunEnded`), and a table of what Langfuse (trace per run, session per thread, span per message), OpenTelemetry ("Agent Run" span) and `TraceCollector` show.

- [ ] **Step 3: Migration** - in `docs/migrations/0x-to-1x.md` and `docs/reference/migration.md`, inside Stage 1's existing agent migration note, add a table:

| Before | After |
|---|---|
| `agent.runWithEvents(query)(onEvent)` | `agent.stream(threadId, query)(listener).flatMap(_.await())` |
| `runCollectingEvents` | collect in the listener, or replay with `GraphRuntime.subscribe(threadId, afterSeq = 0)` |
| `AgentEvent.TextDelta(delta)` | `AgentEvents.TextDelta(d)` (`d.text`, `d.attempt`) with `withStreaming()` |
| `ToolCallStarted`/`ToolCallCompleted`/`ToolCallFailed` | `AgentEvents.ToolCallStarted`, `ToolResult` (live), `ToolExecuted` (durable, with outcome) |
| `HandoffStarted`/`HandoffCompleted` | `AgentEvents.HandedOff` |
| `InputGuardrail*`/`OutputGuardrail*` | `AgentEvents.GuardrailBlocked` (on a block); the outcome on `AgentStatus.Blocked` |
| `AgentStarted`/`AgentCompleted`/`AgentFailed`, `StepStarted`/`StepCompleted` | kernel events: `RunStarted`, `RunCompleted`, `RunFailed`; `ModelCallStarted`/`ModelCallCompleted` |
| `TraceEvent.AgentStateUpdated` | `TraceEvent.AgentRunEnded` |
| `context.progress(payload)` | `context.progress(name, version, payload)`, or an `EventType` |
| `ModelStep.next(messages, tools)` | `next(messages, tools, call)` |

  Update `README.md` and `docs/guide/agents/index.md` wherever they mention `runWithEvents`, `AgentEvent` or `AgentStateUpdated` (`grep -n "runWithEvents\|AgentEvent\|AgentStateUpdated" README.md docs/guide/agents/index.md`).

- [ ] **Step 4: `CHANGELOG.md`** - under the unreleased section, an entry for #1329 with the "Source breaks" list from the spec, the new API (`Agent.stream*`, `AgentRun.subscribe`, `AgentBuilder.withStreaming`, `AgentEvents`, `EventType`, `Observer`, `AgentIO.stream*`, `AgentZ.stream*`, `TraceEvent.AgentRunEnded`), and the Langfuse change (trace id = run id, session = thread id). Follow the format of the #1328 entry.

- [ ] **Step 5: Design doc and `CLAUDE.md`**
  - `docs/design/typed-agent-runtime-design.md`: add "### 4.14 Stage 1 slice 3: events and tracing ([#1329](https://github.com/llm4s/llm4s/issues/1329))" after §4.13, in the implemented-record style of §4.13: the decisions (with the four refinements from this plan's Global Constraints), the specs added, source breaks, and limits (Java/Kotlin streams; kernel failure messages; live events are not replayed). In §4.9's table, mark "`ModelStep` streaming through live progress, `AgentEvent` replaced (#1329)" as **closed by #1329**, and add "Closed by #1329 (§4.14): ..." after the "Closed by #1328" paragraph. In §4.13 "Limits", replace the #1329 line with a pointer to §4.14. In §4.13's tracing bullet, note that `AgentStateUpdated` was removed by #1329.
  - `CLAUDE.md`: replace the "Streaming Events" paragraph with:

```markdown
### Streaming Events

`AgentBuilder.withStreaming()` streams the model's answer; `agent.stream(threadId, query)(listener)`
delivers every event of the turn as a `StreamEvent`, matched with `AgentEvents` (`TextDelta`,
`ToolCallStarted`, `ToolExecuted`, `ModelCallCompleted`, ...). Durable events carry no message
content; content is live-only. `AgentRun.subscribe` is run-scoped; `AgentIO.stream`/`AgentZ.stream`
wrap it as fs2/ZIO streams. Tracing ends each run with `TraceEvent.AgentRunEnded` (#1329).
```
  and update the "Slice 7" paragraph's sentence about `TraceEvent.AgentStateUpdated` ("which the agent runtime no longer emits (#1328; its consumers move in #1329)") to say it was replaced by `AgentRunEnded` in #1329.

- [ ] **Step 6: Follow-up issue** - ask the user before creating it (it is outward-facing). Proposed: title "[PHASE 1] Agent event streams for Java (`JAgent`) and Kotlin (`AgentKt`)", body: "Follow-up to #1329. #1329 added `Agent.stream*` and fs2/ZIO streams. Add a listener-interface stream to `JAgent` and a `Flow` to `AgentKt` over `Agent.stream`, with `AgentEventBuffer` as the bridge." Create with `gh issue create` once approved, and put its number in Step 1's Limits.

- [ ] **Step 7: Full verification**

Run: `sbt buildAll stabilityTierCheck frozenDependencyCheck it/itTierCheck docs/doc`
Then: `grep -rn "AgentStateUpdated\|runWithEvents\|AgentEvent\b" --include=*.scala --include=*.md modules docs README.md CLAUDE.md | grep -v "docs/design/phase-2.1\|docs/superpowers\|CHANGELOG"` - expected: only historical mentions in the migration table and design log.
Expected: all green.

- [ ] **Step 8: Commit**

```bash
git add docs README.md CHANGELOG.md CLAUDE.md
git commit -s -m "docs: agent streaming and tracing guides, migration note, design record for #1329

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Self-review notes

- Spec coverage: decision 1 (content rule) - Tasks 3, 4 (`AgentEventsSpec` content test), 11; decision 2 (`EventType`, named `Live`) - Task 1; decision 3 (vocabulary) - Tasks 3, 4; decision 4 (opt-in streaming) - Task 3; decision 5 (`Observer`, run-scoped subscribe, `stream*`) - Tasks 2, 5; decision 6 (`AgentRunEnded`, `AgentTracing`, backends) - Tasks 6, 7; decision 7 (fs2/ZIO) - Tasks 8, 9; samples - Tasks 6, 10; docs - Task 11; tests table - each task's tests; rollout - task order.
- The spec's `finishReason` and `next(messages, tools, context)` are refined as recorded under Global Constraints; the design record (Task 11) states the final form.
