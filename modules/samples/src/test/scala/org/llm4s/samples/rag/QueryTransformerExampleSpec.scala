package org.llm4s.samples.rag

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class QueryTransformerExampleSpec extends AnyFunSuite with Matchers {
  test("query transformers improve the offline RAG ranking and propagate errors") {
    val result   = QueryTransformerExample.run().fold(error => fail(error.formatted), identity)
    val rankings = result.rankings.map(ranking => ranking.label -> ranking).toMap

    rankings("no transformer").topHit.id shouldBe "alert-glossary-chunk-0"
    rankings("abbreviation expander").topHit.id shouldBe "kubernetes-chunk-0"
    rankings("scripted LLM rewriter").topHit.id shouldBe "kubernetes-chunk-0"
    result.chained.topHit.id shouldBe "kubernetes-chunk-0"
    result.chained.transformedQuery should include("memory limit terminated")
    result.propagatedError should include("intentional sample failure")
  }
}
