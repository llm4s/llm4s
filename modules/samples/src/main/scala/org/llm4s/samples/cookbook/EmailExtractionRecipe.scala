package org.llm4s.samples.cookbook

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, SystemMessage, UserMessage }
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import upickle.default.ReadWriter

final case class LineItem(product: String, quantity: Int) derives ReadWriter

/** What a customer asked for in an email, as the model is asked to produce it. */
final case class OrderRequest(customer: String, orderNumber: String, items: Seq[LineItem], deliverBy: String)
    derives ReadWriter

/** Recipe: extract structured data, with a nested list, from an email; then check it against the email. */
object EmailExtractionRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "email-extraction",
    title = "Extract structured data from an email",
    summary = "Read an order request out of a free-text email into a case class with a list of line items.",
    mainClass = "org.llm4s.samples.cookbook.EmailExtractionRecipe"
  )

  val email: String =
    """From: Dana Whitfield <dana@harbour-cafe.example>
      |Subject: Re: order HC-2291
      |
      |Hi, could you change order HC-2291 to 12 bags of the house blend and 3 boxes of oat milk?
      |We need it by 14 November 2026 at the latest. Thanks, Dana (Harbour Cafe)""".stripMargin

  val orderSchema = Schema
    .`object`[OrderRequest]("An order request taken from an email")
    .withRequiredField("customer", Schema.string("The business that sent the email"))
    .withRequiredField("orderNumber", Schema.string("The order number exactly as written in the email"))
    .withRequiredField(
      "items",
      Schema.array(
        "Every product asked for",
        Schema
          .`object`[LineItem]("One product and how many")
          .withRequiredField("product", Schema.string("The product name"))
          .withRequiredField("quantity", Schema.integer("How many").withRange(Some(1), None))
      )
    )
    .withRequiredField("deliverBy", Schema.string("The latest delivery date as YYYY-MM-DD, or \"\" if none is given"))

  def extract(client: LLMClient, email: String): Result[OrderRequest] = {
    val prompt = Conversation(
      Seq(SystemMessage("Extract the order request from the email. Use only what the email says."), UserMessage(email))
    )
    for {
      order <- client.completeStructured[OrderRequest](prompt, orderSchema)
      // A model can produce a well-formed value that is not in the text: check what you can against the source.
      _ <- Either.cond(
        email.contains(order.orderNumber),
        (),
        ValidationError.invalid("orderNumber", s"'${order.orderNumber}' does not appear in the email")
      )
      _ <- Either.cond(order.items.nonEmpty, (), ValidationError.invalid("items", "the email asks for nothing"))
    } yield order
  }

  /** Answers with the JSON a model following the schema would produce for [[email]]. */
  def script: ScriptedClient = new ScriptedClient((_, _) =>
    Right(AssistantMessage("""{"customer": "Harbour Cafe", "orderNumber": "HC-2291", "deliverBy": "2026-11-14",
      |"items": [{"product": "house blend", "quantity": 12}, {"product": "oat milk", "quantity": 3}]}""".stripMargin))
  )

  def demo(client: LLMClient): Result[String] =
    extract(client, email).map { order =>
      val items = order.items.map(item => s"${item.quantity} x ${item.product}").mkString(", ")
      s"${order.customer}, order ${order.orderNumber}: $items, deliver by ${order.deliverBy}"
    }
}
