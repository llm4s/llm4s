package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.*
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Live subscriptions across runtimes over one SQLite file (#1705). SQLite has no way to tell another connection of
 * a commit, so each subscription polls the event log - `Checkpointer.awaitEventsAfter`'s default, every
 * `WatchPolicy.pollInterval` - and a subscriber in one runtime receives what another runtime, with a connection of
 * its own, commits: in order, without gap or duplicate, in every durability mode, across a restart, and under
 * contention. The cases every store passes are `CheckpointerContract`'s, run by `SqliteCheckpointerContractSpec`.
 */
class SqliteCrossRuntimeSubscriptionSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val patience = 20.seconds
  private val quick    = WatchPolicy(pollInterval = 20.millis)

  private def runtime(store: Checkpointer, watch: WatchPolicy = quick): GraphRuntime =
    GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, watch)

  private def opened(path: java.nio.file.Path): SqliteCheckpointer = SqliteFiles.opened(path, Clock.systemUTC())

  private def eventually(what: String)(condition: => Boolean): Unit = {
    val deadline = System.nanoTime() + patience.toNanos
    while (!condition && System.nanoTime() < deadline) Thread.sleep(10)
    if (!condition) fail(s"timed out waiting for $what")
  }

  private def completed[O](handle: Result[RunHandle[O]]): O = handle.value.await().value match {
    case RunResult.Completed(_, output, _) => output
    case other                             => fail(s"run did not complete: $other")
  }

  /** What a subscription delivered, in order; each call waits for `pause`, if any, and then `slow`. */
  final private class Seen(pause: Option[CountDownLatch] = None, slow: FiniteDuration = Duration.Zero)
      extends (StreamEvent => Unit) {
    private val events = new CopyOnWriteArrayList[StreamEvent]()
    def apply(event: StreamEvent): Unit = {
      pause.foreach(_.await(patience.toMillis, TimeUnit.MILLISECONDS))
      if (slow > Duration.Zero) Thread.sleep(slow.toMillis)
      events.add(event): Unit
    }
    def records: Vector[EventRecord] = events.asScala.toVector.collect { case StreamEvent.Durable(r) => r }
    def seqs: Vector[Long]           = records.map(_.seq)
    def disconnected: Option[StreamEvent.Disconnected] =
      events.asScala.collectFirst { case d: StreamEvent.Disconnected => d }
  }

  /** `(seq, runId, event)` of a thread's whole log, as written. */
  private def logOf(store: Checkpointer, thread: ThreadId): Vector[(Long, String, RunEvent)] =
    store.eventsAfter(thread, 0L, 10000).value.map(r => (r.seq, r.runId, r.event))

  private def shape(records: Vector[EventRecord]): Vector[(Long, String, RunEvent)] =
    records.map(r => (r.seq, r.runId, r.event))

  /** A store that counts its reads of the event log. */
  final private class Counted(underlying: Checkpointer) extends Checkpointer {
    val reads                                            = new AtomicInteger()
    def claim(threadId: ThreadId, request: ClaimRequest) = underlying.claim(threadId, request)
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) =
      underlying.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: FencingToken)   = underlying.release(threadId, token)
    def commit(threadId: ThreadId, commit: Commit)         = underlying.commit(threadId, commit)
    def latest(threadId: ThreadId)                         = underlying.latest(threadId)
    def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId)                   = underlying.deleteThread(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] = {
      reads.incrementAndGet()
      underlying.eventsAfter(threadId, afterSeq, limit)
    }
  }

  Seq(Durability.Sync, Durability.Async, Durability.OnExit).foreach { durability =>
    s"A subscription in another runtime over the file ($durability)" should
      "receive every run committed through the first, in order, without gap or duplicate" in {
        val db      = SqliteFiles.fresh()
        val thread  = ThreadId(s"across-$durability")
        val workers = Workers()
        val storeA  = opened(db)
        val storeB  = opened(db)
        val seen    = Seen()
        val b       = runtime(storeB)
        val sub     = b.subscribe(thread)(seen).value
        // live, so the commits reach it through its watch rather than its replay
        eventually("the watch to start")(b.storeWatches(thread) == 1)
        val a = runtime(storeA)
        completed(a.start(thread, workers.graph, Vector("a", "b"), durability = durability))
        completed(a.start(thread, workers.graph, Vector("c"), durability = durability))
        completed(a.start(thread, workers.graph, Vector("d", "e", "f"), durability = durability)) shouldBe
          Vector("A", "B", "C", "D", "E", "F")
        val committed = logOf(storeA, thread)
        eventually("the subscriber to catch up")(seen.seqs.size >= committed.size)
        shape(seen.records) shouldBe committed
        seen.disconnected shouldBe None
        sub.cancel()
        storeA.close()
        storeB.close()
      }
  }

  "A subscription whose process stops" should
    "resume from its last sequence number after the restart, with what was committed meanwhile" in {
      val db      = SqliteFiles.fresh()
      val thread  = ThreadId("restart")
      val workers = Workers()
      val storeA  = opened(db)
      val a       = runtime(storeA)
      val before  = opened(db)
      val first   = Seen()
      val watcher = runtime(before)
      watcher.subscribe(thread)(first).value
      eventually("the watch to start")(watcher.storeWatches(thread) == 1)
      completed(a.start(thread, workers.graph, Vector("a")))
      eventually("the first run")(first.seqs.size == logOf(storeA, thread).size)

      // the subscriber's process stops: its store closes under the subscription, whose next read of the file fails
      before.close()
      eventually("the subscription to end")(first.disconnected.isDefined)
      val lastSeq = first.disconnected.value.lastSeq
      lastSeq shouldBe first.seqs.last
      first.disconnected.value.reason.isInstanceOf[DisconnectReason.ReplayFailed] shouldBe true

      completed(a.start(thread, workers.graph, Vector("b"))) // committed while it is down
      val after   = Seen()
      val resumed = runtime(opened(db)).subscribe(thread, afterSeq = lastSeq)(after).value
      completed(a.start(thread, workers.graph, Vector("c"))) // and after it is back
      val committed = logOf(storeA, thread)
      eventually("the resumed subscriber to catch up")(after.seqs.lastOption.contains(committed.last._1))
      shape(first.records ++ after.records) shouldBe committed
      resumed.cancel()
      storeA.close()
    }

  "A slow subscriber with a small queue" should "receive another runtime's commits without lagging" in {
    val db      = SqliteFiles.fresh()
    val thread  = ThreadId("slow")
    val workers = Workers()
    val storeA  = opened(db)
    val held    = new CountDownLatch(1)
    val seen    = Seen(pause = Some(held), slow = 2.millis)
    val b       = runtime(opened(db))
    val sub     = b.subscribe(thread, capacity = 2)(seen).value
    eventually("the watch to start")(b.storeWatches(thread) == 1)
    val a = runtime(storeA)
    (1 to 4).foreach(i => completed(a.start(thread, workers.graph, Vector(s"x$i", s"y$i"))))
    val committed = logOf(storeA, thread)
    committed.size should be > 10
    held.countDown()
    eventually("the slow subscriber to catch up")(seen.seqs.size >= committed.size)
    shape(seen.records) shouldBe committed
    seen.disconnected shouldBe None
    sub.cancel()
  }

  "A subscription's watch" should "poll the file once per poll interval, and stop reading it once cancelled" in {
    val db       = SqliteFiles.fresh()
    val thread   = ThreadId("polled")
    val counted  = Counted(opened(db))
    val interval = 200.millis
    val b        = runtime(counted, WatchPolicy(pollInterval = interval))
    val sub      = b.subscribe(thread)(Seen()).value
    eventually("the watch to start")(b.storeWatches(thread) == 1)
    val start = counted.reads.get
    Thread.sleep(1000)
    val polled = counted.reads.get - start
    // about five reads in a second; generous bounds, for a loaded machine
    polled should be >= 2
    polled should be <= 8
    sub.cancel()
    eventually("the watch to stop")(b.storeWatches(thread) == 0)
    val reads = counted.reads.get
    Thread.sleep(interval.toMillis * 3)
    counted.reads.get shouldBe reads
    b.liveSubscriptions(thread) shouldBe 0
  }

  "Subscriptions in several runtimes, each with a connection of its own" should
    "each see every thread's whole log, in order, while the runtimes race for the threads" in {
      val db       = SqliteFiles.fresh()
      val stores   = Vector.fill(3)(opened(db))
      val runtimes = stores.map(runtime(_))
      val threads  = Vector(ThreadId("race-1"), ThreadId("race-2"))
      val seen = for {
        r <- runtimes
        t <- threads
      } yield (t, Seen(), r)
      val subs = seen.map { case (t, listener, r) => r.subscribe(t, capacity = 4096)(listener).value }
      seen.foreach { case (t, _, r) => eventually("the watches to start")(r.storeWatches(t) == 1) }
      val workers = Workers()
      val racers = for {
        (r, i) <- runtimes.zipWithIndex
        t      <- threads
      } yield Thread.ofVirtual().start { () =>
        (1 to 6).foreach { n =>
          // retried until admitted: the runtimes contend for each thread
          Iterator
            .continually(
              r.start(t, workers.graph, Vector(s"r$i-$n"), RunConfig().withRunId(RunId(s"${t.value}-r$i-$n")))
                .flatMap(_.await())
            )
            .find { outcome =>
              if (outcome.isLeft) Thread.sleep(2)
              outcome.isRight
            }: Unit
        }
      }
      racers.foreach(_.join(java.time.Duration.ofSeconds(120)))
      seen.foreach { case (t, listener, _) =>
        val committed = logOf(stores.head, t)
        committed.map(_._2).distinct.size shouldBe 3 * 6
        eventually(s"a subscriber to ${t.value} to catch up")(listener.seqs.size >= committed.size)
        shape(listener.records) shouldBe committed
      }
      subs.foreach(_.cancel())
      stores.foreach(_.close())
    }
}
