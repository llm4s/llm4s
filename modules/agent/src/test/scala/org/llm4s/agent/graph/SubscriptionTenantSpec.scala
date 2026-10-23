package org.llm4s.agent.graph

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.{ CountDownLatch, LinkedBlockingQueue, TimeUnit }
import scala.concurrent.duration.*

/**
 * A subscription is a read of a thread, so it is tenant-checked as `history` is (#1700, audit C F2): a
 * caller of another tenant is refused before it joins, and a subscription made before the thread
 * exists never delivers a thread another tenant then creates.
 */
class SubscriptionTenantSpec extends AnyFlatSpec with TestTimeLimit with Matchers with EitherValues with OptionValues {

  private val threadIds             = new AtomicInteger()
  private def newThread(): ThreadId = ThreadId(s"sub-tenant-${threadIds.incrementAndGet()}")

  private val logKey = StateKey.appending[String]("log")

  /** One node that emits its input as a durable custom event, and as a live one. */
  private val emitter: CompiledGraph[String, Vector[String]] = {
    val b = GraphBuilder("emitter", "v1")
    val step = b.node[String]("step", writes = Set(logKey)) { (input, _, context) =>
      context.emit("secret", 1, ujson.Str(input))
      context.progress("secret.progress", 1, ujson.Str(input))
      continue(Command.empty.update(logKey, input))
    }
    b.compile(step)(_.get(logKey)).value
  }

  private def tenant(name: String, runId: String): RunConfig =
    RunConfig().withTenantId(TenantId(name)).withRunId(RunId(runId)).withPrincipal(Principal(s"$name-user"))

  /** What a subscription delivered, taken in order, each wait bounded. */
  final private class Seen extends (StreamEvent => Unit) {
    private val events              = new LinkedBlockingQueue[StreamEvent]()
    def apply(e: StreamEvent): Unit = events.put(e)
    def next(): StreamEvent         = Option(events.poll(10, TimeUnit.SECONDS)).getOrElse(fail("no event within 10s"))

    /** Events up to and including the first that `last` accepts. */
    def until(last: StreamEvent => Boolean): Vector[StreamEvent] = {
      val taken = Vector.newBuilder[StreamEvent]
      var e     = next()
      while !last(e) do {
        taken += e
        e = next()
      }
      (taken += e).result()
    }
  }

  private def completedRun(e: StreamEvent): Boolean = e match {
    case StreamEvent.Durable(r) => r.event == RunEvent.RunCompleted
    case _                      => false
  }

  private def disconnection(e: StreamEvent): Boolean = e.isInstanceOf[StreamEvent.Disconnected]

  private def payloads(events: Vector[StreamEvent]): Vector[String] = events
    .collect {
      case StreamEvent.Durable(r) =>
        r.event match {
          case RunEvent.Custom(_, _, payload) => payload.str
          case _                              => ""
        }
      case l: StreamEvent.Live => l.payload.str
    }
    .filter(_.nonEmpty)

  "GraphRuntime.subscribe" should "refuse another tenant's thread before joining, as history does" in {
    val runtime = GraphRuntime.inMemory()
    val thread  = newThread()
    await(runtime.start(thread, emitter, "tenant a's private input", tenant("a", "a1")).value).completed

    runtime.subscribe(thread, config = tenant("b", "b1"))(_ => ()).left.value shouldBe
      GraphError.TenantMismatch(thread.value, Some("b"))
    runtime.subscribe(thread)(_ => ()).left.value shouldBe GraphError.TenantMismatch(thread.value, None)
    runtime.liveSubscriptions(thread) shouldBe 0
    runtime.history(thread, config = tenant("b", "b1")).left.value shouldBe a[GraphError.TenantMismatch]

    // the thread's own tenant replays it as before
    val seen = Seen()
    val sub  = runtime.subscribe(thread, config = tenant("a", "a2"))(seen).value
    payloads(seen.until(completedRun)) should contain("tenant a's private input")
    sub.cancel()
  }

