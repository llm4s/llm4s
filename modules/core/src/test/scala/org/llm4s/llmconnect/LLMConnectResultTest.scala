package org.llm4s.llmconnect

import org.llm4s.config.ConfigKeys._
import org.llm4s.config.ConfigReader
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LLMConnectResultTest extends AnyFunSuite with Matchers {

  test("getClientResult returns OpenAIClient for openai/ model") {
    val reader = ConfigReader(
      Map(
        LLM_MODEL      -> "openai/gpt-4o",
        OPENAI_API_KEY -> "sk"
      )
    )

    val res = LLMConnect.getClient(reader)
    res.isRight shouldBe true
    res.toOption.get.getClass.getSimpleName shouldBe "OpenAIClient"
  }

  test("getClientResult returns OpenRouterClient when base URL is OpenRouter") {
    val reader = ConfigReader(
      Map(
        LLM_MODEL       -> "openrouter/gpt-4o",
        OPENAI_API_KEY  -> "sk",
        OPENAI_BASE_URL -> "https://openrouter.ai/api/v1"
      )
    )

    val res = LLMConnect.getClient(reader)
    res.isRight shouldBe true
    res.toOption.get.getClass.getSimpleName shouldBe "OpenRouterClient"
  }

  test("getClientResult returns ConfigurationError when LLM_MODEL missing") {
    val reader = ConfigReader(Map.empty)
    val res    = LLMConnect.getClient(reader)
    res.isLeft shouldBe true
  }

  test("getClientResult returns OpenAIClient for Azure provider") {
    val reader = ConfigReader(
      Map(
        LLM_MODEL      -> "azure/gpt-4o",
        AZURE_API_BASE -> "https://example.azure.com",
        AZURE_API_KEY  -> "az-sk"
      )
    )

    val res = LLMConnect.getClient(reader)
    res.isRight shouldBe true
    res.toOption.get.getClass.getSimpleName shouldBe "OpenAIClient"
  }
}
