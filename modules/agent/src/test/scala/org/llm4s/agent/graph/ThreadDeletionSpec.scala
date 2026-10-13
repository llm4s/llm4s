package org.llm4s.agent.graph

import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }

import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.concurrent.duration.*

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

  /** Forwards to `underlying`; `onLatest(n)` runs after the `n`th call to `latest` has read the thread. */
  private class Hooked(val underlying: Checkpointer) extends Checkpointer:
    @volatile var onLatest: Int => Unit                                     = _ => ()
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
    def deleteThread(threadId: ThreadId, token: FencingToken)       = underlying.deleteThread(threadId, token)
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
