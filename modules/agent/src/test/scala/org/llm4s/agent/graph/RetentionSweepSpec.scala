package org.llm4s.agent.graph

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.*

class RetentionSweepSpec extends AnyFlatSpec with Matchers with EitherValues {

  "SweepPolicy" should "change nothing by default and refuse limits outside its bounds" in {
    SweepPolicy() shouldBe SweepPolicy.keepAll
    SweepPolicy.keepAll.retention shouldBe RetentionPolicy.keepAll
    SweepPolicy.keepAll.tombstoneAge shouldBe None
    SweepPolicy.keepAll.batchSize shouldBe SweepPolicy.DefaultBatchSize
    SweepPolicy.of(batchSize = 0).isLeft shouldBe true
    SweepPolicy.of(batchSize = SweepPolicy.MaxBatchSize + 1).isLeft shouldBe true
    SweepPolicy.of(tombstoneAge = Some(0.seconds)).isLeft shouldBe true
    SweepPolicy.of(tombstoneAge = Some(1.day), batchSize = SweepPolicy.MaxBatchSize).value.batchSize shouldBe 1000
    an[IllegalArgumentException] should be thrownBy SweepPolicy.keepAll.withBatchSize(0)
    SweepPolicy.keepAll.withTombstoneAge(1.day).withTombstoneAge(None).tombstoneAge shouldBe None
    SweepPolicy.keepAll.withRetention(RetentionPolicy(maxCheckpoints = Some(2))).retention.maxCheckpoints shouldBe
      Some(2)
  }

  "SweepGuard.of" should "answer only for the threads it is asked about" in {
    val guard = SweepGuard.of(Map(ThreadId("a") -> Set(RunId("r")), ThreadId("b") -> Set.empty))
    guard.retained(Vector(ThreadId("a"), ThreadId("c"))).value shouldBe Map(ThreadId("a") -> Set(RunId("r")))
    SweepGuard.none.retained(Vector(ThreadId("a"))).value shouldBe empty
  }

  "RetentionSweep.kept" should "keep what the policy keeps, then the newest checkpoint of each protected run" in {
    val at      = Instant.parse("2026-10-21T00:00:00Z")
    val history = Vector("a1" -> "a", "a2" -> "a", "b1" -> "b", "c1" -> "c")
    val (byPolicy, evidence) =
      RetentionSweep.kept(history, RetentionPolicy(maxCheckpoints = Some(1)), None, _ => at, _._2, Set(RunId("a")))
    byPolicy.map(_._1) shouldBe Vector("c1")
    evidence.map(_._1) shouldBe Vector("a2")
    // a protected run whose newest checkpoint the policy keeps adds nothing
    RetentionSweep.kept(history, RetentionPolicy.keepAll, None, _ => at, _._2, Set(RunId("c")))._2 shouldBe empty
    RetentionSweep.kept(
      Vector.empty[(String, String)],
      RetentionPolicy.keepAll,
      None,
      _ => at,
      _._2,
      Set.empty
    ) shouldBe
      ((Vector.empty, Vector.empty))
  }

  "RetentionSweep.next" should "name the page's last thread only when another follows" in {
    val ids = Vector("a", "b", "c").map(ThreadId(_))
    RetentionSweep.next(ids, 2) shouldBe Some(ThreadId("b"))
    RetentionSweep.next(ids, 3) shouldBe None
  }
}
