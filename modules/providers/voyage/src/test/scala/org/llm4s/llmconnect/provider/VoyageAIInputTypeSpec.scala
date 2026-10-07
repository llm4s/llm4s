package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingRequest, InputPurpose }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ujson.read

/** Voyage's `input_type`: a request says whether its texts are documents or queries, and Voyage is told. */
class VoyageAIInputTypeSpec extends AnyFlatSpec with Matchers {

  private val cfg      = EmbeddingProviderConfig(baseUrl = "http://voyage-test", model = "voyage-3", apiKey = "k")
  private val modelCfg = EmbeddingModelConfig("voyage-3", 1024)
  private val okBody   = """{"data":[{"embedding":[0.1,0.2]},{"embedding":[0.3,0.4]}]}"""

  private def sentBody(request: EmbeddingRequest): ujson.Value = {
    val http = new MockHttpClient(HttpResponse(200, okBody, Map.empty))
    VoyageAIEmbeddingProvider.forTest(cfg, http).embed(request).isRight shouldBe true
    read(http.lastBody.get)
  }

  "VoyageAIEmbeddingProvider" should "send input_type document for a document request" in {
    sentBody(EmbeddingRequest(Seq("a", "b"), modelCfg, InputPurpose.Document))("input_type").str shouldBe "document"
  }

  it should "send input_type query for a query request" in {
    sentBody(EmbeddingRequest(Seq("a", "b"), modelCfg, InputPurpose.Query))("input_type").str shouldBe "query"
  }

  it should "send input_type document when the request does not say (Voyage received no input_type at all before purposes existed)" in {
    sentBody(EmbeddingRequest(Seq("a", "b"), modelCfg))("input_type").str shouldBe "document"
  }

  it should "send the texts and the model unchanged alongside the input_type" in {
    val body = sentBody(EmbeddingRequest(Seq("first", "second"), modelCfg, InputPurpose.Query))

    body("input").arr.map(_.str).toSeq shouldBe Seq("first", "second")
    body("model").str shouldBe "voyage-3"
    body.obj.keySet shouldBe Set("input", "model", "input_type")
  }

  it should "return the same vectors whichever the purpose, since the purpose only changes the request" in {
    val http = new MockHttpClient(HttpResponse(200, okBody, Map.empty))
    val p    = VoyageAIEmbeddingProvider.forTest(cfg, http)

    val doc   = p.embed(EmbeddingRequest(Seq("a", "b"), modelCfg)).map(_.embeddings)
    val query = p.embed(EmbeddingRequest(Seq("a", "b"), modelCfg, InputPurpose.Query)).map(_.embeddings)

    doc shouldBe Right(Seq(Vector(0.1, 0.2), Vector(0.3, 0.4)))
    query shouldBe doc
  }

  "inputTypeFor" should "map each purpose to Voyage's name for it, and no two purposes to the same one" in {
    InputPurpose.values.map(VoyageAIEmbeddingProvider.inputTypeFor).toSet shouldBe Set("document", "query")
  }
}
