package org.llm4s.llmconnect.smoke

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret

import org.llm4s.it.{ GeminiSmoke, Tier }
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.config.{ ContextWindowResolver, GeminiConfig }
import org.llm4s.llmconnect.provider.GeminiClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke test for a sum type on Gemini (#2327): the derived `anyOf`, wrapped in a `value` property, is accepted
 * as the response schema and the reply reads back into the right case. One small call; only the case is asserted.
 *
 * Requires: `GOOGLE_API_KEY` (or `GEMINI_API_KEY`). Tier: `@Cloud` - `sbt testSmoke`.
 */
@Cloud
class GeminiStructuredOutputSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = GeminiSmoke.apiKey

  "completeStructuredOf on Gemini" should "return a sum type" in {
    Tier.require(apiKey.isDefined, s"${GeminiSmoke.KeyEnvVars} not set")
    val config = GeminiConfig
      .fromValues(
        modelName = ModelName(GeminiSmoke.Model),
        apiKey = Secret(apiKey.get),
        baseUrl = BaseUrl("https://generativelanguage.googleapis.com/v1beta")
      )
      .value
    val result = SumTypeSmoke.decide(GeminiClient(config).value)
    withClue(s"completeStructuredOf failed: ${result.swap.toOption}")(result.isRight shouldBe true)
    result.value shouldBe a[SumTypeSmoke.Action.Refund]
  }
}
