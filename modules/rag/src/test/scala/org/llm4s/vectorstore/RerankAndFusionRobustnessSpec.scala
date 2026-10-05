package org.llm4s.vectorstore

import org.llm4s.chunking.ChunkingConfig
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.utils.ChunkingUtils
import org.llm4s.reranker.{ RerankRequest, RerankResponse, RerankResult, Reranker }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** #1318 items 2-6: LIKE-escaped prefixes, a misbehaving reranker, and inputs that threw instead of returning `Left`. */
class RerankAndFusionRobustnessSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def vec(id: String) = VectorRecord(id, Array(1.0f, 0.0f), Some(s"text $id"))

  "SQLiteVectorStore.deleteByPrefix" should "match _ and % literally" in {
    val store = VectorStoreFactory.inMemory().value
    Seq("a_b-chunk-0", "axb-chunk-0", "a%-chunk-0", "abc-chunk-0", "a\\b-chunk-0", "aXb-chunk-0")
      .foreach(id => store.upsert(vec(id)).value)

    store.deleteByPrefix("a_b-chunk-").value shouldBe 1L
    store.deleteByPrefix("a%-chunk-").value shouldBe 1L
    store.deleteByPrefix("a\\b-chunk-").value shouldBe 1L
    store.get("axb-chunk-0").value shouldBe defined
    store.get("abc-chunk-0").value shouldBe defined
    store.get("aXb-chunk-0").value shouldBe defined
    store.count().value shouldBe 3L
  }

  "SQLiteKeywordIndex.deleteByPrefix" should "match _ and % literally" in {
    val index = KeywordIndex.inMemory().value
    Seq("a_b-chunk-0", "axb-chunk-0", "a%-chunk-0", "abc-chunk-0")
      .foreach(id => index.index(KeywordDocument(id, s"text $id")).value)

    index.deleteByPrefix("a_b-chunk-").value shouldBe 1L
    index.deleteByPrefix("a%-chunk-").value shouldBe 1L
    index.count().value shouldBe 2L
  }

  private def searcher(): HybridSearcher = {
    val vs = VectorStoreFactory.inMemory().value
    val ki = KeywordIndex.inMemory().value
    Seq("one", "two").foreach { id =>
      vs.upsert(vec(id)).value
      ki.index(KeywordDocument(id, s"text $id")).value
    }
    HybridSearcher(vs, ki)
  }

  private class FixedReranker(indices: Int*) extends Reranker {
    def rerank(request: RerankRequest): Result[RerankResponse] =
      Right(RerankResponse(indices.map(i => RerankResult(i, 0.5, ""))))
  }

  "HybridSearcher.searchWithReranking" should "drop a result naming a missing candidate instead of throwing" in {
    val r = searcher().searchWithReranking(Array(1f, 0f), "text", reranker = Some(new FixedReranker(1, 7, -1, 0))).value
    r should have size 2
  }

  it should "still map valid indices" in {
    val r = searcher().searchWithReranking(Array(1f, 0f), "text", reranker = Some(new FixedReranker(1, 0))).value
    r.map(_.score) shouldBe Seq(0.5, 0.5)
  }

  "WeightedScore fusion" should "not score the weakest genuine hit like a miss" in {
    val vs = VectorStoreFactory.inMemory().value
    val ki = KeywordIndex.inMemory().value
    // Two vector hits of different strength; no keyword channel overlap needed.
    vs.upsert(VectorRecord("strong", Array(1f, 0f), Some("strong"))).value
    vs.upsert(VectorRecord("weak", Array(0.6f, 0.8f), Some("weak"))).value
    val hs = HybridSearcher(vs, ki)
    val r = hs
      .search(Array(1f, 0f), "nomatchatall", topK = 5, strategy = FusionStrategy.WeightedScore(1.0, 0.0))
      .value
    r.map(_.id) shouldBe Seq("strong", "weak")
    r.last.score should be > 0.0
    r.head.score shouldBe 1.0 +- 1e-9
  }

  "FusionStrategy.weightedScore" should "return Left for bad weights and Right for good ones" in {
    FusionStrategy.weightedScore(-1, 1).left.value shouldBe a[ValidationError]
    FusionStrategy.weightedScore(0, 0).isLeft shouldBe true
    FusionStrategy.weightedScore(Double.NaN, 1).isLeft shouldBe true
    FusionStrategy.weightedScore(Double.PositiveInfinity, 1).isLeft shouldBe true
    FusionStrategy.weightedScore(1, Double.NegativeInfinity).isLeft shouldBe true
    an[IllegalArgumentException] should be thrownBy FusionStrategy.WeightedScore(Double.PositiveInfinity, 1)
    FusionStrategy.weightedScore(0.7, 0.3).value shouldBe FusionStrategy.WeightedScore(0.7, 0.3)
  }

  "ChunkingConfig.validated" should "list every violation as a Left" in {
    val e = ChunkingConfig.validated(targetSize = 0, maxSize = -1, overlap = 5, minChunkSize = -2).left.value
    e shouldBe a[ValidationError]
    e.asInstanceOf[ValidationError].violations should have size 4
  }

  it should "accept a valid configuration" in {
    ChunkingConfig.validated(targetSize = 100, maxSize = 150, overlap = 10, minChunkSize = 5).value shouldBe
      ChunkingConfig(100, 150, 10, 5)
  }

  "ChunkingUtils.chunkTextValidated" should "return Left instead of throwing" in {
    ChunkingUtils.chunkTextValidated("abc", 0, 0).isLeft shouldBe true
    ChunkingUtils.chunkTextValidated("abc", 3, 3).isLeft shouldBe true
    ChunkingUtils.chunkTextValidated("abcdef", 3, 0).value shouldBe Seq("abc", "def")
  }
}
