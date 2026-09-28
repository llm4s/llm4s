package org.llm4s.llmconnect.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The provider-config case - `OpenAIConfig` and `ZaiConfig` must not print their API keys -
 * moved to `llm4s-openai-compatible`'s `OpenAICompatibleConfigRedactionSpec` with those
 * configs (#1132), and the `LangfuseConfig` case to `llm4s-observability`'s
 * `LangfuseConfigSpec` (#1133).
 */
class ConfigRedactionSpec extends AnyFlatSpec with Matchers {

  private val secret = "SECRET_TEST_VALUE_12345"

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
