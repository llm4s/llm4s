package org.llm4s.llmconnect

import org.scalatest.EitherValues
import java.time.Instant

import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.testutil.{ FixtureChatConfig, FixtureChatProvider }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LlmClientOptionsSpec extends AnyFunSuite with Matchers with EitherValues {
  private val registryService        = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ModelRegistryService = registryService

  test("LlmClientOptions.default keeps exchange logging disabled") {
    LlmClientOptions.default.exchangeLogging shouldBe ProviderExchangeLogging.Disabled
  }

  test("ProviderExchangeLogging.enabled stores the provided sink") {
    val sink    = ProviderExchangeSink.noop
    val enabled = ProviderExchangeLogging.enabled(sink)

    enabled shouldBe ProviderExchangeLogging.Enabled(sink)
  }

  test("LLMConnect.getClient accepts explicit options for config-driven construction") {
    // This used an OpenRouter OpenAIConfig until OpenRouter and OpenAIConfig left core (#1132).
    val cfg = FixtureChatConfig
      .fromValues(
        model = "fixture-model",
        apiKey = "sk-test",
        baseUrl = FixtureChatProvider.DefaultBaseUrl
      )
      .value
    val options = LlmClientOptions(
      exchangeLogging = ProviderExchangeLogging.enabled(ProviderExchangeSink.noop)
    )

    val res = LLMConnect.getClient(cfg, options)

    res.isRight shouldBe true
  }

  test("LLMConnect.getClient accepts explicit options for provider-checked construction") {
    val cfg = FixtureChatConfig
      .fromValues(
        model = "fixture-model",
        apiKey = "sk-test",
        baseUrl = FixtureChatProvider.DefaultBaseUrl
      )
      .value
    val options = LlmClientOptions(
      exchangeLogging = ProviderExchangeLogging.enabled(ProviderExchangeSink.noop)
    )

    val res = LLMConnect.getClient(FixtureChatProvider.id, cfg, options)

    res.isRight shouldBe true
  }

  test("ProviderExchange carries timing, outcome, and correlation metadata") {
    val startedAt   = Instant.parse("2026-03-22T10:00:00Z")
    val completedAt = Instant.parse("2026-03-22T10:00:02Z")

    val exchange = ProviderExchange(
      exchangeId = "ex-1",
      provider = "ollama",
      model = Some("gemma3:27b"),
      requestId = Some("req-1"),
      correlationId = Some("corr-1"),
      startedAt = startedAt,
      completedAt = completedAt,
      durationMs = 2000L,
      outcome = ProviderExchangeOutcome.Success,
      requestBody = """{"prompt":"hello"}""",
      responseBody = Some("""{"response":"hi"}"""),
      errorMessage = None
    )

    exchange.durationMs shouldBe 2000L
    exchange.outcome shouldBe ProviderExchangeOutcome.Success
    exchange.requestId shouldBe Some("req-1")
    exchange.correlationId shouldBe Some("corr-1")
  }

}
