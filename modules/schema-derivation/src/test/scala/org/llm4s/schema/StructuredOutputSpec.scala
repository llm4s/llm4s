package org.llm4s.schema

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.schema.Fixtures.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference

/** `completeStructuredOf` with a client that answers with a fixed reply and keeps what it was asked. */
class StructuredOutputSpec extends AnyFlatSpec with Matchers {

  final private class Scripted(reply: String) extends LLMClient {
    val asked = new AtomicReference[Option[CompletionOptions]](None)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      asked.set(Some(options))
      Right(Completion("id", 0L, reply, "test-model", AssistantMessage(reply)))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private val conversation = Conversation(Seq(UserMessage("Extract the invoice.")))
  private val invoiceJson =
    """{"vendor":"Acme Supplies Ltd","amount":1250.0,"currency":"GBP","description":"office furniture"}"""

  "completeStructuredOf" should "send the derived schema as the response format" in {
    val client = new Scripted(invoiceJson)
    client.completeStructuredOf[Invoice](conversation) shouldBe a[Right[?, ?]]

    client.asked.get.flatMap(_.responseFormat) match {
      case Some(ResponseFormat.JsonSchema(schema, _, strict)) =>
        strict shouldBe true
        ujson.write(schema) shouldBe ujson.write(SchemaOf[Invoice].toJsonSchema())
      case other => fail(s"expected a JSON schema response format, got $other")
    }
  }

  it should "read the reply into the case class" in {
    new Scripted(invoiceJson).completeStructuredOf[Invoice](conversation) shouldBe
      Right(Invoice("Acme Supplies Ltd", 1250.0, "GBP", "office furniture"))
  }

  it should "read a reply the model wrapped in a code fence, as completeStructured does" in {
    new Scripted(s"```json\n$invoiceJson\n```").completeStructuredOf[Invoice](conversation) shouldBe
      Right(Invoice("Acme Supplies Ltd", 1250.0, "GBP", "office furniture"))
  }

  it should "fail with a ValidationError for a reply that is not the type" in {
    val result = new Scripted("""{"vendor":"Acme"}""").completeStructuredOf[Invoice](conversation)
    result.left.map(_.getClass) shouldBe Left(classOf[ValidationError])
  }

  it should "read a nested type with optional and list fields" in {
    new Scripted("""{"a":3,"b":null,"c":"Blue"}""").completeStructuredOf[Optionals](conversation) shouldBe
      Right(Optionals(Some(3), None, Some(Color.Blue)))
  }

  it should "keep the options it is given" in {
    val client = new Scripted(invoiceJson)
    client.completeStructuredOf[Invoice](conversation, CompletionOptions().withTemperature(0.0))
    client.asked.get.map(_.temperature) shouldBe Some(0.0)
  }

  it should "refuse a type that is not a JSON object, without calling the model" in {
    val client = new Scripted("\"Red\"")
    val result = client.completeStructuredOf[Color](conversation)
    result.left.map(_.getClass) shouldBe Left(classOf[ValidationError])
    result.left.toOption.map(_.message).getOrElse("") should include("not a JSON object")
    client.asked.get shouldBe None
  }
}
