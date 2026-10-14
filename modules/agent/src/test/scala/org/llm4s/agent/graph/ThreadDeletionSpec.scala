package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }

import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * [[GraphRuntime.deleteThread]] against runs and subscriptions in other runtimes (#1700): the delete is
 * fenced by its own claim, so one whose claim another runtime took over deletes nothing; and a deleted
 * thread's subscriptions end with [[DisconnectReason.ThreadDeleted]], so none of them reads a new
 * thread of the same id as if it continued the old one. The store side is `CheckpointerContract`.
 */
class ThreadDeletionSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues with Eventually:
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(10, Seconds))

  private val start = Instant.parse("2026-10-09T12:00:00Z")
  private val ttl   = 30.seconds

  private val threadIds             = new AtomicInteger()
  private def newThread(): ThreadId = ThreadId(s"deletion-${threadIds.incrementAndGet()}")

  /** A clock the test moves. */
  final private class MovingClock extends Clock:
    private val current                        = new AtomicReference(start)
    def instant(): Instant                     = current.get
    def getZone: ZoneId                        = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    def advance(by: FiniteDuration): Unit      = current.updateAndGet(_.plusNanos(by.toNanos)): Unit

  /**
   * Forwards to `underlying`; `onLatest(n)` runs after the `n`th call to `latest` has read the thread,
   * and `onDelete` before the store deletes it.
   */
  private class Hooked(val underlying: Checkpointer) extends Checkpointer:
    @volatile var onLatest: Int => Unit                                     = _ => ()
    @volatile var onDelete: () => Unit                                      = () => ()
    private val latestCalls                                                 = new AtomicInteger()
    def claim(threadId: ThreadId, request: ClaimRequest)                    = underlying.claim(threadId, request)
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) = underlying.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: FencingToken)                    = underlying.release(threadId, token)
    def commit(threadId: ThreadId, commit: Commit)                          = underlying.commit(threadId, commit)
    def latest(threadId: ThreadId) =
      val read = underlying.latest(threadId)
      onLatest(latestCalls.incrementAndGet())
      read
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) = underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long)          = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId, token: FencingToken) =
      onDelete()
      underlying.deleteThread(threadId, token)
    def history(threadId: ThreadId, before: Option[String], limit: Int) = underlying.history(threadId, before, limit)
    def checkpoint(threadId: ThreadId, checkpointId: String)            = underlying.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy)              = underlying.prune(threadId, policy)

  private def checkpoint(thread: ThreadId, id: String, tenant: Option[String]): Checkpoint =
    Checkpoint(
      Checkpoint.CurrentFormat,
      id,
      None,
      thread.value,
      "other-run",
      CheckpointStatus.Running,
      start,
      GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false),
      tenant,
      None
    )

  private def event(name: String): EventDraft =
    EventDraft("other-run", None, None, None, start, RunEvent.Custom(name, 1, ujson.Obj()))

  /**
   * A runtime whose `deleteThread` stalls past its claim's `ttl` after re-reading the thread under its
   * claim - the second `latest` - and before the store deletes it, while another runtime takes the
   * thread over, for `tenant`, and commits checkpoint `b1`. Returns the store, the runtime, and the
   * other run's claim.
   */
  private def takenOverWhileDeleting(thread: ThreadId, tenant: Option[String]) =
    val clock   = MovingClock()
    val store   = Hooked(InMemoryCheckpointer(clock))
    val runtime = GraphRuntime(store, clock, ClaimPolicy(ttl, 10.seconds))
    val taken   = new AtomicReference[RunClaim]()
    store.onLatest = n =>
      if n == 2 then
        clock.advance(ttl + 1.second)
        val claim = store.underlying.claim(thread, ClaimRequest(RunId("other-run"), ttl)).value
        store.underlying.commit(thread, Commit(claim.token, Some(checkpoint(thread, "b1", tenant)))).value
        taken.set(claim)
    (store, runtime, taken)

  private val logKey = StateKey.appending[String]("log")

  /** One node that emits its input as a durable custom event. */
  private val emitter: CompiledGraph[String, Vector[String]] =
    val b = GraphBuilder("emitter", "v1")
    val step = b.node[String]("step", writes = Set(logKey)) { (input, _, context) =>
      context.emit("said", 1, ujson.Str(input))
      continue(Command.empty.update(logKey, input))
    }
    b.compile(step)(_.get(logKey)).value

  private def tenant(name: String, run: String) =
    RunConfig().withTenantId(TenantId(name)).withRunId(RunId(run))

  final private class Seen extends (StreamEvent => Unit):
    private val events                  = new CopyOnWriteArrayList[StreamEvent]()
    def apply(event: StreamEvent): Unit = events.add(event): Unit
    def all: Vector[StreamEvent]        = events.asScala.toVector
    def durable: Vector[EventRecord]    = all.collect { case StreamEvent.Durable(record) => record }
    def said: Vector[String] = durable.collect {
      case r if r.event.isInstanceOf[RunEvent.Custom] =>
        r.event.asInstanceOf[RunEvent.Custom].payload.str
    }
    def disconnected: Option[StreamEvent.Disconnected] = all.collectFirst { case d: StreamEvent.Disconnected => d }

  private val watching = WatchPolicy(pollInterval = 20.millis)

  "GraphRuntime.deleteThread" should "delete nothing when another runtime takes the thread over before the store deletes it" in {
    val thread                  = newThread()
    val (store, runtime, taken) = takenOverWhileDeleting(thread, None)

    runtime.deleteThread(thread).left.value shouldBe GraphError.ThreadBusy(thread.value, Some("b1"))

    store.underlying.latest(thread).value.map(_.checkpoint.id) shouldBe Some("b1")
    // the other run still holds the thread, and its commits are accepted
    store.underlying.commit(thread, Commit(taken.get.token, events = Vector(event("b2")))).value should have size 1
    store.underlying.claim(thread, ClaimRequest(RunId("third"), ttl)).left.value shouldBe
      GraphError.ThreadBusy(thread.value, Some("b1"), Some("other-run"))
  }

  it should "refuse another tenant's run that took the thread over as TenantMismatch, naming nothing of it" in {
    val thread              = newThread()
    val (store, runtime, _) = takenOverWhileDeleting(thread, Some("B"))

    runtime.deleteThread(thread).left.value shouldBe GraphError.TenantMismatch(thread.value, None)

    store.underlying.latest(thread).value.map(_.checkpoint.id) shouldBe Some("b1")
  }

  // ---- subscriptions across a delete ----

  "A subscription in another runtime" should "end with ThreadDeleted, and never deliver a new thread of the same id" in {
    val store  = InMemoryCheckpointer()
    val r1     = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, watching)
    val r2     = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, watching)
    val thread = newThread()
    await(r1.start(thread, emitter, "a-said", tenant("A", "a1")).value)
    val lastOfA = store.eventsAfter(thread, 0L, 1000).value.last.seq
    val seen    = Seen()
    val sub     = r1.subscribe(thread, config = tenant("A", "a-watch"))(seen).value
    eventually(seen.durable.lastOption.map(_.seq) shouldBe Some(lastOfA))

    // another runtime deletes the thread, and another tenant creates one of the same id
    r2.deleteThread(thread, tenant("A", "a-delete")).value shouldBe (())
    (1 to 3).foreach(i => await(r2.start(thread, emitter, s"b-said-$i", tenant("B", s"b$i")).value))

    eventually(seen.disconnected shouldBe Some(StreamEvent.Disconnected(lastOfA, DisconnectReason.ThreadDeleted)))
    seen.said shouldBe Vector("a-said")
    eventually(r1.liveSubscriptions(thread) shouldBe 0)
    sub.cancel()
  }

  "A subscription in the deleting runtime" should "end with ThreadDeleted when the thread is deleted, without a watch" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy.disabled)
    val thread  = newThread()
    await(runtime.start(thread, emitter, "first", RunConfig()).value)
    val lastSeq = store.eventsAfter(thread, 0L, 1000).value.last.seq
    val seen    = Seen()
    runtime.subscribe(thread)(seen).value
    // the whole log replayed - not only the custom event, which the run's closing events follow
    eventually(seen.durable.lastOption.map(_.seq) shouldBe Some(lastSeq))
    seen.said shouldBe Vector("first")

    runtime.deleteThread(thread).value shouldBe (())

    eventually(seen.disconnected shouldBe Some(StreamEvent.Disconnected(lastSeq, DisconnectReason.ThreadDeleted)))
    await(runtime.start(thread, emitter, "second", RunConfig()).value)
    seen.said shouldBe Vector("first")
  }

  it should "end with ThreadDeleted when it joins while the store is deleting the thread" in {
    val store   = Hooked(InMemoryCheckpointer())
    val runtime = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy.disabled)
    val thread  = newThread()
    await(runtime.start(thread, emitter, "old", RunConfig()).value)
    val lastOld  = store.underlying.eventsAfter(thread, 0L, 1000).value.last.seq
    val deleting = new java.util.concurrent.CountDownLatch(1)
    val resume   = new java.util.concurrent.CountDownLatch(1)
    store.onDelete = () =>
      deleting.countDown()
      resume.await(10, java.util.concurrent.TimeUnit.SECONDS): Unit
    val deleted = new java.util.concurrent.LinkedBlockingQueue[Result[Unit]]()
    Thread.ofVirtual().start(() => deleted.put(runtime.deleteThread(thread))): Unit
    deleting.await(10, java.util.concurrent.TimeUnit.SECONDS) shouldBe true

    // joins while the delete is in flight, and replays the thread about to be deleted
    val seen = Seen()
    val sub  = runtime.subscribe(thread)(seen).value
    eventually(seen.said shouldBe Vector("old"))
    resume.countDown()
    Option(deleted.poll(10, java.util.concurrent.TimeUnit.SECONDS)).value shouldBe Right(())

    eventually(seen.disconnected shouldBe Some(StreamEvent.Disconnected(lastOld, DisconnectReason.ThreadDeleted)))
    sub.cancel()
  }

  "A subscription made after a delete" should "follow the new thread, whose events are numbered past the deleted thread's" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy.disabled)
    val thread  = newThread()
    await(runtime.start(thread, emitter, "old", RunConfig()).value)
    val lastOld = store.eventsAfter(thread, 0L, 1000).value.last.seq
    runtime.deleteThread(thread).value shouldBe (())

    val seen = Seen()
    val sub  = runtime.subscribe(thread)(seen).value
    await(runtime.start(thread, emitter, "new", RunConfig()).value)

    val log = store.eventsAfter(thread, 0L, 1000).value
    // the deleted thread's next number is skipped
    log.head.seq shouldBe lastOld + 2
    eventually(seen.durable.map(_.seq) shouldBe log.map(_.seq))
    seen.said shouldBe Vector("new")
    seen.disconnected shouldBe None
    sub.cancel()
  }

  it should "take the new thread's first local commit as contiguous, however long the deleted log was" in {
    // a watch that never polls: the new thread's events reach the subscription only as this runtime's hand-over
    val runtime =
      GraphRuntime(InMemoryCheckpointer(), Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(true, 1.hour))
    val thread   = newThread()
    val capacity = 8
    (1 to 3).foreach(i => await(runtime.start(thread, emitter, s"old-$i", RunConfig()).value))
    runtime.deleteThread(thread).value shouldBe (())

    val seen = Seen()
    val sub  = runtime.subscribe(thread, afterSeq = 0L, capacity = capacity)(seen).value
    // its watch starts once it has replayed the (empty) new thread and joined the live set
    eventually(runtime.storeWatches(thread) shouldBe 1)
    await(runtime.start(thread, emitter, "new", RunConfig()).value)

    eventually(seen.said shouldBe Vector("new"))
    seen.disconnected shouldBe None
    sub.cancel()
  }

  it should "take the new thread's first commit from another runtime as contiguous, however long the deleted log was" in {
    val store    = InMemoryCheckpointer()
    val watching = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy(pollInterval = 20.millis))
    val other    = GraphRuntime(store, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy.disabled)
    val thread   = newThread()
    (1 to 3).foreach(i => await(other.start(thread, emitter, s"old-$i", RunConfig()).value))
    other.deleteThread(thread).value shouldBe (())

    val seen = Seen()
    val sub  = watching.subscribe(thread, afterSeq = 0L, capacity = 8)(seen).value
    eventually(watching.storeWatches(thread) shouldBe 1)
    await(other.start(thread, emitter, "new", RunConfig()).value)

    eventually(seen.said shouldBe Vector("new"))
    seen.disconnected shouldBe None
    sub.cancel()
  }

  it should "follow a run of the new thread from its first event, as a run handle's subscription does" in {
    val runtime = GraphRuntime(InMemoryCheckpointer(), Clock.systemUTC(), ClaimPolicy.default, WatchPolicy.disabled)
    val thread  = newThread()
    await(runtime.start(thread, emitter, "old", RunConfig()).value)
    runtime.deleteThread(thread).value shouldBe (())

    // a run handle subscribes from just before its run's claim, the new thread's first event
    val handle = runtime.start(thread, emitter, "new", RunConfig()).value
    val seen   = Seen()
    val sub    = handle.subscribe()(seen).value
    await(handle)
    eventually(seen.said shouldBe Vector("new"))
    seen.disconnected shouldBe None
    sub.cancel()
  }
