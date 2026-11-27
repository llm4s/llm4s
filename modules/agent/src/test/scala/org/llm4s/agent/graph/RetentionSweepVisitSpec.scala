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

  it should "always finish the first thread, then stop between threads once its budget has passed" in {
    val steps = mutable.Buffer.empty[String]
    // the budget is gone from the start: the first thread is still finished, in all its steps
    val visited = RetentionSweep.visit(page, Duration.Zero, "spec", closed = false) { (t, floor) =>
      steps += t.value
      if floor.contains(2L) then Right(Step.Done) else Right(Step.More(Some(floor.fold(1L)(_ + 1))))
    }
    visited.value shouldBe Visited(1, 0, cutShort = true)
    steps.toVector shouldBe Vector("a", "a", "a")
    // the next call, paging after the cursor, starts with b: nothing is skipped
    visited.value.cursor(page, 10) shouldBe Some(ThreadId("a"))
  }

  it should "stop inside a later thread once its budget has passed, its cursor the last thread it finished" in {
    // #3086: the thread it stopped in must come back first in the next call, not be paged past
    val steps = mutable.Buffer.empty[String]
    val visited = RetentionSweep.visit(page, 200.millis, "spec", closed = false) { (t, _) =>
      steps += t.value
      if t.value == "a" then Right(Step.Done)
      else
        Thread.sleep(150)
        Right(Step.More(Some(1L)))
    }
    visited.value shouldBe Visited(1, 0, cutShort = true)
    visited.value.cursor(page, 10) shouldBe Some(ThreadId("a"))
    steps.head shouldBe "a"
    steps.tail.toSet shouldBe Set("b")
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
