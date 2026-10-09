package org.llm4s.agent.testkit

import org.llm4s.agent.graph.*
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.{ Assertion, EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant }
import scala.annotation.unused
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, CyclicBarrier, LinkedBlockingQueue, TimeUnit }
import scala.concurrent.duration.*

/**
 * The contract every [[org.llm4s.agent.graph.Checkpointer]] must meet, as a ScalaTest suite. Mix it
 * into a spec, implement [[newCheckpointer]], and every case below runs against your store:
 *
 * {{{
 * class SqliteCheckpointerContractSpec extends AnyFlatSpec with CheckpointerContract:
 *   protected def newCheckpointer(clock: java.time.Clock): Checkpointer =
 *     SqliteCheckpointer.open(freshDatabaseFile(), clock)
 * }}}
 *
 * The cases are the ones the `Checkpointer` Scaladoc states, in two groups:
 *
 *  - '''The store''', called directly: commits applied atomically or not at all; a new checkpoint
 *    accepted only over the latest ([[GraphError.CheckpointConflict]]) and pending writes only for
 *    it ([[GraphError.InvalidCommit]]); events numbered contiguously in commit order and never
 *    reused, also after a refused commit, compaction or `deleteThread`; compaction and
 *    [[GraphError.ReplayUnavailable]]; `deleteThread`; checkpoints, pending writes and events read
 *    back as written; the checkpoint history - every superseded checkpoint kept, newest first, paged
 *    and read by id, ids never repeated, a blocked turn retracted (`Commit.retractTurn`), and pruning
 *    by age, by count and by event count, never the latest checkpoint; and claims and fencing - one
 *    live claim per thread, tokens strictly
 *    increasing, takeover once a claim has expired by the store's clock, renewal and release by the
 *    current token only, and every commit refused with [[GraphError.StaleClaim]] unless it carries
 *    the current token - including from many threads at once.
 *  - '''Two runtimes over one store''': a second [[GraphRuntime]] is refused a thread whose run is
 *    live in the first ([[GraphError.ThreadBusy]] naming the holder); a run that keeps renewing is
 *    not taken over; once a claim expires, the second runtime recovers the thread without re-running
 *    the first run's completed tasks, and the first run's later commits are refused, so it fails
 *    with [[GraphError.CheckpointWriteFailed]] and leaves nothing in the thread; and of several
 *    runtimes recovering one thread at once, exactly one is admitted; `updateState` and `fork` from a
 *    second runtime are refused while a run is live in the first, and fenced by their own claim; and
 *    a run a node blocks leaves nothing of its turn in the history.
 *
 * Claim expiry is judged by the store's clock. By default the store under test must use the `clock`
 * it is given for that (and may use it for nothing else), and the suite moves a [[ManualClock]]; it
 * never waits for real time to pass a claim's `ttl`. A store that judges expiry by a clock it cannot
 * be handed - a database server's `now()` - overrides [[advanceStoreClock]] to make claims expire
 * as if that clock had moved (for example by moving every stored expiry back), and sets
 * [[exactExpiry]] to `false`, so that the cases check expiry by its effect only.
 *
 * A durable store also overrides [[reopen]], so that the cases about claims and tokens surviving a
 * restart - a process that closes the store and opens the same storage again - run against storage
 * that was really closed. The default reopens nothing and hands the same instance back.
 *
 * Each case gets a new store from [[newCheckpointer]] and uses its own thread ids; the store need
 * not be empty of other threads.
 */
