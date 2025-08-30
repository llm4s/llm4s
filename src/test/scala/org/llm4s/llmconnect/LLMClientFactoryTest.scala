package org.llm4s.llmconnect

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.config.ConfigReader

class LLMClientFactoryTest extends AnyFunSuite with Matchers {

  test("LLM.client(reader) returns OpenAIClient for openai/ prefix with API key") {
    val reader = ConfigReader.from(
      Map(
        "LLM_MODEL"      -> "openai/gpt-4o",
        "OPENAI_API_KEY" -> "key"
      )
    )
    val client = LLM.client(reader)
    client.getClass.getSimpleName shouldBe "OpenAIClient"
  }

  test("LLM.client(reader) returns AnthropicClient for anthropic/ prefix with API key") {
    val reader = ConfigReader.from(
      Map(
        "LLM_MODEL"         -> "anthropic/claude-3-sonnet",
        "ANTHROPIC_API_KEY" -> "key"
      )
    )
    val client = LLM.client(reader)
    client.getClass.getSimpleName shouldBe "AnthropicClient"
  }
}
