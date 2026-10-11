package org.llm4s.agent.graph

import org.llm4s.types.Result
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import scala.jdk.CollectionConverters.*

/**
 * Live events handed to the event hub while a subscription replays (#1731): held, bounded, and
 * delivered in their place among the durable events, at the dispatcher.
 */
class EventHubReplaySpec extends AnyFlatSpec with Matchers with Eventually:
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))

  private val thread = ThreadId("t")
  private val run    = RunId("r1")

  private def record(seq: Long): EventRecord =
    EventRecord(
      thread.value,
      seq,
      run.value,
      None,
      None,
      None,
      java.time.Instant.EPOCH,
      RunEvent.RunStarted(None, None)
    )

  private def terminal(seq: Long): EventRecord =
    EventRecord(thread.value, seq, run.value, None, None, None, java.time.Instant.EPOCH, RunEvent.RunCompleted)

  private def label(event: StreamEvent): String = event match
    case StreamEvent.Durable(r)                => r.seq.toString
    case StreamEvent.Disconnected(_, reason)   => s"Disconnected($reason)"
    case StreamEvent.LiveGap(n)                => s"gap:$n"
    case StreamEvent.Live(_, _, _, _, _, _, p) => s"live:${p.num.toInt}"

  private def live(i: Int): StreamEvent.Live =
    StreamEvent.Live(thread.value, run.value, "task", "node", "test.progress", 1, ujson.Num(i))

  /** A run-scoped listener recording each event's label and each barrier reached, in one sequence. */
  private class Recording extends RunListener:
    val seen  = new CopyOnWriteArrayList[String]()
    val ended = new CountDownLatch(1)
    def apply(event: StreamEvent): Unit =
      seen.add(label(event))
      if event.isInstanceOf[StreamEvent.Disconnected] then ended.countDown()
    def runEnded(runId: RunId): Unit =
      seen.add(s"end:${runId.value}")
      ended.countDown()
    def awaitEnd(): Vector[String] =
      ended.await(5, TimeUnit.SECONDS) shouldBe true
      seen.asScala.toVector

  /**
   * A log whose `n`th read returns `page(n)`, the first read signalling `reading` and then waiting
   * for `release`: a subscription's replay held up by its store.
   */
  final private class ScriptedLog(page: Int => Vector[EventRecord]) extends Checkpointer:
    private val underlying                                                      = InMemoryCheckpointer()
    private val reads                                                           = new AtomicInteger(0)
    val reading                                                                 = new CountDownLatch(1)
    val release                                                                 = new CountDownLatch(1)
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = underlying.commit(threadId, commit)
    def claim(threadId: ThreadId, request: org.llm4s.agent.graph.ClaimRequest)  = underlying.claim(threadId, request)
    def renew(
      threadId: ThreadId,
      token: org.llm4s.agent.graph.FencingToken,
      ttl: scala.concurrent.duration.FiniteDuration
    ) =
      underlying.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: org.llm4s.agent.graph.FencingToken) = underlying.release(threadId, token)
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]           = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      val n = reads.incrementAndGet()
      if n == 1 then
        reading.countDown()
        release.await(5, TimeUnit.SECONDS): Unit
      Right(page(n).filter(_.seq > afterSeq).take(limit))
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId): Result[Unit]                   = underlying.deleteThread(threadId)
    def history(threadId: ThreadId, before: Option[String], limit: Int)  = underlying.history(threadId, before, limit)
    def checkpoint(threadId: ThreadId, checkpointId: String)             = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy)               = underlying.prune(threadId, policy)

  "A replaying subscription" should "be in the live set when subscribe returns, and place held live events by commit" in {
    @volatile var log = Vector(record(1), record(2))
    val store         = ScriptedLog(_ => log)
    val hub           = EventHub(store)
    val listener      = Recording()
    val sub = hub.subscribe(thread, afterSeq = 0L, capacity = 16, listener).fold(e => fail(e.message), identity)
    hub.liveCount(thread) shouldBe 1
    store.reading.await(5, TimeUnit.SECONDS) shouldBe true
    // sent before any commit reached the hub: after everything already in the log
    hub.live(thread, live(1))
    hub.live(thread, live(2))
    log = log :+ record(3)
    hub.durable(thread, Vector(record(3)))
    hub.live(thread, live(3))
    log = log ++ Vector(record(4), record(5))
    hub.durable(thread, Vector(record(4), record(5)))
    hub.live(thread, live(4))
    sub.endOfRun(run)
    store.release.countDown()
    listener.awaitEnd() shouldBe
      Vector("1", "2", "live:1", "live:2", "3", "live:3", "4", "5", "live:4", "end:r1")
    sub.cancel()
    hub.liveCount(thread) shouldBe 0
  }

  it should "hold at most capacity live events, reporting the dropped ones as gaps before the next commit" in {
    @volatile var log = Vector(record(1), record(2))
    val store         = ScriptedLog(_ => log)
    val hub           = EventHub(store)
    val listener      = Recording()
    val sub = hub.subscribe(thread, afterSeq = 0L, capacity = 4, listener).fold(e => fail(e.message), identity)
    store.reading.await(5, TimeUnit.SECONDS) shouldBe true
    (1 to 5).foreach(i => hub.live(thread, live(i))) // 3 held, one slot kept for a gap marker; 2 dropped
    log = log :+ record(3)
    hub.durable(thread, Vector(record(3))) // the 2 dropped are held as a gap marker, in the last slot
    (6 to 7).foreach(i => hub.live(thread, live(i)))
    log = log :+ record(4)
    // all slots taken: merged into that gap marker, which moves to just before 4 - 6 and 7 were
    // dropped after 3, so reporting them before it would be early; 4 and 5 are reported late instead
    hub.durable(thread, Vector(record(4)))
    hub.live(thread, live(8)) // dropped, and pending: reported with the barrier
    sub.endOfRun(run)
    store.release.countDown()
    listener.awaitEnd() shouldBe
      Vector("1", "2", "live:1", "live:2", "live:3", "3", "gap:4", "4", "gap:1", "end:r1")
    sub.cancel()
  }

  // A commit is written to the store before the hub hands it over, so a replay can read one that has
  // not been handed over yet. A subscription that joins mid-run, before any commit is handed to it,
  // must still place a live event sent before that commit ahead of it.

  "A subscription joining mid-run" should "deliver a live event sent before a commit the replay reads early before it" in {
    @volatile var log = Vector(record(1))
    val store         = ScriptedLog(_ => log)
    val hub           = EventHub(store)
    hub.durable(thread, Vector(record(1))) // the run's claim, handed over before the subscription
    val listener = Recording()
    val sub      = hub.subscribe(thread, afterSeq = 0L, capacity = 16, listener).fold(e => fail(e.message), identity)
    store.reading.await(5, TimeUnit.SECONDS) shouldBe true
    hub.live(thread, live(1)) // the node's progress: nothing handed to this subscription yet
    log = log :+ record(2)    // then the node's commit is written ...
    store.release.countDown() // ... and the replay reads it before it is handed over
    eventually(listener.seen.asScala should contain("live:1"))
    hub.durable(thread, Vector(record(2))) // the hand-over
    eventually(listener.seen.asScala should contain("2"))
    // what a subscription already past its replay, or an Observer, sees
    listener.seen.asScala.toVector shouldBe Vector("1", "live:1", "2")
    sub.cancel()
  }

  it should "deliver a run-scoped listener a live event sent before its run's terminal commit" in {
    @volatile var log = Vector(record(1))
    val store         = ScriptedLog(_ => log)
    val hub           = EventHub(store)
    hub.durable(thread, Vector(record(1)))
    val seen  = new CopyOnWriteArrayList[String]()
    val scope = org.llm4s.agent.RunScope(run, e => seen.add(label(e)): Unit)
    val sub   = hub.subscribe(thread, afterSeq = 0L, capacity = 16, scope).fold(e => fail(e.message), identity)
    scope.attach(sub)
    store.reading.await(5, TimeUnit.SECONDS) shouldBe true
    hub.live(thread, live(1))
    log = log :+ terminal(2)
    store.release.countDown()
    eventually(seen.asScala should contain("live:1"))
    hub.durable(thread, Vector(terminal(2)))
    sub.endOfRun(run)
    // the scope ends at its terminal event: a live event delivered after it would be lost
    eventually(seen.asScala should contain("2"))
    seen.asScala.toVector shouldBe Vector("1", "live:1", "2")
  }

  it should "deliver a live event sent between a commit's write and its hand-over before it" in {
    @volatile var log = Vector(record(1))
    val store         = ScriptedLog(_ => log)
    val hub           = EventHub(store)
    hub.durable(thread, Vector(record(1)))
    val listener = Recording()
    val sub      = hub.subscribe(thread, afterSeq = 0L, capacity = 16, listener).fold(e => fail(e.message), identity)
    log = log :+ record(2) // written, not yet handed over
    store.reading.await(5, TimeUnit.SECONDS) shouldBe true
    store.release.countDown()
    eventually(listener.seen.asScala should contain("1"))
    // held or, once the subscription has switched to live, queued: either way before 2
    hub.live(thread, live(1))
    hub.durable(thread, Vector(record(2)))
    eventually(listener.seen.asScala should contain("2"))
    listener.seen.asScala.toVector shouldBe Vector("1", "live:1", "2")
    sub.cancel()
  }

  "The hub" should "keep a thread's hand-over mark only until the thread is released" in {
    val hub = EventHub(ScriptedLog(_ => Vector.empty))
    hub.marked(thread) shouldBe false
    hub.durable(thread, Vector(record(1), record(2)))
    hub.marked(thread) shouldBe true
    hub.release(thread)
    hub.marked(thread) shouldBe false
  }

  "A replaying subscription" should "count held live events as dropped when its catch-up makes it lag" in {
    // the replay's read finds nothing; the switch to live's catch-up finds five commits
    @volatile var log = Vector.empty[EventRecord]
    val store         = ScriptedLog(n => if n == 1 then Vector.empty else log)
    val hub           = EventHub(store)
    val listener      = Recording()
    hub.subscribe(thread, afterSeq = 0L, capacity = 3, listener).fold(e => fail(e.message), identity)
    store.reading.await(5, TimeUnit.SECONDS) shouldBe true
    hub.live(thread, live(1))
    log = (1L to 5L).map(record).toVector
    hub.durable(thread, log)
    hub.live(thread, live(2))
    store.release.countDown()
    // 4 does not fit in a queue of three: lagging; live:2 followed 5, so it is counted, not delivered
    listener.awaitEnd() shouldBe Vector("live:1", "1", "2", "3", "gap:1", "Disconnected(Lagging)")
    hub.liveCount(thread) shouldBe 0
  }

  it should "leave the live set and deliver nothing when cancelled while replaying" in {
    val store    = ScriptedLog(_ => Vector(record(1)))
    val hub      = EventHub(store)
    val listener = Recording()
    val sub      = hub.subscribe(thread, afterSeq = 0L, capacity = 16, listener).fold(e => fail(e.message), identity)
    store.reading.await(5, TimeUnit.SECONDS) shouldBe true
    hub.live(thread, live(1))
    sub.cancel() // interrupts the blocked read
    hub.liveCount(thread) shouldBe 0
    hub.live(thread, live(2))
    store.release.countDown()
    Thread.sleep(100)
    listener.seen.asScala shouldBe empty
  }
