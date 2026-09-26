package org.llm4s.llmconnect.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The provider-config case - `OpenAIConfig` and `ZaiConfig` must not print their API keys -
 * moved to `llm4s-openai-compatible`'s `OpenAICompatibleConfigRedactionSpec` with those
 * configs (#1132).
 */
class ConfigRedactionSpec extends AnyFlatSpec with Matchers {

  private val secret = "SECRET_TEST_VALUE_12345"

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
