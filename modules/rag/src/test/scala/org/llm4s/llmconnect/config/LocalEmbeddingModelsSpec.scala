package org.llm4s.llmconnect.config

import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** `LocalEmbeddingModels`, moved from `llm4s-core` with its encoder table (#2346, #2805). */
class LocalEmbeddingModelsSpec extends AnyFunSuite with Matchers with EitherValues {

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

  test("dimensionsOf answers the local encoders and refuses any other model (#2805)") {
    LocalEmbeddingModels.dimensionsOf("openclip-vit-b32").value shouldBe 512
    LocalEmbeddingModels.dimensionsOf("wav2vec2-base").value shouldBe 768
    LocalEmbeddingModels.dimensionsOf("timesformer-base").value shouldBe 768
    LocalEmbeddingModels.dimensionsOf("no-such-encoder").left.value.formatted should include(
      "Unknown model 'no-such-encoder' for provider 'local'"
    )
  }
}
