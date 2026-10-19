package org.llm4s.agent.testkit

import org.llm4s.agent.graph.*
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.types.Result
import org.scalatest.{ Assertion, EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant }
import scala.annotation.unused
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{
  ConcurrentHashMap,
  CopyOnWriteArrayList,
  CountDownLatch,
  CyclicBarrier,
  LinkedBlockingQueue,
  TimeUnit
}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

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
 * The cases are the ones the `Checkpointer` Scaladoc states, in three groups:
 *
 *  - '''The store''', called directly: commits applied atomically or not at all; a new checkpoint
 *    accepted only over the latest ([[GraphError.CheckpointConflict]]) and pending writes only for
 *    it ([[GraphError.InvalidCommit]]); events numbered contiguously in commit order and never
 *    reused, also after a refused commit, compaction or `deleteThread` - a new thread of a deleted
 *    thread's id is numbered on past the deleted thread's events, and a read from inside the deleted
 *    log is refused ([[GraphError.ThreadDeleted]]), also while waiting; compaction and
 *    [[GraphError.ReplayUnavailable]]; `deleteThread`, fenced by the current claim's token like a
 *    commit ([[GraphError.StaleClaim]] otherwise, deleting nothing); checkpoints, pending writes and events read
 *    back as written; the checkpoint history - every superseded checkpoint kept, newest first, paged
 *    and read by id, ids never repeated, a blocked turn retracted (`Commit.retractTurn`), and pruning
 *    by age, by count and by event count, never the latest checkpoint; effect records (design §4.24) -
 *    upserted by key and fenced like any commit, released at a commit with a checkpoint exactly as
 *    `Commit.releasesEffect` says, kept by `prune` and across a reopen, removed by `deleteThread`; child threads (design
 *    4.41) - a checkpoint's `origin` and `outcome` read back, a format-7 checkpoint read with neither, and `children`
 *    listing a parent's children oldest first, paged, without a tenant, never one deleted or one whose latest
 *    checkpoint names another parent; and claims and fencing - one
 *    live claim per thread, tokens strictly
 *    increasing, takeover once a claim has expired by the store's clock (naming the expired claim's
 *    holder as `takenOverFrom`), the tenant recorded on each claim - a holder named, in a refusal or as
 *    `takenOverFrom`, only to a claim of its own tenant, also across a reopen - renewal and release by the
 *    current token only, a refused claim leaving the live one as it was, and every commit refused
 *    with [[GraphError.StaleClaim]] unless it carries the current token - checked before any other
 *    refusal, and including from many threads at once; one claim granted of many raced at once from
 *    as many stores over the storage ([[contenders]]); stored values kept apart from those callers
 *    pass in and are given back - and change notification:
 *    `awaitEventsAfter` returns what is there at once, returns a commit made meanwhile through
 *    this store or another over the same storage within about its `timeout`, returns empty when
 *    nothing lands, and ends its wait when interrupted.
 *  - '''Two runtimes over one store''': a second [[GraphRuntime]] is refused a thread whose run is
 *    live in the first ([[GraphError.ThreadBusy]] naming the holder); a run that keeps renewing is
 *    not taken over; once a claim expires, the second runtime recovers the thread without re-running
 *    the first run's completed tasks, and the first run's later commits are refused as stale, so it
 *    ends with [[GraphError.ClaimLost]] and leaves nothing in the thread; and of several
 *    runtimes recovering one thread at once, exactly one is admitted; `updateState` and `fork` from a
 *    second runtime are refused while a run is live in the first, and fenced by their own claim; and
 *    a run a node blocks leaves nothing of its turn in the history; and a claim of another tenant on a
 *    thread never reveals its run id to the thread's own tenant - not through `ThreadBusy`, also while it
 *    has claimed but not yet checkpointed, not as `RunRecovered.takenOverFrom` once it lapses, and not
 *    through `ClaimLost` when it takes over a run of the owner.
 *  - '''Subscriptions across runtimes''', each runtime over a store of its own on the same storage
 *    ([[sibling]]): a subscription in one runtime receives the durable events of runs another
 *    runtime commits - in order, without gap or duplicate, when it joined before or mid-run - while
 *    runs alternate between the runtimes; resumes from a sequence number after its runtime restarts;
 *    never receives another runtime's live progress; stops watching the store when cancelled; and
 *    ends with [[DisconnectReason.TenantMismatch]], delivering nothing of it, when another runtime
 *    creates the thread for another tenant.
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
 * that was really closed. The default reopens nothing and hands the same instance back. It overrides
 * [[sibling]] too, so that the cases about subscriptions across runtimes put each runtime on a store
 * of its own - another connection, as another process would open - and change notification is
 * checked between stores, not within one; and the cases racing many claims at once spread them over
 * that many stores ([[contenders]], by default built with `sibling`), so that a store's own storage
 * decides the race - for `SqliteCheckpointer`, sixteen connections to one file.
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
   * Another store over the same storage as `store`, as another runtime or process would open it,
   * judging claims by the same `clock`; `store` stays open. The default hands `store` back, which is
   * right for a store that keeps nothing beyond its instance, such as `InMemoryCheckpointer`: runtimes
   * share such a store by sharing the instance.
   */
  protected def sibling(store: Checkpointer, @unused clock: ManualClock): Checkpointer = store

  /**
   * `n` stores over the same storage as `store`, `store` first, that the cases racing many claims at once
   * spread their claimers over - one store each - so that the race is decided by the storage's own
   * exclusion, not by one instance's lock. The default is `store` and `n - 1` [[sibling]]s: another
   * connection each for a store that overrides `sibling`, the same instance for one that does not.
   */
  protected def contenders(store: Checkpointer, clock: ManualClock, n: Int): Vector[Checkpointer] =
    store +: Vector.fill(n - 1)(sibling(store, clock))

  /**
   * Closes a store a case opened besides its own - a [[contenders]] store - once the case is done with it.
   * The default closes an `AutoCloseable` store and leaves any other alone.
   */
  protected def closeExtra(store: Checkpointer): Unit = store match
    case closeable: AutoCloseable => closeable.close()
    case _                        => ()

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

  /**
   * A store for the case that checks the contract's bound on every call (#1919), whose claims expire by `clock`:
   * the store, the bound its own documentation gives every call - [[CheckpointerContract.BoundedStore]] - and a way
   * to make its storage busy from outside, as another connection or process holding a write would. The case waits
   * out that bound more than once, so give it a store configured with a short one. `None`, the default, skips the
   * case: right for a store whose storage nothing outside the instance can hold, such as `InMemoryCheckpointer`,
   * whose calls never wait on anything but each other's brief turn at its lock.
   */
  protected def boundedStore(@unused clock: Clock): Option[CheckpointerContract.BoundedStore] = None

  /**
   * How far past its bound a call may return in [[boundedStore]]'s case before the case fails: scheduling, and a
   * driver that polls its lock - SQLite's busy handler sleeps up to 100 ms between tries - add a little to any bound.
   */
  protected def boundGrace: FiniteDuration = 500.millis

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

    /** Another store over the same storage; see [[CheckpointerContract.sibling]]. */
    def other(): Checkpointer = sibling(current, clock)

    /** `n` stores over the same storage, this one first; see [[CheckpointerContract.contenders]]. */
    def contending(n: Int): Vector[Checkpointer] = contenders(current, clock, n)

    /** Closes the stores [[contending]] opened besides this one; see [[CheckpointerContract.closeExtra]]. */
    def closeContenders(stores: Vector[Checkpointer]): Unit =
      stores.filterNot(_ eq current).distinct.foreach(closeExtra)

    /** Moves the store's clock; see [[CheckpointerContract.advanceStoreClock]]. */
    def advance(by: FiniteDuration): Unit = advanceStoreClock(current, clock, by)

    /** `actual` is `start` plus `offset`, checked as far as [[exactExpiry]] allows. */
    def expiresAt(actual: Instant, offset: FiniteDuration, previous: Option[Instant] = None): Assertion =
      if exactExpiry then actual shouldBe start.plusNanos(offset.toNanos)
      else previous.fold(succeed)(before => actual.isAfter(before) shouldBe true)

    def claim(holder: String, on: ThreadId = thread, tenant: Option[String] = None): Result[RunClaim] =
      store.claim(on, ClaimRequest(RunId(holder), tenant.map(TenantId(_)), ttl))

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

  it should "record on each event the token of the commit that stored it, so two claims under one run id differ" in {
    val s     = Store()
    val first = s.claim("run").value
    s.commit(first.token, Some(s.checkpoint("c1", None)), events = Vector(s.event("a"), s.event("b")))
      .value
      .map(_.token) shouldBe Vector(first.token, first.token)
    // the same run id claims again, after a takeover: its events carry the new claim's token
    s.advance(ttl)
    val again = s.claim("run").value
    s.commit(again.token, events = Vector(s.event("c"))).value.map(_.token) shouldBe Vector(again.token)
    s.store.eventsAfter(s.thread, 0L, 10).value.map(r => r.runId -> r.token) shouldBe
      Vector("run" -> first.token, "run" -> first.token, "run" -> again.token)
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
      token,
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

  it should "delete a thread's checkpoint, writes, events and claim with the current token, and leave others alone" in {
    val s     = Store()
    val other = newThread()
    val token = s.claim("run").value.token
    s.commit(token, Some(s.checkpoint("c1", None)), Vector(s.write("c1", "0.0")), Vector(s.event("a"))).value
    val otherToken = s.claim("other", other).value.token
    s.commit(otherToken, Some(s.checkpoint("o1", None, on = other)), events = Vector(s.event("x")), on = other).value

    s.store.deleteThread(s.thread, token).value shouldBe (())

    s.store.latest(s.thread).value shouldBe None
    s.historyIds() shouldBe empty
    s.seqs() shouldBe empty
    s.latestId(other) shouldBe Some("o1")
    s.seqs(other) shouldBe Vector(1L)
    // the claim went with the thread: its token is stale, and the thread can be claimed at once
    s.commit(token, events = Vector(s.event("late"))).refused shouldBe a[GraphError.StaleClaim]
    s.store.renew(s.thread, token, ttl).refused shouldBe a[GraphError.StaleClaim]
    s.store.deleteThread(s.thread, token).refused shouldBe GraphError.StaleClaim(s.thread.value, token, None)
    val again = s.claim("again").value
    again.token.value should be > token.value
    // the id is a new thread: a first checkpoint is accepted, its events numbered on past the old ones,
    // the deleted thread's next number skipped
    s.commit(again.token, Some(s.checkpoint("c1", None)), events = Vector(s.event("again"))).value.map(_.seq) shouldBe
      Vector(3L)
    s.store.latest(s.thread).value.value.pendingWrites shouldBe empty
  }

  it should "refuse a delete with a stale token, deleting nothing" in {
    val s     = Store()
    val first = s.claim("first").value
    s.commit(first.token, Some(s.checkpoint("c1", None)), Vector(s.write("c1", "0.0")), Vector(s.event("a"))).value
    // released: the token is no longer the thread's claim, and nobody holds it
    s.store.release(s.thread, first.token).value shouldBe (())
    s.store.deleteThread(s.thread, first.token).refused shouldBe
      GraphError.StaleClaim(s.thread.value, first.token, None)
    // taken over after expiry: the old holder's delete is refused, naming the current token
    val second = s.claim("second").value
    s.advance(ttl)
    val third = s.claim("third").value
    s.store.deleteThread(s.thread, second.token).refused shouldBe
      GraphError.StaleClaim(s.thread.value, second.token, Some(third.token))
    s.store.deleteThread(s.thread, FencingToken(third.token.value + 1000)).refused shouldBe a[GraphError.StaleClaim]
    // nothing was deleted, and the current holder is untouched
    s.latestId() shouldBe Some("c1")
    s.store.latest(s.thread).value.value.pendingWrites.map(_.taskId) shouldBe Vector("0.0")
    s.seqs() shouldBe Vector(1L)
    s.claim("fourth").refused shouldBe GraphError.ThreadBusy(
      s.thread.value,
      Some("c1"),
      GraphError.BusyHolder.Run(RunId("third"))
    )
    s.commit(third.token, events = Vector(s.event("b"))).value.map(_.seq) shouldBe Vector(2L)
    // an unknown thread has no claim to delete under
    val unknown = newThread()
    s.store.deleteThread(unknown, third.token).refused shouldBe
      GraphError.StaleClaim(unknown.value, third.token, None)
  }

  it should "refuse a delete over another holder's live claim, deleting nothing and leaving the claim live" in {
    val s      = Store()
    val holder = s.claim("holder").value
    s.commit(holder.token, Some(s.checkpoint("c1", None)), events = Vector(s.event("a"))).value
    // a deleter holding a claim on another thread, or a token from before, cannot delete this one
    val elsewhere = s.claim("deleter", newThread()).value
    s.store.deleteThread(s.thread, elsewhere.token).refused shouldBe
      GraphError.StaleClaim(s.thread.value, elsewhere.token, Some(holder.token))
    s.store.deleteThread(s.thread, FencingToken(holder.token.value - 1)).refused shouldBe
      GraphError.StaleClaim(s.thread.value, FencingToken(holder.token.value - 1), Some(holder.token))
    s.latestId() shouldBe Some("c1")
    s.seqs() shouldBe Vector(1L)
    s.claim("deleter").refused shouldBe GraphError.ThreadBusy(
      s.thread.value,
      Some("c1"),
      GraphError.BusyHolder.Run(RunId("holder"))
    )
    s.store.renew(s.thread, holder.token, ttl).value.token shouldBe holder.token
    s.commit(holder.token, events = Vector(s.event("b"))).value.map(_.seq) shouldBe Vector(2L)
  }

  it should "number a new thread of a deleted thread's id past the deleted thread's events, across a reopen" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(s.checkpoint("c1", None)), events = Vector(s.event("a"), s.event("b"), s.event("c"))).value
    s.store.deleteThread(s.thread, token).value shouldBe (())
    s.seqs() shouldBe empty
    s.reopened()
    s.seqs() shouldBe empty
    val again = s.claim("again").value.token
    // 4, the deleted thread's next number, is skipped
    s.commit(again, Some(s.checkpoint("n1", None)), events = Vector(s.event("n1"), s.event("n2")))
      .value
      .map(_.seq) shouldBe
      Vector(5L, 6L)
    s.seqs() shouldBe Vector(5L, 6L)
    s.store.eventsAfter(s.thread, 5L, 10).value.map(_.seq) shouldBe Vector(6L)
    // and again: each new thread of the id is numbered on past every one before it
    s.store.deleteThread(s.thread, again).value shouldBe (())
    val third = s.claim("third").value.token
    s.commit(third, events = Vector(s.event("t1"))).value.map(_.seq) shouldBe Vector(8L)
    // deleting a tombstone that numbered no event since moves nothing on: forgetting twice is idempotent
    s.store.deleteThread(s.thread, third).value shouldBe (())
    val fourth = s.claim("fourth").value.token
    s.store.deleteThread(s.thread, fourth).value shouldBe (())
    s.store.eventsAfter(s.thread, 8L, 10).refused shouldBe GraphError.ThreadDeleted(s.thread.value, 8L, 10L)
    val fifth = s.claim("fifth").value.token
    s.commit(fifth, events = Vector(s.event("f1"))).value.map(_.seq) shouldBe Vector(10L)
    // a thread that numbered no event leaves no gap
    val quiet = newThread()
    val q1    = s.claim("q1", quiet).value.token
    s.store.deleteThread(quiet, q1).value shouldBe (())
    val q2 = s.claim("q2", quiet).value.token
    s.commit(q2, events = Vector(s.event("q")), on = quiet).value.map(_.seq) shouldBe Vector(1L)
  }

  it should "refuse a read from inside a deleted thread's log, so no reader takes a new thread for its sequel" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(s.checkpoint("c1", None)), events = Vector(s.event("a"), s.event("b"), s.event("c"))).value
    s.store.deleteThread(s.thread, token).value shouldBe (())

    // deleted, not yet created again: a reader of the old log learns that it ended
    s.store.eventsAfter(s.thread, 3L, 10).refused shouldBe GraphError.ThreadDeleted(s.thread.value, 3L, 5L)
    s.store.eventsAfter(s.thread, 1L, 10).refused shouldBe GraphError.ThreadDeleted(s.thread.value, 1L, 5L)
    within("awaitEventsAfter")(s.store.awaitEventsAfter(s.thread, 3L, 10, 1.hour)).refused shouldBe
      GraphError.ThreadDeleted(s.thread.value, 3L, 5L)
    // a reader from the start, or from just before the new thread's first event, reads the new thread
    s.store.eventsAfter(s.thread, 0L, 10).value shouldBe empty
    s.store.eventsAfter(s.thread, 4L, 10).value shouldBe empty

    val again = s.claim("again").value.token
    s.commit(again, Some(s.checkpoint("n1", None)), events = Vector(s.event("n1"), s.event("n2"))).value
    // created again: the old log's readers are still refused, by every store over the storage
    s.store.eventsAfter(s.thread, 3L, 10).refused shouldBe GraphError.ThreadDeleted(s.thread.value, 3L, 5L)
    within("awaitEventsAfter")(s.store.awaitEventsAfter(s.thread, 3L, 10, 1.hour)).refused shouldBe
      GraphError.ThreadDeleted(s.thread.value, 3L, 5L)
    s.other().eventsAfter(s.thread, 2L, 10).refused shouldBe GraphError.ThreadDeleted(s.thread.value, 2L, 5L)
    s.store.eventsAfter(s.thread, 0L, 10).value.map(_.seq) shouldBe Vector(5L, 6L)
    s.store.eventsAfter(s.thread, 4L, 10).value.map(_.seq) shouldBe Vector(5L, 6L)
    s.store.eventsAfter(s.thread, 5L, 10).value.map(_.seq) shouldBe Vector(6L)
  }

  it should "end an awaitEventsAfter wait from inside a thread's log when the thread is deleted meanwhile" in {
    val s     = Store()
    val other = s.other()
    val token = s.claim("run").value.token
    s.commit(token, events = Vector(s.event("a"), s.event("b"))).value
    val found = new LinkedBlockingQueue[Result[Vector[EventRecord]]]()
    // waits as a subscription's watch does: one call after another, until one does not return empty
    Thread.ofVirtual().start { () =>
      val deadline = System.nanoTime() + contractWait.toNanos
      var result   = s.store.awaitEventsAfter(s.thread, 2L, 10, 100.millis)
      while result.exists(_.isEmpty) && System.nanoTime() < deadline do
        result = s.store.awaitEventsAfter(s.thread, 2L, 10, 100.millis)
      found.put(result)
    }: Unit
    Thread.sleep(50)
    other.deleteThread(s.thread, token).value shouldBe (())
    Option(found.poll(contractWait.toMillis + 1000, TimeUnit.MILLISECONDS))
      .getOrElse(fail("awaitEventsAfter never returned"))
      .refused shouldBe GraphError.ThreadDeleted(s.thread.value, 2L, 4L)
  }

  // ---- claims ----

  it should "grant a claim on a new thread, then refuse every other claim while it is live, naming its holder" in {
    val s       = Store()
    val granted = s.claim("first").value
    granted.threadId shouldBe s.thread
    granted.holder shouldBe RunId("first")
    s.expiresAt(granted.expiresAt, 30.seconds)
    s.claim("second").refused shouldBe GraphError.ThreadBusy(
      s.thread.value,
      None,
      GraphError.BusyHolder.Run(RunId("first"))
    )
    // the same holder is refused too: a claim is not re-entrant
    s.claim("first").refused shouldBe GraphError.ThreadBusy(
      s.thread.value,
      None,
      GraphError.BusyHolder.Run(RunId("first"))
    )
    s.advance(ttl - 1.milli)
    s.claim("second").refused shouldBe a[GraphError.ThreadBusy]
    s.commit(granted.token, Some(s.checkpoint("c1", None))).value
    s.claim("second").refused shouldBe GraphError.ThreadBusy(
      s.thread.value,
      Some("c1"),
      GraphError.BusyHolder.Run(RunId("first"))
    )
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
    // the takeover names the expired claim's holder, so a recovering run can record it
    taken.takenOverFrom shouldBe Some(RunId("first"))
    first.takenOverFrom shouldBe None

    s.commit(
      first.token,
      Some(s.checkpoint("stale", Some("c1"))),
      Vector(s.write("stale", "1.0")),
      Vector(s.event("x"))
    ).refused shouldBe GraphError.StaleClaim(s.thread.value, first.token, Some(taken.token))
    s.commit(first.token, None, Vector(s.write("c1", "1.0"))).refused shouldBe a[GraphError.StaleClaim]
    s.commit(first.token, events = Vector(s.event("y"))).refused shouldBe a[GraphError.StaleClaim]
    s.store.renew(s.thread, first.token, ttl).refused shouldBe a[GraphError.StaleClaim]
    s.store.release(s.thread, first.token).value shouldBe (())
    s.claim("third").refused shouldBe GraphError.ThreadBusy(
      s.thread.value,
      Some("c1"),
      GraphError.BusyHolder.Run(RunId("second"))
    )

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

  it should "check the token before every other refusal: a duplicate id, misdirected writes, a retraction with nothing to close it" in {
    val s     = Store()
    val first = s.claim("first").value
    s.chain(first.token, Vector("c1", "c2"))
    s.advance(ttl)
    val taken = s.claim("second").value
    val seqs  = s.seqs()
    val stale = GraphError.StaleClaim(s.thread.value, first.token, Some(taken.token))
    // each of these is invalid as well as stale: the stale writer learns only that it lost its claim
    s.commit(first.token, Some(s.checkpoint("c1", Some("c2"))), events = Vector(s.event("dup"))).refused shouldBe stale
    s.commit(first.token, None, Vector(s.write("c1", "0.0"))).refused shouldBe stale
    s.commit(first.token, Some(s.checkpoint("c3", Some("c2"))), Vector(s.write("c2", "0.0"))).refused shouldBe stale
    s.store.commit(s.thread, Commit(first.token, None, retractTurn = true)).refused shouldBe stale
    // with the current token, each is refused for what it is
    s.commit(taken.token, Some(s.checkpoint("c1", Some("c2")))).refused shouldBe a[GraphError.InvalidCommit]
    s.commit(taken.token, None, Vector(s.write("c1", "0.0"))).refused shouldBe a[GraphError.InvalidCommit]
    s.store.commit(s.thread, Commit(taken.token, None, retractTurn = true)).refused shouldBe
      a[GraphError.InvalidCommit]
    s.historyIds() shouldBe Vector("c2", "c1")
    s.seqs() shouldBe seqs
  }

  it should "leave the live claim as it was when it refuses a claim, issuing nothing for the refusal" in {
    val s     = Store()
    val first = s.claim("first").value
    s.commit(first.token, Some(s.checkpoint("c1", None)), events = Vector(s.event("a"))).value
    s.advance(ttl - 1.second)
    (1 to 3).foreach(i => s.claim(s"refused-$i").refused shouldBe a[GraphError.ThreadBusy])
    // the holder's claim is untouched: it renews and commits under its token, and nothing else does
    val renewed = s.store.renew(s.thread, first.token, ttl).value
    (renewed.holder, renewed.token, renewed.takenOverFrom) shouldBe ((RunId("first"), first.token, None))
    s.commit(first.token, events = Vector(s.event("b"))).value.map(_.seq) shouldBe Vector(2L)
    // no refusal left a claim behind: a token above the holder's is refused, naming the holder's
    s.commit(FencingToken(first.token.value + 1), events = Vector(s.event("x"))).refused shouldBe
      GraphError.StaleClaim(s.thread.value, FencingToken(first.token.value + 1), Some(first.token))
    // and once the renewed claim expires, the takeover replaces the holder's, not a refused request's
    s.advance(ttl + ttl)
    val taken = s.claim("second").value
    taken.takenOverFrom shouldBe Some(RunId("first"))
    taken.token.value should be > first.token.value
    s.seqs() shouldBe Vector(1L, 2L)
  }

  it should "record a claim's tenant, and name a live claim's holder only to a claim of the same tenant" in {
    val s       = Store()
    val granted = s.claim("b-run", tenant = Some("b")).value
    granted.tenantId shouldBe Some(TenantId("b"))
    def refusals(latest: Option[String]): Assertion =
      // another tenant, and no tenant, which differs from every tenant, learn only that the thread is held
      s.claim("a-run", tenant = Some("a")).refused shouldBe
        GraphError.ThreadBusy(s.thread.value, latest, GraphError.BusyHolder.Unnamed)
      s.claim("none-run").refused shouldBe GraphError.ThreadBusy(s.thread.value, latest, GraphError.BusyHolder.Unnamed)
      s.claim("b-other", tenant = Some("b")).refused shouldBe
        GraphError.ThreadBusy(s.thread.value, latest, GraphError.BusyHolder.Run(RunId("b-run")))
    // before the holder's first checkpoint, the moment the thread has no tenant of its own to check
    refusals(None)
    s.commit(granted.token, Some(s.checkpoint("c1", None))).value
    refusals(Some("c1"))
    // the tenant is stored with the claim: it survives a reopen, as the claim does
    s.reopened()
    refusals(Some("c1"))
    s.store.renew(s.thread, granted.token, ttl).value.tenantId shouldBe Some(TenantId("b"))
    // and a claim of no tenant is named to a claim of no tenant only
    val other = newThread()
    s.claim("plain", other).value.tenantId shouldBe None
    s.claim("again", other).refused shouldBe
      GraphError.ThreadBusy(other.value, None, GraphError.BusyHolder.Run(RunId("plain")))
    s.claim("a-run", other, Some("a")).refused shouldBe
      GraphError.ThreadBusy(other.value, None, GraphError.BusyHolder.Unnamed)
  }

  it should "name the holder a takeover replaced only to a claim of the same tenant" in {
    val s = Store()
    s.claim("b-run", tenant = Some("b")).value
    s.advance(ttl)
    // another tenant's takeover learns nothing of the run it replaced
    val foreign = s.claim("a-run", tenant = Some("a")).value
    foreign.takenOverFrom shouldBe None
    foreign.tenantId shouldBe Some(TenantId("a"))
    s.advance(ttl)
    s.claim("plain").value.takenOverFrom shouldBe None
    s.advance(ttl)
    s.claim("plain-2").value.takenOverFrom shouldBe Some(RunId("plain"))
    s.advance(ttl)
    s.claim("a-run", tenant = Some("a")).value.takenOverFrom shouldBe None
    // across a reopen too, the replaced claim's tenant decides
    s.reopened()
    s.advance(ttl)
    s.claim("a-next", tenant = Some("a")).value.takenOverFrom shouldBe Some(RunId("a-run"))
    s.advance(ttl)
    s.claim("b-next", tenant = Some("b")).value.takenOverFrom shouldBe None
  }

  it should "keep what it stores apart from the values its callers pass in and are given back" in {
    val s     = Store()
    val token = s.claim("run").value.token
    // ujson values are mutable: each call below builds a fresh one with the same content
    def payload(): ujson.Obj = ujson.Obj("text" -> "kept", "list" -> ujson.Arr(1, 2))
    def checkpoint(p: ujson.Value): Checkpoint =
      val base = s.checkpoint("c1", None)
      base.withSnapshot(base.snapshot.copy(state = Map("log" -> VersionedJson(1, p))))
    def write(p: ujson.Value): PendingWrite =
      PendingWrite("c1", "0.0", "n", Vector(EncodedOperation.Update("log", VersionedJson(1, p))), Vector.empty)
    def event(p: ujson.Value): RunEvent = RunEvent.Custom("contract.alias", 1, p)
    def change(value: ujson.Value): Unit = value match
      case obj: ujson.Obj =>
        obj.value("text") = ujson.Str("changed")
        obj.value("list").arr += ujson.Num(3)
        obj.value("added") = ujson.True
      case _ => ()
    def changeCheckpoint(c: Checkpoint): Unit = c.snapshot.state.values.foreach(v => change(v.value))
    def changeWrites(writes: Vector[PendingWrite]): Unit =
      writes.flatMap(_.operations).foreach {
        case EncodedOperation.Update(_, update) => change(update.value)
        case _                                  => ()
      }
    def changeEvents(records: Vector[EventRecord]): Unit =
      records.map(_.event).foreach {
        case RunEvent.Custom(_, _, p) => change(p)
        case _                        => ()
      }

    val (inCheckpoint, inWrite, inEvent) = (payload(), payload(), payload())
    val records = s
      .commit(
        token,
        Some(checkpoint(inCheckpoint)),
        Vector(write(inWrite)),
        Vector(EventDraft("run", Some("c1"), None, None, start, event(inEvent)))
      )
      .value
    val stored = StoredCheckpoint(checkpoint(payload()), Vector(write(payload())))
    val logged = Vector(EventRecord(s.thread.value, 1L, "run", token, Some("c1"), None, None, start, event(payload())))
    def readsBack(): Assertion =
      same(s.store.latest(s.thread).value.value, stored)
      same(s.store.history(s.thread, None, 10).value, Vector(stored.checkpoint))
      same(s.store.checkpoint(s.thread, "c1").value.value, stored.checkpoint)
      same(s.store.eventsAfter(s.thread, 0L, 10).value, logged)

    // what the caller passed, and what the commit returned, changed afterwards
    Seq(inCheckpoint, inWrite, inEvent).foreach(change)
    changeEvents(records)
    readsBack()
    // what a read returned, changed afterwards
    val latest = s.store.latest(s.thread).value.value
    changeCheckpoint(latest.checkpoint)
    changeWrites(latest.pendingWrites)
    s.store.history(s.thread, None, 10).value.foreach(changeCheckpoint)
    s.store.checkpoint(s.thread, "c1").value.foreach(changeCheckpoint)
    changeEvents(s.store.eventsAfter(s.thread, 0L, 10).value)
    readsBack()
  }

  it should "refuse a commit with no claim, or with a token never issued, applying nothing" in {
    val s = Store()
    s.commit(FencingToken(1L), Some(s.checkpoint("c1", None)), events = Vector(s.event("a"))).refused shouldBe
      a[GraphError.StaleClaim]
    val granted = s.claim("run").value
    s.commit(FencingToken(granted.token.value + 1000), Some(s.checkpoint("c1", None))).refused shouldBe
      GraphError.StaleClaim(s.thread.value, FencingToken(granted.token.value + 1000), Some(granted.token))
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
    s.claim("second").refused shouldBe GraphError.ThreadBusy(
      s.thread.value,
      None,
      GraphError.BusyHolder.Run(RunId("first"))
    )
    s.advance(10.seconds)
    s.claim("second").value.token.value should be > first.token.value
  }

  it should "free a released thread at once, refuse the released token, and ignore a stale release" in {
    val s     = Store()
    val first = s.claim("first").value
    s.commit(first.token, Some(s.checkpoint("c1", None))).value
    s.store.release(s.thread, first.token).value shouldBe (())
    s.commit(first.token, events = Vector(s.event("late"))).refused shouldBe
      GraphError.StaleClaim(s.thread.value, first.token, None)
    s.store.renew(s.thread, first.token, ttl).refused shouldBe
      GraphError.StaleClaim(s.thread.value, first.token, None)
    s.store.release(s.thread, first.token).value shouldBe (())
    val second = s.claim("second").value
    second.token.value should be > first.token.value
    // a released claim was not taken over
    second.takenOverFrom shouldBe None
    // a release with the old token must not free the new holder's claim
    s.store.release(s.thread, first.token).value shouldBe (())
    s.claim("third").refused shouldBe GraphError.ThreadBusy(
      s.thread.value,
      Some("c1"),
      GraphError.BusyHolder.Run(RunId("second"))
    )
    s.latestId() shouldBe Some("c1")
    s.seqs() shouldBe empty
  }

  it should "issue strictly increasing tokens per thread across releases, takeovers and deletes" in {
    val s = Store()
    val tokens = (1 to 6).map { i =>
      val granted = s.claim(s"run-$i").value
      i % 3 match
        case 0 => s.store.deleteThread(s.thread, granted.token).value
        case 1 => s.store.release(s.thread, granted.token).value
        case _ => s.advance(ttl)
      granted.token.value
    }
    tokens shouldBe tokens.sorted
    tokens.distinct should have size tokens.size.toLong
  }

  it should "grant exactly one of many concurrent claims on a thread, from as many stores over the storage" in {
    val s        = Store()
    val claimers = 16
    val stores   = s.contending(claimers)
    val barrier  = new CyclicBarrier(claimers)
    val results  = new LinkedBlockingQueue[(Int, Result[RunClaim])]()
    val threads = (1 to claimers).map { i =>
      Thread.ofVirtual().start { () =>
        barrier.await(contractWait.toMillis, TimeUnit.MILLISECONDS): Unit
        results.put(i -> stores(i - 1).claim(s.thread, ClaimRequest(RunId(s"run-$i"), None, ttl)))
      }
    }
    threads.foreach(_.join(contractWait.toMillis))
    s.closeContenders(stores)
    val all     = Iterator.continually(results.poll()).takeWhile(_ != null).toVector
    val granted = all.collect { case (i, Right(claim)) => i -> claim }
    all should have size claimers.toLong
    granted should have size 1
    val (winner, claim) = granted.head
    claim.holder shouldBe RunId(s"run-$winner")
    all.collect { case (_, Left(error)) => error }.foreach {
      _ shouldBe GraphError.ThreadBusy(s.thread.value, None, GraphError.BusyHolder.Run(RunId(s"run-$winner")))
    }
  }

  it should "grant exactly one of many concurrent takeovers of an expired claim, from as many stores over the storage" in {
    val s        = Store()
    val first    = s.claim("first").value
    val claimers = 16
    val stores   = s.contending(claimers)
    s.commit(first.token, Some(s.checkpoint("c1", None))).value
    s.advance(ttl)
    val barrier = new CyclicBarrier(claimers)
    val results = new LinkedBlockingQueue[(Int, Result[RunClaim])]()
    val threads = (1 to claimers).map { i =>
      Thread.ofVirtual().start { () =>
        barrier.await(contractWait.toMillis, TimeUnit.MILLISECONDS): Unit
        results.put(i -> stores(i - 1).claim(s.thread, ClaimRequest(RunId(s"run-$i"), None, ttl)))
      }
    }
    threads.foreach(_.join(contractWait.toMillis))
    s.closeContenders(stores)
    val all     = Iterator.continually(results.poll()).takeWhile(_ != null).toVector
    val granted = all.collect { case (i, Right(claim)) => i -> claim }
    all should have size claimers.toLong
    granted should have size 1
    val (winner, claim) = granted.head
    claim.holder shouldBe RunId(s"run-$winner")
    claim.token.value should be > first.token.value
    all.collect { case (_, Left(error)) => error }.foreach {
      _ shouldBe GraphError.ThreadBusy(s.thread.value, Some("c1"), GraphError.BusyHolder.Run(RunId(s"run-$winner")))
    }
    // the old holder is fenced off, and the winner alone can commit
    s.commit(first.token, events = Vector(s.event("stale"))).refused shouldBe
      GraphError.StaleClaim(s.thread.value, first.token, Some(claim.token))
    s.commit(claim.token, events = Vector(s.event("won"))).value.map(_.seq) shouldBe Vector(1L)
  }

  // ---- reopening the store ----

  it should "keep a live claim, its holder and its token across a close and reopen" in {
    val s     = Store()
    val first = s.claim("first").value
    s.commit(first.token, Some(s.checkpoint("c1", None)), events = Vector(s.event("a"))).value
    s.reopened()
    s.claim("second").refused shouldBe GraphError.ThreadBusy(
      s.thread.value,
      Some("c1"),
      GraphError.BusyHolder.Run(RunId("first"))
    )
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
      GraphError.StaleClaim(s.thread.value, first.token, Some(taken.token))
    s.store.renew(s.thread, first.token, ttl).refused shouldBe a[GraphError.StaleClaim]
    s.store.release(s.thread, first.token).value shouldBe (())
    s.claim("third").refused shouldBe GraphError.ThreadBusy(
      s.thread.value,
      Some("c1"),
      GraphError.BusyHolder.Run(RunId("second"))
    )
    s.commit(taken.token, events = Vector(s.event("ok"))).value.map(_.seq) shouldBe Vector(1L)

    // a token issued after a reopen is greater than every token before it, on this thread and after a delete
    s.store.deleteThread(s.thread, taken.token).value shouldBe (())
    s.reopened()
    val again = s.claim("again").value
    again.token.value should be > taken.token.value
    s.claim("fresh", newThread()).value.token.value should be > again.token.value
  }

  // ---- compaction and deletion together ----

  it should "start a deleted thread's replay floor at the new thread's first event, so it replays from the start" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(s.checkpoint("c1", None)), events = (1 to 5).map(i => s.event(s"e$i")).toVector).value
    s.store.compactEvents(s.thread, 4L).value shouldBe (())
    s.store.eventsAfter(s.thread, 0L, 10).refused shouldBe GraphError.ReplayUnavailable(s.thread.value, 4L)

    s.store.deleteThread(s.thread, token).value shouldBe (())
    s.store.eventsAfter(s.thread, 0L, 10).value shouldBe empty
    val again = s.claim("again").value.token
    s.commit(again, Some(s.checkpoint("n1", None)), events = Vector(s.event("n1"), s.event("n2")))
      .value
      .map(_.seq) shouldBe
      Vector(7L, 8L)
    s.store.eventsAfter(s.thread, 0L, 10).value.map(_.seq) shouldBe Vector(7L, 8L)
    // and compaction on the new thread starts from its own floor
    s.store.compactEvents(s.thread, 8L).value shouldBe (())
    s.store.eventsAfter(s.thread, 0L, 10).refused shouldBe GraphError.ReplayUnavailable(s.thread.value, 8L)
    s.store.eventsAfter(s.thread, 7L, 10).value.map(_.seq) shouldBe Vector(8L)
    // a reader of the deleted log is told it was deleted, not that it was compacted
    s.store.eventsAfter(s.thread, 3L, 10).refused shouldBe GraphError.ThreadDeleted(s.thread.value, 3L, 7L)
  }

  // ---- change notification ----

  /** `body` on a thread of its own, failing the case unless it returns within [[contractWait]]. */
  private def within[A](what: String)(body: => A): A =
    val done = new LinkedBlockingQueue[Either[Throwable, A]]()
    Thread.ofVirtual().start(() => done.put(scala.util.Try(body).toEither)): Unit
    Option(done.poll(contractWait.toMillis, TimeUnit.MILLISECONDS)) match
      case Some(Right(value)) => value
      case Some(Left(thrown)) => fail(s"$what threw: $thrown")
      case None               => fail(s"$what did not return within $contractWait")

  it should "return the events after a sequence from awaitEventsAfter at once, as eventsAfter would" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, events = Vector(s.event("a"), s.event("b"), s.event("c"))).value
    // an hour's timeout: a store that waited although events were there would fail the case
    within("awaitEventsAfter")(s.store.awaitEventsAfter(s.thread, 1L, 10, 1.hour)).value.map(_.seq) shouldBe
      Vector(2L, 3L)
    within("awaitEventsAfter")(s.store.awaitEventsAfter(s.thread, 0L, 2, 1.hour)).value.map(_.seq) shouldBe
      Vector(1L, 2L)
    same(
      within("awaitEventsAfter")(s.store.awaitEventsAfter(s.thread, 0L, 10, 1.hour)).value,
      s.store.eventsAfter(s.thread, 0L, 10).value
    )
  }

  it should "return nothing from awaitEventsAfter once its timeout passes with nothing committed, and report a compacted floor" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, events = Vector(s.event("a"), s.event("b"))).value
    within("awaitEventsAfter")(s.store.awaitEventsAfter(s.thread, 2L, 10, 50.millis)).value shouldBe empty
    within("awaitEventsAfter")(s.store.awaitEventsAfter(newThread(), 0L, 10, 50.millis)).value shouldBe empty
    s.store.compactEvents(s.thread, 2L).value shouldBe (())
    within("awaitEventsAfter")(s.store.awaitEventsAfter(s.thread, 0L, 10, 50.millis)).refused shouldBe
      GraphError.ReplayUnavailable(s.thread.value, 2L)
  }

  it should "return from awaitEventsAfter a commit made while it waits, through this store or another over the same storage" in {
    val s     = Store()
    val other = s.other()
    val token = s.claim("run").value.token
    s.commit(token, events = Vector(s.event("a"))).value

    /** Waits as a subscription's watch does: one call after another, until one returns events. */
    def watching(after: Long): LinkedBlockingQueue[Result[Vector[EventRecord]]] =
      val found = new LinkedBlockingQueue[Result[Vector[EventRecord]]]()
      Thread.ofVirtual().start { () =>
        val deadline = System.nanoTime() + contractWait.toNanos
        var result   = s.store.awaitEventsAfter(s.thread, after, 10, 100.millis)
        while result.exists(_.isEmpty) && System.nanoTime() < deadline do
          result = s.store.awaitEventsAfter(s.thread, after, 10, 100.millis)
        found.put(result)
      }: Unit
      found

    def await(found: LinkedBlockingQueue[Result[Vector[EventRecord]]]): Vector[Long] =
      Option(found.poll(contractWait.toMillis + 1000, TimeUnit.MILLISECONDS))
        .getOrElse(fail("awaitEventsAfter never returned"))
        .value
        .map(_.seq)

    val first = watching(1L)
    Thread.sleep(50)
    other.commit(s.thread, Commit(token, None, Vector.empty, Vector(s.event("b"), s.event("c")))).value
    await(first) shouldBe Vector(2L, 3L)

    val second = watching(3L)
    Thread.sleep(50)
    s.commit(token, events = Vector(s.event("d"))).value
    await(second) shouldBe Vector(4L)
  }

  it should "end an awaitEventsAfter wait when its thread is interrupted, with CancelledError and the flag set" in {
    val s       = Store()
    val outcome = new LinkedBlockingQueue[(Result[Vector[EventRecord]], Boolean)]()
    val waiting = Thread.ofVirtual().start { () =>
      val result = s.store.awaitEventsAfter(s.thread, 0L, 10, 1.hour)
      // `offer`, not `put`, which an interrupted thread could not complete
      outcome.offer(result -> Thread.currentThread().isInterrupted): Unit
    }
    Thread.sleep(50)
    waiting.interrupt()
    val (result, flagged) =
      Option(outcome.poll(contractWait.toMillis, TimeUnit.MILLISECONDS)).getOrElse(fail("the wait did not end"))
    result.refused shouldBe a[CancelledError]
    flagged shouldBe true
  }

  // ---- every call returns within the store's bound (#1919) ----

  it should "return from every call within the bound it documents, also while another connection holds its storage" in {
    val bounded = boundedStore(ManualClock(start))
    assume(bounded.isDefined, "the store gives no bound, nor a way to make its storage busy (boundedStore)")
    val b      = bounded.get
    val store  = b.store
    val thread = newThread()
    val held   = store.claim(thread, ClaimRequest(RunId("holder"), None, ttl)).value
    def event(name: String): EventDraft =
      EventDraft("run", None, None, None, start, RunEvent.Custom(name, 1, ujson.Obj("name" -> name)))
    // writes and reads at once, so that each also waits behind the store's own earlier calls, not only the storage
    val calls: Vector[(String, () => Result[Any])] = Vector(
      "commit"        -> (() => store.commit(thread, Commit(held.token, events = Vector(event("a"))))),
      "commit"        -> (() => store.commit(thread, Commit(held.token, events = Vector(event("b"))))),
      "renew"         -> (() => store.renew(thread, held.token, ttl)),
      "claim"         -> (() => store.claim(newThread(), ClaimRequest(RunId("other"), None, ttl))),
      "claim"         -> (() => store.claim(newThread(), ClaimRequest(RunId("another"), None, ttl))),
      "compactEvents" -> (() => store.compactEvents(thread, 1L)),
      "prune"         -> (() => store.prune(thread, RetentionPolicy(maxCheckpoints = Some(1)))),
      "latest"        -> (() => store.latest(thread)),
      "eventsAfter"   -> (() => store.eventsAfter(thread, 0L, 10)),
      "history"       -> (() => store.history(thread, None, 10))
    )
    val took  = new LinkedBlockingQueue[(String, FiniteDuration)]()
    val busy  = b.busy()
    val ready = new CyclicBarrier(calls.size)
    val callers = calls.map { (name, call) =>
      Thread.ofVirtual().start { () =>
        ready.await(contractWait.toMillis, TimeUnit.MILLISECONDS): Unit
        val began = System.nanoTime()
        call(): Unit
        took.put(name -> (System.nanoTime() - began).nanos)
      }
    }
    callers.foreach(_.join(contractWait.toMillis))
    busy.close()
    val all = Iterator.continually(took.poll()).takeWhile(_ != null).toVector
    all should have size calls.size.toLong
    all.foreach { (name, waited) =>
      withClue(
        s"$name took ${waited.toMillis} ms; its bound is ${b.bound.toMillis} ms, with ${boundGrace.toMillis} ms of grace: "
      ) {
        waited should be <= (b.bound + boundGrace)
      }
    }
    // a call that gave up changed nothing it should not have, and the store is usable again at once
    store.commit(thread, Commit(held.token, events = Vector(event("after")))).value.map(_.event) shouldBe
      Vector(RunEvent.Custom("after", 1, ujson.Obj("name" -> "after")))
    closeExtra(store)
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
    s.store.deleteThread(s.thread, token).value shouldBe (())
    s.historyIds() shouldBe empty
    s.store.checkpoint(s.thread, "c1").value shouldBe None
    // a new thread of the same id may use the ids again
    val again = s.claim("again").value.token
    s.chain(again, Vector("c1"))
    s.historyIds() shouldBe Vector("c1")
  }

  // ---- child threads (design 4.41) ----

  private def originOf(parent: ThreadId, n: Int, depth: Int = 1): RunOrigin =
    RunOrigin(parent, IdempotencyKey(s"call-$n"), org.llm4s.agent.AgentId.unsafe("worker"), start.plusSeconds(n), depth)

  /** Commits a first checkpoint with `origin` on `child`, under a claim of its own, with one event, and releases it. */
  private def childCommitted(
    s: Store,
    child: ThreadId,
    origin: Option[RunOrigin],
    status: CheckpointStatus = CheckpointStatus.Completed,
    tenant: Option[String] = None,
    id: String = "c1"
  ): Unit =
    val claim  = s.claim("child", on = child, tenant = tenant).value
    val parent = s.latestId(child)
    s.commit(
      claim.token,
      Some(s.checkpoint(id, parent, on = child, status = status).withOrigin(origin).withTenantId(tenant)),
      events = Vector(s.event(id, Some(id))),
      on = child
    ).value
    s.store.release(child, claim.token).value

  /**
   * A format-7 checkpoint as the last format-7 build wrote it (`Checkpoint.toJson`, 7623bd442): no `origin` and no
   * `outcome` field.
   */
  private val formatSeven: String =
    """{"formatVersion":7,"id":"c7","parent":null,"threadId":"format-7","runId":"run","status":"Completed","createdAt":"2026-10-09T12:00:00Z","snapshot":{"graphId":"g","graphVersion":"v1","fingerprint":"f","superstep":0,"state":{},"frontier":[],"staticJoins":[],"dynamicJoins":[],"parked":[],"paused":false},"tenantId":"tenant-a","fencingToken":3}"""

  it should "read back a checkpoint's origin and outcome, on the latest, in the history and by id, across a reopen" in {
    val s      = Store()
    val origin = originOf(s.thread, 1)
    val child  = ThreadId(s"${s.thread.value}/~task/worker/1")
    val token  = s.claim("run", on = child).value.token
    s.commit(token, Some(s.checkpoint("c1", None, on = child).withOrigin(origin)), on = child).value
    s.commit(
      token,
      Some(
        s.checkpoint("c2", Some("c1"), on = child, status = CheckpointStatus.Completed)
          .withOrigin(origin)
          .withOutcome(RunOutcome.Blocked("LengthCheck", "too long"))
      ),
      on = child
    ).value
    s.reopened()
    val latest = s.store.latest(child).value.value.checkpoint
    latest.origin shouldBe Some(origin)
    latest.outcome shouldBe Some(RunOutcome.Blocked("LengthCheck", "too long"))
    s.store.history(child, None, 10).value.map(c => (c.id, c.origin, c.outcome)) shouldBe Vector(
      ("c2", Some(origin), Some(RunOutcome.Blocked("LengthCheck", "too long"))),
      ("c1", Some(origin), None)
    )
    s.store.checkpoint(child, "c1").value.value.origin shouldBe Some(origin)
    // a later checkpoint without an outcome clears it, and keeps the origin it carries
    s.commit(token, Some(s.checkpoint("c3", Some("c2"), on = child).withOrigin(origin)), on = child).value
    s.store.latest(child).value.value.checkpoint.outcome shouldBe None
    s.store.checkpoint(child, "c2").value.value.outcome shouldBe Some(RunOutcome.Blocked("LengthCheck", "too long"))
    val invalid = RunOutcome.OutputInvalid(Vector("a", "b", "c", "d", "e", "f"))
    s.commit(
      token,
      Some(s.checkpoint("c4", Some("c3"), on = child, status = CheckpointStatus.Failed).withOutcome(invalid)),
      on = child
    ).value
    s.store.latest(child).value.value.checkpoint.outcome shouldBe Some(invalid)
  }

  it should "list a thread's children oldest first, paged, without a tenant, and never one deleted" in {
    val s        = Store()
    val children = (1 to 4).map(n => ThreadId(s"${s.thread.value}/~task/worker/$n")).toVector
    children.zipWithIndex.foreach { (child, i) =>
      childCommitted(
        s,
        child,
        Some(originOf(s.thread, i + 1)),
        status = if i == 1 then CheckpointStatus.Suspended else CheckpointStatus.Completed,
        tenant = Some("tenant-a")
      )
    }
    // another parent's child and a top-level thread are not listed
    childCommitted(s, ThreadId(s"${s.thread.value}-other/~task/worker/1"), Some(originOf(ThreadId("other"), 1)))
    childCommitted(s, ThreadId(s"${s.thread.value}-top"), None)

    val listed = s.store.children(s.thread, None, 10).value
    listed.map(_.threadId) shouldBe children
    listed.map(_.status)(1) shouldBe CheckpointStatus.Suspended
    listed.head.origin shouldBe originOf(s.thread, 1)
    listed.head.latest shouldBe "c1"
    listed.head.updatedAt shouldBe start
    s.store.children(s.thread, None, 0).value shouldBe empty

    val first = s.store.children(s.thread, None, 3).value
    first.map(_.threadId) shouldBe children.take(3)
    s.store.children(s.thread, first.lastOption.map(_.threadId), 3).value.map(_.threadId) shouldBe children.drop(3)
    s.store.children(s.thread, Some(ThreadId("stranger")), 3).refused shouldBe a[ValidationError]
    s.store.children(s.thread, Some(children(0)), 10).value.map(_.threadId) shouldBe children.drop(1)

    // a child keeps its place as it moves on
    childCommitted(s, children(0), Some(originOf(s.thread, 1)), tenant = Some("tenant-a"), id = "c2")
    s.store.children(s.thread, None, 10).value.map(_.threadId) shouldBe children

    // a deleted child is not listed, and keeps its place as a page boundary
    val claim = s.claim("deleter", on = children(1), tenant = Some("tenant-a")).value
    s.store.deleteThread(children(1), claim.token).value
    s.store.children(s.thread, None, 10).value.map(_.threadId) shouldBe children.patch(1, Nil, 1)
    s.store.children(s.thread, Some(children(1)), 10).value.map(_.threadId) shouldBe children.drop(2)

    s.reopened()
    s.store.children(s.thread, None, 10).value.map(_.threadId) shouldBe children.patch(1, Nil, 1)

    // a new thread under the deleted child's id is the newest child, not in the old one's place
    childCommitted(s, children(1), Some(originOf(s.thread, 2)), tenant = Some("tenant-a"), id = "c3")
    s.store.children(s.thread, None, 10).value.map(_.threadId) shouldBe children.patch(1, Nil, 1) :+ children(1)
    s.reopened()
    s.store.children(s.thread, None, 10).value.map(_.threadId) shouldBe children.patch(1, Nil, 1) :+ children(1)
  }

  it should "keep a child's place when a blocked first turn retracts every checkpoint it had" in {
    val s       = Store()
    val blocked = ThreadId(s"${s.thread.value}/~task/worker/1")
    val sibling = ThreadId(s"${s.thread.value}/~task/worker/2")
    childCommitted(s, blocked, Some(originOf(s.thread, 1)), status = CheckpointStatus.Running)
    childCommitted(s, sibling, Some(originOf(s.thread, 2)))
    // the blocked turn retracts back to the newest settled checkpoint, of which there is none: the history is
    // emptied before the terminal checkpoint is written, but the thread is the same one and keeps its place
    val claim = s.claim("child", on = blocked).value
    val terminal = s
      .checkpoint("c2", s.latestId(blocked), on = blocked, status = CheckpointStatus.Failed)
      .withOrigin(Some(originOf(s.thread, 1)))
    s.store.commit(blocked, Commit(claim.token, Some(terminal), retractTurn = true)).value
    s.store.release(blocked, claim.token).value
    s.store.children(s.thread, None, 10).value.map(_.threadId) shouldBe Vector(blocked, sibling)
    s.reopened()
    s.store.children(s.thread, None, 10).value.map(_.threadId) shouldBe Vector(blocked, sibling)
  }

  it should "stop listing a child whose latest checkpoint names no parent, or another" in {
    val s     = Store()
    val child = ThreadId(s"${s.thread.value}/~task/worker/1")
    childCommitted(s, child, Some(originOf(s.thread, 1)))
    s.store.children(s.thread, None, 10).value.map(_.threadId) shouldBe Vector(child)
    childCommitted(s, child, None, id = "c2")
    s.store.children(s.thread, None, 10).value shouldBe empty
    childCommitted(s, child, Some(originOf(ThreadId("elsewhere"), 1)), id = "c3")
    s.store.children(s.thread, None, 10).value shouldBe empty
    s.store.children(ThreadId("elsewhere"), None, 10).value.map(_.threadId) shouldBe Vector(child)
  }

  it should "read a format-7 checkpoint as a top-level thread with no outcome, listed as nobody's child" in {
    val s       = Store()
    val written = Checkpoint.fromJson(ujson.read(formatSeven)).value
    written.formatVersion shouldBe Checkpoint.CurrentFormat
    written.origin shouldBe None
    written.outcome shouldBe None
    val token = s.claim("seed", tenant = Some("tenant-a")).value.token
    s.commit(token, Some(written.withThreadId(s.thread.value))).value
    s.reopened()
    val read = s.store.latest(s.thread).value.value.checkpoint
    read.origin shouldBe None
    read.outcome shouldBe None
    s.store.children(s.thread, None, 10).value shouldBe empty
  }

  // ---- effect records (design §4.24) ----

  /**
   * An effect record of the task `taskId` of lineage `lineage` under `key`, as a store reads it back; completed
   * with `outcome` when one is given.
   */
  private def effectRecord(
    key: String,
    taskId: String,
    lineage: String,
    outcome: Option[ujson.Value] = None
  ): EffectRecord =
    val json = ujson.Obj(
      "format"    -> EffectRecord.CurrentFormat,
      "key"       -> key,
      "operation" -> "contract.op",
      "scope"     -> "contract-scope",
      "taskId"    -> taskId,
      "lineage"   -> lineage,
      "intent"    -> ujson.Obj("support" -> "unverifiable")
    )
    outcome.foreach(value => json("outcome") = ujson.Obj("value" -> value))
    EffectRecord.fromJson(json).value

  private def keyOf(record: EffectRecord): EffectKey = record.key

  /**
   * A checkpoint at `superstep` whose frontier holds the tasks `frontier` and whose parked continuations stand in
   * for the tasks `parked`, each an id and its lineage.
   */
  private def liveCheckpoint(
    s: Store,
    id: String,
    parent: Option[String],
    superstep: Int,
    frontier: Seq[(String, String)] = Seq.empty,
    parked: Seq[(String, String)] = Seq.empty,
    token: Option[FencingToken] = None
  ): Checkpoint =
    val task = (id: String, lineage: String) =>
      GraphSnapshot.PendingTask(id, "n", VersionedJson(1, ujson.Null), None, None, None, None, lineage = lineage)
    val park = (origin: String, lineage: String) =>
      GraphSnapshot.ParkedContinuation(
        s"i-$origin",
        "n",
        VersionedJson(1, ujson.Null),
        origin,
        "n",
        None,
        None,
        originLineage = lineage
      )
    s.checkpoint(id, parent, token)
      .withSnapshot(
        GraphSnapshot(
          "g",
          "v1",
          "f",
          superstep,
          Map.empty,
          frontier.map(task.tupled).toVector,
          Vector.empty,
          Vector.empty,
          parked.map(park.tupled).toVector,
          false
        )
      )

  private def effects(s: Store, records: EffectRecord*): Vector[Option[EffectRecord]] =
    records.toVector.map(r => s.store.effect(s.thread, keyOf(r)).value)

  it should "keep a commit's effect records, upserted by key and readable by key, and refuse a stale writer's" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(liveCheckpoint(s, "c1", None, 0, Seq("0.0" -> "a")))).value
    val begun = effectRecord("k1", "0.0", "a")
    s.store.commit(s.thread, Commit(token, effects = Vector(begun))).value shouldBe empty
    same(effects(s, begun), Vector(Some(begun)))
    val done = effectRecord("k1", "0.0", "a", Some(ujson.Num(7)))
    s.store.commit(s.thread, Commit(token, effects = Vector(done))).value
    same(effects(s, begun), Vector(Some(done)))
    s.store.effect(s.thread, keyOf(effectRecord("other", "0.0", "a"))).value shouldBe None
    s.store.effect(newThread(), keyOf(begun)).value shouldBe None

    // fenced like any commit: nothing of a stale writer's lands
    s.advance(ttl * 2)
    s.claim("taker").value
    s.store.commit(s.thread, Commit(token, effects = Vector(effectRecord("k2", "0.0", "a")))).refused shouldBe
      a[GraphError.StaleClaim]
    s.store.effect(s.thread, keyOf(effectRecord("k2", "0.0", "a"))).value shouldBe None
    same(effects(s, begun), Vector(Some(done)))
  }

  it should "release, at a commit with a checkpoint, the records of finished tasks only, judged before its own" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(liveCheckpoint(s, "c1", None, 0, Seq("0.0" -> "a", "0.1" -> "b")))).value
    val finished = effectRecord("finished", "0.0", "a")
    val parked   = effectRecord("parked", "0.1", "b")
    val ahead    = effectRecord("ahead", "2.0", "c")
    s.store.commit(s.thread, Commit(token, effects = Vector(finished, parked, ahead))).value
    // a commit with no checkpoint releases nothing
    s.commit(token, writes = Vector(s.write("c1", "0.0"))).value
    same(effects(s, finished, parked, ahead), Vector(Some(finished), Some(parked), Some(ahead)))

    // superstep 1: 0.0 finished; 0.1 parked, so it is live; 2.0 runs ahead of this commit (Async) and is kept
    val next = effectRecord("next", "1.0", "d")
    s.store
      .commit(
        s.thread,
        Commit(
          token,
          Some(liveCheckpoint(s, "c2", Some("c1"), 1, Seq("1.0" -> "d"), parked = Seq("0.1" -> "b"))),
          effects = Vector(next)
        )
      )
      .value
    same(effects(s, finished, parked, ahead, next), Vector(None, Some(parked), Some(ahead), Some(next)))

    // superstep 3: everything before it has finished
    s.commit(token, Some(liveCheckpoint(s, "c3", Some("c2"), 3))).value
    same(effects(s, parked, ahead, next), Vector(None, None, None))
  }

  it should "keep, through a takeover, the records of work after the last durable checkpoint, until it is planned" in {
    // the first run went on past its last durable checkpoint (Async or OnExit) and was taken over: its task 5.0
    // recorded an intent and may still be running. The second run plans that work again; from the same results
    // it is the same task, and the record must answer for it
    val s     = Store()
    val first = s.claim("first").value.token
    s.commit(first, Some(liveCheckpoint(s, "c1", None, 4, Seq("4.0" -> "a")))).value
    val recovered = effectRecord("recovered", "4.0", "a")
    val same5     = effectRecord("same", "5.0", "b")
    val other5    = effectRecord("other", "5.1", "c")
    s.store.commit(s.thread, Commit(first, effects = Vector(recovered, same5, other5))).value
    s.advance(ttl * 2)
    val second = s.claim("second").value.token
    s.commit(second, Some(liveCheckpoint(s, "c2", Some("c1"), 4, Seq("4.0" -> "a")))).value
    same(effects(s, recovered, same5, other5), Vector(Some(recovered), Some(same5), Some(other5)))
    // the first run's late writes are refused: nothing of it lands after the takeover
    s.store.commit(s.thread, Commit(first, effects = Vector(same5))).refused shouldBe a[GraphError.StaleClaim]
    // the second run's plan is durable: 5.0 is the same task, 5.1 was planned as other work, which the record
    // can never answer for, since the superstep that scheduled 5.1 never runs again
    s.commit(second, Some(liveCheckpoint(s, "c3", Some("c2"), 5, Seq("5.0" -> "b", "5.1" -> "z")))).value
    same(effects(s, recovered, same5, other5), Vector(None, Some(same5), None))
  }

  it should "release at a takeover's settled checkpoint the records a stale claimant made at later supersteps" in {
    // the first run went past its last durable checkpoint to superstep 7 (Async or OnExit) and was taken over;
    // the second run took a shorter path and settled at superstep 5. A settled thread is never recovered or
    // resumed, so no task can look up those records again, whatever superstep scheduled them
    for status <- Seq(CheckpointStatus.Completed, CheckpointStatus.Failed) do
      val s     = Store()
      val first = s.claim("first").value.token
      s.commit(first, Some(liveCheckpoint(s, "c1", None, 4, Seq("4.0" -> "a")))).value
      val ahead = Seq(
        effectRecord("five", "5.0", "b"),
        effectRecord("six", "6.0", "c", Some(ujson.Str("out"))),
        effectRecord("seven", "7.0", "d")
      )
      s.store.commit(s.thread, Commit(first, effects = ahead.toVector)).value
      s.advance(ttl * 2)
      val second = s.claim("second").value.token
      s.commit(second, Some(liveCheckpoint(s, "c2", Some("c1"), 4, Seq("4.0" -> "a")))).value
      same(effects(s, ahead*), ahead.toVector.map(Some(_)))
      s.commit(second, Some(liveCheckpoint(s, "c3", Some("c2"), 5).withStatus(status))).value
      withClue(s"at a $status checkpoint: ")(same(effects(s, ahead*), Vector(None, None, None)))
  }

  it should "release at a run's single exit commit what its tasks recorded, keeping a parked one's (OnExit)" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(liveCheckpoint(s, "c1", None, 0, Seq("0.0" -> "a")))).value
    val zero  = effectRecord("zero", "0.0", "a")
    val one   = effectRecord("one", "1.0", "b")
    val asked = effectRecord("asked", "2.0", "c")
    s.store.commit(s.thread, Commit(token, effects = Vector(zero, one, asked))).value
    // the run's intermediate checkpoints were never written: its exit commit suspends at superstep 3
    val exit = liveCheckpoint(s, "c4", Some("c1"), 3, parked = Seq("2.0" -> "c"))
      .withStatus(CheckpointStatus.Suspended)
    s.commit(token, Some(exit)).value
    same(effects(s, zero, one, asked), Vector(None, None, Some(asked)))
  }

  it should "keep effect records through prune, whatever the policy, and across a close and reopen" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(liveCheckpoint(s, "c1", None, 0, Seq("0.0" -> "a")))).value
    val record = effectRecord("k", "0.0", "a", Some(ujson.Str("out")))
    s.store.commit(s.thread, Commit(token, effects = Vector(record))).value
    s.advance(400.days)
    s.store.prune(s.thread, RetentionPolicy().withMaxAge(1.second).withMaxCheckpoints(1).withMaxEvents(0)).value
    same(effects(s, record), Vector(Some(record)))
    s.reopened()
    same(effects(s, record), Vector(Some(record)))
    same(s.other().effect(s.thread, keyOf(record)).value, Some(record))
  }

  it should "release every record at a settled checkpoint, the record of a task it lists as parked included" in {
    // a Block closes the run Failed while a sibling of its superstep parked: nothing can run that sibling again
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(liveCheckpoint(s, "c1", None, 0, Seq("0.0" -> "a", "0.1" -> "b")))).value
    val parked = effectRecord("parked", "0.1", "b")
    s.store.commit(s.thread, Commit(token, effects = Vector(parked))).value
    val closing =
      liveCheckpoint(s, "c2", Some("c1"), 1, parked = Seq("0.1" -> "b")).withStatus(CheckpointStatus.Failed)
    s.store.commit(s.thread, Commit(token, Some(closing), retractTurn = true)).value
    same(effects(s, parked), Vector(None))
  }

  it should "keep an effect record of a thread that has nothing else, until a later claim's commit releases it" in {
    // an effect-only commit on a fresh claim, then the claim released: the thread holds only the record
    val s      = Store()
    val first  = s.claim("first").value.token
    val record = effectRecord("orphan", "0.0", "a")
    s.store.commit(s.thread, Commit(first, effects = Vector(record))).value
    s.store.release(s.thread, first).value
    same(effects(s, record), Vector(Some(record)))
    s.reopened()
    same(effects(s, record), Vector(Some(record)))
    // the next claim's first checkpoint schedules 0.0 as a task of its own lineage: the record answers for none
    val next = s.claim("next").value.token
    s.commit(next, Some(liveCheckpoint(s, "c1", None, 0, Seq("0.0" -> "b")))).value
    same(effects(s, record), Vector(None))
  }

  it should "remove a thread's effect records with the thread, so a new thread of its id has none" in {
    val s     = Store()
    val token = s.claim("run").value.token
    s.commit(token, Some(liveCheckpoint(s, "c1", None, 0, Seq("0.0" -> "a"))), events = Vector(s.event("e"))).value
    val record = effectRecord("k", "0.0", "a")
    s.store.commit(s.thread, Commit(token, effects = Vector(record))).value
    s.store.deleteThread(s.thread, token).value
    s.store.effect(s.thread, keyOf(record)).value shouldBe None
    // and a thread that numbered no event, which leaves no tombstone
    val bare      = newThread()
    val bareToken = s.claim("bare", bare).value.token
    s.commit(bareToken, Some(liveCheckpoint(s, "c1", None, 0, Seq("0.0" -> "a"))), on = bare).value
    s.store.commit(bare, Commit(bareToken, effects = Vector(record))).value
    s.store.deleteThread(bare, bareToken).value
    s.store.effect(bare, keyOf(record)).value shouldBe None
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
      GraphError.ThreadBusy(s.thread.value, latest, GraphError.BusyHolder.Run(RunId("first")))
    second.deleteThread(s.thread, config("second")).refused shouldBe
      GraphError.ThreadBusy(s.thread.value, latest, GraphError.BusyHolder.Run(RunId("first")))
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
    val s = Store()
    val w = Workers()
    // Renewals are counted by when their store call began, not when it returned: one already in flight at
    // the advance read the clock before it, so it extended the claim only from the old time.
    val entered      = new AtomicInteger()
    val advancedAt   = new AtomicInteger(Int.MaxValue)
    val renewedAfter = new AtomicInteger()
    val counted = new Checkpointer:
      def claim(threadId: ThreadId, request: ClaimRequest) = s.store.claim(threadId, request)
      def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) =
        val ordinal = entered.incrementAndGet()
        val result  = s.store.renew(threadId, token, ttl)
        if result.isRight && ordinal > advancedAt.get then renewedAfter.incrementAndGet(): Unit
        result
      def release(threadId: ThreadId, token: FencingToken)                 = s.store.release(threadId, token)
      def commit(threadId: ThreadId, commit: Commit)                       = s.store.commit(threadId, commit)
      def effect(threadId: ThreadId, key: org.llm4s.agent.graph.EffectKey) = s.store.effect(threadId, key)
      def latest(threadId: ThreadId)                                       = s.store.latest(threadId)
      def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) = s.store.eventsAfter(threadId, afterSeq, limit)
      def compactEvents(threadId: ThreadId, beforeSeq: Long)          = s.store.compactEvents(threadId, beforeSeq)
      def deleteThread(threadId: ThreadId, token: FencingToken)       = s.store.deleteThread(threadId, token)
      def history(threadId: ThreadId, before: Option[String], limit: Int) = s.store.history(threadId, before, limit)
      def children(parent: ThreadId, after: Option[ThreadId], limit: Int) = s.store.children(parent, after, limit)
      def checkpoint(threadId: ThreadId, checkpointId: String)            = s.store.checkpoint(threadId, checkpointId)
      def prune(threadId: ThreadId, policy: RetentionPolicy)              = s.store.prune(threadId, policy)
    val first   = GraphRuntime(counted, claims = ClaimPolicy(ttl = ttl, renewEvery = 20.millis))
    val second  = GraphRuntime(s.store, claims = neverRenewed)
    val gate    = w.gate("a")
    val running = first.start(s.thread, w.graph, Vector("a"), config("first")).value
    w.awaitEntered("a")

    s.advance(20.seconds)
    // every renewal numbered above this began after the advance, so read the advanced clock
    advancedAt.set(entered.get)
    eventually(renewedAfter.get > 0)
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

    // the first run wakes up: its commits are refused as stale, so it ends ClaimLost and records nothing more
    val seqs = s.seqs()
    gate.countDown()
    outcome(running).value match
      case RunResult.Failed(_, _: GraphError.ClaimLost) => succeed
      case other                                        => fail(s"the stale run was not refused: $other")
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

  /**
   * Graph `contract-evolving`: `plan` logs its input and suspends at `approve` for `ask`; `approve` logs the answer.
   * `gained` adds key `tally`, which `plan` and `approve` read, and a node nothing reaches; `dropped` removes `log`.
   */
  private def evolving(gained: Boolean = false, dropped: Boolean = false) =
    val log     = StateKey.appending[String]("log")
    val tally   = StateKey.replace[Int]("tally", 41)
    val b       = GraphBuilder("contract-evolving", "v1")
    val logs    = if dropped then Set.empty[StateKey[?, ?]] else Set[StateKey[?, ?]](log)
    val approve = b.declareResume[String, String]("approve")
    def logged(state: ThreadState, line: String): StateUpdate =
      if dropped then StateUpdate.empty
      else StateUpdate.update(log, if gained then s"$line/${state.get(tally).getOrElse(-1)}" else line)
    if gained then
      b.stateKey(tally)
      b.node[Unit]("unreached")((_, _, _) => NodeResult.Continue(Command.empty))
    b.implement(approve.node, writes = logs)((resumed, state, _) =>
      NodeResult.Continue(Command(logged(state, s"approved:${resumed.answer}"), Nil))
    )
    val plan = b.node[String]("plan", writes = logs) { (input, state, _) =>
      if input == "ask" then NodeResult.Suspend(logged(state, input), input, approve)
      else NodeResult.Continue(Command(logged(state, input), Nil))
    }
    b.compile(plan)(state => if dropped then Right(Vector.empty[String]) else state.get(log)).value -> approve

  it should "restore its threads, across a reopen, into a graph that gained a key and a node it cannot reach" in {
    val s            = Store()
    val (before, _)  = evolving()
    val (after, ask) = evolving(gained = true)
    val waiting      = newThread()
    completed(GraphRuntime(s.store).start(s.thread, before, "hello", config("first")).value) shouldBe Vector("hello")
    outcome(GraphRuntime(s.store).start(waiting, before, "ask", config("ask")).value).value shouldBe
      a[RunResult.Suspended]
    s.store.latest(s.thread).value.value.checkpoint.snapshot.shape shouldBe Some(before.shape)

    val reopened = GraphRuntime(s.reopened())
    completed(reopened.start(s.thread, after, "again", config("second")).value) shouldBe Vector("hello", "again/41")
    completed(
      reopened.resume(waiting, after, Map(InterruptId("0.0") -> ask.answer("yes")), config("resumed")).value
    ) shouldBe
      Vector("ask", "approved:yes/41")
    s.store.latest(s.thread).value.value.checkpoint.snapshot.shape shouldBe Some(after.shape)
  }

  it should "refuse a thread to a graph that removed a key the thread holds a value for, leaving it unchanged" in {
    val s           = Store()
    val (before, _) = evolving()
    completed(GraphRuntime(s.store).start(s.thread, before, "hello", config("first")).value)
    val latest = s.latestId()
    GraphRuntime(s.reopened()).start(s.thread, evolving(dropped = true)._1, "again", config("second")).refused shouldBe
      a[GraphError.RestoreRejected]
    s.latestId() shouldBe latest
  }

  it should "delete nothing through a runtime whose claim another runtime took over before the delete" in {
    val s     = Store()
    val w     = Workers()
    val reads = new AtomicInteger()
    val taken = new LinkedBlockingQueue[RunClaim]()
    // the deleting runtime stalls past its claim's ttl after re-reading the thread under its claim
    // (its second `latest`), and meanwhile another runtime takes the thread over and commits
    val stalling = new Checkpointer:
      def claim(threadId: ThreadId, request: ClaimRequest)                    = s.store.claim(threadId, request)
      def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) = s.store.renew(threadId, token, ttl)
      def release(threadId: ThreadId, token: FencingToken)                    = s.store.release(threadId, token)
      def commit(threadId: ThreadId, commit: Commit)                          = s.store.commit(threadId, commit)
      def effect(threadId: ThreadId, key: org.llm4s.agent.graph.EffectKey)    = s.store.effect(threadId, key)
      def latest(threadId: ThreadId) =
        if reads.incrementAndGet() == 2 then
          s.advance(neverRenewed.ttl)
          val other = s.claim("other").value
          s.commit(other.token, Some(s.checkpoint("b1", s.latestId())), events = Vector(s.event("b"))).value
          taken.put(other)
        s.store.latest(threadId)
      def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) = s.store.eventsAfter(threadId, afterSeq, limit)
      def compactEvents(threadId: ThreadId, beforeSeq: Long)          = s.store.compactEvents(threadId, beforeSeq)
      def deleteThread(threadId: ThreadId, token: FencingToken)       = s.store.deleteThread(threadId, token)
      def history(threadId: ThreadId, before: Option[String], limit: Int) = s.store.history(threadId, before, limit)
      def children(parent: ThreadId, after: Option[ThreadId], limit: Int) = s.store.children(parent, after, limit)
      def checkpoint(threadId: ThreadId, checkpointId: String)            = s.store.checkpoint(threadId, checkpointId)
      def prune(threadId: ThreadId, policy: RetentionPolicy)              = s.store.prune(threadId, policy)
    completed(GraphRuntime(s.store, claims = neverRenewed).start(s.thread, w.graph, Vector("a"), config("a")).value)
    val seqs = s.seqs()

    GraphRuntime(stalling, claims = neverRenewed).deleteThread(s.thread, config("deleter")).refused shouldBe
      GraphError.ThreadBusy(s.thread.value, Some("b1"))

    val other = Option(taken.poll(contractWait.toMillis, TimeUnit.MILLISECONDS)).getOrElse(fail("no takeover"))
    s.latestId() shouldBe Some("b1")
    s.seqs() shouldBe seqs :+ (seqs.last + 1)
    s.claim("third").refused shouldBe GraphError.ThreadBusy(
      s.thread.value,
      Some("b1"),
      GraphError.BusyHolder.Run(RunId("other"))
    )
    s.commit(other.token, events = Vector(s.event("b2"))).value.map(_.seq) shouldBe Vector(seqs.last + 2)
  }

  it should "never reveal another tenant's claim on a thread: not through ThreadBusy, RunRecovered.takenOverFrom or ClaimLost" in {
    val s                 = Store()
    val w                 = Workers()
    val owner             = GraphRuntime(s.store, claims = neverRenewed)
    def mine(run: String) = config(run).withTenantId(TenantId("a"))
    // the run id of tenant b, which nothing tenant a is given may contain
    val secret = "b-secret-run"
    def hidden(error: org.llm4s.error.LLMError): Assertion =
      withClue(error.toString)(error.toString.contains(secret) || error.message.contains(secret) shouldBe false)

    // ThreadBusy: tenant b's run has claimed the owner's thread - its admission read the thread before the owner's
    // run released it - and has not checkpointed yet, so the thread's latest checkpoint is still the owner's
    completed(owner.start(s.thread, w.graph, Vector("a"), mine("a1")).value) shouldBe Vector("A")
    val latest  = s.latestId()
    val foreign = s.claim(secret, tenant = Some("b")).value
    val busy    = GraphError.ThreadBusy(s.thread.value, latest, GraphError.BusyHolder.Unnamed)
    owner.start(s.thread, w.graph, Vector("b"), mine("a2")).refused shouldBe busy
    owner.deleteThread(s.thread, mine("a3")).refused shouldBe busy
    s.store.release(s.thread, foreign.token).value
    // a thread with no checkpoint yet: unnamed for another tenant, named for the claim's own
    val fresh = newThread()
    s.claim(secret, fresh, Some("b")).value
    owner.start(fresh, w.graph, Vector("b"), mine("a4")).refused shouldBe
      GraphError.ThreadBusy(fresh.value, None, GraphError.BusyHolder.Unnamed)
    val ours = newThread()
    s.claim("a-elsewhere", ours, Some("a")).value
    owner.start(ours, w.graph, Vector("b"), mine("a5")).refused shouldBe
      GraphError.ThreadBusy(ours.value, None, GraphError.BusyHolder.Run(RunId("a-elsewhere")))

    // RunRecovered.takenOverFrom: tenant b's claim on the owner's incomplete thread lapses unreleased, and the
    // owner's recovery replaces it. A failed run leaves its thread Running with nothing holding it
    val failing = new java.util.concurrent.atomic.AtomicBoolean(true)
    val b       = GraphBuilder("contract-tenant-takeover", "v1")
    val log     = StateKey.appending[String]("log")
    val step = b.node[String]("step", writes = Set(log)) { (input, _, _) =>
      if failing.get then NodeResult.Fail(ValidationError("step", "failed once"))
      else NodeResult.Continue(Command.empty.update(log, input))
    }
    val graph   = b.compile(step)(_.get(log)).value
    val stalled = newThread()
    owner.start(stalled, graph, "x", mine("a6")).value.await().value shouldBe a[RunResult.Failed]
    failing.set(false)
    s.claim(secret, stalled, Some("b")).value
    s.advance(ttl)
    completed(owner.recover(stalled, graph, mine("a7")).value) shouldBe Vector("x")
    val recovered = s.store.eventsAfter(stalled, 0L, 1000).value.map(_.event).collect { case r: RunEvent.RunRecovered =>
      r.takenOverFrom
    }
    recovered shouldBe Vector(None)

    // ClaimLost: tenant b's claim takes over the owner's live run, whose next commit is then refused
    val taken   = newThread()
    val gate    = w.gate("t")
    val running = owner.start(taken, w.graph, Vector("t"), mine("a8")).value
    w.awaitEntered("t")
    s.advance(neverRenewed.ttl)
    val thief = s.claim(secret, taken, Some("b")).value
    thief.takenOverFrom shouldBe None
    gate.countDown()
    outcome(running).value match
      case RunResult.Failed(_, lost: GraphError.ClaimLost) =>
        lost.current shouldBe Some(thief.token)
        hidden(lost)
      case other => fail(s"the run did not lose its claim: $other")
    s.store.release(taken, thief.token).value

    // nothing of tenant b reached any of the owner's logs
    Vector(s.thread, stalled, taken).foreach { t =>
      s.store.eventsAfter(t, 0L, 1000).value.map(_.toString).filter(_.contains(secret)) shouldBe empty
    }
  }

  // ---- subscriptions across runtimes ----

  /**
   * A one-node graph: each run logs its input, emitting a durable custom event and a live progress
   * event on the way; a run whose input has a gate waits for the gate after both.
   */
  final private class Steps:
    val log     = StateKey.appending[String]("log")
    val gates   = new ConcurrentHashMap[String, CountDownLatch]()
    val entered = new ConcurrentHashMap[String, CountDownLatch]()

    def gate(input: String): CountDownLatch =
      val latch = new CountDownLatch(1)
      gates.put(input, latch)
      entered.put(input, new CountDownLatch(1))
      latch

    def awaitEntered(input: String): Assertion =
      entered.get(input).await(contractWait.toMillis, TimeUnit.MILLISECONDS) shouldBe true

    val graph: CompiledGraph[String, Vector[String]] =
      val b = GraphBuilder("contract-steps", "v1")
      val step = b.node[String]("step", writes = Set(log)) { (input, _, context) =>
        context.emit("contract.step", 1, ujson.Str(input))
        context.progress("contract.progress", 1, ujson.Str(input))
        Option(entered.get(input)).foreach(_.countDown())
        Option(gates.remove(input)).foreach(_.await(contractWait.toMillis, TimeUnit.MILLISECONDS))
        NodeResult.Continue(Command.empty.update(log, input))
      }
      b.compile(step)(_.get(log)).value

  /** What a subscription delivered, in order. */
  final private class Seen extends (StreamEvent => Unit):
    private val events                  = new CopyOnWriteArrayList[StreamEvent]()
    def apply(event: StreamEvent): Unit = events.add(event): Unit
    def all: Vector[StreamEvent]        = events.asScala.toVector
    def durable: Vector[EventRecord]    = all.collect { case StreamEvent.Durable(record) => record }
    def live: Vector[StreamEvent.Live]  = all.collect { case live: StreamEvent.Live => live }

    /** Waits until the subscription has delivered `log`'s last event, then checks it delivered exactly `log`. */
    def deliveredExactly(log: Vector[EventRecord]): Assertion =
      eventually(durable.lastOption.map(_.seq) == log.lastOption.map(_.seq) || durable.size > log.size)
      same(durable, log)

  /** A watch quick enough that the cases need not wait long for a store that is polled. */
  private val watching = WatchPolicy(pollInterval = 20.millis)

  private def log(s: Store, after: Long = 0L): Vector[EventRecord] = s.store.eventsAfter(s.thread, after, 1000).value

  private def runtime(store: Checkpointer): GraphRuntime =
    GraphRuntime(store, Clock.systemUTC(), neverRenewed, watching)

  behavior.of("A subscription to a Checkpointer shared by several runtimes")

  it should "receive the durable events another runtime commits, in order, without gap or duplicate" in {
    val s     = Store()
    val st    = Steps()
    val a     = runtime(s.store)
    val b     = runtime(s.other())
    val seenB = Seen()
    val seenA = Seen()
    val inB   = b.subscribe(s.thread)(seenB).value
    val inA   = a.subscribe(s.thread)(seenA).value
    // live, so the commits reach them as they land rather than through their replay
    eventually(a.storeWatches(s.thread) == 1 && b.storeWatches(s.thread) == 1)
    completed(a.start(s.thread, st.graph, "one", config("a1")).value) shouldBe Vector("one")
    completed(a.start(s.thread, st.graph, "two", config("a2")).value) shouldBe Vector("one", "two")
    val committed = log(s)
    committed.map(_.runId).distinct shouldBe Vector("a1", "a2")
    seenB.deliveredExactly(committed)
    seenA.deliveredExactly(committed)
    // live progress is never stored: it reaches the runtime whose run sent it, and no other
    seenA.live.map(_.payload.str) shouldBe Vector("one", "two")
    seenB.live shouldBe empty
    inA.cancel()
    inB.cancel()
  }

  it should "receive another runtime's run without gap or duplicate when it joins mid-run" in {
    val s  = Store()
    val st = Steps()
    val a  = runtime(s.store)
    val b  = runtime(s.other())
    completed(a.start(s.thread, st.graph, "zero", config("a0")).value)
    val gate    = st.gate("one")
    val running = a.start(s.thread, st.graph, "one", config("a1")).value
    st.awaitEntered("one")
    val seen = Seen()
    val sub  = b.subscribe(s.thread)(seen).value
    gate.countDown()
    completed(running)
    completed(a.start(s.thread, st.graph, "two", config("a2")).value)
    seen.deliveredExactly(log(s))
    sub.cancel()
  }

  it should "receive every run in order while runs alternate between the runtimes, in each runtime" in {
    val s     = Store()
    val st    = Steps()
    val a     = runtime(s.store)
    val b     = runtime(s.other())
    val seenA = Seen()
    val seenB = Seen()
    val inA   = a.subscribe(s.thread)(seenA).value
    val inB   = b.subscribe(s.thread)(seenB).value
    eventually(a.storeWatches(s.thread) == 1 && b.storeWatches(s.thread) == 1)
    (1 to 6).foreach { i =>
      val on = if i % 2 == 1 then a else b
      completed(on.start(s.thread, st.graph, s"run-$i", config(s"run-$i")).value)
    }
    val committed = log(s)
    committed.map(_.runId).distinct should have size 6
    seenA.deliveredExactly(committed)
    seenB.deliveredExactly(committed)
    inA.cancel()
    inB.cancel()
  }

  it should "resume from a sequence number after its runtime restarts, with what was committed meanwhile" in {
    val s     = Store()
    val st    = Steps()
    val a     = runtime(s.store)
    val first = Seen()
    val b     = runtime(s.other())
    val sub   = b.subscribe(s.thread)(first).value
    eventually(b.storeWatches(s.thread) == 1)
    completed(a.start(s.thread, st.graph, "one", config("a1")).value)
    first.deliveredExactly(log(s))
    sub.cancel()
    val lastSeq = first.durable.last.seq

    // committed while no subscriber's runtime is up
    completed(a.start(s.thread, st.graph, "two", config("a2")).value)
    // the restarted runtime: a new store over the same storage
    val again     = Seen()
    val restarted = runtime(s.other())
    val resumed   = restarted.subscribe(s.thread, afterSeq = lastSeq)(again).value
    eventually(restarted.storeWatches(s.thread) == 1)
    completed(a.start(s.thread, st.graph, "three", config("a3")).value)
    again.deliveredExactly(log(s, lastSeq))
    resumed.cancel()
  }

  it should "stop watching the store, and deliver nothing more, once cancelled" in {
    val s    = Store()
    val st   = Steps()
    val a    = runtime(s.store)
    val b    = runtime(s.other())
    val seen = Seen()
    val sub  = b.subscribe(s.thread)(seen).value
    eventually(b.storeWatches(s.thread) == 1)
    completed(a.start(s.thread, st.graph, "one", config("a1")).value)
    seen.deliveredExactly(log(s))
    sub.cancel()
    eventually(b.storeWatches(s.thread) == 0)
    b.liveSubscriptions(s.thread) shouldBe 0
    val delivered = seen.all
    completed(a.start(s.thread, st.graph, "two", config("a2")).value)
    Thread.sleep(watching.pollInterval.toMillis * 5)
    same(seen.all, delivered)
  }

  it should "end with ThreadDeleted when another runtime deletes its thread, delivering nothing of a new one" in {
    val s    = Store()
    val st   = Steps()
    val a    = runtime(s.store)
    val b    = runtime(s.other())
    val seen = Seen()
    completed(a.start(s.thread, st.graph, "old", config("a1")).value)
    val old = log(s)
    val sub = b.subscribe(s.thread)(seen).value
    seen.deliveredExactly(old)
    eventually(b.storeWatches(s.thread) == 1)

    a.deleteThread(s.thread, config("deleter")).value shouldBe (())
    completed(a.start(s.thread, st.graph, "new", config("a2")).value) shouldBe Vector("new")

    eventually(seen.all.lastOption.exists(_.isInstanceOf[StreamEvent.Disconnected]))
    seen.all.last shouldBe StreamEvent.Disconnected(old.last.seq, DisconnectReason.ThreadDeleted)
    same(seen.durable, old)
    eventually(b.storeWatches(s.thread) == 0)
    sub.cancel()
    // a subscription from the start follows the new thread, numbered on past the old one
    val again = Seen()
    val next  = b.subscribe(s.thread)(again).value
    again.deliveredExactly(log(s))
    log(s).head.seq shouldBe old.last.seq + 2
    next.cancel()
  }

  it should "end with TenantMismatch, delivering nothing, when another runtime creates the thread for another tenant" in {
    val s    = Store()
    val st   = Steps()
    val a    = runtime(s.store)
    val b    = runtime(s.other())
    val seen = Seen()
    // tenant "mine" watches a thread that does not exist yet, from the runtime over the other store
    val sub = b.subscribe(s.thread, config = config("watcher").withTenantId(TenantId("mine")))(seen).value
    eventually(b.storeWatches(s.thread) == 1)

    completed(a.start(s.thread, st.graph, "theirs", config("a1").withTenantId(TenantId("theirs"))).value)

    eventually(seen.all.lastOption.exists(_.isInstanceOf[StreamEvent.Disconnected]))
    seen.all shouldBe Vector(StreamEvent.Disconnected(0L, DisconnectReason.TenantMismatch))
    eventually(b.storeWatches(s.thread) == 0)
    sub.cancel()
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
      GraphError.ThreadBusy(s.thread.value, live, GraphError.BusyHolder.Run(RunId("second-turn")))
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

object CheckpointerContract:

  /**
   * A store with the bound its documentation gives every call, as the `Checkpointer` contract requires (#1919), for
   * [[CheckpointerContract.boundedStore]]: `bound` is how long any call may take, waits for its storage included,
   * before it returns - with a failure, if it must. `busy` makes the storage busy for writers, as another connection
   * or process holding a write would, until the handle it returns is closed.
   */
  final case class BoundedStore(store: Checkpointer, bound: FiniteDuration, busy: () => AutoCloseable)
