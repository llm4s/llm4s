package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.toolapi.Schema
import upickle.default.{ macroRW, ReadWriter }

/**
 * Demonstrates native structured output via [[org.llm4s.llmconnect.LLMClient.completeStructured]].
 *
 * The provider is asked to respond with JSON that conforms to the `Invoice` schema.
 * OpenAI and Gemini enforce the schema at generation time; Anthropic falls back to
 * system-message injection (same guarantee at the prompt level).
 *
 * Run with:
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.basic.StructuredOutputExample"
 * }}}
 */
object StructuredOutputExample extends App {

  case class Invoice(vendor: String, amount: Double, currency: String, description: String)
  object Invoice { implicit val rw: ReadWriter[Invoice] = macroRW }

  val invoiceSchema = Schema
    .`object`[Invoice]("An invoice extracted from text")
    .withRequiredField("vendor", Schema.string("Name of the vendor or supplier"))
    .withRequiredField("amount", Schema.number("Total invoice amount as a decimal number"))
    .withRequiredField("currency", Schema.string("ISO 4217 currency code, e.g. USD, EUR, GBP"))
    .withRequiredField("description", Schema.string("Brief description of goods or services"))

  val conversation = Conversation(
    Seq(
      UserMessage(
        """Extract the invoice details from this text:
          |
          |"Please find attached invoice INV-2024-0042 from Acme Supplies Ltd
          | for office furniture delivered on 5 Jan 2024. Total due: £1,250.00 GBP.
          | Items: 4x ergonomic chairs, 2x standing desks."
          |""".stripMargin
      )
    )
  )

  val result = for {
    providerConfig <- Llm4sConfig.provider()
    client         <- LLMConnect.getClient(providerConfig)
    invoice        <- client.completeStructured[Invoice](conversation, invoiceSchema)
  } yield invoice

  result match {
    case Right(invoice) =>
      println("Extracted invoice:")
      println(s"  Vendor:      ${invoice.vendor}")
      println(s"  Amount:      ${invoice.amount} ${invoice.currency}")
      println(s"  Description: ${invoice.description}")
    case Left(error) =>
      println(s"Error: ${error.message}")
      sys.exit(1)
  }
}
