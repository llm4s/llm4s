package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.{ CancelledError, ProcessingError, ValidationError }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }

import java.time.Clock
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.{ ConcurrentLinkedQueue, CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Subscriptions see the durable events of commits made through another [[GraphRuntime]] sharing the
 * store (#1705): through the store's change notification ([[Checkpointer.awaitEventsAfter]]), or by
 * polling, configured by [[WatchPolicy]]; with the guarantees of the runtime's own commits - in
 * order, without gap or duplicate - and the same lag and failure behaviour. The cases that hold for
 * every store are in `CheckpointerContract`; these are the runtime's own.
 */
class CrossRuntimeSubscriptionSpec extends AnyFlatSpec with Matchers with EitherValues with Eventually:
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(10, Seconds))

  private val threadIds             = new AtomicInteger()
  private def newThread(): ThreadId = ThreadId(s"cross-${threadIds.incrementAndGet()}")

  /** Forwards everything to `underlying`, keeping [[Checkpointer.awaitEventsAfter]]'s polling default. */
  private class Polled(val underlying: Checkpointer) extends Checkpointer:
    val reads                                                               = new AtomicInteger()
    def claim(threadId: ThreadId, request: ClaimRequest)                    = underlying.claim(threadId, request)
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) = underlying.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: FencingToken)                    = underlying.release(threadId, token)
    def commit(threadId: ThreadId, commit: Commit)                          = underlying.commit(threadId, commit)
    def latest(threadId: ThreadId)                                          = underlying.latest(threadId)
    def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId)                   = underlying.deleteThread(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      reads.incrementAndGet()
      underlying.eventsAfter(threadId, afterSeq, limit)

  /** A one-node graph whose runs log their input, emitting a durable and a live event each. */
  private val logKey = StateKey.appending[String]("log")
  private val steps: CompiledGraph[String, Vector[String]] =
    val b = GraphBuilder("cross-steps", "v1")
    val step = b.node[String]("step", writes = Set(logKey)) { (input, _, context) =>
      context.emit("cross.step", 1, ujson.Str(input))
      context.progress("cross.progress", 1, ujson.Str(input))
      continue(Command.empty.update(logKey, input))
    }
    b.compile(step)(_.get(logKey)).value

  private def run(runtime: GraphRuntime, thread: ThreadId, input: String): Vector[String] =
    await(runtime.start(thread, steps, input, RunConfig().withRunId(RunId(input))).value) match
      case RunResult.Completed(_, output, _) => output
      case other                             => fail(s"the run did not complete: $other")

  /** What a subscription delivered, in order. */
  final private class Seen(pause: Option[CountDownLatch] = None, slow: FiniteDuration = Duration.Zero)
      extends (StreamEvent => Unit):
    private val events = new CopyOnWriteArrayList[StreamEvent]()
    def apply(event: StreamEvent): Unit =
      pause.foreach(_.await(10, TimeUnit.SECONDS))
      if slow > Duration.Zero then Thread.sleep(slow.toMillis)
      events.add(event): Unit
    def all: Vector[StreamEvent]                       = events.asScala.toVector
    def seqs: Vector[Long]                             = all.collect { case StreamEvent.Durable(r) => r.seq }
    def disconnected: Option[StreamEvent.Disconnected] = all.collectFirst { case d: StreamEvent.Disconnected => d }

  private def logOf(store: Checkpointer, thread: ThreadId): Vector[Long] =
    store.eventsAfter(thread, 0L, 10000).value.map(_.seq)

  private def contiguous(n: Int): Vector[Long] = (1L to n.toLong).toVector

  // ---- WatchPolicy ----

  "WatchPolicy" should "default to watching every 250 milliseconds, and validate its interval" in {
    WatchPolicy.default.enabled shouldBe true
    WatchPolicy.default.pollInterval shouldBe 250.millis
    WatchPolicy.disabled.enabled shouldBe false
    WatchPolicy().withPollInterval(1.second).pollInterval shouldBe 1.second
    WatchPolicy().withEnabled(false) shouldBe WatchPolicy.disabled
    WatchPolicy.of(pollInterval = 5.millis).value.pollInterval shouldBe 5.millis
    WatchPolicy.of(pollInterval = Duration.Zero).left.value shouldBe a[ValidationError]
    WatchPolicy.of(pollInterval = -1.second).left.value.message should include("pollInterval must be positive")
    an[IllegalArgumentException] should be thrownBy WatchPolicy(pollInterval = Duration.Zero)
    an[IllegalArgumentException] should be thrownBy WatchPolicy.default.withPollInterval(-1.millis)
  }

  // ---- the SPI hook ----

  "Checkpointer.awaitEventsAfter's default" should "poll: return what is there at once, else wait the timeout and return nothing" in {
    val store  = Polled(InMemoryCheckpointer())
    val thread = newThread()
    val token  = store.claim(thread, ClaimRequest(RunId("r"), 1.minute)).value.token
    store
      .commit(
        thread,
        Commit(token)
          .withEvents(Vector(EventDraft("r", None, None, None, java.time.Instant.EPOCH, RunEvent.RunCompleted)))
      )
      .value
    store.awaitEventsAfter(thread, 0L, 10, 1.hour).value.map(_.seq) shouldBe Vector(1L)
    val started = System.nanoTime()
    store.awaitEventsAfter(thread, 1L, 10, 120.millis).value shouldBe empty
    (System.nanoTime() - started).nanos should be >= 120.millis
  }

  it should "end its wait when interrupted, with CancelledError and the flag set" in {
    val store   = Polled(InMemoryCheckpointer())
    val outcome = new ConcurrentLinkedQueue[(Result[Vector[EventRecord]], Boolean)]()
    val waiting = Thread.ofVirtual().start { () =>
      outcome.add(store.awaitEventsAfter(newThread(), 0L, 10, 1.hour) -> Thread.currentThread().isInterrupted): Unit
    }
    Thread.sleep(50)
    waiting.interrupt()
    waiting.join(5000)
    val (result, flagged) = outcome.peek()
    result.left.value shouldBe a[CancelledError]
    flagged shouldBe true
  }

  "InMemoryCheckpointer.awaitEventsAfter" should "wake on a commit instead of waiting out its timeout" in {
    val store  = InMemoryCheckpointer()
    val thread = newThread()
    val token  = store.claim(thread, ClaimRequest(RunId("r"), 1.minute)).value.token
    val found  = new ConcurrentLinkedQueue[Result[Vector[EventRecord]]]()
    val waiter = Thread.ofVirtual().start(() => found.add(store.awaitEventsAfter(thread, 0L, 10, 1.hour)): Unit)
    Thread.sleep(50)
    store
      .commit(
        thread,
        Commit(token)
          .withEvents(Vector(EventDraft("r", None, None, None, java.time.Instant.EPOCH, RunEvent.RunCompleted)))
      )
      .value
    waiter.join(java.time.Duration.ofSeconds(5)) shouldBe true
    found.peek().value.map(_.seq) shouldBe Vector(1L)
  }

  // ---- runtimes ----

  "A subscription in one runtime" should "see another runtime's commits at once over a store that is told of them" in {
    val store  = InMemoryCheckpointer()
    val thread = newThread()
    val a      = GraphRuntime(store)
    // an hour between polls: only the store's own notification can deliver within the test
    val b    = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(pollInterval = 1.hour))
    val seen = Seen()
    val sub  = b.subscribe(thread)(seen).value
    eventually(b.storeWatches(thread) shouldBe 1)
    run(a, thread, "one")
    run(a, thread, "two")
    eventually(seen.seqs shouldBe logOf(store, thread))
    seen.seqs shouldBe contiguous(seen.seqs.size)
    // live progress is never stored, so another runtime's does not arrive
    seen.all.collect { case l: StreamEvent.Live => l } shouldBe empty
    sub.cancel()
  }

  it should "pass the configured poll interval to the store as its wait" in {
    val timeouts = new ConcurrentLinkedQueue[FiniteDuration]()
    val store = new Polled(InMemoryCheckpointer()):
      override def awaitEventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int, timeout: FiniteDuration) =
        timeouts.add(timeout)
        super.awaitEventsAfter(threadId, afterSeq, limit, timeout)
    val thread = newThread()
    val b      = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(pollInterval = 37.millis))
    val sub    = b.subscribe(thread)(Seen()).value
    eventually(timeouts.size should be >= 3)
    timeouts.asScala.toSet shouldBe Set(37.millis)
    sub.cancel()
  }

  it should "not watch the store when the runtime's WatchPolicy is disabled, seeing another runtime's commits on resubscribing" in {
    val store  = Polled(InMemoryCheckpointer())
    val thread = newThread()
    val a      = GraphRuntime(store.underlying)
    val b      = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy.disabled)
    val seen   = Seen()
    val sub    = b.subscribe(thread)(seen).value
    // replayed and live - the replay's read and the switch's catch-up read - so A's run reaches it only live
    eventually(store.reads.get should be >= 2)
    Thread.sleep(100) // the catch-up read itself returns after its count
    val readsAfterReplay = store.reads.get
    run(a, thread, "one")
    Thread.sleep(200)
    b.storeWatches(thread) shouldBe 0
    seen.seqs shouldBe empty
    store.reads.get shouldBe readsAfterReplay
    sub.cancel()
    val again = Seen()
    val resub = b.subscribe(thread)(again).value
    eventually(again.seqs shouldBe logOf(store, thread))
    resub.cancel()
  }

  it should "read the commits it has not seen before its own runtime's next one, so it never skips one" in {
    // a store that is polled, an hour apart: the watch reads once and then sleeps through the case
    val store  = Polled(InMemoryCheckpointer())
    val thread = newThread()
    val a      = GraphRuntime(store.underlying)
    val b      = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(pollInterval = 1.hour))
    val seen   = Seen()
    val sub    = b.subscribe(thread)(seen).value
    // replay, the switch to live and the watch's first read: the watch then sleeps through the case
    eventually(store.reads.get should be >= 3)
    run(a, thread, "one")
    Thread.sleep(100)
    seen.seqs shouldBe empty
    val fromA = logOf(store, thread).size
    // this runtime's claim follows A's events: the hand-over reads them first, in their place
    run(b, thread, "two")
    eventually(seen.seqs shouldBe logOf(store, thread))
    seen.seqs shouldBe contiguous(seen.seqs.size)
    val records = seen.all.collect { case StreamEvent.Durable(r) => r }
    records.take(fromA).map(_.runId).distinct shouldBe Vector("one")
    records.drop(fromA).map(_.runId).distinct shouldBe Vector("two")
    sub.cancel()
  }

  it should "lag, resumably, when the commits it has not seen do not fit before its own runtime's next one" in {
    val store  = Polled(InMemoryCheckpointer())
    val thread = newThread()
    val a      = GraphRuntime(store.underlying)
    val b      = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(pollInterval = 1.hour))
    val seen   = Seen()
    val sub    = b.subscribe(thread, capacity = 2)(seen).value
    eventually(store.reads.get should be >= 3)
    run(a, thread, "one") // more than two events
    logOf(store, thread).size should be > 2
    run(b, thread, "two")
    eventually(seen.disconnected shouldBe Some(StreamEvent.Disconnected(0L, DisconnectReason.Lagging)))
    seen.seqs shouldBe empty
    eventually(b.storeWatches(thread) shouldBe 0)
    sub.cancel()
    val again = Seen()
    val resub = b.subscribe(thread, afterSeq = 0L)(again).value
    eventually(again.seqs shouldBe logOf(store, thread))
    resub.cancel()
  }

  it should "never lag on another runtime's commits: they wait in the store until a slow listener has room" in {
    val store  = InMemoryCheckpointer()
    val thread = newThread()
    val a      = GraphRuntime(store)
    val b      = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(pollInterval = 10.millis))
    val held   = new CountDownLatch(1)
    val seen   = Seen(pause = Some(held), slow = 2.millis)
    val sub    = b.subscribe(thread, capacity = 2)(seen).value
    // live, so A's commits reach it through its watch rather than its replay
    eventually(b.storeWatches(thread) shouldBe 1)
    (1 to 5).foreach(i => run(a, thread, s"run-$i"))
    val committed = logOf(store, thread)
    committed.size should be > 10
    held.countDown()
    eventually(seen.seqs shouldBe committed)
    seen.disconnected shouldBe None
    sub.cancel()
  }

  it should "end with ReplayFailed, after what it has queued, when watching the store fails" in {
    val failing = new AtomicBoolean(false)
    val problem = ProcessingError("eventsAfter", "the store went away")
    val store = new Polled(InMemoryCheckpointer()):
      override def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
        if failing.get then Left(problem) else super.eventsAfter(threadId, afterSeq, limit)
    val thread = newThread()
    val a      = GraphRuntime(store.underlying)
    val b      = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(pollInterval = 10.millis))
    val seen   = Seen()
    b.subscribe(thread)(seen).value
    eventually(b.storeWatches(thread) shouldBe 1)
    run(a, thread, "one")
    eventually(seen.seqs shouldBe logOf(store.underlying, thread))
    failing.set(true)
    val delivered = seen.seqs.last
    eventually(
      seen.disconnected shouldBe Some(StreamEvent.Disconnected(delivered, DisconnectReason.ReplayFailed(problem)))
    )
    eventually(b.storeWatches(thread) shouldBe 0)
    b.liveSubscriptions(thread) shouldBe 0
  }

  it should "stop its watch when cancelled, reading the store no more" in {
    val store  = Polled(InMemoryCheckpointer())
    val thread = newThread()
    val b      = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(pollInterval = 5.millis))
    val sub    = b.subscribe(thread)(Seen()).value
    eventually(store.reads.get should be > 3)
    sub.cancel()
    eventually(b.storeWatches(thread) shouldBe 0)
    val reads = store.reads.get
    Thread.sleep(100)
    store.reads.get shouldBe reads
  }

  "A subscription in the committing runtime" should "receive a commit its store wrote but reported as failed (#1737)" in {
    val inMemory = InMemoryCheckpointer()
    val commits  = new AtomicInteger()
    // the run's second commit lands, but the store reports it failed, as one whose connection dropped after COMMIT may
    val store = new Polled(inMemory):
      override def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
        val written = super.commit(threadId, commit)
        if commits.incrementAndGet() == 2 then Left(ProcessingError("commit", "connection reset after COMMIT"))
        else written
    val thread  = newThread()
    val runtime = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(pollInterval = 10.millis))
    val seen    = Seen()
    val sub     = runtime.subscribe(thread)(seen).value
    await(runtime.start(thread, steps, "one").value) shouldBe a[RunResult.Failed]
    val committed = logOf(inMemory, thread)
    committed.size should be > 1
    eventually(seen.seqs shouldBe committed)
    sub.cancel()
  }

  "Subscriptions in several runtimes" should "each see every thread's whole log, in order, while the runtimes race for the threads" in {
    val store = InMemoryCheckpointer()
    val runtimes =
      Vector.fill(3)(GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(pollInterval = 10.millis)))
    val threads = Vector.fill(3)(newThread())
    val seen = for
      runtime <- runtimes
      thread  <- threads
    yield (thread, Seen(), runtime)
    val subs = seen.map((thread, listener, runtime) => runtime.subscribe(thread, capacity = 4096)(listener).value)
    seen.foreach((thread, _, runtime) => eventually(runtime.storeWatches(thread) shouldBe 1))
    val workers = for
      (runtime, r) <- runtimes.zipWithIndex
      thread       <- threads
    yield Thread.ofVirtual().start { () =>
      (1 to 8).foreach { i =>
        // retried until admitted: the runtimes contend for each thread
        Iterator
          .continually(runtime.start(thread, steps, s"r$r-$i", RunConfig().withRunId(RunId(s"${thread.value}-r$r-$i"))))
          .map(_.flatMap(_.await()))
          .find { outcome =>
            if outcome.isLeft then Thread.sleep(1)
            outcome.isRight
          }
          .getOrElse(fail("never admitted")): Unit
      }
    }
    workers.foreach(_.join(60000))
    seen.foreach { (thread, listener, _) =>
      val committed = logOf(store, thread)
      committed.size should be >= 3 * 8
      eventually(listener.seqs shouldBe committed)
    }
    subs.foreach(_.cancel())
  }
