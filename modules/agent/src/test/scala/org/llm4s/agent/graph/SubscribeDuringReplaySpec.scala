package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, LinkedBlockingQueue, TimeUnit }
import scala.concurrent.duration.*
import scala.concurrent.{ blocking, Await, ExecutionContext, Future }
import scala.jdk.CollectionConverters.*

/**
 * A subscription receives every live event sent after `subscribe` returns, also while its
 * dispatcher is still replaying the log (#1731), in its place among the durable events.
 */
class SubscribeDuringReplaySpec extends AnyFlatSpec with Matchers with EitherValues with Eventually {

  given ExecutionContext                               = ExecutionContext.global
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(10, Seconds))

  /**
   * A store whose first `eventsAfter` waits on `release` - a remote store's slow first round trip.
   * With `stale`, that read sees the log as it was when called, so what was committed meanwhile is
   * caught up by the switch to live; otherwise as it is once released, so the replay delivers it.
   */
  final private class SlowFirstRead(stale: Boolean) extends Checkpointer {
    val underlying                                                             = InMemoryCheckpointer()
    val release                                                                = new CountDownLatch(1)
    val reading                                                                = new CountDownLatch(1)
    private val reads                                                          = new AtomicInteger()
    def commit(threadId: ThreadId, commit: Commit)                             = underlying.commit(threadId, commit)
    def claim(threadId: ThreadId, request: org.llm4s.agent.graph.ClaimRequest) = underlying.claim(threadId, request)
    def renew(
      threadId: ThreadId,
      token: org.llm4s.agent.graph.FencingToken,
      ttl: scala.concurrent.duration.FiniteDuration
    ) =
      underlying.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: org.llm4s.agent.graph.FencingToken) = underlying.release(threadId, token)
    def latest(threadId: ThreadId)                                             = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      if reads.incrementAndGet() == 1 then
        val early = underlying.eventsAfter(threadId, afterSeq, limit)
        reading.countDown()
        release.await(10, TimeUnit.SECONDS): Unit
        if stale then early else underlying.eventsAfter(threadId, afterSeq, limit)
      else underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId)                   = underlying.deleteThread(threadId)
  }

  /** A chain of `steps` nodes, each sending `live` progress events and committing one custom event. */
  private def chain(steps: Int, live: Int): CompiledGraph[Unit, Unit] = {
    val b = GraphBuilder("p", "v1")
    val nodes = (0 until steps).map { i =>
      b.node[Unit](s"n$i") { (_, _, ctx) =>
        (1 to live).foreach(n => ctx.progress("p", 1, ujson.Obj("node" -> i, "n" -> n)))
        ctx.emit("e", 1, ujson.Num(i))
        continue(Command.empty)
      }
    }
    nodes.zip(nodes.drop(1)).foreach((from, to) => b.edge(from, to))
    b.compile(nodes.head)(_ => Right(())).value
  }

  private def isEnd(event: StreamEvent): Boolean = event match {
    case StreamEvent.Durable(r) => r.event == RunEvent.RunCompleted
    case _                      => false
  }

  /** Collects a listener's events; `ended` opens once a run's `RunCompleted` (or a disconnect) arrives. */
  final private class Recorder {
    val events = new CopyOnWriteArrayList[StreamEvent]()
    val ended  = new CountDownLatch(1)
    def listener: StreamEvent => Unit = { event =>
      events.add(event)
      if isEnd(event) || event.isInstanceOf[StreamEvent.Disconnected] then ended.countDown()
    }
    def awaitEnd(): Vector[StreamEvent] = {
      ended.await(10, TimeUnit.SECONDS) shouldBe true
      events.asScala.toVector
    }
  }

  private def durableSeqs(events: Vector[StreamEvent]): Vector[Long] =
    events.collect { case StreamEvent.Durable(r) => r.seq }

  "subscribe then start" should "deliver the run's live progress though the store's first read is slow" in {
    val store   = SlowFirstRead(stale = false)
    val runtime = GraphRuntime(store)
    val thread  = ThreadId("t")
    val b       = GraphBuilder("p", "v1")
    val n = b.node[Unit]("n") { (_, _, ctx) =>
      ctx.progress("p", 1, ujson.Str("hello"))
      continue(Command.empty)
    }
    val graph    = b.compile(n)(_ => Right(())).value
    val recorder = Recorder()
    runtime.subscribe(thread)(recorder.listener).value
    // the run completes while the subscription's first read is still in flight
    runtime.start(thread, graph, ()).awaited.value
    store.release.countDown()
    val events = recorder.awaitEnd()
    events.collect { case l: StreamEvent.Live => l.payload } shouldBe Vector(ujson.Str("hello"))
  }

  Seq(false -> "replayed", true -> "caught up at the switch to live").foreach { (stale, path) =>
    it should s"deliver live events sent during replay among the durable events $path, as an observer sees them" in {
      val store    = SlowFirstRead(stale)
      val runtime  = GraphRuntime(store)
      val thread   = ThreadId(s"order-$stale")
      val recorder = Recorder()
      val observer = Recorder()
      runtime.subscribe(thread)(recorder.listener).value
      store.reading.await(5, TimeUnit.SECONDS) shouldBe true
      // an observer joins at admission and is never held: its order is the hub's order
      runtime
        .start(thread, chain(steps = 4, live = 3), (), observer = Some(Observer(1024, observer.listener)))
        .awaited
        .value
      store.release.countDown()
      val expected = observer.awaitEnd()
      val received = recorder.awaitEnd()
      received shouldBe expected
      received.count(_.isInstanceOf[StreamEvent.Live]) shouldBe 12
      durableSeqs(received) shouldBe (1L to store.underlying.eventsAfter(thread, 0L, 1000).value.size.toLong).toVector
    }
  }

  it should "hold at most capacity live events during replay, and count the rest as a gap" in {
    val store    = SlowFirstRead(stale = false)
    val runtime  = GraphRuntime(store)
    val thread   = ThreadId("bounded")
    val recorder = Recorder()
    runtime.subscribe(thread, capacity = 4)(recorder.listener).value
    store.reading.await(5, TimeUnit.SECONDS) shouldBe true
    runtime.start(thread, chain(steps = 3, live = 10), ()).awaited.value
    store.release.countDown()
    val received = recorder.awaitEnd()
    val lives    = received.collect { case l: StreamEvent.Live => l.payload }
    val gaps     = received.collect { case StreamEvent.LiveGap(n) => n }
    lives.size should be <= 4
    lives.size + gaps.sum shouldBe 30
    lives shouldBe lives.sortBy(p => (p("node").num, p("n").num)) // in the order sent
    received.collect { case d: StreamEvent.Disconnected => d } shouldBe empty
    durableSeqs(received) shouldBe (1L to store.underlying.eventsAfter(thread, 0L, 1000).value.size.toLong).toVector
  }

  it should "deliver nothing, and leave the live set, when cancelled during replay" in {
    val store    = SlowFirstRead(stale = false)
    val runtime  = GraphRuntime(store)
    val thread   = ThreadId("cancelled")
    val recorder = Recorder()
    val sub      = runtime.subscribe(thread)(recorder.listener).value
    runtime.liveSubscriptions(thread) shouldBe 1 // joined before subscribe returned
    store.reading.await(5, TimeUnit.SECONDS) shouldBe true
    runtime.start(thread, chain(steps = 2, live = 5), ()).awaited.value
    sub.cancel()
    runtime.liveSubscriptions(thread) shouldBe 0
    store.release.countDown()
    Thread.sleep(200)
    recorder.events.asScala shouldBe empty
  }

  "Concurrent subscriptions" should "each receive every live event sent after subscribe returns, durable events once and in order" in {
    val runtime = GraphRuntime.inMemory()
    val graph   = chain(steps = 5, live = 20)
    (1 to 30).foreach { i =>
      val thread = ThreadId(s"stress-$i")
      // subscribing on other threads while the run sends live events, some before and some during it
      val queues  = Vector.fill(8)(new LinkedBlockingQueue[StreamEvent]())
      val started = new CountDownLatch(1)
      val subs = queues.zipWithIndex.map { (queue, k) =>
        Future {
          if k % 2 == 1 then blocking(started.await(5, TimeUnit.SECONDS)): Unit
          runtime.subscribe(thread)(queue.put).value
        }
      }
      val early = subs.zipWithIndex.collect { case (s, k) if k % 2 == 0 => s }
      early.foreach(Await.result(_, 5.seconds))
      val run = Future {
        started.countDown()
        runtime.start(thread, graph, ()).awaited.value
      }
      Await.result(run, 10.seconds)
      val subscriptions = subs.map(Await.result(_, 5.seconds))
      val stored        = runtime.liveSubscriptions(thread)
      stored shouldBe 8
      queues.zipWithIndex.foreach { (queue, k) =>
        @scala.annotation.tailrec
        def take(acc: Vector[StreamEvent]): Vector[StreamEvent] =
          Option(queue.poll(5, TimeUnit.SECONDS)) match {
            case Some(e) if isEnd(e) => acc :+ e
            case Some(e)             => take(acc :+ e)
            case None                => fail(s"thread $i, subscriber $k: no RunCompleted")
          }
        val received = take(Vector.empty)
        withClue(s"thread $i, subscriber $k: ") {
          val seqs = durableSeqs(received)
          seqs shouldBe seqs.distinct.sorted
          seqs.headOption shouldBe Some(1L)
          seqs shouldBe (1L to seqs.last).toVector
          val lives = received.collect { case l: StreamEvent.Live => l.payload }
          lives shouldBe lives.sortBy(p => (p("node").num, p("n").num))
          lives shouldBe lives.distinct
          // subscribed before the run started: nothing may be missing
          if k % 2 == 0 then lives.size shouldBe 100
          received.collect { case g: StreamEvent.LiveGap => g } shouldBe empty
        }
      }
      subscriptions.foreach(_.cancel())
      runtime.liveSubscriptions(thread) shouldBe 0
    }
  }

  they should "not leak from the live set when cancelled at any point of replay" in {
    val runtime = GraphRuntime.inMemory()
    val thread  = ThreadId("churn")
    runtime.start(thread, chain(steps = 3, live = 2), ()).awaited.value
    val graph     = chain(steps = 3, live = 50)
    val run       = Future((1 to 5).foreach(_ => runtime.start(thread, graph, ()).awaited.value))
    val lateCalls = new AtomicInteger()
    (1 to 300).foreach { i =>
      val cancelled = new AtomicBoolean(false)
      val sub       = runtime.subscribe(thread)(_ => if cancelled.get then lateCalls.incrementAndGet(): Unit).value
      if i % 3 == 0 then Thread.sleep(1)
      sub.cancel()
      cancelled.set(true)
      // cancel leaves the live set before it returns, whatever the dispatcher was doing
      runtime.liveSubscriptions(thread) shouldBe 0
    }
    Await.result(run, 30.seconds)
    lateCalls.get shouldBe 0
    runtime.liveSubscriptions(thread) shouldBe 0
  }
}
