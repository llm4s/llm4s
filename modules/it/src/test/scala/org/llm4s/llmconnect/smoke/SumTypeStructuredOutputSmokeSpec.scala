package org.llm4s.llmconnect.smoke

import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ ContextWindowResolver, GeminiConfig, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.llmconnect.provider.{ GeminiClient, OpenAIClient }
import org.llm4s.model.ModelRegistryService
import org.llm4s.schema.*
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

/**
 * Cloud smoke tests for `completeStructuredOf` with a sum type (#2327): the derived `anyOf`, wrapped in a `value`
 * property, is accepted by OpenAI's strict mode and by Gemini, and the reply reads back into the right case. One
 * small call per provider. Only the case is asserted, not the model's wording.
 *
 * Requires: `OPENAI_API_KEY`, `GEMINI_API_KEY` (each test skips without its key). Tier: `@Cloud` - `sbt testSmoke`.
 */
@Cloud
class SumTypeStructuredOutputSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  import SumTypeStructuredOutputSmokeSpec.*

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private def key(name: String): Option[String] = Option(System.getenv(name)).filter(_.nonEmpty)

  private val conversation = Conversation(
    Seq(
      SystemMessage("You are a support bot. Decide what to do with the customer's message."),
      UserMessage("My parcel arrived broken, please refund the 25.50 I paid.")
    )
  )

  private val options = CompletionOptions().withMaxTokens(300).withTemperature(0.0)

  private def decide(client: LLMClient): Unit = {
    val result = client.completeStructuredOf[Action](conversation, options)
    withClue(s"completeStructuredOf failed: ${result.swap.toOption}")(result.isRight shouldBe true)
    result.value shouldBe a[Action.Refund]
  }

  "completeStructuredOf on OpenAI" should "return a sum type in strict mode" in {
    Tier.require(key("OPENAI_API_KEY").isDefined, "OPENAI_API_KEY not set")
    val config = OpenAIConfig
      .fromValues(
        modelName = "gpt-4o-mini",
        apiKey = key("OPENAI_API_KEY").get,
        organization = None,
        baseUrl = "https://api.openai.com/v1"
      )
      .value
    decide(OpenAIClient(config).value)
  }

  "completeStructuredOf on Gemini" should "return a sum type" in {
    Tier.require(key("GEMINI_API_KEY").isDefined, "GEMINI_API_KEY not set")
    val config = GeminiConfig
      .fromValues(
        modelName = "gemini-2.5-flash",
        apiKey = key("GEMINI_API_KEY").get,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta"
      )
      .value
    decide(GeminiClient(config).value)
  }
}

object SumTypeStructuredOutputSmokeSpec {
  enum Team derives SchemaOf, ReadWriter { case Billing, Shipping }

  @description("What the support bot decides to do")
  enum Action derives SchemaOf, ReadWriter {
    @description("Refund the customer") case Refund(amount: BigDecimal, reason: String)
    @description("Hand over to a human team") case Escalate(team: Team)
    case Reply(text: String)
  }
}
