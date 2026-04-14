package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.{ ContextWindowResolver, OllamaConfig, OpenAIConfig }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.types.ProviderModelTypes.ProviderKind
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LLMConnectEnvReaderRoutingTest extends AnyFunSuite with Matchers {
  private val registryService        = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ModelRegistryService = registryService

  private given ContextWindowResolver =
    ContextWindowResolver(registryService)

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

    val res = LLMConnect.getClient(ProviderKind.Ollama, cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OllamaClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }
}
