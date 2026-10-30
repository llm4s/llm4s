package org.llm4s.llmconnect.smoke

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.schema.*
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * What `OpenAIStructuredOutputSmokeSpec` and `GeminiStructuredOutputSmokeSpec` ask (#2327): `completeStructuredOf` with a sum type,
 * whose derived `anyOf` is sent wrapped in a `value` property and read back into its case.
 */
object SumTypeSmoke {

  enum Team derives SchemaOf, ReadWriter { case Billing, Shipping }

  @description("What the support bot decides to do")
  enum Action derives SchemaOf, ReadWriter {
    @description("Refund the customer") case Refund(amount: BigDecimal, reason: String)
    @description("Hand over to a human team") case Escalate(team: Team)
    case Reply(text: String)
  }

  private val conversation = Conversation(
    Seq(
      SystemMessage("You are a support bot. Decide what to do with the customer's message."),
      UserMessage("My parcel arrived broken, please refund the 25.50 I paid.")
    )
  )

  private val options = CompletionOptions().withMaxTokens(300).withTemperature(0.0)

  /** One small call: the model should decide on a refund. */
  def decide(client: LLMClient): Result[Action] = client.completeStructuredOf[Action](conversation, options)
}
