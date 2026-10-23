package org.llm4s.llmconnect.utils

import org.llm4s.llmconnect.config.LocalEmbeddingModels
import org.llm4s.llmconnect.model._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ModelSelectorSpec extends AnyFunSuite with Matchers {

  // Use model names from ModelDimensionRegistry
  val testLocalModels: LocalEmbeddingModels = LocalEmbeddingModels(
    imageModel = "openclip-vit-b32",
    audioModel = "wav2vec2-base",
    videoModel = "timesformer-base"
  )

  test("selectModel returns error for Text modality") {
    val result = ModelSelector.selectModel(Text, testLocalModels)
    result.isLeft shouldBe true
    result.left.toOption.get.formatted should include("Text model selection is configuration-driven")
  }

  test("selectModel returns config for Image modality") {
    val result = ModelSelector.selectModel(Image, testLocalModels)
    result.isRight shouldBe true
    result.toOption.get.name shouldBe "openclip-vit-b32"
  }

  test("selectModel returns config for Audio modality") {
    val result = ModelSelector.selectModel(Audio, testLocalModels)
    result.isRight shouldBe true
    result.toOption.get.name shouldBe "wav2vec2-base"
  }

  test("selectModel returns config for Video modality") {
    val result = ModelSelector.selectModel(Video, testLocalModels)
    result.isRight shouldBe true
    result.toOption.get.name shouldBe "timesformer-base"
  }
}
