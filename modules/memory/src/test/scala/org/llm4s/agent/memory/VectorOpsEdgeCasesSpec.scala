package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VectorOpsEdgeCasesSpec extends AnyFlatSpec with Matchers {

  private val tol = 1e-6
  private val a2  = Array(1f, 2f)
  private val a3  = Array(1f, 2f, 3f)

  "VectorOps.dotProduct" should "compute 1*4 + 2*5 + 3*6 = 32" in {
    VectorOps.dotProduct(Array(1f, 2f, 3f), Array(4f, 5f, 6f)) shouldBe 32.0 +- tol
  }

  it should "return 0.0 for empty arrays" in {
    VectorOps.dotProduct(Array.empty[Float], Array.empty[Float]) shouldBe 0.0 +- tol
  }

  it should "return a negative result for opposite vectors" in {
    VectorOps.dotProduct(Array(1f, 0f), Array(-1f, 0f)) shouldBe -1.0 +- tol
  }

  it should "throw when sizes differ" in {
    val ex = intercept[IllegalArgumentException](VectorOps.dotProduct(a2, a3))
    ex.getMessage should include("Vector dimensions must match: 2 != 3")
  }

  "VectorOps.cosineSimilarity" should "throw when sizes differ" in {
    val ex = intercept[IllegalArgumentException](VectorOps.cosineSimilarity(a2, a3))
    ex.getMessage should include("Vector dimensions must match: 2 != 3")
  }

  it should "return 0.0 for empty vectors" in {
    VectorOps.cosineSimilarity(Array.empty[Float], Array.empty[Float]) shouldBe 0.0 +- tol
  }

  it should "return 0.0 when one vector is all zeros" in {
    VectorOps.cosineSimilarity(Array(0f, 0f), Array(1f, 1f)) shouldBe 0.0 +- tol
  }

  "VectorOps.euclideanDistance" should "throw when sizes differ" in {
    val ex = intercept[IllegalArgumentException](VectorOps.euclideanDistance(a2, a3))
    ex.getMessage should include("Vector dimensions must match: 2 != 3")
  }

  it should "return 0.0 for empty vectors" in {
    VectorOps.euclideanDistance(Array.empty[Float], Array.empty[Float]) shouldBe 0.0 +- tol
  }

  "VectorOps.normalize" should "keep a zero vector as zeros (no NaN)" in {
    val r = VectorOps.normalize(Array(0f, 0f))
    r.toSeq shouldBe Seq(0f, 0f)
  }

  it should "turn (3, 4) into (0.6, 0.8)" in {
    val r = VectorOps.normalize(Array(3f, 4f))
    r(0).toDouble shouldBe 0.6 +- tol
    r(1).toDouble shouldBe 0.8 +- tol
  }
  "VectorOps.topKBySimilarity" should "return every candidate, best first, when k is larger than the candidates" in {
    val query = Array(1f, 0f)
    val candidates = Seq(
      (Array(0f, 1f), "far"),
      (Array(1f, 0f), "best"),
      (Array(1f, 1f), "middle")
    )

    val result = VectorOps.topKBySimilarity(query, candidates, 10)

    result.map(_._1) shouldBe Seq("best", "middle", "far")
    result.map(_._2).zip(Seq(1.0, 0.7071067811865475, 0.0)).foreach { case (actual, expected) =>
      actual shouldBe expected +- tol
    }
  }

  it should "return an empty Seq when k is 0" in {
    val query      = Array(1f, 0f)
    val candidates = Seq((Array(1f, 0f), "a"), (Array(0f, 1f), "b"))

    VectorOps.topKBySimilarity(query, candidates, 0) shouldBe empty
  }
}
