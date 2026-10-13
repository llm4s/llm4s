package org.llm4s.agent.graph

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/**
 * The event hub's end-of-run barrier, at the dispatcher: lagging, and a barrier given during replay;
 * and the queue's bound when dropped live events and durable commits alternate.
 */
class EventHubBarrierSpec extends AnyFlatSpec with Matchers:

  private val thread = ThreadId("t")
  private val run    = RunId("r1")

  private def record(seq: Long, runId: RunId = run): EventRecord =
    EventRecord(
      thread.value,
      seq,
      runId.value,
      None,
      None,
      None,
      java.time.Instant.EPOCH,
      RunEvent.RunStarted(None, None)
    )

  /** A run-scoped listener recording, in one sequence, each event's label and each barrier reached. */
  private class Recording(onEvent: StreamEvent => Unit = _ => ()) extends RunListener:
    val seen    = new CopyOnWriteArrayList[String]()
    val ends    = new AtomicInteger(0)
    val reached = new CountDownLatch(1)
    // recorded before `onEvent`, so a latch it counts down is seen only after the record
    def apply(event: StreamEvent): Unit =
      seen.add(event match
        case StreamEvent.Durable(r)              => r.seq.toString
        case StreamEvent.Disconnected(_, reason) => s"Disconnected($reason)"
        case StreamEvent.LiveGap(n)              => s"gap:$n"
        case _: StreamEvent.Live                 => "live"
      )
      onEvent(event)
    def runEnded(runId: RunId): Unit =
      ends.incrementAndGet()
      seen.add(s"end:${runId.value}")
      reached.countDown()

  "A run-scoped dispatcher already lagging" should "end with Disconnected(Lagging), never reaching a barrier" in {
    val entered  = new CountDownLatch(1)
    val proceed  = new CountDownLatch(1)
    val finished = new CountDownLatch(1)
    val listener = Recording {
      case StreamEvent.Durable(r) if r.seq == 1 =>
        entered.countDown()
        proceed.await(5, TimeUnit.SECONDS): Unit
      case _: StreamEvent.Disconnected => finished.countDown()
      case _                           => ()
    }
    val hub = EventHub(InMemoryCheckpointer())
    val sub = hub.observe(thread, capacity = 2, listener).start(lastSeq = 0L)
    hub.durable(thread, Vector(record(1)))
    entered.await(5, TimeUnit.SECONDS) shouldBe true             // the listener holds the first event
    hub.durable(thread, Vector(record(2), record(3), record(4))) // 4 does not fit: lagging
    sub.endOfRun(run)
    proceed.countDown()
    finished.await(5, TimeUnit.SECONDS) shouldBe true
    listener.seen.asScala.toVector shouldBe Vector("1", "2", "3", "Disconnected(Lagging)")
    listener.ends.get shouldBe 0
    hub.liveCount(thread) shouldBe 0
  }

  /**
   * A log of `records` whose `blockOn`th read (counting from 1) signals `reading`, then blocks until
   * `release` opens; it returns what `records` holds once released.
   */
  final private class BlockingLog(initial: Vector[EventRecord], blockOn: Int) extends Checkpointer:
    private val underlying                                                      = InMemoryCheckpointer()
    private val reads                                                           = new AtomicInteger(0)
    @volatile var records                                                       = initial
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
      if reads.incrementAndGet() == blockOn then
        reading.countDown()
        release.await(5, TimeUnit.SECONDS): Unit
      Right(records.filter(_.seq > afterSeq).take(limit))
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId, token: FencingToken): Result[Unit] = underlying.deleteThread(threadId, token)
    def history(threadId: ThreadId, before: Option[String], limit: Int) = underlying.history(threadId, before, limit)
    def checkpoint(threadId: ThreadId, checkpointId: String)            = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy)              = underlying.prune(threadId, policy)

  "A barrier given before the dispatcher joins the live set" should "be reached after its catch-up" in {
    // replay reads [1, 2], then an empty page; the third read is switchToLive's catch-up, under hubLock
    val log      = BlockingLog(Vector(record(1), record(2)), blockOn = 3)
    val listener = Recording()
    val hub      = EventHub(log)
    val sub      = hub.subscribe(thread, afterSeq = 0L, capacity = 16, listener).fold(e => fail(e.message), identity)
    log.reading.await(5, TimeUnit.SECONDS) shouldBe true // the catch-up read is blocked; not yet joined
    // the run's last event, committed after the replay, is visible only to the catch-up; then the run ends
    log.records = log.records :+ record(3)
    sub.endOfRun(run)
    log.release.countDown()
    listener.reached.await(5, TimeUnit.SECONDS) shouldBe true
    // a barrier queued at once, ignoring that the dispatcher has not joined, would precede 3
    listener.seen.asScala.toVector shouldBe Vector("1", "2", "3", "end:r1")
    sub.cancel()
    hub.liveCount(thread) shouldBe 0
  }

  "A held dispatcher" should "stay within twice its capacity when dropped live events and durable commits alternate" in {
    val capacity = 4
    val entered  = new CountDownLatch(1)
    val proceed  = new CountDownLatch(1)
    val listener = Recording {
      case StreamEvent.Durable(r) if r.seq == 1 =>
        entered.countDown()
        proceed.await(5, TimeUnit.SECONDS): Unit
      case _ => ()
    }
    def live(i: Int): StreamEvent.Live =
      StreamEvent.Live(thread.value, run.value, "task", "node", "test.progress", 1, ujson.Num(i))
    val hub = EventHub(InMemoryCheckpointer())
    val sub = hub.observe(thread, capacity, listener).start(lastSeq = 0L)
    hub.durable(thread, Vector(record(1)))
    entered.await(5, TimeUnit.SECONDS) shouldBe true // the listener holds the first event

    // fills the live slots: 3 accepted (one slot is kept for a gap marker), 7 dropped
    (1 to 10).foreach(i => hub.live(thread, live(i)))
    sub.queued shouldBe capacity - 1
    // each commit follows a dropped live event, so each needs a gap before it; the live slots are full
    (2 to capacity + 1).foreach { seq =>
      hub.live(thread, live(100 + seq))
      hub.durable(thread, Vector(record(seq.toLong)))
      sub.queued should be <= 2 * capacity
    }
    sub.queued shouldBe 2 * capacity - 1 // 3 live events and 4 durable ones, each gap riding with its event
    // two more dropped before the run ends: the barrier carries them, in its own slot
    (1 to 2).foreach(i => hub.live(thread, live(200 + i)))
    sub.endOfRun(run)
    sub.queued shouldBe 2 * capacity

    proceed.countDown()
    listener.reached.await(5, TimeUnit.SECONDS) shouldBe true
    // each gap is reported where it happened: before the commit, or the end, that followed the drops
    listener.seen.asScala.toVector shouldBe
      Vector("1", "live", "live", "live", "gap:8", "2", "gap:1", "3", "gap:1", "4", "gap:1", "5", "gap:2", "end:r1")
    sub.cancel()
    hub.liveCount(thread) shouldBe 0
  }

  "A run's cut taken while a hand-over's read of the events missing before it is in flight" should "count that commit as abandoned, so the run's listeners agree live and on replay" in {
    // 1 is the run's, handed over; 2 was committed elsewhere, so the run-scoped dispatcher is missing it
    // when the run's commit 3 is handed over, and reads it - the log's only read, the first - from the store
    val log      = BlockingLog(Vector(record(1), record(2, RunId("elsewhere"))), blockOn = 1)
    val cut      = AbandonedWrites(run)
    val fourth   = new CountDownLatch(1)
    val listener = Recording(countAt(4, fourth))
    val hub      = EventHub(log)
    val sub      = hub.observe(thread, capacity = 16, listener, cut.apply).start(lastSeq = 0L)
    hub.durable(thread, Vector(record(1)))
    log.records = log.records :+ record(3)
    val handing = Thread.ofVirtual().start(() => hub.durable(thread, Vector(record(3))))
    log.reading.await(5, TimeUnit.SECONDS) shouldBe true // 3's gap read is stuck in the store
    // the run loses its claim and stops waiting for it: its cut is taken, then its barrier queued
    hub.atMark(thread)(cut.abandonAfter)
    sub.endOfRun(run)
    listener.reached.await(5, TimeUnit.SECONDS) shouldBe true
    log.release.countDown()
    handing.join(java.time.Duration.ofSeconds(5)) shouldBe true
    // a later commit, by another run, shows the dispatcher has delivered or skipped everything before it
    log.records = log.records :+ record(4, RunId("next"))
    hub.durable(thread, Vector(record(4, RunId("next"))))
    fourth.await(5, TimeUnit.SECONDS) shouldBe true
    // 3 is the run's own, and above the cut: abandoned, never delivered - not after the barrier either
    listener.seen.asScala.toVector shouldBe Vector("1", "end:r1", "2", "4")
    sub.cancel()

    // a replay through the same cut agrees: the run's events are 1 alone
    val replayedFourth = new CountDownLatch(1)
    val replayed       = Recording(countAt(4, replayedFourth))
    val again =
      hub.subscribe(thread, afterSeq = 0L, capacity = 16, replayed, cut.apply).fold(e => fail(e.message), identity)
    replayedFourth.await(5, TimeUnit.SECONDS) shouldBe true
    replayed.seen.asScala.toVector shouldBe Vector("1", "2", "4")
    again.cancel()
    hub.liveCount(thread) shouldBe 0
  }

  /** Counts `latch` down once the durable event `seq` has been recorded. */
  private def countAt(seq: Long, latch: CountDownLatch): StreamEvent => Unit = {
    case StreamEvent.Durable(r) if r.seq == seq => latch.countDown()
    case _                                      => ()
  }
