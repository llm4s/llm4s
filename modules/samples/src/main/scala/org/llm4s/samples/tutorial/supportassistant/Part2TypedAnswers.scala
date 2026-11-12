package org.llm4s.samples.tutorial.supportassistant

import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

/**
 * Part 2 of the "Build a support assistant" tutorial (`docs/tutorials/support-assistant/part-2-typed-answers.md`):
 * classify a customer's message into a typed `Ticket` with a derived schema.
 *
 * Each `// snippet:` region is a Scala block of the page, word for word; `Part2Spec` runs the code and compares the
 * regions with the page.
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part2TypedAnswers"          # scripted, no key
 *   sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part2TypedAnswers --live"   # your provider
 * }}}
 */
object Part2TypedAnswers {

  // format: off
  // snippet: ticket
  import org.llm4s.schema.*
  import upickle.default.ReadWriter

  enum Category derives SchemaOf, ReadWriter {
    case Billing, Delivery, Returns, Other
  }

  @description("A customer's message to the support team, classified")
  case class Ticket(
    @description("What the message is about") category: Category,
    @description("From 1 (can wait) to 5 (urgent)") urgency: Int,
    @description("The order number, for example A-1001, if the message gives one") orderId: Option[String],
    @description("The problem in one sentence") summary: String
  ) derives SchemaOf, ReadWriter
  // end snippet
  // format: on

  // snippet: classify
  import org.llm4s.llmconnect.LLMClient
  import org.llm4s.llmconnect.model.{ Conversation, SystemMessage, UserMessage }
  import org.llm4s.types.Result

  def classify(client: LLMClient, message: String): Result[Ticket] =
    client.completeStructuredOf[Ticket](
      Conversation(Seq(SystemMessage("Classify this message to Acme Shop's support team."), UserMessage(message)))
    )
  // end snippet

  // snippet: triage
  import org.llm4s.error.ValidationError

  def checked(ticket: Ticket): Result[Ticket] =
    Either.cond(
      1 <= ticket.urgency && ticket.urgency <= 5,
      ticket,
      ValidationError.invalid("urgency", s"${ticket.urgency} is outside 1 to 5")
    )

  def triage(client: LLMClient, message: String): Result[Ticket] =
    classify(client, message).flatMap(checked)
  // end snippet

  val message: String = "My order A-1001 was charged twice and I need the second payment back today."

  /** The model this part's demo talks to when it runs without `--live`. */
  def scripted(): ScriptedLLMClient =
    ScriptedLLMClient.sequence(
      Reply.text(
        """{"category": "Billing", "urgency": 4, "orderId": "A-1001", "summary": "Charged twice for order A-1001."}"""
      )
    )

  def main(args: Array[String]): Unit =
    TutorialRunner.run(args, scripted())(client => triage(client, message).map(_.toString))
}
