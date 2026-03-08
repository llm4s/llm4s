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

    // Expectation: Only "text2" is sent to the base client in a single batched call
    val expectedMissReq = batchRequest.copy(input = Seq("text2"))
    (baseClient.embed _)
      .expects(expectedMissReq)
      .returning(Right(EmbeddingResponse(Seq(Seq(2.0)))))
      .once()

    val result = cachedClient.embed(batchRequest)

    result.map(_.embeddings) shouldBe Right(Seq(Seq(1.0), Seq(2.0)))
  }

  it should "not cache base client errors, allowing the next call to retry" in {
    val baseClient   = mock[EmbeddingClient]
    val cache        = new InMemoryEmbeddingCache[Seq[Double]]()
    val cachedClient = new CachedEmbeddingClient(baseClient, cache)

    val request   = EmbeddingRequest(Seq("hello"), testModel)
    val testError = EmbeddingError(code = Some("500"), message = "server error", provider = "test")

    // Base client fails on first call then succeeds on second.
    // If errors were cached the second call would never reach the base client.
    (baseClient.embed _).expects(request).returning(Left(testError)).once()
    (baseClient.embed _)
      .expects(request)
      .returning(Right(EmbeddingResponse(Seq(Seq(0.1, 0.2)))))
      .once()

    cachedClient.embed(request) shouldBe Left(testError)
    cachedClient.embed(request).isRight shouldBe true
  }

  it should "deduplicate identical texts within the same batch before calling the base client" in {
    val baseClient   = mock[EmbeddingClient]
    val cache        = new InMemoryEmbeddingCache[Seq[Double]]()
    val cachedClient = new CachedEmbeddingClient(baseClient, cache)

    // Request with duplicated inputs
    val request = EmbeddingRequest(Seq("duplicate text", "unique text", "duplicate text"), testModel)

    // Expectation: Base client should only receive the deduplicated texts
    val expectedUniqueReq  = request.copy(input = Seq("duplicate text", "unique text"))
    val mockUniqueResponse = EmbeddingResponse(Seq(Seq(1.1, 1.1), Seq(2.2, 2.2)))

    (baseClient.embed _)
      .expects(expectedUniqueReq)
      .returning(Right(mockUniqueResponse))
      .once()

    val result = cachedClient.embed(request)

    // The final result should contain 3 vectors, reconstructing the duplicates
    // in the exact order they were requested.
    val expectedFinalVectors = Seq(Seq(1.1, 1.1), Seq(2.2, 2.2), Seq(1.1, 1.1))

    result.map(_.embeddings) shouldBe Right(expectedFinalVectors)
  }
}
