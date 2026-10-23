package org.llm4s.agent.graph

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneOffset }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import GraphTestSupport.*

/**
 * The runtime's side of replay (design §4.24): records written under the run's claim, kept while their task
 * can run again - across a late `Async` commit, a suspension, `updateState` - and released by the commit that
 * ends that, by a Block, or with the thread; a fork's re-run is a new effect.
 */
class EffectReplayRuntimeSpec extends AnyFlatSpec with TestTimeLimit with Matchers with EitherValues with OptionValues {

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

  "A task planned again after a takeover" should "replay the first plan's evidence when planned from the same results" in {
    // Async: the checkpoint that scheduled `b` never became durable, so a recovery plans `b` again
    val store    = InMemoryCheckpointer()
    val effect   = new Effect
    val keys     = new java.util.concurrent.ConcurrentLinkedQueue[EffectKey]()
    val lineages = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val failing  = new AtomicBoolean(true)
    // the writer holds the lost checkpoint until b has run its effect, so the run sees the loss only after
    val bRan = new CountDownLatch(1)
    val lossy = new ForwardingCheckpointer(store) {
      override def commit(threadId: ThreadId, commit: Commit) =
        if failing.get && commit.checkpoint.exists(_.snapshot.superstep == 1) then
          Left(ValidationError("store", "lost the checkpoint that scheduled b"))
        else store.commit(threadId, commit)
    }
    val b = GraphBuilder("replanned", "v1")
    val last = b.node[Unit]("b", writes = Set(log)) { (_, _, context) =>
      lineages.add(context.effectLineage)
      NodeResult.fromResult(effect.perform(context, during = () => bRan.countDown()).map { n =>
        keys.add(effect.key.get)
        Command.empty.update(log, s"b:$n")
      })
    }
    val a = b.node[Unit]("a", writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "a")))
    b.edge(a, last)
    val graph = b.compile(a)(_.get(log)).value

    {
      val runtime = GraphRuntime(lossy)
      runtime.committing = (_, commit) =>
        if Thread.currentThread().getName.startsWith("llm4s-graph-writer-") &&
          commit.checkpoint.exists(_.snapshot.superstep == 1)
        then bRan.await(5, TimeUnit.SECONDS): Unit
      runtime
    }.start(thread, graph, (), durability = Durability.Async).awaited.value.failed
    effect.applied.get shouldBe 1
    record(store, thread, keys.peek()).value.completed shouldBe true
    failing.set(false)
    // the recovery claims at the durable checkpoint, where b is not yet scheduled, and plans b again from a's
    // same result: the same task, so it replays the first plan's evidence rather than repeating the effect
    GraphRuntime(store).recover(thread, graph, durability = Durability.Async).awaited.value.completed._2 shouldBe
      Vector("a", "b:1")
    effect.applied.get shouldBe 1
    lineages.asScala.toVector.distinct should have size 1
    keys.asScala.toVector.distinct should have size 1
    keys.asScala.toVector.flatMap(record(store, thread, _)) shouldBe empty
  }

  it should "replay the first plan's evidence when the result's codec encodes differently each time" in {
    // the lineage comes from the write as recorded: a second encoding, here with another nonce, is never taken
    val nonce = new AtomicInteger()
    given upickle.default.ReadWriter[String] = upickle.default
      .readwriter[ujson.Value]
      .bimap[String](v => ujson.Obj("v" -> v, "nonce" -> nonce.incrementAndGet()), _("v").str)
    val items    = StateKey.appending[String]("items")
    val store    = InMemoryCheckpointer()
    val effect   = new Effect
    val keys     = new java.util.concurrent.ConcurrentLinkedQueue[EffectKey]()
    val lineages = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val failing  = new AtomicBoolean(true)
    val bRan     = new CountDownLatch(1)
    val lossy = new ForwardingCheckpointer(store) {
      override def commit(threadId: ThreadId, commit: Commit) =
        if failing.get && commit.checkpoint.exists(_.snapshot.superstep == 1) then
          Left(ValidationError("store", "lost the checkpoint that scheduled b"))
        else store.commit(threadId, commit)
    }
    val b = GraphBuilder("nonced", "v1")
    val last = b.node[Unit]("b", writes = Set(log)) { (_, _, context) =>
      lineages.add(context.effectLineage)
      NodeResult.fromResult(effect.perform(context, during = () => bRan.countDown()).map { n =>
        keys.add(effect.key.get)
        Command.empty.update(log, s"b:$n")
      })
    }
    val a = b.node[Unit]("a", writes = Set(items))((_, _, _) => continue(Command.empty.update(items, "x")))
    b.edge(a, last)
    val graph = b.compile(a)(_.get(log)).value
    val first = GraphRuntime(lossy)
    first.committing = (_, commit) =>
      if Thread.currentThread().getName.startsWith("llm4s-graph-writer-") &&
        commit.checkpoint.exists(_.snapshot.superstep == 1)
      then bRan.await(5, TimeUnit.SECONDS): Unit
    first.start(thread, graph, (), durability = Durability.Async).awaited.value.failed
    effect.applied.get shouldBe 1
    failing.set(false)
    GraphRuntime(store).recover(thread, graph, durability = Durability.Async).awaited.value.completed._2 shouldBe
      Vector("b:1")
    effect.applied.get shouldBe 1
    lineages.asScala.toVector.distinct should have size 1
    keys.asScala.toVector.distinct should have size 1
  }

  it should "replay the first plan's evidence when the reused result was written by an older codec" in {
    // the recovery's graph reads a's update at a newer codec version: decoding migrates it, so its successor's
    // lineage must come from the write as recorded, not from the migrated result encoded again
    final case class Old(label: String) derives upickle.default.ReadWriter
    final case class Item(name: String) derives upickle.default.ReadWriter
    val rename: ujson.Value => Result[ujson.Value] = json => Right(ujson.Obj("name" -> json("label")))
    val oldItems                                   = StateKey.appending[Old]("items")
    val newItems = StateKey
      .appending[Item]("items")
      .withUpdateVersion(SchemaVersion(2)(1 -> rename))
      .withStateVersion(
        SchemaVersion(2)(1 -> (json => Right(ujson.Arr.from(json.arr.map(o => ujson.Obj("name" -> o("label")))))))
      )
    val store    = InMemoryCheckpointer()
    val effect   = new Effect
    val keys     = new java.util.concurrent.ConcurrentLinkedQueue[EffectKey]()
    val lineages = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val failing  = new AtomicBoolean(true)
    // the writer holds the lost checkpoint until b has run its effect, so the run sees the loss only after
    val bRan = new CountDownLatch(1)
    val lossy = new ForwardingCheckpointer(store) {
      override def commit(threadId: ThreadId, commit: Commit) =
        if failing.get && commit.checkpoint.exists(_.snapshot.superstep == 1) then
          Left(ValidationError("store", "lost the checkpoint that scheduled b"))
        else store.commit(threadId, commit)
    }
    def graphWith[A](items: StateKey[Vector[A], A], item: A) =
      val b = GraphBuilder("migrated", "v1")
      val last = b.node[Unit]("b", writes = Set(log)) { (_, _, context) =>
        lineages.add(context.effectLineage)
        NodeResult.fromResult(effect.perform(context, during = () => bRan.countDown()).map { n =>
          keys.add(effect.key.get)
          Command.empty.update(log, s"b:$n")
        })
      }
      val a = b.node[Unit]("a", writes = Set(items))((_, _, _) => continue(Command.empty.update(items, item)))
      b.edge(a, last)
      b.compile(a)(_.get(log)).value

    {
      val runtime = GraphRuntime(lossy)
      runtime.committing = (_, commit) =>
        if Thread.currentThread().getName.startsWith("llm4s-graph-writer-") &&
          commit.checkpoint.exists(_.snapshot.superstep == 1)
        then bRan.await(5, TimeUnit.SECONDS): Unit
      runtime
    }
      .start(thread, graphWith(oldItems, Old("x")), (), durability = Durability.Async)
      .awaited
      .value
      .failed
    effect.applied.get shouldBe 1
    store.latest(thread).value.value.pendingWrites.map(_.operations) shouldBe Vector(
      Vector(EncodedOperation.Update("items", VersionedJson(1, ujson.Obj("label" -> "x"))))
    )
    failing.set(false)
    GraphRuntime(store)
      .recover(thread, graphWith(newItems, Item("x")), durability = Durability.Async)
      .awaited
      .value
      .completed
      ._2 shouldBe Vector("b:1")
    effect.applied.get shouldBe 1
    lineages.asScala.toVector.distinct should have size 1
    keys.asScala.toVector.distinct should have size 1
    keys.asScala.toVector.flatMap(record(store, thread, _)) shouldBe empty
  }

  it should "be another task, with an effect of its own, when planned from other results" in {
    val store   = InMemoryCheckpointer()
    val effect  = new Effect
    val keys    = new java.util.concurrent.ConcurrentLinkedQueue[EffectKey]()
    val failing = new AtomicBoolean(true)
    val answers = new AtomicInteger()
    // nothing of the first run after its claim is durable, a's result included: the writer waits until b has
    // recorded and run its effect, then loses everything queued
    val bRan = new CountDownLatch(1)
    val lossy = new ForwardingCheckpointer(store) {
      override def commit(threadId: ThreadId, commit: Commit) =
        if failing.get && (commit.checkpoint.exists(_.snapshot.superstep == 1) || commit.pendingWrites.nonEmpty) then
          Left(ValidationError("store", "lost a's result and the checkpoint that scheduled b"))
        else store.commit(threadId, commit)
    }
    val first = GraphRuntime(lossy)
    first.committing = (_, commit) =>
      if Thread.currentThread().getName.startsWith("llm4s-graph-writer-") && commit.effects.isEmpty then
        bRan.await(5, TimeUnit.SECONDS): Unit
    val b = GraphBuilder("replanned-otherwise", "v1")
    val last = b.node[Unit]("b", writes = Set(log)) { (_, _, context) =>
      NodeResult.fromResult(effect.perform(context, during = () => bRan.countDown()).map { n =>
        keys.add(effect.key.get)
        Command.empty.update(log, s"b:$n")
      })
    }
    // a answers differently each time it runs, as a model may
    val a = b.node[Unit]("a", writes = Set(log))((_, _, _) =>
      continue(Command.empty.update(log, s"a${answers.incrementAndGet()}"))
    )
    b.edge(a, last)
    val graph = b.compile(a)(_.get(log)).value
    first.start(thread, graph, (), durability = Durability.Async).awaited.value.failed
    effect.applied.get shouldBe 1
    failing.set(false)
    // a runs again and answers otherwise: b is other work, a task of another lineage with an effect of its own
    GraphRuntime(store).recover(thread, graph, durability = Durability.Async).awaited.value.completed._2 shouldBe
      Vector("a2", "b:2")
    keys.asScala.toVector.distinct should have size 2
    keys.asScala.toVector.flatMap(record(store, thread, _)) shouldBe empty
  }

  "A task of a format-6 checkpoint" should "restore under a fresh root lineage, a record keyed before lineages ignored" in {
    // format 6 never shipped: its task has no lineage, and admission stamps it like a task of a fresh run
    val store  = InMemoryCheckpointer()
    val effect = new Effect
    val b      = GraphBuilder("legacy", "v1")
    val n = b.node[Unit]("work", writes = Set(log)) { (_, _, context) =>
      NodeResult.fromResult(effect.perform(context).map(n => Command.empty.update(log, s"work:$n")))
    }
    val graph  = b.compile(n)(_.get(log)).value
    val legacy = graph.snapshot(graph.start(())).value
    val format6 = Checkpoint(
      6,
      "old/1",
      None,
      thread.value,
      "old",
      CheckpointStatus.Running,
      Instant.EPOCH,
      legacy.copy(frontier = legacy.frontier.map(_.copy(lineage = "")))
    )
    val token = store.claim(thread, ClaimRequest(RunId("old"), None, 1.minute)).value.token
    store.commit(thread, Commit(token, Some(format6))).value
    // the key a format-6 writer gave the effect: its task key named no lineage
    val oldTask = IdempotencyKey(KeyDigest.of(Seq("llm4s-task-v1", thread.value, "0.0")))
    val oldKey  = EffectKey.derive(thread, oldTask, "count.add", Seq("counter"))
    val written =
      new EffectRecord(oldKey, TaskId("0.0"), "", EffectBody.Completed(EffectIntent.Unverifiable, ujson.Num(1)))
    store.commit(thread, Commit(token, effects = Vector(written))).value
    store.release(thread, token).value

    GraphRuntime(store).recover(thread, graph).awaited.value.completed._2 shouldBe Vector("work:1")
    effect.applied.get shouldBe 1
    effect.key.get should not be oldKey
  }

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

  "A resume's identity" should "stay the same when the question or the continuation's codec migrates between runs" in {
    // taken once, when the answer is applied, and kept with the continuation: never recomputed from what a
    // later run decodes, which a newer codec migrates
    final case class Old(label: String) derives upickle.default.ReadWriter
    final case class Item(name: String) derives upickle.default.ReadWriter
    val renamed: ujson.Value => Result[ujson.Value] = json => Right(ujson.Obj("name" -> json("label")))
    val digests                                     = new java.util.concurrent.ConcurrentLinkedQueue[Option[String]]()
    val failing                                     = new AtomicBoolean(false)
    def graphOf[Q: upickle.default.ReadWriter](question: Q, versions: Option[(SchemaVersion, SchemaVersion)]) =
      val b = GraphBuilder("migrating-resume", "v1")
      val review = versions.fold(b.declareResume[Q, String]("review")) { (q, input) =>
        b.declareResume[Q, String]("review", questionVersion = q, inputVersion = input)
      }
      b.implement(review.node, writes = Set(log)) { (resumed, _, context) =>
        digests.add(context.resumeDigest)
        if failing.get then NodeResult.Fail(ValidationError("review", "failed"))
        else continue(Command.empty.update(log, resumed.answer))
      }
      val act = b.node[Unit]("act")((_, _, _) => NodeResult.Suspend(StateUpdate.empty, question, review))
      b.compile(act)(_.get(log)).value -> review
    val (older, olderReview) = graphOf(Old("x"), None)
    val (newer, _) = graphOf(
      Item("x"),
      Some(
        SchemaVersion(2)(1 -> renamed) -> SchemaVersion(2)(
          1 -> (json =>
            Right(ujson.Obj("question" -> ujson.Obj("name" -> json("question")("label")), "answer" -> json("answer")))
          )
        )
      )
    )
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    def answered(on: ThreadId) =
      val parked = runtime.start(on, older, ()).awaited.value.suspended
      Map(parked.interrupts.head.id -> olderReview.answer("yes"))

    // the continuation's input migrates: a recovery under the newer codec reads the identity it was given
    val answer = answered(thread)
    failing.set(true)
    runtime.resume(thread, older, answer).awaited.value.failed
    failing.set(false)
    runtime.recover(thread, newer).awaited.value.completed._2 shouldBe Vector("yes")
    // the parked question migrates: the answer applied under the newer codec takes the same identity
    runtime.resume(other, newer, answered(other)).awaited.value.completed._2 shouldBe Vector("yes")
    digests.asScala.toVector.flatten.distinct should have size 1
    digests.asScala.toVector should have size 3
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

  it should "stay the task's when the graph is stepped in memory, the continuation replaying from the execution" in {
    val effect = new Effect
    val asking = new HookAsking(effect)
    val parked = drive(asking.graph, asking.graph.start(())).suspended
    val resumed = asking.graph
      .resume(parked.execution, Map(parked.interrupts.head.id -> asking.review.answer("yes")))
      .value
    drive(asking.graph, resumed).completed._2 shouldBe Vector("act:1", "yes:1")
    effect.applied.get shouldBe 1
    asking.keys.toArray.distinct should have size 1
    // and the execution's records went with the tasks that finished
    resumed.journal.lookup(effect.key.get).value shouldBe None
  }

  /** A node that performs the effect and then fails while `failing`: the thread stays recoverable. */
  final private class Failing(effect: Effect, gained: Boolean = false, spare: Boolean = true) {
    val failing = new AtomicBoolean(true)
    val graph: CompiledGraph[Unit, Vector[String]] = {
      val b = GraphBuilder("failing", "v1")
      // the shape changes of design §4.29: a key and a node added, or a node removed
      if gained then
        b.stateKey(StateKey.replace[Int]("gained", 0))
        b.node[Unit]("unreached")((_, _, _) => continue(Command.empty))
      if spare then b.node[Unit]("spare")((_, _, _) => continue(Command.empty))
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

  it should "stay valid when the thread is recovered into a graph that gained a key and a node (§4.29)" in {
    val store  = InMemoryCheckpointer()
    val effect = new Effect
    GraphRuntime(store).start(thread, new Failing(effect).graph, ()).awaited.value.failed
    record(store, thread, effect.key.get).value.completed shouldBe true
    val evolved = new Failing(effect, gained = true)
    evolved.failing.set(false)
    // the restored task keeps its id, so its key, and replays the recorded outcome instead of applying it again
    GraphRuntime(store).recover(thread, evolved.graph).awaited.value.completed._2 shouldBe Vector("work:1")
    effect.applied.get shouldBe 1
    record(store, thread, effect.key.get) shouldBe None
  }

  it should "be kept, untouched, when a restore is refused, and replay once the thread is recovered in its own graph" in {
    val store  = InMemoryCheckpointer()
    val effect = new Effect
    val work   = new Failing(effect)
    GraphRuntime(store).start(thread, work.graph, ()).awaited.value.failed
    val before = record(store, thread, effect.key.get).value
    // a node removed refuses the restore before any commit, so nothing is claimed and no record is released
    GraphRuntime(store).recover(thread, new Failing(effect, spare = false).graph).awaited.left.value shouldBe
      a[GraphError.RestoreRejected]
    record(store, thread, effect.key.get).value shouldBe before
    work.failing.set(false)
    GraphRuntime(store).recover(thread, work.graph).awaited.value.completed._2 shouldBe Vector("work:1")
    effect.applied.get shouldBe 1
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

  it should "release the records of a sibling that parked in the superstep it closed" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val effect  = new Effect
    val b       = GraphBuilder("blocking-sibling", "v1")
    val done    = b.declareResume[String, String]("done")
    b.implement(done.node, writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "resumed")))
    val asker = b.node[Unit]("asker", writes = Set(log)) { (_, _, context) =>
      effect.perform(context).fold(NodeResult.Fail(_), _ => NodeResult.Suspend(StateUpdate.empty, "ok?", done))
    }
    val guard = b.node[Unit]("guard", writes = Set(log)) { (_, _, _) =>
      NodeResult.Block(StateUpdate.empty, ValidationError("guard", "no"))
    }
    val start = b.node[Unit]("start", writes = Set(log))((_, _, _) => continue(Command.empty))
    b.edge(start, asker)
    b.edge(start, guard)
    runtime.start(thread, b.compile(start)(_.get(log)).value, ()).awaited.value.failed
    val closing = store.latest(thread).value.value.checkpoint
    closing.status shouldBe CheckpointStatus.Failed
    closing.snapshot.parked should not be empty
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
    store.claim(thread, ClaimRequest(RunId("taker"), None, 1.hour)).value
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
