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
   * A store that serialises `renew` and `release` on one lock, as `SqliteCheckpointer` serialises every
   * call on its connection. After [[hangRenewals]], the next renewal holds the lock until [[unhang]], so a
   * release waits behind it. `released` records the token of each release that ran.
   */
  private class Serialised(val underlying: Checkpointer) extends Checkpointer {
    private val storeLock                  = new java.util.concurrent.locks.ReentrantLock()
    private val hang                       = new CountDownLatch(1)
    private val hanging                    = new AtomicBoolean(false)
    val renewing                           = new CountDownLatch(1)
    val released                           = new ConcurrentLinkedQueue[FencingToken]()
    @volatile var beforeCommit: () => Unit = () => ()
    def hangRenewals(): Unit               = hanging.set(true)
    def unhang(): Unit                     = hang.countDown()
    private def serialised[A](body: => A): A = {
      storeLock.lock()
      val outcome = scala.util.Try(body)
      storeLock.unlock()
      outcome.get
    }
    def claim(threadId: ThreadId, request: ClaimRequest) = underlying.claim(threadId, request)
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) = serialised {
      if hanging.compareAndSet(true, false) then {
        renewing.countDown()
        hang.await(10, TimeUnit.SECONDS): Unit
      }
      underlying.renew(threadId, token, ttl)
    }
    def release(threadId: ThreadId, token: FencingToken) = serialised {
      released.add(token)
      underlying.release(threadId, token)
    }
    def commit(threadId: ThreadId, commit: Commit) = {
      beforeCommit()
      underlying.commit(threadId, commit)
    }
    def latest(threadId: ThreadId)                                  = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) = underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long)          = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId)                            = underlying.deleteThread(threadId)
    def history(threadId: ThreadId, before: Option[String], limit: Int) = underlying.history(threadId, before, limit)
    def checkpoint(threadId: ThreadId, checkpointId: String)            = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy)              = underlying.prune(threadId, policy)
  }

  /**
   * Waits for `latch`, at most 10 seconds, whatever interrupts the waiting thread - as a store call that
   * ignores interrupts does - and sets the flag again afterwards if one arrived.
   */
  private def stuckUntil(latch: CountDownLatch): Unit = {
    val deadline    = System.nanoTime() + 10.seconds.toNanos
    var interrupted = false
    while latch.getCount > 0 && System.nanoTime() < deadline do
      CancelledError
        .catchInterrupt(latch.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS))
        .left
        .foreach(_ => interrupted = true)
    if interrupted then Thread.currentThread().interrupt()
  }

  /** Runs `body` on a virtual thread; its outcome, or `None` if it has not returned within 5 seconds. */
  private def within[A](body: => A): Option[A] = {
    val done = new java.util.concurrent.LinkedBlockingQueue[A]()
    Thread.ofVirtual().start(() => done.offer(body): Unit)
    Option(done.poll(5, TimeUnit.SECONDS))
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

  it should "keep its claim when a renewal publishes while the beat is deciding the lease has passed" in {
    val time  = ManualTime()
    val store = Hooked(InMemoryCheckpointer(time))
    val two   = TwoStep()
    val rt    = runtime(store, time)
    // the beat's ticker reads: one is held, mid-decision, until the test releases it
    val beatCalls   = new AtomicInteger()
    val blockFrom   = new AtomicInteger(Int.MaxValue)
    val blocked     = new AtomicBoolean(false)
    val beatHeld    = new CountDownLatch(1)
    val beatRelease = new CountDownLatch(1)
    val released    = new AtomicBoolean(false)
    val decided     = new CountDownLatch(1)
    rt.ticker = () => {
      if Thread.currentThread().getName == s"llm4s-claim-${thread.value}" then {
        if released.get then decided.countDown()
        else {
          // the beat alternates two reads per beat; the odd one starts its expiry check
          val n = beatCalls.incrementAndGet()
          if n >= blockFrom.get && n % 2 == 1 && blocked.compareAndSet(false, true) then {
            beatHeld.countDown()
            beatRelease.await(10, TimeUnit.SECONDS): Unit
          }
        }
      }
      time.nanos()
    }
    val handle = rt.start(thread, two.graph, "x", run("first")).value
    two.awaitEntered()
    // no renewal lands after the grant's: the lease ends at 30s
    store.onRenew = unreachable
    time.advance(20.seconds)
    val failing = store.renewals.get
    eventually(store.renewals.get > failing + 1)

    // the next renewal, sent at 20s, succeeds in the store but is held before the run hears of it
    val holding  = new AtomicBoolean(true)
    val renewing = new CountDownLatch(1)
    val proceed  = new CountDownLatch(1)
    store.onRenew = () => {
      if holding.compareAndSet(true, false) then {
        renewing.countDown()
        proceed.await(10, TimeUnit.SECONDS): Unit
      }
      None
    }
    renewing.await(5, TimeUnit.SECONDS) shouldBe true
    blockFrom.set(beatCalls.get + 1)
    beatHeld.await(5, TimeUnit.SECONDS) shouldBe true

    // the beat is deciding while the clock passes the grant's lease; the renewal (lease to 50s) settles
    time.advance(11.seconds)
    val settled = new CountDownLatch(1)
    rt.renewalSettled = _ => settled.countDown()
    proceed.countDown()
    settled.await(5, TimeUnit.SECONDS) shouldBe true
    released.set(true)
    beatRelease.countDown()

    // the beat either goes on beating, or has stopped the run
    Thread.ofVirtual().start { () =>
      two.interrupted.await(10, TimeUnit.SECONDS); decided.countDown()
    }: Unit
    decided.await(5, TimeUnit.SECONDS) shouldBe true
    two.interrupted.getCount shouldBe 1
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

  // ---- a lost run's end does not wait on a store that hangs ----

  /** Whether the Async writer of thread `t` is still alive: a platform thread, so it can be listed. */
  private def writerAlive: Boolean =
    Thread.getAllStackTraces.keySet.asScala.exists(t => t.getName == s"llm4s-graph-writer-${thread.value}" && t.isAlive)

  "A lost Async run whose commit hangs in the store" should
    "still end and free its thread locally, and let the late write land harmlessly" in {
      val time        = ManualTime()
      val store       = Hooked(InMemoryCheckpointer(time))
      val writing     = new CountDownLatch(1)
      val hang        = new CountDownLatch(1)
      val releasing   = new CountDownLatch(1)
      val releaseGate = new CountDownLatch(1)
      val releasers   = new ConcurrentLinkedQueue[Thread]()
      val entered     = new CountDownLatch(1)
      val log         = StateKey.appending[String]("log")
      val b           = GraphBuilder("queued", "v1")
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
      // `quick`'s pending write hangs in the store: the partition the heartbeat exists to survive
      val armed = new AtomicBoolean(true)
      store.onCommit = commit => {
        if commit.checkpoint.isEmpty && commit.pendingWrites.nonEmpty && armed.compareAndSet(true, false) then {
          writing.countDown()
          hang.await(10, TimeUnit.SECONDS): Unit
        }
        None
      }
      // and the release is held too, so the late write lands while the claim is still the run's
      store.onRelease = () => {
        releasers.add(Thread.currentThread())
        releasing.countDown()
        releaseGate.await(10, TimeUnit.SECONDS): Unit
        None
      }
      val rt     = runtime(store, time, ClaimPolicy(300.millis, 10.millis))
      val handle = rt.start(thread, graph, "x", run("first"), Durability.Async).value
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      writing.await(5, TimeUnit.SECONDS) shouldBe true
      store.onRenew = unreachable
      val token = tokenOf(store)

      time.advance(1.second)
      // its result does not wait for the hung write, nor for its release
      await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
      rt.heldLocally(thread) shouldBe false
      releasing.await(5, TimeUnit.SECONDS) shouldBe true

      // the hung write lands late: fenced by the token, it is accepted only because nobody took over
      hang.countDown()
      eventually(!writerAlive)
      events(store).map(_.event) should contain(RunEvent.TaskCompleted)
      // and it leaves the hub no hand-over mark for a thread no run holds
      rt.handOverMarked(thread) shouldBe false
      // the detached release lands once the store lets it, freeing the claim
      releaseGate.countDown()
      eventually(
        store.underlying.latest(thread).isRight && store.releases.get == 1 && !releasers.asScala.exists(_.isAlive)
      )
      store.underlying.claim(thread, ClaimRequest(RunId("next"), 1.minute)).value.holder shouldBe RunId("next")
    }

  /**
   * A run whose Async write of `quick`'s result hangs in the store until `hang` opens, and whose release
   * waits for `releaseGate`, so the late write lands while the claim is still the run's. Its `held` node
   * waits for `heldGate`, or for the interrupt a lost claim sends.
   */
  final private class LateWrite {
    val time        = ManualTime()
    val store       = Hooked(InMemoryCheckpointer(time))
    val writing     = new CountDownLatch(1)
    val hang        = new CountDownLatch(1)
    val entered     = new CountDownLatch(1)
    val releaseGate = new CountDownLatch(1)
    val heldGate    = new CountDownLatch(1)
    val releasers   = new ConcurrentLinkedQueue[Thread]()
    private val log = StateKey.appending[String]("log")
    val graph: CompiledGraph[String, Vector[String]] = {
      val b = GraphBuilder("late", "v1")
      val held = b.node[Unit]("held", writes = Set(log)) { (_, _, _) =>
        entered.countDown()
        CancelledError.catchInterrupt(heldGate.await(60, TimeUnit.SECONDS)).left.foreach { _ =>
          Thread.currentThread().interrupt()
        }
        continue(Command.empty.update(log, "held"))
      }
      val quick = b.node[String]("quick", writes = Set(log)) { (input, _, _) =>
        continue(Command.empty.update(log, input).goto(held))
      }
      b.compile(quick)(_.get(log)).value
    }
    private val armed = new AtomicBoolean(true)
    store.onCommit = commit => {
      if commit.checkpoint.isEmpty && commit.pendingWrites.nonEmpty && armed.compareAndSet(true, false) then {
        writing.countDown()
        hang.await(10, TimeUnit.SECONDS): Unit
      }
      None
    }
    store.onRelease = () => {
      releasers.add(Thread.currentThread())
      releaseGate.await(10, TimeUnit.SECONDS): Unit
      None
    }
    val rt: GraphRuntime = runtime(store, time, ClaimPolicy(300.millis, 10.millis))

    def start(): RunHandle[Vector[String]] = rt.start(thread, graph, "x", run("first"), Durability.Async).value

    /** Waits until `quick`'s write hangs and `held` runs, then lets the lease pass; the run's token. */
    def loseClaim(): FencingToken = {
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      writing.await(5, TimeUnit.SECONDS) shouldBe true
      store.onRenew = unreachable
      val token = tokenOf(store)
      time.advance(1.second)
      token
    }

    /** Opens the release, and waits until it has landed. */
    def letRelease(): Unit = {
      releaseGate.countDown()
      eventually(store.releases.get >= 1 && !releasers.asScala.exists(_.isAlive))
    }
  }

  /** A run-scoped listener that never ends its subscription: it records every event, and each barrier. */
  final private class RunRecorder extends RunListener {
    val received                                = new ConcurrentLinkedQueue[StreamEvent]()
    val ended                                   = new CountDownLatch(1)
    def apply(event: StreamEvent): Unit         = received.add(event): Unit
    def runEnded(runId: RunId): Unit            = ended.countDown()
    def records: Vector[EventRecord]            = received.asScala.toVector.collect { case StreamEvent.Durable(r) => r }
    def eventsOf(run: String): Vector[RunEvent] = records.filter(_.runId == run).map(_.event)
  }

  "A lost Async run's write that lands after the run ended" should
    "reach the thread's subscribers in order, but never the run's own listeners" in {
      val late         = LateWrite()
      val threadEvents = new ConcurrentLinkedQueue[EventRecord]()
      val onThread = late.rt
        .subscribe(thread) {
          case StreamEvent.Durable(r) => threadEvents.add(r): Unit
          case _                      => ()
        }
        .value
      val handle = late.start()
      val live   = RunRecorder()
      handle.subscribe()(live).value
      val token = late.loseClaim()
      await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
      live.ended.await(5, TimeUnit.SECONDS) shouldBe true

      // the write the run abandoned lands now, after the run's end-of-run barrier
      late.hang.countDown()
      eventually(!writerAlive)
      events(late.store).filter(_.runId == "first").map(_.event) should contain(RunEvent.TaskCompleted)
      // a run-scoped subscription made now replays the log, the late write included
      val replaying = RunRecorder()
      handle.subscribe()(replaying).value
      replaying.ended.await(5, TimeUnit.SECONDS) shouldBe true

      // a later run on the thread moves every subscription past the late write
      late.letRelease()
      late.store.onRenew = () => None
      late.heldGate.countDown()
      await(late.rt.recover(thread, late.graph, run("second")).value).completed
      eventually(Seq(live, replaying).forall(_.eventsOf("second").contains(RunEvent.RunCompleted)))
      eventually(threadEvents.asScala.exists(r => r.runId == "second" && r.event == RunEvent.RunCompleted))
      onThread.cancel()

      // the late write is an orphan of a run that has ended: not one of its delivered events
      for recorder <- Seq(live, replaying) do recorder.eventsOf("first") should not contain RunEvent.TaskCompleted
      // the thread's subscribers get it, in sequence, before anything of the run that followed
      val seqs = threadEvents.asScala.toVector.map(_.seq)
      seqs shouldBe (1L to seqs.last).toVector
      val ordered = threadEvents.asScala.toVector
      ordered.indexWhere(r => r.runId == "first" && r.event == RunEvent.TaskCompleted) should be <
        ordered.indexWhere(_.runId == "second")
    }

  "A lost Async run's late write that lands while the run frees its thread" should
    "leave the hub no hand-over mark, whichever of the two finishes last" in {
      val late   = LateWrite()
      val hooked = new CountDownLatch(1)
      // between the run forgetting the mark and freeing its slot: the late write lands and recreates the
      // mark, then either finishes its own cleanup (a race) or waits for the release to complete
      late.rt.releasingLocally = _ => {
        // the run thread of a lost run still has its interrupt flag set; waiting here needs it clear
        val interrupted = Thread.interrupted()
        late.hang.countDown()
        eventually(late.rt.handOverMarked(thread))
        eventually(!writerAlive || late.rt.activeLockContended)
        hooked.countDown()
        if interrupted then Thread.currentThread().interrupt()
      }
      val handle = late.start()
      val token  = late.loseClaim()
      await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
      hooked.getCount shouldBe 0
      eventually(!writerAlive)
      late.rt.heldLocally(thread) shouldBe false
      late.rt.handOverMarked(thread) shouldBe false
      late.letRelease()
    }

  "A healthy Async run" should "still drain every commit before it releases its claim" in {
    val time   = ManualTime()
    val store  = Hooked(InMemoryCheckpointer(time))
    val two    = TwoStep()
    val atEnd  = new AtomicReference[Option[CheckpointStatus]](None)
    val stalls = new CountDownLatch(1) // never opened: the closing commit waits out its stall
    store.onCommit = commit => {
      if commit.checkpoint.exists(_.status == CheckpointStatus.Completed) then
        stalls.await(200, TimeUnit.MILLISECONDS): Unit
      None
    }
    store.onRelease = () => {
      atEnd.set(store.underlying.latest(thread).toOption.flatten.map(_.checkpoint.status))
      None
    }
    two.gate.countDown()
    await(runtime(store, time).start(thread, two.graph, "x", run("first"), Durability.Async).value).completed
    atEnd.get shouldBe Some(CheckpointStatus.Completed)
  }

  "A lost run whose renewal hangs in a store that serialises its calls" should
    "end and free its thread locally without waiting for its release, which then spares a new claim" in {
      val time       = ManualTime()
      val underlying = InMemoryCheckpointer(time)
      val store      = Serialised(underlying)
      val two        = TwoStep()
      val rt         = runtime(store, time, ClaimPolicy(300.millis, 10.millis))
      val handle     = rt.start(thread, two.graph, "x", run("first")).value
      two.awaitEntered()
      val token = tokenOf(store)
      store.hangRenewals()
      store.renewing.await(5, TimeUnit.SECONDS) shouldBe true

      // the lease passes while the renewal hangs; the release would queue behind it
      time.advance(1.second)
      await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
      rt.heldLocally(thread) shouldBe false
      store.released.asScala.toVector shouldBe empty

      // another runtime, on a connection of its own, takes the expired claim over
      val next      = TwoStep()
      val successor = runtime(underlying, time, slow).recover(thread, next.graph, run("second")).value
      next.awaitEntered()
      // the hung renewal ends; the old release then runs with the old token, and spares the new claim
      store.unhang()
      eventually(store.released.contains(token))
      underlying.claim(thread, ClaimRequest(RunId("other"), 1.minute)).left.value should matchPattern {
        case GraphError.ThreadBusy("t", _, Some("second")) =>
      }
      next.gate.countDown()
      await(successor).completed
    }

  // ---- a loss found while the run is finishing still bounds its end ----

  "A run that loses its claim while its finishing commit hangs in the store" should
    "still end ClaimLost and free its thread, and leave a later run's hold alone, in every durability mode" in {
      for durability <- Durability.values do
        withClue(s"$durability: ") {
          val time       = ManualTime()
          val store      = Hooked(InMemoryCheckpointer(time))
          val two        = TwoStep()
          val committing = new CountDownLatch(1)
          val hang       = new CountDownLatch(1)
          val finishing  = new CountDownLatch(1)
          val finish     = new CountDownLatch(1)
          // each run's closing checkpoint enters the store and stays there, interrupted or not: the
          // first run's until `hang` opens, the later run's until `finish` does
          store.onCommit = commit => {
            commit.checkpoint.filter(_.status == CheckpointStatus.Completed).foreach { closing =>
              if closing.runId == "first" then {
                committing.countDown()
                stuckUntil(hang)
              } else {
                finishing.countDown()
                stuckUntil(finish)
              }
            }
            None
          }
          val rt     = runtime(store, time, ClaimPolicy(300.millis, 10.millis))
          val handle = rt.start(thread, two.graph, "x", run("first"), durability).value
          val token  = tokenOf(store)
          two.gate.countDown()
          committing.await(5, TimeUnit.SECONDS) shouldBe true

          // the lease passes once the run is finishing: its end must not wait on the store
          store.onRenew = unreachable
          time.advance(1.second)
          await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
          rt.heldLocally(thread) shouldBe false
          eventually(store.releases.get == 1)

          // a later run takes the thread over while the old commit is still in the store
          store.onRenew = () => None
          val next = TwoStep()
          next.gate.countDown()
          val successor = new java.util.concurrent.LinkedBlockingQueue[Result[RunHandle[Vector[String]]]]()
          Thread.ofVirtual().start(() => successor.offer(rt.recover(thread, next.graph, run("second"))): Unit)
          eventually(rt.heldLocally(thread))
          // the old commit returns, refused: the claim it was fenced by is gone
          hang.countDown()
          val second = Option(successor.poll(5, TimeUnit.SECONDS)).value.value
          finishing.await(5, TimeUnit.SECONDS) shouldBe true
          handle.asInstanceOf[DefaultRunHandle[?]].runThreadExited.await(5, TimeUnit.SECONDS) shouldBe true
          // the old run, ending at last, set no second result and did not free the later run's hold
          await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
          rt.heldLocally(thread) shouldBe true
          events(store).filter(_.runId == "first").map(_.event) should not contain RunEvent.RunCompleted
          finish.countDown()
          await(second).completed
        }
    }

  "A change whose heartbeat cannot be joined, in a store that serialises its calls" should
    "return and free its thread without waiting for its release" in {
      val time       = ManualTime()
      val underlying = InMemoryCheckpointer(time)
      val store      = Serialised(underlying)
      val two        = TwoStep()
      two.gate.countDown()
      await(runtime(store, time, slow).start(thread, two.graph, "x", run("first")).value).completed
      val latest   = store.latest(thread).value.value.checkpoint.id
      val released = store.released.size
      // the change's commit waits until its claim's renewal hangs, holding the store's lock
      store.hangRenewals()
      store.beforeCommit = () => store.renewing.await(5, TimeUnit.SECONDS): Unit
      val rt = runtime(store, time, ClaimPolicy(300.millis, 10.millis))

      val updated =
        within(rt.updateState(thread, two.graph, latest, StateUpdate.update(two.log, "fixed"), config = run("edit")))
      updated.value.value.runId shouldBe "edit"
      rt.heldLocally(thread) shouldBe false
      store.released.size shouldBe released
      // the renewal ends; the release queued behind it then gives the claim back
      store.unhang()
      eventually(store.released.size == released + 1)
      underlying.claim(thread, ClaimRequest(RunId("next"), 1.minute)).value.holder shouldBe RunId("next")
    }

  "A run whose commit hangs in the store" should "not hold up a run on another thread of the same runtime" in {
    val time       = ManualTime()
    val store      = Hooked(InMemoryCheckpointer(time))
    val committing = new CountDownLatch(1)
    val hang       = new CountDownLatch(1)
    store.onCommit = commit => {
      if commit.checkpoint.exists(c => c.threadId == thread.value && c.status == CheckpointStatus.Completed) then {
        committing.countDown()
        stuckUntil(hang)
      }
      None
    }
    val rt    = runtime(store, time, slow)
    val stuck = TwoStep()
    stuck.gate.countDown()
    val handle = rt.start(thread, stuck.graph, "x", run("first")).value
    committing.await(5, TimeUnit.SECONDS) shouldBe true

    val other = TwoStep()
    other.gate.countDown()
    val elsewhere = within(rt.start(ThreadId("u"), other.graph, "y", run("other")).flatMap(_.await()))
    hang.countDown()
    elsewhere.value.value.completed
    await(handle).completed
  }

  "ThreadLocks" should "serialise one thread id, leave others free, and keep no lock once idle" in {
    val locks   = ThreadLocks()
    val holding = new CountDownLatch(1)
    val leave   = new CountDownLatch(1)
    val holder  = Thread.ofVirtual().start(() => locks.withThreadLock(thread) { holding.countDown(); leave.await() })
    holding.await(5, TimeUnit.SECONDS) shouldBe true
    within(locks.withThreadLock(ThreadId("u"))(locks.withThreadLock(ThreadId("u"))("other"))) shouldBe Some("other")
    val waiting = new AtomicBoolean(false)
    val second  = Thread.ofVirtual().start(() => locks.withThreadLock(thread)(waiting.set(true)))
    second.join(100)
    waiting.get shouldBe false
    leave.countDown()
    holder.join(5000)
    second.join(5000)
    waiting.get shouldBe true
    locks.size shouldBe 0
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
