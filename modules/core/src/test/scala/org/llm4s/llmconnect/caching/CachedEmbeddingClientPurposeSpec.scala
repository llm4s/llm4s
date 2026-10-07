package org.llm4s.llmconnect.caching

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse, InputPurpose }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryTestSupport
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * The cache and the purpose of a request: a query and a document with the same text are different
 * vectors for the models that embed them differently, so they must never share an entry, while the
 * keys of documents stay what they were before the purpose existed.
 */
class CachedEmbeddingClientPurposeSpec extends AnyFlatSpec with Matchers {

  private given org.llm4s.model.ModelRegistryService = ModelRegistryTestSupport.defaultService()

  private val model = EmbeddingModelConfig("test-model", 2)

  /** A provider that answers with a vector naming the purpose it was asked for, and records every request. */
  final private class PurposeEchoProvider extends EmbeddingProvider {
    val requests: ListBuffer[EmbeddingRequest] = ListBuffer.empty

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      requests += request
      val tag = if (request.purpose == InputPurpose.Query) 1.0 else 0.0
      Right(EmbeddingResponse(request.input.map(_ => Seq(tag, request.input.size.toDouble))))
    }
  }

  private def setup(): (PurposeEchoProvider, InMemoryEmbeddingCache[Seq[Double]], CachedEmbeddingClient) = {
    val provider = new PurposeEchoProvider
    val cache    = new InMemoryEmbeddingCache[Seq[Double]]()
    (provider, cache, new CachedEmbeddingClient(new EmbeddingClient(provider), cache))
  }

  "CachedEmbeddingClient" should "not serve a cached document vector for a query with the same text" in {
    val (provider, _, client) = setup()

    val doc   = client.embed(EmbeddingRequest(Seq("same text"), model)).map(_.embeddings.head)
    val query = client.embed(EmbeddingRequest(Seq("same text"), model, InputPurpose.Query)).map(_.embeddings.head)

    doc shouldBe Right(Seq(0.0, 1.0))
    query shouldBe Right(Seq(1.0, 1.0))
    provider.requests.map(_.purpose) shouldBe Seq(InputPurpose.Document, InputPurpose.Query)
  }

  it should "cache each purpose separately, so a repeat of either is a hit" in {
    val (provider, _, client) = setup()
    val doc                   = EmbeddingRequest(Seq("same text"), model)
    val query                 = doc.withPurpose(InputPurpose.Query)

    client.embed(doc)
    client.embed(query)
    val docAgain   = client.embed(doc).map(_.embeddings.head)
    val queryAgain = client.embed(query).map(_.embeddings.head)

    provider.requests should have size 2
    docAgain shouldBe Right(Seq(0.0, 1.0))
    queryAgain shouldBe Right(Seq(1.0, 1.0))
  }

  it should "forward the purpose of the request to the base client for the texts it misses" in {
    val (provider, cache, client) = setup()
    cache.put(CacheKeyGenerator.sha256("cached", s"${model.name}#query"), Seq(9.0, 9.0))

    val result = client.embed(EmbeddingRequest(Seq("cached", "fresh"), model, InputPurpose.Query))

    provider.requests.map(r => (r.input, r.purpose)) shouldBe Seq((Seq("fresh"), InputPurpose.Query))
    result.map(_.embeddings) shouldBe Right(Seq(Seq(9.0, 9.0), Seq(1.0, 1.0)))
  }

  it should "key a document by the plain model name, as it did before purposes existed" in {
    val (_, cache, client) = setup()

    client.embed(EmbeddingRequest(Seq("hello"), model))

    cache.get(CacheKeyGenerator.sha256("hello", model.name)) shouldBe Some(Seq(0.0, 1.0))
  }

  it should "find a document vector cached by a version that had no purpose" in {
    val (provider, cache, client) = setup()
    cache.put(CacheKeyGenerator.sha256("old", model.name), Seq(7.0, 7.0))

    val result = client.embed(EmbeddingRequest(Seq("old"), model))

    result.map(_.embeddings) shouldBe Right(Seq(Seq(7.0, 7.0)))
    provider.requests shouldBe empty
  }

  it should "key a query by the model name and the query marker" in {
    val (_, cache, client) = setup()

    client.embed(EmbeddingRequest(Seq("hello"), model, InputPurpose.Query))

    cache.get(CacheKeyGenerator.sha256("hello", s"${model.name}#query")) shouldBe Some(Seq(1.0, 1.0))
    cache.get(CacheKeyGenerator.sha256("hello", model.name)) shouldBe None
  }

  it should "hand a custom key generator the query-scoped model for a query and the plain model for a document" in {
    val seen  = ListBuffer.empty[(String, String)]
    val cache = new InMemoryEmbeddingCache[Seq[Double]]()
    val client = new CachedEmbeddingClient(
      new EmbeddingClient(new PurposeEchoProvider),
      cache,
      (text, scope) => { seen += text -> scope; s"$scope|$text" }
    )

    client.embed(EmbeddingRequest(Seq("t"), model))
    client.embed(EmbeddingRequest(Seq("t"), model, InputPurpose.Query))

    (seen.toList should contain).allOf("t" -> "test-model", "t" -> "test-model#query")
  }
}
