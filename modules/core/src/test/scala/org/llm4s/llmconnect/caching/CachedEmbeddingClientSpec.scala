package org.llm4s.llmconnect.caching

import org.llm4s.llmconnect.model._
import org.llm4s.error.EmbeddingError
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.types.Result

class CachedEmbeddingClientSpec extends AnyFlatSpec with Matchers with MockFactory with EitherValues {

  val testModel = EmbeddingModelConfig("test-model", 1536)

  "CachedEmbeddingClient" should "only call the base client for cache misses" in {
    val baseClient   = mock[EmbeddingProvider]
    val cache        = InMemoryEmbeddingCache.create().value
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
    val baseClient   = mock[EmbeddingProvider]
    val cache        = InMemoryEmbeddingCache.create().value
    val cachedClient = new CachedEmbeddingClient(baseClient, cache)

    // Pre-seed the cache for "text1"
    cache.put(CacheKeyGenerator.embeddingKey("text1", testModel.name, InputPurpose.Document), Seq(1.0))

    val batchRequest = EmbeddingRequest(Seq("text1", "text2"), testModel)

    // Expectation: Only "text2" is sent to the base client in a single batched call
    val expectedMissReq = batchRequest.withInput(Seq("text2"))
    (baseClient.embed _)
      .expects(expectedMissReq)
      .returning(Right(EmbeddingResponse(Seq(Seq(2.0)))))
      .once()

    val result = cachedClient.embed(batchRequest)

    result.map(_.embeddings) shouldBe Right(Seq(Seq(1.0), Seq(2.0)))
  }

  it should "not cache base client errors, allowing the next call to retry" in {
    val baseClient   = mock[EmbeddingProvider]
    val cache        = InMemoryEmbeddingCache.create().value
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

  "EmbeddingError" should "unapply to its code, message and provider" in {
    val error = EmbeddingError(code = Some("500"), message = "server error", provider = "test")
    error match {
      case EmbeddingError(code, message, provider) =>
        code shouldBe Some("500")
        message shouldBe "server error"
        provider shouldBe "test"
      case _ => fail("Pattern matching failed")
    }
  }

  it should "deduplicate identical texts within the same batch before calling the base client" in {
    val baseClient   = mock[EmbeddingProvider]
    val cache        = InMemoryEmbeddingCache.create().value
    val cachedClient = new CachedEmbeddingClient(baseClient, cache)

    // Request with duplicated inputs
    val request = EmbeddingRequest(Seq("duplicate text", "unique text", "duplicate text"), testModel)

    // Expectation: Base client should only receive the deduplicated texts
    val expectedUniqueReq  = request.withInput(Seq("duplicate text", "unique text"))
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

  it should "be an EmbeddingProvider, so an EmbeddingClient can wrap it" in {
    val base                          = mock[EmbeddingProvider]
    val cached                        = new CachedEmbeddingClient(base, InMemoryEmbeddingCache.create().value)
    val asProvider: EmbeddingProvider = cached
    asProvider shouldBe cached
  }

  it should "keep the base response's usage, metadata and dim when it called the base client" in {
    val baseClient   = mock[EmbeddingProvider]
    val cachedClient = new CachedEmbeddingClient(baseClient, InMemoryEmbeddingCache.create().value)
    val request      = EmbeddingRequest(Seq("a", "b"), testModel)
    val usage        = EmbeddingUsage(promptTokens = 7, totalTokens = 7)
    val fresh = EmbeddingResponse(
      Seq(Seq(1.0, 0.0), Seq(0.0, 1.0)),
      metadata = Map("provider" -> "test"),
      dim = Some(2),
      usage = Some(usage)
    )
    (baseClient.embed _).expects(request).returning(Right(fresh)).once()

    val response = cachedClient.embed(request).value
    response.embeddings shouldBe fresh.embeddings
    response.usage shouldBe Some(usage)
    response.metadata shouldBe Map("provider" -> "test")
    response.dim shouldBe Some(2)

    // Served wholly from the cache: no tokens were spent, and the dimension is the vectors'
    val cachedResponse = cachedClient.embed(request).value
    cachedResponse.embeddings shouldBe fresh.embeddings
    cachedResponse.usage shouldBe None
    cachedResponse.dim shouldBe Some(2)
  }

  it should "treat a failing cache as a miss, and still answer when storing fails" in {
    val failing = new EmbeddingCache {
      private val boom                                           = org.llm4s.error.ProcessingError("cache", "down")
      override def get(key: String): Result[Option[Seq[Double]]] = Left(boom)
      override def put(key: String, embedding: Seq[Double]): Result[Unit] = Left(boom)
      override def clear(): Result[Unit]                                  = Left(boom)
    }
    val baseClient   = mock[EmbeddingProvider]
    val cachedClient = new CachedEmbeddingClient(baseClient, failing)
    val request      = EmbeddingRequest(Seq("hello"), testModel)
    (baseClient.embed _).expects(request).returning(Right(EmbeddingResponse(Seq(Seq(0.5))))).twice()

    cachedClient.embed(request).value.embeddings shouldBe Seq(Seq(0.5))
    cachedClient.embed(request).value.embeddings shouldBe Seq(Seq(0.5))
    cachedClient.cacheStats shouldBe None
    cachedClient.clearCache().isLeft shouldBe true
  }
}
