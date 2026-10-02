package org.llm4s.agent.graph

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class InMemoryCheckpointerSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val thread = ThreadId("t")
  private val now    = Instant.parse("2026-10-02T12:00:00Z")

  private def checkpoint(id: String, parent: Option[String]) =
    Checkpoint(
      Checkpoint.CurrentFormat,
      id,
      parent,
      thread.value,
      "run",
      CheckpointStatus.Running,
      now,
      GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty)
    )
  private def event(name: String) = EventDraft("run", None, None, None, now, RunEvent.Custom(name, 1, ujson.Null))
  private def write(checkpointId: String, taskId: String) =
    PendingWrite(checkpointId, taskId, "n", Vector.empty, Vector.empty)

  "InMemoryCheckpointer" should "number events contiguously across commits" in {
    val store = InMemoryCheckpointer()
    store.latest(thread).value shouldBe None
    store
      .commit(thread, Commit(Some(checkpoint("c1", None)), Vector.empty, Vector(event("a"), event("b"))))
      .value
      .map(_.seq) shouldBe
      Vector(1L, 2L)
    store.commit(thread, Commit(None, Vector(write("c1", "0.0")), Vector(event("c")))).value.map(_.seq) shouldBe Vector(
      3L
    )
    store.eventsAfter(thread, 0L, 10).value.map(_.seq) shouldBe Vector(1L, 2L, 3L)
    store.eventsAfter(thread, 1L, 1).value.map(_.seq) shouldBe Vector(2L)
    store.latest(thread).value.map(_.pendingWrites.map(_.taskId)) shouldBe Some(Vector("0.0"))
  }

  it should "reject a checkpoint whose parent is not the latest, applying nothing" in {
    val store = InMemoryCheckpointer()
    store.commit(thread, Commit(Some(checkpoint("c1", None)), Vector.empty, Vector(event("a"))))
    store
      .commit(thread, Commit(Some(checkpoint("c2", Some("other"))), Vector.empty, Vector(event("lost"))))
      .left
      .value shouldBe
      GraphError.CheckpointConflict("t", Some("other"), Some("c1"))
    store.commit(thread, Commit(Some(checkpoint("c2", None)), Vector.empty, Vector.empty)).left.value shouldBe
      GraphError.CheckpointConflict("t", None, Some("c1"))
    // the refused commit consumed no sequence number
    store.commit(thread, Commit(None, Vector.empty, Vector(event("b")))).value.map(_.seq) shouldBe Vector(2L)
    store.latest(thread).value.map(_.checkpoint.id) shouldBe Some("c1")
  }

  it should "reject pending writes for a checkpoint other than the latest" in {
    val store = InMemoryCheckpointer()
    store
      .commit(thread, Commit(None, Vector(write("c1", "0.0")), Vector.empty))
      .left
      .value shouldBe a[GraphError.InvalidCommit]
    store.commit(thread, Commit(Some(checkpoint("c1", None)), Vector.empty, Vector.empty))
    store.commit(thread, Commit(None, Vector(write("c0", "0.0")), Vector(event("x")))).left.value shouldBe
      a[GraphError.InvalidCommit]
    store.eventsAfter(thread, 0L, 10).value shouldBe empty
  }

  it should "drop the old checkpoint's pending writes when a new checkpoint lands" in {
    val store = InMemoryCheckpointer()
    store.commit(thread, Commit(Some(checkpoint("c1", None)), Vector(write("c1", "0.0")), Vector.empty))
    store.commit(thread, Commit(Some(checkpoint("c2", Some("c1"))), Vector(write("c2", "1.0")), Vector.empty))
    store.latest(thread).value.map(s => s.checkpoint.id -> s.pendingWrites.map(_.taskId)) shouldBe Some(
      "c2" -> Vector("1.0")
    )
  }

  it should "report the replay floor after compaction and never reuse a sequence number" in {
    val store = InMemoryCheckpointer()
    store.commit(thread, Commit(Some(checkpoint("c1", None)), Vector.empty, (1 to 5).map(i => event(s"e$i")).toVector))
    store.compactEvents(thread, 4L).value shouldBe (())
    store.eventsAfter(thread, 0L, 10).left.value shouldBe GraphError.ReplayUnavailable("t", 4L)
    store.eventsAfter(thread, 2L, 10).left.value shouldBe GraphError.ReplayUnavailable("t", 4L)
    store.eventsAfter(thread, 3L, 10).value.map(_.seq) shouldBe Vector(4L, 5L)
    store.compactEvents(thread, 2L).value shouldBe (()) // a floor never moves back
    store.eventsAfter(thread, 3L, 10).value.map(_.seq) shouldBe Vector(4L, 5L)
    store.compactEvents(thread, 100L).value shouldBe (()) // clamps to the next sequence
    store.eventsAfter(thread, 5L, 10).value shouldBe empty
    store.commit(thread, Commit(None, Vector.empty, Vector(event("e6")))).value.map(_.seq) shouldBe Vector(6L)
  }
}
