package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.schema.*
import upickle.default.ReadWriter

/**
 * Demonstrates native structured output via [[org.llm4s.llmconnect.LLMClient.completeStructured]], with the
 * schema derived from the `Invoice` case class by [[org.llm4s.schema.SchemaOf]] instead of written by hand.
 *
 * The provider is asked to respond with JSON that conforms to the `Invoice` schema.
 * OpenAI and Gemini enforce the schema at generation time; Anthropic falls back to
 * system-message injection (a best-effort prompt-level instruction; not schema-enforced).
 *
 * Run with:
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.basic.StructuredOutputExample"
 * }}}
 */
object StructuredOutputExample extends App {

  // The schema is derived from the case class (llm4s-schema-derivation), so the type is written once.
  @description("An invoice extracted from text")
  case class Invoice(
    @description("Name of the vendor or supplier") vendor: String,
    @description("Total invoice amount as a decimal number") amount: Double,
    @description("ISO 4217 currency code, e.g. USD, EUR, GBP") currency: String,
    @description("Brief description of goods or services") description: String
  ) derives SchemaOf,
        ReadWriter

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
    providerConfig  <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client  <- LLMConnect.getClient(providerConfig)
    invoice <- client.completeStructuredOf[Invoice](conversation)
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
