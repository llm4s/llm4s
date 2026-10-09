package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingRequest, InputPurpose }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ujson.read

/**
 * Cohere's `input_type` and the request's purpose. With no explicit input type the purpose decides
 * (a document is a search_document, a query a search_query); an explicit input type is a deliberate
 * choice and wins, because it can be one the purpose cannot express.
 */
class CohereEmbeddingPurposeSpec extends AnyFlatSpec with Matchers {

  private val cfg = EmbeddingProviderConfig(baseUrl = "http://cohere-test", model = "embed-english-v3.0", apiKey = "k")
  private val ok  = HttpResponse(200, """{"embeddings":{"float":[[0.1,0.2]]}}""", Map.empty)

  private def request(purpose: InputPurpose) =
    EmbeddingRequest(Seq("text"), EmbeddingModelConfig("embed-english-v3.0", 1024), purpose)

  /** The `input_type` of the request the provider sent, and the one it reports in the response metadata. */
  private def sent(provider: MockHttpClient => EmbeddingProvider, req: EmbeddingRequest): (String, String) = {
    val http   = new MockHttpClient(ok)
    val result = provider(http).embed(req).toOption.get
    (read(http.lastBody.get)("input_type").str, result.metadata("input_type"))
  }

  private val followingPurpose: MockHttpClient => EmbeddingProvider = http => CohereEmbeddingProvider.forTest(cfg, http)
  private def fixed(inputType: CohereInputType): MockHttpClient => EmbeddingProvider =
    http => CohereEmbeddingProvider.forTest(cfg, http, inputType)

  "a provider with no explicit input type" should "send search_document for a document" in {
    sent(followingPurpose, request(InputPurpose.Document)) shouldBe ("search_document" -> "search_document")
  }

  it should "send search_query for a query, and say so in the response metadata" in {
    sent(followingPurpose, request(InputPurpose.Query)) shouldBe ("search_query" -> "search_query")
  }

  it should "still send search_document when the request does not say, as every request did before" in {
    sent(followingPurpose, EmbeddingRequest(Seq("text"), EmbeddingModelConfig("embed-english-v3.0", 1024)))._1 shouldBe
      "search_document"
  }

  it should "follow the purpose of each request when one provider serves both" in {
    val http = new MockHttpClient(ok)
    val p    = CohereEmbeddingProvider.forTest(cfg, http)

    p.embed(request(InputPurpose.Query)).isRight shouldBe true
    read(http.lastBody.get)("input_type").str shouldBe "search_query"
    p.embed(request(InputPurpose.Document)).isRight shouldBe true
    read(http.lastBody.get)("input_type").str shouldBe "search_document"
  }

  "an explicit input type" should "win over the purpose of the request, whichever it is" in {
    InputPurpose.values.foreach { purpose =>
      withClue(purpose.toString) {
        sent(fixed(CohereInputType.Classification), request(purpose))._1 shouldBe "classification"
        sent(fixed(CohereInputType.Clustering), request(purpose))._1 shouldBe "clustering"
      }
    }
  }

  it should "win even when it contradicts the purpose" in {
    sent(fixed(CohereInputType.SearchDocument), request(InputPurpose.Query))._1 shouldBe "search_document"
    sent(fixed(CohereInputType.SearchQuery), request(InputPurpose.Document))._1 shouldBe "search_query"
  }

  "CohereInputType.forPurpose" should "map each purpose to a distinct search type, and a document to the default" in {
    CohereInputType.forPurpose(InputPurpose.Document) shouldBe CohereInputType.SearchDocument
    CohereInputType.forPurpose(InputPurpose.Query) shouldBe CohereInputType.SearchQuery
    CohereInputType.forPurpose(InputPurpose.Document) shouldBe CohereInputType.default
  }
}
