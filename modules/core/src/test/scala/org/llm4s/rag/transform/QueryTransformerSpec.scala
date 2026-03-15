package org.llm4s.rag.transform

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for QueryTransformer trait and chain composition.
 */
class QueryTransformerSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // Test Helpers
  // ==========================================================================

  /** Transformer that uppercases the query */
  private class UpperCaseTransformer extends QueryTransformer {
    override val name: String                             = "upper-case"
    override def transform(query: String): Result[String] = Right(query.toUpperCase)
  }

  /** Transformer that appends a suffix */
  private class SuffixTransformer(suffix: String) extends QueryTransformer {
    override val name: String                             = "suffix"
    override def transform(query: String): Result[String] = Right(s"$query $suffix")
  }

  /** Transformer that always fails */
  private class FailingTransformer extends QueryTransformer {
    override val name: String = "failing"
    override def transform(query: String): Result[String] =
      Left(ProcessingError("query-transform", "Transform failed"))
  }

  // ==========================================================================
  // applyChain Tests
  // ==========================================================================

  "QueryTransformer.applyChain" should "return original query when no transforms" in {
    val result = QueryTransformer.applyChain("hello world", Seq.empty)
    result shouldBe Right("hello world")
  }

  it should "apply a single transform" in {
    val result = QueryTransformer.applyChain("hello", Seq(new UpperCaseTransformer))
    result shouldBe Right("HELLO")
  }

  it should "apply transforms sequentially" in {
    val transforms = Seq(
      new SuffixTransformer("search"),
      new UpperCaseTransformer
    )
    val result = QueryTransformer.applyChain("hello", transforms)
    result shouldBe Right("HELLO SEARCH")
  }

  it should "short-circuit on first error" in {
    val transforms = Seq(
      new UpperCaseTransformer,
      new FailingTransformer,
      new SuffixTransformer("should-not-reach")
    )
    val result = QueryTransformer.applyChain("hello", transforms)
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Transform failed")
  }

  it should "chain three transforms correctly" in {
    val transforms = Seq(
      new SuffixTransformer("one"),
      new SuffixTransformer("two"),
      new SuffixTransformer("three")
    )
    val result = QueryTransformer.applyChain("start", transforms)
    result shouldBe Right("start one two three")
  }

  it should "handle empty query string" in {
    val result = QueryTransformer.applyChain("", Seq(new UpperCaseTransformer))
    result shouldBe Right("")
  }
}
