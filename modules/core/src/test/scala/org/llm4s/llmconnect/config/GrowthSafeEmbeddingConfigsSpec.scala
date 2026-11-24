package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId }
import org.llm4s.security.Secret
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/** `EmbeddingProviderConfig` and `EmbeddingModelConfig` as growth-prone types (#2346). */
class GrowthSafeEmbeddingConfigsSpec extends AnyFunSuite with Matchers {

  private val provider = EmbeddingProviderConfig(
    ProviderId("fixtureembedding"),
    BaseUrl("https://api.example.invalid/v1"),
    ModelName("embed-small"),
    Some(Secret("sk-secret"))
  )

  test("EmbeddingProviderConfig(...) defaults timeouts") {
    provider.timeouts shouldBe ProviderTimeouts.default
  }

  test("EmbeddingProviderConfig's setters each change one field") {
    val timeouts = ProviderTimeouts(request = Some(30.seconds))
    val adjusted = provider
      .withBaseUrl(BaseUrl("https://other.invalid/v2"))
      .withModel(ModelName("embed-large"))
      .withApiKey(Secret("sk-other"))
      .withTimeouts(timeouts)

    adjusted shouldBe EmbeddingProviderConfig(
      ProviderId("fixtureembedding"),
      BaseUrl("https://other.invalid/v2"),
      ModelName("embed-large"),
      Some(Secret("sk-other")),
      timeouts
    )
  }

  test("EmbeddingProviderConfig still redacts the API key") {
    (provider.withApiKey(Secret("sk-very-secret-value")).toString should not).include("sk-very-secret-value")
  }

  test("EmbeddingProviderConfig keeps its constructor and copy private") {
    assertTypeError("""new EmbeddingProviderConfig("u", "m", "k", ProviderTimeouts.default)""")
    assertTypeError("""provider.copy(model = "m")""")
  }

  test("EmbeddingModelConfig's setters each change one field") {
    val model = EmbeddingModelConfig(ModelName("text-embedding-3-small"), 1536)

    model.withName(ModelName("text-embedding-3-large")) shouldBe EmbeddingModelConfig(
      ModelName("text-embedding-3-large"),
      1536
    )
    model.withDimensions(3072) shouldBe EmbeddingModelConfig(ModelName("text-embedding-3-small"), 3072)
  }

  test("EmbeddingModelConfig keeps its constructor and copy private") {
    assertTypeError("""new EmbeddingModelConfig("m", 1)""")
    assertTypeError("""EmbeddingModelConfig("m", 1).copy(dimensions = 2)""")
  }
}
