package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.{ OllamaConfig, OpenAIConfig }
import org.llm4s.llmconnect.provider.LLMProvider
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LLMConnectEnvReaderRoutingTest extends AnyFunSuite with Matchers {

  test("LLMConnect.getClient returns OpenRouterClient when OpenAI baseUrl points to openrouter.ai") {
    val cfg = OpenAIConfig.fromValues(
      modelName = "gpt-4o",
      apiKey = "sk-test",
      organization = None,
      baseUrl = "https://openrouter.ai/api/v1"
    )

    val res = LLMConnect.getClient(cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OpenRouterClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("LLMConnect.getClient returns OllamaClient for Ollama provider") {
    val cfg = OllamaConfig.fromValues(
      modelName = "llama3.1",
      baseUrl = "http://localhost:11434"
    )

    val res = LLMConnect.getClient(LLMProvider.Ollama, cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OllamaClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }
}
