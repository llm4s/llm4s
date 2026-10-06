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

  private class ScriptedReranker(results: RerankResult*) extends Reranker {
    def rerank(request: RerankRequest): Result[RerankResponse] = Right(RerankResponse(results))
  }

  it should "fail, not return an empty success, when the reranker names only missing candidates" in {
    val r = searcher().searchWithReranking(Array(1f, 0f), "text", reranker = Some(new FixedReranker(7, -1)))
    r.left.value shouldBe a[org.llm4s.error.ProcessingError]
    r.left.value.message should include("no usable result")
  }

  it should "keep an empty reranker response as an empty success" in {
    searcher().searchWithReranking(Array(1f, 0f), "text", reranker = Some(new FixedReranker())).value shouldBe empty
  }

  it should "use a candidate once when the reranker names it twice, keeping the first score" in {
    val reranker = new ScriptedReranker(RerankResult(1, 0.9, ""), RerankResult(1, 0.1, ""), RerankResult(0, 0.5, ""))
    val r        = searcher().searchWithReranking(Array(1f, 0f), "text", reranker = Some(reranker)).value
    r should have size 2
    r.map(_.score) shouldBe Seq(0.9, 0.5)
    r.map(_.id).distinct should have size 2
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

  // #1318 item 5, decided: a `WeightedScore` or `ChunkingConfig` built from literals throws on an invalid value,
  // as every `require`-guarded case class here does. There is no `Result` twin, because `RAGConfig.withWeightedScore`
  // and `withChunking` are chainable builders that return a `RAGConfig` and a `Left` cannot be chained.
  "FusionStrategy.WeightedScore" should "reject weights that would break the ranking" in {
    an[IllegalArgumentException] should be thrownBy FusionStrategy.WeightedScore(-1, 1)
    an[IllegalArgumentException] should be thrownBy FusionStrategy.WeightedScore(0, 0)
    an[IllegalArgumentException] should be thrownBy FusionStrategy.WeightedScore(Double.NaN, 1)
    an[IllegalArgumentException] should be thrownBy FusionStrategy.WeightedScore(Double.PositiveInfinity, 1)
    an[IllegalArgumentException] should be thrownBy FusionStrategy.WeightedScore(1, Double.NegativeInfinity)
    // Each finite, but the sum overflows - and fusion divides by it.
    an[IllegalArgumentException] should be thrownBy FusionStrategy.WeightedScore(Double.MaxValue, Double.MaxValue)
    FusionStrategy.WeightedScore(0.7, 0.3) shouldBe FusionStrategy.WeightedScore(0.7, 0.3)
  }

  "ChunkingConfig" should "reject an overlap that is not smaller than the chunk size" in {
    an[IllegalArgumentException] should be thrownBy ChunkingConfig(targetSize = 100, maxSize = 150, overlap = 100)
    ChunkingConfig(targetSize = 100, maxSize = 150, overlap = 99).overlap shouldBe 99
  }

  "ChunkingUtils.chunkTextValidated" should "return Left instead of throwing" in {
    ChunkingUtils.chunkTextValidated("abc", 0, 0).left.value shouldBe a[ValidationError]
    ChunkingUtils.chunkTextValidated("abc", 3, 3).left.value shouldBe a[ValidationError]
    ChunkingUtils.chunkTextValidated("abcdef", 3, 0).value shouldBe Seq("abc", "def")
  }
}
