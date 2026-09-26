package org.llm4s.llmconnect

import org.scalatest.EitherValues
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LLMConnectEnvReaderRoutingTest extends AnyFunSuite with Matchers with EitherValues {
  private val registryService        = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ModelRegistryService = registryService

  private given ContextWindowResolver =
    ContextWindowResolver(registryService)

  test("LLMConnect.getClient returns OpenRouterClient when OpenAI baseUrl points to openrouter.ai") {
    val cfg = OpenAIConfig
      .fromValues(
        modelName = "gpt-4o",
        apiKey = "sk-test",
        organization = None,
        baseUrl = "https://openrouter.ai/api/v1"
      )
      .value

    val res = LLMConnect.getClient(cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OpenRouterClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }
}
