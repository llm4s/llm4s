package org.llm4s.rag.transform

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for IdentityTransformer.
 */
class IdentityTransformerSpec extends AnyFlatSpec with Matchers {

  "IdentityTransformer" should "have correct name" in {
    IdentityTransformer().name shouldBe "identity"
  }

  it should "return query unchanged" in {
    val transformer = IdentityTransformer()
    transformer.transform("hello world") shouldBe Right("hello world")
  }

  it should "preserve empty strings" in {
    IdentityTransformer().transform("") shouldBe Right("")
  }

  it should "preserve whitespace" in {
    IdentityTransformer().transform("  spaces  ") shouldBe Right("  spaces  ")
  }

  it should "preserve special characters" in {
    val query = "what is RAGConfig.withPgVector()?"
    IdentityTransformer().transform(query) shouldBe Right(query)
  }

  it should "always succeed" in {
    val transformer = IdentityTransformer()
    (1 to 100).foreach(i => transformer.transform(s"query $i").isRight shouldBe true)
  }

  it should "work in a transform chain" in {
    val transforms = Seq(IdentityTransformer(), IdentityTransformer(), IdentityTransformer())
    val result     = QueryTransformer.applyChain("unchanged", transforms)
    result shouldBe Right("unchanged")
  }
}
