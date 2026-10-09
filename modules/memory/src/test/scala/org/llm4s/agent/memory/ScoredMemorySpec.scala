package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ScoredMemorySpec extends AnyFlatSpec with Matchers {

  // Memory.userFact gives each memory a fresh id, so memories can be told apart by id
  private def memory(content: String): Memory = Memory.userFact(content)

  private def ids(scored: Seq[ScoredMemory]): Seq[MemoryId] = scored.map(_.memory.id)

  // Defined before any test is registered, so the class is fully initialised when the tests run
  private val low  = ScoredMemory(memory("low"), 0.2)
  private val mid  = ScoredMemory(memory("mid"), 0.5)
  private val high = ScoredMemory(memory("high"), 0.9)

  // ---------------------------------------------------------------------------
  // Score validation: the range is closed, [0.0, 1.0]
  // ---------------------------------------------------------------------------

  "ScoredMemory" should "accept every score in the closed range [0.0, 1.0], boundaries included" in {
    val accepted = Seq(0.0, 0.5, 1.0, Double.MinPositiveValue, Math.nextDown(1.0))

    accepted.foreach { score =>
      withClue(s"score $score: ") {
        ScoredMemory(memory("likes Scala"), score).score shouldBe score
      }
    }
  }

  it should "reject scores outside the range, NaN and the infinities, naming the range" in {
    val rejected = Seq(
      "-0.1"              -> -0.1,
      "just below 0.0"    -> -Double.MinPositiveValue,
      "1.0000001"         -> 1.0000001,
      "just above 1.0"    -> Math.nextUp(1.0),
      "Double.MaxValue"   -> Double.MaxValue,
      "NaN"               -> Double.NaN,
      "positive infinity" -> Double.PositiveInfinity,
      "negative infinity" -> Double.NegativeInfinity
    )

    rejected.foreach { case (label, score) =>
      withClue(s"score $label: ") {
        val thrown = the[IllegalArgumentException] thrownBy ScoredMemory(memory("likes Scala"), score)
        // The range is part of the message, so a caller can see what was expected
        thrown.getMessage should include("between 0.0 and 1.0")
      }
    }
  }

  // ---------------------------------------------------------------------------
  // perfect
  // ---------------------------------------------------------------------------

  "ScoredMemory.perfect" should "score 1.0 and hold the very memory it was given" in {
    val m      = memory("prefers dark mode")
    val scored = ScoredMemory.perfect(m)

    scored.score shouldBe 1.0
    (scored.memory should be).theSameInstanceAs(m)
  }

  // ---------------------------------------------------------------------------
  // Equality (case class): the memory and the score both count
  // ---------------------------------------------------------------------------

  "A ScoredMemory" should "equal another with the same memory and score" in {
    val m = memory("likes Scala")
    ScoredMemory(m, 0.4) shouldBe ScoredMemory(m, 0.4)
  }

  it should "differ from one with another score or another memory" in {
    val m = memory("likes Scala")
    ScoredMemory(m, 0.4) should not be ScoredMemory(m, 0.5)
    ScoredMemory(m, 0.4) should not be ScoredMemory(memory("likes Scala"), 0.4)
  }

  // ---------------------------------------------------------------------------
  // byScoreDescending: found through the companion, highest score first
  // ---------------------------------------------------------------------------

  "ScoredMemory.byScoreDescending" should "be found without an import, so sorted puts the highest score first" in {
    ids(Seq(low, high, mid).sorted) shouldBe ids(Seq(high, mid, low))
  }

  it should "put the highest score first whatever order the input arrives in" in {
    val expected = ids(Seq(high, mid, low))
    Seq(low, mid, high).permutations.foreach(input => ids(input.sorted) shouldBe expected)
  }

  it should "sort the extremes 1.0 and 0.0 to the two ends" in {
    val best  = ScoredMemory.perfect(memory("best"))
    val worst = ScoredMemory(memory("worst"), 0.0)
    ids(Seq(worst, mid, best).sorted) shouldBe ids(Seq(best, mid, worst))
  }

  it should "keep the original relative order of equal scores (sorted is stable)" in {
    val a = ScoredMemory(memory("a"), 0.5)
    val b = ScoredMemory(memory("b"), 0.5)
    val c = ScoredMemory(memory("c"), 0.5)

    ids(Seq(a, b, c).sorted) shouldBe ids(Seq(a, b, c))
    ids(Seq(c, a, b).sorted) shouldBe ids(Seq(c, a, b))
  }

  it should "keep ties in their original order while still ranking them against other scores" in {
    val tiedFirst  = ScoredMemory(memory("tied first"), 0.5)
    val tiedSecond = ScoredMemory(memory("tied second"), 0.5)

    ids(Seq(low, tiedFirst, high, tiedSecond).sorted) shouldBe ids(Seq(high, tiedFirst, tiedSecond, low))
  }

  it should "compare a higher score as smaller, and equal scores as equal" in {
    val ordering = ScoredMemory.byScoreDescending

    ordering.compare(high, low) should be < 0
    ordering.compare(low, high) should be > 0
    ordering.compare(mid, ScoredMemory(memory("another mid"), 0.5)) shouldBe 0
  }

  // With a descending ordering "smallest" means "best match": min is the highest score and max the
  // lowest. Callers who want the best match should sort and take the head instead.
  it should "make min the highest score and max the lowest" in {
    val all = Seq(mid, low, high)

    all.min.memory.id shouldBe high.memory.id
    all.max.memory.id shouldBe low.memory.id
  }

  it should "sort an empty sequence to an empty sequence" in {
    Seq.empty[ScoredMemory].sorted shouldBe empty
  }
}