  it should "refuse another tenant while a first run on a new thread is still being admitted in this runtime" in {
    val underlying = InMemoryCheckpointer()
    val reading    = new CountDownLatch(1)
    val gate       = new CountDownLatch(1)
    val armed      = new AtomicBoolean(true)
    val slow = new ForwardingCheckpointer(underlying) {
      override def latest(threadId: ThreadId) = {
        if armed.compareAndSet(true, false) then {
          reading.countDown()
          gate.await(10, TimeUnit.SECONDS): Unit
        }
        underlying.latest(threadId)
      }
    }
    val runtime = GraphRuntime(slow)
    val thread  = newThread()
    val first   = new LinkedBlockingQueue[Result[RunResult[Vector[String]]]]()
    Thread.ofVirtual().start(() => first.put(runtime.start(thread, emitter, "x", tenant("a", "a1")).awaited))
    reading.await(10, TimeUnit.SECONDS) shouldBe true

    runtime.subscribe(thread, config = tenant("b", "b1"))(_ => ()).left.value shouldBe
      GraphError.TenantMismatch(thread.value, Some("b"))

    gate.countDown()
    Option(first.poll(10, TimeUnit.SECONDS)).value.value.completed._2 shouldBe Vector("x")
  }

  it should "return the store's error when the thread cannot be read" in {
    val failing = new ForwardingCheckpointer(InMemoryCheckpointer()) {
      override def latest(threadId: ThreadId) = Left(ProcessingError("db", "connection reset"))
    }
    GraphRuntime(failing).subscribe(newThread())(_ => ()).left.value shouldBe ProcessingError("db", "connection reset")
  }

  it should "end a subscription made before the thread existed when another tenant creates it, delivering nothing" in {
    val runtime = GraphRuntime.inMemory()
    val thread  = newThread()
    val seen    = Seen()
    val sub     = runtime.subscribe(thread, config = tenant("a", "a1"))(seen).value

    await(runtime.start(thread, emitter, "tenant b's private input", tenant("b", "b1")).value).completed

    seen.next() shouldBe StreamEvent.Disconnected(0L, DisconnectReason.TenantMismatch)
    sub.cancel()
  }

  it should "deliver a thread created after it subscribed by its own tenant" in {
    val runtime = GraphRuntime.inMemory()
    val thread  = newThread()
    val seen    = Seen()
    val sub     = runtime.subscribe(thread, config = tenant("a", "a1"))(seen).value

    await(runtime.start(thread, emitter, "mine", tenant("a", "a2")).value).completed

    val events = seen.until(completedRun)
    events.head should matchPattern { case StreamEvent.Durable(EventRecord(_, 1L, _, _, _, _, _, _, _)) => }
    payloads(events) should contain("mine")
    events.exists(disconnection) shouldBe false
    sub.cancel()
  }

  it should "check the store, not deliver, when the first event it would deliver names no tenant" in {
    val runtime = GraphRuntime.inMemory()
    val thread  = newThread()
    val seen    = Seen()
    // past the RunStarted that would name the tenant: the next event it sees is the node's Custom one
    val sub = runtime.subscribe(thread, afterSeq = 1L, config = tenant("a", "a1"))(seen).value

    await(runtime.start(thread, emitter, "tenant b's private input", tenant("b", "b1")).value).completed

    seen.next() shouldBe StreamEvent.Disconnected(1L, DisconnectReason.TenantMismatch)
    sub.cancel()
  }

  it should "end a subscription in another runtime when another tenant creates the thread there" in {
    val store = InMemoryCheckpointer()
    val mine =
      GraphRuntime(store, java.time.Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(pollInterval = 10.millis))
    val theirs = GraphRuntime(store)
    val thread = newThread()
    val seen   = Seen()
    val sub    = mine.subscribe(thread, config = tenant("a", "a1"))(seen).value

    await(theirs.start(thread, emitter, "tenant b's private input", tenant("b", "b1")).value).completed

    seen.next() shouldBe StreamEvent.Disconnected(0L, DisconnectReason.TenantMismatch)
    sub.cancel()
  }

  it should "not deliver a thread deleted and created again by another tenant through another runtime" in {
    val store = InMemoryCheckpointer()
    val mine =
      GraphRuntime(store, java.time.Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(pollInterval = 10.millis))
    val theirs = GraphRuntime(store)
    val thread = newThread()
    await(mine.start(thread, emitter, "a-input", tenant("a", "a1")).value).completed
    val seen = Seen()
    val sub  = mine.subscribe(thread, config = tenant("a", "a2"))(seen).value
    seen.until(completedRun)

    theirs.deleteThread(thread, tenant("a", "a-del")).value
    (1 to 3).foreach(i => await(theirs.start(thread, emitter, s"b-secret-$i", tenant("b", s"b$i")).value).completed)

    val rest = seen.until(disconnection)
    payloads(rest).filter(_.startsWith("b-secret")) shouldBe empty
    rest.last should matchPattern {
      case StreamEvent.Disconnected(_, DisconnectReason.ThreadDeleted | DisconnectReason.TenantMismatch) =>
    }
    sub.cancel()
  }

