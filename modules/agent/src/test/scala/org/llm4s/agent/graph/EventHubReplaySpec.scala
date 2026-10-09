package org.llm4s.agent.graph

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import scala.jdk.CollectionConverters.*

/**
 * Live events handed to the event hub while a subscription replays (#1731): held, bounded, and
 * delivered in their place among the durable events, at the dispatcher.
 */
class EventHubReplaySpec extends AnyFlatSpec with Matchers:

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

  private def live(i: Int): StreamEvent.Live =
    StreamEvent.Live(thread.value, run.value, "task", "node", "test.progress", 1, ujson.Num(i))

  /** A run-scoped listener recording each event's label and each barrier reached, in one sequence. */
  private class Recording extends RunListener:
    val seen  = new CopyOnWriteArrayList[String]()
    val ended = new CountDownLatch(1)
    def apply(event: StreamEvent): Unit =
      seen.add(event match
        case StreamEvent.Durable(r)                => r.seq.toString
        case StreamEvent.Disconnected(_, reason)   => s"Disconnected($reason)"
        case StreamEvent.LiveGap(n)                => s"gap:$n"
        case StreamEvent.Live(_, _, _, _, _, _, p) => s"live:${p.num.toInt}"
      )
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
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]            = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      val n = reads.incrementAndGet()
      if n == 1 then
        reading.countDown()
        release.await(5, TimeUnit.SECONDS): Unit
      Right(page(n).filter(_.seq > afterSeq).take(limit))
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId): Result[Unit]                   = underlying.deleteThread(threadId)

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
    hub.durable(thread, Vector(record(4))) // all slots taken: merged into that gap marker
    hub.live(thread, live(8))              // dropped, and pending: reported with the barrier
    sub.endOfRun(run)
    store.release.countDown()
    listener.awaitEnd() shouldBe
      Vector("1", "2", "live:1", "live:2", "live:3", "gap:4", "3", "4", "gap:1", "end:r1")
    sub.cancel()
  }

  it should "count held live events as dropped when its catch-up makes it lag" in {
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
