package org.llm4s.agent.testkit

import org.llm4s.agent.graph.*
import org.llm4s.annotation.Experimental
import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.scalatest.{ Assertion, EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant }
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The contract every external target of the harness must meet when `recover` runs its effects
 * again (design §4.24), as a ScalaTest suite: mix it into a spec, implement [[newSubject]] with the
 * target's own guarded code path, and every case below runs against it - once per support class the
 * target offers, one spec each.
 *
 * {{{
 * class StoreFileBackendReplaySpec extends AnyFlatSpec with ReplayContract:
 *   protected def newSubject(): ReplayContract.Subject = StoreFileSubject(InMemoryLongTermStore())
 * }}}
 *
 * Each case runs a one-task graph on a real [[org.llm4s.agent.graph.GraphRuntime GraphRuntime]] whose task performs the subject's two
 * effects (slots 0 and 1), then crashes the run at a point - the store goes down at a chosen effect
 * record write, before or after writing it, or after the task's commit, and every later write fails as
 * in a dead process - moves the store's clock past the claim's lease, and recovers on a new runtime.
 * The task records each slot's outcome, or the guard's refusal, as a tool turns a refusal into a tool
 * error; any other failure fails the task, as a tool's `Fatal` does. For every crash point and the
 * subject's [[org.llm4s.agent.graph.ReplaySupport ReplaySupport]] it checks the target's state, the outcomes against a run that never
 * crashed, that the two effects never collide, and that once the run completes the store holds no
 * effect record and the target no applied key:
 *
 * | Crash point | Transactional | Verifiable | Unverifiable |
 * |---|---|---|---|
 * | before the intent is written | once | once | once |
 * | after the intent, before the effect | once | once | refused, never run (run once if idempotent) |
 * | after the effect, before its outcome is written | once, the target's recorded outcome | once, the presumed outcome | refused (run again if idempotent) |
 * | after the outcome, before the task's commit | once, the stored outcome | same | same |
 * | after the task's commit | not re-run | same | same |
 *
 * and, when another thread changes the target after the effect and before its outcome is written: the
 * recorded outcome with the change left in place (Transactional), [[org.llm4s.agent.graph.GraphError.EffectReplayRefused GraphError.EffectReplayRefused]]
 * (Verifiable), refused (Unverifiable). It also checks that a run left recoverable for a year, its
 * thread pruned by age meanwhile, still replays from its records.
 *
 * Two cases put a stale claimant beside its successor on a real takeover. In each, a run pauses inside the
 * store, its claim expires, and a successor recovers the thread; then the stale run carries on:
 *  - The stale run paused after reading no record. Its successor applied the effect and crashed before
 *    recording the outcome.
 *  - The stale run paused after writing its intent. Its successor replayed the effect and completed.
 *
 * Either way the effect must apply once. That holds only if the target fences itself: a
 * `Transactional` target refuses a token older than the newest it has seen for the key's scope
 * ([[org.llm4s.agent.graph.AppliedKeys]]), and a `Verifiable` effect is a compare-and-set against its
 * plan's `before`.
 *
 * Override [[newCheckpointer]] to run the suite against another store; the default is
 * `InMemoryCheckpointer`. The store must judge claims by the `clock` it is given.
 */
