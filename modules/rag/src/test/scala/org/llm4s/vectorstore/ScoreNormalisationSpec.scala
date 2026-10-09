package org.llm4s.vectorstore

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/**
 * The invariants of [[ScoreNormalisation]], which [[FusionStrategy.WeightedScore]] relies on (#1318, item 6).
 *
 * Plain min-max mapped a channel's weakest hit to `0`, the score of a chunk the channel did not return at all, so a
 * genuinely relevant chunk that ranked last in one channel contributed nothing from it. The properties below hold for
 * any scores, not only the few the fusion specs use.
 */
class ScoreNormalisationSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 200)

  private val Tolerance = 1e-9

  private val genScore: Gen[Double] = Gen.choose(-1000.0, 1000.0)

  /** Two or more raw scores that differ, so the channel has a weakest and a best hit. */
  private val genSpread: Gen[List[Double]] =
    Gen
      .choose(2, 20)
      .flatMap(n => Gen.listOfN(n, genScore))
      .suchThat(xs => xs.max - xs.min > 1e-3)

  "ScoreNormalisation.Floor" should "sit strictly between a miss (0) and the best hit (1)" in {
    ScoreNormalisation.Floor should be > 0.0
    ScoreNormalisation.Floor should be < 1.0
    // The guide (docs/guide/vector-store.md) states this number: change the guide with it.
    ScoreNormalisation.Floor shouldBe 0.1
  }

  "ScoreNormalisation.over" should "map the weakest hit to the floor and the best hit to 1" in {
    forAll(genSpread) { scores =>
      val normalise = ScoreNormalisation.over(scores)
      normalise(scores.min) shouldBe ScoreNormalisation.Floor +- Tolerance
      normalise(scores.max) shouldBe 1.0 +- Tolerance
    }
  }

  it should "never score a genuine hit like a miss, and never above 1" in {
    forAll(genSpread) { scores =>
      val normalise = ScoreNormalisation.over(scores)
      scores.foreach { s =>
        val n = normalise(s)
        n should be > 0.0
        n should be >= ScoreNormalisation.Floor - Tolerance
        n should be <= 1.0 + Tolerance
      }
    }
  }

  it should "keep the channel's own ordering" in {
    forAll(genSpread) { scores =>
      val normalise = ScoreNormalisation.over(scores)
      val ordered   = scores.sorted.map(normalise)
      ordered.zip(ordered.drop(1)).foreach { case (lower, higher) => lower should be <= higher }
      // Scores that are clearly different stay clearly different: no two distinct hits collapse onto one value.
      val sorted = scores.sorted
      sorted.zip(sorted.drop(1)).foreach { case (lower, higher) =>
        if (higher - lower > 1e-6) normalise(lower) should be < normalise(higher)
      }
    }
  }

  it should "not depend on the scale or the offset of the channel's raw scores" in {
    val genScale  = Gen.choose(0.001, 1000.0)
    val genOffset = Gen.choose(-100.0, 100.0)
    forAll(genSpread, genScale, genOffset) { (scores, scale, offset) =>
      val plain   = ScoreNormalisation.over(scores)
      val shifted = ScoreNormalisation.over(scores.map(s => s * scale + offset))
      scores.foreach(s => shifted(s * scale + offset) shouldBe plain(s) +- 1e-6)
    }
  }

  it should "score every hit 1 when the channel has one hit, or all hits tie" in {
    ScoreNormalisation.over(Seq(0.42))(0.42) shouldBe 1.0
    ScoreNormalisation.over(Seq(3.0, 3.0, 3.0))(3.0) shouldBe 1.0
    forAll(genScore, Gen.choose(2, 10)) { (score, n) =>
      ScoreNormalisation.over(List.fill(n)(score))(score) shouldBe 1.0
    }
  }

  it should "give an empty channel a normaliser that does not fail" in {
    ScoreNormalisation.over(Seq.empty)(0.7) shouldBe 1.0
  }
}
