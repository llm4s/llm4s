package org.llm4s.spring

import org.llm4s.llmconnect.config.{ AnthropicConfig, OllamaConfig, OpenAIConfig, ProviderConfig }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * What the flat `llm4s.provider` / `llm4s.model` / `llm4s.api-key` ... properties of openai, anthropic and
 * ollama resolve to, written down as literals so that routing other providers through the provider SPI cannot
 * change them (#1467). Every expectation here was recorded from the behaviour before that change and passes
 * unchanged after it.
 */
class LegacyConfigGoldenSpec extends AnyFlatSpec with Matchers {

  private def props(
    provider: String = "",
    model: String = "",
    apiKey: String = "",
    baseUrl: String = "",
    organization: String = "",
    contextWindow: Int = 128000,
    reserveCompletion: Int = 4096
  ): Llm4sProperties = {
    val p = new Llm4sProperties
    p.provider = provider
    p.model = model
    p.apiKey = apiKey
    p.baseUrl = baseUrl
    p.organization = organization
    p.contextWindow = contextWindow
    p.reserveCompletion = reserveCompletion
    p
  }

  private def parsed(p: Llm4sProperties): ProviderConfig = ProviderConfigParser.parse(p).get()

  private def failure(p: Llm4sProperties): String = ProviderConfigParser.parse(p).getError().getMessage

  private val runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(classOf[Llm4sAutoConfiguration], classOf[LlmActuatorAutoConfiguration]))

  private def healthDetails(ctx: AssertableApplicationContext): (String, String) = {
    val details = ctx.getBean(classOf[HealthIndicator]).health().getDetails
    (details.get("provider").toString, details.get("model").toString)
  }

  // ---- the configs the flat properties build ------------------------------------------------------

  "the flat openai properties" should "build an OpenAIConfig with every field set" in {
    parsed(
      props(
        "openai",
        "gpt-4o",
        "sk-x",
        "https://example.invalid/v1",
        "org-1",
        contextWindow = 32000,
        reserveCompletion = 2000
      )
    ) shouldBe OpenAIConfig(
      apiKey = "sk-x",
      model = "gpt-4o",
      organization = Some("org-1"),
      baseUrl = "https://example.invalid/v1",
      contextWindow = 32000,
      reserveCompletion = 2000
    )
  }

  it should "default the base URL, the organisation and the context sizes" in {
    parsed(props("openai", "gpt-4o", "sk-x")) shouldBe OpenAIConfig(
      apiKey = "sk-x",
      model = "gpt-4o",
      organization = None,
      baseUrl = "https://api.openai.com/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
  }

  it should "trim every value and read the provider id in any case" in {
    parsed(props("  OpenAI ", " gpt-4o ", " sk-x ", " https://example.invalid/v1 ", " org-1 ")) shouldBe OpenAIConfig(
      apiKey = "sk-x",
      model = "gpt-4o",
      organization = Some("org-1"),
      baseUrl = "https://example.invalid/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
  }

  "the flat anthropic properties" should "build an AnthropicConfig, with and without overrides" in {
    parsed(
      props("anthropic", "claude-x", "sk-ant", "https://proxy.invalid", contextWindow = 64000, reserveCompletion = 1000)
    ) shouldBe
      AnthropicConfig(
        apiKey = "sk-ant",
        model = "claude-x",
        baseUrl = "https://proxy.invalid",
        contextWindow = 64000,
        reserveCompletion = 1000
      )
    parsed(props("anthropic", "claude-x", "sk-ant")) shouldBe AnthropicConfig(
      apiKey = "sk-ant",
      model = "claude-x",
      baseUrl = "https://api.anthropic.com",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
  }

  "the flat ollama properties" should "build an OllamaConfig that needs no key, with and without overrides" in {
    parsed(
      props("ollama", "llama3", baseUrl = "http://gpu.invalid:11434", contextWindow = 8192, reserveCompletion = 512)
    ) shouldBe
      OllamaConfig(
        model = "llama3",
        baseUrl = "http://gpu.invalid:11434",
        contextWindow = 8192,
        reserveCompletion = 512
      )
    parsed(props("ollama", "llama3")) shouldBe
      OllamaConfig(
        model = "llama3",
        baseUrl = "http://localhost:11434",
        contextWindow = 128000,
        reserveCompletion = 4096
      )
  }

  // ---- the messages of the failures they report ---------------------------------------------------

  "an invalid flat configuration" should "name the property that is wrong" in {
    failure(props(model = "m")) shouldBe "llm4s.provider is required"
    failure(props(provider = "openai")) shouldBe "llm4s.model is required"
    failure(props("openai", "m", contextWindow = 0)) shouldBe "llm4s.context-window must be positive, got 0"
    failure(props("openai", "m", contextWindow = 100, reserveCompletion = 100)) shouldBe
      "llm4s.reserve-completion must be >= 0 and less than llm4s.context-window (100), got 100"
    failure(props("openai", "m")) shouldBe "llm4s.api-key is required for OpenAI"
    failure(props("anthropic", "m")) shouldBe "llm4s.api-key is required for Anthropic"
  }

  it should "start with 'Unknown provider' for an id the flat keys do not know" in {
    failure(props("nope", "m")) should startWith("Unknown provider: 'nope'.")
  }

  // ---- what a running context does with them ------------------------------------------------------

  "a context started from the flat properties" should "report the configured provider and model in health" in {
    runner.withPropertyValues("llm4s.provider=openai", "llm4s.model=gpt-4o", "llm4s.api-key=sk-x").run { ctx =>
      ctx.getStartupFailure shouldBe null
      healthDetails(ctx) shouldBe (("openai", "gpt-4o"))
    }
    runner.withPropertyValues("llm4s.provider=anthropic", "llm4s.model=claude-x", "llm4s.api-key=k").run { ctx =>
      ctx.getStartupFailure shouldBe null
      healthDetails(ctx) shouldBe (("anthropic", "claude-x"))
    }
    runner.withPropertyValues("llm4s.provider=ollama", "llm4s.model=llama3").run { ctx =>
      ctx.getStartupFailure shouldBe null
      healthDetails(ctx) shouldBe (("ollama", "llama3"))
    }
  }

  it should "report the flat key that is missing when it fails to start" in {
    runner.withPropertyValues("llm4s.provider=openai", "llm4s.model=gpt-4o").run { ctx =>
      Iterator
        .iterate[Throwable](ctx.getStartupFailure)(_.getCause)
        .takeWhile(_ != null)
        .map(_.getMessage)
        .mkString(" | ") should include("llm4s.api-key is required for OpenAI")
    }
  }
}
