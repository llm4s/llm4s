package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Checkpoint history, fork, `updateState` and retention (#1702, design 4.17): a store keeps every
 * checkpoint a thread's runs commit, `GraphRuntime` lists and reads them, forks a new thread from any of
 * them, edits the latest one's state as a new checkpoint, and prunes by age or size; a guardrail Block
 * retracts its turn from the history.
 */
class CheckpointHistorySpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val thread = ThreadId("t")
  private val other  = ThreadId("t-fork")

  /** A clock that moves only when told to, for the runtime's `createdAt` and the store's age. */
  final private class TestClock(start: Instant) extends Clock {
    private val now                         = AtomicReference(start)
    def instant(): Instant                  = now.get
    def getZone: ZoneId                     = ZoneOffset.UTC
    override def withZone(z: ZoneId): Clock = this
    def advance(by: FiniteDuration): Unit   = now.updateAndGet(_.plusNanos(by.toNanos)): Unit
  }

  private def config(run: String): RunConfig = RunConfig().withRunId(RunId(run))
  private def tenant(id: String): RunConfig  = RunConfig().withTenantId(TenantId(id))

  /**
   * `write` logs its input, then `after` logs "after"; an input starting "ask" suspends for review, one
   * starting "bad" blocks, and while `failing` is set a "fail" input fails `after`. Every task records
   * where it ran, as a tool call's idempotency key is derived from it.
   */
  final private class Notes {
    val log       = StateKey.appending[String]("log")
    val count     = StateKey.replace[Int]("count", 0)
    val failing   = AtomicBoolean(true)
    val positions = ConcurrentLinkedQueue[RunPosition]()
    val calls     = ConcurrentLinkedQueue[String]()

    private val b = GraphBuilder("notes", "v1")
    val after: NodeRef[Unit] = b.node[Unit]("after", writes = Set(log)) { (_, state, ctx) =>
      positions.add(ctx.position)
      calls.add("after")
      if failing.get && state.get(log).value.lastOption.contains("fail") then
        NodeResult.Fail(ValidationError("after", "failed"))
      else continue(Command.empty.update(log, "after"))
    }
    val review: ResumeRef[String, String] = b.resumeNode[String, String]("review", writes = Set(log)) {
      (resumed, _, ctx) =>
        positions.add(ctx.position)
        continue(Command.empty.update(log, s"reviewed:${resumed.question}:${resumed.answer}").goto(after))
    }
    val write: NodeRef[String] = b.node[String]("write", writes = Set(log)) { (input, _, ctx) =>
      positions.add(ctx.position)
      calls.add(s"write:$input")
      if input.startsWith("ask") then NodeResult.Suspend(StateUpdate.update(log, s"asked:$input"), input, review)
      else if input.startsWith("bad") then
        NodeResult.Block(StateUpdate.update(log, s"blocked:$input"), ValidationError("guard", s"$input refused"))
      else continue(Command.empty.update(log, input).goto(after))
    }
    b.stateKey(count)
    val graph: CompiledGraph[String, Vector[String]] = b.compile(write)(_.get(log)).value
  }

  /**
   * `plan` sends "a" and "b" to one `worker` each; while `failing` is set "b" fails, so the run fails
   * with "a"'s result kept as a pending write. `ran` records every worker call.
   */
  final private class Siblings {
    val log       = StateKey.appending[String]("log")
    val ran       = ConcurrentLinkedQueue[String]()
    val failing   = AtomicBoolean(true)
    private val b = GraphBuilder("siblings", "v1")
    private val worker = b.node[String]("worker", writes = Set(log)) { (item, _, _) =>
      ran.add(item)
      if item == "b" && failing.get then NodeResult.Fail(ValidationError("worker", "b failed"))
      else continue(Command.empty.update(log, item))
    }
    private val plan = b.node[Unit]("plan")((_, _, _) => continue(Command.empty.send(worker, "a").send(worker, "b")))
    val graph: CompiledGraph[Unit, Vector[String]] = b.compile(plan)(_.get(log)).value
    // sorted: the workers of one superstep run concurrently, in no fixed order
    def runs: Vector[String] = ran.asScala.toVector.sorted
  }

  private def ids(history: Vector[Checkpoint]): Vector[String] = history.map(_.id)

  private def logAt(n: Notes, checkpoint: Checkpoint): Vector[String] =
    n.graph.restore(checkpoint.snapshot).value.state.get(n.log).value

  // ---- history ----

  "A thread's history" should "keep every checkpoint its runs commit, newest first, the latest first" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val n       = Notes()
    runtime.start(thread, n.graph, "a", config("r1")).awaited.value.completed
    val first = runtime.history(thread).value
    first.head.id shouldBe store.latest(thread).value.value.checkpoint.id
    first.map(_.status).head shouldBe CheckpointStatus.Completed
    first.last.parent shouldBe None
    // each checkpoint's parent is the next one in the list: one linear chain
    first.zip(first.drop(1)).foreach((newer, older) => newer.parent shouldBe Some(older.id))

    runtime.start(thread, n.graph, "b", config("r2")).awaited.value.completed
    val both = runtime.history(thread).value
    both.drop(both.size - first.size).map(_.id) shouldBe ids(first)
    logAt(n, both.head) shouldBe Vector("a", "after", "b", "after")
    logAt(n, first.head) shouldBe Vector("a", "after")
  }

  it should "number checkpoint ids along the thread, so a RunId used for several runs never repeats one" in {
    val runtime = GraphRuntime.inMemory()
    val n       = Notes()
    runtime.start(thread, n.graph, "a", config("same")).awaited.value.completed
    runtime.start(thread, n.graph, "b", config("same")).awaited.value.completed
    val all = ids(runtime.history(thread).value)
    all.distinct shouldBe all
    all.reverse shouldBe all.indices.map(i => s"same/${i + 1}")
  }

  it should "page from the latest backwards after a given checkpoint" in {
    val runtime = GraphRuntime.inMemory()
    val n       = Notes()
    runtime.start(thread, n.graph, "a").awaited.value.completed
    runtime.start(thread, n.graph, "b").awaited.value.completed
    val all = ids(runtime.history(thread).value)
    all.size should be > 4
    val pages = Iterator
      .iterate(runtime.history(thread, None, 3).value)(page => runtime.history(thread, Some(page.last.id), 3).value)
      .takeWhile(_.nonEmpty)
      .toVector
    pages.flatMap(ids) shouldBe all
    pages.init.foreach(_.size shouldBe 3)
    runtime.history(thread, Some(all.last)).value shouldBe empty
  }

  it should "refuse a page after a checkpoint it does not hold, and a non-positive limit" in {
    val runtime = GraphRuntime.inMemory()
    val n       = Notes()
    runtime.history(thread).value shouldBe empty
    runtime.history(thread, Some("nope")).left.value shouldBe GraphError.CheckpointNotFound("t", "nope")
    runtime.start(thread, n.graph, "a").awaited.value.completed
    runtime.history(thread, Some("nope")).left.value shouldBe GraphError.CheckpointNotFound("t", "nope")
    runtime.history(thread, limit = 0).left.value shouldBe a[ValidationError]
  }

  it should "read any one checkpoint, whose snapshot restores the state at that point" in {
    val runtime = GraphRuntime.inMemory()
    val n       = Notes()
    runtime.start(thread, n.graph, "a").awaited.value.completed
    runtime.start(thread, n.graph, "b").awaited.value.completed
    val all = runtime.history(thread).value
    all.foreach(c => runtime.checkpoint(thread, c.id).value shouldBe c)
    val claimOfB = all.filter(c => c.status == CheckpointStatus.Running && logAt(n, c) == Vector("a", "after")).head
    n.graph.restore(claimOfB.snapshot).value.pendingTasks.map(_._2) shouldBe Vector(NodeId("write"))
    runtime.checkpoint(thread, "nope").left.value shouldBe GraphError.CheckpointNotFound("t", "nope")
    runtime.checkpoint(ThreadId("unknown"), "x").left.value shouldBe GraphError.CheckpointNotFound("unknown", "x")
  }

  it should "be another tenant's to read: history, checkpoint, fork, updateState and prune say nothing" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val n       = Notes()
    runtime.start(thread, n.graph, "a", tenant("a")).awaited.value.completed
    val latest   = runtime.history(thread, config = tenant("a")).value.head
    val mismatch = GraphError.TenantMismatch("t", Some("b"))
    runtime.history(thread, config = tenant("b")).left.value shouldBe mismatch
    runtime.history(thread, Some("nope"), config = tenant("b")).left.value shouldBe mismatch
    runtime.checkpoint(thread, latest.id, tenant("b")).left.value shouldBe mismatch
    runtime.checkpoint(thread, "nope", tenant("b")).left.value shouldBe mismatch
    runtime.fork(thread, latest.id, other, tenant("b")).left.value shouldBe mismatch
    runtime
      .updateState(thread, n.graph, latest.id, StateUpdate.update(n.count, 1), config = tenant("b"))
      .left
      .value shouldBe
      mismatch
    runtime.prune(thread, RetentionPolicy(maxCheckpoints = Some(1)), tenant("b")).left.value shouldBe mismatch
    runtime.history(thread, config = RunConfig()).left.value shouldBe GraphError.TenantMismatch("t", None)
    runtime.history(thread, config = tenant("a")).value.head shouldBe latest
    store.latest(other).value shouldBe None
  }

  // ---- fork ----

  "fork" should "start a new thread from an earlier checkpoint, leaving the source unchanged" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val n       = Notes()
    runtime.start(thread, n.graph, "a", config("r1")).awaited.value.completed
    val afterA = runtime.history(thread).value.head
    runtime.start(thread, n.graph, "b", config("r2")).awaited.value.completed
    val source = runtime.history(thread).value

    val forked = runtime.fork(thread, afterA.id, other, config("f")).value
    forked.id shouldBe "f/1"
    forked.parent shouldBe None
    forked.threadId shouldBe other.value
    forked.status shouldBe CheckpointStatus.Completed
    forked.snapshot shouldBe afterA.snapshot
    forked.fencingToken shouldBe defined
    runtime.history(other).value shouldBe Vector(forked)
    store.eventsAfter(other, 0L, 10).value.map(e => e.seq -> e.event) shouldBe
      Vector(1L -> RunEvent.ThreadForked("t", afterA.id, None, None))

    val (_, output) = runtime.start(other, n.graph, "c").awaited.value.completed
    output shouldBe Vector("a", "after", "c", "after")
    runtime.history(thread).value shouldBe source
  }

  it should "fork a suspended checkpoint, whose interrupt the new thread resumes" in {
    val runtime = GraphRuntime.inMemory()
    val n       = Notes()
    val parked  = runtime.start(thread, n.graph, "ask-1").awaited.value.suspended
    val at      = runtime.history(thread).value.head
    at.status shouldBe CheckpointStatus.Suspended
    runtime.fork(thread, at.id, other).value.status shouldBe CheckpointStatus.Suspended
    val id = parked.interrupts.head.id
    runtime.resume(other, n.graph, Map(id -> n.review.answer("yes"))).awaited.value.completed._2 shouldBe
      Vector("asked:ask-1", "reviewed:ask-1:yes", "after")
    // the source still waits for its own answer
    runtime.resume(thread, n.graph, Map(id -> n.review.answer("no"))).awaited.value.completed._2 shouldBe
      Vector("asked:ask-1", "reviewed:ask-1:no", "after")
  }

  it should "fork a running checkpoint without its pending writes, so recover runs its tasks in the new thread" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val w       = Siblings()
    runtime.start(thread, w.graph, ()).awaited.value.failed
    val stuck = store.latest(thread).value.value
    stuck.checkpoint.status shouldBe CheckpointStatus.Running
    stuck.pendingWrites should have size 1
    val forked = runtime.fork(thread, stuck.checkpoint.id, other).value
    forked.status shouldBe CheckpointStatus.Running
    store.latest(other).value.value.pendingWrites shouldBe empty
    w.failing.set(false)
    w.ran.clear()
    runtime.recover(other, w.graph).awaited.value.completed._2 shouldBe Vector("a", "b")
    w.runs shouldBe Vector("a", "b")
    // the source keeps its own pending write: its recover runs only the failed task
    w.ran.clear()
    runtime.recover(thread, w.graph).awaited.value.completed._2 shouldBe Vector("a", "b")
    w.runs shouldBe Vector("b")
  }

  it should "refuse a target that exists, is the source, or is another tenant's, and a checkpoint it lacks" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val n       = Notes()
    runtime.start(thread, n.graph, "a").awaited.value.completed
    runtime.start(other, n.graph, "x").awaited.value.completed
    runtime.start(ThreadId("b-owned"), n.graph, "y", tenant("b")).awaited.value.completed
    val at     = runtime.history(thread).value.last.id
    val before = runtime.history(other).value

    runtime.fork(thread, at, other).left.value shouldBe GraphError.ThreadExists(other.value)
    runtime.fork(thread, at, thread).left.value shouldBe a[ValidationError]
    runtime.fork(thread, at, ThreadId("b-owned")).left.value shouldBe GraphError.TenantMismatch("b-owned", None)
    runtime.fork(thread, "nope", ThreadId("new")).left.value shouldBe GraphError.CheckpointNotFound("t", "nope")
    runtime.fork(ThreadId("missing"), "x", ThreadId("new")).left.value shouldBe
      GraphError.CheckpointNotFound("missing", "x")
    runtime.history(other).value shouldBe before
    store.latest(ThreadId("new")).value shouldBe None
  }

  it should "refuse a target a run holds, and release the target's claim after forking" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val n       = Notes()
    runtime.start(thread, n.graph, "a").awaited.value.completed
    val at   = runtime.history(thread).value.head.id
    val held = store.claim(other, ClaimRequest(RunId("elsewhere"), 1.hour)).value
    runtime.fork(thread, at, other).left.value shouldBe GraphError.ThreadBusy(other.value, None, None)
    store.release(other, held.token).value
    runtime.fork(thread, at, other).value
    // the fork gave its claim back: a run can start on the new thread at once
    runtime.start(other, n.graph, "b").awaited.value.completed._2 shouldBe Vector("a", "after", "b", "after")
  }

  // ---- updateState ----

  "updateState" should "commit the latest state with an update applied, as a new checkpoint the next run continues" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val n       = Notes()
    runtime.start(thread, n.graph, "a").awaited.value.completed
    val latest = store.latest(thread).value.value.checkpoint
    val seq    = store.eventsAfter(thread, 0L, 1000).value.last.seq

    val updated =
      runtime.updateState(thread, n.graph, latest.id, StateUpdate.update(n.log, "fixed"), Some(n.write)).value
    updated.parent shouldBe Some(latest.id)
    updated.status shouldBe CheckpointStatus.Completed
    updated.snapshot.superstep shouldBe latest.snapshot.superstep + 1
    updated.fencingToken shouldBe defined
    store.latest(thread).value.value.checkpoint shouldBe updated
    logAt(n, updated) shouldBe Vector("a", "after", "fixed")
    val events = store.eventsAfter(thread, seq, 10).value
    events should have size 1
    val event = events.head
    event.event shouldBe RunEvent.StateUpdated(latest.id, Some("write"), None, None)
    event.nodeId shouldBe Some("write")
    event.checkpointId shouldBe Some(updated.id)

    runtime.start(thread, n.graph, "b").awaited.value.completed._2 shouldBe
      Vector("a", "after", "fixed", "b", "after")
  }

  it should "correct a suspended thread's state before resume, keeping its interrupt" in {
    val runtime = GraphRuntime.inMemory()
    val n       = Notes()
    val parked  = runtime.start(thread, n.graph, "ask-1").awaited.value.suspended
    val latest  = runtime.history(thread).value.head
    val updated = runtime.updateState(thread, n.graph, latest.id, StateUpdate.update(n.count, 7)).value
    updated.status shouldBe CheckpointStatus.Suspended
    updated.snapshot.parked shouldBe latest.snapshot.parked
    runtime.start(thread, n.graph, "x").left.value shouldBe a[GraphError.PendingInterrupts]
    val (state, output) =
      runtime.resume(thread, n.graph, Map(parked.interrupts.head.id -> n.review.answer("ok"))).awaited.value.completed
    output shouldBe Vector("asked:ask-1", "reviewed:ask-1:ok", "after")
    state.get(n.count).value shouldBe 7
  }

  it should "carry a running checkpoint's pending writes over, so recover does not run completed tasks again" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val w       = Siblings()
    runtime.start(thread, w.graph, ()).awaited.value.failed
    val stuck = store.latest(thread).value.value
    stuck.pendingWrites.map(_.nodeId) shouldBe Vector("worker")

    val updated = runtime.updateState(thread, w.graph, stuck.checkpoint.id, StateUpdate.update(w.log, "edit")).value
    store.latest(thread).value.value.pendingWrites.map(p => p.checkpointId -> p.taskId) shouldBe
      stuck.pendingWrites.map(p => updated.id -> p.taskId)
    w.failing.set(false)
    w.ran.clear()
    runtime.recover(thread, w.graph).awaited.value.completed._2 shouldBe Vector("edit", "a", "b")
    w.runs shouldBe Vector("b")
  }

  it should "refuse a stale version, leaving the thread unchanged" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val n       = Notes()
    runtime.start(thread, n.graph, "a").awaited.value.completed
    val old = runtime.history(thread).value(1).id
    val now = store.latest(thread).value.value.checkpoint.id
    runtime.updateState(thread, n.graph, old, StateUpdate.update(n.count, 1)).left.value shouldBe
      GraphError.CheckpointConflict("t", Some(old), Some(now))
    runtime.updateState(ThreadId("none"), n.graph, "x", StateUpdate.update(n.count, 1)).left.value shouldBe
      GraphError.CheckpointNotFound("none", "x")
    store.latest(thread).value.value.checkpoint.id shouldBe now
  }

  it should "check the update as asNode wrote it, or against the graph's keys, and apply it with the key's function" in {
    val runtime = GraphRuntime.inMemory()
    val n       = Notes()
    runtime.start(thread, n.graph, "a").awaited.value.completed
    val at      = runtime.history(thread).value.head.id
    val foreign = Notes()
    val unknown = StateKey.replace[Int]("unknown", 0)
    val picky   = StateKey[Int, Int]("count", 0)((_, _) => Left(ValidationError("count", "no")))

    runtime.updateState(thread, n.graph, at, StateUpdate.update(n.count, 1), Some(n.write)).left.value shouldBe
      GraphError.UndeclaredWrite(NodeId("write"), TaskId("updateState"), StateKeyId("count"))
    runtime.updateState(thread, n.graph, at, StateUpdate.update(n.log, "x"), Some(foreign.write)).left.value shouldBe
      a[ValidationError]
    runtime.updateState(thread, n.graph, at, StateUpdate.update(unknown, 1)).left.value shouldBe
      GraphError.UnknownStateKey(StateKeyId("unknown"))
    // a key instance the graph did not register, even with a registered id
    runtime.updateState(thread, n.graph, at, StateUpdate.update(picky, 1)).left.value shouldBe
      GraphError.UnknownStateKey(StateKeyId("count"))
    runtime.history(thread).value.head.id shouldBe at

    val counted = runtime.updateState(thread, n.graph, at, StateUpdate.update(n.count, 3).remove(n.log)).value
    val state   = n.graph.restore(counted.snapshot).value.state
    state.get(n.count).value shouldBe 3
    state.get(n.log).value shouldBe empty
  }

  it should "report an update its key's function rejects" in {
    val runtime = GraphRuntime.inMemory()
    val b       = GraphBuilder("picky", "v1")
    val picky = StateKey[Int, Int]("picky", 0)((current, by) =>
      if by < 0 then Left(ValidationError("picky", "negative")) else Right(current + by)
    )
    val node  = b.node[Unit]("n", writes = Set(picky))((_, _, _) => continue(Command.empty.update(picky, 1)))
    val graph = b.compile(node)(_.get(picky)).value
    runtime.start(thread, graph, ()).awaited.value.completed
    val at = runtime.history(thread).value.head.id
    runtime.updateState(thread, graph, at, StateUpdate.update(picky, -1)).left.value shouldBe
      a[GraphError.StateUpdateFailed]
    val updated = runtime.updateState(thread, graph, at, StateUpdate.update(picky, 5)).value
    graph.restore(updated.snapshot).value.state.get(picky).value shouldBe 6
  }

  it should "be refused while a run holds the thread, here or in another runtime" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val gate    = CountDownLatch(1)
    val entered = CountDownLatch(1)
    val b       = GraphBuilder("slow", "v1")
    val log     = StateKey.appending[String]("log")
    val node = b.node[String]("n", writes = Set(log)) { (input, _, _) =>
      if input == "hold" then
        entered.countDown()
        gate.await(5, TimeUnit.SECONDS)
      continue(Command.empty.update(log, input))
    }
    val graph = b.compile(node)(_.get(log)).value
    runtime.start(thread, graph, "first").awaited.value.completed
    val running = runtime.start(thread, graph, "hold", config("holder")).value
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    val at = store.latest(thread).value.value.checkpoint.id
    runtime.updateState(thread, graph, at, StateUpdate.update(log, "x")).left.value shouldBe
      GraphError.ThreadBusy("t", Some(at), Some("holder"))
    GraphRuntime(store).updateState(thread, graph, at, StateUpdate.update(log, "x")).left.value shouldBe
      GraphError.ThreadBusy("t", Some(at), Some("holder"))
    gate.countDown()
    await(running).completed
    val done = store.latest(thread).value.value.checkpoint.id
    GraphRuntime(store).updateState(thread, graph, done, StateUpdate.update(log, "x")).value
    // the update released the thread and its claim
    runtime.start(thread, graph, "last").awaited.value.completed._2 shouldBe Vector("first", "hold", "x", "last")
  }

  it should "keep supersteps increasing, so with one RunId reused no later task runs where an earlier one did" in {
    // a tool call's idempotency key is derived from (thread, checkpoint id, superstep, task, call id): the
    // positions below must never repeat on the thread, whatever RunId the runs and updates reuse
    val runtime      = GraphRuntime.inMemory()
    val n            = Notes()
    val same         = config("same")
    val parked       = runtime.start(thread, n.graph, "ask-1", same).awaited.value.suspended
    val beforeUpdate = n.positions.asScala.toVector
    val latest       = runtime.history(thread, config = same).value.head
    runtime.updateState(thread, n.graph, latest.id, StateUpdate.update(n.count, 1), config = same).value
    runtime.resume(thread, n.graph, Map(parked.interrupts.head.id -> n.review.answer("ok")), same).awaited.value
    val done = runtime.history(thread, config = same).value.head
    runtime.updateState(thread, n.graph, done.id, StateUpdate.update(n.count, 2), config = same).value
    runtime.start(thread, n.graph, "next", same).awaited.value.completed

    val all  = n.positions.asScala.toVector
    val keys = all.map(p => (p.threadId, p.checkpointId, p.superstep, p.taskId))
    keys.distinct shouldBe keys
    all.drop(beforeUpdate.size).map(_.superstep).min should be > beforeUpdate.map(_.superstep).max
    // and the superstep only grows along the thread
    all.map(_.superstep) shouldBe all.map(_.superstep).sorted
    val history = runtime.history(thread, config = same).value
    ids(history).distinct shouldBe ids(history)
  }

  // ---- retention ----

  "prune" should "keep the newest checkpoints by count, always the latest, and drop the events before them" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val n       = Notes()
    runtime.start(thread, n.graph, "a").awaited.value.completed
    runtime.start(thread, n.graph, "b").awaited.value.completed
    val all = runtime.history(thread).value

    runtime.prune(thread, RetentionPolicy().withMaxCheckpoints(2)).value
    val kept = runtime.history(thread).value
    kept shouldBe all.take(2)
    val oldest = kept.last.id
    val floor  = store.eventsAfter(thread, 0L, 1).left.value
    floor shouldBe a[GraphError.ReplayUnavailable]
    val earliest = floor.asInstanceOf[GraphError.ReplayUnavailable].earliestSeq
    val events   = store.eventsAfter(thread, earliest - 1, 1000).value
    events.head.checkpointId shouldBe Some(oldest)
    runtime.fork(thread, all.last.id, other).left.value shouldBe GraphError.CheckpointNotFound("t", all.last.id)

    runtime.prune(thread, RetentionPolicy(maxCheckpoints = Some(1))).value
    ids(runtime.history(thread).value) shouldBe Vector(all.head.id)
    runtime.start(thread, n.graph, "c").awaited.value.completed._2.takeRight(2) shouldBe Vector("c", "after")
  }

  it should "remove checkpoints and events older than maxAge by the store's clock, never the latest" in {
    val clock   = TestClock(Instant.parse("2026-10-10T00:00:00Z"))
    val store   = InMemoryCheckpointer(clock)
    val runtime = GraphRuntime(store, clock)
    val n       = Notes()
    runtime.start(thread, n.graph, "a").awaited.value.completed
    val old = runtime.history(thread).value
    clock.advance(2.hours)
    runtime.start(thread, n.graph, "b").awaited.value.completed
    val young = runtime.history(thread).value.take(runtime.history(thread).value.size - old.size)

    runtime.prune(thread, RetentionPolicy(maxAge = Some(1.hour))).value
    runtime.history(thread).value shouldBe young
    store.eventsAfter(thread, 0L, 1000).left.value shouldBe a[GraphError.ReplayUnavailable]

    clock.advance(10.days)
    runtime.prune(thread, RetentionPolicy(maxAge = Some(1.hour))).value
    runtime.history(thread).value shouldBe Vector(young.head)
    // every event is older than an hour now, so none is left to replay
    store.eventsAfter(thread, 0L, 1000).left.value shouldBe a[GraphError.ReplayUnavailable]
    runtime.start(thread, n.graph, "c").awaited.value.completed
  }

  it should "keep a running checkpoint's pending writes, so recover still reuses them" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val w       = Siblings()
    runtime.start(thread, w.graph, ()).awaited.value.failed
    val writes = store.latest(thread).value.value.pendingWrites
    writes should have size 1
    runtime.prune(thread, RetentionPolicy(maxCheckpoints = Some(1), maxEvents = Some(0))).value
    store.latest(thread).value.value.pendingWrites shouldBe writes
    runtime.history(thread).value should have size 1
    w.failing.set(false)
    w.ran.clear()
    runtime.recover(thread, w.graph).awaited.value.completed._2 shouldBe Vector("a", "b")
    w.runs shouldBe Vector("b")
  }

  it should "keep only the newest events by maxEvents, and accept an unknown thread" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val n       = Notes()
    runtime.start(thread, n.graph, "a").awaited.value.completed
    val all = store.eventsAfter(thread, 0L, 1000).value
    runtime.prune(thread, RetentionPolicy(maxEvents = Some(2))).value
    store.eventsAfter(thread, all.size - 2L, 1000).value shouldBe all.takeRight(2)
    store.eventsAfter(thread, 0L, 1000).left.value shouldBe GraphError.ReplayUnavailable("t", all.size - 1L)
    // a looser policy never lowers the floor
    runtime.prune(thread, RetentionPolicy.keepAll).value
    store.eventsAfter(thread, 0L, 1000).left.value shouldBe GraphError.ReplayUnavailable("t", all.size - 1L)
    runtime.history(thread).value.size should be > 1
    runtime.prune(ThreadId("unknown"), RetentionPolicy(maxCheckpoints = Some(1))).value shouldBe (())
  }

  "RetentionPolicy" should "validate its limits, and change one with each setter" in {
    RetentionPolicy() shouldBe RetentionPolicy.keepAll
    an[IllegalArgumentException] should be thrownBy RetentionPolicy(maxAge = Some(0.seconds))
    an[IllegalArgumentException] should be thrownBy RetentionPolicy(maxCheckpoints = Some(0))
    an[IllegalArgumentException] should be thrownBy RetentionPolicy(maxEvents = Some(-1))
    an[IllegalArgumentException] should be thrownBy RetentionPolicy().withMaxCheckpoints(0)
    RetentionPolicy.of(maxAge = Some(-1.second), maxCheckpoints = Some(0)).left.value.message should
      (include("maxAge").and(include("maxCheckpoints")))
    RetentionPolicy.of(maxEvents = Some(0)).value.maxEvents shouldBe Some(0)
    val p = RetentionPolicy().withMaxAge(1.day).withMaxCheckpoints(10).withMaxEvents(100)
    (p.maxAge, p.maxCheckpoints, p.maxEvents) shouldBe (Some(1.day), Some(10), Some(100))
    val q = p.withMaxAge(None).withMaxCheckpoints(None).withMaxEvents(None)
    q shouldBe RetentionPolicy.keepAll
  }

  // ---- a guardrail Block retracts its turn ----

  "A Block" should "retract its turn from the history: only the closing Failed checkpoint remains of it" in {
    for durability <- Durability.values do
      val store   = InMemoryCheckpointer()
      val runtime = GraphRuntime(store)
      val n       = Notes()
      runtime.start(thread, n.graph, "a", durability = durability).awaited.value.completed
      val settled = runtime.history(thread).value
      runtime.start(thread, n.graph, "bad-1", durability = durability).awaited.value.failed
      val history = runtime.history(thread).value
      withClue(durability) {
        history.tail shouldBe settled
        history.head.status shouldBe CheckpointStatus.Failed
        history.flatMap(c => logAt(n, c)).filter(_.contains("bad-1")) shouldBe Vector("blocked:bad-1")
      }
  }

  it should "retract a turn that suspended and resumed before it was blocked, and a fork cannot reach it" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val b       = GraphBuilder("review-then-block", "v1")
    val log     = StateKey.appending[String]("log")
    val verdict = b.resumeNode[String, String]("verdict", writes = Set(log)) { (resumed, _, _) =>
      if resumed.answer == "block" then
        NodeResult.Block(StateUpdate.update(log, "withdrawn"), ValidationError("guard", "blocked after review"))
      else continue(Command.empty.update(log, s"kept:${resumed.question}"))
    }
    val draft = b.node[String]("draft", writes = Set(log)) { (input, _, _) =>
      NodeResult.Suspend(StateUpdate.update(log, s"secret:$input"), input, verdict)
    }
    val graph = b.compile(draft)(_.get(log)).value

    val parked   = runtime.start(thread, graph, "one").awaited.value.suspended
    val openTurn = runtime.history(thread).value
    openTurn.head.status shouldBe CheckpointStatus.Suspended
    runtime.resume(thread, graph, Map(parked.interrupts.head.id -> verdict.answer("keep"))).awaited.value.completed
    val settled = runtime.history(thread).value

    val again  = runtime.start(thread, graph, "two").awaited.value.suspended
    val secret = runtime.history(thread).value.head
    graph.restore(secret.snapshot).value.state.get(log).value.last shouldBe "secret:two"
    runtime.resume(thread, graph, Map(again.interrupts.head.id -> verdict.answer("block"))).awaited.value.failed

    val history = runtime.history(thread).value
    history.tail shouldBe settled
    // only the closing Failed checkpoint is left of the turn; the suspended one holding its draft is gone
    history.head.status shouldBe CheckpointStatus.Failed
    // the retraction removes only earlier checkpoints: the closing one holds what the Block's own update
    // leaves, and this node's appends, so the draft stays there (Agent's output Block commits RemoveTurn
    // instead; a custom blocking node must remove the content in its Block update)
    val settledLog = graph.restore(settled.head.snapshot).value.state.get(log).value
    graph.restore(history.head.snapshot).value.state.get(log).value shouldBe
      settledLog ++ Vector("secret:two", "withdrawn")
    history.tail.flatMap(c => graph.restore(c.snapshot).value.state.get(log).value) should not contain "secret:two"
    runtime.checkpoint(thread, secret.id).left.value shouldBe GraphError.CheckpointNotFound("t", secret.id)
    runtime.fork(thread, secret.id, other).left.value shouldBe GraphError.CheckpointNotFound("t", secret.id)
    // the thread is usable: a new turn starts from the Failed checkpoint
    runtime.start(thread, graph, "three").awaited.value.suspended
  }

  it should "retract a first turn entirely when the thread has no settled checkpoint before it" in {
    val runtime = GraphRuntime.inMemory()
    val n       = Notes()
    runtime.start(thread, n.graph, "bad-0").awaited.value.failed
    val history = runtime.history(thread).value
    history.map(_.status) shouldBe Vector(CheckpointStatus.Failed)
    history.head.parent shouldBe defined
    runtime.checkpoint(thread, history.head.parent.value).left.value shouldBe a[GraphError.CheckpointNotFound]
  }
}
