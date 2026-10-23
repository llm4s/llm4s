package org.llm4s.llmconnect.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/** `EmbeddingProviderConfig`, `EmbeddingModelConfig` and `LocalEmbeddingModels` as growth-prone types (#2346). */
class GrowthSafeEmbeddingConfigsSpec extends AnyFunSuite with Matchers {

  private val provider = EmbeddingProviderConfig("https://api.example.invalid/v1", "embed-small", "sk-secret")

  test("EmbeddingProviderConfig(...) defaults timeouts") {
    provider.timeouts shouldBe ProviderTimeouts.default
  }

  test("EmbeddingProviderConfig's setters each change one field") {
    val timeouts = ProviderTimeouts(request = Some(30.seconds))
    val adjusted = provider
      .withBaseUrl("https://other.invalid/v2")
      .withModel("embed-large")
      .withApiKey("sk-other")
      .withTimeouts(timeouts)

    adjusted shouldBe EmbeddingProviderConfig("https://other.invalid/v2", "embed-large", "sk-other", timeouts)
  }

  test("EmbeddingProviderConfig still redacts the API key") {
    (provider.withApiKey("sk-very-secret-value").toString should not).include("sk-very-secret-value")
  }

  test("EmbeddingProviderConfig keeps its constructor and copy private") {
    assertTypeError("""new EmbeddingProviderConfig("u", "m", "k", ProviderTimeouts.default)""")
    assertTypeError("""provider.copy(model = "m")""")
  }

  test("EmbeddingModelConfig's setters each change one field") {
    val model = EmbeddingModelConfig("text-embedding-3-small", 1536)

    model.withName("text-embedding-3-large") shouldBe EmbeddingModelConfig("text-embedding-3-large", 1536)
    model.withDimensions(3072) shouldBe EmbeddingModelConfig("text-embedding-3-small", 3072)
  }

  test("EmbeddingModelConfig keeps its constructor and copy private") {
    assertTypeError("""new EmbeddingModelConfig("m", 1)""")
    assertTypeError("""EmbeddingModelConfig("m", 1).copy(dimensions = 2)""")
  }

  test("LocalEmbeddingModels' setters each change one field") {
    val models = LocalEmbeddingModels("openclip-vit-b32", "wav2vec2-base", "timesformer-base")

    models.withImageModel("clip") shouldBe LocalEmbeddingModels("clip", "wav2vec2-base", "timesformer-base")
    models.withAudioModel("whisper") shouldBe LocalEmbeddingModels("openclip-vit-b32", "whisper", "timesformer-base")
    models.withVideoModel("vivit") shouldBe LocalEmbeddingModels("openclip-vit-b32", "wav2vec2-base", "vivit")
  }

  test("LocalEmbeddingModels keeps its constructor and copy private") {
    assertTypeError("""new LocalEmbeddingModels("i", "a", "v")""")
    assertTypeError("""LocalEmbeddingModels("i", "a", "v").copy(imageModel = "x")""")
  }
}
