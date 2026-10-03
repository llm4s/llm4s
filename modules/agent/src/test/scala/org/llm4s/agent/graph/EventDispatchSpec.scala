package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, LinkedBlockingQueue, TimeUnit }
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.jdk.CollectionConverters.*

class EventDispatchSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val thread = ThreadId("dispatch")

  given ExecutionContext = ExecutionContext.global

  /** What a subscriber received, polled with a timeout: listeners run on the dispatcher thread. */
  final private class Collector {
    val queue                         = new LinkedBlockingQueue[StreamEvent]()
    def listener: StreamEvent => Unit = queue.put(_)

    def next(): StreamEvent = Option(queue.poll(5, TimeUnit.SECONDS)).getOrElse(fail("no event within 5s"))

    /** Takes events until the `runs`-th run ends (or the subscription ends), and returns them. */
    def untilRunEnds(runs: Int = 1): Vector[StreamEvent] = {
      @scala.annotation.tailrec
      def loop(acc: Vector[StreamEvent], ended: Int): Vector[StreamEvent] =
        next() match {
          case d @ StreamEvent.Durable(r) if isEnd(r) =>
            if ended + 1 == runs then acc :+ d else loop(acc :+ d, ended + 1)
          case d: StreamEvent.Disconnected => acc :+ d
          case other                       => loop(acc :+ other, ended)
        }
      loop(Vector.empty, 0)
    }

    /** Nothing more arrives within a short wait. */
    def quiet(): Unit = Option(queue.poll(200, TimeUnit.MILLISECONDS)) shouldBe None
  }

  private def isEnd(r: EventRecord): Boolean = r.event match {
    case RunEvent.RunCompleted | RunEvent.RunFailed(_) => true
    case _                                             => false
  }

  private def durableSeqs(events: Vector[StreamEvent]): Vector[Long] =
    events.collect { case StreamEvent.Durable(r) => r.seq }

  /**
   * A store that reports when a subscription to an empty thread begins its switch to live: replay
   * reads one empty page, then the switch reads under the hub's lock. A commit made after that is
   * handed to the dispatcher's queue rather than replayed.
   */
  final private class Watched(val underlying: InMemoryCheckpointer = InMemoryCheckpointer()) extends Checkpointer {
    private val reads                              = new AtomicInteger()
    val switching                                  = new CountDownLatch(1)
    def commit(threadId: ThreadId, commit: Commit) = underlying.commit(threadId, commit)
    def latest(threadId: ThreadId)                 = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] = {
      if reads.incrementAndGet() == 2 then switching.countDown()
      underlying.eventsAfter(threadId, afterSeq, limit)
    }
    def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
    def awaitSwitch(): Unit                                = switching.await(5, TimeUnit.SECONDS) shouldBe true
  }

  /** A chain of `steps` nodes n0 -> n1 -> ..., each running `body(index, context)` once. */
  private def chain(steps: Int)(body: (Int, RunContext) => Unit): CompiledGraph[String, Unit] = {
    val b = GraphBuilder("chain", "v1")
    val first = b.node[String]("n0") { (_, _, context) =>
      body(0, context); continue(Command.empty)
    }
    val rest = (1 until steps).map { i =>
      b.node[Unit](s"n$i") { (_, _, context) =>
        body(i, context); continue(Command.empty)
      }
    }
    (first +: rest).zip(rest).foreach((from, to) => b.edge(from, to))
    b.compile(first)(_ => Right(())).value
  }

  private def emitting(count: Int): CompiledGraph[String, Unit] =
    chain(3)((i, context) => (1 to count).foreach(n => context.emit("e", 1, ujson.Obj("node" -> i, "n" -> n))))

  "Event dispatch" should "deliver durable events in order, on one dispatcher thread that is not a task's" in {
    val taskThreads     = ConcurrentHashMap.newKeySet[Thread]()
    val listenerThreads = ConcurrentHashMap.newKeySet[Thread]()
    val graph = chain(3) { (i, context) =>
      taskThreads.add(Thread.currentThread())
      context.emit("e", 1, ujson.Num(i))
    }
    val store     = InMemoryCheckpointer()
    val runtime   = GraphRuntime(store)
    val collector = Collector()
    runtime
      .subscribe(thread) { event =>
        listenerThreads.add(Thread.currentThread())
        collector.listener(event)
      }
      .value
    runtime.start(thread, graph, "go").awaited.value.completed

    val received = durableSeqs(collector.untilRunEnds())
    received shouldBe (1L to store.eventsAfter(thread, 0L, 1000).value.size.toLong).toVector
    listenerThreads.size shouldBe 1
    val dispatcher = listenerThreads.asScala.head
    dispatcher should not be Thread.currentThread()
    taskThreads.asScala should not contain dispatcher
    dispatcher.isVirtual shouldBe true
    dispatcher.getName shouldBe s"llm4s-subscriber-${thread.value}"
  }

  it should "not stall commits behind a blocked listener, and disconnect it as lagging" in {
    val store     = Watched()
    val runtime   = GraphRuntime(store)
    val collector = Collector()
    val release   = new CountDownLatch(1)
    val blocked   = new AtomicBoolean(false)
    runtime
      .subscribe(thread, capacity = 4) { event =>
        collector.listener(event)
        if blocked.compareAndSet(false, true) then release.await(10, TimeUnit.SECONDS): Unit
      }
      .value
    store.awaitSwitch()

    val run = Future(runtime.start(thread, emitting(2), "go").awaited)
    Await.result(run, 5.seconds).value.completed
    val total = store.underlying.eventsAfter(thread, 0L, 1000).value.size.toLong
    total should be > 6L
    release.countDown()

    val received = collector.untilRunEnds()
    val k        = durableSeqs(received).last
    durableSeqs(received) shouldBe (1L to k).toVector
    received.last shouldBe StreamEvent.Disconnected(k, DisconnectReason.Lagging)
    k should be < total
    collector.quiet()

    val again = Collector()
    runtime.subscribe(thread, afterSeq = k)(again.listener).value
    durableSeqs(again.untilRunEnds()) shouldBe ((k + 1) to total).toVector
  }

  it should "drop live events that do not fit, and report how many with a gap marker" in {
    val store      = Watched()
    val runtime    = GraphRuntime(store)
    val collector  = Collector()
    val burstDone  = new CountDownLatch(1)
    val blocked    = new AtomicBoolean(false)
    val sawGap     = new AtomicBoolean(false)
    val lastTick   = new AtomicInteger(-1)
    val tickSignal = new LinkedBlockingQueue[Int]()
    val ticksSent  = new AtomicInteger()
    runtime
      .subscribe(thread, capacity = 4) { event =>
        collector.listener(event)
        event match {
          case StreamEvent.Live(_, _, _, _, payload) if payload("kind").str == "burst" =>
            if blocked.compareAndSet(false, true) then burstDone.await(10, TimeUnit.SECONDS): Unit
          case StreamEvent.Live(_, _, _, _, payload) =>
            lastTick.set(payload("i").num.toInt)
            tickSignal.put(payload("i").num.toInt)
          case _: StreamEvent.LiveGap => sawGap.set(true)
          case _                      => ()
        }
      }
      .value
    store.awaitSwitch()

    // the burst overflows while the listener is blocked; ticks then flush the gap and drain the
    // queue, so the node's commit finds room and nothing is disconnected
    val graph = chain(1) { (_, context) =>
      (1 to 50).foreach(i => context.progress(ujson.Obj("kind" -> "burst", "i" -> i)))
      burstDone.countDown()
      val deadline = System.nanoTime() + 5_000_000_000L
      @scala.annotation.tailrec
      def tick(sent: Int): Int =
        if (sawGap.get && lastTick.get == sent - 1) || System.nanoTime() > deadline then sent
        else {
          context.progress(ujson.Obj("kind" -> "tick", "i" -> sent))
          tickSignal.poll(20, TimeUnit.MILLISECONDS): Unit
          tick(sent + 1)
        }
      ticksSent.set(tick(0))
    }
    runtime.start(thread, graph, "go").awaited.value.completed

    val received = collector.untilRunEnds()
    received.collect { case d: StreamEvent.Disconnected => d } shouldBe empty
    val lives = received.collect { case l: StreamEvent.Live => l.payload }
    val burst = lives.filter(_("kind").str == "burst").map(_("i").num.toInt)
    val ticks = lives.count(_("kind").str == "tick")
    val gaps  = received.collect { case StreamEvent.LiveGap(d) => d }
    gaps should not be empty
    gaps.forall(_ > 0) shouldBe true
    burst shouldBe burst.sorted
    burst.size should be < 50
    (burst.size + ticks + gaps.sum) shouldBe (50 + ticksSent.get)
    // the gap marker precedes the next accepted event
    received.indexWhere(_.isInstanceOf[StreamEvent.LiveGap]) should be > received.indexWhere {
      case StreamEvent.Live(_, _, _, _, p) => p("kind").str == "burst"; case _ => false
    }
  }

  it should "disconnect a listener that throws, without delivering anything after" in {
    val runtime   = GraphRuntime.inMemory()
    val collector = Collector()
    val boom      = new IllegalStateException("listener failed")
    val durables  = new AtomicInteger()
    runtime
      .subscribe(thread) {
        case StreamEvent.Durable(_) if durables.incrementAndGet() == 2 => throw boom
        case other                                                     => collector.listener(other)
      }
      .value
    runtime.start(thread, emitting(1), "go").awaited.value.completed

    collector.next() match {
      case StreamEvent.Durable(r) => r.seq shouldBe 1L
      case other                  => fail(s"expected seq 1, got $other")
    }
    collector.next() shouldBe StreamEvent.Disconnected(1L, DisconnectReason.ListenerFailed(boom))
    runtime.start(thread, emitting(1), "again").awaited.value.completed
    collector.quiet()
  }

  it should "switch from replay to live without losing or repeating a commit" in {
    val graph   = chain(2)((_, context) => context.emit("e", 1, ujson.Null))
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    (1 to 200).foreach { i =>
      val t         = ThreadId(s"race-$i")
      val collector = Collector()
      val sub       = runtime.subscribe(t)(collector.listener).value
      val run       = Future(runtime.start(t, graph, "go").awaited)
      val seqs      = durableSeqs(collector.untilRunEnds())
      Await.result(run, 5.seconds).value.completed
      withClue(s"thread $i: ")(seqs shouldBe (1L to store.eventsAfter(t, 0L, 1000).value.size.toLong).toVector)
      sub.cancel()
    }
  }

  it should "replay a thread's committed events and continue with a concurrent run's, each once" in {
    val graph   = chain(3)((_, context) => (1 to 3).foreach(_ => context.emit("e", 1, ujson.Null)))
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    (1 to 50).foreach { i =>
      val t = ThreadId(s"replay-$i")
      runtime.start(t, graph, "first").awaited.value.completed
      val collector = Collector()
      val run       = Future(runtime.start(t, graph, "second").awaited)
      runtime.subscribe(t, afterSeq = 0L)(collector.listener).value
      val seqs = durableSeqs(collector.untilRunEnds(runs = 2))
      Await.result(run, 5.seconds).value.completed
      withClue(s"thread $i: ")(seqs shouldBe (1L to store.eventsAfter(t, 0L, 1000).value.size.toLong).toVector)
    }
  }

  it should "end with ReplayFailed when the store cannot replay" in {
    val error = ValidationError("store", "unreadable")
    val failing = new Checkpointer {
      private val store                              = InMemoryCheckpointer()
      def commit(threadId: ThreadId, commit: Commit) = store.commit(threadId, commit)
      def latest(threadId: ThreadId)                 = store.latest(threadId)
      def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] = Left(error)
      def compactEvents(threadId: ThreadId, beforeSeq: Long) = store.compactEvents(threadId, beforeSeq)
    }
    val runtime   = GraphRuntime(failing)
    val collector = Collector()
    runtime.subscribe(thread)(collector.listener).value
    collector.next() shouldBe StreamEvent.Disconnected(0L, DisconnectReason.ReplayFailed(error))
    collector.quiet()

    val later = Collector()
    runtime.subscribe(thread, afterSeq = 7L)(later.listener).value
    later.next() shouldBe StreamEvent.Disconnected(7L, DisconnectReason.ReplayFailed(error))
  }

  it should "deliver nothing after cancel, not even Disconnected" in {
    val runtime   = GraphRuntime.inMemory()
    val cancelled = Collector()
    runtime.subscribe(thread)(cancelled.listener).value.cancel()
    runtime.start(thread, emitting(1), "go").awaited.value.completed
    val witness = Collector()
    runtime.subscribe(thread)(witness.listener).value
    witness.untilRunEnds()
    cancelled.quiet()

    // cancelled while its listener is blocked
    val blocked = Collector()
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val sub = runtime
      .subscribe(thread) { event =>
        blocked.listener(event)
        if entered.getCount > 0 then {
          entered.countDown()
          release.await(10, TimeUnit.SECONDS): Unit
        }
      }
      .value
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    sub.cancel()
    release.countDown()
    runtime.start(thread, emitting(1), "again").awaited.value.completed
    blocked.next() shouldBe a[StreamEvent.Durable]
    blocked.quiet()
  }

  /**
   * Cancels while `listener` is inside a call that ignores interrupts until released; `cancel` must
   * not return before that call ends, and nothing is delivered after.
   */
  private def cancelDuringCall(blockOn: StreamEvent => Boolean, failOnFirstDurable: Boolean): Unit = {
    val t         = ThreadId(s"cancel-during-${blockOn.hashCode}")
    val runtime   = GraphRuntime.inMemory()
    val collector = Collector()
    val entered   = new CountDownLatch(1)
    val released  = new AtomicBoolean(false)
    val ended     = new AtomicBoolean(false)
    runtime.start(t, emitting(1), "go").awaited.value.completed
    val sub = runtime
      .subscribe(t) { event =>
        collector.listener(event)
        if blockOn(event) && entered.getCount > 0 then {
          entered.countDown()
          while !released.get do Thread.onSpinWait()
          ended.set(true)
        } else if failOnFirstDurable && event.isInstanceOf[StreamEvent.Durable] then
          throw new IllegalStateException("x")
      }
      .value
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    val cancelling = Future { sub.cancel(); ended.get }
    a[java.util.concurrent.TimeoutException] should be thrownBy Await.ready(cancelling, 200.millis)
    released.set(true)
    Await.result(cancelling, 5.seconds) shouldBe true
    val before = Iterator.continually(Option(collector.queue.poll())).takeWhile(_.isDefined).flatten.toVector
    before.lastOption.exists(blockOn) shouldBe true
    runtime.start(t, emitting(1), "again").awaited.value.completed
    collector.quiet()
  }

  it should "make cancel wait for a listener call in progress, then deliver nothing" in {
    cancelDuringCall(_.isInstanceOf[StreamEvent.Durable], failOnFirstDurable = false)
  }

  it should "make cancel wait for a Disconnected delivery in progress, then deliver nothing" in {
    cancelDuringCall(_.isInstanceOf[StreamEvent.Disconnected], failOnFirstDurable = true)
  }

  it should "refuse a capacity below two, which leaves no room for a gap marker" in {
    val runtime = GraphRuntime.inMemory()
    Seq(-1, 0, 1).foreach { capacity =>
      runtime.subscribe(thread, capacity = capacity)(_ => ()).left.value shouldBe a[ValidationError]
    }
    runtime.subscribe(thread, capacity = 2)(_ => ()).value.cancel()
  }
}
