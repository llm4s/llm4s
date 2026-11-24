package org.llm4s.llmconnect.model

import org.llm4s.error.{ LLMError, ValidationError }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Every message validation names the field it rejects, and gives the reason as the violation (#2796). */
class MessageValidationSpec extends AnyFlatSpec with Matchers {

  private def rejected(result: Either[LLMError, ?]): ValidationError =
    result match {
      case Left(e: ValidationError) => e
      case other                    => fail(s"expected a ValidationError, got $other")
    }

  private def assertRejects(result: Either[LLMError, ?], field: String, reason: String) = {
    val error = rejected(result)
    error.field shouldBe field
    error.violations shouldBe List(reason)
    error.message shouldBe s"Invalid $field: $reason"
  }

  "Message.system" should "reject blank content, naming the content field" in {
    assertRejects(Message.system(" "), "content", "System message content cannot be empty")
  }

  "Message.user" should "reject blank content, naming the content field" in {
    assertRejects(Message.user(""), "content", "User message content cannot be empty")
  }

  "Message.assistant" should "reject a message with neither content nor tool calls, naming the content field" in {
    assertRejects(Message.assistant(" "), "content", "Assistant message must have either content or tool calls")
  }

  "Message.tool" should "reject blank content, naming the content field" in {
    assertRejects(Message.tool(" ", "call-1"), "content", "Tool message content cannot be empty")
  }

  it should "reject a blank tool call id, naming the toolCallId field" in {
    assertRejects(Message.tool("ok", " "), "toolCallId", "Tool call ID cannot be empty")
  }

  "Message.validate" should "reject a blank system message, naming the content field" in {
    assertRejects(SystemMessage(" ").validate, "content", "system message content cannot be empty")
  }

  "AssistantMessage.validate" should "reject a message with neither content nor tool calls, naming the content field" in {
    assertRejects(
      AssistantMessage(" ").validate,
      "content",
      "Assistant message must have either content or tool calls"
    )
  }

  "ToolMessage.validate" should "reject blank content and a blank call id together, naming the toolMessage field" in {
    val error = rejected(ToolMessage(Vector.empty, " ").validate)
    error.field shouldBe "toolMessage"
    error.violations shouldBe List("Tool message content cannot be empty", "Tool call ID cannot be empty")
    error.message shouldBe "Invalid toolMessage: Tool message content cannot be empty, Tool call ID cannot be empty"
  }

  it should "reject a blank call id alone, naming the toolMessage field" in {
    assertRejects(ToolMessage("ok", " ").validate, "toolMessage", "Tool call ID cannot be empty")
  }

  "Message.validateConversation" should "name the conversation field and list each violation once" in {
    val error = rejected(Message.validateConversation(List(UserMessage("hi"), ToolMessage("orphan", "call-9"))))
    error.field shouldBe "conversation"
    error.violations should not be empty
    error.violations should not contain "conversation"
    error.message shouldBe s"Invalid conversation: ${error.violations.mkString(", ")}"
  }

  // #2809: the name of a call that is not in the history was the sentinel "unknown-tool"
  "ToolMessage.findToolCallName" should "be the name of the call it answers, and None when that call is not there" in {
    val history = Seq[Message](
      UserMessage("weather?"),
      AssistantMessage(None, Seq(ToolCall("call-1", "get_weather", ujson.Obj())))
    )

    ToolMessage("sunny", "call-1").findToolCallName(history) shouldBe Some("get_weather")
    ToolMessage("sunny", "call-2").findToolCallName(history) shouldBe None
    ToolMessage("sunny", "call-1").findToolCallName(Seq.empty) shouldBe None
  }
}
