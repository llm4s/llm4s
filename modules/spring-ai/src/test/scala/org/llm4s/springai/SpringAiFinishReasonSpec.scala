package org.llm4s.springai

import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, FinishReason, ToolCall, ToolCallId }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

// RFC 0009: an llm4s completion's reason reaches Spring AI as it was, where it once was guessed from the tool calls
class SpringAiFinishReasonSpec extends AnyFlatSpec with Matchers {

  private val call = ToolCall(ToolCallId("c1"), "weather", ujson.Obj())

  private def completion(reason: FinishReason, calls: List[ToolCall] = Nil) =
    Completion(
      id = "c",
      created = Instant.EPOCH,
      model = "m",
      message = AssistantMessage(Some("x"), calls),
      finishReason = reason
    )

  "SpringAiMapping.finishOf" should "pass a reply cut at the limit on as LENGTH, not STOP" in {
    SpringAiMapping.finishOf(completion(FinishReason.Length)) shouldBe "LENGTH"
  }

  it should "name Stop and ToolCalls as Spring AI's own models do" in {
    SpringAiMapping.finishOf(completion(FinishReason.Stop)) shouldBe "STOP"
    SpringAiMapping.finishOf(completion(FinishReason.ToolCalls, List(call))) shouldBe "TOOL_CALLS"
  }

  it should "pass a raw reason through" in {
    SpringAiMapping.finishOf(completion(FinishReason.Other("content_filter"))) shouldBe "content_filter"
  }

  it should "name a reply whose client read no reason by its calls, which the tool-calling advisor needs" in {
    SpringAiMapping.finishOf(completion(FinishReason.Unknown, List(call))) shouldBe "TOOL_CALLS"
    SpringAiMapping.finishOf(completion(FinishReason.Unknown)) shouldBe "STOP"
  }

  it should "name a reply with calls TOOL_CALLS when its client has not mapped the reason yet (Bedrock's tool_use)" in {
    SpringAiMapping.finishOf(completion(FinishReason.Other("tool_use"), List(call))) shouldBe "TOOL_CALLS"
  }
}
