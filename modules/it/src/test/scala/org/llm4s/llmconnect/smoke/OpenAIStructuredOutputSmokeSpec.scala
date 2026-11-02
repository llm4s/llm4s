package org.llm4s.llmconnect.smoke

import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.provider.OpenAIClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke test for a sum type on OpenAI (#2327): strict mode accepts the derived `anyOf`, wrapped below the
 * root, and the reply reads back into the right case. One small call; only the case is asserted.
 *
 * Requires: `OPENAI_API_KEY`. Tier: `@Cloud` - `sbt testSmoke`.
 */
@Cloud
class OpenAIStructuredOutputSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty)

  "completeStructuredOf on OpenAI" should "return a sum type in strict mode" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")
    val config = OpenAIConfig
      .fromValues(
        modelName = "gpt-4o-mini",
        apiKey = apiKey.get,
        organization = None,
        baseUrl = "https://api.openai.com/v1"
      )
      .value
    val result = SumTypeSmoke.decide(OpenAIClient(config).value)
    withClue(s"completeStructuredOf failed: ${result.swap.toOption}")(result.isRight shouldBe true)
    result.value shouldBe a[SumTypeSmoke.Action.Refund]
  }
}