  it should "accept the owner while another tenant's doomed call is admitting on its thread" in {
    val underlying = InMemoryCheckpointer()
    val thread     = newThread()
    GraphRuntime(underlying).start(thread, emitter, "mine", tenant("a", "a1")).awaited.value.completed
    val reading = new CountDownLatch(1)
    val gate    = new CountDownLatch(1)
    val armed   = new AtomicBoolean(true)
    // tenant b's start holds the thread in this runtime while it reads the thread, before it is refused
    val slow = new ForwardingCheckpointer(underlying) {
      override def latest(threadId: ThreadId) = {
        if armed.compareAndSet(true, false) then {
          reading.countDown()
          gate.await(10, TimeUnit.SECONDS): Unit
        }
        underlying.latest(threadId)
      }
    }
    val runtime = GraphRuntime(slow)
    val probe   = new LinkedBlockingQueue[Result[RunHandle[Vector[String]]]]()
    Thread.ofVirtual().start(() => probe.put(runtime.start(thread, emitter, "x", tenant("b", "b1"))))
    reading.await(10, TimeUnit.SECONDS) shouldBe true

    // the checkpoint says the thread is tenant a's: b's reservation does not make it b's
    val seen = Seen()
    val sub  = runtime.subscribe(thread, config = tenant("a", "a2"))(seen).value
    payloads(seen.until(completedRun)) should contain("mine")
    sub.cancel()

    gate.countDown()
    Option(probe.poll(10, TimeUnit.SECONDS)).value.left.value shouldBe GraphError.TenantMismatch(
      thread.value,
      Some("b")
    )
  }

  it should "not deliver another tenant's thread recreated past a cursor ahead of the owner's log" in {
    val store = InMemoryCheckpointer()
    val mine =
      GraphRuntime(store, java.time.Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(pollInterval = 10.millis))
    val theirs = GraphRuntime(store)
    val thread = newThread()
    await(mine.start(thread, emitter, "a-input", tenant("a", "a1")).value).completed
    val head = store.eventsAfter(thread, 0L, 1000).value.last.seq
    // a cursor ahead of the log: past the recreated thread's RunStarted (numbered head + 2)
    val seen = Seen()
    val sub  = mine.subscribe(thread, afterSeq = head + 2, config = tenant("a", "a2"))(seen).value

    theirs.deleteThread(thread, tenant("a", "a-del")).value
    await(theirs.start(thread, emitter, "b-secret", tenant("b", "b1")).value).completed

    // up to the first of: the end, or anything of tenant b's thread's content
    val delivered = seen.until(e => disconnection(e) || payloads(Vector(e)).nonEmpty)
    payloads(delivered) shouldBe empty
    delivered.last shouldBe StreamEvent.Disconnected(head + 2, DisconnectReason.TenantMismatch)
    sub.cancel()
  }

  "TracingSubscriber.attach" should "refuse another tenant's thread" in {
    val runtime = GraphRuntime.inMemory()
    val thread  = newThread()
    await(runtime.start(thread, emitter, "x", tenant("a", "a1")).value).completed
    val tracing = new Tracing {
      def traceEvent(event: TraceEvent): Result[Unit]                                        = Right(())
      def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
      def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
      def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
      def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
    }
    TracingSubscriber.attach(runtime, thread, tracing, config = tenant("b", "b1")).left.value shouldBe
      GraphError.TenantMismatch(thread.value, Some("b"))
  }

  "RunHandle.subscribe" should "deliver its own run on a tenant's new thread" in {
    val runtime = GraphRuntime.inMemory()
    val thread  = newThread()
    val handle  = runtime.start(thread, emitter, "own", tenant("a", "a1")).value
    val seen    = Seen()
    val sub     = handle.subscribe()(seen).value
    payloads(seen.until(completedRun)) should contain("own")
    await(handle).completed
    sub.cancel()
  }
}
