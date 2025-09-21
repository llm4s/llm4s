package org.llm4s.llmconnect

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.config.ConfigReader

class LLMClientFactoryTest extends AnyFunSuite with Matchers {

  test("LLMConnect.getClientResult(reader) returns OpenAIClient for openai/ prefix with API key") {
    val reader = ConfigReader(
      Map(
        "LLM_MODEL"      -> "openai/gpt-4o",
        "OPENAI_API_KEY" -> "key"
      )
    )
    val res = LLMConnect.getClient(reader)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OpenAIClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("LLMConnect.getClientResult(reader) returns AnthropicClient for anthropic/ prefix with API key") {
    val reader = ConfigReader(
      Map(
        "LLM_MODEL"         -> "anthropic/claude-3-sonnet",
        "ANTHROPIC_API_KEY" -> "key"
      )
    )
    val res = LLMConnect.getClient(reader)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "AnthropicClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }
}
