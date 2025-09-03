package org.llm4s.llmconnect

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.config.ConfigReader

class LLMConnectEnvReaderRoutingTest extends AnyFunSuite with Matchers {

  test("getClientResult(reader) returns OpenRouterClient when OPENAI_BASE_URL points to openrouter.ai") {
    val reader = ConfigReader(
      Map(
        "LLM_MODEL"       -> "openai/gpt-4o",
        "OPENAI_BASE_URL" -> "https://openrouter.ai/api/v1",
        "OPENAI_API_KEY"  -> "test-key"
      )
    )
    val res = LLMConnect.getClient(reader)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OpenRouterClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("getClientResult(reader) returns OllamaClient for ollama/ prefix with provided base URL") {
    val reader = ConfigReader(
      Map(
        "LLM_MODEL"       -> "ollama/llama3.1",
        "OLLAMA_BASE_URL" -> "http://localhost:11434"
      )
    )
    val res = LLMConnect.getClient(reader)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OllamaClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("getClientResult(reader) returns Left on missing LLM_MODEL") {
    val reader = ConfigReader(Map.empty)
    val res    = LLMConnect.getClient(reader)
    res.isLeft shouldBe true
  }
}
