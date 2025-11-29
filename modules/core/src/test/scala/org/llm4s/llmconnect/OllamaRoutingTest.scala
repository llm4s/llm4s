package org.llm4s.llmconnect

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.provider.LLMProvider
import org.llm4s.llmconnect.config.OllamaConfig

class OllamaRoutingTest extends AnyFunSuite with Matchers {

  test("provider-based getClientResult returns OllamaClient") {
    val cfg = OllamaConfig(
      model = "llama3.1",
      baseUrl = "http://localhost:11434",
      contextWindow = 8192,
      reserveCompletion = 4096
    )
    val res = LLMConnect.getClient(LLMProvider.Ollama, cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OllamaClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("OllamaConfig.from(reader) uses provided base URL") {
    val cfg = OllamaConfig.fromValues("mistral:latest", "http://lan-host:11434")
    cfg.baseUrl shouldBe "http://lan-host:11434"
    cfg.model shouldBe "mistral:latest"
  }
}
