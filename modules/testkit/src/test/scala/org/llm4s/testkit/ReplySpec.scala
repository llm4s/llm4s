package org.llm4s.testkit

import org.llm4s.error.{ RateLimitError, ValidationError }
import org.llm4s.llmconnect.model.{ TokenUsage, ToolCall }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ReplySpec extends AnyFlatSpec with Matchers {

  private val usage = TokenUsage(promptTokens = 3, completionTokens = 4, totalTokens = 7)

  "Reply.text" should "carry the text and nothing else" in {
    Reply.text("hello") shouldBe Reply(content = "hello")
    val reply = Reply.text("hello")
    reply.toolCalls shouldBe empty
    reply.failure shouldBe None
    reply.usage shouldBe None
    reply.model shouldBe Reply.DefaultModel
    reply.isFailure shouldBe false
  }

  "Reply.toolCall" should "make one call with the name and arguments, and an empty id for the client to number" in {
    val reply = Reply.toolCall("get_weather", ujson.Obj("city" -> "Paris"))
    reply.toolCalls shouldBe List(ToolCall("", "get_weather", ujson.Obj("city" -> "Paris")))
    reply.content shouldBe ""
  }

  it should "default to no arguments and accept an explicit id" in {
    Reply.toolCall("ping").toolCalls shouldBe List(ToolCall("", "ping", ujson.Obj()))
    Reply.toolCall("ping", id = "abc").toolCalls.map(_.id) shouldBe List("abc")
  }

  "Reply.toolCalls" should "keep the calls in the order given" in {
    val first  = ToolCall("a", "one", ujson.Obj())
    val second = ToolCall("b", "two", ujson.Obj())
    Reply.toolCalls(first, second).toolCalls shouldBe List(first, second)
    Reply.toolCalls().toolCalls shouldBe empty
  }

  "Reply.failure" should "carry the error and report itself as a failure" in {
    val error = RateLimitError("openai")
    val reply = Reply.failure(error)
    reply.failure shouldBe Some(error)
    reply.isFailure shouldBe true
  }

  "the with* methods" should "change only their own field" in {
    val base = Reply.text("x")
    base.withContent("y") shouldBe base.copyForTest(content = "y")
    base.withModel("m") shouldBe base.copyForTest(model = "m")
    base.withUsage(usage) shouldBe base.copyForTest(usage = Some(usage))
    base.withToolCalls(List(ToolCall("i", "n", ujson.Obj()))) shouldBe
      base.copyForTest(toolCalls = List(ToolCall("i", "n", ujson.Obj())))
  }

  it should "let the Option overload of withUsage clear the usage again" in {
    Reply.text("x").withUsage(usage).withUsage(None).usage shouldBe None
    Reply.text("x").withUsage(Some(usage)).usage shouldBe Some(usage)
  }

  it should "not change the reply it was called on" in {
    val base = Reply.text("x")
    base.withContent("y")
    base shouldBe Reply.text("x")
  }

  "Reply.apply" should "default every field except the content to empty" in {
    Reply() shouldBe Reply(
      content = "",
      toolCalls = List.empty,
      failure = None,
      model = Reply.DefaultModel,
      usage = None
    )
  }

  "equality" should "compare every field" in {
    Reply.text("a") should not be Reply.text("b")
    Reply.text("a") should not be Reply.text("a").withModel("other")
    Reply.failure(ValidationError("f", "r")) shouldBe Reply.failure(ValidationError("f", "r"))
  }

  /** `copy` is private on purpose; the spec rebuilds the expected value through the public `apply`. */
  extension (reply: Reply) {
    private def copyForTest(
      content: String = reply.content,
      toolCalls: List[ToolCall] = reply.toolCalls,
      model: String = reply.model,
      usage: Option[TokenUsage] = reply.usage
    ): Reply = Reply(content, toolCalls, reply.failure, model, usage)
  }
}
