package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneOffset }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration.*

import GraphTestSupport.*

/**
 * The runtime's side of replay (design §4.24): records written under the run's claim, kept while their task
 * can run again - across a late `Async` commit, a suspension, `updateState` - and released by the commit that
 * ends that, by a Block, or with the thread; a fork's re-run is a new effect.
 */
class EffectReplayRuntimeSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val thread = ThreadId("t")
  private val other  = ThreadId("fork")
  private val log    = StateKey.appending[String]("log")

  /** An unverifiable effect that counts its applications; `key` is the last one it used. */
  final private class Effect {
    val applied = new AtomicInteger()
    val key     = new AtomicReference[EffectKey]()
    def perform(context: RunContext, during: () => Unit = () => ()): Result[Int] = {
      val k = context.effectKey("count.add", "counter")
      key.set(k)
      ReplayGuard.unverifiable[Int](context, k, idempotent = false) {
        during()
        Right(applied.incrementAndGet())
      }
    }
  }

  private def record(store: Checkpointer, on: ThreadId, key: EffectKey): Option[EffectRecord] =
    store.effect(on, key).value

  "A run under Async" should "keep the record of a task running ahead of a commit that lands late" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val effect  = new Effect
    val running = new CountDownLatch(1)
    val checked = new CountDownLatch(1)
    val kept    = new AtomicReference[Option[EffectRecord]]()
    // the writer holds the commit of superstep 1 until the task of superstep 2 is mid-effect, and that of
    // superstep 2 until the record has been read with only superstep 1 committed
    runtime.committing = (_, commit) =>
      if Thread.currentThread().getName.startsWith("llm4s-graph-writer-") then
        commit.checkpoint.map(_.snapshot.superstep) match {
          case Some(1) => running.await(5, TimeUnit.SECONDS): Unit
          case Some(2) => checked.await(5, TimeUnit.SECONDS): Unit
          case _       => ()
        }
    val b = GraphBuilder("async-ahead", "v1")
    val c = b.node[Unit]("c", writes = Set(log)) { (_, _, context) =>
      NodeResult.fromResult(
        effect
          .perform(
            context,
            during = () => {
              running.countDown()
              // superstep 1 lands: the record of this task, scheduled at superstep 2, must survive it
              val deadline = System.nanoTime() + 5.seconds.toNanos
              while store.latest(thread).value.forall(_.checkpoint.snapshot.superstep < 1) && System
                  .nanoTime() < deadline
              do Thread.sleep(5)
              kept.set(record(store, thread, effect.key.get))
              checked.countDown()
            }
          )
          .map(n => Command.empty.update(log, s"c:$n"))
      )
    }
    val bNode = b.node[Unit]("b", writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "b")))
    val a     = b.node[Unit]("a", writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "a")))
    b.edge(a, bNode)
    b.edge(bNode, c)
    val graph = b.compile(a)(_.get(log)).value

    runtime.start(thread, graph, (), durability = Durability.Async).awaited.value.completed._2 shouldBe
      Vector("a", "b", "c:1")
    kept.get.value.taskId shouldBe TaskId("2.0")
    // the run's last commit ends the window
    record(store, thread, effect.key.get) shouldBe None
  }

  /** `act` performs the effect and suspends; `review` performs it again under the same call key, then logs. */
  final private class Asking(effect: Effect) {
    private val call = IdempotencyKey("call-1")
    private def perform(context: RunContext): Result[Int] = {
      val k = context.effectKeyUnder(call, "count.add", Seq("counter"))
      effect.key.set(k)
      ReplayGuard.unverifiable[Int](context, k, idempotent = false)(Right(effect.applied.incrementAndGet()))
    }
    val (graph, review) = {
      val b      = GraphBuilder("asking", "v1")
      val review = b.declareResume[String, String]("review")
      b.implement(review.node, writes = Set(log)) { (resumed, _, context) =>
        NodeResult.fromResult(perform(context).map(n => Command.empty.update(log, s"${resumed.answer}:$n")))
      }
      val act = b.node[Unit]("act", writes = Set(log)) { (_, _, context) =>
        perform(context).fold(
          NodeResult.Fail(_),
          n => NodeResult.Suspend(StateUpdate.update(log, s"act:$n"), "ok?", review)
        )
      }
      (b.compile(act)(_.get(log)).value, review)
    }
  }

  "A suspended task's records" should "survive the suspension, so the call run again after the answer replays them" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val effect  = new Effect
    val asking  = new Asking(effect)
    val parked  = runtime.start(thread, asking.graph, ()).awaited.value.suspended
    record(store, thread, effect.key.get).value.completed shouldBe true
    val answer = Map(parked.interrupts.head.id -> asking.review.answer("yes"))
    runtime.resume(thread, asking.graph, answer).awaited.value.completed._2 shouldBe Vector("act:1", "yes:1")
    effect.applied.get shouldBe 1
    record(store, thread, effect.key.get) shouldBe None
  }

  /**
   * A hook-style effect keyed by `RunContext.effectKey` (so by `taskKey`): `act` performs it and suspends;
   * the continuation `review` performs it again, then fails while `failing`.
   */
  final private class HookAsking(effect: Effect) {
    val failing = new AtomicBoolean(false)
    val keys    = new java.util.concurrent.ConcurrentLinkedQueue[EffectKey]()
    private def perform(context: RunContext): Result[Int] = {
      val result = effect.perform(context)
      keys.add(effect.key.get)
      result
    }
    val (graph, review) = {
      val b      = GraphBuilder("hook-asking", "v1")
      val review = b.declareResume[String, String]("review")
      b.implement(review.node, writes = Set(log)) { (resumed, _, context) =>
        perform(context) match {
          case Left(error)             => NodeResult.Fail(error)
          case Right(_) if failing.get => NodeResult.Fail(ValidationError("review", "failed after its effect"))
          case Right(n)                => continue(Command.empty.update(log, s"${resumed.answer}:$n"))
        }
      }
      val act = b.node[Unit]("act", writes = Set(log)) { (_, _, context) =>
        perform(context).fold(
          NodeResult.Fail(_),
          n => NodeResult.Suspend(StateUpdate.update(log, s"act:$n"), "ok?", review)
        )
      }
      (b.compile(act)(_.get(log)).value, review)
    }
  }

  "A hook's effect key" should "stay the task's across a suspension, so the continuation replays the effect" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val effect  = new Effect
    val asking  = new HookAsking(effect)
    val parked  = runtime.start(thread, asking.graph, ()).awaited.value.suspended
    val answer  = Map(parked.interrupts.head.id -> asking.review.answer("yes"))
    runtime.resume(thread, asking.graph, answer).awaited.value.completed._2 shouldBe Vector("act:1", "yes:1")
    effect.applied.get shouldBe 1
    asking.keys.toArray.distinct should have size 1
    record(store, thread, effect.key.get) shouldBe None
  }

  it should "stay the same when the continuation is recovered after a failure" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val effect  = new Effect
    val asking  = new HookAsking(effect)
    val parked  = runtime.start(thread, asking.graph, ()).awaited.value.suspended
    asking.failing.set(true)
    runtime
      .resume(thread, asking.graph, Map(parked.interrupts.head.id -> asking.review.answer("yes")))
      .awaited
      .value
      .failed
    record(store, thread, effect.key.get).value.completed shouldBe true
    asking.failing.set(false)
    runtime.recover(thread, asking.graph).awaited.value.completed._2 shouldBe Vector("act:1", "yes:1")
    effect.applied.get shouldBe 1
    asking.keys.toArray.distinct should have size 1
  }

  it should "be another in a fork resumed from the suspension, whose continuation is an effect of its own" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val effect  = new Effect
    val asking  = new HookAsking(effect)
    val parked  = runtime.start(thread, asking.graph, ()).awaited.value.suspended
    val answer  = Map(parked.interrupts.head.id -> asking.review.answer("yes"))
    runtime.fork(thread, store.latest(thread).value.value.checkpoint.id, other).value
    runtime.resume(other, asking.graph, answer).awaited.value.completed._2 shouldBe Vector("act:1", "yes:2")
    runtime.resume(thread, asking.graph, answer).awaited.value.completed._2 shouldBe Vector("act:1", "yes:1")
    effect.applied.get shouldBe 2
    asking.keys.toArray.distinct should have size 2
  }

  /** A node that performs the effect and then fails while `failing`: the thread stays recoverable. */
  final private class Failing(effect: Effect) {
    val failing = new AtomicBoolean(true)
    val graph: CompiledGraph[Unit, Vector[String]] = {
      val b = GraphBuilder("failing", "v1")
      val n = b.node[Unit]("work", writes = Set(log)) { (_, _, context) =>
        effect.perform(context) match {
          case Left(error)               => NodeResult.Fail(error)
          case Right(_) if failing.get() => NodeResult.Fail(ValidationError("work", "failed after its effect"))
          case Right(n)                  => continue(Command.empty.update(log, s"work:$n"))
        }
      }
      b.compile(n)(_.get(log)).value
    }
  }

  "A task's records" should "replay its effect after updateState and recover, the task keeping its key" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val effect  = new Effect
    val work    = new Failing(effect)
    runtime.start(thread, work.graph, ()).awaited.value.failed
    val stuck = store.latest(thread).value.value.checkpoint
    // the edit moves the superstep on and keeps the frontier, task ids included
    val edited = runtime.updateState(thread, work.graph, stuck.id, StateUpdate.update(log, "edited")).value
    edited.snapshot.superstep shouldBe stuck.snapshot.superstep + 1
    record(store, thread, effect.key.get).value.completed shouldBe true
    work.failing.set(false)
    runtime.recover(thread, work.graph).awaited.value.completed._2 shouldBe Vector("edited", "work:1")
    effect.applied.get shouldBe 1
    record(store, thread, effect.key.get) shouldBe None
  }

  it should "not be shared with a fork, whose re-run of the task is an effect of its own" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val effect  = new Effect
    val work    = new Failing(effect)
    runtime.start(thread, work.graph, ()).awaited.value.failed
    val sourceKey = effect.key.get
    runtime.fork(thread, store.latest(thread).value.value.checkpoint.id, other).value
    work.failing.set(false)
    runtime.recover(other, work.graph).awaited.value.completed._2 shouldBe Vector("work:2")
    effect.key.get should not be sourceKey
    // the source's evidence is untouched, and its own recover replays it
    record(store, thread, sourceKey).value.completed shouldBe true
    runtime.recover(thread, work.graph).awaited.value.completed._2 shouldBe Vector("work:1")
    effect.applied.get shouldBe 2
  }

  it should "go with the thread when it is deleted" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val effect  = new Effect
    runtime.start(thread, new Failing(effect).graph, ()).awaited.value.failed
    record(store, thread, effect.key.get) shouldBe defined
    runtime.deleteThread(thread).value
    record(store, thread, effect.key.get) shouldBe None
  }

  "A Block" should "leave its turn's effect in place and release its records" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val effect  = new Effect
    val b       = GraphBuilder("blocking", "v1")
    val n = b.node[Unit]("guard", writes = Set(log)) { (_, _, context) =>
      effect
        .perform(context)
        .fold(NodeResult.Fail(_), _ => NodeResult.Block(StateUpdate.empty, ValidationError("guard", "no")))
    }
    runtime.start(thread, b.compile(n)(_.get(log)).value, ()).awaited.value.failed
    store.latest(thread).value.value.checkpoint.status shouldBe CheckpointStatus.Failed
    effect.applied.get shouldBe 1
    record(store, thread, effect.key.get) shouldBe None
  }

  "A run that lost its claim" should "write no record, and so never start its effect" in {
    val clock   = new MutableClock(Instant.parse("2026-10-14T09:00:00Z"))
    val store   = InMemoryCheckpointer(clock)
    val claims  = ClaimPolicy(ttl = 1.hour, renewEvery = 30.minutes)
    val entered = new CountDownLatch(1)
    val taken   = new CountDownLatch(1)
    val effect  = new Effect
    val outcome = new AtomicReference[Result[Int]]()
    val b       = GraphBuilder("taken-over", "v1")
    val n = b.node[Unit]("work", writes = Set(log)) { (_, _, context) =>
      entered.countDown()
      taken.await(5, TimeUnit.SECONDS)
      // the interrupt a lost claim may deliver is not what stops it here: the store refuses the intent
      Thread.interrupted(): Unit
      outcome.set(effect.perform(context))
      NodeResult.Fail(ValidationError("work", "stopped"))
    }
    val handle = GraphRuntime(store, clock, claims).start(thread, b.compile(n)(_.get(log)).value, ()).value
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    clock.advance(2.hours)
    store.claim(thread, ClaimRequest(RunId("taker"), 1.hour)).value
    taken.countDown()
    awaitResult(handle)
    outcome.get.left.value shouldBe a[GraphError.StaleClaim]
    effect.applied.get shouldBe 0
  }

  /** A clock moved by hand. */
  final private class MutableClock(start: Instant) extends Clock {
    private val now                                      = new AtomicReference(start)
    def instant(): Instant                               = now.get
    def getZone: java.time.ZoneId                        = ZoneOffset.UTC
    override def withZone(zone: java.time.ZoneId): Clock = this
    def advance(by: FiniteDuration): Unit                = now.updateAndGet(_.plusNanos(by.toNanos)): Unit
  }
}
