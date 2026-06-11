package org.llm4s.llmconnect

import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ ObjectSchema, Schema }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, ReadWriter }

class StructuredOutputSpec extends AnyFlatSpec with Matchers {

  case class Invoice(vendor: String, amount: Double)
  object Invoice { implicit val rw: ReadWriter[Invoice] = macroRW }

  val invoiceSchema: ObjectSchema[Invoice] = Schema
    .`object`[Invoice]("An invoice")
    .withRequiredField("vendor", Schema.string("Vendor name"))
    .withRequiredField("amount", Schema.number("Invoice amount"))

  val conversation: Conversation = Conversation(Seq(UserMessage("Extract invoice")))

  def makeCompletion(content: String): Completion =
    Completion(
      id = "test-id",
      created = 0L,
      content = content,
      model = "test-model",
      message = AssistantMessage(Some(content))
    )

  class StubClient(stubResponse: org.llm4s.types.Result[Completion]) extends LLMClient {
    var lastOptions: CompletionOptions = CompletionOptions()

    def complete(conversation: Conversation, options: CompletionOptions): org.llm4s.types.Result[Completion] = {
      lastOptions = options
      stubResponse
    }
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): org.llm4s.types.Result[Completion] =
      stubResponse
    def getContextWindow(): Int     = 4096
    def getReserveCompletion(): Int = 512
  }

  "completeStructured" should "parse valid JSON into the target type" in {
    val json   = """{"vendor":"Acme","amount":99.99}"""
    val stub   = new StubClient(Right(makeCompletion(json)))
    val result = stub.completeStructured[Invoice](conversation, invoiceSchema)
    result shouldBe Right(Invoice("Acme", 99.99))
  }

  it should "set ResponseFormat.JsonSchema on the options" in {
    val json = """{"vendor":"Acme","amount":1.0}"""
    val stub = new StubClient(Right(makeCompletion(json)))
    stub.completeStructured[Invoice](conversation, invoiceSchema)
    stub.lastOptions.responseFormat shouldBe a[Some[_]]
    stub.lastOptions.responseFormat.get shouldBe a[ResponseFormat.JsonSchema]
  }

  it should "forward caller-supplied temperature alongside the response format" in {
    val json = """{"vendor":"X","amount":0.0}"""
    val opts = CompletionOptions(temperature = 0.2)
    val stub = new StubClient(Right(makeCompletion(json)))
    stub.completeStructured[Invoice](conversation, invoiceSchema, opts)
    stub.lastOptions.temperature shouldBe 0.2
  }

  it should "return ValidationError when the response is not valid JSON" in {
    val stub   = new StubClient(Right(makeCompletion("not json at all")))
    val result = stub.completeStructured[Invoice](conversation, invoiceSchema)
    result.isLeft shouldBe true
    result.swap.foreach(err => err.message should include("not valid JSON"))
  }

  it should "return ValidationError when JSON does not match the expected schema" in {
    val stub   = new StubClient(Right(makeCompletion("""{"wrong":true}""")))
    val result = stub.completeStructured[Invoice](conversation, invoiceSchema)
    result.isLeft shouldBe true
    result.swap.foreach(err => err.message should include("expected schema"))
  }

  it should "propagate provider failures unchanged" in {
    import org.llm4s.error.AuthenticationError
    val stub   = new StubClient(Left(AuthenticationError("test", "bad key")))
    val result = stub.completeStructured[Invoice](conversation, invoiceSchema)
    result shouldBe Left(AuthenticationError("test", "bad key"))
  }

  it should "tolerate extra JSON fields not in the schema" in {
    val json   = """{"vendor":"Acme","amount":5.0,"extra":"ignored"}"""
    val stub   = new StubClient(Right(makeCompletion(json)))
    val result = stub.completeStructured[Invoice](conversation, invoiceSchema)
    result shouldBe Right(Invoice("Acme", 5.0))
  }
}
