package org.llm4s.llmconnect

import org.llm4s.error.{ NetworkError, ValidationError }
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, ResponseFormat, UserMessage }
import org.llm4s.testutil.MockLLMClients.{ FailingMock, MultiResponseMock, SimpleMock }
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import upickle.default.{ macroRW, ReadWriter }

import StructuredOutputGuideSpec._

/**
 * The snippets of `docs/guide/structured-output.md`, compiled and run as written.
 *
 * If a snippet here stops compiling or an assertion fails, the guide is teaching something that no
 * longer works: change the guide and this spec together. Each `snippet` below is the code of one
 * block in the guide, and the assertions pin what the prose around it claims. No network is used:
 * the clients are the shared mocks.
 */
class StructuredOutputGuideSpec extends AnyWordSpec with Matchers with EitherValues with OptionValues {

  private val invoiceJson = """{"vendor":"Acme Supplies Ltd","amount":1250.0,"currency":"GBP"}"""

  /** A mock that also remembers the options of the last call. */
  private class RecordingMock(response: String) extends SimpleMock(response) {
    var lastOptions: Option[CompletionOptions] = None

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      lastOptions = Some(options)
      super.complete(conversation, options)
    }
  }

  // ---- 1. A minimal example

  private def extractInvoice(client: LLMClient, text: String): Result[Invoice] =
    client.completeStructured[Invoice](
      Conversation(Seq(UserMessage(s"Extract the invoice from this text:\n$text"))),
      invoiceSchema
    )

  // ---- 5. Options

  private def deterministic(client: LLMClient, text: String): Result[Invoice] =
    client.completeStructured[Invoice](
      Conversation(Seq(UserMessage(text))),
      invoiceSchema,
      CompletionOptions().withTemperature(0.0).withMaxTokens(256)
    )

  // ---- 6. A schema name, or non-strict mode: call `complete` yourself

  private def withCustomFormat(client: LLMClient, text: String): Result[Invoice] = {
    val format = ResponseFormat.JsonSchema(
      invoiceSchema.toJsonSchema(strict = false),
      name = "invoice",
      strict = false
    )
    for {
      completion <- client.complete(
        Conversation(Seq(UserMessage(text))),
        CompletionOptions().withResponseFormat(format)
      )
      // No fence or prose recovery on this path: the reply has to be bare JSON.
      invoice <- scala.util
        .Try(upickle.default.read[Invoice](completion.content))
        .toEither
        .left
        .map(e => ValidationError("invoice", e.getMessage))
    } yield invoice
  }

  // ---- 7. Errors

  private def describe(result: Result[Invoice]): String = result match {
    case Right(invoice)                                                     => s"ok: ${invoice.vendor}"
    case Left(error: ValidationError) if error.field == "structured_output" => s"model reply rejected: ${error.message}"
    case Left(error)                                                        => s"call failed: ${error.message}"
  }

  private def retryOnce(client: LLMClient, text: String): Result[Invoice] =
    extractInvoice(client, text) match {
      case Left(error: ValidationError) if error.field == "structured_output" => extractInvoice(client, text)
      case other                                                              => other
    }

  "completeStructured" should {

    "describe the type as a closed object schema with all declared properties (section 2)" in {
      val schema = invoiceSchema.toJsonSchema(strict = true)

      schema("type").str shouldBe "object"
      schema("additionalProperties").bool shouldBe false
      schema("properties").obj.keySet shouldBe Set("vendor", "amount", "currency")
      schema("properties")("amount")("type").str shouldBe "number"
    }

    "return the typed value for a reply that is JSON matching the schema (section 1)" in {
      extractInvoice(new SimpleMock(invoiceJson), "Acme invoice").value shouldBe
        Invoice("Acme Supplies Ltd", 1250.0, "GBP")
    }

    "recover JSON from a markdown fence or from surrounding prose (section 3)" in {
      val fenced   = "```json\n" + invoiceJson + "\n```"
      val prose    = s"Sure! Here is the invoice you asked for: $invoiceJson. Let me know if you need anything else."
      val expected = Invoice("Acme Supplies Ltd", 1250.0, "GBP")

      extractInvoice(new SimpleMock(fenced), "x").value shouldBe expected
      extractInvoice(new SimpleMock(prose), "x").value shouldBe expected
    }

    "send the schema as a JsonSchema format, with every property required (section 4)" in {
      val client = new RecordingMock(invoiceJson)
      extractInvoice(client, "x").isRight shouldBe true

      val format = client.lastOptions.value.responseFormat.value
      format shouldBe ResponseFormat.JsonSchema(invoiceSchema.toJsonSchema(strict = true))
      // The defaults `completeStructured` leaves on the format:
      val js = format.asInstanceOf[ResponseFormat.JsonSchema]
      js.name shouldBe "response"
      js.strict shouldBe true
    }

    "make every property required, including one declared optional (section 4)" in {
      val strict    = optionalNoteSchema.toJsonSchema(strict = true)("required").arr.map(_.str).toSet
      val nonStrict = optionalNoteSchema.toJsonSchema(strict = false)("required").arr.map(_.str).toSet

      strict shouldBe Set("vendor", "note")
      nonStrict shouldBe Set("vendor")
    }

    "keep the caller's options and override only responseFormat (section 5)" in {
      val client = new RecordingMock(invoiceJson)
      val options =
        CompletionOptions().withTemperature(0.0).withMaxTokens(256).withResponseFormat(ResponseFormat.Json)

      client
        .completeStructured[Invoice](Conversation(Seq(UserMessage("x"))), invoiceSchema, options)
        .isRight shouldBe true

      val seen = client.lastOptions.value
      seen.temperature shouldBe 0.0
      seen.maxTokens shouldBe Some(256)
      seen.responseFormat shouldBe Some(ResponseFormat.JsonSchema(invoiceSchema.toJsonSchema(strict = true)))

      deterministic(new SimpleMock(invoiceJson), "x").isRight shouldBe true
    }

    "let you choose the schema name and strictness by calling complete directly (section 6)" in {
      val client = new RecordingMock(invoiceJson)

      withCustomFormat(client, "x").value shouldBe Invoice("Acme Supplies Ltd", 1250.0, "GBP")

      val js = client.lastOptions.value.responseFormat.value.asInstanceOf[ResponseFormat.JsonSchema]
      js.name shouldBe "invoice"
      js.strict shouldBe false
      js.schema("required").arr.map(_.str).toSet shouldBe Set("vendor", "amount", "currency")

      // ... and on that path a fenced reply is not recovered.
      withCustomFormat(new SimpleMock("```json\n" + invoiceJson + "\n```"), "x").isLeft shouldBe true
    }
  }

  "a reply that cannot become the type" should {

    "be a ValidationError on structured_output, for text that is not JSON (section 7)" in {
      val error = extractInvoice(new SimpleMock("I'm sorry, I can't help with that."), "x").left.value
      error shouldBe a[ValidationError]
      error.asInstanceOf[ValidationError].field shouldBe "structured_output"
      error.message should include("not valid JSON")
    }

    "be a ValidationError for a JSON null (section 7)" in {
      val error = extractInvoice(new SimpleMock("null"), "x").left.value
      error.asInstanceOf[ValidationError].field shouldBe "structured_output"
      error.message should include("got JSON null")
    }

    "be a ValidationError for JSON of the wrong shape, or with a field missing (section 7)" in {
      val missing = extractInvoice(new SimpleMock("""{"vendor":"Acme"}"""), "x").left.value
      val array   = extractInvoice(new SimpleMock("[1, 2, 3]"), "x").left.value

      missing.asInstanceOf[ValidationError].field shouldBe "structured_output"
      missing.message should include("does not match expected schema")
      array.asInstanceOf[ValidationError].field shouldBe "structured_output"
      array.message should include("does not match expected schema")
    }

    "deserialise rather than validate: a reply with a key the schema forbids is still Right (section 7)" in {
      invoiceSchema.toJsonSchema(strict = true)("additionalProperties").bool shouldBe false
      val extra = """{"vendor":"Acme","amount":1.0,"currency":"GBP","note":"not in the schema"}"""
      extractInvoice(new SimpleMock(extra), "x").value shouldBe Invoice("Acme", 1.0, "GBP")
    }

    "not be reported when the provider call itself fails: that error comes back unchanged (section 7)" in {
      val error = extractInvoice(new FailingMock("connection refused"), "x").left.value
      error shouldBe a[NetworkError]
    }

    "be told apart from a failed call by the pattern in section 7" in {
      describe(extractInvoice(new SimpleMock(invoiceJson), "x")) shouldBe "ok: Acme Supplies Ltd"
      describe(extractInvoice(new SimpleMock("nope"), "x")) should startWith("model reply rejected: ")
      describe(extractInvoice(new FailingMock("down"), "x")) should startWith("call failed: ")
    }

    "not be retried by the reliability rule, because a ValidationError is not recoverable (section 7)" in {
      val error = extractInvoice(new SimpleMock("nope"), "x").left.value

      error shouldBe a[org.llm4s.error.NonRecoverableError]
      org.llm4s.reliability.RetryPolicy.isTransient(error) shouldBe false
    }

    "succeed on a second try when the first reply was unusable (section 7)" in {
      retryOnce(new MultiResponseMock(Seq("Sorry, no JSON today.", invoiceJson)), "x").value shouldBe
        Invoice("Acme Supplies Ltd", 1250.0, "GBP")
    }
  }
}

object StructuredOutputGuideSpec {

  final case class Invoice(vendor: String, amount: Double, currency: String)
  object Invoice { implicit val rw: ReadWriter[Invoice] = macroRW }

  val invoiceSchema =
    Schema
      .`object`[Invoice]("An invoice extracted from text")
      .withRequiredField("vendor", Schema.string("Name of the vendor or supplier"))
      .withRequiredField("amount", Schema.number("Total invoice amount as a decimal number"))
      .withRequiredField("currency", Schema.string("ISO 4217 currency code, e.g. USD, EUR, GBP"))

  val optionalNoteSchema =
    Schema
      .`object`[Map[String, String]]("A note")
      .withRequiredField("vendor", Schema.string("Vendor"))
      .withProperty(Schema.property("note", Schema.string("Free-text note"), required = false))
}
