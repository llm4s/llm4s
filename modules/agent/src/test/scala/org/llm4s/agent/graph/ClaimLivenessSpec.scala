package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.{ CancelledError, ProcessingError }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference }
import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Whether a run keeps, or knows it has lost, its claim on a thread (#1700 follow-up): the run judges
 * its own lease by its monotonic clock, stops at once when it finds the claim lost - committing nothing
 * more - releases with a clean interrupt flag, joins its heartbeat first, renews from the grant, and
 * records a takeover on `RunRecovered`.
 *
 * Time is manual: [[ManualTime]] is both the store's clock and the runtime's lease clock
 * ([[GraphRuntime.ticker]]), and moves only when a test moves it. Renewal ticks are real time.
 */
class ClaimLivenessSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val thread = ThreadId("t")
  private val t0     = Instant.parse("2026-10-13T09:00:00Z")

  /**
   * The store's clock and the runtime's monotonic one, moved together by [[advance]]; [[skewStore]]
   * moves only the store's, as a store clock running ahead does. `broken` makes the store's throw.
   */
  final private class ManualTime extends Clock {
    private val offset                         = new AtomicLong(0L)
    private val storeAhead                     = new AtomicLong(0L)
    @volatile var broken                       = false
    def getZone: ZoneId                        = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant =
      if broken then throw new IllegalStateException("clock unavailable")
      else t0.plusNanos(offset.get + storeAhead.get)
    def nanos(): Long                       = 1_000_000_000_000L + offset.get
    def advance(by: FiniteDuration): Unit   = offset.addAndGet(by.toNanos): Unit
    def skewStore(by: FiniteDuration): Unit = storeAhead.addAndGet(by.toNanos): Unit
  }

  /** A store that delegates to `underlying`, with hooks a test sets. */
  private class Hooked(val underlying: Checkpointer) extends Checkpointer {
    @volatile var onRenew: () => Option[Result[RunClaim]]                  = () => None
    @volatile var onRelease: () => Option[Result[Unit]]                    = () => None
    @volatile var onLatest: () => Option[Result[Option[StoredCheckpoint]]] = () => None
    @volatile var onCommit: Commit => Option[Result[Vector[EventRecord]]]  = _ => None
    val renewals                                                           = new AtomicInteger()
    val renewed                                                            = new AtomicInteger()
    val releases                                                           = new AtomicInteger()
    def claim(threadId: ThreadId, request: ClaimRequest)                   = underlying.claim(threadId, request)
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) = {
      renewals.incrementAndGet()
      val outcome = onRenew().getOrElse(underlying.renew(threadId, token, ttl))
      if outcome.isRight then renewed.incrementAndGet()
      outcome
    }
    def release(threadId: ThreadId, token: FencingToken) = {
      releases.incrementAndGet()
      onRelease().getOrElse(underlying.release(threadId, token))
    }
    def commit(threadId: ThreadId, commit: Commit) = onCommit(commit).getOrElse(underlying.commit(threadId, commit))
    def latest(threadId: ThreadId)                 = onLatest().getOrElse(underlying.latest(threadId))
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) = underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long)          = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId)                            = underlying.deleteThread(threadId)
    def history(threadId: ThreadId, before: Option[String], limit: Int) = underlying.history(threadId, before, limit)
    def checkpoint(threadId: ThreadId, checkpointId: String)            = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy)              = underlying.prune(threadId, policy)
  }

  /**
   * `first` opens `entered` and waits for `gate`, opening `interrupted` if it is interrupted instead;
   * then `second` counts its executions in `later`, standing in for a tool's side effect.
   */
  final private class TwoStep {
    val gate        = new CountDownLatch(1)
    val entered     = new CountDownLatch(1)
    val interrupted = new CountDownLatch(1)
    val later       = new AtomicInteger()
    val log         = StateKey.appending[String]("log")
    val graph: CompiledGraph[String, Vector[String]] = {
      val b = GraphBuilder("two", "v1")
      val second = b.node[Unit]("second", writes = Set(log)) { (_, _, _) =>
        later.incrementAndGet()
        continue(Command.empty.update(log, "second"))
      }
      val first = b.node[String]("first", writes = Set(log)) { (input, _, _) =>
        entered.countDown()
        if CancelledError.catchInterrupt(gate.await(60, TimeUnit.SECONDS)).isLeft then {
          interrupted.countDown()
          Thread.currentThread().interrupt()
        }
        continue(Command.empty.update(log, input).goto(second))
      }
      b.compile(first)(_.get(log)).value
    }
    def awaitEntered(): Unit = entered.await(5, TimeUnit.SECONDS) shouldBe true
  }

  /** A claim renewed every 10ms: renewal ticks are real time, so a renewal is always about to happen. */
  private val live = ClaimPolicy(ttl = 30.seconds, renewEvery = 10.millis)

  /** A claim that is never renewed during a test. */
  private val slow = ClaimPolicy(ttl = 2.hours, renewEvery = 1.hour)

  private def runtime(store: Checkpointer, time: ManualTime, claims: ClaimPolicy = live): GraphRuntime = {
    val runtime = GraphRuntime(store, claims = claims)
    runtime.ticker = () => time.nanos()
    runtime
  }

  private def run(id: String) = RunConfig().withRunId(RunId(id))

  private def tokenOf(store: Checkpointer): FencingToken =
    store.latest(thread).value.value.checkpoint.fencingToken.value

  private def events(store: Checkpointer): Vector[EventRecord] = store.eventsAfter(thread, 0L, 10_000).value

  private val unreachable: () => Option[Result[RunClaim]] = () => Some(Left(ProcessingError("store", "unreachable")))

  private def eventually(condition: => Boolean): Unit = {
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(5)
    condition shouldBe true
  }

  // ---- 1 and 2: a run judges its own lease, and a loss stops it at once ----

  "A run whose renewals fail" should
    "stop once its lease passes, interrupting its task and committing nothing more, in every durability mode" in {
      for durability <- Durability.values do
        withClue(s"$durability: ") {
          val time   = ManualTime()
          val store  = Hooked(InMemoryCheckpointer(time))
          val two    = TwoStep()
          val handle = runtime(store, time).start(thread, two.graph, "x", run("first"), durability).value
          two.awaitEntered()
          store.onRenew = unreachable
          val failing = store.renewals.get
          eventually(store.renewals.get > failing + 1)
          val token  = tokenOf(store)
          val before = events(store)

          time.advance(31.seconds)
          await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
          two.interrupted.getCount shouldBe 0
          two.later.get shouldBe 0
          // not even RunFailed or RunCancelled, and under OnExit not its buffered task write
          events(store) shouldBe before

          // it released the claim as it ended, so the thread is free at once
          store.onRenew = () => None
          two.gate.countDown()
          await(runtime(store, time, slow).recover(thread, two.graph, run("second")).value).completed._2 shouldBe
            Vector("x", "second")
        }
    }

  it should "not run a side effect after another runtime took the thread over (a partition)" in {
    val time   = ManualTime()
    val store  = Hooked(InMemoryCheckpointer(time))
    val stale  = TwoStep()
    val handle = runtime(store, time).start(thread, stale.graph, "x", run("first"), Durability.OnExit).value
    stale.awaitEntered()
    store.onRenew = unreachable
    val token = tokenOf(store)

    time.advance(31.seconds)
    stale.interrupted.await(5, TimeUnit.SECONDS) shouldBe true
    val successor = TwoStep()
    successor.gate.countDown()
    await(runtime(store.underlying, time, slow).recover(thread, successor.graph, run("second")).value).completed
    successor.later.get shouldBe 1

    await(handle).failed._2 should matchPattern { case GraphError.ClaimLost("t", `token`, _) => }
    stale.later.get shouldBe 0
    store.latest(thread).value.value.checkpoint.runId shouldBe "second"
  }

  it should "stop once its lease passes while a renewal hangs in the store, in every durability mode" in {
    for durability <- Durability.values do
      withClue(s"$durability: ") {
        val time     = ManualTime()
        val store    = Hooked(InMemoryCheckpointer(time))
        val two      = TwoStep()
        val renewing = new CountDownLatch(1)
        val hang     = new CountDownLatch(1)
        val handle   = runtime(store, time).start(thread, two.graph, "x", run("first"), durability).value
        two.awaitEntered()
        val token = tokenOf(store)
        store.onRenew = () => {
          renewing.countDown()
          hang.await(60, TimeUnit.SECONDS): Unit
          Some(Left(ProcessingError("store", "timed out")))
        }
        renewing.await(5, TimeUnit.SECONDS) shouldBe true

        time.advance(31.seconds)
        two.interrupted.await(5, TimeUnit.SECONDS) shouldBe true
        hang.countDown()
        await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
        two.later.get shouldBe 0
      }
  }

  it should "keep its claim when a renewal lands before the lease passes, retrying the ones the store fails" in {
    val time   = ManualTime()
    val store  = Hooked(InMemoryCheckpointer(time))
    val two    = TwoStep()
    val handle = runtime(store, time).start(thread, two.graph, "x", run("first")).value
    two.awaitEntered()
    store.onRenew = unreachable
    time.advance(20.seconds)
    val failing = store.renewals.get
    eventually(store.renewals.get > failing + 1)

    store.onRenew = () => None
    val landed = store.renewed.get
    eventually(store.renewed.get > landed)
    // past the lease the grant gave, inside the one the renewal gave
    time.advance(25.seconds)
    Thread.sleep(100) // many beats: one that judged by the grant's lease would stop the run now
    two.gate.countDown()
    await(handle).completed._2 shouldBe Vector("x", "second")
  }

  it should "keep renewing through a store clock that throws" in {
    val time   = ManualTime()
    val store  = Hooked(InMemoryCheckpointer(time))
    val two    = TwoStep()
    val handle = runtime(store, time).start(thread, two.graph, "x", run("first")).value
    two.awaitEntered()
    time.broken = true
    time.advance(20.seconds)
    val failing = store.renewals.get
    eventually(store.renewals.get > failing + 1)
    store.renewed.get shouldBe 0

    time.broken = false
    eventually(store.renewed.get > 0)
    time.advance(25.seconds)
    Thread.sleep(100)
    two.gate.countDown()
    await(handle).completed._2 shouldBe Vector("x", "second")
  }

  "A run whose renewal is refused" should "be interrupted in its task at once, ending ClaimLost" in {
    val time        = ManualTime()
    val store       = Hooked(InMemoryCheckpointer(time))
    val entered     = new CountDownLatch(1)
    val interrupted = new CountDownLatch(1)
    val toolCalls   = new AtomicInteger()
    val log         = StateKey.appending[String]("log")
    val b           = GraphBuilder("long", "v1")
    // an agent-like node: a tool call every 10ms for up to 10s, checking its interrupt flag
    val agent = b.node[String]("agent", writes = Set(log)) { (input, _, _) =>
      entered.countDown()
      val until = System.nanoTime() + 10.seconds.toNanos
      while System.nanoTime() < until && !Thread.currentThread().isInterrupted do {
        toolCalls.incrementAndGet()
        if CancelledError.catchInterrupt(Thread.sleep(10)).isLeft then Thread.currentThread().interrupt()
      }
      if Thread.currentThread().isInterrupted then interrupted.countDown()
      continue(Command.empty.update(log, input))
    }
    val graph  = b.compile(agent)(_.get(log)).value
    val handle = runtime(store, time).start(thread, graph, "x", run("first")).value
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    val token = tokenOf(store)

    store.onRenew = () => Some(Left(GraphError.StaleClaim("t", token.value, Some(99L))))
    interrupted.await(5, TimeUnit.SECONDS) shouldBe true
    await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, Some(FencingToken(99L)))
  }

  "A run whose commit is refused as stale" should "end ClaimLost, naming the current claim, in every durability mode" in {
    for durability <- Durability.values do
      withClue(s"$durability: ") {
        val time   = ManualTime()
        val store  = InMemoryCheckpointer(time)
        val two    = TwoStep()
        val handle = runtime(store, time, slow).start(thread, two.graph, "x", run("first"), durability).value
        two.awaitEntered()
        val token = tokenOf(store)
        // the store's clock runs ahead, so it expires the claim the run still judges live; another takes it
        time.skewStore(3.hours)
        val thief = store.claim(thread, ClaimRequest(RunId("thief"), 1.hour)).value

        two.gate.countDown()
        await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, Some(thief.token))
      }
  }

  it should "interrupt the superstep in flight at once, in Sync and Async" in {
    for durability <- Seq(Durability.Sync, Durability.Async) do
      withClue(s"$durability: ") {
        val time        = ManualTime()
        val store       = InMemoryCheckpointer(time)
        val gate        = new CountDownLatch(1)
        val entered     = new CountDownLatch(1)
        val interrupted = new CountDownLatch(1)
        val log         = StateKey.appending[String]("log")
        val b           = GraphBuilder("siblings", "v1")
        // `quick` finishes, and its commit is refused; its sibling `long` is still running a tool loop
        val quick = b.node[Unit]("quick", writes = Set(log)) { (_, _, _) =>
          gate.await(10, TimeUnit.SECONDS): Unit
          continue(Command.empty.update(log, "quick"))
        }
        val long = b.node[Unit]("long", writes = Set(log)) { (_, _, _) =>
          entered.countDown()
          val until = System.nanoTime() + 10.seconds.toNanos
          while System.nanoTime() < until && !Thread.currentThread().isInterrupted do
            if CancelledError.catchInterrupt(Thread.sleep(10)).isLeft then Thread.currentThread().interrupt()
          if Thread.currentThread().isInterrupted then interrupted.countDown()
          continue(Command.empty.update(log, "long"))
        }
        val fork  = b.node[String]("fork")((_, _, _) => continue(Command.empty.goto(quick).goto(long)))
        val graph = b.compile(fork)(_.get(log)).value
        // never renewed during the test: only the refused commit can tell the run
        val handle = runtime(store, time, slow).start(thread, graph, "x", run("first"), durability).value
        entered.await(5, TimeUnit.SECONDS) shouldBe true
        val token = tokenOf(store)
        time.skewStore(3.hours)
        val thief = store.claim(thread, ClaimRequest(RunId("thief"), 1.hour)).value

        gate.countDown()
        interrupted.await(5, TimeUnit.SECONDS) shouldBe true
        await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, Some(thief.token))
      }
  }

  "A lost Async run" should "drop the commits it had queued, waiting only for the one in flight" in {
    val time    = ManualTime()
    val store   = Hooked(InMemoryCheckpointer(time))
    val writing = new CountDownLatch(1)
    val proceed = new CountDownLatch(1)
    val entered = new CountDownLatch(1)
    val log     = StateKey.appending[String]("log")
    val b       = GraphBuilder("queued", "v1")
    val held = b.node[Unit]("held", writes = Set(log)) { (_, _, _) =>
      entered.countDown()
      CancelledError.catchInterrupt(new CountDownLatch(1).await(60, TimeUnit.SECONDS)).left.foreach { _ =>
        Thread.currentThread().interrupt()
      }
      continue(Command.empty.update(log, "held"))
    }
    val quick = b.node[String]("quick", writes = Set(log)) { (input, _, _) =>
      continue(Command.empty.update(log, input).goto(held))
    }
    val graph = b.compile(quick)(_.get(log)).value
    // the writer stalls on `quick`'s pending write, so the superstep's checkpoint queues behind it
    val armed = new AtomicBoolean(true)
    store.onCommit = commit =>
      if commit.checkpoint.isEmpty && commit.pendingWrites.nonEmpty && armed.compareAndSet(true, false) then {
        writing.countDown()
        proceed.await(10, TimeUnit.SECONDS): Unit
      }
      None
    val rt = runtime(store, time)
    rt.abandoning = _ => proceed.countDown()
    val handle = rt.start(thread, graph, "x", run("first"), Durability.Async).value
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    writing.await(5, TimeUnit.SECONDS) shouldBe true
    store.onRenew = unreachable
    val token           = tokenOf(store)
    val claimCheckpoint = store.latest(thread).value.value.checkpoint.id

    time.advance(31.seconds)
    await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
    // the write in flight landed; the queued superstep checkpoint did not
    store.latest(thread).value.value.checkpoint.id shouldBe claimCheckpoint
    events(store).map(_.event).collect { case c: RunEvent.CheckpointCommitted => c } shouldBe empty
  }

  // ---- 3: release with a clean interrupt flag; no late interrupt reaches a closing run ----

  "A cancelled run" should "release its claim with the interrupt flag clear" in {
    val time    = ManualTime()
    val store   = Hooked(InMemoryCheckpointer(time))
    val two     = TwoStep()
    val flagged = new AtomicReference[Option[Boolean]](None)
    store.onRelease = () => { flagged.set(Some(Thread.currentThread().isInterrupted)); None }
    val handle = runtime(store, time, slow).start(thread, two.graph, "x", run("first")).value
    two.awaitEntered()
    handle.cancel()
    await(handle).failed._2 shouldBe a[GraphError.Cancelled]
    flagged.get shouldBe Some(false)
  }

  it should "leave the thread free when the store's release honours interrupts" in {
    val time  = ManualTime()
    val store = Hooked(InMemoryCheckpointer(time))
    val two   = TwoStep()
    // a store whose I/O checks the flag (java.net.http, lockInterruptibly, Future.get)
    store.onRelease =
      () => if Thread.currentThread().isInterrupted then Some(Left(CancelledError("release", None))) else None
    val handle = runtime(store, time, slow).start(thread, two.graph, "x", run("first")).value
    two.awaitEntered()
    handle.cancel()
    await(handle)
    two.gate.countDown()
    await(runtime(store, time, slow).recover(thread, two.graph, run("second")).value).completed
  }

  /** Runs `handle`'s release through `store` with a cancel arriving inside it; whether it interrupted. */
  private def cancelDuringRelease(store: Hooked): (AtomicReference[RunHandle[?]], AtomicReference[Option[Boolean]]) = {
    val handleRef = new AtomicReference[RunHandle[?]](null)
    val flagged   = new AtomicReference[Option[Boolean]](None)
    store.onRelease = () => {
      // a cancel that arrives while the run is releasing, after it settled its outcome
      Option(handleRef.get).foreach(_.cancel())
      flagged.set(Some(Thread.currentThread().isInterrupted))
      None
    }
    (handleRef, flagged)
  }

  "A run stopping on a store failure" should "not take a late cancel's interrupt into its release" in {
    val time                 = ManualTime()
    val store                = Hooked(InMemoryCheckpointer(time))
    val two                  = TwoStep()
    val (handleRef, flagged) = cancelDuringRelease(store)
    store.onCommit =
      c => Option.when(c.checkpoint.isEmpty && c.pendingWrites.nonEmpty)(Left(ProcessingError("db", "down")))
    val handle = runtime(store, time, slow).start(thread, two.graph, "x", run("first")).value
    handleRef.set(handle)
    two.gate.countDown()
    await(handle).failed._2 shouldBe a[GraphError.CheckpointWriteFailed]
    flagged.get shouldBe Some(false)
  }

  "A run that crashed" should "not take a late cancel's interrupt into its release" in {
    val store = Hooked(InMemoryCheckpointer())
    // admission reads the runtime's clock twice; the run thread's first read throws
    val reads = new AtomicInteger()
    val clock = new Clock {
      def getZone: ZoneId                        = ZoneOffset.UTC
      override def withZone(zone: ZoneId): Clock = this
      override def instant(): Instant =
        if reads.incrementAndGet() > 2 then throw new IllegalStateException("clock exploded") else t0
    }
    val (handleRef, flagged) = cancelDuringRelease(store)
    val two                  = TwoStep()
    two.gate.countDown()
    val handle = GraphRuntime(store, clock, slow).start(thread, two.graph, "x", run("first")).value
    handleRef.set(handle)
    await(handle).failed._2 shouldBe a[GraphError.RunCrashed]
    flagged.get shouldBe Some(false)
  }

  "A run that lost its claim" should "not take a late cancel's interrupt into its release" in {
    val time                 = ManualTime()
    val store                = Hooked(InMemoryCheckpointer(time))
    val two                  = TwoStep()
    val (handleRef, flagged) = cancelDuringRelease(store)
    val handle               = runtime(store, time).start(thread, two.graph, "x", run("first")).value
    handleRef.set(handle)
    two.awaitEntered()
    store.onRenew = unreachable
    time.advance(31.seconds)
    await(handle).failed._2 shouldBe a[GraphError.ClaimLost]
    flagged.get shouldBe Some(false)
  }

  "An admission that fails interrupted" should "give its claim back with the flag clear, and keep the flag" in {
    val time  = ManualTime()
    val store = Hooked(InMemoryCheckpointer(time))
    val two   = TwoStep()
    // a cancelled run leaves the thread Running, for recover
    val first = runtime(store, time, slow).start(thread, two.graph, "x", run("first")).value
    two.awaitEntered()
    first.cancel()
    await(first)

    val flagged = new AtomicReference[Option[Boolean]](None)
    store.onRelease = () => { flagged.set(Some(Thread.currentThread().isInterrupted)); None }
    // recover reads the thread, claims it, and reads it again under the claim: that read is interrupted
    val reads = new AtomicInteger()
    store.onLatest = () =>
      Option.when(reads.incrementAndGet() == 2) {
        Thread.currentThread().interrupt()
        Left(CancelledError("read", None))
      }
    runtime(store, time, slow).recover(thread, two.graph, run("second")).left.value shouldBe a[CancelledError]
    Thread.interrupted() shouldBe true
    flagged.get shouldBe Some(false)
  }

  "An updateState that fails interrupted" should "give its claim back with the flag clear, and keep the flag" in {
    val time  = ManualTime()
    val store = Hooked(InMemoryCheckpointer(time))
    val two   = TwoStep()
    two.gate.countDown()
    await(runtime(store, time, slow).start(thread, two.graph, "x", run("first")).value).completed
    val latest = store.latest(thread).value.value.checkpoint.id

    val flagged = new AtomicReference[Option[Boolean]](None)
    store.onRelease = () => { flagged.set(Some(Thread.currentThread().isInterrupted)); None }
    // checked before the claim, then read again under it: that read is interrupted
    val reads = new AtomicInteger()
    store.onLatest = () =>
      Option.when(reads.incrementAndGet() == 2) {
        Thread.currentThread().interrupt()
        Left(CancelledError("read", None))
      }
    runtime(store, time, slow)
      .updateState(thread, two.graph, latest, StateUpdate.update(two.log, "fixed"))
      .left
      .value shouldBe a[CancelledError]
    Thread.interrupted() shouldBe true
    flagged.get shouldBe Some(false)
  }

  // ---- 4: the heartbeat is stopped and joined before the release ----

  "A renewal in flight when the run ends" should "return before the run releases its claim" in {
    val time     = ManualTime()
    val store    = Hooked(InMemoryCheckpointer(time))
    val two      = TwoStep()
    val order    = new ConcurrentLinkedQueue[String]()
    val armed    = new AtomicBoolean(false)
    val renewing = new CountDownLatch(1)
    val finish   = new CountDownLatch(1)
    store.onRenew = () => {
      if armed.compareAndSet(true, false) then {
        renewing.countDown()
        finish.await(10, TimeUnit.SECONDS): Unit
        order.add("renewed"): Unit
      }
      None
    }
    store.onRelease = () => { order.add("released"); None }
    val handle = runtime(store, time).start(thread, two.graph, "x", run("first")).value
    two.awaitEntered()
    armed.set(true)
    renewing.await(5, TimeUnit.SECONDS) shouldBe true

    two.gate.countDown()
    Thread.sleep(100) // the run ends meanwhile; it must not release while the renewal is in the store
    finish.countDown()
    await(handle).completed
    order.asScala.toVector shouldBe Vector("renewed", "released")
    store.underlying.claim(thread, ClaimRequest(RunId("next"), 1.minute)).value.holder shouldBe RunId("next")
  }

  it should "not hold the run's end for longer than ttl when it hangs" in {
    val time     = ManualTime()
    val store    = Hooked(InMemoryCheckpointer(time))
    val two      = TwoStep()
    val armed    = new AtomicBoolean(false)
    val renewing = new CountDownLatch(1)
    val hang     = new CountDownLatch(1)
    store.onRenew = () => {
      if armed.compareAndSet(true, false) then {
        renewing.countDown()
        hang.await(60, TimeUnit.SECONDS): Unit
      }
      None
    }
    val handle =
      runtime(store, time, ClaimPolicy(300.millis, 10.millis)).start(thread, two.graph, "x", run("first")).value
    two.awaitEntered()
    armed.set(true)
    renewing.await(5, TimeUnit.SECONDS) shouldBe true

    two.gate.countDown()
    await(handle).completed
    store.releases.get shouldBe 1
    hang.countDown()
  }

  // ---- 5: renewal is timed from the grant, and admission's time counts against the lease ----

  "Admission" should "renew the claim from the grant, while it is still admitting" in {
    val time   = ManualTime()
    val store  = Hooked(InMemoryCheckpointer(time))
    val two    = TwoStep()
    val during = new AtomicInteger(-1)
    // the claim commit is slow: renewals must not wait for the run to launch
    store.onCommit = commit => {
      if during.get < 0 && commit.checkpoint.nonEmpty then {
        val deadline = System.nanoTime() + 2.seconds.toNanos
        while store.renewals.get == 0 && System.nanoTime() < deadline do Thread.sleep(5)
        during.set(store.renewals.get)
      }
      None
    }
    two.gate.countDown()
    await(runtime(store, time).start(thread, two.graph, "x", run("first")).value).completed
    during.get should be > 0
  }

  it should "count its own time against the lease the grant gave" in {
    val time  = ManualTime()
    val store = Hooked(InMemoryCheckpointer(time))
    val two   = TwoStep()
    store.onRenew = unreachable
    val claimed = new AtomicBoolean(false)
    // the claim commit takes longer than the lease: the run must not count from its launch
    store.onCommit = commit => {
      if commit.checkpoint.nonEmpty && claimed.compareAndSet(false, true) then time.advance(31.seconds)
      None
    }
    val handle = runtime(store, time).start(thread, two.graph, "x", run("first")).value
    await(handle).failed._2 shouldBe a[GraphError.ClaimLost]
    two.later.get shouldBe 0
  }

  "updateState" should "renew the claim it holds while it commits" in {
    val time  = ManualTime()
    val store = Hooked(InMemoryCheckpointer(time))
    val two   = TwoStep()
    two.gate.countDown()
    await(runtime(store, time, slow).start(thread, two.graph, "x", run("first")).value).completed
    val latest = store.latest(thread).value.value.checkpoint.id
    val before = store.renewals.get
    val during = new AtomicInteger(-1)
    store.onCommit = commit => {
      if commit.events.exists(_.event.isInstanceOf[RunEvent.StateUpdated]) then {
        val deadline = System.nanoTime() + 2.seconds.toNanos
        while store.renewals.get == before && System.nanoTime() < deadline do Thread.sleep(5)
        during.set(store.renewals.get - before)
      }
      None
    }
    runtime(store, time).updateState(thread, two.graph, latest, StateUpdate.update(two.log, "fixed")).value
    during.get should be > 0
  }

  // ---- 6: a takeover is recorded on RunRecovered ----

  "recover" should "record the run whose expired claim it took over" in {
    val time  = ManualTime()
    val store = InMemoryCheckpointer(time)
    val stale = TwoStep()
    val first = runtime(store, time, slow).start(thread, stale.graph, "x", run("first")).value
    stale.awaitEntered()
    // the claim expires in the store; the run itself does not notice before the takeover
    time.skewStore(3.hours)
    val successor = TwoStep()
    successor.gate.countDown()
    await(runtime(store, time, slow).recover(thread, successor.graph, run("second")).value).completed
    events(store).map(_.event).collect { case r: RunEvent.RunRecovered => r.takenOverFrom } shouldBe
      Vector(Some("first"))

    stale.gate.countDown()
    await(first).failed._2 shouldBe a[GraphError.ClaimLost]
  }

  it should "record no takeover when the thread was free" in {
    val time  = ManualTime()
    val store = InMemoryCheckpointer(time)
    val two   = TwoStep()
    val first = runtime(store, time, slow).start(thread, two.graph, "x", run("first")).value
    two.awaitEntered()
    first.cancel()
    await(first)
    two.gate.countDown()
    await(runtime(store, time, slow).recover(thread, two.graph, run("second")).value).completed
    events(store).map(_.event).collect { case r: RunEvent.RunRecovered => r.takenOverFrom } shouldBe Vector(None)
  }

  "RunRecovered" should "read a log written before takenOverFrom, and round-trip it" in {
    val json =
      """{"threadId":"t","seq":1,"runId":"r","checkpointId":null,"taskId":null,"nodeId":null,""" +
        """"timestamp":"2026-10-02T12:00:00Z","event":{"$type":"RunRecovered","fromCheckpoint":"c1","tenantId":null,"principal":null}}"""
    upickle.default.read[EventRecord](json).event shouldBe RunEvent.RunRecovered("c1", None, None, None)
    val taken = RunEvent.RunRecovered("c1", Some("a"), None, Some("r-1"))
    upickle.default.read[RunEvent](upickle.default.write(taken)) shouldBe taken
  }

  "InMemoryCheckpointer" should "name the holder of the expired claim a claim replaced" in {
    val time  = ManualTime()
    val store = InMemoryCheckpointer(time)
    store.claim(thread, ClaimRequest(RunId("first"), 1.second)).value.takenOverFrom shouldBe None
    time.advance(1.second)
    val taken = store.claim(thread, ClaimRequest(RunId("second"), 1.second)).value
    taken.takenOverFrom shouldBe Some(RunId("first"))
    store.release(thread, taken.token).value
    store.claim(thread, ClaimRequest(RunId("third"), 1.second)).value.takenOverFrom shouldBe None
  }
}
