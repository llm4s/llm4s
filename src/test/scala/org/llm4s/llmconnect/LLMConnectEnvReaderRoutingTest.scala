package org.llm4s.llmconnect

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.config.ConfigReader

class LLMConnectEnvReaderRoutingTest extends AnyFunSuite with Matchers {

  test("getClient(reader) returns OpenRouterClient when OPENAI_BASE_URL points to openrouter.ai") {
    val reader = ConfigReader.from(
      Map(
        "LLM_MODEL"       -> "openai/gpt-4o",
        "OPENAI_BASE_URL" -> "https://openrouter.ai/api/v1",
        "OPENAI_API_KEY"  -> "test-key"
      )
    )
    val client = LLMConnect.getClient(reader)
    client.getClass.getSimpleName shouldBe "OpenRouterClient"
  }

  test("getClient(reader) returns OllamaClient for ollama/ prefix with provided base URL") {
    val reader = ConfigReader.from(
      Map(
        "LLM_MODEL"       -> "ollama/llama3.1",
        "OLLAMA_BASE_URL" -> "http://localhost:11434"
      )
    )
    val client = LLMConnect.getClient(reader)
    client.getClass.getSimpleName shouldBe "OllamaClient"
  }

  test("getClient(reader) throws on missing LLM_MODEL") {
    val reader = ConfigReader.from(Map.empty)
    assertThrows[IllegalArgumentException] {
      LLMConnect.getClient(reader)
    }
  }
}
