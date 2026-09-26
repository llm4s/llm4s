package org.llm4s.llmconnect.config

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The configs `llm4s-openai-compatible` holds do not print their API keys - core's
 * `ConfigRedactionSpec` case, moved here with them (#1132).
 */
class OpenAICompatibleConfigRedactionSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  private val secret = "SECRET_TEST_VALUE_12345"

  "Provider config toString" should "not leak apiKey values" in {
    val zai = ZaiConfig
      .fromValues(
        modelName = "glm-4.5",
        apiKey = secret,
        baseUrl = "https://example.invalid"
      )
      .value
    (zai.toString should not).include(secret)
    zai.toString should include("***")
  }
}
