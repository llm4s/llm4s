package org.llm4s.testutil

import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The retrieval assertions in the RAG specs are only as trustworthy as the mock they rely on,
 * so the properties they assume of [[MockEmbeddingProviders.BagOfWordsMock]] are pinned here.
 */
class BagOfWordsMockSpec extends AnyFlatSpec with Matchers {

  import MockEmbeddingProviders.BagOfWordsMock.vectorFor

  private def cosine(a: Seq[Double], b: Seq[Double]): Double =
    a.zip(b).map { case (x, y) => x * y }.sum

  "BagOfWordsMock.vectorFor" should "be deterministic" in {
    vectorFor("functional programming in scala") shouldBe vectorFor("functional programming in scala")
  }

  it should "produce unit-length vectors of the requested dimension" in {
    val v = vectorFor("alpha beta gamma delta", 32)
    v should have size 32
    math.sqrt(v.map(x => x * x).sum) shouldBe 1.0 +- 1e-9
  }

  it should "ignore word order, case and punctuation" in {
    vectorFor("Scala, functional: programming!") shouldBe vectorFor("programming functional scala")
  }

  it should "ignore tokens shorter than three characters" in {
    vectorFor("is a to be") shouldBe Seq.fill(64)(0.0)
    vectorFor("scala is a language") shouldBe vectorFor("scala language")
  }

  it should "return the zero vector for empty text" in {
    vectorFor("") shouldBe Seq.fill(64)(0.0)
  }

  it should "place texts sharing terms closer together than texts sharing none" in {
    val query     = vectorFor("functional programming language", 256)
    val related   = vectorFor("scala is a functional programming language", 256)
    val unrelated = vectorFor("rust garbage collector memory safety", 256)

    cosine(query, related) should be > cosine(query, unrelated)
    cosine(query, vectorFor("functional programming language", 256)) shouldBe 1.0 +- 1e-9
  }

  "BagOfWordsMock provider" should "return one vector per input, in order, and count calls" in {
    val provider = new MockEmbeddingProviders.BagOfWordsMock(16)
    val config   = EmbeddingModelConfig("mock-model", 16)

    val response = provider.embed(EmbeddingRequest(Seq("first text", "second text"), config))

    response.map(_.embeddings) shouldBe Right(Seq(vectorFor("first text", 16), vectorFor("second text", 16)))
    response.map(_.metadata.get("provider")) shouldBe Right(Some("bag-of-words-mock"))
    provider.callCount shouldBe 1
  }
}
