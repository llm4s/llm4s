package org.llm4s.llmconnect

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.provider.LLMProvider
import org.llm4s.llmconnect.config.OllamaConfig

class OllamaRoutingTest extends AnyFunSuite with Matchers {

  test("provider-based getClient returns OllamaClient") {
    val cfg = OllamaConfig(
      model = "llama3.1",
      baseUrl = "http://localhost:11434"
    )
    val client = LLMConnect.getClient(LLMProvider.Ollama, cfg)
    client.getClass.getSimpleName shouldBe "OllamaClient"
  }

  test("OllamaConfig.from(reader) uses provided base URL") {
    val reader = org.llm4s.config.ConfigReader.from(
      Map("OLLAMA_BASE_URL" -> "http://lan-host:11434")
    )
    val cfg = org.llm4s.llmconnect.config.OllamaConfig.from("mistral:latest", reader)
    cfg.baseUrl shouldBe "http://lan-host:11434"
    cfg.model shouldBe "mistral:latest"
  }
}
