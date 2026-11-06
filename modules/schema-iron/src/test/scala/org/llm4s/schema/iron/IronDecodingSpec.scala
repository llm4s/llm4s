package org.llm4s.schema.iron

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ Conversation, ResponseFormat, UserMessage }
import org.llm4s.schema.*
import org.llm4s.schema.iron.IronFixtures.*
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ ToolCallError, ToolParameterError }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A reply or a tool call that breaks a refinement is refused with the field and the bound, and one that keeps it
 * decodes into the refined type.
 */
class IronDecodingSpec extends AnyFlatSpec with Matchers {

  private val conversation = Conversation(Seq(UserMessage("Review the product.")))

  private def reply(json: String): ScriptedLLMClient = ScriptedLLMClient.always(Reply.text(json))

  private val review = Review("Great kettle", 4, List("kitchen"))

  private val book: Either[String, org.llm4s.toolapi.ToolFunction[Booking, Confirmation]] =
    Tool
      .of[Booking, Confirmation]("book", "Books a stay")(b => Right(Confirmation(s"${b.guest}-${b.nights}")))
      .left
      .map(_.message)

  "completeStructuredOf" should "decode a reply within every bound into the refined type" in {
    val client = reply("""{"title":"Great kettle","rating":4,"tags":["kitchen"]}""")
    client.completeStructuredOf[Review](conversation) shouldBe Right(review)
    // the bounds were sent to the model
    client.lastCall.flatMap(_.options.responseFormat) match {
      case Some(format: ResponseFormat.JsonSchema) =>
        format.schema("properties")("rating")("maximum") shouldBe ujson.Num(5)
      case other => fail(s"expected a JSON Schema response format, got $other")
    }
  }

  it should "return a Left naming the field and the bound for a reply that breaks one" in {
    val result = reply("""{"title":"Great kettle","rating":7,"tags":["kitchen"]}""").completeStructuredOf[Review](
      conversation
    )
    result match {
      case Left(e: ValidationError) =>
        e.message should include("rating")
        e.message should include("Should be included in [1, 5]")
      case other => fail(s"expected a ValidationError, got $other")
    }
  }

  it should "refuse a blank title, which the schema describes only as at least one character" in {
    val result = reply("""{"title":"   ","rating":4,"tags":["kitchen"]}""").completeStructuredOf[Review](conversation)
    result.left.map(_.message) match {
      case Left(message) => message should include("title")
      case other         => fail(s"expected a Left, got $other")
    }
  }

  it should "refuse a list outside its length bounds" in {
    val result = reply("""{"title":"Great kettle","rating":4,"tags":[]}""").completeStructuredOf[Review](conversation)
    result.left.map(_.message) match {
      case Left(message) =>
        message should include("tags")
        message should include("minimum length of 1")
      case other => fail(s"expected a Left, got $other")
    }
  }

  "completeStructured with the derived definition" should "refuse a reply out of bounds, as completeStructuredOf does" in {
    val schema = SchemaOf[Review].definition.asInstanceOf[org.llm4s.toolapi.ObjectSchema[Review]]
    reply("""{"title":"Great kettle","rating":4,"tags":["kitchen"]}""")
      .completeStructured[Review](conversation, schema) shouldBe Right(review)
    reply("""{"title":"Great kettle","rating":0,"tags":["kitchen"]}""")
      .completeStructured[Review](conversation, schema)
      .isLeft shouldBe true
  }

  "The ReadWriter" should "write a refined value as its base value and read it back" in {
    upickle.default.write(review) shouldBe """{"title":"Great kettle","rating":4,"tags":["kitchen"]}"""
    upickle.default.read[Review](upickle.default.write(review)) shouldBe review
    val positive: Int :| Positive = 3
    upickle.default.write(positive) shouldBe "3"
    upickle.default.read[Int :| Positive]("3") shouldBe positive
    val refused = intercept[Exception](upickle.default.read[Int :| Positive]("0"))
    Iterator.iterate[Throwable](refused)(_.getCause).takeWhile(_ != null).map(_.getMessage).toList should contain(
      "Should be strictly positive"
    )
  }

  it should "read refined values inside an Option and a List" in {
    upickle.default.read[Wrapped]("""{"maybe":null,"each":["a"]}""") shouldBe Wrapped(None, List("a"))
    intercept[Exception](upickle.default.read[Wrapped]("""{"maybe":0,"each":["a"]}"""))
    intercept[Exception](upickle.default.read[Wrapped]("""{"maybe":1,"each":[" "]}"""))
  }

  "Tool.of with a refined argument" should "send the bounds in its parameters" in {
    val tool       = book.fold(fail(_), identity)
    val parameters = tool.toOpenAITool()("function")("parameters")
    parameters("properties")("nights")("minimum") shouldBe ujson.Num(1)
    parameters("properties")("nights")("maximum") shouldBe ujson.Num(14)
    parameters("properties")("guest")("minLength") shouldBe ujson.Num(1)
  }

  it should "run with arguments in bounds" in {
    val tool = book.fold(fail(_), identity)
    tool.execute(ujson.Obj("guest" -> "Ada", "nights" -> 3)) shouldBe Right(ujson.Obj("id" -> "Ada-3"))
  }

  it should "reject an argument out of range with a ToolParameterError naming the field and the bound" in {
    val tool = book.fold(fail(_), identity)
    val decoded = Tool.decode[Booking](
      tool.schema.asInstanceOf[org.llm4s.toolapi.ObjectSchema[Booking]],
      ujson.Obj("guest" -> "Ada", "nights" -> 30)
    )
    decoded match {
      case Left(List(e: ToolParameterError.TypeMismatch)) =>
        e.parameterName shouldBe "nights"
        e.getMessage should include("Should be included in [1, 14]")
      case other => fail(s"expected one TypeMismatch, got $other")
    }
    tool.execute(ujson.Obj("guest" -> "Ada", "nights" -> 30)) match {
      case Left(ToolCallError.HandlerError("book", message)) =>
        message should include("nights")
        message should include("Should be included in [1, 14]")
      case other => fail(s"expected the handler's error, got $other")
    }
  }

  it should "reject a blank guest name" in {
    val tool = book.fold(fail(_), identity)
    tool.execute(ujson.Obj("guest" -> " ", "nights" -> 3)) match {
      case Left(ToolCallError.HandlerError(_, message)) => message should include("guest")
      case other                                        => fail(s"expected the handler's error, got $other")
    }
  }
}
