package org.llm4s.springai

import org.llm4s.error.AuthenticationError
import org.llm4s.javaapi.{ LlmErrorKind, LlmException, Llm4s }
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.testutil.FixtureEmbeddingProvider
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingRequest

import scala.jdk.CollectionConverters.*

class Llm4sEmbeddingModelSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = ModelRegistryService.fromModels(Nil)

  private val fixture: EmbeddingProvider =
    FixtureEmbeddingProvider
      .build(
        EmbeddingProviderConfig(FixtureEmbeddingProvider.DefaultBaseUrl, "fixture-embed-small", Secret("sk-fixture"))
      )
      .toOption
      .get

  private def model(declared: Int): Llm4sEmbeddingModel =
    Llm4sEmbeddingModel.of(new EmbeddingClient(fixture), EmbeddingModelConfig("fixture-embed-small", declared))

  "call" should "embed every text of the request, in order, with the model the provider named" in {
    val response = model(256).call(new EmbeddingRequest(java.util.List.of("a", "bb", "ccc"), null))

    response.getResults.asScala.map(_.getIndex.intValue) shouldBe Seq(0, 1, 2)
    response.getResults.asScala.map(_.getOutput.toList) shouldBe Seq.fill(3)(
      List.fill(FixtureEmbeddingProvider.Dimensions)(0.5f)
    )
    response.getMetadata.getModel shouldBe "fixture-embed-small"
  }

  "embed" should "embed a text, a list of texts and a document" in {
    val m = model(256)
    m.embed("hello").toList shouldBe List.fill(FixtureEmbeddingProvider.Dimensions)(0.5f)
    m.embed(java.util.List.of("a", "b")).size shouldBe 2
    m.embed(new Document("a document")).length shouldBe FixtureEmbeddingProvider.Dimensions
  }

  "dimensions" should "be the model's declared length, or what the provider makes when none is declared" in {
    model(256).dimensions() shouldBe 256
    model(0).dimensions() shouldBe FixtureEmbeddingProvider.Dimensions
  }

  "a failure" should "throw the LlmException the client's result holds" in {
    val failing: EmbeddingProvider = _ => Left(AuthenticationError("fixtureembedding", "bad key"))
    val m = new Llm4sEmbeddingModel(
      Llm4s.wrapEmbeddingClient(new EmbeddingClient(failing), EmbeddingModelConfig("fixture-embed-small", 256))
    )
    intercept[LlmException](m.embed("hi")).getKind shouldBe LlmErrorKind.AUTHENTICATION
  }

  "the model" should "refuse a null client" in {
    intercept[NullPointerException](new Llm4sEmbeddingModel(null))
  }
}
