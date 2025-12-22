package org.llm4s.llmconnect.utils

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class SimilarityUtilsSpec extends AnyFunSuite with Matchers {

  test("cosine similarity of identical vectors should be 1.0") {
    val vec = Seq(1.0, 2.0, 3.0)
    SimilarityUtils.cosineSimilarity(vec, vec) shouldBe 1.0
  }

  test("cosine similarity of orthogonal vectors should be 0.0") {
    val vec1 = Seq(1.0, 0.0)
    val vec2 = Seq(0.0, 1.0)
    SimilarityUtils.cosineSimilarity(vec1, vec2) shouldBe 0.0
  }

  test("cosine similarity of opposite vectors should be -1.0") {
    val vec1 = Seq(1.0, 2.0, 3.0)
    val vec2 = Seq(-1.0, -2.0, -3.0)
    SimilarityUtils.cosineSimilarity(vec1, vec2) shouldBe -1.0
  }

  test("cosine similarity of similar vectors should be positive") {
    val vec1 = Seq(1.0, 2.0, 3.0)
    val vec2 = Seq(2.0, 4.0, 6.0) // Parallel to vec1
    SimilarityUtils.cosineSimilarity(vec1, vec2) shouldBe 1.0 // Same direction
  }

  test("cosine similarity handles different magnitude vectors") {
    val vec1 = Seq(1.0, 0.0, 0.0)
    val vec2 = Seq(100.0, 0.0, 0.0)
    SimilarityUtils.cosineSimilarity(vec1, vec2) shouldBe 1.0 // Same direction
  }

  test("cosine similarity with unit vectors") {
    val sqrt2 = math.sqrt(2) / 2
    val vec1  = Seq(1.0, 0.0)
    val vec2  = Seq(sqrt2, sqrt2)
    // 45 degree angle, cosine should be ~0.707
    SimilarityUtils.cosineSimilarity(vec1, vec2) shouldBe (sqrt2 +- 0.001)
  }
}
