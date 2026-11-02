package org.llm4s.llmconnect.smoke

import org.llm4s.it.Tier
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
 * Requires: `GEMINI_API_KEY`. Tier: `@Cloud` - `sbt testSmoke`.
 */
@Cloud
class GeminiStructuredOutputSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("GEMINI_API_KEY")).filter(_.nonEmpty)

  "completeStructuredOf on Gemini" should "return a sum type" in {
    Tier.require(apiKey.isDefined, "GEMINI_API_KEY not set")
    val config = GeminiConfig
      .fromValues(
        modelName = "gemini-2.5-flash",
        apiKey = apiKey.get,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta"
      )
      .value
    val result = SumTypeSmoke.decide(GeminiClient(config).value)
    withClue(s"completeStructuredOf failed: ${result.swap.toOption}")(result.isRight shouldBe true)
    result.value shouldBe a[SumTypeSmoke.Action.Refund]
  }
}