@Experimental
trait ReplayContract extends AnyFlatSpecLike with Matchers with EitherValues with OptionValues:

  /** A fresh target for one case, in its initial state. */
  protected def newSubject(): ReplayContract.Subject

  /** The store the runs use; its claims must expire by `clock`. */
  protected def newCheckpointer(clock: Clock): Checkpointer = InMemoryCheckpointer(clock)

  /** How long a case waits for a run before it fails rather than hangs. */
  protected def contractWait: FiniteDuration = 10.seconds

  import ReplayContract.*

  private val start  = Instant.parse("2026-10-14T09:00:00Z")
  private val claims = ClaimPolicy(ttl = 2.hours, renewEvery = 1.hour)
  private val runs   = new AtomicInteger()

  private val outcomes = StateKey.replace[Map[String, ujson.Value]]("replay-contract-outcomes", Map.empty)

  /** One task performing both effects; a refusal is recorded, any other failure fails the task. */
  private def graph(subject: Subject): CompiledGraph[Unit, Map[String, ujson.Value]] =
    val b = GraphBuilder("replay-contract", "v1")
    val effects = b.node[Unit]("effects", writes = Set(outcomes)) { (_, _, context) =>
      val performed = Slots.foldLeft[Result[Map[String, ujson.Value]]](Right(Map.empty)) { (done, slot) =>
        done.flatMap { recorded =>
          subject.perform(context, slot) match
            case Right(value) => Right(recorded.updated(slot.toString, ujson.Obj("value" -> value)))
            case Left(_: GraphError.EffectReplayRefused) => Right(recorded.updated(slot.toString, Refused))
            case Left(failure)                           => Left(failure)
        }
      }
      performed.fold(NodeResult.Fail(_), done => NodeResult.Continue(Command.empty.update(outcomes, done)))
    }
    b.compile(effects)(_.get(outcomes)).value

  /**
   * A task that performs slot 0 and suspends; its continuation, which stands in for it, performs slot 0
   * again and then slot 1, and records both outcomes.
   */
  private def suspending(subject: Subject): (CompiledGraph[Unit, Map[String, ujson.Value]], ResumeRef[String, String]) =
    val b    = GraphBuilder("replay-contract-suspending", "v1")
    val done = b.declareResume[String, String]("continue")
    def recorded(context: RunContext, slot: Int): Result[ujson.Value] =
      subject.perform(context, slot) match
        case Right(value)                            => Right(ujson.Obj("value" -> value))
        case Left(_: GraphError.EffectReplayRefused) => Right(Refused)
        case Left(failure)                           => Left(failure)
    b.implement(done.node, writes = Set(outcomes)) { (_, _, context) =>
      val both = for
        zero <- recorded(context, 0)
        one  <- recorded(context, 1)
      yield Map("0" -> zero, "1" -> one)
      both.fold(NodeResult.Fail(_), m => NodeResult.Continue(Command.empty.update(outcomes, m)))
    }
    val first = b.node[Unit]("first", writes = Set(outcomes)) { (_, _, context) =>
      recorded(context, 0).fold(NodeResult.Fail(_), _ => NodeResult.Suspend(StateUpdate.empty, "go on?", done))
    }
    (b.compile(first)(_.get(outcomes)).value, done)

  /** A store whose writes die at `point`, and which records every effect key written through it. */
  final private class Crashing(
    val underlying: Checkpointer,
    point: Option[Crash],
    val keys: java.util.Set[EffectKey] = ConcurrentHashMap.newKeySet[EffectKey]()
  ) extends Checkpointer:
    val dead                                            = new AtomicBoolean(false)
    private val begins                                  = new AtomicInteger()
    private val completes                               = new AtomicInteger()
    private val commits                                 = new AtomicInteger()
    private def down: Result[Nothing]                   = Left(ProcessingError("replay-contract", "the store is down"))
    private def alive[A](call: => Result[A]): Result[A] = if dead.get then down else call

    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = alive {
      commit.effects.foreach(r => keys.add(r.key))
      val firstBegin    = commit.effects.exists(!_.completed) && begins.incrementAndGet() == 1
      val firstComplete = commit.effects.exists(_.completed) && completes.incrementAndGet() == 1
      val firstTask     = commit.pendingWrites.nonEmpty && commits.incrementAndGet() == 1
      point match
        case Some(Crash.BeforeIntent) if firstBegin    => dead.set(true); down
        case Some(Crash.AfterIntent) if firstBegin     => written(threadId, commit)
        case Some(Crash.AfterEffect) if firstComplete  => dead.set(true); down
        case Some(Crash.AfterOutcome) if firstComplete => written(threadId, commit)
        case Some(Crash.AfterCommit) if firstTask      => written(threadId, commit)
        case _                                         => underlying.commit(threadId, commit)
    }

    private def written(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
      underlying.commit(threadId, commit).flatMap { _ =>
        dead.set(true)
        down
      }

    def claim(threadId: ThreadId, request: ClaimRequest) = alive(underlying.claim(threadId, request))
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) = alive(
      underlying.renew(threadId, token, ttl)
    )
    def release(threadId: ThreadId, token: FencingToken) = alive(underlying.release(threadId, token))
    def latest(threadId: ThreadId)                       = alive(underlying.latest(threadId))
    def effect(threadId: ThreadId, key: EffectKey)       = alive(underlying.effect(threadId, key))
    def history(threadId: ThreadId, before: Option[String], limit: Int) =
      alive(underlying.history(threadId, before, limit))
    def children(parent: ThreadId, after: Option[ThreadId], limit: Int) =
      alive(underlying.children(parent, after, limit))
    def threads(query: org.llm4s.agent.graph.ThreadQuery) =
      alive(underlying.threads(query))
    def incompleteThreads(
      tenantId: Option[org.llm4s.agent.graph.TenantId],
      after: Option[org.llm4s.agent.graph.ThreadCursor],
      limit: Int
    ) =
      alive(underlying.incompleteThreads(tenantId, after, limit))
    def latestOfRuns(threadId: ThreadId, runIds: Set[org.llm4s.agent.graph.RunId]) =
      alive(underlying.latestOfRuns(threadId, runIds))
    def checkpoint(threadId: ThreadId, checkpointId: String) = alive(underlying.checkpoint(threadId, checkpointId))
    def prune(threadId: ThreadId, policy: RetentionPolicy)   = alive(underlying.prune(threadId, policy))
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) =
      alive(underlying.eventsAfter(threadId, afterSeq, limit))
    def compactEvents(threadId: ThreadId, beforeSeq: Long) = alive(underlying.compactEvents(threadId, beforeSeq))
    def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]) = alive(
      underlying.deleteThread(threadId, token, fence)
    )

  /**
   * The store as a run that is about to lose its claim sees it: the run pauses at `at` - its first effect
   * lookup has returned, or its first intent has been written - until [[resume]], while its claim
   * expires and another run takes the thread over. It then carries on, unaware, as a stalled process does.
   */
  final private class Pausing(underlying: Checkpointer, at: Pause, keys: java.util.Set[EffectKey]) extends Checkpointer:
    val reached         = new java.util.concurrent.CountDownLatch(1)
    private val resumed = new java.util.concurrent.CountDownLatch(1)
    private val lookups = new AtomicInteger()
    private val begins  = new AtomicInteger()
    def resume(): Unit  = resumed.countDown()
    private def pause(): Unit =
      reached.countDown()
      resumed.await(contractWait.toMillis, java.util.concurrent.TimeUnit.MILLISECONDS): Unit

    def effect(threadId: ThreadId, key: EffectKey) =
      val read = underlying.effect(threadId, key)
      if at == Pause.AfterLookup && lookups.incrementAndGet() == 1 then pause()
      read
    def commit(threadId: ThreadId, commit: Commit) =
      commit.effects.foreach(r => keys.add(r.key))
      val written = underlying.commit(threadId, commit)
      if at == Pause.AfterIntent && commit.effects.exists(!_.completed) && begins.incrementAndGet() == 1 then pause()
      written
    def claim(threadId: ThreadId, request: ClaimRequest)                    = underlying.claim(threadId, request)
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) = underlying.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: FencingToken)                    = underlying.release(threadId, token)
    def latest(threadId: ThreadId)                                          = underlying.latest(threadId)
    def history(threadId: ThreadId, before: Option[String], limit: Int) =
      underlying.history(threadId, before, limit)
    def children(parent: ThreadId, after: Option[ThreadId], limit: Int) =
      underlying.children(parent, after, limit)
    def threads(query: org.llm4s.agent.graph.ThreadQuery) =
      underlying.threads(query)
    def incompleteThreads(
      tenantId: Option[org.llm4s.agent.graph.TenantId],
      after: Option[org.llm4s.agent.graph.ThreadCursor],
      limit: Int
    ) =
      underlying.incompleteThreads(tenantId, after, limit)
    def latestOfRuns(threadId: org.llm4s.agent.graph.ThreadId, runIds: Set[org.llm4s.agent.graph.RunId]) =
      underlying.latestOfRuns(threadId, runIds)
    def checkpoint(threadId: ThreadId, checkpointId: String) = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy)   = underlying.prune(threadId, policy)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]) =
      underlying.deleteThread(threadId, token, fence)

  /**
   * A run that loses its claim while it pauses at `at`, and a successor that takes the thread over and
   * crashes at `crash` (or completes); then the stale run carries on to its end, and, if the successor
   * crashed, a third run recovers the thread. The outcomes are the run that completed.
   */
  private def staleClaimant(at: Pause, crash: Option[Crash]): Ran =
    val subject = newSubject()
    val clock   = ManualClock(start)
    val base    = newCheckpointer(clock)
    val keys    = ConcurrentHashMap.newKeySet[EffectKey]()
    val thread  = ThreadId(s"replay-contract-${java.util.UUID.randomUUID()}")
    val g       = graph(subject)
    val stale   = Pausing(base, at, keys)
    val first   = GraphRuntime(stale, clock, claims).start(thread, g, (), config("stale")).value
    stale.reached.await(contractWait.toMillis, java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe true
    clock.advance(claims.ttl + 1.minute)
    val successor = Crashing(base, crash, keys)
    val second    = GraphRuntime(successor, clock, claims).recover(thread, g, config("successor"))
    val outcomes = crash match
      case None => Some(completed(second))
      case Some(_) =>
        awaitRun(second.value) shouldBe a[RunResult.Failed]
        None
    // the stale run carries on: whatever it does now must neither undo nor repeat its successor's work
    stale.resume()
    awaitRun(first) shouldBe a[RunResult.Failed]
    val last = Crashing(base, None, keys)
    outcomes match
      case Some(done) => Ran(done, subject, last, thread)
      case None =>
        clock.advance(claims.ttl + 1.minute)
        Ran(completed(GraphRuntime(last, clock, claims).recover(thread, g, config("recover"))), subject, last, thread)

  /** What one case leaves: the outcome per slot, and the target. */
  final private case class Ran(outcomes: Map[String, ujson.Value], subject: Subject, store: Crashing, thread: ThreadId)

  private def completed(handle: Result[RunHandle[Map[String, ujson.Value]]]): Map[String, ujson.Value] =
    awaitRun(handle.value) match
      case RunResult.Completed(_, output, _) => output
      case other                             => fail(s"the run did not complete: $other")

  /** The run's result, awaited on a thread of its own so that a hung run fails the case after [[contractWait]]. */
  private def awaitRun[O](handle: RunHandle[O]): RunResult[O] =
    val done = new java.util.concurrent.LinkedBlockingQueue[Result[RunResult[O]]]()
    Thread.ofVirtual().start(() => done.offer(handle.await()): Unit)
    Option(done.poll(contractWait.toMillis, java.util.concurrent.TimeUnit.MILLISECONDS))
      .getOrElse(fail(s"the run did not end within $contractWait"))
      .fold(error => fail(s"awaiting the run failed: ${error.message}"), identity)

  private def config(name: String): RunConfig = RunConfig().withRunId(RunId(s"$name-${runs.incrementAndGet()}"))

  /**
   * Runs the graph on a fresh subject, crashing at `point`; then `between` (the subject and the clock),
   * a move of the store's clock past the claim's lease, and recovery on a new runtime.
   */
  private def crashAndRecover(
    point: Option[Crash],
    between: (Subject, ManualClock, Checkpointer, ThreadId) => Unit = (_, _, _, _) => (),
    durability: Durability = Durability.Sync
  ): Ran =
    val subject = newSubject()
    val clock   = ManualClock(start)
    val store   = Crashing(newCheckpointer(clock), point)
    val thread  = ThreadId(s"replay-contract-${java.util.UUID.randomUUID()}")
    val g       = graph(subject)
    val first   = GraphRuntime(store, clock, claims).start(thread, g, (), config("first"), durability)
    point match
      case None =>
        Ran(completed(first), subject, store, thread)
      case Some(_) =>
        // the crashed run ends: its store is down, so it can write nothing more
        awaitRun(first.value) shouldBe a[RunResult.Failed]
        store.dead.get shouldBe true
        between(subject, clock, store.underlying, thread)
        clock.advance(claims.ttl + 1.minute)
        val recovered =
          GraphRuntime(store.underlying, clock, claims).recover(thread, g, config("recover"), durability)
        Ran(completed(recovered), subject, store, thread)

  private lazy val clean: Map[String, ujson.Value] =
    val ran = crashAndRecover(None)
    ran.outcomes.keySet shouldBe Slots.map(_.toString).toSet
    ran.outcomes

  private def value(slot: Int): ujson.Value = clean(slot.toString)

  private def once(ran: Ran, slot: Int): Assertion = ran.subject.observe(slot).value shouldBe ran.subject.once(slot)

  /** No effect record left in the store, and no applied key in the target: the window is closed. */
  private def released(ran: Ran): Assertion =
    ran.store.keys.asScala.toVector.flatMap(key => ran.store.underlying.effect(ran.thread, key).value) shouldBe empty
    ran.subject.appliedKeys shouldBe 0

  private def same(ran: Ran, slot: Int): Assertion =
    if ran.outcomes(slot.toString) == value(slot) then succeed
    else fail(s"slot $slot replayed ${ran.outcomes(slot.toString)}, not ${value(slot)}")

  private def refused(ran: Ran, slot: Int): Assertion =
    if ran.outcomes(slot.toString) == Refused then succeed
    else fail(s"slot $slot was not refused: ${ran.outcomes(slot.toString)}")

  /** Slot 0's outcome when its effect began, and may have run, before a crash or takeover. */
  private def begunOutcome(ran: Ran): Assertion =
    if support == ReplaySupport.Unverifiable && !idempotent then refused(ran, 0) else same(ran, 0)

  private lazy val support    = newSubject().support
  private lazy val idempotent = newSubject().idempotent

  behavior.of(s"A replayable target (${newSubject().name})")

  it should "perform each effect once in a run that never crashes, the two never colliding" in {
    val ran = crashAndRecover(None)
    Slots.foreach(slot => once(ran, slot))
    ran.store.keys.size shouldBe Slots.size
    ran.outcomes.keySet shouldBe Slots.map(_.toString).toSet
    released(ran)
  }

  it should "perform an effect once when the run crashed before writing its intent" in {
    val ran = crashAndRecover(Some(Crash.BeforeIntent))
    Slots.foreach { slot =>
      once(ran, slot)
      same(ran, slot)
    }
    released(ran)
  }

  it should "run an effect whose intent was written but which never ran: once, or refused if it cannot be checked" in {
    val ran = crashAndRecover(Some(Crash.AfterIntent))
    if support == ReplaySupport.Unverifiable && !idempotent then
      refused(ran, 0)
      ran.subject.observe(0).value shouldBe ran.subject.initial(0)
    else
      once(ran, 0)
      same(ran, 0)
    once(ran, 1)
    same(ran, 1)
    released(ran)
  }

  it should "not perform again an effect that ran before its outcome was written, replaying its outcome" in {
    val ran = crashAndRecover(Some(Crash.AfterEffect))
    once(ran, 0)
    if support == ReplaySupport.Unverifiable && !idempotent then refused(ran, 0) else same(ran, 0)
    once(ran, 1)
    same(ran, 1)
    released(ran)
  }

  it should "replay the stored outcome of an effect whose outcome was written before the crash" in {
    val ran = crashAndRecover(Some(Crash.AfterOutcome))
    Slots.foreach { slot =>
      once(ran, slot)
      same(ran, slot)
    }
    released(ran)
  }

  it should "not run a task again once its result was committed" in {
    val ran = crashAndRecover(Some(Crash.AfterCommit))
    Slots.foreach { slot =>
      once(ran, slot)
      same(ran, slot)
    }
    released(ran)
  }

  it should "behave as its support class says when another thread changed the target after the effect" in {
    val changed = new java.util.concurrent.atomic.AtomicReference[String]()
    val ran = crashAndRecover(
      Some(Crash.AfterEffect),
      (subject, _, _, _) =>
        subject.interfere(0)
        changed.set(subject.observe(0).value)
    )
    support match
      case ReplaySupport.Transactional =>
        same(ran, 0)
        ran.subject.observe(0).value shouldBe changed.get
      case ReplaySupport.Verifiable =>
        refused(ran, 0)
        ran.subject.observe(0).value shouldBe changed.get
      case ReplaySupport.Unverifiable =>
        if !idempotent then
          refused(ran, 0)
          ran.subject.observe(0).value shouldBe changed.get
    once(ran, 1)
    same(ran, 1)
    released(ran)
  }

  it should "replay the stored outcome, never touching the target, when it changed after the outcome was written" in {
    val changed = new java.util.concurrent.atomic.AtomicReference[String]()
    val ran = crashAndRecover(
      Some(Crash.AfterOutcome),
      (subject, _, _, _) =>
        subject.interfere(0)
        changed.set(subject.observe(0).value)
    )
    same(ran, 0)
    ran.subject.observe(0).value shouldBe changed.get
    released(ran)
  }

  it should "not let a run that lost its claim after finding no record undo its successor's effect" in {
    // the stale run read no record of slot 0, then stalled; its successor applied the effect and crashed
    // before recording the outcome; the stale run then carries on as if nothing had happened
    val ran = staleClaimant(Pause.AfterLookup, Some(Crash.AfterEffect))
    once(ran, 0)
    begunOutcome(ran)
    once(ran, 1)
    same(ran, 1)
    released(ran)
  }

  it should "not let a run that lost its claim with its effect under way apply it after its successor" in {
    // the stale run recorded its intent for slot 0 and stalled before the effect; its successor replays
    // the effect and completes the run; the stale run's effect then goes ahead
    val ran = staleClaimant(Pause.AfterIntent, None)
    once(ran, 0)
    begunOutcome(ran)
    once(ran, 1)
    same(ran, 1)
    released(ran)
  }

  it should "replay, in a continuation of the task, the effect the task performed before it suspended" in {
    // a hook that asks runs again with its answer as a continuation standing in for its task: same keys
    val subject      = newSubject()
    val clock        = ManualClock(start)
    val keys         = ConcurrentHashMap.newKeySet[EffectKey]()
    val store        = Crashing(newCheckpointer(clock), None, keys)
    val thread       = ThreadId(s"replay-contract-${java.util.UUID.randomUUID()}")
    val (g, proceed) = suspending(subject)
    val runtime      = GraphRuntime(store, clock, claims)
    val parked = awaitRun(runtime.start(thread, g, (), config("suspending")).value) match
      case s: RunResult.Suspended => s
      case other                  => fail(s"the run did not suspend: $other")
    val answer = Map(parked.interrupts.head.id -> proceed.answer("yes"))
    val ran    = Ran(completed(runtime.resume(thread, g, answer, config("continued"))), subject, store, thread)
    once(ran, 0)
    same(ran, 0)
    once(ran, 1)
    same(ran, 1)
    keys.size shouldBe Slots.size
    released(ran)
  }

  for durability <- Vector(Durability.Async, Durability.OnExit) do
    it should s"replay, under $durability durability, an effect its run recorded before it crashed" in {
      // the task's frontier is durable from the run's claim, so its records belong to a task a recovery runs
      Vector(Crash.AfterIntent, Crash.AfterEffect).foreach { point =>
        val ran = crashAndRecover(Some(point), durability = durability)
        if point == Crash.AfterIntent && support == ReplaySupport.Unverifiable && !idempotent then
          refused(ran, 0)
          ran.subject.observe(0).value shouldBe ran.subject.initial(0)
        else
          once(ran, 0)
          if support == ReplaySupport.Unverifiable && !idempotent then refused(ran, 0) else same(ran, 0)
        once(ran, 1)
        same(ran, 1)
        released(ran)
      }
    }

  it should "perform its effects anew on a thread deleted and created again, never answered by the first's evidence" in {
    // the first thread crashed after its effect, leaving its intent - and a transactional target its applied key
    val subject = newSubject()
    val clock   = ManualClock(start)
    val keys    = ConcurrentHashMap.newKeySet[EffectKey]()
    val base    = newCheckpointer(clock)
    val store   = Crashing(base, Some(Crash.AfterEffect), keys)
    val thread  = ThreadId(s"replay-contract-${java.util.UUID.randomUUID()}")
    val g       = graph(subject)
    awaitRun(GraphRuntime(store, clock, claims).start(thread, g, (), config("first")).value) shouldBe
      a[RunResult.Failed]
    clock.advance(claims.ttl + 1.minute)
    val runtime = GraphRuntime(Crashing(base, None, keys), clock, claims)
    runtime.deleteThread(thread, config("delete")).value
    val again = completed(runtime.start(thread, g, (), config("again")))
    // a new thread's first task has the root lineage of a new claim: new keys, so its effects run, never replayed
    Slots.foreach(slot => if again(slot.toString) == Refused then fail(s"slot $slot was refused on the new thread"))
    if !idempotent then subject.observe(0).value should not be subject.once(0)
    once(Ran(again, subject, Crashing(base, None, keys), thread), 1) // slot 1 never ran on the first thread
    keys.asScala.toVector.flatMap(key => base.effect(thread, key).value) shouldBe empty
  }

  it should "replay from its records a run left recoverable for a year, its thread pruned by age meanwhile" in {
    val ran = crashAndRecover(
      Some(Crash.AfterEffect),
      (_, clock, store, thread) =>
        clock.advance(365.days)
        store.prune(thread, RetentionPolicy().withMaxAge(1.day)).value
    )
    once(ran, 0)
    if support == ReplaySupport.Unverifiable && !idempotent then refused(ran, 0) else same(ran, 0)
    released(ran)
  }

@Experimental
object ReplayContract:

  /** The effect slots each case performs, in one task. */
  val Slots: Vector[Int] = Vector(0, 1)

  /** What the contract's task records for a slot the guard refused. */
  private val Refused: ujson.Value = ujson.Obj("refused" -> true)

  /**
   * An external target under test, in a state of its own: a fresh one per case. It performs effect
   * `slot` (0 or 1) on a part of the target of its own - a file each, a store key each - through the
   * target's own guarded code path, as a tool or hook of the harness would.
   */
  trait Subject:

    /** Names the target in the suite's output. */
    def name: String

    /** The support class this subject exercises. */
    def support: ReplaySupport

    /** Whether the effect may run again (the tool's `ToolHints.idempotent`); only `Unverifiable` reads it. */
    def idempotent: Boolean = false

    /**
     * Performs effect `slot` under `context` through the target's guarded code path - building its key with
     * `context.effectKey` - and returns its outcome as JSON, or the guard's `Left`.
     */
    def perform(context: RunContext, slot: Int): Result[ujson.Value]

    /** The observable state of slot `slot`'s part of the target. */
    def observe(slot: Int): Result[String]

    /** What [[observe]] shows before the effect. */
    def initial(slot: Int): String

    /** What [[observe]] shows once the effect has been applied exactly once. */
    def once(slot: Int): String

    /** Changes slot `slot`'s part of the target as another thread would. */
    def interfere(slot: Int): Unit

    /** How many applied keys a `Transactional` target still holds; `0` for other targets. */
    def appliedKeys: Int = 0

  /** Where a case crashes the run that performs the effects. */
  private enum Crash:
    case BeforeIntent, AfterIntent, AfterEffect, AfterOutcome, AfterCommit

  /** Where a run about to lose its claim stalls. */
  private enum Pause:
    case AfterLookup, AfterIntent
