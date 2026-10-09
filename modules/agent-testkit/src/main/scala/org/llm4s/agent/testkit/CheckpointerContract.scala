package org.llm4s.agent.testkit

import org.llm4s.agent.graph.*
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.{ Assertion, EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant }
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
 *    back as written; and claims and fencing - one live claim per thread, tokens strictly
 *    increasing, takeover once a claim has expired by the store's clock, renewal and release by the
 *    current token only, and every commit refused with [[GraphError.StaleClaim]] unless it carries
 *    the current token - including from many threads at once.
 *  - '''Two runtimes over one store''': a second [[GraphRuntime]] is refused a thread whose run is
 *    live in the first ([[GraphError.ThreadBusy]] naming the holder); a run that keeps renewing is
 *    not taken over; once a claim expires, the second runtime recovers the thread without re-running
 *    the first run's completed tasks, and the first run's later commits are refused, so it fails
 *    with [[GraphError.CheckpointWriteFailed]] and leaves nothing in the thread; and of several
 *    runtimes recovering one thread at once, exactly one is admitted.
 *
 * Claim expiry is judged by the store's clock, so the store under test must use the `clock` it is
 * given for that (and may use it for nothing else). The suite moves a [[ManualClock]]; it never
 * waits for real time to pass a claim's `ttl`. Each case gets a new store from [[newCheckpointer]]
 * and uses its own thread ids; the store need not be empty of other threads.
 */
trait CheckpointerContract extends AnyFlatSpecLike with Matchers with EitherValues with OptionValues:

  /**
   * A store for one test case, whose claims expire by `clock`. It may share a backing database with
   * other cases: each case uses thread ids of its own.
   */
  protected def newCheckpointer(clock: Clock): Checkpointer

  /** How long a case waits for a run or a background step before it fails rather than hangs. */
  protected def contractWait: FiniteDuration = 10.seconds

  private val start     = Instant.parse("2026-10-09T12:00:00Z")
  private val ttl       = 30.seconds
  private val threadIds = new AtomicInteger()
  private def newThread(): ThreadId = ThreadId(
    s"contract-${java.util.UUID.randomUUID()}-${threadIds.incrementAndGet()}"
  )

  final private class Store:
    val clock: ManualClock  = ManualClock(start)
    val store: Checkpointer = newCheckpointer(clock)
    val thread: ThreadId    = newThread()

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
      on: ThreadId = thread
    ): Checkpoint =
      Checkpoint(
        Checkpoint.CurrentFormat,
        id,
        parent,
        on.value,
        "run",
        CheckpointStatus.Running,
        start,
        GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false),
        None,
        token
      )

    def event(name: String): EventDraft =
      EventDraft("run", None, None, None, start, RunEvent.Custom(name, 1, ujson.Obj("name" -> name)))

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
    granted.expiresAt shouldBe start.plusSeconds(30)
    s.claim("second").refused shouldBe GraphError.ThreadBusy(s.thread.value, None, Some("first"))
    // the same holder is refused too: a claim is not re-entrant
    s.claim("first").refused shouldBe GraphError.ThreadBusy(s.thread.value, None, Some("first"))
    s.clock.advance(ttl - 1.milli)
    s.claim("second").refused shouldBe a[GraphError.ThreadBusy]
    s.commit(granted.token, Some(s.checkpoint("c1", None))).value
    s.claim("second").refused shouldBe GraphError.ThreadBusy(s.thread.value, Some("c1"), Some("first"))
    s.claim("third", newThread()).value.holder shouldBe RunId("third")
  }

  it should "let an expired claim be taken over with a greater token, and fence off its old holder" in {
    val s     = Store()
    val first = s.claim("first").value
    s.commit(first.token, Some(s.checkpoint("c1", None)), events = Vector(s.event("a"))).value
    s.clock.advance(ttl)
    val taken = s.claim("second").value
    taken.holder shouldBe RunId("second")
    taken.token.value should be > first.token.value
    taken.expiresAt shouldBe start.plusSeconds(60)

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
    s.clock.advance(ttl)
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
    s.clock.advance(ttl + 5.seconds)
    s.commit(first.token, Some(s.checkpoint("c1", None)), events = Vector(s.event("late"))).value.map(_.seq) shouldBe
      Vector(1L)
    val renewed = s.store.renew(s.thread, first.token, ttl).value
    renewed.token shouldBe first.token
    renewed.holder shouldBe RunId("first")
    renewed.expiresAt shouldBe start.plusSeconds(65)
    s.claim("second").refused shouldBe a[GraphError.ThreadBusy]
  }

  it should "extend a claim on renewal, by the store's clock" in {
    val s     = Store()
    val first = s.claim("first").value
    s.clock.advance(20.seconds)
    s.store.renew(s.thread, first.token, ttl).value.expiresAt shouldBe start.plusSeconds(50)
    s.clock.advance(20.seconds) // 40s after the claim, 20s after the renewal
    s.claim("second").refused shouldBe GraphError.ThreadBusy(s.thread.value, None, Some("first"))
    s.clock.advance(10.seconds)
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
        case _ => s.clock.advance(ttl)
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
    val first   = GraphRuntime(counted, claims = ClaimPolicy(ttl = ttl, renewEvery = 20.millis))
    val second  = GraphRuntime(s.store, claims = neverRenewed)
    val gate    = w.gate("a")
    val running = first.start(s.thread, w.graph, Vector("a"), config("first")).value
    w.awaitEntered("a")

    s.clock.advance(20.seconds)
    val before = renewed.get
    eventually(renewed.get > before)
    s.clock.advance(20.seconds) // past the first claim's expiry, not the renewed one's
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
    s.clock.advance(neverRenewed.ttl)
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
