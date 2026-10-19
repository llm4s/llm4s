package org.llm4s.agent.graph

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * The event hub's end-of-run barrier, at the dispatcher: lagging, and a barrier given during replay;
 * and the queue's bound when dropped live events and durable commits alternate.
 */
class EventHubBarrierSpec extends AnyFlatSpec with Matchers:

  private val thread = ThreadId("t")
  private val run    = RunId("r1")
  // the token of `run`'s claim: every record of the tests' run is committed under it
  private val claim = FencingToken(2L)

  private def record(seq: Long, runId: RunId = run, token: FencingToken = claim): EventRecord =
    EventRecord(
      thread.value,
      seq,
      runId.value,
      token,
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
    val sub =
      hub
        .observe(thread, capacity = 2, listener, AbandonedWrites.none, TenantScope.Unscoped)
        .claimed(claim)
        .start(lastSeq = 0L)
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
   * `release` opens; it returns what `records` holds once released. `afterRead` is told each read's
   * number once it has found what it returns, just before it returns it.
   */
  final private class BlockingLog(initial: Vector[EventRecord], blockOn: Int, afterRead: Int => Unit = _ => ())
      extends Checkpointer:
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
    def effect(threadId: ThreadId, key: org.llm4s.agent.graph.EffectKey)       = underlying.effect(threadId, key)
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]           = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      val read = reads.incrementAndGet()
      if read == blockOn then
        reading.countDown()
        release.await(5, TimeUnit.SECONDS): Unit
      val found = records.filter(_.seq > afterSeq).take(limit)
      afterRead(read)
      Right(found)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId, token: FencingToken): Result[Unit] = underlying.deleteThread(threadId, token)
    def children(parent: ThreadId, after: Option[ThreadId], limit: Int)     = underlying.children(parent, after, limit)
    def history(threadId: ThreadId, before: Option[String], limit: Int) = underlying.history(threadId, before, limit)
    def checkpoint(threadId: ThreadId, checkpointId: String)            = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy)              = underlying.prune(threadId, policy)

  "A barrier given before the dispatcher joins the live set" should "be reached after its catch-up" in {
    // replay reads [1, 2], then an empty page; the third read is switchToLive's catch-up, under hubLock
    val log      = BlockingLog(Vector(record(1), record(2)), blockOn = 3)
    val listener = Recording()
    val hub      = EventHub(log)
    val sub = hub
      .subscribe(thread, afterSeq = 0L, capacity = 16, listener, AbandonedWrites.none, TenantScope.Unscoped)
      .fold(e => fail(e.message), identity)
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
      StreamEvent.Live(thread.value, run.value, claim, "task", "node", "test.progress", 1, ujson.Num(i))
    val hub = EventHub(InMemoryCheckpointer())
    val sub =
      hub
        .observe(thread, capacity, listener, AbandonedWrites.none, TenantScope.Unscoped)
        .claimed(claim)
        .start(lastSeq = 0L)
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
    val sub =
      hub.observe(thread, capacity = 16, listener, cut.apply, TenantScope.Unscoped).claimed(claim).start(lastSeq = 0L)
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
      hub
        .subscribe(thread, afterSeq = 0L, capacity = 16, replayed, cut.apply, TenantScope.Unscoped)
        .fold(e => fail(e.message), identity)
    replayedFourth.await(5, TimeUnit.SECONDS) shouldBe true
    replayed.seen.asScala.toVector shouldBe Vector("1", "2", "4")
    again.cancel()
    hub.liveCount(thread) shouldBe 0
  }

  "A hand-over that one subscription must first read missing events for" should "reach no subscription until every read is done, so a run's cut taken meanwhile is the same for all its listeners and a replay" in {
    // 1 is the run's, handed over; 2 was committed elsewhere. `behind` has queued 1 and is missing 2;
    // `level` subscribes from 2, so it misses nothing. Reads: `level`'s replay, its switch to live, then
    // the third - the hand-over's read of 2 for `behind` - is stuck in the store
    val log         = BlockingLog(Vector(record(1), record(2, RunId("elsewhere"))), blockOn = 3)
    val cut         = AbandonedWrites(run)
    val behindFour  = new CountDownLatch(1)
    val levelFour   = new CountDownLatch(1)
    val levelLive   = new CountDownLatch(1)
    val levelMarker = new CountDownLatch(2)
    val behind      = Recording(countAt(4, behindFour))
    val level = Recording { event =>
      countAt(4, levelFour)(event)
      event match
        case _: StreamEvent.Live =>
          levelLive.countDown()
          levelMarker.countDown()
        case _ => ()
    }
    def live(i: Int): StreamEvent.Live =
      StreamEvent.Live(thread.value, run.value, claim, "task", "node", "test.progress", 1, ujson.Num(i))
    val hub = EventHub(log)
    val b =
      hub.observe(thread, capacity = 16, behind, cut.apply, TenantScope.Unscoped).claimed(claim).start(lastSeq = 0L)
    hub.durable(thread, Vector(record(1)))
    val l = hub
      .subscribe(thread, afterSeq = 2L, capacity = 16, level, cut.apply, TenantScope.Unscoped)
      .fold(e => fail(e.message), identity)
    // a live event reaches `level` only once it has switched to live
    hub.live(thread, live(0))
    levelLive.await(5, TimeUnit.SECONDS) shouldBe true
    log.records = log.records :+ record(3)
    val handing = Thread.ofVirtual().start(() => hub.durable(thread, Vector(record(3))))
    log.reading.await(5, TimeUnit.SECONDS) shouldBe true // `behind`'s read of 2 is stuck in the store
    // a live event sent now follows whatever of the hand-over `level` has been given: once `level` has
    // it, `level` has delivered anything of the run's 3 queued to it
    hub.live(thread, live(1))
    levelMarker.await(5, TimeUnit.SECONDS) shouldBe true
    // the run loses its claim and stops waiting: its cut is taken, then its barrier queued to both
    hub.atMark(thread)(cut.abandonAfter)
    b.endOfRun(run)
    l.endOfRun(run)
    behind.reached.await(5, TimeUnit.SECONDS) shouldBe true
    level.reached.await(5, TimeUnit.SECONDS) shouldBe true
    log.release.countDown()
    handing.join(java.time.Duration.ofSeconds(5)) shouldBe true
    // a later commit, by another run, shows each dispatcher has delivered or skipped everything before it
    log.records = log.records :+ record(4, RunId("next"))
    hub.durable(thread, Vector(record(4, RunId("next"))))
    behindFour.await(5, TimeUnit.SECONDS) shouldBe true
    levelFour.await(5, TimeUnit.SECONDS) shouldBe true
    // 3 was not handed over when the cut was taken: abandoned, on both subscriptions alike
    level.seen.asScala.toVector shouldBe Vector("live", "live", "end:r1", "4")
    behind.seen.asScala.toVector shouldBe Vector("1", "live", "live", "end:r1", "2", "4")
    b.cancel()
    l.cancel()

    // a replay through the same cut agrees: the run's events are 1 alone
    val replayedFourth = new CountDownLatch(1)
    val replayed       = Recording(countAt(4, replayedFourth))
    val again =
      hub
        .subscribe(thread, afterSeq = 0L, capacity = 16, replayed, cut.apply, TenantScope.Unscoped)
        .fold(e => fail(e.message), identity)
    replayedFourth.await(5, TimeUnit.SECONDS) shouldBe true
    replayed.seen.asScala.toVector shouldBe Vector("1", "2", "4")
    again.cancel()
    hub.liveCount(thread) shouldBe 0
  }

  /**
   * A log whose store watch returns only when `watchGo` gives it a permit, and whose read after `stallAfter`
   * off a subscriber's thread - a hand-over's read of missing events - signals `reading`, then blocks
   * until `release` opens. Every read off a subscriber's thread is counted, by where it starts.
   */
  final private class WatchedLog(stallAfter: Long) extends Checkpointer:
    private val underlying = InMemoryCheckpointer()
    @volatile var records  = Vector.empty[EventRecord]
    val watchGo            = new java.util.concurrent.Semaphore(0)
    val reading            = new CountDownLatch(1)
    val release            = new CountDownLatch(1)
    val handOverReads      = new CopyOnWriteArrayList[Long]()
    // opened by the first store watch to call `awaitEventsAfter`
    val watching                                                                = new CountDownLatch(1)
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = underlying.commit(threadId, commit)
    def claim(threadId: ThreadId, request: ClaimRequest)                        = underlying.claim(threadId, request)
    def renew(threadId: ThreadId, token: FencingToken, ttl: scala.concurrent.duration.FiniteDuration) =
      underlying.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: FencingToken)                 = underlying.release(threadId, token)
    def effect(threadId: ThreadId, key: org.llm4s.agent.graph.EffectKey) = underlying.effect(threadId, key)
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]     = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      if !Thread.currentThread().getName.startsWith("llm4s-") then
        handOverReads.add(afterSeq)
        if afterSeq == stallAfter && reading.getCount > 0 then
          reading.countDown()
          release.await(5, TimeUnit.SECONDS): Unit
      Right(records.filter(_.seq > afterSeq).take(limit))
    override def awaitEventsAfter(
      threadId: ThreadId,
      afterSeq: Long,
      limit: Int,
      timeout: scala.concurrent.duration.FiniteDuration
    ): Result[Vector[EventRecord]] =
      watching.countDown()
      if watchGo.tryAcquire(5, TimeUnit.SECONDS) then Right(records.filter(_.seq > afterSeq).take(limit))
      else Right(Vector.empty)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId, token: FencingToken): Result[Unit] = underlying.deleteThread(threadId, token)
    def children(parent: ThreadId, after: Option[ThreadId], limit: Int)     = underlying.children(parent, after, limit)
    def history(threadId: ThreadId, before: Option[String], limit: Int) = underlying.history(threadId, before, limit)
    def checkpoint(threadId: ThreadId, checkpointId: String)            = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy)              = underlying.prune(threadId, policy)

  "An observer's store watch" should "start watching the store once its run's first commit is handed to it, not a poll interval later" in {
    val log = WatchedLog(stallAfter = Long.MaxValue)
    // an hour: a watch that waited one interval for its observer's first commit would not watch in this test
    val hub    = EventHub(log, watch = WatchPolicy(pollInterval = 1.hour))
    val parked = new CountDownLatch(1)
    hub.awaitingAnchor = _ => parked.countDown()
    val listener = Recording()
    val sub =
      hub
        .observe(thread, capacity = 16, listener, AbandonedWrites.none, TenantScope.Unscoped)
        .claimed(claim)
        .start(lastSeq = 0L)
    // the watch has found the observer not yet anchored, and is about to wait for it
    parked.await(5, TimeUnit.SECONDS) shouldBe true
    log.records = Vector(record(1))
    hub.durable(thread, Vector(record(1)))
    log.watching.await(5, TimeUnit.SECONDS) shouldBe true
    sub.cancel()
    sub.ended(5.seconds) shouldBe true
  }

  "A subscriber that switches to live behind a hand-over's read of missing events" should "have what it is missing read too, before the commit reaches anyone" in {
    val log = WatchedLog(stallAfter = 2L)
    // the watch reads only when `watchGo` lets it, so the poll interval paces nothing here: an observer's
    // watch that checks before `durable` below anchors it is woken by the anchoring
    val hub = EventHub(log, watch = WatchPolicy(pollInterval = scala.concurrent.duration.DurationInt(10).millis))
    // `ahead` takes 1 from the hand-over and 2, committed elsewhere, from its watch: it is past the hub's mark
    val aheadSaw2 = new CountDownLatch(1)
    val aheadSaw4 = new CountDownLatch(1)
    val ahead = Recording { event =>
      countAt(2, aheadSaw2)(event)
      countAt(4, aheadSaw4)(event)
    }
    val a =
      hub
        .observe(thread, capacity = 16, ahead, AbandonedWrites.none, TenantScope.Unscoped)
        .claimed(claim)
        .start(lastSeq = 0L)
    log.records = Vector(record(1))
    hub.durable(thread, Vector(record(1)))
    log.records = log.records :+ record(2, RunId("elsewhere"))
    log.watchGo.release()
    aheadSaw2.await(5, TimeUnit.SECONDS) shouldBe true
    // 3 is committed elsewhere and not watched; the run's 4 is handed over, and `ahead`'s read of 3 sticks
    log.records = log.records :+ record(3, RunId("elsewhere")) :+ record(4)
    val handing = Thread.ofVirtual().start(() => hub.durable(thread, Vector(record(4))))
    log.reading.await(5, TimeUnit.SECONDS) shouldBe true
    // meanwhile `late` subscribes: it replays to the hub's mark, 1, and switches to live there, behind `ahead`
    val lateJoined = new CountDownLatch(1)
    val lateSaw4   = new CountDownLatch(1)
    val late = Recording { event =>
      countAt(4, lateSaw4)(event)
      event match
        case _: StreamEvent.Live => lateJoined.countDown()
        case _                   => ()
    }
    val l = hub
      .subscribe(thread, afterSeq = 0L, capacity = 16, late, AbandonedWrites.none, TenantScope.Unscoped)
      .fold(e => fail(e.message), identity)
    hub.live(thread, StreamEvent.Live(thread.value, run.value, claim, "task", "node", "test.progress", 1, ujson.Num(0)))
    lateJoined.await(5, TimeUnit.SECONDS) shouldBe true
    late.seen.asScala.toVector shouldBe Vector("1", "live")
    log.release.countDown()
    handing.join(java.time.Duration.ofSeconds(5)) shouldBe true
    aheadSaw4.await(5, TimeUnit.SECONDS) shouldBe true
    lateSaw4.await(5, TimeUnit.SECONDS) shouldBe true
    // the hand-over read again, from `late`'s place, before queueing 4 to either
    log.handOverReads.asScala.toVector shouldBe Vector(2L, 1L)
    ahead.seen.asScala.toVector shouldBe Vector("1", "2", "live", "3", "4")
    late.seen.asScala.toVector shouldBe Vector("1", "live", "2", "3", "4")
    a.cancel()
    l.cancel()
    hub.liveCount(thread) shouldBe 0
  }

  private def live(i: Int, token: FencingToken = claim): StreamEvent.Live =
    StreamEvent.Live(thread.value, run.value, token, "task", "node", "test.progress", 1, ujson.Num(i))

  /** A thread-scoped listener recording each event's label, as [[Recording]] does. */
  private def recording(onEvent: StreamEvent => Unit = _ => ()): Recording = Recording(onEvent)

  "A subscription that joins while a hand-over on a thread no run holds is reading missing events" should "not replay the commit being handed over, so a live event sent meanwhile has the same place for it as for every other subscription" in {
    // 1 is handed over, then the run releases the thread: the hub has no mark. 2 was committed
    // elsewhere: `behind` has queued 1 and is missing 2; `level` subscribes from 2. Reads: `level`'s
    // replay, its switch to live, the hand-over's read of 2 for `behind` - the third, stuck in the
    // store - and the fourth, `late`'s replay
    val lateRead = new CountDownLatch(1)
    val log = BlockingLog(
      Vector(record(1), record(2, RunId("elsewhere"))),
      blockOn = 3,
      afterRead = read => if read == 4 then lateRead.countDown()
    )
    val locks       = ThreadLocks()
    val hub         = EventHub(log, locks)
    val behindThree = new CountDownLatch(1)
    val behindSeen  = recording(countAt(3, behindThree))
    val levelJoined = new CountDownLatch(1)
    val levelLive   = new CountDownLatch(2)
    val levelThree  = new CountDownLatch(1)
    val levelSeen = recording { event =>
      countAt(3, levelThree)(event)
      if event.isInstanceOf[StreamEvent.Live] then
        levelJoined.countDown()
        levelLive.countDown()
    }
    val lateThree = new CountDownLatch(1)
    val lateSeen  = recording(countAt(3, lateThree))
    val b = hub
      .observe(thread, capacity = 16, behindSeen, AbandonedWrites.none, TenantScope.Unscoped)
      .claimed(claim)
      .start(lastSeq = 0L)
    locks.withThreadLock(thread)(hub.durable(thread, Vector(record(1))))
    hub.release(thread)
    val l = hub
      .subscribe(thread, afterSeq = 2L, capacity = 16, levelSeen, AbandonedWrites.none, TenantScope.Unscoped)
      .fold(e => fail(e.message), identity)
    // a live event reaches `level` only once it has switched to live
    hub.live(thread, live(0))
    levelJoined.await(5, TimeUnit.SECONDS) shouldBe true
    // the next run's first commit, 3, is in the store; its hand-over holds the thread's commit lock, as
    // every commit's does, while it reads 2 for `behind`
    log.records = log.records :+ record(3)
    val handing = Thread.ofVirtual().start(() => locks.withThreadLock(thread)(hub.durable(thread, Vector(record(3)))))
    log.reading.await(5, TimeUnit.SECONDS) shouldBe true
    // `late` subscribes meanwhile, and its replay reads 1, 2 and 3 from the store
    val la = hub
      .subscribe(thread, afterSeq = 0L, capacity = 16, lateSeen, AbandonedWrites.none, TenantScope.Unscoped)
      .fold(e => fail(e.message), identity)
    lateRead.await(5, TimeUnit.SECONDS) shouldBe true
    // progress sent during the hand-over: `level` has it before any of 3
    hub.live(thread, live(1))
    levelLive.await(5, TimeUnit.SECONDS) shouldBe true
    log.release.countDown()
    handing.join(java.time.Duration.ofSeconds(5)) shouldBe true
    behindThree.await(5, TimeUnit.SECONDS) shouldBe true
    levelThree.await(5, TimeUnit.SECONDS) shouldBe true
    lateThree.await(5, TimeUnit.SECONDS) shouldBe true
    // the progress precedes 3 on every subscription: 3 was not handed over when it was sent
    levelSeen.seen.asScala.toVector shouldBe Vector("live", "live", "3")
    behindSeen.seen.asScala.toVector shouldBe Vector("1", "live", "live", "2", "3")
    lateSeen.seen.asScala.toVector shouldBe Vector("1", "2", "live", "3")
    Seq(b, l, la).foreach(_.cancel())
    hub.liveCount(thread) shouldBe 0
  }

  "A subscription that joins between a commit's write and its hand-over on a thread no run holds" should "leave the commit to the hand-over, behind a live event sent meanwhile, as every other subscription has it" in {
    // 1 is handed over, then the run releases the thread: the hub has no mark. Reads: `level`'s replay
    // and switch to live, then the third, `late`'s replay
    val lateRead = new CountDownLatch(1)
    val log   = BlockingLog(Vector(record(1)), blockOn = 0, afterRead = read => if read == 3 then lateRead.countDown())
    val locks = ThreadLocks()
    val hub   = EventHub(log, locks)
    val levelJoined = new CountDownLatch(1)
    val levelLive   = new CountDownLatch(2)
    val levelTwo    = new CountDownLatch(1)
    val levelSeen = recording { event =>
      countAt(2, levelTwo)(event)
      if event.isInstanceOf[StreamEvent.Live] then
        levelJoined.countDown()
        levelLive.countDown()
    }
    val lateTwo  = new CountDownLatch(1)
    val lateSeen = recording(countAt(2, lateTwo))
    locks.withThreadLock(thread)(hub.durable(thread, Vector(record(1))))
    hub.release(thread)
    val l = hub
      .subscribe(thread, afterSeq = 1L, capacity = 16, levelSeen, AbandonedWrites.none, TenantScope.Unscoped)
      .fold(e => fail(e.message), identity)
    hub.live(thread, live(0))
    levelJoined.await(5, TimeUnit.SECONDS) shouldBe true
    // the next run's commit, 2, is written under the thread's commit lock and not yet handed over
    val written  = new CountDownLatch(1)
    val handOver = new CountDownLatch(1)
    val committing = Thread.ofVirtual().start { () =>
      locks.withThreadLock(thread) {
        log.records = log.records :+ record(2)
        written.countDown()
        handOver.await(5, TimeUnit.SECONDS): Unit
        hub.durable(thread, Vector(record(2)))
      }
    }
    written.await(5, TimeUnit.SECONDS) shouldBe true
    val la = hub
      .subscribe(thread, afterSeq = 0L, capacity = 16, lateSeen, AbandonedWrites.none, TenantScope.Unscoped)
      .fold(e => fail(e.message), identity)
    lateRead.await(5, TimeUnit.SECONDS) shouldBe true // `late`'s replay has read 1 and 2
    hub.live(thread, live(1))
    levelLive.await(5, TimeUnit.SECONDS) shouldBe true
    handOver.countDown()
    committing.join(java.time.Duration.ofSeconds(5)) shouldBe true
    levelTwo.await(5, TimeUnit.SECONDS) shouldBe true
    lateTwo.await(5, TimeUnit.SECONDS) shouldBe true
    levelSeen.seen.asScala.toVector shouldBe Vector("live", "live", "2")
    lateSeen.seen.asScala.toVector shouldBe Vector("1", "live", "2")
    Seq(l, la).foreach(_.cancel())
    hub.liveCount(thread) shouldBe 0
  }

  "A subscription waiting behind a hung commit to learn what has been handed over" should "end promptly when cancelled, and never hold the hub meanwhile" in {
    val log   = BlockingLog(Vector(record(1)), blockOn = 0)
    val locks = ThreadLocks()
    val hub   = EventHub(log, locks)
    locks.withThreadLock(thread)(hub.durable(thread, Vector(record(1))))
    hub.release(thread)
    // a commit on the thread hangs in the store, holding its commit lock, after writing 2
    val written = new CountDownLatch(1)
    val unhang  = new CountDownLatch(1)
    val hung = Thread.ofVirtual().start { () =>
      locks.withThreadLock(thread) {
        log.records = log.records :+ record(2)
        written.countDown()
        // longer than the wait for the cancelled subscription to end, so only the cancel can end it
        unhang.await(60, TimeUnit.SECONDS): Unit
      }
    }
    written.await(5, TimeUnit.SECONDS) shouldBe true
    val waiting = new CountDownLatch(1)
    hub.settling = _ => waiting.countDown()
    val lateSeen = recording()
    val la = hub
      .subscribe(thread, afterSeq = 0L, capacity = 16, lateSeen, AbandonedWrites.none, TenantScope.Unscoped)
      .fold(e => fail(e.message), identity)
    waiting.await(5, TimeUnit.SECONDS) shouldBe true // `late` read 1 and 2, and waits for the commit lock
    // the hub is free: another subscription's live event and a hand-over on another thread go through
    val other = ThreadId("other")
    locks.withThreadLock(other)(hub.durable(other, Vector(record(1).copy(threadId = other.value))))
    hub.live(thread, live(0))
    la.cancel()
    la.ended(5.seconds) shouldBe true
    lateSeen.seen.asScala.toVector shouldBe Vector.empty
    hub.liveCount(thread) shouldBe 0
    unhang.countDown()
    hung.join(java.time.Duration.ofSeconds(5)) shouldBe true
  }

  "An observer joined before its run's claim is handed over" should "not take a previous run's commit handed over first as its place in the log, nor deliver it" in {
    // a lost run's late write, 5, is handed over after the next run's observer joined and before that
    // run's claim, 6, which waited for the commit lock behind it
    val hub         = EventHub(InMemoryCheckpointer())
    val sixth       = new CountDownLatch(1)
    val listener    = recording(countAt(6, sixth))
    val observation = hub.observe(thread, capacity = 16, listener, AbandonedWrites.none, TenantScope.Unscoped)
    hub.durable(thread, Vector(record(5, RunId("lost"), FencingToken(1L))))
    observation.claimed(claim)
    hub.durable(thread, Vector(record(6)))
    val sub = observation.start(lastSeq = 5L)
    sixth.await(5, TimeUnit.SECONDS) shouldBe true
    listener.seen.asScala.toVector shouldBe Vector("6")
    sub.cancel()
    hub.liveCount(thread) shouldBe 0
  }

  it should "not take one by a lost run with the same RunId either, handed over before or after the claim is granted (#1918)" in {
    // a run id may be reused; a token never is. The lost run's writes, 4 and 5, are its own run id's,
    // fenced by its older claim; 4 lands before the next run's claim is granted, 5 after
    val hub         = EventHub(InMemoryCheckpointer())
    val sixth       = new CountDownLatch(1)
    val listener    = recording(countAt(6, sixth))
    val observation = hub.observe(thread, capacity = 16, listener, AbandonedWrites.none, TenantScope.Unscoped)
    hub.durable(thread, Vector(record(4, run, FencingToken(1L))))
    observation.claimed(claim)
    hub.durable(thread, Vector(record(5, run, FencingToken(1L))))
    hub.durable(thread, Vector(record(6), record(7)))
    val sub = observation.start(lastSeq = 5L)
    sixth.await(5, TimeUnit.SECONDS) shouldBe true
    sub.endOfRun(run)
    listener.reached.await(5, TimeUnit.SECONDS) shouldBe true
    listener.seen.asScala.toVector shouldBe Vector("6", "7", "end:r1")
    sub.cancel()
    hub.liveCount(thread) shouldBe 0
  }

  it should "not deliver a live event sent before its run's claim is handed over: no node of its run has run" in {
    val hub         = EventHub(InMemoryCheckpointer())
    val listener    = recording()
    val observation = hub.observe(thread, capacity = 16, listener, AbandonedWrites.none, TenantScope.Unscoped)
    // an earlier run's node, outliving its run, still sends progress under the same run id
    hub.live(thread, live(0))
    observation.claimed(claim)
    hub.live(thread, live(1))
    hub.durable(thread, Vector(record(6)))
    hub.live(thread, live(2))
    val sub = observation.start(lastSeq = 5L)
    sub.endOfRun(run)
    listener.reached.await(5, TimeUnit.SECONDS) shouldBe true
    listener.seen.asScala.toVector shouldBe Vector("6", "live", "end:r1")
    sub.cancel()
    hub.liveCount(thread) shouldBe 0
  }

  it should "not deliver a live event of an older claim sent after its run's claim, though it has its RunId, but a newer claim's (#1918)" in {
    // a lost run's node ignores the interrupt and outlives its run: it sends progress under the same run
    // id after the next run's claim, 6, was handed over. A later run's progress, of a newer claim, is
    // delivered, as its durable events are
    val hub         = EventHub(InMemoryCheckpointer())
    val listener    = recording()
    val observation = hub.observe(thread, capacity = 16, listener, AbandonedWrites.none, TenantScope.Unscoped)
    observation.claimed(claim)
    hub.durable(thread, Vector(record(6)))
    hub.live(thread, live(0, FencingToken(1L)))
    hub.live(thread, live(1))
    hub.durable(thread, Vector(record(7)))
    hub.live(thread, live(2, FencingToken(1L)))
    hub.live(thread, live(3, FencingToken(3L)))
    val sub = observation.start(lastSeq = 5L)
    sub.endOfRun(run)
    listener.reached.await(5, TimeUnit.SECONDS) shouldBe true
    listener.seen.asScala.toVector shouldBe Vector("6", "live", "7", "live", "end:r1")
    sub.cancel()
    hub.liveCount(thread) shouldBe 0
  }

  "A live event whose run no longer holds its claim" should "be offered to no subscription: a lost run's node, outliving its run (#1918)" in {
    val hub    = EventHub(InMemoryCheckpointer())
    val joined = new CountDownLatch(1)
    val first  = new CountDownLatch(1)
    val listener = recording {
      case _: StreamEvent.Live => joined.countDown()
      case event               => countAt(1, first)(event)
    }
    val sub = hub
      .subscribe(thread, afterSeq = 0L, capacity = 16, listener, AbandonedWrites.none, TenantScope.Unscoped)
      .fold(e => fail(e.message), identity)
    hub.live(thread, live(0))
    // delivered only once the subscription has switched to live, so the commit below is handed to it
    joined.await(5, TimeUnit.SECONDS) shouldBe true
    hub.live(thread, live(1, FencingToken(1L)), () => false)
    hub.durable(thread, Vector(record(1)))
    first.await(5, TimeUnit.SECONDS) shouldBe true
    listener.seen.asScala.toVector shouldBe Vector("live", "1")
    sub.cancel()
    hub.liveCount(thread) shouldBe 0
  }

  "A subscription with no mark that learns of a hand-over while it waits for the commit lock" should "still count what else it read as history, so an event committed elsewhere after that commit is replayed" in {
    // 1 is handed over, then the run releases the thread: the hub has no mark. A commit holds the commit
    // lock after writing 2 - a lost run's late write, applied before another runtime committed 3 - and
    // the store watch is disabled, so only a replay can deliver 3
    val log   = BlockingLog(Vector(record(1)), blockOn = 0)
    val locks = ThreadLocks()
    val hub   = EventHub(log, locks, WatchPolicy.disabled)
    locks.withThreadLock(thread)(hub.durable(thread, Vector(record(1))))
    hub.release(thread)
    val written  = new CountDownLatch(1)
    val handOver = new CountDownLatch(1)
    val committing = Thread.ofVirtual().start { () =>
      locks.withThreadLock(thread) {
        log.records = log.records :+ record(2, RunId("lost"))
        written.countDown()
        handOver.await(5, TimeUnit.SECONDS): Unit
        hub.durable(thread, Vector(record(2, RunId("lost"))))
      }
    }
    written.await(5, TimeUnit.SECONDS) shouldBe true
    log.records = log.records :+ record(3, RunId("elsewhere"))
    val waiting = new CountDownLatch(1)
    hub.settling = _ => waiting.countDown()
    val lateThree = new CountDownLatch(1)
    val lateSeen  = recording(countAt(3, lateThree))
    val la = hub
      .subscribe(thread, afterSeq = 0L, capacity = 16, lateSeen, AbandonedWrites.none, TenantScope.Unscoped)
      .fold(e => fail(e.message), identity)
    waiting.await(5, TimeUnit.SECONDS) shouldBe true // `late` read 1, 2 and 3, and waits for the commit lock
    // 2 is handed over, and noted by `late`, while it waits
    handOver.countDown()
    committing.join(java.time.Duration.ofSeconds(5)) shouldBe true
    lateThree.await(5, TimeUnit.SECONDS) shouldBe true
    lateSeen.seen.asScala.toVector shouldBe Vector("1", "2", "3")
    la.cancel()
    hub.liveCount(thread) shouldBe 0
  }

  /** Counts `latch` down once the durable event `seq` has been recorded. */
  private def countAt(seq: Long, latch: CountDownLatch): StreamEvent => Unit = {
    case StreamEvent.Durable(r) if r.seq == seq => latch.countDown()
    case _                                      => ()
  }
