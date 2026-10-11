package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, ThreadLocalRandom, TimeUnit }
import scala.concurrent.duration.*
import scala.concurrent.{ blocking, Await, ExecutionContext, Future }
import scala.jdk.CollectionConverters.*

/**
 * A subscription made at a random point of a run sees the events in the order an [[Observer]] -
 * subscribed before the run's claim - sees them, less the live events sent before it joined, while
 * the store's reads race commits and live events (#1731).
 */
class SubscriptionOrderStressSpec extends AnyFlatSpec with Matchers with EitherValues {
  given ExecutionContext = ExecutionContext.global

  /** Reads the log when called and returns it after a random pause, so a read races commits. */
  final private class JitteryStore extends Checkpointer {
    val underlying                                                             = InMemoryCheckpointer()
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
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] = {
      val page = underlying.eventsAfter(threadId, afterSeq, limit)
      if ThreadLocalRandom.current().nextInt(4) > 0 then Thread.sleep(0, ThreadLocalRandom.current().nextInt(800000))
      page
    }
    def compactEvents(threadId: ThreadId, beforeSeq: Long)              = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId)                                = underlying.deleteThread(threadId)
    def history(threadId: ThreadId, before: Option[String], limit: Int) = underlying.history(threadId, before, limit)
    def checkpoint(threadId: ThreadId, checkpointId: String)            = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy)              = underlying.prune(threadId, policy)
  }

  /** `steps` nodes in a line, each sending `live` progress events and then one custom event. */
  private def chain(steps: Int, live: Int): CompiledGraph[Unit, Unit] = {
    val b = GraphBuilder("p", "v1")
    val nodes = (0 until steps).map { i =>
      b.node[Unit](s"n$i") { (_, _, ctx) =>
        (1 to live).foreach { n =>
          ctx.progress("p", 1, ujson.Obj("node" -> i, "n" -> n))
          if ThreadLocalRandom.current().nextInt(5) == 0 then Thread.`yield`()
        }
        ctx.emit("e", 1, ujson.Num(i))
        continue(Command.empty)
      }
    }
    nodes.zip(nodes.drop(1)).foreach((from, to) => b.edge(from, to))
    b.compile(nodes.head)(_ => Right(())).value
  }

  private def completed(events: Vector[StreamEvent]): Int =
    events.count {
      case StreamEvent.Durable(r) => r.event == RunEvent.RunCompleted
      case _                      => false
    }

  private def key(e: StreamEvent): String = e match {
    case StreamEvent.Durable(r) => s"d${r.seq}"
    case l: StreamEvent.Live    => s"l${l.runId}:${l.payload}"
    case other                  => other.toString
  }

  "A subscription made mid-run" should "see the observer's order, less a prefix of live events" in {
    val failures = new AtomicInteger()
    (1 to 300).foreach { i =>
      val store      = JitteryStore()
      val runtime    = GraphRuntime(store)
      val thread     = ThreadId(s"o-$i")
      val obs        = new CopyOnWriteArrayList[StreamEvent]()
      val sub        = new CopyOnWriteArrayList[StreamEvent]()
      val runs       = 3
      val graph      = chain(steps = 4, live = 6)
      val durability = if i % 2 == 0 then Durability.Async else Durability.Sync
      val go         = new CountDownLatch(1)
      val delayUs    = ThreadLocalRandom.current().nextInt(3000)
      val subscribed = Future {
        blocking(go.await(5, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        while System.nanoTime() - t0 < delayUs * 1000L do Thread.onSpinWait()
        runtime.subscribe(thread)(e => sub.add(e): Unit).value
      }
      go.countDown()
      runtime
        .start(thread, graph, (), durability = durability, observer = Some(Observer(4096, e => obs.add(e): Unit)))
        .awaited
        .value
      (2 to runs).foreach(_ => runtime.start(thread, graph, (), durability = durability).awaited.value)
      val s        = Await.result(subscribed, 5.seconds)
      val deadline = System.nanoTime() + 10.seconds.toNanos
      while (completed(obs.asScala.toVector) < runs || completed(sub.asScala.toVector) < runs) &&
        System.nanoTime() < deadline
      do Thread.sleep(2)
      val o       = obs.asScala.toVector.map(key)
      val r       = sub.asScala.toVector.map(key)
      val missing = o.count(_.startsWith("l")) - r.count(_.startsWith("l"))
      // the observer's sequence without its first `missing` live events
      val expected = o
        .foldLeft((Vector.empty[String], missing)) { case ((kept, toDrop), e) =>
          if toDrop > 0 && e.startsWith("l") then (kept, toDrop - 1) else (kept :+ e, toDrop)
        }
        ._1
      if r != expected then
        failures.incrementAndGet()
        if failures.get <= 3 then
          info(
            s"iteration $i ($durability, delay ${delayUs}us, missing $missing):\n  expected=$expected\n  received=$r"
          )
      s.cancel()
      runtime.handOverMarked(thread) shouldBe false
    }
    failures.get shouldBe 0
  }

  "A subscription cancelled at a random point" should "never call its listener after cancel returns, nor stay in the live set" in {
    val store   = JitteryStore()
    val runtime = GraphRuntime(store)
    val thread  = ThreadId("churn")
    runtime.start(thread, chain(steps = 3, live = 2), ()).awaited.value
    val graph = chain(steps = 3, live = 40)
    val run   = Future((1 to 10).foreach(_ => runtime.start(thread, graph, ()).awaited.value))
    val late  = new AtomicInteger()
    (1 to 600).foreach { i =>
      val cancelled = new AtomicBoolean(false)
      val s =
        runtime.subscribe(thread, capacity = 2 + i % 7)(_ => if cancelled.get then late.incrementAndGet(): Unit).value
      val spin = ThreadLocalRandom.current().nextInt(2000)
      val t0   = System.nanoTime()
      while System.nanoTime() - t0 < spin * 1000L do Thread.onSpinWait()
      s.cancel()
      cancelled.set(true)
      runtime.liveSubscriptions(thread) shouldBe 0
    }
    Await.result(run, 60.seconds)
    Thread.sleep(100)
    late.get shouldBe 0
    runtime.liveSubscriptions(thread) shouldBe 0
  }

  "The runtime" should "keep a thread's hand-over mark only while a run holds the thread" in {
    val runtime = GraphRuntime(InMemoryCheckpointer())
    val thread  = ThreadId("marked")
    val entered = new CountDownLatch(1)
    val proceed = new CountDownLatch(1)
    val b       = GraphBuilder("m", "v1")
    val only = b.node[Unit]("only") { (_, _, _) =>
      entered.countDown()
      blocking(proceed.await(5, TimeUnit.SECONDS)): Unit
      continue(Command.empty)
    }
    val graph  = b.compile(only)(_ => Right(())).value
    val handle = runtime.start(thread, graph, ()).value
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    runtime.handOverMarked(thread) shouldBe true
    proceed.countDown()
    awaitResult(handle).value
    runtime.handOverMarked(thread) shouldBe false
    runtime.deleteThread(thread).value
    runtime.handOverMarked(thread) shouldBe false
  }
}
