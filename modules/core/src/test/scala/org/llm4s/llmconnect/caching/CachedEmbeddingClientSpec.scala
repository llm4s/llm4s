package org.llm4s.llmconnect.caching

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CachedEmbeddingClientSpec extends AnyFlatSpec with Matchers {

  // Mock implementations
  case class MockRequest(customParam: String = "")
  case class MockResponse(text: String, embedding: Vector[Float])

  /**
   * * Mock client implementing the standard llmconnect contract.
   * Resolves architectural drift by using core library types.
   */
  class MockEmbeddingClient extends EmbeddingClient[MockRequest, MockResponse] {
    var callCount = 0

    def embed(
      text: String,
      model: String,
      request: Option[MockRequest] = None
    ): Either[EmbeddingError, MockResponse] = {
      callCount += 1
      Right(MockResponse(text, Vector(0.1f, 0.2f, 0.3f)))
    }
  }

  class FailingEmbeddingClient extends EmbeddingClient[MockRequest, MockResponse] {
    var callCount = 0

    def embed(
      text: String,
      model: String,
      request: Option[MockRequest] = None
    ): Either[EmbeddingError, MockResponse] = {
      callCount += 1
      Left(EmbeddingError("API error"))
    }
  }

  def createCachedClient(
    baseClient: EmbeddingClient[MockRequest, MockResponse]
  ): CachedEmbeddingClient[MockRequest, MockResponse, Vector[Float]] = {

    val cache = new InMemoryEmbeddingCache[Vector[Float]]()

    new CachedEmbeddingClient(
      baseClient,
      cache,
      // Explicitly using secure hashing for privacy boundaries
      keyGenerator = CacheKeyGenerator.sha256,
      embeddingExtractor = (response: MockResponse) => Some(response.embedding)
    )
  }

  "CachedEmbeddingClient" should "return cached embedding on cache hit" in {
    val baseClient   = new MockEmbeddingClient()
    val cachedClient = createCachedClient(baseClient)

    val result1 = cachedClient.embed("hello", "model-v1")
    baseClient.callCount should be(1)

    val result2 = cachedClient.embed("hello", "model-v1")
    baseClient.callCount should be(1)

    result1 should be(result2)
  }

  it should "call base client on cache miss" in {
    val baseClient   = new MockEmbeddingClient()
    val cachedClient = createCachedClient(baseClient)

    cachedClient.embed("text1", "model-v1")
    cachedClient.embed("text2", "model-v1")

    baseClient.callCount should be(2)
  }

  it should "not cache failed responses" in {
    val baseClient   = new FailingEmbeddingClient()
    val cachedClient = createCachedClient(baseClient)

    val result1 = cachedClient.embed("hello", "model-v1")
    baseClient.callCount should be(1)
    result1.isLeft should be(true)

    val result2 = cachedClient.embed("hello", "model-v1")
    baseClient.callCount should be(2)
    result2.isLeft should be(true)
  }

  it should "embed batch with caching using monadic transformation" in {
    val baseClient   = new MockEmbeddingClient()
    val cachedClient = createCachedClient(baseClient)

    val texts   = Seq("text1", "text2", "text3")
    val result1 = cachedClient.embedBatch(texts, "model-v1")

    baseClient.callCount should be(3)

    val result2 = cachedClient.embedBatch(texts, "model-v1")
    baseClient.callCount should be(3) // Verified via cache

    result1 should be(result2)
  }

  it should "fail-fast and return error from batch if any request fails" in {
    val baseClient   = new FailingEmbeddingClient()
    val cachedClient = createCachedClient(baseClient)

    val texts  = Seq("text1", "text2", "text3")
    val result = cachedClient.embedBatch(texts, "model-v1")

    result.isLeft should be(true)
    // In monadic foldLeft, it stops at the first error
    baseClient.callCount should be(1)
  }

  it should "track cache statistics correctly" in {
    val baseClient   = new MockEmbeddingClient()
    val cachedClient = createCachedClient(baseClient)

    cachedClient.embed("text1", "model-v1")
    cachedClient.embed("text1", "model-v1") // hit
    cachedClient.embed("text2", "model-v1") // miss

    val stats = cachedClient.getCacheStats()
    stats("hits").asInstanceOf[Long] should be(1L)
    stats("misses").asInstanceOf[Long] should be(2L)
  }

  it should "clear cache and reset state" in {
    val baseClient   = new MockEmbeddingClient()
    val cachedClient = createCachedClient(baseClient)

    cachedClient.embed("text1", "model-v1")
    baseClient.callCount should be(1)

    cachedClient.clearCache()

    cachedClient.embed("text1", "model-v1")
    baseClient.callCount should be(2)
  }
  it should "return an error if the embedding extractor returns None" in {
    val baseClient = new MockEmbeddingClient()
    val cachedClient = new CachedEmbeddingClient(
      baseClient,
      new InMemoryEmbeddingCache[Vector[Double]](),
      keyGenerator = CacheKeyGenerator.sha256,
      embeddingExtractor = (_: MockResponse) => None
    )

    val result = cachedClient.embed("test prompt", "gpt-4o")

    // Using pattern matching to avoid deprecation warnings
    result match {
      case Left(error) => error.message should include("Failed to extract embedding")
      case Right(_)    => fail("Should have returned a Left error")
    }
  }

  it should "propagate errors from the base client without attempting to cache" in {
    val baseClient = new FailingEmbeddingClient()
    val cache      = new InMemoryEmbeddingCache[Vector[Double]]()
    val cachedClient = new CachedEmbeddingClient(
      baseClient,
      cache,
      keyGenerator = CacheKeyGenerator.sha256,
      embeddingExtractor = (_: MockResponse) => Some(Vector(1.0))
    )

    val result = cachedClient.embed("test prompt", "gpt-4o")

    result.isLeft should be(true)
    cache.stats()("size") should be(0)
  }
}
