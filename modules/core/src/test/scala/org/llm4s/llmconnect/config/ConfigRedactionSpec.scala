package org.llm4s.llmconnect.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigRedactionSpec extends AnyFlatSpec with Matchers {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  private val secret = "SECRET_TEST_VALUE_12345"

  "Provider config toString" should "not leak apiKey values" in {
    val openai = OpenAIConfig.fromValues(
      modelName = "gpt-4",
      apiKey = secret,
      organization = Some("org"),
      baseUrl = "https://example.invalid/v1"
    )
    (openai.toString should not).include(secret)
    openai.toString should include("***")

    val azure = AzureConfig.fromValues(
      modelName = "gpt-4",
      endpoint = "https://example.invalid",
      apiKey = secret,
      apiVersion = "2024-02-01"
    )
    (azure.toString should not).include(secret)
    azure.toString should include("***")

    val anthropic = AnthropicConfig.fromValues(
      modelName = "claude-3-5-sonnet-20241022",
      apiKey = secret,
      baseUrl = "https://example.invalid"
    )
    (anthropic.toString should not).include(secret)
    anthropic.toString should include("***")

    val gemini = GeminiConfig.fromValues(
      modelName = "gemini-1.5-pro",
      apiKey = secret,
      baseUrl = "https://example.invalid"
    )
    (gemini.toString should not).include(secret)
    gemini.toString should include("***")

    val zai = ZaiConfig.fromValues(
      modelName = "glm-4.5",
      apiKey = secret,
      baseUrl = "https://example.invalid"
    )
    (zai.toString should not).include(secret)
    zai.toString should include("***")
  }

  "LangfuseConfig toString" should "not leak keys" in {
    val cfg = LangfuseConfig(
      url = "https://example.invalid",
      publicKey = Some(secret),
      secretKey = Some(secret),
      env = "dev",
      release = "local",
      version = "0"
    )

    (cfg.toString should not).include(secret)
    cfg.toString should include("Some(***)")
  }

  it should "render missing keys as None" in {
    val cfg = LangfuseConfig(
      url = "https://example.invalid",
      publicKey = None,
      secretKey = None,
      env = "dev",
      release = "local",
      version = "0"
    )

    cfg.toString should include("publicKey=None")
    cfg.toString should include("secretKey=None")
  }

  "EmbeddingProviderConfig toString" should "not leak apiKey values" in {
    val cfg = EmbeddingProviderConfig(
      baseUrl = "https://example.invalid",
      model = "text-embedding-3-large",
      apiKey = secret
    )

    (cfg.toString should not).include(secret)
    cfg.toString should include("***")
  }
}
