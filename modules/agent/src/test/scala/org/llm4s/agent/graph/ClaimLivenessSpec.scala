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
    @volatile var onRenew: () => Option[Result[RunClaim]] = () => None
    @volatile var onRelease: () => Option[Result[Unit]]   = () => None
    // consulted before `onRelease`, with the token being released
    @volatile var onReleaseOf: FencingToken => Option[Result[Unit]]        = _ => None
    @volatile var onLatest: () => Option[Result[Option[StoredCheckpoint]]] = () => None
    @volatile var onCommit: Commit => Option[Result[Vector[EventRecord]]]  = _ => None
    @volatile var onDelete: () => Unit                                     = () => ()
    // run once the store has answered a read of the log, before the answer is returned
    @volatile var onEventsAfter: ThreadId => Unit = _ => ()
    val renewals                                  = new AtomicInteger()
    val renewed                                   = new AtomicInteger()
    val releases                                  = new AtomicInteger()
    // releases that have returned from the store, so whose effect a later call sees
    val released = new AtomicInteger()
    // claims the store has granted or refused
    val claims = new AtomicInteger()
    def claim(threadId: ThreadId, request: ClaimRequest) = {
      claims.incrementAndGet()
      underlying.claim(threadId, request)
    }
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) = {
      renewals.incrementAndGet()
      val outcome = onRenew().getOrElse(underlying.renew(threadId, token, ttl))
      if outcome.isRight then renewed.incrementAndGet()
      outcome
    }
    def release(threadId: ThreadId, token: FencingToken) = {
      releases.incrementAndGet()
      val outcome = onReleaseOf(token).orElse(onRelease()).getOrElse(underlying.release(threadId, token))
      released.incrementAndGet()
      outcome
    }
    def commit(threadId: ThreadId, commit: Commit) = onCommit(commit).getOrElse(underlying.commit(threadId, commit))
    def effect(threadId: ThreadId, key: org.llm4s.agent.graph.EffectKey) = underlying.effect(threadId, key)
    def latest(threadId: ThreadId) = onLatest().getOrElse(underlying.latest(threadId))
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) = {
      val read = underlying.eventsAfter(threadId, afterSeq, limit)
      onEventsAfter(threadId)
      read
    }
    def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId, token: FencingToken) = {
      onDelete()
      underlying.deleteThread(threadId, token)
    }
    def history(threadId: ThreadId, before: Option[String], limit: Int) = underlying.history(threadId, before, limit)
    def checkpoint(threadId: ThreadId, checkpointId: String)            = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy)              = underlying.prune(threadId, policy)
  }

  /**
   * A store that serialises `renew` and `release` on one lock, as `SqliteCheckpointer` serialises every
   * call on its connection. After [[hangRenewals]], the next renewal holds the lock until [[unhang]], so a
   * release waits behind it. `released` records the token of each release the store has made.
   *
   * After [[serialiseAfterClaim]], once the next claim is granted, `latest`, `commit` and `deleteThread`
   * are serialised too, and `latest` first waits for a renewal to hang, so that the claim holder's next
   * read queues behind it. `changes` counts the commits and deletes made then.
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
    private val serialising         = new AtomicBoolean(false)
    private val claimed             = new AtomicBoolean(false)
    val changes                     = new AtomicInteger()
    def serialiseAfterClaim(): Unit = serialising.set(true)
    def queued: Boolean             = storeLock.hasQueuedThreads
    private def all: Boolean        = serialising.get && claimed.get
    def claim(threadId: ThreadId, request: ClaimRequest) = {
      val granted = underlying.claim(threadId, request)
      if serialising.get then claimed.set(true)
      granted
    }
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) = serialised {
      if hanging.compareAndSet(true, false) then {
        renewing.countDown()
        hang.await(10, TimeUnit.SECONDS): Unit
      }
      underlying.renew(threadId, token, ttl)
    }
    def release(threadId: ThreadId, token: FencingToken) = serialised {
      // recorded once the store has released, so a claim made after a test sees it is not refused
      val outcome = underlying.release(threadId, token)
      released.add(token)
      outcome
    }
    def commit(threadId: ThreadId, commit: Commit) = {
      beforeCommit()
      if all then serialised { changes.incrementAndGet(); underlying.commit(threadId, commit) }
      else underlying.commit(threadId, commit)
    }
    def effect(threadId: ThreadId, key: org.llm4s.agent.graph.EffectKey) = underlying.effect(threadId, key)
    def latest(threadId: ThreadId) =
      if all then {
        renewing.await(5, TimeUnit.SECONDS): Unit
        serialised(underlying.latest(threadId))
      } else underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) = underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long)          = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId, token: FencingToken) =
      if all then serialised { changes.incrementAndGet(); underlying.deleteThread(threadId, token) }
      else underlying.deleteThread(threadId, token)
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

  private def runtime(
    store: Checkpointer,
    time: ManualTime,
    claims: ClaimPolicy = live,
    watch: WatchPolicy = WatchPolicy.default
  ): GraphRuntime = {
    val runtime = GraphRuntime(store, claims = claims, watch = watch)
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
    // a beat starts a renewal only once the last has ended, at the end of a beat that judged the lease
    // still held: the third renewal to reach the store after the advance was started by a beat that
    // judged the lease after it - one that judged by the grant's lease would have stopped the run
    val judged = store.renewals.get
    eventually(store.renewals.get > judged + 2)
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

  it should "lose its claim, and send no renewal, when the lease passes between the beat reading the clock and launching a renewal" in {
    val time  = ManualTime()
    val store = Hooked(InMemoryCheckpointer(time))
    val two   = TwoStep()
    val rt    = runtime(store, time)
    // the beat's ticker reads: the first odd one from `holdFrom` on returns the time it read on entry,
    // after being held until the test releases it - the beat descheduled just after reading the clock
    val beatCalls   = new AtomicInteger()
    val holdFrom    = new AtomicInteger(Int.MaxValue)
    val held        = new AtomicBoolean(false)
    val beatHeld    = new CountDownLatch(1)
    val beatRelease = new CountDownLatch(1)
    rt.ticker = () => {
      val read = time.nanos()
      if Thread.currentThread().getName == s"llm4s-claim-${thread.value}" then {
        // a beat reads the clock twice: to judge the lease and size its wait (odd), then after the wait
        val n = beatCalls.incrementAndGet()
        if n >= holdFrom.get && n % 2 == 1 && held.compareAndSet(false, true) then {
          beatHeld.countDown()
          beatRelease.await(10, TimeUnit.SECONDS): Unit
        }
      }
      read
    }
    // the next renewal is held in the store, so that it is the one renewal in flight, and then fails
    val inStore  = new CountDownLatch(1)
    val failNow  = new CountDownLatch(1)
    val entering = new AtomicBoolean(true)
    store.onRenew = () => {
      if entering.compareAndSet(true, false) then {
        inStore.countDown()
        failNow.await(10, TimeUnit.SECONDS): Unit
      }
      unreachable()
    }
    val handle = rt.start(thread, two.graph, "x", run("first")).value
    two.awaitEntered()
    inStore.await(5, TimeUnit.SECONDS) shouldBe true
    // every renewal so far was sent at 0s, so the lease ends at 30s
    holdFrom.set(beatCalls.get + 1)
    beatHeld.await(5, TimeUnit.SECONDS) shouldBe true

    // the beat has read 0s and is descheduled; the lease passes, and the renewal in flight settles
    time.advance(31.seconds)
    val settled = new CountDownLatch(1)
    rt.renewalSettled = _ => settled.countDown()
    failNow.countDown()
    settled.await(5, TimeUnit.SECONDS) shouldBe true
    // from here on the store would renew the lapsed claim - no takeover has happened
    val sent = store.renewals.get
    store.onRenew = () => None
    val token = tokenOf(store)
    beatRelease.countDown()

    two.interrupted.await(5, TimeUnit.SECONDS) shouldBe true
    await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
    store.renewals.get shouldBe sent
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
    // three renewals after the advance: a beat has judged the renewed lease still held since it
    val judged = store.renewals.get
    eventually(store.renewals.get > judged + 2)
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
   * waits for `heldGate`, or for the interrupt a lost claim sends - unless `ignoresInterrupts`, when it
   * waits for `heldGate` whatever interrupts it, so the run thread never ends while the test runs. With
   * `hangs` false the write does not hang. `landed` opens once the write has landed;
   * `writeInterrupted` records whether its thread was interrupted while in the store.
   */
  final private class LateWrite(ignoresInterrupts: Boolean = false, hangs: Boolean = true) {
    val time             = ManualTime()
    val store            = Hooked(InMemoryCheckpointer(time))
    val writing          = new CountDownLatch(1)
    val hang             = new CountDownLatch(1)
    val entered          = new CountDownLatch(1)
    val releaseGate      = new CountDownLatch(1)
    val heldGate         = new CountDownLatch(1)
    val releasers        = new ConcurrentLinkedQueue[Thread]()
    val landed           = new CountDownLatch(1)
    val writeInterrupted = new AtomicBoolean(false)
    private val log      = StateKey.appending[String]("log")
    val graph: CompiledGraph[String, Vector[String]] = {
      val b = GraphBuilder("late", "v1")
      val held = b.node[Unit]("held", writes = Set(log)) { (_, _, _) =>
        entered.countDown()
        if ignoresInterrupts then stuckUntil(heldGate)
        else
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
    store.onCommit = commit =>
      if commit.checkpoint.isEmpty && commit.pendingWrites.nonEmpty && armed.compareAndSet(true, false) then {
        writing.countDown()
        if hangs then
          CancelledError.catchInterrupt(hang.await(10, TimeUnit.SECONDS)).left.foreach { _ =>
            writeInterrupted.set(true)
            stuckUntil(hang)
          }
        val written = store.underlying.commit(thread, commit)
        landed.countDown()
        Some(written)
      } else None
    store.onRelease = () => {
      releasers.add(Thread.currentThread())
      releaseGate.await(10, TimeUnit.SECONDS): Unit
      None
    }
    val rt: GraphRuntime = runtime(store, time, ClaimPolicy(300.millis, 10.millis))
    // this run's own Async writer, never another test's on a thread of the same name
    val writers = new ConcurrentLinkedQueue[Thread]()
    rt.writerCreated = (_, created) => writers.add(created): Unit
    def writer: Thread = Option(writers.peek()).value

    def start(): RunHandle[Vector[String]] = rt.start(thread, graph, "x", run("first"), Durability.Async).value

    /**
     * Waits until `quick`'s write hangs - or, with `hangs` false, has landed - and `held` runs, then lets
     * the lease pass; the run's token.
     */
    def loseClaim(): FencingToken = {
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      writing.await(5, TimeUnit.SECONDS) shouldBe true
      if !hangs then landed.await(5, TimeUnit.SECONDS) shouldBe true
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

  "A run-scoped subscription made after a lost run ended, while the run's abandoned write still hangs in the store" should
    "replay the run and end at once, never waiting on the hung write" in {
      val late   = LateWrite()
      val handle = late.start()
      val token  = late.loseClaim()
      await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
      writerAlive shouldBe true // the abandoned write is still in the store, holding the thread's commit lock

      val replaying = RunRecorder()
      val sub       = handle.subscribe()(replaying).value
      // the hung write is let land only after this, so a subscription that waited on it could not end
      replaying.ended.await(5, TimeUnit.SECONDS) shouldBe true
      replaying.eventsOf("first") should contain(RunEvent.RunStarted(None, None))
      replaying.eventsOf("first") should not contain RunEvent.TaskCompleted
      writerAlive shouldBe true
      sub.cancel()

      late.hang.countDown()
      eventually(!writerAlive)
      // once the orphan has landed, the hub keeps no mark for the idle thread
      late.rt.handOverMarked(thread) shouldBe false
      late.letRelease()
    }

  "A lost Async run settled without its run thread, whose writer is idle" should
    "shut the writer down without waiting for the run thread, which may still end later" in {
      val late   = LateWrite(ignoresInterrupts = true, hangs = false)
      val handle = late.start()
      val own    = handle.asInstanceOf[DefaultRunHandle[Vector[String]]]
      val token  = late.loseClaim()
      // the writer started, and its one write has landed: it is idle, waiting for the next
      val writer = late.writer
      // the run thread ignores the interrupt, so only the watchdog can settle the run
      await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
      own.runThreadExited.getCount shouldBe 1
      writer.join(5000)
      withClue("the idle writer outlives a run settled without its run thread: ") {
        writer.isAlive shouldBe false
      }

      // the run thread ends at last: its abandon finds the writer shut down, and nothing more of it lands
      val before = events(late.store).size
      late.heldGate.countDown()
      own.runThreadExited.await(5, TimeUnit.SECONDS) shouldBe true
      await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
      events(late.store).size shouldBe before
      late.rt.heldLocally(thread) shouldBe false
      late.letRelease()
    }

  "A lost Async run settled without its run thread, while its write is in the store" should
    "let the write land uninterrupted as an orphan the run's listeners never get, and then end the writer" in {
      val late   = LateWrite(ignoresInterrupts = true)
      val handle = late.start()
      val own    = handle.asInstanceOf[DefaultRunHandle[Vector[String]]]
      val runs   = RunRecorder()
      handle.subscribe()(runs).value
      val token  = late.loseClaim()
      val writer = late.writer
      await(handle).failed._2 shouldBe GraphError.ClaimLost("t", token, None)
      runs.ended.await(5, TimeUnit.SECONDS) shouldBe true
      own.runThreadExited.getCount shouldBe 1
      // the write is still in the store: an orphan, so the hub keeps its mark until it returns
      late.rt.handOverMarked(thread) shouldBe true
      writer.isAlive shouldBe true

      late.hang.countDown()
      late.landed.await(5, TimeUnit.SECONDS) shouldBe true
      writer.join(5000)
      writer.isAlive shouldBe false
      late.writeInterrupted.get shouldBe false
      events(late.store).filter(_.runId == "first").map(_.event) should contain(RunEvent.TaskCompleted)
      runs.eventsOf("first") should not contain RunEvent.TaskCompleted
      // once the orphan has returned, the hub keeps no mark for the idle thread
      late.rt.handOverMarked(thread) shouldBe false

      late.heldGate.countDown()
      own.runThreadExited.await(5, TimeUnit.SECONDS) shouldBe true
      late.letRelease()
    }

  "A run-scoped subscription made after a lost Sync run ended, while one of two concurrent task commits still hangs" should
    "replay the run and end at once, and the hung commit's hand-over be cleaned up once it returns" in {
      val time    = ManualTime()
      val store   = Hooked(InMemoryCheckpointer(time))
      val results = StateKey.appending[String]("results")
      val graph: CompiledGraph[Vector[String], Vector[String]] = {
        val b = GraphBuilder("pair", "v1")
        val worker = b.node[String]("worker", writes = Set(results)) { (item, _, _) =>
          continue(Command.empty.update(results, item))
        }
        val summarize = b.node[Unit]("summarize")((_, _, _) => continue(Command.empty))
        val join      = b.dynamicJoin("workers", summarize)
        val plan = b.node[Vector[String]]("plan")((items, _, _) => continue(Command.empty.fanOut(join, worker, items)))
        b.compile(plan)(_.get(results)).value
      }
      def isWorker(commit: Commit): Boolean = commit.pendingWrites.exists(_.nodeId == "worker")
      // both workers' commits begin - concurrently, from their own tasks - before the first returns
      val begun  = new CountDownLatch(2)
      val stuck  = new CountDownLatch(1)
      val hang   = new CountDownLatch(1)
      val inside = new AtomicInteger()
      val rt     = runtime(store, time, ClaimPolicy(300.millis, 10.millis))
      rt.committing = (_, commit) => if isWorker(commit) then begun.countDown()
      // the first worker commit to reach the store returns once both have begun; the second stays there
      store.onCommit = commit => {
        if isWorker(commit) then
          if inside.incrementAndGet() == 1 then begun.await(5, TimeUnit.SECONDS): Unit
          else {
            stuck.countDown()
            stuckUntil(hang)
          }
        None
      }
      val handle = rt.start(thread, graph, Vector("a", "b"), run("first")).value
      stuck.await(5, TimeUnit.SECONDS) shouldBe true

      // the lease passes while the second commit hangs; the run's end is bounded by the watchdog
      store.onRenew = unreachable
      time.advance(1.second)
      await(handle).failed._2 shouldBe a[GraphError.ClaimLost]
      // the hung commit is an orphan: the hub keeps its hand-over mark while it is in flight
      rt.handOverMarked(thread) shouldBe true

      val replaying = RunRecorder()
      val sub       = handle.subscribe()(replaying).value
      // the hung commit is let return only after this, so a subscription that waited on it could not end
      replaying.ended.await(5, TimeUnit.SECONDS) shouldBe true
      replaying.eventsOf("first") should contain(RunEvent.RunStarted(None, None))
      sub.cancel()

      hang.countDown()
      // once the orphan has returned, the hub keeps no mark for the idle thread
      eventually(!rt.handOverMarked(thread))
      eventually(!rt.heldLocally(thread))
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

  "A run settled without its run thread" should
    "end with the outcome its own listeners saw, even when its run thread finishes meanwhile" in {
      val time       = ManualTime()
      val store      = Hooked(InMemoryCheckpointer(time))
      val two        = TwoStep()
      val committing = new CountDownLatch(1)
      val hang       = new CountDownLatch(1)
      // the run's closing commit enters the store and stays there, interrupted or not, until `hang` opens
      store.onCommit = commit => {
        if commit.checkpoint.exists(c => c.status == CheckpointStatus.Completed && c.runId == "first") then {
          committing.countDown()
          stuckUntil(hang)
        }
        None
      }
      val rt     = runtime(store, time, ClaimPolicy(300.millis, 10.millis))
      val handle = rt.start(thread, two.graph, "x", run("first")).value
      val own    = handle.asInstanceOf[DefaultRunHandle[Vector[String]]]
      val runs   = RunRecorder()
      handle.subscribe()(runs).value
      // the watchdog has its outcome - the run's cut is taken - when the closing commit lands, above the
      // cut; the run thread then settles the run, if it still can, before the watchdog goes on
      own.settlingWithoutRunThread = () => {
        hang.countDown()
        own.runThreadExited.await(5, TimeUnit.SECONDS): Unit
      }
      two.gate.countDown()
      committing.await(5, TimeUnit.SECONDS) shouldBe true

      // the lease passes while the run is finishing; its end is bounded by the watchdog
      store.onRenew = unreachable
      time.advance(1.second)
      val result = await(handle)
      runs.ended.await(5, TimeUnit.SECONDS) shouldBe true
      own.runThreadExited.await(5, TimeUnit.SECONDS) shouldBe true
      val sawCompleted = runs.eventsOf("first").contains(RunEvent.RunCompleted)
      withClue(s"result $result, the run's listener saw RunCompleted: $sawCompleted: ") {
        result.isInstanceOf[RunResult.Completed[?]] shouldBe sawCompleted
      }
      result.failed._2 shouldBe a[GraphError.ClaimLost]
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

  // ---- round 7: the fence is atomic with a commit's start, admission is bounded, settling never joins ----

  "A commit that has begun when its run's claim is found lost" should
    "be the one the fence counts as in flight, so that no commit starts after the fence, in every durability mode" in {
      for durability <- Durability.values do
        withClue(s"$durability: ") {
          val time    = ManualTime()
          val store   = Hooked(InMemoryCheckpointer(time))
          val two     = TwoStep()
          val rt      = runtime(store, time, ClaimPolicy(300.millis, 10.millis))
          val verdict = new java.util.concurrent.LinkedBlockingQueue[Boolean]()
          val seen    = new AtomicReference[Option[Boolean]](None)
          val reached = new AtomicBoolean(false)
          rt.fenced = (_, inFlight) => verdict.offer(inFlight): Unit
          store.onCommit = commit => {
            if commit.checkpoint.exists(_.status == CheckpointStatus.Completed) then reached.set(true)
            None
          }
          // the closing commit has been let past the fence, and has not reached the store: the claim is
          // found lost now, and the fence must count the commit as begun, since it goes on to the store
          rt.committing = (_, commit) =>
            if commit.checkpoint.exists(_.status == CheckpointStatus.Completed) then {
              store.onRenew = unreachable
              time.advance(1.second)
              seen.set(Option(verdict.poll(5, TimeUnit.SECONDS)))
            }
          two.gate.countDown()
          val handle = rt.start(thread, two.graph, "x", run("first"), durability).value
          awaitResult(handle).isRight shouldBe true
          reached.get shouldBe true
          seen.get shouldBe Some(true)
        }
    }

  "An admission whose claim commit hangs in the store" should
    "return ClaimLost once its claim is found lost, free its thread, and run nothing, for start and recover" in {
      for recovering <- Seq(false, true) do
        withClue(s"${if recovering then "recover" else "start"}: ") {
          val time  = ManualTime()
          val store = Hooked(InMemoryCheckpointer(time))
          if recovering then {
            // a cancelled run leaves the thread Running, for recover
            val prior = TwoStep()
            val first = runtime(store, time, slow).start(thread, prior.graph, "x", run("prior")).value
            prior.awaitEntered()
            first.cancel()
            await(first)
          }
          val two       = TwoStep()
          val entering  = new CountDownLatch(1)
          val hang      = new CountDownLatch(1)
          val returned  = new CountDownLatch(1)
          val late      = new AtomicReference[Option[Result[Vector[EventRecord]]]](None)
          val releasing = store.released.get
          // the claim's commit enters the store and stays there, interrupted or not, until `hang` opens
          store.onCommit = commit =>
            if commit.checkpoint.exists(_.runId == "first") && entering.getCount > 0 then {
              entering.countDown()
              stuckUntil(hang)
              val landing = store.underlying.commit(thread, commit)
              late.set(Some(landing))
              returned.countDown()
              Some(landing)
            } else None
          val rt        = runtime(store, time, ClaimPolicy(300.millis, 10.millis))
          val admitting = new java.util.concurrent.LinkedBlockingQueue[Result[RunHandle[Vector[String]]]]()
          Thread
            .ofVirtual()
            .start(() =>
              admitting.offer(
                if recovering then rt.recover(thread, two.graph, run("first"))
                else rt.start(thread, two.graph, "x", run("first"))
              ): Unit
            )
          entering.await(5, TimeUnit.SECONDS) shouldBe true

          // the lease passes while the claim's commit hangs: admission must not wait on the store
          store.onRenew = unreachable
          time.advance(1.second)
          val admitted = Option(admitting.poll(5, TimeUnit.SECONDS))
          admitted.value.left.value shouldBe a[GraphError.ClaimLost]
          rt.heldLocally(thread) shouldBe false

          // the claim is given back while its commit still hangs: the release is detached, so it lands
          // with the commit in the store. The commit began before the loss, so the fence lets it go on;
          // only the token stops it. Had it returned before the release landed, it would have landed,
          // as a commit in flight at a loss may - so `hang` opens only once the release has landed.
          eventually(store.released.get == releasing + 1)
          hang.countDown()

          // the late commit is refused, the claim it was fenced by is gone, and it leaves no mark
          returned.await(5, TimeUnit.SECONDS) shouldBe true
          late.get.value.left.value shouldBe a[GraphError.StaleClaim]
          eventually(!rt.handOverMarked(thread))
          two.entered.getCount shouldBe 1
          events(store).filter(_.runId == "first") shouldBe empty

          // the thread is free for the next run
          store.onRenew = () => None
          two.gate.countDown()
          val next =
            if recovering then rt.recover(thread, two.graph, run("next"))
            else rt.start(thread, two.graph, "x", run("next"))
          await(next.value).completed
        }
    }

  /** How a thread is admitted in [[admitting]]: `start` on a new thread, `recover` or `resume`. */
  private def admissions = Seq("start", "recover", "resume")

  /**
   * Prepares `store`'s thread for `how`, with runs that end before this returns, and gives the call that
   * admits run `first` on it: `start` on a new thread, `recover` of a cancelled run's `Running`
   * checkpoint, or `resume` of a suspended one.
   */
  private def admitting(how: String, store: Checkpointer, time: ManualTime): GraphRuntime => Result[RunHandle[?]] =
    how match {
      case "start" =>
        val two = TwoStep()
        rt => rt.start(thread, two.graph, "x", run("first"))
      case "recover" =>
        val prior = TwoStep()
        val first = runtime(store, time, slow).start(thread, prior.graph, "x", run("prior")).value
        prior.awaitEntered()
        first.cancel()
        await(first)
        rt => rt.recover(thread, prior.graph, run("first"))
      case _ =>
        val log     = StateKey.appending[String]("log")
        val b       = GraphBuilder("ask", "v1")
        val approve = b.declareResume[String, String]("approve")
        b.implement(approve.node, writes = Set(log))((resumed, _, _) =>
          continue(Command.empty.update(log, resumed.answer))
        )
        val ask    = b.node[String]("ask")((_, _, _) => NodeResult.Suspend(StateUpdate.empty, "ok?", approve))
        val graph  = b.compile(ask)(_.get(log)).value
        val parked = await(runtime(store, time, slow).start(thread, graph, "x", run("prior")).value).suspended
        val answer = Map(parked.interrupts.head.id -> approve.answer("yes"))
        rt => rt.resume(thread, graph, answer, run("first"))
    }

  "An admission whose claim commit is refused as stale" should
    "return ClaimLost naming the current claim, release its claim and free its thread, for start, recover and resume" in {
      for how <- admissions do
        withClue(s"$how: ") {
          val time  = ManualTime()
          val store = Hooked(InMemoryCheckpointer(time))
          val admit = admitting(how, store, time)
          val own   = AtomicReference[Option[FencingToken]](None)
          val thief = AtomicReference[Option[RunClaim]](None)
          // as the claim's commit reaches the store, the store's clock has run past the claim and another took it
          store.onCommit = commit => {
            if commit.checkpoint.exists(_.runId == "first") && own.get.isEmpty then {
              own.set(Some(commit.token))
              time.skewStore(3.hours)
              thief.set(Some(store.underlying.claim(thread, ClaimRequest(RunId("thief"), 1.hour)).value))
            }
            None
          }
          val rt        = runtime(store, time, slow)
          val releasing = store.releases.get

          admit(rt).left.value shouldBe GraphError.ClaimLost("t", own.get.value, Some(thief.get.value.token))
          rt.heldLocally(thread) shouldBe false
          eventually(store.releases.get == releasing + 1)
          // the release of a stale token leaves the successor's claim alone, and nothing of the run landed
          store.renew(thread, thief.get.value.token, 1.hour).isRight shouldBe true
          events(store).filter(_.runId == "first") shouldBe empty
        }
    }

  it should "return the one ClaimLost when its heartbeat declares the same loss meanwhile, for start, recover and resume" in {
    for how <- admissions do
      withClue(s"$how: ") {
        val time     = ManualTime()
        val store    = Hooked(InMemoryCheckpointer(time))
        val admit    = admitting(how, store, time)
        val rt       = runtime(store, time)
        val declared = new CountDownLatch(1)
        rt.fenced = (_, _) => declared.countDown()
        val own   = AtomicReference[Option[FencingToken]](None)
        val thief = AtomicReference[Option[RunClaim]](None)
        // while the claim's commit is in the store, the lease passes, the heartbeat declares the loss, and
        // another run takes the expired claim; the commit is then refused as stale
        store.onCommit = commit => {
          if commit.checkpoint.exists(_.runId == "first") && own.get.isEmpty then {
            own.set(Some(commit.token))
            store.onRenew = unreachable
            time.advance(31.seconds)
            declared.await(5, TimeUnit.SECONDS) shouldBe true
            thief.set(Some(store.underlying.claim(thread, ClaimRequest(RunId("thief"), 1.hour)).value))
          }
          None
        }
        val releasing = store.releases.get

        admit(rt).left.value shouldBe GraphError.ClaimLost("t", own.get.value, Some(thief.get.value.token))
        declared.getCount shouldBe 0
        rt.heldLocally(thread) shouldBe false
        eventually(store.releases.get == releasing + 1)
        events(store).filter(_.runId == "first") shouldBe empty
      }
  }

  "A change whose commit is refused as stale" should
    "be ThreadBusy, never a store outage, for updateState and fork, leaving the thread unchanged" in {
      val time  = ManualTime()
      val store = Hooked(InMemoryCheckpointer(time))
      val two   = TwoStep()
      two.gate.countDown()
      await(runtime(store, time, slow).start(thread, two.graph, "x", run("first")).value).completed
      val latest = store.latest(thread).value.value
      val target = ThreadId("u")
      // as the change's commit reaches the store, the store's clock has run past its claim and another took it
      store.onCommit = commit => {
        val forking  = commit.events.exists(_.event.isInstanceOf[RunEvent.ThreadForked])
        val updating = commit.events.exists(_.event.isInstanceOf[RunEvent.StateUpdated])
        if forking || updating then {
          time.skewStore(3.hours)
          store.underlying.claim(if forking then target else thread, ClaimRequest(RunId("thief"), 1.hour)).value: Unit
        }
        None
      }
      val rt = runtime(store, time, slow)

      rt.updateState(thread, two.graph, latest.checkpoint.id, StateUpdate.update(two.log, "fixed"))
        .left
        .value shouldBe GraphError.ThreadBusy("t", Some(latest.checkpoint.id))
      store.latest(thread).value.value shouldBe latest
      rt.heldLocally(thread) shouldBe false

      rt.fork(thread, latest.checkpoint.id, target).left.value shouldBe GraphError.ThreadBusy("u", None)
      store.latest(target).value shouldBe None
      rt.heldLocally(target) shouldBe false
    }

  /** The three changes that hold a thread without running it, on the thread `first` completed. */
  private def changes = Seq("fork", "updateState", "deleteThread")

  /** The thread change `how` holds: the fork's target, otherwise the thread itself. */
  private def heldBy(how: String): ThreadId = if how == "fork" then ThreadId("u") else thread

  /** Change `how` on the thread `two` completed, whose latest checkpoint is `latest`, as run `runId`. */
  private def changing(how: String, rt: GraphRuntime, two: TwoStep, latest: String, runId: String): Result[Any] =
    how match {
      case "fork" => rt.fork(thread, latest, heldBy(how), run(runId))
      case "updateState" =>
        rt.updateState(thread, two.graph, latest, StateUpdate.update(two.log, "fixed"), config = run(runId))
      case _ => rt.deleteThread(thread, run(runId))
    }

  // ---- a stale refusal is a loss found then, wherever the store returns it ----

  /**
   * A renewal the store grants without being asked, so that only a refused commit or delete can find the
   * claim lost: the lease, judged by the manual ticker, never passes, and no renewal is ever refused.
   */
  private def alwaysRenewed: () => Option[Result[RunClaim]] =
    () => Some(Right(RunClaim(thread, RunId("renewed"), FencingToken(1L), t0)))

  /** A claim whose bound, twice its ttl, is well inside the 5 seconds a test waits. */
  private def second = ClaimPolicy(1.second, 10.millis)

  "An OnExit run whose exit commit is refused as stale after a takeover, while its release hangs" should
    "end ClaimLost within the loss bound and free its thread, without waiting on the release" in {
      val time  = ManualTime()
      val store = Hooked(InMemoryCheckpointer(time))
      store.onRenew = alwaysRenewed
      val two   = TwoStep()
      val thief = AtomicReference[Option[RunClaim]](None)
      // as the exit commit reaches the store, the store's clock has run past the claim and another took it
      store.onCommit = commit => {
        if commit.checkpoint.exists(_.status == CheckpointStatus.Completed) && thief.get.isEmpty then {
          time.skewStore(3.hours)
          thief.set(Some(store.underlying.claim(thread, ClaimRequest(RunId("thief"), 1.hour)).value))
        }
        None
      }
      val releasing = new CountDownLatch(1)
      val hang      = new CountDownLatch(1)
      store.onRelease = () => { releasing.countDown(); stuckUntil(hang); None }
      val rt     = runtime(store, time, second)
      val handle = rt.start(thread, two.graph, "x", run("first"), Durability.OnExit).value
      val token  = tokenOf(store)
      two.gate.countDown()

      within(handle.await()).value.value.failed._2 shouldBe
        GraphError.ClaimLost("t", token, Some(thief.get.value.token))
      rt.heldLocally(thread) shouldBe false
      releasing.await(5, TimeUnit.SECONDS) shouldBe true
      hang.countDown()
      events(store).filter(_.runId == "first").map(_.event) should not contain RunEvent.RunCompleted
      store.underlying.renew(thread, thief.get.value.token, 1.hour).isRight shouldBe true
    }

  "A change whose commit is refused as stale after a takeover, while its release hangs" should
    "be ThreadBusy within the loss bound and free its thread, for fork, updateState and deleteThread" in {
      for how <- changes do
        withClue(s"$how: ") {
          val time  = ManualTime()
          val store = Hooked(InMemoryCheckpointer(time))
          val two   = TwoStep()
          two.gate.countDown()
          await(runtime(store, time, slow).start(thread, two.graph, "x", run("first")).value).completed
          val latest = store.latest(thread).value.value
          val held   = heldBy(how)
          store.onRenew = alwaysRenewed
          // as the change's write reaches the store, the store's clock has run past its claim and another took it
          val taken = new AtomicBoolean(false)
          def takeOver(): Unit =
            if taken.compareAndSet(false, true) then {
              time.skewStore(3.hours)
              store.underlying.claim(held, ClaimRequest(RunId("thief"), 1.hour)).value: Unit
            }
          store.onCommit = commit => {
            if commit.events.exists(_.runId == "change") then takeOver()
            None
          }
          store.onDelete = () => takeOver()
          val releasing = new CountDownLatch(1)
          val hang      = new CountDownLatch(1)
          store.onRelease = () => { releasing.countDown(); stuckUntil(hang); None }
          val rt = runtime(store, time, second)

          within(changing(how, rt, two, latest.checkpoint.id, "change")).value.left.value shouldBe
            GraphError.ThreadBusy(held.value, if how == "fork" then None else Some(latest.checkpoint.id))
          taken.get shouldBe true
          rt.heldLocally(held) shouldBe false
          releasing.await(5, TimeUnit.SECONDS) shouldBe true
          hang.countDown()
          store.latest(thread).value.value shouldBe latest
          if how == "fork" then store.latest(held).value shouldBe None
        }
    }

  "A change whose claim is found lost while its next store call waits behind a hung renewal" should
    "return ThreadBusy within the loss bound, free its thread, and commit nothing, for fork, updateState and deleteThread" in {
      for how <- changes do
        withClue(s"$how: ") {
          val time       = ManualTime()
          val underlying = InMemoryCheckpointer(time)
          val store      = Serialised(underlying)
          val two        = TwoStep()
          two.gate.countDown()
          await(runtime(store, time, slow).start(thread, two.graph, "x", run("first")).value).completed
          val latest = store.latest(thread).value.value
          val held   = heldBy(how)
          // once the change's claim is granted, its first renewal hangs holding the store's lock, and the
          // change's next read - every change reads its thread again under the claim - queues behind it
          store.serialiseAfterClaim()
          store.hangRenewals()
          val rt       = runtime(store, time, ClaimPolicy(300.millis, 10.millis))
          val releases = store.released.size
          val returned = new CountDownLatch(1)
          rt.changeReturned = id => if id == held then returned.countDown()
          val outcome = new java.util.concurrent.LinkedBlockingQueue[Result[Any]]()
          Thread.ofVirtual().start(() => outcome.offer(changing(how, rt, two, latest.checkpoint.id, "change")): Unit)
          store.renewing.await(5, TimeUnit.SECONDS) shouldBe true
          eventually(store.queued)

          // the lease passes: the change must return within twice the ttl, without the store
          time.advance(1.second)
          Option(outcome.poll(5, TimeUnit.SECONDS)).value.left.value shouldBe GraphError.ThreadBusy(held.value, None)
          rt.heldLocally(held) shouldBe false

          // the store returns: the change's read completes, its commit finds the fence, and nothing lands
          store.unhang()
          returned.await(5, TimeUnit.SECONDS) shouldBe true
          store.changes.get shouldBe 0
          store.latest(thread).value.value shouldBe latest
          if how == "fork" then store.latest(held).value shouldBe None

          // the lost claim is given back on a detached thread, queued behind the hung renewal - which renewed
          // it, nobody having taken it - and the change's return does not wait for it: until it lands the
          // store still holds the claim, and a new change is refused ThreadBusy
          eventually(store.released.size == releases + 1)

          // and the thread is free for the same change in this runtime
          changing(how, rt, two, latest.checkpoint.id, "again").isRight shouldBe true
        }
    }

  "A change whose claim is found lost before its commit begins" should
    "never reach the store with it, and be ThreadBusy, for fork, updateState and deleteThread" in {
      for how <- changes do
        withClue(s"$how: ") {
          val time  = ManualTime()
          val store = Hooked(InMemoryCheckpointer(time))
          val two   = TwoStep()
          two.gate.countDown()
          await(runtime(store, time, slow).start(thread, two.graph, "x", run("first")).value).completed
          val latest = store.latest(thread).value.value
          val held   = heldBy(how)
          val rt     = runtime(store, time)
          // the change's renewal is refused as stale while it reads its thread under the claim: the loss is
          // declared, and acted on, before that read returns
          val refused  = new AtomicBoolean(false)
          val declared = new CountDownLatch(1)
          rt.renewalSettled = _ => if refused.get then declared.countDown()
          val claimsBefore = store.claims.get
          val armed        = new AtomicBoolean(true)
          store.onLatest = () => {
            if store.claims.get > claimsBefore && armed.compareAndSet(true, false) then {
              store.onRenew = () => { refused.set(true); Some(Left(GraphError.StaleClaim(held.value, 0L, None))) }
              declared.await(5, TimeUnit.SECONDS) shouldBe true
            }
            None
          }
          val reached = new AtomicInteger()
          store.onCommit = commit => {
            if commit.events.exists(e => e.runId == "change") then reached.incrementAndGet()
            None
          }
          store.onDelete = () => reached.incrementAndGet(): Unit
          val releasing = store.releases.get

          val outcome = changing(how, rt, two, latest.checkpoint.id, "change")
          outcome.left.value shouldBe GraphError.ThreadBusy(
            held.value,
            if how == "fork" then None else Some(latest.checkpoint.id)
          )
          reached.get shouldBe 0
          store.latest(thread).value.value shouldBe latest
          rt.heldLocally(held) shouldBe false
          eventually(store.releases.get == releasing + 1)
        }
    }

  "A run whose claim is found lost while a renewal hangs in the store" should
    "end and free its thread without joining that renewal, in every durability mode" in {
      for durability <- Durability.values do
        withClue(s"$durability: ") {
          val time     = ManualTime()
          val store    = Hooked(InMemoryCheckpointer(time))
          val two      = TwoStep()
          val renewing = new CountDownLatch(1)
          val hang     = new CountDownLatch(1)
          val rt       = runtime(store, time)
          val handle   = rt.start(thread, two.graph, "x", run("first"), durability).value
          two.awaitEntered()
          val token = tokenOf(store)
          // the next renewal stays in the store, interrupted or not, until `hang` opens
          store.onRenew = () => {
            renewing.countDown()
            stuckUntil(hang)
            Some(Left(ProcessingError("store", "timed out")))
          }
          renewing.await(5, TimeUnit.SECONDS) shouldBe true

          time.advance(31.seconds)
          val ended = within(await(handle))
          hang.countDown()
          ended.value.failed._2 shouldBe GraphError.ClaimLost("t", token, None)
          rt.heldLocally(thread) shouldBe false
          eventually(store.releases.get == 1)
        }
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
    val rt      = runtime(store, time)
    val joining = new CountDownLatch(1)
    rt.joiningRenewal = _ => joining.countDown()
    val handle = rt.start(thread, two.graph, "x", run("first")).value
    two.awaitEntered()
    armed.set(true)
    renewing.await(5, TimeUnit.SECONDS) shouldBe true

    two.gate.countDown()
    // the run has ended and is stopping its heartbeat: it waits for the renewal in the store, and must
    // not release meanwhile
    joining.await(5, TimeUnit.SECONDS) shouldBe true
    order.asScala.toVector shouldBe empty
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
    // the renewal could not be joined within ttl, so the claim is given back on a detached thread that
    // the run's end does not wait for: it may reach the store only after `await` returns
    eventually(store.releases.get == 1)
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

  "deleteThread" should "renew the claim it holds while the store deletes, so a delete slower than ttl still lands" in {
    val time  = ManualTime()
    val store = Hooked(InMemoryCheckpointer(time))
    val two   = TwoStep()
    two.gate.countDown()
    await(runtime(store, time, slow).start(thread, two.graph, "x", run("first")).value).completed
    val releases = store.releases.get
    val thief    = new AtomicReference[Option[Result[RunClaim]]](None)
    // the delete stalls for 40s of a 30s ttl, letting a renewal land at 20s and again at 40s
    store.onDelete = () => {
      Seq(20.seconds, 20.seconds).foreach { step =>
        time.advance(step)
        val landed = store.renewed.get
        eventually(store.renewed.get > landed)
      }
      thief.set(Some(store.claim(thread, ClaimRequest(RunId("thief"), 30.seconds))))
    }
    runtime(store, time).deleteThread(thread, run("deleter")) shouldBe Right(())
    thief.get.value.left.value shouldBe a[GraphError.ThreadBusy]
    store.latest(thread).value shouldBe None
    // the delete removed the claim with the thread: nothing is given back
    store.releases.get shouldBe releases
  }

  it should "give back the newest claim whose release failed, whatever an older run's late detached release did" in {
    val down: Option[Result[Unit]] = Some(Left(ProcessingError("store", "down")))
    for (label, late) <- Seq("failing" -> down, "landing" -> Some(Right(()))) do
      withClue(s"an older release $label late: ") {
        val time  = ManualTime()
        val store = Hooked(InMemoryCheckpointer(time))
        val rt    = runtime(store, time, ClaimPolicy(300.millis, 10.millis))
        val older = TwoStep()
        val first = rt.start(thread, older.graph, "x", run("first")).value
        older.awaitEntered()
        val oldToken = tokenOf(store)
        // the older run's release - detached, as its claim is lost - is held until `lateGate` opens
        val releasing = new CountDownLatch(1)
        val lateGate  = new CountDownLatch(1)
        val releasers = new ConcurrentLinkedQueue[Thread]()
        val failNewer = new AtomicBoolean(true)
        val asked     = new ConcurrentLinkedQueue[FencingToken]()
        store.onReleaseOf = token => {
          asked.add(token)
          if token == oldToken then {
            releasers.add(Thread.currentThread())
            releasing.countDown()
            stuckUntil(lateGate)
            late
          } else if failNewer.get then down
          else None
        }
        store.onRenew = unreachable
        time.advance(1.second)
        await(first).failed._2 shouldBe GraphError.ClaimLost("t", oldToken, None)
        releasing.await(5, TimeUnit.SECONDS) shouldBe true

        // a later run takes the lapsed thread over in the same runtime, and its own release fails
        store.onRenew = () => None
        val newer = TwoStep()
        newer.gate.countDown()
        await(rt.recover(thread, newer.graph, run("second")).value).completed
        val newToken = tokenOf(store)
        newToken.value should be > oldToken.value
        asked.asScala.toSeq should contain(newToken)

        // only then does the older release return
        lateGate.countDown()
        releasers.asScala.foreach(_.join(5000))
        releasers.asScala.exists(_.isAlive) shouldBe false

        // the delete gives back the newer claim, still live, and so is not refused by it
        failNewer.set(false)
        asked.clear()
        rt.deleteThread(thread, run("deleter")) shouldBe Right(())
        asked.asScala.toSeq shouldBe Seq(newToken)
        store.latest(thread).value shouldBe None
      }
  }

  // ---- 5c: a store read never holds a lock another thread's run needs ----

  private def other = ThreadId("other")

  /** Whether the calling thread is one of `prefix`'s, as the runtime names its threads. */
  private def onThread(prefix: String): Boolean = Thread.currentThread().getName.startsWith(prefix)

  /**
   * Subscribes to `other` in `runtime` and holds the dispatcher's second read of the store - the first
   * after its replay found the thread empty: the read that switches it to live - until `unstall` opens.
   */
  private def stallGoingLive(store: Hooked, runtime: GraphRuntime, unstall: CountDownLatch): Subscription = {
    val reads   = new AtomicInteger()
    val stalled = new CountDownLatch(1)
    store.onEventsAfter = threadId =>
      if threadId == other && onThread("llm4s-subscriber-") && reads.incrementAndGet() == 2 then {
        stalled.countDown()
        stuckUntil(unstall)
      }
    val subscription = runtime.subscribe(other)(_ => ()).value
    stalled.await(5, TimeUnit.SECONDS) shouldBe true
    subscription
  }

  "A subscription whose switch to live is stuck in the store" should "not hold up a run on another thread: it is admitted, completes and frees its thread" in {
    val time    = ManualTime()
    val store   = Hooked(InMemoryCheckpointer(time))
    val rt      = runtime(store, time, watch = WatchPolicy.disabled)
    val unstall = new CountDownLatch(1)
    val sub     = stallGoingLive(store, rt, unstall)
    val two     = TwoStep()
    two.gate.countDown()
    val outcome = within(awaitResult(rt.start(thread, two.graph, "x", run("first")).value))
    unstall.countDown()
    outcome.value.value shouldBe a[RunResult.Completed[?]]
    rt.heldLocally(thread) shouldBe false
    sub.cancel()
  }

  it should "not hold up the end of a run on another thread that loses its claim" in {
    val time   = ManualTime()
    val store  = Hooked(InMemoryCheckpointer(time))
    val rt     = runtime(store, time, watch = WatchPolicy.disabled)
    val two    = TwoStep()
    val handle = rt.start(thread, two.graph, "x", run("first")).value
    two.awaitEntered()
    val unstall = new CountDownLatch(1)
    val sub     = stallGoingLive(store, rt, unstall)
    store.onRenew = unreachable
    time.advance(31.seconds)
    val outcome = within(awaitResult(handle))
    val held    = within(rt.heldLocally(thread))
    unstall.countDown()
    outcome.value.value.failed._2 shouldBe a[GraphError.ClaimLost]
    held shouldBe Some(false)
    sub.cancel()
  }

  "A hand-over whose read of the events missing before it is stuck in the store" should "not hold up a run on another thread, and still deliver the thread's log in order" in {
    val time       = ManualTime()
    val underlying = InMemoryCheckpointer(time)
    val store      = Hooked(underlying)
    // `here` hands its commits over; `elsewhere` commits behind its back. The watch polls once an hour,
    // so the only read of what `elsewhere` committed is the hand-over's gap read
    val here      = runtime(store, time, watch = WatchPolicy(pollInterval = 1.hour))
    val elsewhere = runtime(underlying, time, watch = WatchPolicy.disabled)
    val seen      = new ConcurrentLinkedQueue[Long]()
    val watched   = new CountDownLatch(1)
    store.onEventsAfter = threadId => if threadId == other && onThread("llm4s-watch-") then watched.countDown()
    val sub = here
      .subscribe(other) {
        case StreamEvent.Durable(record) => seen.add(record.seq): Unit
        case _                           => ()
      }
      .value
    // live, and its watch has read the empty thread
    watched.await(5, TimeUnit.SECONDS) shouldBe true
    val first = TwoStep()
    first.gate.countDown()
    await(elsewhere.start(other, first.graph, "a", run("elsewhere")).value).completed
    val unstall = new CountDownLatch(1)
    val stalled = new CountDownLatch(1)
    store.onEventsAfter = threadId =>
      if threadId == other && !onThread("llm4s-watch-") && !onThread("llm4s-subscriber-") then {
        stalled.countDown()
        stuckUntil(unstall)
      }
    val second = TwoStep()
    second.gate.countDown()
    val behind = new AtomicReference[Option[RunResult[Vector[String]]]](None)
    Thread.ofVirtual().start(() => behind.set(Some(await(here.start(other, second.graph, "b", run("here")).value))))
    stalled.await(5, TimeUnit.SECONDS) shouldBe true
    val two = TwoStep()
    two.gate.countDown()
    val outcome = within(awaitResult(here.start(thread, two.graph, "x", run("first")).value))
    // a healthy run waits for its own hand-over's gap read: it has not ended, so no barrier precedes it
    behind.get shouldBe None
    unstall.countDown()
    outcome.value.value shouldBe a[RunResult.Completed[?]]
    eventually(behind.get.isDefined)
    behind.get.value shouldBe a[RunResult.Completed[?]]
    val log = underlying.eventsAfter(other, 0L, 10_000).value.map(_.seq)
    eventually(seen.size == log.size)
    seen.asScala.toVector shouldBe log
    sub.cancel()
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

  it should "record a takeover even from a run with the same run id" in {
    val time  = ManualTime()
    val store = InMemoryCheckpointer(time)
    val stale = TwoStep()
    val first = runtime(store, time, slow).start(thread, stale.graph, "x", run("same")).value
    stale.awaitEntered()
    time.skewStore(3.hours)
    val successor = TwoStep()
    successor.gate.countDown()
    // a reused run id: the store still reports the expired claim it replaced, which may still be executing
    await(runtime(store, time, slow).recover(thread, successor.graph, run("same")).value).completed
    events(store).map(_.event).collect { case r: RunEvent.RunRecovered => r.takenOverFrom } shouldBe
      Vector(Some("same"))

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
