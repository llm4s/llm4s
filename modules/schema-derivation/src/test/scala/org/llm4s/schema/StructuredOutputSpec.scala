package org.llm4s.schema

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.schema.Fixtures.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference
import java.time.Instant

/** `completeStructuredOf` with a client that answers with a fixed reply and keeps what it was asked. */
class StructuredOutputSpec extends AnyFlatSpec with Matchers {

  final private class Scripted(reply: String) extends LLMClient {
    val asked = new AtomicReference[Option[CompletionOptions]](None)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      asked.set(Some(options))
      Right(Completion("id", Instant.EPOCH, reply, "test-model", AssistantMessage(reply)))
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

  it should "refuse a schema that cannot be built, a recursion through a hand-written given, without calling the model" in {
    import RecursionFixtures.DerivesAndHelper.P
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.duration.*
    // The model is never called, so the reader is never used.
    given upickle.default.Reader[P] = upickle.default.reader[ujson.Value].map(_ => P(None))
    val client                      = new Scripted("{}")
    val result =
      scala.concurrent.Await.result(scala.concurrent.Future(client.completeStructuredOf[P](conversation)), 30.seconds)
    result.left.map(_.getClass) shouldBe Left(classOf[ValidationError])
    result.left.toOption.map(_.message).getOrElse("") should include("recursive type P")
    client.asked.get shouldBe None
  }

  // ---- sum types (#2327)

  private def sentSchema(client: Scripted): ujson.Value =
    client.asked.get.flatMap(_.responseFormat) match {
      case Some(ResponseFormat.JsonSchema(schema, _, strict)) =>
        strict shouldBe true
        schema
      case other => fail(s"expected a JSON schema response format, got $other")
    }

  "completeStructuredOf for a sum type" should "send the anyOf wrapped in a value property, and unwrap the reply" in {
    import SumFixtures.*
    val client = new Scripted("""{"value":{"$type":"Refund","amount":"12.50","reason":"broken"}}""")
    client.completeStructuredOf[Action](conversation) shouldBe Right(Action.Refund(BigDecimal("12.50"), "broken"))

    val schema = sentSchema(client)
    ujson.write(schema) shouldBe ujson.write(StructuredOutput.wrap(SchemaOf[Action].toJsonSchema()))
    schema("type") shouldBe ujson.Str("object")
    schema("required") shouldBe ujson.Arr("value")
    schema("additionalProperties") shouldBe ujson.False
    schema("properties")("value")("anyOf").arr should have size 3
  }

  it should "read each case, a singleton, a custom discriminator and a fenced reply" in {
    import SumFixtures.*
    new Scripted("""{"value":{"$type":"Escalate","team":"Billing"}}""")
      .completeStructuredOf[Action](conversation) shouldBe
      Right(Action.Escalate(Team.Billing))
    new Scripted("""{"value":"Free"}""").completeStructuredOf[Payment](conversation) shouldBe Right(Payment.Free)
    new Scripted("""{"value":{"kind":"click","x":1,"y":2}}""").completeStructuredOf[Event](conversation) shouldBe
      Right(Click(1, 2))
    new Scripted("Here it is:\n```json\n{\"value\":{\"$type\":\"Reply\",\"text\":\"hi\"}}\n```")
      .completeStructuredOf[Action](conversation) shouldBe Right(Action.Reply("hi"))
    new Scripted("""{"value":null}""").completeStructuredOf[Option[Action]](conversation) shouldBe Right(None)
  }

  it should "fail with a ValidationError for a reply without the wrapper, of an unknown case, not JSON or null" in {
    import SumFixtures.*
    for (
      reply <- List(
        """{"$type":"Reply","text":"hi"}""",
        """{"value":{"$type":"Shout","text":"hi"}}""",
        """{"value":{"$type":"Reply"}}""",
        "not json at all",
        "null",
        "[1]"
      )
    ) withClue(reply) {
      val result = new Scripted(reply).completeStructuredOf[Action](conversation)
      result.left.map(_.getClass) shouldBe Left(classOf[ValidationError])
      result.left.toOption.map(_.message).getOrElse("") should include("Response")
    }
  }

  "completeStructuredOf for a case class holding sum types" should "send its JSON schema unwrapped and read the reply" in {
    import SumFixtures.*
    val reply  = """{"title":"t","shapes":[{"$type":"Circle","radius":1},"Dot"],"highlight":null,"last":"idle"}"""
    val client = new Scripted(reply)
    client.completeStructuredOf[Drawing](conversation) shouldBe Right(Drawing("t", List(Circle(1), Dot), None, Idle))
    ujson.write(sentSchema(client)) shouldBe ujson.write(SchemaOf[Drawing].toJsonSchema())
    new Scripted("""{"title":"t"}""").completeStructuredOf[Drawing](conversation).left.map(_.getClass) shouldBe
      Left(classOf[ValidationError])
  }

  "A sum type's core schema" should "be refused with a message, since core's model has no union" in {
    import SumFixtures.*
    val error = intercept[UnsupportedOperationException](SchemaOf[Action].definition)
    error.getMessage should include("Action is a sum type with fields")
    error.getMessage should include("anyOf")
    intercept[UnsupportedOperationException](SchemaOf[Drawing].definition).getMessage should include("Shape")
  }
}
