package org.llm4s.samples.cookbook

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, UserMessage }
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import upickle.default.ReadWriter

/** A support ticket, as the model is asked to produce it. */
final case class Ticket(category: String, urgency: Int, summary: String) derives ReadWriter

/**
 * Recipe: classify free text into a typed value.
 *
 * `completeStructured` sends a JSON schema with the request and reads the reply into a case class, so the rest of the
 * program deals in a `Ticket` and never in a string. The category is an enum in the schema, and the recipe checks it
 * again on the way out, because only some providers enforce a schema while generating.
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.cookbook.StructuredOutputRecipe"          # scripted client, no API key
 *   sbt "samples/runMain org.llm4s.samples.cookbook.StructuredOutputRecipe --live"   # your configured provider
 * }}}
 */
object StructuredOutputRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "structured-output",
    title = "Classify text into a typed value",
    summary = "Turn a customer message into a Ticket case class with completeStructured.",
    mainClass = "org.llm4s.samples.cookbook.StructuredOutputRecipe"
  )

  val categories: Seq[String] = Seq("billing", "bug", "question")

  val message: String = "I was charged twice for my March invoice and need the second payment refunded today."

  // snippet:start
  val ticketSchema = Schema
    .`object`[Ticket]("A customer support ticket")
    .withRequiredField("category", Schema.string("The kind of ticket").withEnum(categories))
    .withRequiredField("urgency", Schema.integer("From 1 (can wait) to 5 (urgent)").withRange(Some(1), Some(5)))
    .withRequiredField("summary", Schema.string("One sentence describing the problem"))

  def classify(client: LLMClient, text: String): Result[Ticket] =
    for {
      ticket <- client.completeStructured[Ticket](Conversation(Seq(UserMessage(text))), ticketSchema)
      _ <- Either.cond(
        categories.contains(ticket.category),
        (),
        ValidationError.invalid("category", s"'${ticket.category}' is not one of ${categories.mkString(", ")}")
      )
      // The schema states the range, but a provider that ignores it can still answer outside it.
      _ <- Either.cond(
        1 <= ticket.urgency && ticket.urgency <= 5,
        (),
        ValidationError.invalid("urgency", s"${ticket.urgency} is outside 1 to 5")
      )
    } yield ticket
  // snippet:end

  /** Answers with the JSON a model following the schema would produce. */
  def script: ScriptedClient = new ScriptedClient((_, _) =>
    Right(
      AssistantMessage("""{"category": "billing", "urgency": 4, "summary": "Charged twice for the March invoice."}""")
    )
  )

  def demo(client: LLMClient): Result[String] =
    classify(client, message).map(t => s"category=${t.category} urgency=${t.urgency} summary=${t.summary}")
}
