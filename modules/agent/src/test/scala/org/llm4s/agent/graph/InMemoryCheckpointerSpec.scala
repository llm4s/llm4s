package org.llm4s.agent.graph

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import scala.concurrent.duration.*

/**
 * The reference store's own cases. Every store, this one included, must also pass
 * `CheckpointerContract` in `llm4s-agent-testkit`, which runs it against `InMemoryCheckpointer`
 * (`InMemoryCheckpointerContractSpec`); this module cannot depend on the testkit, which depends on it.
 */
class InMemoryCheckpointerSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val thread = ThreadId("t")
  private val now    = Instant.parse("2026-10-02T12:00:00Z")
  private val ttl    = 30.seconds

  private def checkpoint(id: String, parent: Option[String]) =
    Checkpoint(
      Checkpoint.CurrentFormat,
      id,
      parent,
      thread.value,
      "run",
      CheckpointStatus.Running,
      now,
      GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false)
    )
  private def event(name: String) = EventDraft("run", None, None, None, now, RunEvent.Custom(name, 1, ujson.Null))
  private def write(checkpointId: String, taskId: String) =
    PendingWrite(checkpointId, taskId, "n", Vector.empty, Vector.empty)

  /** A clock the test sets. */
  final private class MutableClock(start: Instant) extends Clock {
    @volatile private var current: Instant     = start
    def instant(): Instant                     = current
    def getZone: ZoneId                        = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    def advance(by: FiniteDuration): Unit      = current = current.plusNanos(by.toNanos)
  }

  /** A store over a clock the test moves, with `thread` claimed; `token` fences its commits. */
  final private class Claimed {
    val clock = new MutableClock(now)
    val store = InMemoryCheckpointer(clock)
    val token = store.claim(thread, ClaimRequest(RunId("run"), ttl)).value.token
    def commit(checkpoint: Option[Checkpoint], writes: Vector[PendingWrite], events: Vector[EventDraft]) =
      store.commit(thread, Commit(token, checkpoint, writes, events))
  }

  "InMemoryCheckpointer" should "number events contiguously across commits" in {
    val c     = Claimed()
    val store = c.store
    store.latest(thread).value shouldBe None
    c.commit(Some(checkpoint("c1", None)), Vector.empty, Vector(event("a"), event("b"))).value.map(_.seq) shouldBe
      Vector(1L, 2L)
    c.commit(None, Vector(write("c1", "0.0")), Vector(event("c"))).value.map(_.seq) shouldBe Vector(3L)
    store.eventsAfter(thread, 0L, 10).value.map(_.seq) shouldBe Vector(1L, 2L, 3L)
    store.eventsAfter(thread, 1L, 1).value.map(_.seq) shouldBe Vector(2L)
    store.latest(thread).value.map(_.pendingWrites.map(_.taskId)) shouldBe Some(Vector("0.0"))
  }

  it should "reject a checkpoint whose parent is not the latest, applying nothing" in {
    val c     = Claimed()
    val store = c.store
    c.commit(Some(checkpoint("c1", None)), Vector.empty, Vector(event("a")))
    c.commit(Some(checkpoint("c2", Some("other"))), Vector.empty, Vector(event("lost"))).left.value shouldBe
      GraphError.CheckpointConflict("t", Some("other"), Some("c1"))
    c.commit(Some(checkpoint("c2", None)), Vector.empty, Vector.empty).left.value shouldBe
      GraphError.CheckpointConflict("t", None, Some("c1"))
    // the refused commit consumed no sequence number
    c.commit(None, Vector.empty, Vector(event("b"))).value.map(_.seq) shouldBe Vector(2L)
    store.latest(thread).value.map(_.checkpoint.id) shouldBe Some("c1")
  }

  it should "reject pending writes for a checkpoint other than the latest" in {
    val c     = Claimed()
    val store = c.store
    c.commit(None, Vector(write("c1", "0.0")), Vector.empty).left.value shouldBe a[GraphError.InvalidCommit]
    c.commit(Some(checkpoint("c1", None)), Vector.empty, Vector.empty)
    c.commit(None, Vector(write("c0", "0.0")), Vector(event("x"))).left.value shouldBe a[GraphError.InvalidCommit]
    store.eventsAfter(thread, 0L, 10).value shouldBe empty
  }

  it should "drop the old checkpoint's pending writes when a new checkpoint lands" in {
    val c = Claimed()
    c.commit(Some(checkpoint("c1", None)), Vector(write("c1", "0.0")), Vector.empty)
    c.commit(Some(checkpoint("c2", Some("c1"))), Vector(write("c2", "1.0")), Vector.empty)
    c.store.latest(thread).value.map(s => s.checkpoint.id -> s.pendingWrites.map(_.taskId)) shouldBe Some(
      "c2" -> Vector("1.0")
    )
  }

  it should "report the replay floor after compaction and never reuse a sequence number" in {
    val c     = Claimed()
    val store = c.store
    c.commit(Some(checkpoint("c1", None)), Vector.empty, (1 to 5).map(i => event(s"e$i")).toVector)
    store.compactEvents(thread, 4L).value shouldBe (())
    store.eventsAfter(thread, 0L, 10).left.value shouldBe GraphError.ReplayUnavailable("t", 4L)
    store.eventsAfter(thread, 2L, 10).left.value shouldBe GraphError.ReplayUnavailable("t", 4L)
    store.eventsAfter(thread, 3L, 10).value.map(_.seq) shouldBe Vector(4L, 5L)
    store.compactEvents(thread, 2L).value shouldBe (()) // a floor never moves back
    store.eventsAfter(thread, 3L, 10).value.map(_.seq) shouldBe Vector(4L, 5L)
    store.compactEvents(thread, 100L).value shouldBe (()) // clamps to the next sequence
    store.eventsAfter(thread, 5L, 10).value shouldBe empty
    c.commit(None, Vector.empty, Vector(event("e6"))).value.map(_.seq) shouldBe Vector(6L)
  }

  it should "delete a thread's checkpoint, pending writes, events and claim, leaving other threads alone" in {
    val c     = Claimed()
    val store = c.store
    val other = ThreadId("other")
    c.commit(Some(checkpoint("c1", None)), Vector.empty, Vector(event("a"), event("b")))
    c.commit(None, Vector(write("c1", "0.0")), Vector.empty)
    val otherToken = store.claim(other, ClaimRequest(RunId("o"), ttl)).value.token
    store.commit(other, Commit(otherToken, Some(checkpoint("o1", None)), Vector.empty, Vector(event("x"))))

    store.deleteThread(thread).value shouldBe (())

    store.latest(thread).value shouldBe None
    store.eventsAfter(thread, 0L, 10).value shouldBe empty
    store.latest(other).value.map(_.checkpoint.id) shouldBe Some("o1")
    store.eventsAfter(other, 0L, 10).value.map(_.seq) shouldBe Vector(1L)
    // the claim went with the thread, so its token is stale; a new claim is granted at once
    store.commit(thread, Commit(c.token)).left.value shouldBe a[GraphError.StaleClaim]
    val again = store.claim(thread, ClaimRequest(RunId("again"), ttl)).value
    again.token.value should be > otherToken.value
    // the id is a new thread again: a first checkpoint is accepted, its events numbered from 1
    store
      .commit(thread, Commit(again.token, Some(checkpoint("c1", None)), Vector.empty, Vector(event("again"))))
      .value
      .map(_.seq) shouldBe Vector(1L)
    store.deleteThread(ThreadId("unknown")).value shouldBe (())
  }

  it should "grant one live claim per thread, by its own clock, and let an expired one be taken over" in {
    val c     = Claimed()
    val store = c.store
    store.claim(thread, ClaimRequest(RunId("thief"), ttl)).left.value shouldBe
      GraphError.ThreadBusy("t", None, Some("run"))
    c.clock.advance(ttl)
    val taken = store.claim(thread, ClaimRequest(RunId("thief"), ttl)).value
    taken.holder shouldBe RunId("thief")
    taken.token.value should be > c.token.value
    taken.expiresAt shouldBe now.plusSeconds(60)
    // the first holder's token is fenced off: no commit, no renewal, and its release is a no-op
    c.commit(None, Vector.empty, Vector(event("late"))).left.value shouldBe
      GraphError.StaleClaim("t", c.token.value, Some(taken.token.value))
    store.renew(thread, c.token, ttl).left.value shouldBe a[GraphError.StaleClaim]
    store.release(thread, c.token).value shouldBe (())
    store.claim(thread, ClaimRequest(RunId("third"), ttl)).left.value shouldBe
      GraphError.ThreadBusy("t", None, Some("thief"))
    store.eventsAfter(thread, 0L, 10).value shouldBe empty
  }

  it should "renew a claim from the store's clock, even after it expired if nobody took it over" in {
    val c     = Claimed()
    val store = c.store
    c.clock.advance(ttl + 1.second)
    val renewed = store.renew(thread, c.token, ttl).value
    renewed.token shouldBe c.token
    renewed.expiresAt shouldBe now.plusSeconds(61)
    c.commit(Some(checkpoint("c1", None)), Vector.empty, Vector.empty).value shouldBe empty
    store.claim(thread, ClaimRequest(RunId("thief"), ttl)).left.value shouldBe
      GraphError.ThreadBusy("t", Some("c1"), Some("run"))
  }

  it should "free a released thread at once, and keep nothing for a thread that only held a claim" in {
    val c     = Claimed()
    val store = c.store
    store.release(thread, c.token).value shouldBe (())
    store.commit(thread, Commit(c.token)).left.value shouldBe GraphError.StaleClaim("t", c.token.value, None)
    store.renew(thread, c.token, ttl).left.value shouldBe GraphError.StaleClaim("t", c.token.value, None)
    val next = store.claim(thread, ClaimRequest(RunId("next"), ttl)).value
    next.token.value shouldBe c.token.value + 1
    store.release(thread, next.token).value shouldBe (())
    store.latest(thread).value shouldBe None
    store.eventsAfter(thread, 0L, 10).value shouldBe empty
  }
}