trait CheckpointerContract extends AnyFlatSpecLike with Matchers with EitherValues with OptionValues:

  /**
   * A store for one test case, whose claims expire by `clock`. It may share a backing database with
   * other cases: each case uses thread ids of its own.
   */
  protected def newCheckpointer(clock: Clock): Checkpointer

  /** How long a case waits for a run or a background step before it fails rather than hangs. */
  protected def contractWait: FiniteDuration = 10.seconds

  /**
   * Closes `store` and opens the storage behind it again, as a restarted process would; the store
   * returned must judge claims by the same `clock`. The default hands `store` back unchanged, which
   * is right for a store that keeps nothing beyond its instance, such as `InMemoryCheckpointer`.
   */
  protected def reopen(store: Checkpointer, @unused clock: ManualClock): Checkpointer = store

  /**
   * Makes the store's claims expire as if its clock had moved forward by `by`. The default moves
   * `clock`, the clock [[newCheckpointer]] was given; a store that judges expiry by a clock of its
   * own, such as a database server's, overrides this.
   */
  protected def advanceStoreClock(@unused store: Checkpointer, clock: ManualClock, by: FiniteDuration): Unit =
    clock.advance(by)

  /**
   * Whether a claim's `expiresAt` is exactly the injected clock's time plus its `ttl`, as it is when
   * the store reads `clock`. A store that overrides [[advanceStoreClock]] because it reads a clock of
   * its own sets this to `false`, and the cases then check only that `expiresAt` moves forward.
   */
  protected def exactExpiry: Boolean = true

  private val start     = Instant.parse("2026-10-09T12:00:00Z")
  private val ttl       = 30.seconds
  private val threadIds = new AtomicInteger()
  private def newThread(): ThreadId = ThreadId(
    s"contract-${java.util.UUID.randomUUID()}-${threadIds.incrementAndGet()}"
  )

  final private class Store:
    val clock: ManualClock                      = ManualClock(start)
    @volatile private var current: Checkpointer = newCheckpointer(clock)
    val thread: ThreadId                        = newThread()

    /** The store; another instance over the same storage after [[reopened]]. */
    def store: Checkpointer = current

    /** Closes the store and opens its storage again; see [[CheckpointerContract.reopen]]. */
    def reopened(): Checkpointer =
      current = reopen(current, clock)
      current

    /** Moves the store's clock; see [[CheckpointerContract.advanceStoreClock]]. */
    def advance(by: FiniteDuration): Unit = advanceStoreClock(current, clock, by)

    /** `actual` is `start` plus `offset`, checked as far as [[exactExpiry]] allows. */
    def expiresAt(actual: Instant, offset: FiniteDuration, previous: Option[Instant] = None): Assertion =
      if exactExpiry then actual shouldBe start.plusNanos(offset.toNanos)
      else previous.fold(succeed)(before => actual.isAfter(before) shouldBe true)

    def claim(holder: String, on: ThreadId = thread): Result[RunClaim] =
      store.claim(on, ClaimRequest(RunId(holder), ttl))

    def commit(
      token: FencingToken,
      checkpoint: Option[Checkpoint] = None,
      writes: Vector[PendingWrite] = Vector.empty,
      events: Vector[EventDraft] = Vector.empty,
      on: ThreadId = thread
    ): Result[Vector[EventRecord]] = store.commit(on, Commit(token, checkpoint, writes, events))

    def checkpoint(
      id: String,
      parent: Option[String],
      token: Option[FencingToken] = None,
      on: ThreadId = thread,
      status: CheckpointStatus = CheckpointStatus.Running,
      createdAt: Instant = start
    ): Checkpoint =
      Checkpoint(
        Checkpoint.CurrentFormat,
        id,
        parent,
        on.value,
        "run",
        status,
        createdAt,
        GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false),
        None,
        token
      )

    def event(name: String, checkpointId: Option[String] = None, at: Instant = start): EventDraft =
      EventDraft("run", checkpointId, None, None, at, RunEvent.Custom(name, 1, ujson.Obj("name" -> name)))

    /** Commits `ids` in order as a chain of new checkpoints, each with one event recorded against it. */
    def chain(
      token: FencingToken,
      ids: Vector[String],
      status: String => CheckpointStatus = _ => CheckpointStatus.Running
    ): Unit =
      ids.foldLeft(latestId()) { (parent, id) =>
        commit(token, Some(checkpoint(id, parent, status = status(id))), events = Vector(event(id, Some(id)))).value
        Some(id)
      }: Unit

    def historyIds(on: ThreadId = thread): Vector[String] = store.history(on, None, 1000).value.map(_.id)

    def write(checkpointId: String, taskId: String): PendingWrite =
      PendingWrite(checkpointId, taskId, "n", Vector.empty, Vector.empty)

    def seqs(on: ThreadId = thread): Vector[Long] = store.eventsAfter(on, 0L, 1000).value.map(_.seq)

    def latestId(on: ThreadId = thread): Option[String] = store.latest(on).value.map(_.checkpoint.id)

  extension [A](result: Result[A])
    /**
     * The error a refused call returned; fails the case if it succeeded. The failure carries only
     * text: a value holding `ujson` (which is not serializable) would break a forked test JVM.
     */
    private def refused: org.llm4s.error.LLMError = result match
      case Left(error)  => error
      case Right(value) => fail(s"expected a refusal, but the call succeeded with $value")

  /** `actual == expected`, failing with text only, for values that hold `ujson` (see [[refused]]). */
  private def same[A](actual: A, expected: A): Assertion =
    if actual == expected then succeed else fail(s"$actual was not equal to $expected")

  behavior.of("A Checkpointer")

  // ---- commits ----

  it should "apply a commit's checkpoint, pending writes and events together, numbering events from 1" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.store.latest(s.thread).value shouldBe None
    s.commit(token, Some(s.checkpoint("c1", None)), Vector(s.write("c1", "0.0")), Vector(s.event("a"), s.event("b")))
      .value
      .map(_.seq) shouldBe Vector(1L, 2L)
    s.commit(token, None, Vector(s.write("c1", "0.1")), Vector(s.event("c"))).value.map(_.seq) shouldBe Vector(3L)
    val stored = s.store.latest(s.thread).value.value
    stored.checkpoint.id shouldBe "c1"
    stored.pendingWrites.map(_.taskId) shouldBe Vector("0.0", "0.1")
    s.seqs() shouldBe Vector(1L, 2L, 3L)
  }

  it should "refuse a checkpoint whose parent is not the latest, applying nothing and numbering nothing" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(s.checkpoint("c1", None)), events = Vector(s.event("a"))).value
    s.commit(token, Some(s.checkpoint("c2", Some("other"))), Vector(s.write("c2", "0.0")), Vector(s.event("lost")))
      .refused shouldBe GraphError.CheckpointConflict(s.thread.value, Some("other"), Some("c1"))
    s.commit(token, Some(s.checkpoint("c2", None))).refused shouldBe
      GraphError.CheckpointConflict(s.thread.value, None, Some("c1"))
    s.latestId() shouldBe Some("c1")
    s.store.latest(s.thread).value.value.pendingWrites shouldBe empty
    s.commit(token, events = Vector(s.event("b"))).value.map(_.seq) shouldBe Vector(2L)
  }

  it should "refuse pending writes that do not name the resulting latest checkpoint, applying nothing" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, None, Vector(s.write("c1", "0.0"))).refused shouldBe a[GraphError.InvalidCommit]
    s.commit(token, Some(s.checkpoint("c1", None))).value
    s.commit(token, None, Vector(s.write("c0", "0.0")), Vector(s.event("x"))).refused shouldBe
      a[GraphError.InvalidCommit]
    // writes in a commit that brings a new checkpoint must name the new one, not its parent
    s.commit(token, Some(s.checkpoint("c2", Some("c1"))), Vector(s.write("c1", "0.0")), Vector(s.event("y")))
      .refused shouldBe a[GraphError.InvalidCommit]
    s.latestId() shouldBe Some("c1")
    s.seqs() shouldBe empty
  }

  it should "drop the old checkpoint's pending writes when a new checkpoint lands" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(s.checkpoint("c1", None)), Vector(s.write("c1", "0.0"))).value
    s.commit(token, Some(s.checkpoint("c2", Some("c1"))), Vector(s.write("c2", "1.0"))).value
    s.store.latest(s.thread).value.map(st => st.checkpoint.id -> st.pendingWrites.map(_.taskId)) shouldBe Some(
      "c2" -> Vector("1.0")
    )
  }

  it should "read back checkpoints, pending writes and events exactly as they were written" in {
    val s     = Store()
    val token = s.claim("run").value.token
    val checkpoint = Checkpoint(
      Checkpoint.CurrentFormat,
      "run-1/1",
      None,
      s.thread.value,
      "run-1",
      CheckpointStatus.Suspended,
      Instant.parse("2026-10-09T12:00:00.123456789Z"),
      GraphSnapshot(
        "g",
        "v1",
        "fingerprint",
        3,
        Map("log" -> VersionedJson(2, ujson.Arr("a", ujson.Obj("nested" -> 1.5)))),
        Vector(
          GraphSnapshot.PendingTask("3.0", "worker", VersionedJson(1, ujson.Str("in")), Some("j"), None, None, None)
        ),
        Vector.empty,
        Vector.empty,
        Vector.empty,
        true
      ),
      Some("tenant"),
      Some(token)
    )
    val write = PendingWrite(
      "run-1/1",
      "3.0",
      "worker",
      Vector(EncodedOperation.Update("log", VersionedJson(2, ujson.Str("x"))), EncodedOperation.Remove("count")),
      Vector(EncodedRoute.Goto("next"), EncodedRoute.FanOut("j", "worker", Vector(VersionedJson(1, ujson.Null)))),
      Some(EncodedSuspension("approve", VersionedJson(1, ujson.Obj("q" -> "ok?"))))
    )
    val draft = EventDraft(
      "run-1",
      Some("run-1/1"),
      Some("3.0"),
      Some("worker"),
      Instant.parse("2026-10-09T12:00:01.5Z"),
      RunEvent.Custom("contract.payload", 2, ujson.Obj("text" -> "ünïcode", "n" -> 42))
    )
    val records = s.commit(token, Some(checkpoint), Vector(write), Vector(draft)).value
    val expected = EventRecord(
      s.thread.value,
      1L,
      draft.runId,
      draft.checkpointId,
      draft.taskId,
      draft.nodeId,
      draft.timestamp,
      draft.event
    )
    same(records, Vector(expected))
    same(s.store.eventsAfter(s.thread, 0L, 10).value, Vector(expected))
    same(s.store.latest(s.thread).value.value, StoredCheckpoint(checkpoint, Vector(write)))
  }

  // ---- the event log ----

  it should "page events after a sequence, ascending, at most `limit` at a time" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(s.checkpoint("c1", None)), events = (1 to 5).map(i => s.event(s"e$i")).toVector).value
    s.store.eventsAfter(s.thread, 0L, 2).value.map(_.seq) shouldBe Vector(1L, 2L)
    s.store.eventsAfter(s.thread, 2L, 2).value.map(_.seq) shouldBe Vector(3L, 4L)
    s.store.eventsAfter(s.thread, 4L, 2).value.map(_.seq) shouldBe Vector(5L)
    s.store.eventsAfter(s.thread, 5L, 2).value shouldBe empty
    s.store.eventsAfter(newThread(), 0L, 2).value shouldBe empty
  }

  it should "compact behind a floor that never moves back, reporting it to replay from before it" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(s.checkpoint("c1", None)), events = (1 to 5).map(i => s.event(s"e$i")).toVector).value
    s.store.compactEvents(s.thread, 4L).value shouldBe (())
    s.store.eventsAfter(s.thread, 0L, 10).refused shouldBe GraphError.ReplayUnavailable(s.thread.value, 4L)
    s.store.eventsAfter(s.thread, 2L, 10).refused shouldBe GraphError.ReplayUnavailable(s.thread.value, 4L)
    s.store.eventsAfter(s.thread, 3L, 10).value.map(_.seq) shouldBe Vector(4L, 5L)
    s.store.compactEvents(s.thread, 2L).value shouldBe (())
    s.store.eventsAfter(s.thread, 3L, 10).value.map(_.seq) shouldBe Vector(4L, 5L)
    s.store.compactEvents(s.thread, 100L).value shouldBe (())
    s.store.eventsAfter(s.thread, 5L, 10).value shouldBe empty
    s.commit(token, events = Vector(s.event("e6"))).value.map(_.seq) shouldBe Vector(6L)
    s.latestId() shouldBe Some("c1")
  }

  it should "number events contiguously and uniquely when one claim commits from many threads at once" in {
    val s       = Store()
    val token   = s.claim("run").value.token
    val writers = 8
    val each    = 25
    s.commit(token, Some(s.checkpoint("c1", None))).value
    val barrier = new CyclicBarrier(writers)
    val results = new LinkedBlockingQueue[Result[Vector[EventRecord]]]()
    val threads = (1 to writers).map { w =>
      Thread.ofVirtual().start { () =>
        barrier.await(contractWait.toMillis, TimeUnit.MILLISECONDS): Unit
        (1 to each).foreach(i => results.put(s.commit(token, events = Vector(s.event(s"w$w-$i"), s.event(s"w$w-$i'")))))
      }
    }
    threads.foreach(_.join(contractWait.toMillis))
    val committed = Iterator.continually(results.poll()).takeWhile(_ != null).toVector
    committed should have size (writers * each).toLong
    val seqs = committed.flatMap(_.value.map(_.seq))
    seqs.sorted shouldBe (1L to (writers * each * 2).toLong).toVector
    // one commit's events are numbered consecutively, never interleaved with another's
    committed.foreach { r =>
      val numbered = r.value.map(_.seq)
      numbered.last - numbered.head shouldBe 1L
    }
    s.seqs() shouldBe (1L to (writers * each * 2).toLong).toVector
  }

  // ---- deleting a thread ----

  it should "delete a thread's checkpoint, writes, events and claim, leave others alone, and never reuse a token" in {
    val s     = Store()
    val other = newThread()
    val token = s.claim("run").value.token
    s.commit(token, Some(s.checkpoint("c1", None)), Vector(s.write("c1", "0.0")), Vector(s.event("a"))).value
    val otherToken = s.claim("other", other).value.token
    s.commit(otherToken, Some(s.checkpoint("o1", None, on = other)), events = Vector(s.event("x")), on = other).value

    s.store.deleteThread(s.thread).value shouldBe (())

    s.store.latest(s.thread).value shouldBe None
    s.seqs() shouldBe empty
    s.latestId(other) shouldBe Some("o1")
    s.seqs(other) shouldBe Vector(1L)
    s.commit(token, events = Vector(s.event("late"))).refused shouldBe a[GraphError.StaleClaim]
    val again = s.claim("again").value
    again.token.value should be > token.value
    s.commit(again.token, Some(s.checkpoint("c1", None)), events = Vector(s.event("again"))).value.map(_.seq) shouldBe
      Vector(1L)
    s.store.deleteThread(newThread()).value shouldBe (())
  }

  // ---- claims ----

  it should "grant a claim on a new thread, then refuse every other claim while it is live, naming its holder" in {
    val s       = Store()
    val granted = s.claim("first").value
    granted.threadId shouldBe s.thread
    granted.holder shouldBe RunId("first")
    s.expiresAt(granted.expiresAt, 30.seconds)
    s.claim("second").refused shouldBe GraphError.ThreadBusy(s.thread.value, None, Some("first"))
    // the same holder is refused too: a claim is not re-entrant
    s.claim("first").refused shouldBe GraphError.ThreadBusy(s.thread.value, None, Some("first"))
    s.advance(ttl - 1.milli)
    s.claim("second").refused shouldBe a[GraphError.ThreadBusy]
    s.commit(granted.token, Some(s.checkpoint("c1", None))).value
    s.claim("second").refused shouldBe GraphError.ThreadBusy(s.thread.value, Some("c1"), Some("first"))
    s.claim("third", newThread()).value.holder shouldBe RunId("third")
  }

  it should "let an expired claim be taken over with a greater token, and fence off its old holder" in {
    val s     = Store()
    val first = s.claim("first").value
    s.commit(first.token, Some(s.checkpoint("c1", None)), events = Vector(s.event("a"))).value
    s.advance(ttl)
    val taken = s.claim("second").value
    taken.holder shouldBe RunId("second")
    taken.token.value should be > first.token.value
    s.expiresAt(taken.expiresAt, 60.seconds, Some(first.expiresAt))

    s.commit(
      first.token,
      Some(s.checkpoint("stale", Some("c1"))),
      Vector(s.write("stale", "1.0")),
      Vector(s.event("x"))
    ).refused shouldBe GraphError.StaleClaim(s.thread.value, first.token.value, Some(taken.token.value))
    s.commit(first.token, None, Vector(s.write("c1", "1.0"))).refused shouldBe a[GraphError.StaleClaim]
    s.commit(first.token, events = Vector(s.event("y"))).refused shouldBe a[GraphError.StaleClaim]
    s.store.renew(s.thread, first.token, ttl).refused shouldBe a[GraphError.StaleClaim]
    s.store.release(s.thread, first.token).value shouldBe (())
    s.claim("third").refused shouldBe GraphError.ThreadBusy(s.thread.value, Some("c1"), Some("second"))

    // nothing of the stale holder landed, and no sequence number was spent on it
    s.latestId() shouldBe Some("c1")
    s.store.latest(s.thread).value.value.pendingWrites shouldBe empty
    s.commit(taken.token, events = Vector(s.event("b"))).value.map(_.seq) shouldBe Vector(2L)
  }

  it should "check the token before the thread version, so a stale writer learns nothing from a conflict" in {
    val s     = Store()
    val first = s.claim("first").value
    s.commit(first.token, Some(s.checkpoint("c1", None))).value
    s.advance(ttl)
    val taken = s.claim("second").value
    s.commit(taken.token, Some(s.checkpoint("c2", Some("c1")))).value
    s.commit(first.token, Some(s.checkpoint("x", Some("c1")))).refused shouldBe a[GraphError.StaleClaim]
    s.commit(first.token, Some(s.checkpoint("x", Some("c2")))).refused shouldBe a[GraphError.StaleClaim]
  }

  it should "refuse a commit with no claim, or with a token never issued, applying nothing" in {
    val s = Store()
    s.commit(FencingToken(1L), Some(s.checkpoint("c1", None)), events = Vector(s.event("a"))).refused shouldBe
      a[GraphError.StaleClaim]
    val granted = s.claim("run").value
    s.commit(FencingToken(granted.token.value + 1000), Some(s.checkpoint("c1", None))).refused shouldBe
      GraphError.StaleClaim(s.thread.value, granted.token.value + 1000, Some(granted.token.value))
    s.store.latest(s.thread).value shouldBe None
    s.seqs() shouldBe empty
  }

  it should "let the holder commit and renew after expiry for as long as nobody took the claim over" in {
    val s     = Store()
    val first = s.claim("first").value
    s.advance(ttl + 5.seconds)
    s.commit(first.token, Some(s.checkpoint("c1", None)), events = Vector(s.event("late"))).value.map(_.seq) shouldBe
      Vector(1L)
    val renewed = s.store.renew(s.thread, first.token, ttl).value
    renewed.token shouldBe first.token
    renewed.holder shouldBe RunId("first")
    s.expiresAt(renewed.expiresAt, 65.seconds, Some(first.expiresAt))
    s.claim("second").refused shouldBe a[GraphError.ThreadBusy]
  }

  it should "extend a claim on renewal, by the store's clock" in {
    val s     = Store()
    val first = s.claim("first").value
    s.advance(20.seconds)
    s.expiresAt(s.store.renew(s.thread, first.token, ttl).value.expiresAt, 50.seconds, Some(first.expiresAt))
    s.advance(20.seconds) // 40s after the claim, 20s after the renewal
    s.claim("second").refused shouldBe GraphError.ThreadBusy(s.thread.value, None, Some("first"))
    s.advance(10.seconds)
    s.claim("second").value.token.value should be > first.token.value
  }

  it should "free a released thread at once, refuse the released token, and ignore a stale release" in {
    val s     = Store()
    val first = s.claim("first").value
    s.commit(first.token, Some(s.checkpoint("c1", None))).value
    s.store.release(s.thread, first.token).value shouldBe (())
    s.commit(first.token, events = Vector(s.event("late"))).refused shouldBe
      GraphError.StaleClaim(s.thread.value, first.token.value, None)
    s.store.renew(s.thread, first.token, ttl).refused shouldBe
      GraphError.StaleClaim(s.thread.value, first.token.value, None)
    s.store.release(s.thread, first.token).value shouldBe (())
    val second = s.claim("second").value
    second.token.value should be > first.token.value
    // a release with the old token must not free the new holder's claim
    s.store.release(s.thread, first.token).value shouldBe (())
    s.claim("third").refused shouldBe GraphError.ThreadBusy(s.thread.value, Some("c1"), Some("second"))
    s.latestId() shouldBe Some("c1")
    s.seqs() shouldBe empty
  }

  it should "issue strictly increasing tokens per thread across releases, takeovers and deletes" in {
    val s = Store()
    val tokens = (1 to 6).map { i =>
      val granted = s.claim(s"run-$i").value
      i % 3 match
        case 0 => s.store.deleteThread(s.thread).value
        case 1 => s.store.release(s.thread, granted.token).value
        case _ => s.advance(ttl)
      granted.token.value
    }
    tokens shouldBe tokens.sorted
    tokens.distinct should have size tokens.size.toLong
  }

  it should "grant exactly one of many concurrent claims on a thread" in {
    val s        = Store()
    val claimers = 16
    val barrier  = new CyclicBarrier(claimers)
    val results  = new LinkedBlockingQueue[(Int, Result[RunClaim])]()
    val threads = (1 to claimers).map { i =>
      Thread.ofVirtual().start { () =>
        barrier.await(contractWait.toMillis, TimeUnit.MILLISECONDS): Unit
        results.put(i -> s.claim(s"run-$i"))
      }
    }
    threads.foreach(_.join(contractWait.toMillis))
    val all     = Iterator.continually(results.poll()).takeWhile(_ != null).toVector
    val granted = all.collect { case (i, Right(claim)) => i -> claim }
    all should have size claimers.toLong
    granted should have size 1
    val (winner, claim) = granted.head
    claim.holder shouldBe RunId(s"run-$winner")
    all.collect { case (_, Left(error)) => error }.foreach {
      _ shouldBe GraphError.ThreadBusy(s.thread.value, None, Some(s"run-$winner"))
    }
  }

  it should "grant exactly one of many concurrent takeovers of an expired claim" in {
    val s        = Store()
    val first    = s.claim("first").value
    val claimers = 16
    s.commit(first.token, Some(s.checkpoint("c1", None))).value
    s.advance(ttl)
    val barrier = new CyclicBarrier(claimers)
    val results = new LinkedBlockingQueue[(Int, Result[RunClaim])]()
    val threads = (1 to claimers).map { i =>
      Thread.ofVirtual().start { () =>
        barrier.await(contractWait.toMillis, TimeUnit.MILLISECONDS): Unit
        results.put(i -> s.claim(s"run-$i"))
      }
    }
    threads.foreach(_.join(contractWait.toMillis))
    val all     = Iterator.continually(results.poll()).takeWhile(_ != null).toVector
    val granted = all.collect { case (i, Right(claim)) => i -> claim }
    all should have size claimers.toLong
    granted should have size 1
    val (winner, claim) = granted.head
    claim.holder shouldBe RunId(s"run-$winner")
    claim.token.value should be > first.token.value
    all.collect { case (_, Left(error)) => error }.foreach {
      _ shouldBe GraphError.ThreadBusy(s.thread.value, Some("c1"), Some(s"run-$winner"))
    }
    // the old holder is fenced off, and the winner alone can commit
    s.commit(first.token, events = Vector(s.event("stale"))).refused shouldBe
      GraphError.StaleClaim(s.thread.value, first.token.value, Some(claim.token.value))
    s.commit(claim.token, events = Vector(s.event("won"))).value.map(_.seq) shouldBe Vector(1L)
  }

  // ---- reopening the store ----

  it should "keep a live claim, its holder and its token across a close and reopen" in {
    val s     = Store()
    val first = s.claim("first").value
    s.commit(first.token, Some(s.checkpoint("c1", None)), events = Vector(s.event("a"))).value
    s.reopened()
    s.claim("second").refused shouldBe GraphError.ThreadBusy(s.thread.value, Some("c1"), Some("first"))
    s.commit(first.token, events = Vector(s.event("b"))).value.map(_.seq) shouldBe Vector(2L)
    s.store.renew(s.thread, first.token, ttl).value.token shouldBe first.token
    s.store.release(s.thread, first.token).value shouldBe (())
    s.reopened()
    val second = s.claim("second").value
    second.token.value should be > first.token.value
    s.seqs() shouldBe Vector(1L, 2L)
  }

  it should "keep fencing off a stale holder, and keep tokens increasing, across a close and reopen" in {
    val s     = Store()
    val first = s.claim("first").value
    s.commit(first.token, Some(s.checkpoint("c1", None))).value
    s.advance(ttl)
    val taken = s.claim("second").value
    s.reopened()
    s.commit(first.token, Some(s.checkpoint("stale", Some("c1"))), events = Vector(s.event("x"))).refused shouldBe
      GraphError.StaleClaim(s.thread.value, first.token.value, Some(taken.token.value))
    s.store.renew(s.thread, first.token, ttl).refused shouldBe a[GraphError.StaleClaim]
    s.store.release(s.thread, first.token).value shouldBe (())
    s.claim("third").refused shouldBe GraphError.ThreadBusy(s.thread.value, Some("c1"), Some("second"))
    s.commit(taken.token, events = Vector(s.event("ok"))).value.map(_.seq) shouldBe Vector(1L)

    // a token issued after a reopen is greater than every token before it, on this thread and after a delete
    s.store.deleteThread(s.thread).value shouldBe (())
    s.reopened()
    val again = s.claim("again").value
    again.token.value should be > taken.token.value
    s.claim("fresh", newThread()).value.token.value should be > again.token.value
  }

  // ---- compaction and deletion together ----

  it should "start a deleted thread's replay floor afresh, so a new thread of the same id replays from 1" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(s.checkpoint("c1", None)), events = (1 to 5).map(i => s.event(s"e$i")).toVector).value
    s.store.compactEvents(s.thread, 4L).value shouldBe (())
    s.store.eventsAfter(s.thread, 0L, 10).refused shouldBe GraphError.ReplayUnavailable(s.thread.value, 4L)

    s.store.deleteThread(s.thread).value shouldBe (())
    s.store.eventsAfter(s.thread, 0L, 10).value shouldBe empty
    val again = s.claim("again").value.token
    s.commit(again, Some(s.checkpoint("n1", None)), events = Vector(s.event("n1"), s.event("n2")))
      .value
      .map(
        _.seq
      ) shouldBe Vector(1L, 2L)
    s.store.eventsAfter(s.thread, 0L, 10).value.map(_.seq) shouldBe Vector(1L, 2L)
    // and compaction on the new thread starts from its own floor
    s.store.compactEvents(s.thread, 2L).value shouldBe (())
    s.store.eventsAfter(s.thread, 0L, 10).refused shouldBe GraphError.ReplayUnavailable(s.thread.value, 2L)
    s.store.eventsAfter(s.thread, 1L, 10).value.map(_.seq) shouldBe Vector(2L)
  }

  // ---- the checkpoint history ----

  it should "keep every checkpoint a commit superseded, newest first, read back exactly, with writes for the latest only" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.store.history(s.thread, None, 10).value shouldBe empty
    val c1 = s.checkpoint("c1", None, Some(token))
    val c2 = s.checkpoint("c2", Some("c1"), Some(token), status = CheckpointStatus.Suspended)
    val c3 = s.checkpoint("c3", Some("c2"), Some(token), status = CheckpointStatus.Completed)
    s.commit(token, Some(c1), Vector(s.write("c1", "0.0"))).value
    s.commit(token, Some(c2), Vector(s.write("c2", "1.0"))).value
    s.commit(token, Some(c3)).value
    same(s.store.history(s.thread, None, 10).value, Vector(c3, c2, c1))
    same(s.store.checkpoint(s.thread, "c2").value, Some(c2))
    same(s.store.checkpoint(s.thread, "c3").value, Some(c3))
    s.store.checkpoint(s.thread, "nope").value shouldBe None
    s.store.checkpoint(newThread(), "c1").value shouldBe None
    // the latest's pending writes only: superseded checkpoints keep none
    same(s.store.latest(s.thread).value.value, StoredCheckpoint(c3, Vector.empty))
  }

  it should "page the history after a checkpoint, at most `limit` at a time, and refuse one it does not hold" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.chain(token, (1 to 5).map(i => s"c$i").toVector)
    s.store.history(s.thread, None, 2).value.map(_.id) shouldBe Vector("c5", "c4")
    s.store.history(s.thread, Some("c4"), 2).value.map(_.id) shouldBe Vector("c3", "c2")
    s.store.history(s.thread, Some("c2"), 2).value.map(_.id) shouldBe Vector("c1")
    s.store.history(s.thread, Some("c1"), 2).value shouldBe empty
    s.store.history(s.thread, Some("nope"), 2).refused shouldBe GraphError.CheckpointNotFound(s.thread.value, "nope")
    s.store.history(newThread(), Some("c1"), 2).refused shouldBe a[GraphError.CheckpointNotFound]
    s.store.history(s.thread, None, 0).value shouldBe empty
  }

  it should "refuse a checkpoint whose id is already in the history, applying nothing" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.chain(token, Vector("c1", "c2"))
    val seqs = s.seqs()
    s.commit(token, Some(s.checkpoint("c1", Some("c2"))), events = Vector(s.event("dup"))).refused shouldBe
      a[GraphError.InvalidCommit]
    s.historyIds() shouldBe Vector("c2", "c1")
    s.seqs() shouldBe seqs
  }

  it should "retract a turn back to the newest settled checkpoint with the commit that closes it" in {
    val s       = Store()
    val token   = s.claim("run").value.token
    val settled = Set("done", "failed")
    s.chain(
      token,
      Vector("c1", "done", "c3", "c4"),
      id => if settled(id) then CheckpointStatus.Completed else CheckpointStatus.Running
    )
    val blocked = s.checkpoint("failed", Some("c4"), status = CheckpointStatus.Failed)
    // a retraction needs the checkpoint that closes the turn, and is fenced like any commit
    s.store.commit(s.thread, Commit(token, None, retractTurn = true)).refused shouldBe a[GraphError.InvalidCommit]
    s.store
      .commit(s.thread, Commit(FencingToken(token.value + 1000), Some(blocked), retractTurn = true))
      .refused shouldBe
      a[GraphError.StaleClaim]
    s.historyIds() shouldBe Vector("c4", "c3", "done", "c1")

    s.store.commit(s.thread, Commit(token, Some(blocked), Vector(s.write("failed", "9.0")), retractTurn = true)).value
    s.historyIds() shouldBe Vector("failed", "done", "c1")
    s.store.checkpoint(s.thread, "c3").value shouldBe None
    s.store.latest(s.thread).value.value.pendingWrites.map(_.taskId) shouldBe Vector("9.0")
    // with no settled checkpoint left before the turn, all of it goes
    s.chain(token, Vector("c6", "c7"))
    s.commit(token, Some(s.checkpoint("next", Some("c7"), status = CheckpointStatus.Failed))).value
    s.historyIds() shouldBe Vector("next", "c7", "c6", "failed", "done", "c1")
    val fresh = Store()
    val t2    = fresh.claim("run").value.token
    fresh.chain(t2, Vector("a", "b"))
    fresh.store
      .commit(
        fresh.thread,
        Commit(t2, Some(fresh.checkpoint("x", Some("b"), status = CheckpointStatus.Failed)), retractTurn = true)
      )
      .value
    fresh.historyIds() shouldBe Vector("x")
  }

  it should "prune by count: keep the latest and the newest, and the events from the oldest kept checkpoint's on" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.chain(token, (1 to 5).map(i => s"c$i").toVector)
    s.commit(token, None, Vector(s.write("c5", "4.0")), Vector(s.event("tail"))).value
    s.store.prune(s.thread, RetentionPolicy(maxCheckpoints = Some(2))).value shouldBe (())
    s.historyIds() shouldBe Vector("c5", "c4")
    s.store.checkpoint(s.thread, "c3").value shouldBe None
    // c4's event was the fourth
    s.store.eventsAfter(s.thread, 0L, 10).refused shouldBe GraphError.ReplayUnavailable(s.thread.value, 4L)
    s.store.eventsAfter(s.thread, 3L, 10).value.map(_.seq) shouldBe Vector(4L, 5L, 6L)
    s.store.latest(s.thread).value.value.pendingWrites.map(_.taskId) shouldBe Vector("4.0")
    s.store.prune(s.thread, RetentionPolicy(maxCheckpoints = Some(1))).value shouldBe (())
    s.historyIds() shouldBe Vector("c5")
    s.store.latest(s.thread).value.value.pendingWrites.map(_.taskId) shouldBe Vector("4.0")
    s.commit(token, events = Vector(s.event("more"))).value.map(_.seq) shouldBe Vector(7L)
  }

  it should "prune by age by the store's clock, never the latest checkpoint" in {
    val s     = Store()
    val token = s.claim("run").value.token
    val ages  = Vector("old" -> 0.minutes, "middle" -> 30.minutes, "young" -> 50.minutes)
    ages.foldLeft(Option.empty[String]) { case (parent, (id, offset)) =>
      val at = start.plusNanos(offset.toNanos)
      s.commit(token, Some(s.checkpoint(id, parent, createdAt = at)), events = Vector(s.event(id, Some(id), at))).value
      Some(id)
    }
    s.advance(60.minutes)
    s.store.prune(s.thread, RetentionPolicy(maxAge = Some(20.minutes))).value shouldBe (())
    s.historyIds() shouldBe Vector("young")
    s.store.eventsAfter(s.thread, 2L, 10).value.map(_.seq) shouldBe Vector(3L)
    s.advance(10.days)
    s.store.prune(s.thread, RetentionPolicy(maxAge = Some(1.minute))).value shouldBe (())
    s.historyIds() shouldBe Vector("young")
    s.store.eventsAfter(s.thread, 0L, 10).refused shouldBe GraphError.ReplayUnavailable(s.thread.value, 4L)
    s.commit(token, events = Vector(s.event("after"))).value.map(_.seq) shouldBe Vector(4L)
  }

  it should "prune by event count, never lowering the floor, and accept an unknown thread" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(s.checkpoint("c1", None)), events = (1 to 5).map(i => s.event(s"e$i")).toVector).value
    s.store.prune(s.thread, RetentionPolicy(maxEvents = Some(2))).value shouldBe (())
    s.store.eventsAfter(s.thread, 0L, 10).refused shouldBe GraphError.ReplayUnavailable(s.thread.value, 4L)
    s.store.eventsAfter(s.thread, 3L, 10).value.map(_.seq) shouldBe Vector(4L, 5L)
    s.store.prune(s.thread, RetentionPolicy(maxEvents = Some(10))).value shouldBe (())
    s.store.eventsAfter(s.thread, 0L, 10).refused shouldBe GraphError.ReplayUnavailable(s.thread.value, 4L)
    s.store.prune(s.thread, RetentionPolicy(maxEvents = Some(0))).value shouldBe (())
    s.store.eventsAfter(s.thread, 5L, 10).value shouldBe empty
    s.historyIds() shouldBe Vector("c1")
    s.store.prune(newThread(), RetentionPolicy(maxCheckpoints = Some(1), maxEvents = Some(0))).value shouldBe (())
  }

  it should "delete a thread's whole history, and keep it across a close and reopen" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.chain(token, Vector("c1", "c2", "c3"))
    s.reopened()
    s.historyIds() shouldBe Vector("c3", "c2", "c1")
    s.store.checkpoint(s.thread, "c1").value.map(_.id) shouldBe Some("c1")
    s.store.deleteThread(s.thread).value shouldBe (())
    s.historyIds() shouldBe empty
    s.store.checkpoint(s.thread, "c1").value shouldBe None
    // a new thread of the same id may use the ids again
    val again = s.claim("again").value.token
    s.chain(again, Vector("c1"))
    s.historyIds() shouldBe Vector("c1")
  }

  // ---- two runtimes over one store ----

  /**
   * A graph that fans its items out to one worker task each, behind a dynamic join, and collects
   * their results. A worker counts its calls, and the first call for an item with a gate waits for
   * the gate to open; later calls do not.
   */
  final private class Workers:
    val calls   = new ConcurrentHashMap[String, AtomicInteger]()
    val entered = new ConcurrentHashMap[String, CountDownLatch]()
    val gates   = new ConcurrentHashMap[String, CountDownLatch]()
    val results = StateKey.appending[String]("results")

    def gate(item: String): CountDownLatch =
      val latch = new CountDownLatch(1)
      gates.put(item, latch)
      entered.put(item, new CountDownLatch(1))
      latch

    def callsOf(item: String): Int = Option(calls.get(item)).fold(0)(_.get)

    def awaitEntered(item: String): Assertion =
      entered.get(item).await(contractWait.toMillis, TimeUnit.MILLISECONDS) shouldBe true

    val graph: CompiledGraph[Vector[String], Vector[String]] =
      val b = GraphBuilder("contract-workers", "v1")
      val worker = b.node[String]("worker", writes = Set(results)) { (item, _, _) =>
        calls.computeIfAbsent(item, _ => new AtomicInteger()).incrementAndGet()
        Option(entered.get(item)).foreach(_.countDown())
        Option(gates.remove(item)).foreach(_.await(contractWait.toMillis, TimeUnit.MILLISECONDS))
        NodeResult.Continue(Command.empty.update(results, item.toUpperCase))
      }
      val collect = b.node[Unit]("collect")((_, _, _) => NodeResult.Continue(Command.empty))
      val join    = b.dynamicJoin("workers", collect)
      val plan = b.node[Vector[String]]("plan") { (items, _, _) =>
        NodeResult.Continue(Command.empty.fanOut(join, worker, items))
      }
      b.compile(plan)(_.get(results)).value

  /** A run's result, waiting at most [[contractWait]], so a hung run fails the case. */
  private def outcome[O](handle: RunHandle[O]): Result[RunResult[O]] =
    val done = new LinkedBlockingQueue[Result[RunResult[O]]]()
    Thread.ofVirtual().start(() => done.put(handle.await())): Unit
    Option(done.poll(contractWait.toMillis, TimeUnit.MILLISECONDS))
      .getOrElse(fail(s"run did not end within $contractWait"))

  private def completed[O](handle: RunHandle[O]): O =
    outcome(handle).value match
      case RunResult.Completed(_, output, _) => output
      case other                             => fail(s"run did not complete: $other")

  /** Polls `condition` until it holds or [[contractWait]] passes. */
  private def eventually(condition: => Boolean): Assertion =
    val deadline = System.nanoTime() + contractWait.toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(5)
    condition shouldBe true

  /** A run that renews its claim far less often than any case lasts, so the case decides when it lapses. */
  private val neverRenewed = ClaimPolicy(ttl = 2.hours, renewEvery = 1.hour)

  private def config(run: String) = RunConfig().withRunId(RunId(run))

  it should "refuse a second runtime a thread whose run is live in the first, naming the holder" in {
    val s       = Store()
    val w       = Workers()
    val first   = GraphRuntime(s.store, claims = neverRenewed)
    val second  = GraphRuntime(s.store, claims = neverRenewed)
    val gate    = w.gate("a")
    val running = first.start(s.thread, w.graph, Vector("a"), config("first")).value
    w.awaitEntered("a")
    val latest = s.latestId()
    val seqs   = s.seqs()

    second.recover(s.thread, w.graph, config("second")).refused shouldBe
      GraphError.ThreadBusy(s.thread.value, latest, Some("first"))
    second.deleteThread(s.thread, config("second")).refused shouldBe
      GraphError.ThreadBusy(s.thread.value, latest, Some("first"))
    second.start(s.thread, w.graph, Vector("b"), config("second")).refused shouldBe a[GraphError.IncompleteRun]
    s.latestId() shouldBe latest
    s.seqs() shouldBe seqs

    gate.countDown()
    completed(running) shouldBe Vector("A")
    // the run released its claim as it ended, so the thread is free at once
    completed(second.start(s.thread, w.graph, Vector("b"), config("second")).value) shouldBe Vector("A", "B")
    w.callsOf("a") shouldBe 1
  }

  it should "keep renewing a live run's claim, so it is not taken over while it runs" in {
    val s       = Store()
    val w       = Workers()
    val renewed = new AtomicInteger()
    val counted = new Checkpointer:
      def claim(threadId: ThreadId, request: ClaimRequest) = s.store.claim(threadId, request)
      def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) =
        val result = s.store.renew(threadId, token, ttl)
        if result.isRight then renewed.incrementAndGet(): Unit
        result
      def release(threadId: ThreadId, token: FencingToken)            = s.store.release(threadId, token)
      def commit(threadId: ThreadId, commit: Commit)                  = s.store.commit(threadId, commit)
      def latest(threadId: ThreadId)                                  = s.store.latest(threadId)
      def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) = s.store.eventsAfter(threadId, afterSeq, limit)
      def compactEvents(threadId: ThreadId, beforeSeq: Long)          = s.store.compactEvents(threadId, beforeSeq)
      def deleteThread(threadId: ThreadId)                            = s.store.deleteThread(threadId)
      def history(threadId: ThreadId, before: Option[String], limit: Int) = s.store.history(threadId, before, limit)
      def checkpoint(threadId: ThreadId, checkpointId: String)            = s.store.checkpoint(threadId, checkpointId)
      def prune(threadId: ThreadId, policy: RetentionPolicy)              = s.store.prune(threadId, policy)
    val first   = GraphRuntime(counted, claims = ClaimPolicy(ttl = ttl, renewEvery = 20.millis))
    val second  = GraphRuntime(s.store, claims = neverRenewed)
    val gate    = w.gate("a")
    val running = first.start(s.thread, w.graph, Vector("a"), config("first")).value
    w.awaitEntered("a")

    s.advance(20.seconds)
    val before = renewed.get
    eventually(renewed.get > before)
    s.advance(20.seconds) // past the first claim's expiry, not the renewed one's
    second.recover(s.thread, w.graph, config("second")).refused shouldBe a[GraphError.ThreadBusy]

    gate.countDown()
    completed(running) shouldBe Vector("A")
  }

  it should "let a second runtime recover a run whose claim expired, without re-running its completed tasks" in {
    val s       = Store()
    val w       = Workers()
    val first   = GraphRuntime(s.store, claims = neverRenewed)
    val second  = GraphRuntime(s.store, claims = neverRenewed)
    val gate    = w.gate("b")
    val running = first.start(s.thread, w.graph, Vector("a", "b"), config("first")).value
    w.awaitEntered("b")
    // `a` finished and its result is durable; `b` is still running when its process stops renewing
    eventually(s.store.latest(s.thread).value.exists(_.pendingWrites.exists(_.nodeId == "worker")))

    second.recover(s.thread, w.graph, config("second")).refused shouldBe a[GraphError.ThreadBusy]
    s.advance(neverRenewed.ttl)
    completed(second.recover(s.thread, w.graph, config("second")).value) shouldBe Vector("A", "B")
    w.callsOf("a") shouldBe 1
    w.callsOf("b") shouldBe 2
    val recovered = s.store.latest(s.thread).value.value.checkpoint
    recovered.status shouldBe CheckpointStatus.Completed
    recovered.runId shouldBe "second"

    // the first run wakes up: its commits are refused, so it fails and records nothing more
    val seqs = s.seqs()
    gate.countDown()
    outcome(running).value match
      case RunResult.Failed(_, GraphError.CheckpointWriteFailed(_, _: GraphError.StaleClaim, _)) => succeed
      case other => fail(s"the stale run was not refused: $other")
    s.seqs() shouldBe seqs
    same(s.store.latest(s.thread).value.value.checkpoint, recovered)
    val events         = s.store.eventsAfter(s.thread, 0L, 1000).value
    val firstRecovered = events.indexWhere(_.event.isInstanceOf[RunEvent.RunRecovered])
    events.drop(firstRecovered).map(_.runId).distinct shouldBe Vector("second")
    // and the thread is free: its claim was the second run's, released when that run ended
    completed(first.start(s.thread, w.graph, Vector("c"), config("third")).value) shouldBe Vector("A", "B", "C")
  }

  it should "admit exactly one of several runtimes recovering a thread at once" in {
    val s        = Store()
    val runtimes = 4
    // a failed run leaves its thread Running with nothing holding it
    val failing = new java.util.concurrent.atomic.AtomicBoolean(true)
    val b       = GraphBuilder("contract-recover", "v1")
    val gate    = new CountDownLatch(1)
    val log     = StateKey.appending[String]("log")
    val step = b.node[String]("step", writes = Set(log)) { (input, _, _) =>
      if failing.get then NodeResult.Fail(ValidationError("step", "failed once"))
      else
        gate.await(contractWait.toMillis, TimeUnit.MILLISECONDS)
        NodeResult.Continue(Command.empty.update(log, input))
    }
    val graph = b.compile(step)(_.get(log)).value
    GraphRuntime(s.store).start(s.thread, graph, "x", config("failed")).value.await().value shouldBe
      a[RunResult.Failed]
    failing.set(false)

    val barrier = new CyclicBarrier(runtimes)
    val results = new LinkedBlockingQueue[Result[RunHandle[Vector[String]]]]()
    val threads = (1 to runtimes).map { i =>
      Thread.ofVirtual().start { () =>
        val runtime = GraphRuntime(s.store, claims = neverRenewed)
        barrier.await(contractWait.toMillis, TimeUnit.MILLISECONDS): Unit
        results.put(runtime.recover(s.thread, graph, config(s"recover-$i")))
      }
    }
    threads.foreach(_.join(contractWait.toMillis))
    val admitted = Iterator.continually(results.poll()).takeWhile(_ != null).toVector
    admitted should have size runtimes.toLong
    admitted.count(_.isRight) shouldBe 1
    admitted.collect { case Left(error) => error }.foreach(_ shouldBe a[GraphError.ThreadBusy])
    gate.countDown()
    completed(admitted.collectFirst { case Right(handle) => handle }.value) shouldBe Vector("x")
  }

  it should "refuse updateState and fork into a thread whose run is live in another runtime, then fence them by their own claim" in {
    val s      = Store()
    val w      = Workers()
    val first  = GraphRuntime(s.store, claims = neverRenewed)
    val second = GraphRuntime(s.store, claims = neverRenewed)
    completed(first.start(s.thread, w.graph, Vector("a"), config("first")).value) shouldBe Vector("A")
    val done    = s.latestId().value
    val gate    = w.gate("b")
    val target  = newThread()
    val running = first.start(s.thread, w.graph, Vector("b"), config("second-turn")).value
    w.awaitEntered("b")
    val live = s.latestId()
    second
      .updateState(s.thread, w.graph, live.value, StateUpdate.update(w.results, "X"), config = config("edit"))
      .refused shouldBe
      GraphError.ThreadBusy(s.thread.value, live, Some("second-turn"))
    // a fork only reads its source, so it may fork a thread that is running
    val forked = second.fork(s.thread, done, target, config("fork")).value
    forked.threadId shouldBe target.value
    gate.countDown()
    completed(running) shouldBe Vector("A", "B")

    val latest = s.latestId().value
    val updated =
      second.updateState(s.thread, w.graph, latest, StateUpdate.update(w.results, "X"), config = config("edit")).value
    updated.fencingToken shouldBe defined
    s.store.history(s.thread, None, 1).value.map(_.id) shouldBe Vector(updated.id)
    // both gave their claims back: runs go on at once on either thread
    completed(first.start(s.thread, w.graph, Vector("c"), config("third")).value) shouldBe Vector("A", "B", "X", "C")
    completed(second.start(target, w.graph, Vector("d"), config("on-fork")).value) shouldBe Vector("A", "D")
  }

  it should "leave nothing of a turn a node blocked in the history, through a runtime" in {
    val s   = Store()
    val b   = GraphBuilder("contract-block", "v1")
    val log = StateKey.appending[String]("log")
    val guard = b.node[String]("guard", writes = Set(log)) { (input, _, _) =>
      if input.startsWith("bad") then
        NodeResult.Block(StateUpdate.update(log, "blocked"), ValidationError("guard", "no"))
      else NodeResult.Continue(Command.empty.update(log, input))
    }
    val draft = b.node[String]("draft", writes = Set(log)) { (input, _, _) =>
      NodeResult.Continue(Command.empty.update(log, s"draft:$input").send(guard, input))
    }
    val graph   = b.compile(draft)(_.get(log)).value
    val runtime = GraphRuntime(s.store)
    completed(runtime.start(s.thread, graph, "good", config("one")).value) shouldBe Vector("draft:good", "good")
    val settled = s.historyIds()
    outcome(runtime.start(s.thread, graph, "bad", config("two")).value).value shouldBe a[RunResult.Failed]
    val history = s.store.history(s.thread, None, 1000).value
    history.map(_.id).tail shouldBe settled
    history.head.status shouldBe CheckpointStatus.Failed
    // of the blocked run, only its closing checkpoint is left: its claim and its draft's checkpoint are gone
    history.map(_.runId).count(_ == "two") shouldBe 1
    history.tail.flatMap(c => graph.restore(c.snapshot).value.state.get(log).value) should not contain "draft:bad"
  }
