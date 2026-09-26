package org.llm4s.llmconnect

import org.scalatest.EitherValues
import org.llm4s.llmconnect.config._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ProviderConfigConstructionTest extends AnyFunSuite with Matchers with EitherValues {

  // `OpenAIConfig` lives in llm4s-openai-compatible with OpenRouter; the OpenAI default URL moved
  // to `llm4s-openai` with the client (#1132), so it is spelled out here.
  private val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  test("OpenAIConfig.load returns Right on success") {
    val cfg = OpenAIConfig
      .fromValues(
        modelName = "gpt-4o",
        apiKey = "sk-test",
        organization = None,
        baseUrl = DEFAULT_OPENAI_BASE_URL
      )
      .value
    cfg.model shouldBe "gpt-4o"
    cfg.apiKey shouldBe "sk-test"
    cfg.baseUrl shouldBe DEFAULT_OPENAI_BASE_URL
  }

  test("OpenAIConfig.load returns Left when api key missing") {
    val res =
      OpenAIConfig.fromValues("gpt-4o", "", None, DEFAULT_OPENAI_BASE_URL)
    res.isLeft shouldBe true
  }

  test("OpenAIConfig.fromValues constructs an OpenAI config") {
    val openAi = OpenAIConfig.fromValues("gpt-4o", "sk", None, DEFAULT_OPENAI_BASE_URL).value
    openAi shouldBe a[OpenAIConfig]
  }

  test("OpenAIConfig.fromValues preserves an OpenRouter base URL") {
    val cfg = OpenAIConfig
      .fromValues(
        modelName = "gpt-4o",
        apiKey = "sk",
        organization = None,
        baseUrl = "https://openrouter.ai/api/v1"
      )
      .value
    cfg.baseUrl should include("openrouter.ai")
  }
}
