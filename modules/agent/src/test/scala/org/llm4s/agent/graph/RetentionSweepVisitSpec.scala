package org.llm4s.agent.graph

import org.llm4s.error.{ CancelledError, ValidationError }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.duration.*

/** How a sweep visits a page's threads, step by step (#3086): what a failing step, the budget and a cancel do. */
class RetentionSweepVisitSpec extends AnyFlatSpec with Matchers with EitherValues {

  import RetentionSweep.{ Step, Visited }

  private val page = Vector("a", "b", "c", "d").map(ThreadId(_))

  "RetentionSweep.visit" should "skip a thread whose step fails and finish the rest of the page" in {
    val steps = mutable.Buffer.empty[String]
    val visited = RetentionSweep.visit(page, 1.minute, "spec", closed = false) { (t, _) =>
      steps += t.value
      if t.value == "b" then Left(ValidationError("prune", "refused")) else Right(Step.Done)
    }
    visited.value shouldBe Visited(4, 0, cutShort = false)
    steps.toVector shouldBe Vector("a", "b", "c", "d")
    visited.value.cursor(page :+ ThreadId("e"), 4) shouldBe Some(ThreadId("d"))
  }

  it should "call a thread's step again with the floor it returned until it is done, counting reclaimed tombstones" in {
    val floors = mutable.Buffer.empty[(String, Option[Long])]
    val visited = RetentionSweep.visit(page.take(2), 1.minute, "spec", closed = false) { (t, floor) =>
      floors += (t.value -> floor)
      // a: history in two steps, the second working out the floor, then its events towards it
      if t.value == "b" then Right(Step.Reclaimed)
      else if floors.size == 1 then Right(Step.More(None))
      else if floors.size == 2 then Right(Step.More(Some(7L)))
      else Right(Step.Done)
    }
    visited.value shouldBe Visited(2, 1, cutShort = false)
    floors.toVector shouldBe Vector("a" -> None, "a" -> None, "a" -> Some(7L), "b" -> None)
  }

  it should "stop once its budget has passed, handing back the thread it stopped in as the cursor" in {
    val steps = mutable.Buffer.empty[String]
    // the budget is gone from the start: one step is still made, and the page stops inside that thread
    val inside = RetentionSweep.visit(page, Duration.Zero, "spec", closed = false) { (t, _) =>
      steps += t.value
      Right(Step.More(Some(1L)))
    }
    inside.value shouldBe Visited(1, 0, cutShort = true)
    inside.value.cursor(page, 10) shouldBe Some(ThreadId("a"))
    steps.toVector shouldBe Vector("a")

    // between threads: the next thread is not begun
    val between = RetentionSweep.visit(page, Duration.Zero, "spec", closed = false)((_, _) => Right(Step.Done))
    between.value shouldBe Visited(1, 0, cutShort = true)
    between.value.cursor(page, 10) shouldBe Some(ThreadId("a"))
  }

  it should "end the call on a cancel, or on any failure once the store is closed" in {
    val cancelled = CancelledError("sweep", None)
    RetentionSweep.visit(page, 1.minute, "spec", closed = false)((_, _) => Left(cancelled)).left.value shouldBe
      cancelled
    val failed = ValidationError("prune", "the store is closed")
    RetentionSweep.visit(page, 1.minute, "spec", closed = true)((_, _) => Left(failed)).left.value shouldBe failed
  }

  "RetentionSweep.chunkFloor" should "raise the floor towards the target by at most one chunk, never lowering it" in {
    RetentionSweep.chunkFloor(1L, 5000L, Some(1001L)) shouldBe 1001L
    RetentionSweep.chunkFloor(1L, 500L, Some(1001L)) shouldBe 500L
    RetentionSweep.chunkFloor(1L, 500L, None) shouldBe 500L
    RetentionSweep.chunkFloor(900L, 500L, None) shouldBe 900L
  }
}
