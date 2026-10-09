package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regressions for response alignment: the vector at position i must belong to input i. */
class JinaEmbeddingProviderRegressionSpec extends AnyFlatSpec with Matchers {

  private val modelCfg = EmbeddingModelConfig("jina-embeddings-v3", 1024)
  private val cfg      = EmbeddingProviderConfig("http://jina-test", "jina-embeddings-v3", "k")

  private def run(body: String, n: Int) =
    JinaEmbeddingProvider
      .forTest(cfg, new MockHttpClient(HttpResponse(200, body, Map.empty)))
      .embed(EmbeddingRequest((1 to n).map(i => s"t$i"), modelCfg))

  "response alignment" should "reorder data items by their index field" in {
    val body =
      """{"data":[{"index":2,"embedding":[3.0]},{"index":0,"embedding":[1.0]},{"index":1,"embedding":[2.0]}]}"""
    run(body, 3).toOption.get.embeddings shouldBe Seq(Vector(1.0), Vector(2.0), Vector(3.0))
  }

  it should "keep response order when items carry no index" in {
    run("""{"data":[{"embedding":[1.0]},{"embedding":[2.0]}]}""", 2).toOption.get.embeddings shouldBe
      Seq(Vector(1.0), Vector(2.0))
  }

  it should "fail rather than return misaligned vectors when the count differs from the inputs" in {
    val err = run("""{"data":[{"index":0,"embedding":[1.0]}]}""", 3).left.toOption.get
    err.message should include("3")
    err.message should include("1")
  }

  it should "fail when indices are not a permutation of 0 until n" in {
    run("""{"data":[{"index":0,"embedding":[1.0]},{"index":0,"embedding":[2.0]}]}""", 2).isLeft shouldBe true
    run("""{"data":[{"index":0,"embedding":[1.0]},{"index":5,"embedding":[2.0]}]}""", 2).isLeft shouldBe true
  }

  "error bodies" should "not echo the API key when the server reflects the Authorization header" in {
    val secret = "jina-SECRET-key-123"
    val http   = new MockHttpClient(HttpResponse(401, s"""{"detail":"invalid key Bearer $secret"}""", Map.empty))
    val err = JinaEmbeddingProvider
      .forTest(cfg.copy(apiKey = secret), http)
      .embed(EmbeddingRequest(Seq("a"), modelCfg))
      .left
      .toOption
      .get
    (err.message should not).include(secret)
  }
}
