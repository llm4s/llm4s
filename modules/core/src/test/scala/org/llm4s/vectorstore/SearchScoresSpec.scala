package org.llm4s.vectorstore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The one score rule of every vector and keyword store (#3037, #3006). */
class SearchScoresSpec extends AnyFlatSpec with Matchers {

  "SearchScores.fromCosine" should "map [-1, 1] onto [0, 1] as (1 + cos) / 2" in {
    SearchScores.fromCosine(1.0) shouldBe Some(1.0)
    SearchScores.fromCosine(0.0) shouldBe Some(0.5)
    SearchScores.fromCosine(-1.0) shouldBe Some(0.0)
    SearchScores.fromCosine(0.6).get shouldBe 0.8 +- 1e-12
    SearchScores.fromCosine(1.0000001) shouldBe Some(1.0)
  }

  it should "call a non-finite cosine not comparable" in {
    SearchScores.fromCosine(Double.NaN) shouldBe None
    SearchScores.fromCosine(Double.PositiveInfinity) shouldBe None
  }

  "SearchScores.fromCosineDistance" should "score pgvector's distance on the same scale" in {
    SearchScores.fromCosineDistance(0.0) shouldBe Some(1.0)
    SearchScores.fromCosineDistance(1.0) shouldBe Some(0.5)
    SearchScores.fromCosineDistance(2.0) shouldBe Some(0.0)
    SearchScores.fromCosineDistance(Double.NaN) shouldBe None
  }

  "SearchScores.fromBm25" should "score the better (more negative) match higher, below 1.0" in {
    val strong = SearchScores.fromBm25(-0.576)
    val weak   = SearchScores.fromBm25(-0.180)
    strong should be > weak
    strong should be < 1.0
    SearchScores.fromBm25(0.0) shouldBe 0.0
    SearchScores.fromBm25(1.0) shouldBe 0.0
    SearchScores.fromBm25(Double.NaN) shouldBe 0.0
  }

  "SearchScores.cosine" should "compare only vectors of one length that are not zero and finite" in {
    SearchScores.cosine(Array(1f, 0f), Array(2f, 0f)) shouldBe Some(1.0)
    SearchScores.cosine(Array(1f, 0f), Array(0f, 1f)) shouldBe Some(0.0)
    SearchScores.cosine(Array(1f, 0f), Array(1f, 0f, 0f)) shouldBe None
    SearchScores.cosine(Array(0f, 0f), Array(1f, 0f)) shouldBe None
    SearchScores.cosine(Array(Float.NaN, 0f), Array(1f, 0f)) shouldBe None
    SearchScores.cosine(Array.empty[Float], Array.empty[Float]) shouldBe None
    SearchScores.comparable(Array(0f, 0f)) shouldBe false
    SearchScores.comparable(Array(0f, 1f)) shouldBe true
    SearchScores.comparable(Array(Float.PositiveInfinity, 1f)) shouldBe false
  }
}
