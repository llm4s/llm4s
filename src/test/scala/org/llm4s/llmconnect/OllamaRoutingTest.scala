package org.llm4s.llmconnect

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.provider.LLMProvider
import org.llm4s.llmconnect.config.OllamaConfig

class OllamaRoutingTest extends AnyFunSuite with Matchers {

  test("provider-based getClient returns OllamaClient") {
    val cfg = OllamaConfig(model = "llama3.1", "http://localhost:11434")
    val client = LLMConnect.getClient(LLMProvider.Ollama, cfg)
    client.getClass.getSimpleName shouldBe "OllamaClient"
  }

  //TODO some refactoring requred to enable unit tests
//  test("OllamaConfig.fromEnv uses default base URL when unset") {
//    val cfg = OllamaConfig.fromEnv("mistral:latest").get
//    cfg.baseUrl shouldBe "http://localhost:11434"
//    cfg.model shouldBe "mistral:latest"
//  }
}

