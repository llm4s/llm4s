package org.llm4s.samples.rag

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HybridSearchExampleSpec extends AnyFlatSpec with Matchers {

  private lazy val runs = HybridSearchExample.run().fold(e => fail(e.message), identity).toMap

  "HybridSearchExample" should "rank the nearest vector first with VectorOnly" in {
    runs("VectorOnly").head.id shouldBe "restore-guide"
    runs("VectorOnly").head.keywordScore shouldBe None
  }

  it should "return the document that contains the query word with KeywordOnly" in {
    runs("KeywordOnly").map(_.id) shouldBe Seq("backup-cron")
    runs("KeywordOnly").head.vectorScore shouldBe None
  }

  it should "put a document both lists agree on first with RRF and an even WeightedScore" in {
    val agreed = runs("RRF(60)").filter(r => r.vectorScore.isDefined && r.keywordScore.isDefined).map(_.id)

    agreed should not be empty
    agreed.foreach(id => runs("VectorOnly").map(_.id) should contain(id))
    agreed.foreach(id => runs("KeywordOnly").map(_.id) should contain(id))
    agreed should contain(runs("RRF(60)").head.id)
    runs("WeightedScore(.5/.5)").head.id shouldBe runs("RRF(60)").head.id
  }

  it should "let a heavy vector weight bring the nearest vector back to the top" in {
    runs("WeightedScore(.9/.1)").head.id shouldBe runs("VectorOnly").head.id
  }
}
