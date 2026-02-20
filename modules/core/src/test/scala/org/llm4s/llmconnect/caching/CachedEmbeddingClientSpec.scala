package org.llm4s.llmconnect.caching

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory

class CachedEmbeddingClientSpec extends AnyFlatSpec with Matchers with MockFactory {

  val testModel = EmbeddingModelConfig("test-model", 1536)

  "CachedEmbeddingClient" should "only call the base client for cache misses" in {
    val baseClient   = mock[EmbeddingClient]
    val cache        = new InMemoryEmbeddingCache[Seq[Double]]()
    val cachedClient = new CachedEmbeddingClient(baseClient, cache)

    val request      = EmbeddingRequest(Seq("hello"), testModel)
    val mockVector   = Seq(0.1, 0.2, 0.3)
    val mockResponse = EmbeddingResponse(Seq(mockVector))

    // Expectation: Base client is called exactly once
    (baseClient.embed _).expects(request).returning(Right(mockResponse)).once()

    // First call (Miss)
    cachedClient.embed(request)

    // Second call (Hit)
    val result = cachedClient.embed(request)

    result.map(_.embeddings.head) shouldBe Right(mockVector)
  }

  it should "process batch requests by hitting cache for existing strings" in {
    val baseClient   = mock[EmbeddingClient]
    val cache        = new InMemoryEmbeddingCache[Seq[Double]]()
    val cachedClient = new CachedEmbeddingClient(baseClient, cache)

    // Pre-seed the cache for "text1"
    cache.put(CacheKeyGenerator.sha256("text1", testModel.name), Seq(1.0))

    val batchRequest = EmbeddingRequest(Seq("text1", "text2"), testModel)

    // Expectation: Only "text2" is requested from the base client
    val expectedSingleReq = batchRequest.copy(input = Seq("text2"))
    (baseClient.embed _)
      .expects(expectedSingleReq)
      .returning(Right(EmbeddingResponse(Seq(Seq(2.0)))))
      .once()

    val result = cachedClient.embed(batchRequest)

    result.map(_.embeddings) shouldBe Right(Seq(Seq(1.0), Seq(2.0)))
  }
}
