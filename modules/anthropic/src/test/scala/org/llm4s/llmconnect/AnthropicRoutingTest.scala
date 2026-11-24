package org.llm4s.llmconnect

import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderId }
import org.llm4s.security.Secret
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.config.{ AnthropicConfig, ProviderConfig }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/**
 * `LLMConnect` routes Anthropic configs to `AnthropicClient` through the registry.
 *
 * Moved from core's `LLMConnectProviderTypeSafetyTest` and `LLMClientFactoryTest` (#1132).
 */
class AnthropicRoutingTest extends AnyFunSuite with Matchers {
  private given ModelRegistryService = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get

  private val anthropic: ProviderConfig = AnthropicConfig(
    apiKey = Secret("key"),
    model = ModelName("claude-3-sonnet"),
    baseUrl = "https://api.anthropic.com",
    contextWindow = 200000,
    reserveCompletion = 4096
  )

  test("Anthropic provider with AnthropicConfig returns AnthropicClient") {
    LLMConnect.getClient(ProviderId("anthropic"), anthropic, LlmClientOptions.default) match {
      case Right(client) => client.getClass.getSimpleName shouldBe "AnthropicClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("an AnthropicConfig routed to another provider is refused") {
    LLMConnect.getClient(ProviderId("openai"), anthropic, LlmClientOptions.default).isLeft shouldBe true
  }

  test("LLMConnect.getClient returns AnthropicClient for the default named Anthropic provider") {
    val hocon =
      """
        |llm4s {
        |  defaultProvider = "anthropic-main"
        |  providers {
        |    anthropic-main {
        |      provider = "anthropic"
        |      model = "claude-3-sonnet"
        |      apiKey = "sk-anthropic"
        |      baseUrl = "https://api.anthropic.com"
        |    }
        |  }
        |}
        |""".stripMargin

    Llm4sConfig.providerFrom(ConfigSource.string(hocon)).flatMap(LLMConnect.getClient(_)) match {
      case Right(client) => client.getClass.getSimpleName shouldBe "AnthropicClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }
}
