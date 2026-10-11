package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, LoneElement, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, LinkedBlockingQueue, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.collection.mutable

class GraphRuntimeSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues with LoneElement {

  private val thread = ThreadId("thread-1")

  /**
   * plan fans its input out to workers (dynamic join -> summarize). Each worker counts its calls,
   * emits a custom event and live progress, and fails once if its item is in `failOnce`.
   * `onWorker` runs inside every worker task, for tests that observe the world mid-run.
   */
  final private class Fixture(version: String = "v1") {
    val calls                              = new ConcurrentHashMap[String, AtomicInteger]()
    val failOnce                           = ConcurrentHashMap.newKeySet[String]()
    @volatile var onWorker: String => Unit = _ => ()

    val results = StateKey.appending[String]("results")
    val log     = StateKey.appending[String]("log")

    val graph: CompiledGraph[Vector[String], Vector[String]] = {
      val b = GraphBuilder("fan-out", version)
      val worker = b.node[String]("worker", writes = Set(results)) { (item, _, context) =>
        calls.computeIfAbsent(item, _ => new AtomicInteger()).incrementAndGet()
        onWorker(item)
        context.progress("test.progress", 1, ujson.Str(s"working on $item"))
        context.emit("worked", 1, ujson.Obj("item" -> item))
        if failOnce.remove(item) then NodeResult.Fail(ValidationError("worker", s"$item failed"))
        else continue(Command.empty.update(results, item.toUpperCase))
      }
      val summarize = b.node[Unit]("summarize", writes = Set(log)) { (_, state, _) =>
        NodeResult.fromResult(state.get(results).map(r => Command.empty.update(log, s"summary(${r.size})")))
      }
      val join = b.dynamicJoin("workers", summarize)
      val plan = b.node[Vector[String]]("plan")((items, _, _) => continue(Command.empty.fanOut(join, worker, items)))
      b.compile(plan)(_.get(results)).value
    }

    def callsOf(item: String): Int = Option(calls.get(item)).fold(0)(_.get)
  }

  /**
   * Records what a subscriber receives. Listeners run on the subscription's dispatcher thread, so
   * the test thread takes events from a queue, waiting up to 5s for each.
   */
  final private class Recorder {
    private val queue = new LinkedBlockingQueue[StreamEvent]()
    private val taken = mutable.ArrayBuffer.empty[StreamEvent]

    /** Durable events delivered so far; readable from any thread. */
    val delivered = new AtomicInteger()

    def listener: StreamEvent => Unit = { event =>
      if event.isInstanceOf[StreamEvent.Durable] then delivered.incrementAndGet(): Unit
      queue.put(event)
    }

    def next(): StreamEvent = Option(queue.poll(5, TimeUnit.SECONDS)).getOrElse(fail("no event within 5s"))

    /** Takes events until the durable event numbered `seq` has arrived. */
    def through(seq: Long): Recorder = {
      while durable.lastOption.forall(_.seq < seq) do taken += next()
      this
    }

    /** Takes events until everything `store` holds for the thread has arrived. */
    def caughtUp(store: Checkpointer): Recorder = through(lastSeq(store))

    /** Nothing more arrives within a short wait. */
    def quiet(): Unit = Option(queue.poll(200, TimeUnit.MILLISECONDS)) shouldBe None

    /** No further durable event arrives within a short wait; live ones may. */
    def noMoreDurable(): Unit =
      Iterator
        .continually(Option(queue.poll(200, TimeUnit.MILLISECONDS)))
        .takeWhile(_.isDefined)
        .flatten
        .collect { case d: StreamEvent.Durable => d }
        .toVector shouldBe empty

    def durable: Vector[EventRecord]   = taken.toVector.collect { case StreamEvent.Durable(r) => r }
    def live: Vector[StreamEvent.Live] = taken.toVector.collect { case l: StreamEvent.Live => l }
  }

