package org.llm4s.samples.basic

// The build compiles with -source:3.3, which turns off field access by name on named tuples; Scala 3.7's default
// source level, which applications have, has it.
import scala.language.`3.7`

import org.llm4s.llmconnect.model.Conversation
import org.llm4s.schema.*
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import upickle.default.ReadWriter

/**
 * Structured output, streamed: `streamStructuredOf[A]` prints the invoice as it takes shape, then returns it whole.
 *
 * The partial value is a named tuple with the invoice's field names, each `None` until the model starts writing it:
 * the vendor grows a few characters at a time, line items appear as soon as their object opens, and a number is
 * printed only once complete. The model is a `ScriptedLLMClient` from `llm4s-testkit` that streams a fixed reply in
 * chunks of 8 characters, so the example needs no API key and no network. The guide is
 * `docs/guide/structured-output.md#10-streaming-structured-output`.
 *
 * {{{
 * sbt "samples/runMain org.llm4s.samples.basic.StreamingStructuredOutputExample"
 * }}}
 */
object StreamingStructuredOutputExample extends App {

  case class LineItem(sku: String, quantity: Int, price: BigDecimal) derives SchemaOf, ReadWriter
  case class Invoice(vendor: String, currency: String, items: List[LineItem]) derives SchemaOf, ReadWriter

  private val reply =
    """{"vendor": "Acme Supplies Ltd", "currency": "GBP", "items": [
      |  {"sku": "DESK-120", "quantity": 2, "price": "349.00"},
      |  {"sku": "CHAIR-7", "quantity": 4, "price": "129.50"}
      |]}""".stripMargin

  private val model = ScriptedLLMClient.always(Reply.text(reply)).withChunkSize(8)

  private val result = for {
    conversation <- Conversation.fromPrompts(
      "Extract the invoice as JSON.",
      "Acme: 2 desks at 349, 4 chairs at 129.50."
    )
    invoice <- model.streamStructuredOf[Invoice](conversation) { p =>
      val rows = p.items.getOrElse(Nil).map { item =>
        s"${item.sku.getOrElse("…")} x ${item.quantity.fold("?")(_.toString)} @ ${item.price.fold("?")(_.toString)}"
      }
      println(s"${p.vendor.getOrElse("…")} (${p.currency.getOrElse("…")}): ${rows.mkString("; ")}")
    }
  } yield invoice

  println(s"Result: $result")
}
