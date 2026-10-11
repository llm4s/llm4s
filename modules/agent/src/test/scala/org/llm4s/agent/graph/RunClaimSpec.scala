package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.{ ProcessingError, ValidationError }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * How a [[GraphRuntime]] holds its runs' claims on a store shared with other runtimes (#1700): the
 * claim taken at admission and given back on every exit, renewal while the run executes, the holder
 * named by `ThreadBusy`, and a run that lost its claim refused by the store. The store side of the
 * contract is `CheckpointerContract` in `llm4s-agent-testkit`.
 */
class RunClaimSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val thread = ThreadId("t")
  private val start  = Instant.parse("2026-10-09T12:00:00Z")

  /** A clock the test moves. */
  final private class MovingClock extends Clock {
    private val current                        = new AtomicReference(start)
    def instant(): Instant                     = current.get
    def getZone: ZoneId                        = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    def advance(by: FiniteDuration): Unit      = current.updateAndGet(_.plusNanos(by.toNanos)): Unit
  }

  /** A single node that records its run position, and whose first call waits for `gate`. */
  final private class Held {
    val gate      = new CountDownLatch(1)
    val entered   = new CountDownLatch(1)
    val first     = new AtomicBoolean(true)
    val positions = new java.util.concurrent.ConcurrentLinkedQueue[RunPosition]()
    val log       = StateKey.appending[String]("log")
    val graph: CompiledGraph[String, Vector[String]] = {
      val b = GraphBuilder("held", "v1")
      val step = b.node[String]("step", writes = Set(log)) { (input, _, context) =>
        positions.add(context.position)
        if first.compareAndSet(true, false) then {
          entered.countDown()
          gate.await(5, TimeUnit.SECONDS): Unit
        }
        continue(Command.empty.update(log, input))
      }
      b.compile(step)(_.get(log)).value
    }
    def awaitEntered(): Unit = entered.await(5, TimeUnit.SECONDS) shouldBe true
  }

  /** A store that delegates to `underlying`, with hooks a test sets. */
  private class Hooked(val underlying: Checkpointer) extends Checkpointer {
    @volatile var onClaim: () => Option[Result[RunClaim]] = () => None
    @volatile var onRenew: () => Option[Result[RunClaim]] = () => None
    @volatile var onRelease: () => Option[Result[Unit]]   = () => None
    @volatile var onDelete: () => Option[Result[Unit]]    = () => None
    val renewals                                          = new AtomicInteger()
    val releases                                          = new AtomicInteger()
    def claim(threadId: ThreadId, request: ClaimRequest)  = onClaim().getOrElse(underlying.claim(threadId, request))
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) = {
      renewals.incrementAndGet()
      onRenew().getOrElse(underlying.renew(threadId, token, ttl))
    }
    def release(threadId: ThreadId, token: FencingToken) = {
      releases.incrementAndGet()
      onRelease().getOrElse(underlying.release(threadId, token))
    }
    def commit(threadId: ThreadId, commit: Commit)                  = underlying.commit(threadId, commit)
    def latest(threadId: ThreadId)                                  = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) = underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long)          = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId) = onDelete().getOrElse(underlying.deleteThread(threadId))
  }

  private val slow = ClaimPolicy(ttl = 2.hours, renewEvery = 1.hour)

  private def run(id: String) = RunConfig().withRunId(RunId(id))

  private def eventually(condition: => Boolean): Unit = {
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(5)
    condition shouldBe true
  }

  "A run" should "hold a claim while it runs, carry its token in its position and checkpoints, and give it back" in {
    val clock  = MovingClock()
    val store  = InMemoryCheckpointer(clock)
    val held   = Held()
    val handle = GraphRuntime(store, claims = slow).start(thread, held.graph, "x", run("first")).value
    held.awaitEntered()

    store.claim(thread, ClaimRequest(RunId("other"), 1.second)).left.value shouldBe
      GraphError.ThreadBusy("t", store.latest(thread).value.map(_.checkpoint.id), Some("first"))
    held.gate.countDown()
    await(handle).completed._2 shouldBe Vector("x")

    val token = held.positions.peek().fencingToken.value
    store.latest(thread).value.value.checkpoint.fencingToken shouldBe Some(token)
    // released at the end: a new claim is granted at once, with a greater token
    store.claim(thread, ClaimRequest(RunId("other"), 1.second)).value.token.value should be > token.value
  }

  it should "not carry a token outside a runtime" in {
    val held = Held()
    held.gate.countDown()
    drive(held.graph, held.graph.start("x")).completed
    held.positions.peek().fencingToken shouldBe None
  }

  "A second runtime" should "be refused a live run's thread, take it over once the claim expires, and fence off the first" in {
    val clock  = MovingClock()
    val store  = InMemoryCheckpointer(clock)
    val held   = Held()
    val first  = GraphRuntime(store, claims = slow)
    val second = GraphRuntime(store, claims = slow)
    val handle = first.start(thread, held.graph, "x", run("first")).value
    held.awaitEntered()
    val latest = store.latest(thread).value.map(_.checkpoint.id)

    second.recover(thread, held.graph, run("second")).left.value shouldBe
      GraphError.ThreadBusy("t", latest, Some("first"))
    second.deleteThread(thread, run("second")).left.value shouldBe GraphError.ThreadBusy("t", latest, Some("first"))

    clock.advance(slow.ttl)
    await(second.recover(thread, held.graph, run("second")).value).completed._2 shouldBe Vector("x")
    val after = store.eventsAfter(thread, 0L, 100).value

    held.gate.countDown()
    val (_, error) = await(handle).failed
    error should matchPattern { case GraphError.CheckpointWriteFailed("t", _: GraphError.StaleClaim, None) => }
    store.eventsAfter(thread, 0L, 100).value shouldBe after
    held.positions.asScala.toVector.map(_.fencingToken.value.value).distinct should have size 2
  }

  it should "not be told who holds a thread that has no checkpoint yet" in {
    val store = InMemoryCheckpointer()
    store.claim(thread, ClaimRequest(RunId("admitting"), 1.minute)).value
    val held = Held()
    GraphRuntime(store).start(thread, held.graph, "x", run("second")).left.value shouldBe
      GraphError.ThreadBusy("t", None, None)
  }

  "Admission" should "give the claim back when its claim commit loses a race" in {
    val underlying = InMemoryCheckpointer()
    val held       = Held()
    held.gate.countDown()
    await(GraphRuntime(underlying).start(thread, held.graph, "x", run("first")).value).completed
    val stale = underlying.latest(thread).value
    // a commit lands between this admission's read and its claim commit
    val racing = new Hooked(underlying) {
      override def latest(threadId: ThreadId) = Right(None)
    }
    GraphRuntime(racing).start(thread, held.graph, "y", run("second")).left.value shouldBe
      GraphError.ThreadBusy("t", stale.map(_.checkpoint.id))
    racing.releases.get shouldBe 1
    underlying.claim(thread, ClaimRequest(RunId("third"), 1.second)).value.holder shouldBe RunId("third")
  }

  it should "report a store that cannot grant a claim as a write failure, and one that throws the same way" in {
    val store = Hooked(InMemoryCheckpointer())
    val held  = Held()
    store.onClaim = () => Some(Left(ProcessingError("store", "down")))
    GraphRuntime(store).start(thread, held.graph, "x").left.value should matchPattern {
      case GraphError.CheckpointWriteFailed("t", _: ProcessingError, None) =>
    }
    store.onClaim = () => throw new IllegalStateException("driver")
    GraphRuntime(store).start(thread, held.graph, "x").left.value shouldBe a[GraphError.CheckpointWriteFailed]
    store.latest(thread).value shouldBe None
  }

  it should "fall back to ThreadBusy when the thread cannot be re-read after a refused claim" in {
    val underlying = InMemoryCheckpointer()
    underlying.claim(thread, ClaimRequest(RunId("other"), 1.minute)).value
    val reads = new AtomicInteger()
    val store = new Hooked(underlying) {
      override def latest(threadId: ThreadId) =
        if reads.incrementAndGet() == 1 then Right(None) else Left(ValidationError("store", "down"))
    }
    GraphRuntime(store).start(thread, Held().graph, "x").left.value shouldBe GraphError.ThreadBusy("t", None)
  }

  "Renewal" should "keep a run's claim alive past its ttl, and stop once the claim is lost" in {
    val clock  = MovingClock()
    val store  = Hooked(InMemoryCheckpointer(clock))
    val held   = Held()
    val handle = GraphRuntime(store, claims = ClaimPolicy(30.seconds, 10.millis)).start(thread, held.graph, "x").value
    held.awaitEntered()
    clock.advance(20.seconds)
    val before = store.renewals.get
    eventually(store.renewals.get > before + 1)
    clock.advance(20.seconds)
    store.underlying.claim(thread, ClaimRequest(RunId("thief"), 1.second)).left.value shouldBe a[GraphError.ThreadBusy]

    // a store that fails is tried again; a lost claim ends renewal
    store.onRenew = () => Some(Left(ProcessingError("store", "flaky")))
    val failing = store.renewals.get
    eventually(store.renewals.get > failing + 1)
    val lost = new AtomicInteger()
    store.onRenew = () => {
      lost.incrementAndGet()
      Some(Left(GraphError.StaleClaim("t", 1L, Some(2L))))
    }
    eventually(lost.get == 1)
    Thread.sleep(100)
    lost.get shouldBe 1

    // and the run stops at its next superstep, as it would have at its next commit
    held.gate.countDown()
    await(handle).failed._2 should matchPattern {
      case GraphError.CheckpointWriteFailed("t", GraphError.StaleClaim("t", 1L, Some(2L)), None) =>
    }
  }

  it should "survive a renew that throws" in {
    val store = Hooked(InMemoryCheckpointer())
    val held  = Held()
    store.onRenew = () => throw new IllegalStateException("driver")
    val handle = GraphRuntime(store, claims = ClaimPolicy(30.seconds, 10.millis)).start(thread, held.graph, "x").value
    held.awaitEntered()
    eventually(store.renewals.get > 1)
    held.gate.countDown()
    await(handle).completed._2 shouldBe Vector("x")
  }

  "A run that lost its claim" should "stop at its next superstep, running nothing more, even with deferred commits" in {
    val clock   = MovingClock()
    val store   = Hooked(InMemoryCheckpointer(clock))
    val gate    = new CountDownLatch(1)
    val entered = new CountDownLatch(1)
    val later   = new AtomicInteger()
    val log     = StateKey.appending[String]("log")
    val b       = GraphBuilder("lost", "v1")
    val second = b.node[Unit]("second", writes = Set(log)) { (_, _, _) =>
      later.incrementAndGet()
      continue(Command.empty.update(log, "second"))
    }
    val first = b.node[String]("first", writes = Set(log)) { (input, _, _) =>
      entered.countDown()
      gate.await(5, TimeUnit.SECONDS)
      continue(Command.empty.update(log, input).goto(second))
    }
    val graph = b.compile(first)(_.get(log)).value
    // the store cannot be reached for renewal until another run has taken the thread over
    store.onRenew = () => Some(Left(ProcessingError("store", "unreachable")))
    val handle = GraphRuntime(store, claims = ClaimPolicy(30.seconds, 10.millis))
      .start(thread, graph, "x", run("first"), Durability.OnExit)
      .value
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    val claimCheckpoint = store.latest(thread).value.value.checkpoint
    clock.advance(30.seconds)
    store.underlying.claim(thread, ClaimRequest(RunId("thief"), 1.hour)).value
    val renewed = new CountDownLatch(1)
    store.onRenew = () => {
      renewed.countDown()
      None
    }
    renewed.await(5, TimeUnit.SECONDS) shouldBe true
    Thread.sleep(100) // the renewal's StaleClaim is recorded just after the store answers

    gate.countDown()
    await(handle).failed._2 should matchPattern {
      case GraphError.CheckpointWriteFailed("t", _: GraphError.StaleClaim, None) =>
    }
    // superstep 2 never ran: under OnExit, nothing would have stopped it before the exit commit
    later.get shouldBe 0
    store.latest(thread).value.value.checkpoint shouldBe claimCheckpoint
  }

  "Recovery" should "carry over pending writes an expired, unreplaced holder made before the claim" in {
    val clock   = MovingClock()
    val store   = InMemoryCheckpointer(clock)
    val calls   = new java.util.concurrent.ConcurrentHashMap[String, AtomicInteger]()
    val entered = new CountDownLatch(1)
    val gate    = new CountDownLatch(1)
    val results = StateKey.appending[String]("results")
    val graph = {
      val b = GraphBuilder("workers", "v1")
      val worker = b.node[String]("worker", writes = Set(results)) { (item, _, _) =>
        val n = calls.computeIfAbsent(item, _ => new AtomicInteger()).incrementAndGet()
        if item == "b" && n == 1 then {
          entered.countDown()
          gate.await(5, TimeUnit.SECONDS): Unit
        }
        continue(Command.empty.update(results, item))
      }
      val collect = b.node[Unit]("collect")((_, _, _) => continue(Command.empty))
      val join    = b.dynamicJoin("workers", collect)
      val plan    = b.node[Vector[String]]("plan")((items, _, _) => continue(Command.empty.fanOut(join, worker, items)))
      b.compile(plan)(_.get(results)).value
    }
    def writes = store.latest(thread).value.value.pendingWrites.size
    // the first run's superstep checkpoints wait, so it stays at the checkpoint the recovery reads
    val hold = new CountDownLatch(1)
    val holding = new Hooked(store) {
      override def commit(threadId: ThreadId, commit: Commit) = {
        if commit.checkpoint.exists(_.snapshot.superstep > 1) then hold.await(5, TimeUnit.SECONDS): Unit
        underlying.commit(threadId, commit)
      }
    }
    val handle = GraphRuntime(holding, claims = slow).start(thread, graph, Vector("a", "b"), run("first")).value
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    eventually(writes == 1) // `a` is durable; `b` is running
    clock.advance(slow.ttl)

    // between the recovery's read and its claim, the expired holder - not yet replaced - writes `b`
    val raced      = new AtomicBoolean(false)
    val recovering = Hooked(store)
    recovering.onClaim = () => {
      if raced.compareAndSet(false, true) then {
        gate.countDown()
        eventually(writes == 2)
      }
      None
    }
    await(
      GraphRuntime(recovering, claims = slow).recover(thread, graph, run("second")).value
    ).completed._2.sorted shouldBe
      Vector("a", "b")
    calls.get("a").get shouldBe 1
    calls.get("b").get shouldBe 1

    hold.countDown()
    await(handle).failed._2 should matchPattern {
      case GraphError.CheckpointWriteFailed("t", _: GraphError.StaleClaim, None) =>
    }
  }

  "resume" should "be refused while another runtime is admitting on the thread, naming its run" in {
    val store   = InMemoryCheckpointer()
    val log     = StateKey.appending[String]("log")
    val b       = GraphBuilder("ask", "v1")
    val approve = b.declareResume[String, String]("approve")
    b.implement(approve.node, writes = Set(log))((resumed, _, _) => continue(Command.empty.update(log, resumed.answer)))
    val ask       = b.node[String]("ask")((_, _, _) => NodeResult.Suspend(StateUpdate.empty, "ok?", approve))
    val graph     = b.compile(ask)(_.get(log)).value
    val parked    = await(GraphRuntime(store).start(thread, graph, "x").value).suspended
    val answers   = Map(parked.interrupts.head.id -> approve.answer("yes"))
    val suspended = store.latest(thread).value.value

    // the first runtime holds the store claim and has not committed yet
    val claimed = new CountDownLatch(1)
    val proceed = new CountDownLatch(1)
    val admitting = new Hooked(store) {
      override def claim(threadId: ThreadId, request: ClaimRequest) = {
        val granted = underlying.claim(threadId, request)
        claimed.countDown()
        proceed.await(5, TimeUnit.SECONDS): Unit
        granted
      }
    }
    val first = new java.util.concurrent.LinkedBlockingQueue[Result[RunHandle[Vector[String]]]]()
    Thread
      .ofVirtual()
      .start(() => first.put(GraphRuntime(admitting).resume(thread, graph, answers, run("first")))): Unit
    claimed.await(5, TimeUnit.SECONDS) shouldBe true

    GraphRuntime(store).resume(thread, graph, answers, run("second")).left.value shouldBe
      GraphError.ThreadBusy("t", Some(suspended.checkpoint.id), Some("first"))
    store.latest(thread).value.value shouldBe suspended

    proceed.countDown()
    await(first.poll(5, TimeUnit.SECONDS).value).completed._2 shouldBe Vector("yes")
  }

  "Release" should "leave the thread busy until the claim lapses when the store cannot release it" in {
    val clock = MovingClock()
    val store = Hooked(InMemoryCheckpointer(clock))
    val held  = Held()
    held.gate.countDown()
    store.onRelease = () => Some(Left(ProcessingError("store", "down")))
    val runtime = GraphRuntime(store, claims = slow)
    await(runtime.start(thread, held.graph, "x", run("first")).value).completed
    runtime.start(thread, held.graph, "y", run("second")).left.value should matchPattern {
      case GraphError.ThreadBusy("t", Some(_), Some("first")) =>
    }
    store.onRelease = () => throw new IllegalStateException("driver")
    clock.advance(slow.ttl)
    await(runtime.start(thread, held.graph, "y", run("second")).value).completed._2 shouldBe Vector("x", "y")
    store.releases.get shouldBe 2
  }

  "deleteThread" should "claim the thread, and give the claim back if the store does not delete it" in {
    val store = Hooked(InMemoryCheckpointer())
    val held  = Held()
    held.gate.countDown()
    val runtime = GraphRuntime(store)
    await(runtime.start(thread, held.graph, "x").value).completed
    store.onDelete = () => Some(Left(ProcessingError("store", "down")))
    runtime.deleteThread(thread).left.value shouldBe a[ProcessingError]
    store.releases.get shouldBe 2
    store.onDelete = () => None
    runtime.deleteThread(thread).value shouldBe (())
    store.latest(thread).value shouldBe None
    store.releases.get shouldBe 2 // the deletion took the claim with the thread
  }

  it should "not delete a thread another tenant created between its read and its claim" in {
    val underlying = InMemoryCheckpointer()
    val held       = Held()
    held.gate.countDown()
    val other  = GraphRuntime(underlying)
    val racing = Hooked(underlying)
    val raced  = new AtomicBoolean(false)
    // after this runtime read the thread (and found nothing), another runtime runs a tenant-b run on it
    racing.onClaim = () => {
      if raced.compareAndSet(false, true) then
        await(other.start(thread, held.graph, "b", run("b").withTenantId(TenantId("tenant-b"))).value).completed: Unit
      None
    }
    GraphRuntime(racing).deleteThread(thread, run("a").withTenantId(TenantId("tenant-a"))).left.value shouldBe
      GraphError.TenantMismatch("t", Some("tenant-a"))
    raced.get shouldBe true
    underlying.latest(thread).value.value.checkpoint.tenantId shouldBe Some("tenant-b")
    underlying.eventsAfter(thread, 0L, 100).value should not be empty
    // the refused deletion gave its claim back
    underlying.claim(thread, ClaimRequest(RunId("next"), 1.second)).value.holder shouldBe RunId("next")
  }

  "ClaimPolicy" should "default to a 30 second claim renewed every 10 seconds, and refuse an invalid one" in {
    ClaimPolicy.default shouldBe ClaimPolicy(30.seconds, 10.seconds)
    ClaimPolicy.default.withTtl(1.minute).withRenewEvery(5.seconds) shouldBe ClaimPolicy(1.minute, 5.seconds)
    an[IllegalArgumentException] should be thrownBy ClaimPolicy(0.seconds, 10.seconds)
    an[IllegalArgumentException] should be thrownBy ClaimPolicy(10.seconds, 10.seconds)
    an[IllegalArgumentException] should be thrownBy ClaimPolicy.default.withRenewEvery(0.seconds)
    ClaimPolicy.of(5.seconds, 10.seconds).left.value.message should include("must be shorter than ttl")
    ClaimPolicy.of().value shouldBe ClaimPolicy.default
  }

  "ClaimRequest" should "refuse a non-positive ttl" in {
    ClaimRequest(RunId("r"), 1.second).withHolder(RunId("s")).withTtl(2.seconds) shouldBe
      ClaimRequest(RunId("s"), 2.seconds)
    an[IllegalArgumentException] should be thrownBy ClaimRequest(RunId("r"), 0.seconds)
    ClaimRequest.of(RunId("r"), -1.second).left.value.message should include("ttl must be positive")
    ClaimRequest.of(RunId("r"), 1.second).value shouldBe ClaimRequest(RunId("r"), 1.second)
  }

  "The fenced types" should "change one field per setter, and expose no constructor or copy" in {
    val claim = RunClaim(thread, RunId("r"), FencingToken(1L), start)
    claim
      .withThreadId(ThreadId("u"))
      .withHolder(RunId("s"))
      .withToken(FencingToken(2L))
      .withExpiresAt(start.plusSeconds(1)) shouldBe
      RunClaim(ThreadId("u"), RunId("s"), FencingToken(2L), start.plusSeconds(1))
    val commit = Commit(FencingToken(1L))
    commit.withToken(FencingToken(2L)).token shouldBe FencingToken(2L)
    commit.withEvents(Vector.empty).withPendingWrites(Vector.empty).withCheckpoint(None) shouldBe commit
    val position = RunPosition(thread, RunId("r"), "c", TaskId("0.0"), NodeId("n"), 0)
    position.fencingToken shouldBe None
    position
      .withThreadId(ThreadId("u"))
      .withRunId(RunId("s"))
      .withCheckpointId("d")
      .withTaskId(TaskId("1.0"))
      .withNodeId(NodeId("m"))
      .withSuperstep(1)
      .withFencingToken(FencingToken(3L)) shouldBe
      RunPosition(ThreadId("u"), RunId("s"), "d", TaskId("1.0"), NodeId("m"), 1, Some(FencingToken(3L)))
    position.withFencingToken(None) shouldBe position
    val checkpoint = Checkpoint(
      Checkpoint.CurrentFormat,
      "c1",
      None,
      "t",
      "r",
      CheckpointStatus.Running,
      start,
      GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false)
    )
    val changed = checkpoint
      .withFormatVersion(4)
      .withId("c2")
      .withParent(Some("c1"))
      .withThreadId("u")
      .withRunId("s")
      .withStatus(CheckpointStatus.Completed)
      .withCreatedAt(start.plusSeconds(1))
      .withSnapshot(checkpoint.snapshot.copy(superstep = 1))
      .withTenantId("acme")
      .withFencingToken(FencingToken(5L))
    (changed.formatVersion, changed.id, changed.parent, changed.threadId, changed.runId, changed.status) shouldBe
      ((4, "c2", Some("c1"), "u", "s", CheckpointStatus.Completed))
    (changed.createdAt, changed.snapshot.superstep, changed.tenantId, changed.fencingToken) shouldBe
      ((start.plusSeconds(1), 1, Some("acme"), Some(FencingToken(5L))))
    changed.withTenantId(None).withFencingToken(None).tenantId shouldBe None
    scala.compiletime.testing
      .typeCheckErrors("checkpoint.copy(id = \"x\")")
      .map(_.message)
      .mkString(" ") should include("cannot be accessed")
    FencingToken(3L).value shouldBe 3L
    Seq(FencingToken(2L), FencingToken(1L)).sorted shouldBe Seq(FencingToken(1L), FencingToken(2L))
    scala.compiletime.testing
      .typeCheckErrors("claim.copy(holder = RunId(\"x\"))")
      .map(_.message)
      .mkString(" ") should include("cannot be accessed")
    scala.compiletime.testing
      .typeCheckErrors("commit.copy(token = FencingToken(9L))")
      .map(_.message)
      .mkString(" ") should include("cannot be accessed")
    scala.compiletime.testing
      .typeCheckErrors("position.copy(superstep = 9)")
      .map(_.message)
      .mkString(" ") should include("cannot be accessed")
  }

  it should "describe a stale claim and a busy thread's holder" in {
    GraphError.StaleClaim("t", 1L, Some(2L)).message shouldBe "Claim token 1 on thread 't' is stale (current: 2)"
    GraphError.StaleClaim("t", 1L, None).message should include("current: none")
    GraphError.ThreadBusy("t", Some("c"), Some("r")).message shouldBe "Thread 't' is held by run 'r' (now at c); retry"
    GraphError.ThreadBusy("t", None).message shouldBe "Thread 't' is held by another run (now at <none>); retry"
  }
}