  /** The thread's last committed `seq`, read from the earliest event the store still holds. */
  private def lastSeq(store: Checkpointer): Long = {
    val events = store.eventsAfter(thread, 0L, 1000) match {
      case Left(GraphError.ReplayUnavailable(_, earliest)) => store.eventsAfter(thread, earliest - 1, 1000)
      case other                                           => other
    }
    events.value.lastOption.fold(0L)(_.seq)
  }

  /** A store whose commits can be slowed down, or made to fail as if the process had died. */
  final private class ControlledCheckpointer(val underlying: InMemoryCheckpointer = InMemoryCheckpointer())
      extends Checkpointer {
    @volatile var delayMillis: Long = 0L
    val crashed                     = new AtomicBoolean(false)
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = {
      if delayMillis > 0 then Thread.sleep(delayMillis)
      if crashed.get then Left(ValidationError("store", "simulated crash")) else underlying.commit(threadId, commit)
    }
    def claim(threadId: ThreadId, request: org.llm4s.agent.graph.ClaimRequest) = underlying.claim(threadId, request)
    def renew(
      threadId: ThreadId,
      token: org.llm4s.agent.graph.FencingToken,
      ttl: scala.concurrent.duration.FiniteDuration
    ) =
      underlying.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: org.llm4s.agent.graph.FencingToken) = underlying.release(threadId, token)
    def latest(threadId: ThreadId)                                             = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) = underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long)          = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId)                            = underlying.deleteThread(threadId)
    def history(threadId: ThreadId, before: Option[String], limit: Int) = underlying.history(threadId, before, limit)
    def checkpoint(threadId: ThreadId, checkpointId: String)            = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy)              = underlying.prune(threadId, policy)
  }

  private def kinds(records: Vector[EventRecord]): Vector[String] =
    records.map(r => r.event.toString.takeWhile(_ != '(') + r.nodeId.fold("")(n => s"@$n"))

  private def contiguous(records: Vector[EventRecord]): Unit =
    records.map(_.seq) shouldBe (1L to records.size.toLong).toVector

  "GraphRuntime" should "run a new thread to completion and persist a completed checkpoint and its events" in {
    val f        = Fixture()
    val store    = InMemoryCheckpointer()
    val runtime  = GraphRuntime(store)
    val recorder = Recorder()
    runtime.subscribe(thread)(recorder.listener).value

    runtime
      .start(thread, f.graph, Vector("a", "b"), RunConfig().withRunId(RunId("run-1")))
      .awaited
      .value
      .completed
      ._2 shouldBe Vector("A", "B")

    store.latest(thread).value.map(_.checkpoint.status) shouldBe Some(CheckpointStatus.Completed)
    val events = recorder.caughtUp(store).durable
    contiguous(events)
    kinds(events) shouldBe Vector(
      "RunStarted",
      "TaskCompleted@plan",
      "CheckpointCommitted",
      "TaskCompleted@worker",
      "Custom@worker",
      "TaskCompleted@worker",
      "Custom@worker",
      "CheckpointCommitted",
      "TaskCompleted@summarize",
      "CheckpointCommitted",
      "RunCompleted"
    )
    events.map(_.runId).distinct shouldBe Vector("run-1")
    store.eventsAfter(thread, 0L, 100).value shouldBe events
    // the workers run concurrently, so their live progress arrives in either order
    recorder.live.map(_.payload).toSet shouldBe Set(ujson.Str("working on a"), ujson.Str("working on b"))
    recorder.live should have size 2
  }

  it should "apply a new input to a completed thread's state" in {
    val f       = Fixture()
    val runtime = GraphRuntime(InMemoryCheckpointer())
    runtime
      .start(thread, f.graph, Vector("a"), RunConfig().withRunId(RunId("run-1")))
      .awaited
      .value
      .completed
      ._2 shouldBe Vector("A")
    val (state, output) =
      runtime.start(thread, f.graph, Vector("b", "c"), RunConfig().withRunId(RunId("run-2"))).awaited.value.completed
    output shouldBe Vector("A", "B", "C")
    state.get(f.log).value shouldBe Vector("summary(1)", "summary(3)")
  }

  it should "refuse to start over an incomplete execution and to recover a complete one" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    runtime.recover(thread, f.graph, RunConfig().withRunId(RunId("r0"))).awaited.left.value shouldBe GraphError
      .NothingToRecover(thread.value)

    f.failOnce.add("b")
    runtime.start(thread, f.graph, Vector("a", "b"), RunConfig().withRunId(RunId("run-1"))).awaited.value.failed
    val incomplete = store.latest(thread).value.get.checkpoint.id
    runtime.start(thread, f.graph, Vector("c"), RunConfig().withRunId(RunId("run-2"))).awaited.left.value shouldBe
      GraphError.IncompleteRun(thread.value, incomplete)

    runtime.recover(thread, f.graph, RunConfig().withRunId(RunId("run-3"))).awaited.value.completed
    runtime.recover(thread, f.graph, RunConfig().withRunId(RunId("run-4"))).awaited.left.value shouldBe GraphError
      .NothingToRecover(thread.value)
  }

  it should "refuse every call on a thread whose run is still executing, rather than recover it" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val during  = mutable.ArrayBuffer.empty[(Option[String], Vector[Result[RunResult[Vector[String]]]])]
    f.onWorker = _ =>
      during += store.latest(thread).value.map(_.checkpoint.id) -> Vector(
        runtime.recover(thread, f.graph, RunConfig().withRunId(RunId("thief"))).awaited,
        runtime.start(thread, f.graph, Vector("x"), RunConfig().withRunId(RunId("thief"))).awaited,
        runtime.resume(thread, f.graph, Map.empty, RunConfig().withRunId(RunId("thief"))).awaited
      )

    runtime
      .start(thread, f.graph, Vector("a"), RunConfig().withRunId(RunId("run-1")))
      .awaited
      .value
      .completed
      ._2 shouldBe Vector("A")

    val (latest, results) = during.loneElement
    latest.value should startWith("run-1/")
    results.map(_.left.value) shouldBe Vector.fill(3)(GraphError.ThreadBusy(thread.value, latest, Some("run-1")))
    f.callsOf("a") shouldBe 1
    f.callsOf("x") shouldBe 0
    runtime.recover(thread, f.graph, RunConfig().withRunId(RunId("run-2"))).awaited.left.value shouldBe GraphError
      .NothingToRecover(thread.value)
  }

  it should "recover without re-running siblings whose results were committed" in {
    Seq[CompiledGraph[Vector[String], Vector[String]] => CompiledGraph[Vector[String], Vector[String]]](
      identity,
      _.withExecutor(TaskExecutor.sequential),
      _.withExecutor(reversed),
      _.withExecutor(concurrent(7L))
    ).foreach { variant =>
      val f       = Fixture()
      val graph   = variant(f.graph)
      val store   = InMemoryCheckpointer()
      val runtime = GraphRuntime(store)
      f.failOnce.add("b")

      val (_, error) =
        runtime.start(thread, graph, Vector("a", "b", "c"), RunConfig().withRunId(RunId("run-1"))).awaited.value.failed
      error shouldBe a[GraphError.NodeFailed]
      store.latest(thread).value.get.pendingWrites.map(_.nodeId) shouldBe Vector("worker", "worker")

      val recorder = Recorder()
      runtime.subscribe(thread, afterSeq = store.eventsAfter(thread, 0L, 1000).value.size.toLong)(recorder.listener)
      runtime.recover(thread, graph, RunConfig().withRunId(RunId("run-2"))).awaited.value.completed._2 shouldBe Vector(
        "A",
        "B",
        "C"
      )
      (f.callsOf("a"), f.callsOf("b"), f.callsOf("c")) shouldBe ((1, 2, 1))
      recorder.caughtUp(store)
      kinds(recorder.durable).take(3) shouldBe Vector("RunRecovered", "TaskCompleted@worker", "Custom@worker")
      recorder.durable.map(_.runId).distinct shouldBe Vector("run-2")
      contiguous(store.eventsAfter(thread, 0L, 1000).value)
    }
  }

  it should "discard a failed task's custom events but record its failure" in {
    val f     = Fixture()
    val store = InMemoryCheckpointer()
    f.failOnce.add("a")
    GraphRuntime(store).start(thread, f.graph, Vector("a"), RunConfig().withRunId(RunId("run-1"))).awaited.value.failed
    kinds(store.eventsAfter(thread, 0L, 100).value) shouldBe Vector(
      "RunStarted",
      "TaskCompleted@plan",
      "CheckpointCommitted",
      "TaskFailed@worker",
      "RunFailed"
    )
  }

  it should "replay from a sequence, then continue live without gaps or duplicates" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val late    = Recorder()
    // subscribe from inside a worker, while the run is committing
    f.onWorker = item => if item == "b" then runtime.subscribe(thread)(late.listener).value
    val early = Recorder()
    runtime.subscribe(thread)(early.listener).value
    runtime.start(thread, f.graph, Vector("a", "b", "c"), RunConfig().withRunId(RunId("run-1"))).awaited.value.completed
    contiguous(late.caughtUp(store).durable)
    late.durable shouldBe early.caughtUp(store).durable

    val tail = Recorder()
    runtime.subscribe(thread, afterSeq = 5L)(tail.listener).value
    tail.caughtUp(store).durable shouldBe early.durable.drop(5)
  }

  it should "stop delivering to a cancelled subscription" in {
    val f        = Fixture()
    val store    = InMemoryCheckpointer()
    val runtime  = GraphRuntime(store)
    val recorder = Recorder()
    runtime.subscribe(thread)(recorder.listener).value.cancel()
    runtime.start(thread, f.graph, Vector("a"), RunConfig().withRunId(RunId("run-1"))).awaited.value.completed
    val witness = Recorder()
    runtime.subscribe(thread)(witness.listener).value
    witness.caughtUp(store)
    recorder.quiet()
  }

  it should "report the replay floor after compaction" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    runtime.start(thread, f.graph, Vector("a"), RunConfig().withRunId(RunId("run-1"))).awaited.value.completed
    store.compactEvents(thread, 5L).value
    val refused = Recorder()
    runtime.subscribe(thread)(refused.listener).value
    refused.next() shouldBe StreamEvent.Disconnected(
      0L,
      DisconnectReason.ReplayFailed(GraphError.ReplayUnavailable(thread.value, 5L))
    )
    val recorder = Recorder()
    runtime.subscribe(thread, afterSeq = 4L)(recorder.listener).value
    recorder.caughtUp(store).durable.head.seq shouldBe 5L
  }

  it should "reject a completed thread's checkpoint written by a different graph" in {
    val runtime = GraphRuntime(InMemoryCheckpointer())
    runtime
      .start(thread, Fixture("v1").graph, Vector("a"), RunConfig().withRunId(RunId("run-1")))
      .awaited
      .value
      .completed
    runtime
      .start(thread, Fixture("v2").graph, Vector("b"), RunConfig().withRunId(RunId("run-2")))
      .awaited
      .left
      .value shouldBe a[GraphError.RestoreRejected]
  }

  it should "fail the run when a synchronous commit fails" in {
    val f     = Fixture()
    val store = ControlledCheckpointer()
    f.onWorker = _ => store.crashed.set(true)
    val (_, error) =
      GraphRuntime(store)
        .start(thread, f.graph, Vector("a"), RunConfig().withRunId(RunId("run-1")))
        .awaited
        .value
        .failed
    error shouldBe a[GraphError.CheckpointWriteFailed]
    store.underlying.latest(thread).value.get.checkpoint.snapshot.superstep shouldBe 1
  }

  it should "reject a recovered pending write attributed to another node or writing undeclared keys" in {
    val f     = Fixture()
    val store = InMemoryCheckpointer()
    f.failOnce.add("b")
    GraphRuntime(store)
      .start(thread, f.graph, Vector("a", "b"), RunConfig().withRunId(RunId("run-1")))
      .awaited
      .value
      .failed

    def tampered(change: PendingWrite => PendingWrite): Checkpointer = new Checkpointer {
      def commit(threadId: ThreadId, commit: Commit)                             = store.commit(threadId, commit)
      def claim(threadId: ThreadId, request: org.llm4s.agent.graph.ClaimRequest) = store.claim(threadId, request)
      def renew(
        threadId: ThreadId,
        token: org.llm4s.agent.graph.FencingToken,
        ttl: scala.concurrent.duration.FiniteDuration
      ) =
        store.renew(threadId, token, ttl)
      def release(threadId: ThreadId, token: org.llm4s.agent.graph.FencingToken) = store.release(threadId, token)
      def latest(threadId: ThreadId) =
        store.latest(threadId).map(_.map(s => s.copy(pendingWrites = s.pendingWrites.map(change))))
      def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int)     = store.eventsAfter(threadId, afterSeq, limit)
      def compactEvents(threadId: ThreadId, beforeSeq: Long)              = store.compactEvents(threadId, beforeSeq)
      def deleteThread(threadId: ThreadId)                                = store.deleteThread(threadId)
      def history(threadId: ThreadId, before: Option[String], limit: Int) = store.history(threadId, before, limit)
      def checkpoint(threadId: ThreadId, checkpointId: String)            = store.checkpoint(threadId, checkpointId)
      def prune(threadId: ThreadId, policy: RetentionPolicy)              = store.prune(threadId, policy)
    }
    GraphRuntime(tampered(_.copy(nodeId = "summarize")))
      .recover(thread, f.graph, RunConfig().withRunId(RunId("run-2")))
      .awaited
      .left
      .value
      .message should
      include("names node 'summarize', not 'worker'")
    val logWrite = EncodedOperation.Update("log", VersionedJson(1, ujson.Str("forged")))
    GraphRuntime(tampered(w => w.copy(operations = w.operations :+ logWrite)))
      .recover(thread, f.graph, RunConfig().withRunId(RunId("run-3")))
      .awaited
      .left
      .value shouldBe a[GraphError.UndeclaredWrite]
    // the genuine writes still recover
    GraphRuntime(store)
      .recover(thread, f.graph, RunConfig().withRunId(RunId("run-4")))
      .awaited
      .value
      .completed
      ._2 shouldBe Vector("A", "B")
  }

  it should "keep durable events detached from what subscribers and readers are handed" in {
    val f        = Fixture()
    val store    = InMemoryCheckpointer()
    val runtime  = GraphRuntime(store)
    val tamperer = Recorder()
    runtime
      .subscribe(thread) { event =>
        event match {
          case StreamEvent.Durable(record) =>
            record.event match {
              case RunEvent.Custom(_, _, payload) => payload("item") = "tampered"
              case _                              => ()
            }
          case _ => ()
        }
        tamperer.listener(event)
      }
      .value
    runtime.start(thread, f.graph, Vector("a"), RunConfig().withRunId(RunId("run-1"))).awaited.value.completed
    // the listener has tampered with everything it was handed
    tamperer.caughtUp(store)
    def customs =
      store.eventsAfter(thread, 0L, 100).value.collect { case EventRecord(_, _, _, _, _, _, _, c: RunEvent.Custom) =>
        c.payload
      }
    customs shouldBe Vector(ujson.Obj("item" -> "a"))
    customs.head("item") = "tampered again"
    customs shouldBe Vector(ujson.Obj("item" -> "a"))
  }

  it should "report a failed exit commit rather than only the run's own failure" in {
    Seq(Durability.OnExit, Durability.Async).foreach { durability =>
      val f     = Fixture()
      val store = ControlledCheckpointer()
      f.failOnce.add("a")
      f.onWorker = _ => store.crashed.set(true)
      val (_, error) = GraphRuntime(store)
        .start(thread, f.graph, Vector("a"), RunConfig().withRunId(RunId("run-1")), durability)
        .awaited
        .value
        .failed
      error match {
        case GraphError.CheckpointWriteFailed(_, cause, Some(runError)) =>
          cause.message should include("simulated crash")
          runError shouldBe a[GraphError.NodeFailed]
        case other => fail(s"$durability: $other")
      }
    }
  }

  "Async durability" should "run ahead of its commits but deliver durable events only once committed" in {
    val f     = Fixture()
    val store = ControlledCheckpointer()
    store.delayMillis = 20L
    val runtime = GraphRuntime(store)

    val deliveredBeforeCommit = new AtomicInteger()
    val recorder              = Recorder()
    runtime
      .subscribe(thread) { event =>
        event match {
          case StreamEvent.Durable(record) =>
            if !store.underlying.eventsAfter(thread, record.seq - 1, 1).value.contains(record) then
              deliveredBeforeCommit.incrementAndGet()
          case _ => ()
        }
        recorder.listener(event)
      }
      .value
    val durableSeenByWorker = mutable.ArrayBuffer.empty[Int]
    val committedSuperstep  = mutable.ArrayBuffer.empty[Int]
    f.onWorker = _ =>
      synchronized {
        durableSeenByWorker += recorder.delivered.get
        committedSuperstep += store.underlying.latest(thread).value.fold(-1)(_.checkpoint.snapshot.superstep)
      }

    runtime
      .start(thread, f.graph, Vector("a", "b"), RunConfig().withRunId(RunId("run-1")), Durability.Async)
      .awaited
      .value
      .completed
      ._2 shouldBe
      Vector("A", "B")

    // workers run in superstep 1 while the superstep-1 checkpoint is still queued
    committedSuperstep.forall(_ < 1) shouldBe true
    durableSeenByWorker.forall(_ < 3) shouldBe true
    recorder.caughtUp(store.underlying)
    deliveredBeforeCommit.get shouldBe 0
    // live progress is not held back behind commits
    recorder.live.size shouldBe 2
    contiguous(recorder.durable)
    recorder.durable shouldBe store.underlying.eventsAfter(thread, 0L, 100).value
    store.underlying.latest(thread).value.map(_.checkpoint.status) shouldBe Some(CheckpointStatus.Completed)
  }

  it should "survive a crash: subscribers saw only committed events, and recovery continues their sequence" in {
    val f     = Fixture()
    val store = ControlledCheckpointer()
    store.delayMillis = 5L
    val crashOnce = new AtomicBoolean(true)
    // crash while running "c", once the results of "a" and "b" are durable but nothing after them
    f.onWorker = item =>
      if item == "c" && crashOnce.getAndSet(false) then
        val deadline = System.nanoTime() + 5_000_000_000L
        while store.underlying.latest(thread).value.forall(_.pendingWrites.size < 2) && System.nanoTime() < deadline do
          Thread.sleep(1)
        store.crashed.set(true)
    // sequential, so "a" and "b" are exactly the results durable when "c" crashes
    val graph  = f.graph.withExecutor(TaskExecutor.sequential)
    val before = Recorder()
    val first  = GraphRuntime(store)
    first.subscribe(thread)(before.listener).value

    val (_, error) =
      first
        .start(thread, graph, Vector("a", "b", "c", "d"), RunConfig().withRunId(RunId("run-1")), Durability.Async)
        .awaited
        .value
        .failed
    error shouldBe a[GraphError.CheckpointWriteFailed]
    val durable = store.underlying.eventsAfter(thread, 0L, 1000).value
    before.caughtUp(store.underlying).durable shouldBe durable
    before.noMoreDurable()

    // a new process over the same store
    store.crashed.set(false)
    val second = GraphRuntime(store)
    second
      .recover(thread, graph, RunConfig().withRunId(RunId("run-2")), Durability.Async)
      .awaited
      .value
      .completed
      ._2 shouldBe
      Vector("A", "B", "C", "D")
    val after = Recorder()
    second.subscribe(thread)(after.listener).value
    contiguous(after.caughtUp(store.underlying).durable)
    after.durable.take(durable.size) shouldBe durable
    after.durable.drop(durable.size).map(_.runId).distinct shouldBe Vector("run-2")
    // results durable before the crash are not recomputed
    Seq("a", "b", "c", "d").map(f.callsOf) shouldBe Seq(1, 1, 2, 2)
  }

  "OnExit durability" should "write only its claim until the run ends, then commit the rest at once" in {
    val f              = Fixture()
    val store          = InMemoryCheckpointer()
    val runtime        = GraphRuntime(store)
    val recorder       = Recorder()
    val seenMidRun     = mutable.ArrayBuffer.empty[(Option[StoredCheckpoint], Int)]
    val claimDelivered = new CountDownLatch(1)
    runtime
      .subscribe(thread) { event =>
        recorder.listener(event)
        claimDelivered.countDown()
      }
      .value
    // delivery is asynchronous: wait for the claim's RunStarted, then see that nothing follows it
    f.onWorker = _ => {
      claimDelivered.await(5, TimeUnit.SECONDS): Unit
      synchronized(seenMidRun += (store.latest(thread).value -> recorder.delivered.get))
    }

    runtime
      .start(thread, f.graph, Vector("a", "b"), RunConfig().withRunId(RunId("run-1")), Durability.OnExit)
      .awaited
      .value
      .completed
    // mid-run the store holds just the claim - the starting checkpoint - and its RunStarted
    seenMidRun.toVector.map((stored, seen) => stored.map(_.checkpoint.id) -> seen) shouldBe
      Vector(Some("run-1/1") -> 1, Some("run-1/1") -> 1)
    recorder.caughtUp(store).live.size shouldBe 2
    contiguous(recorder.durable)
    kinds(recorder.durable).last shouldBe "RunCompleted"
    store.latest(thread).value.map(s => s.checkpoint.status -> s.checkpoint.parent) shouldBe
      Some(CheckpointStatus.Completed -> Some("run-1/1"))
  }

  it should "persist a failed run's completed siblings at exit so recovery skips them" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    runtime
      .start(thread, f.graph, Vector("x"), RunConfig().withRunId(RunId("run-0")), Durability.OnExit)
      .awaited
      .value
      .completed
    val previous = store.latest(thread).value.get.checkpoint.id

    f.failOnce.add("b")
    runtime
      .start(thread, f.graph, Vector("a", "b"), RunConfig().withRunId(RunId("run-1")), Durability.OnExit)
      .awaited
      .value
      .failed
    val stored = store.latest(thread).value.get
    // the exit commit sits on the run's claim, which sits on the previous run's completion; checkpoint
    // ids count along the thread, so the claim is the sixth
    stored.checkpoint.parent shouldBe Some("run-1/6")
    previous shouldBe "run-0/5"
    store.checkpoint(thread, "run-1/6").value.value.parent shouldBe Some(previous)
    stored.pendingWrites.map(_.taskId) shouldBe Vector(s"${stored.checkpoint.snapshot.superstep}.0")

    runtime
      .recover(thread, f.graph, RunConfig().withRunId(RunId("run-2")), Durability.OnExit)
      .awaited
      .value
      .completed
      ._2 shouldBe Vector(
      "X",
      "A",
      "B"
    )
    (f.callsOf("a"), f.callsOf("b")) shouldBe ((1, 2))
  }
}
