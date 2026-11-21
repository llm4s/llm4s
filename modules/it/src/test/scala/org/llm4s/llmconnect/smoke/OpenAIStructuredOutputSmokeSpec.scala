package org.llm4s.llmconnect.smoke

import org.llm4s.security.Secret

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
 * root, and the reply reads back into the right case. One small call; only the case is asserted. And
 * `streamStructuredOf` (#2329): the derived schema streamed, partial values seen before the whole one.
 *
 * Requires: `OPENAI_API_KEY`. Tier: `@Cloud` - `sbt testSmoke`.
 */
@Cloud
class OpenAIStructuredOutputSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty)

  private def client: OpenAIClient = {
    val config = OpenAIConfig
      .fromValues(
        modelName = "gpt-4o-mini",
        apiKey = Secret(apiKey.get),
        organization = None,
        baseUrl = "https://api.openai.com/v1"
      )
      .value
    OpenAIClient(config).value
  }

  "completeStructuredOf on OpenAI" should "return a sum type in strict mode" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")
    val result = SumTypeSmoke.decide(client)
    withClue(s"completeStructuredOf failed: ${result.swap.toOption}")(result.isRight shouldBe true)
    result.value shouldBe a[SumTypeSmoke.Action.Refund]
  }

  "streamStructuredOf on OpenAI" should "show partial values while it streams, then return the whole value" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")
    val (result, partials) = StreamingSmoke.extract(client)
    withClue(s"streamStructuredOf failed: ${result.swap.toOption}")(result.isRight shouldBe true)
    result.value.items should not be empty
    // several partial values, so the object was parsed while it streamed, not only at the end
    partials should be > 1
  }
}

/** What `streamStructuredOf` is asked on OpenAI (#2329): a small invoice with a list of line items. */
object StreamingSmoke {
  import org.llm4s.llmconnect.LLMClient
  import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
  import org.llm4s.schema.*
  import org.llm4s.types.Result
  import upickle.default.ReadWriter

  case class LineItem(sku: String, quantity: Int) derives SchemaOf, ReadWriter
  case class Invoice(vendor: String, items: List[LineItem]) derives SchemaOf, ReadWriter

  private val conversation = Conversation(
    Seq(UserMessage("Extract the invoice: Acme Ltd sold us 2 x DESK-1 and 4 x CHAIR-7."))
  )

  /** One small streamed call, and how many partial values it showed. */
  def extract(client: LLMClient): (Result[Invoice], Int) = {
    var partials = 0
    val result =
      client.streamStructuredOf[Invoice](conversation, CompletionOptions().withMaxTokens(200).withTemperature(0.0))(_ =>
        partials += 1
      )
    (result, partials)
  }
}
