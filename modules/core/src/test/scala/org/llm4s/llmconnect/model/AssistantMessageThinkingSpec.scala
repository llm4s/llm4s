package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

class AssistantMessageThinkingSpec extends AnyFlatSpec with Matchers {

  private val call = ToolCall("call-1", "weather", ujson.Obj("city" -> "Paris"))

  private val signed = AssistantMessage(
    contentOpt = Some("Checking."),
    toolCalls = Seq(call),
    thinking = Seq(
      ThinkingBlock.Text("The user wants the weather.", Some("sig-abc")),
      ThinkingBlock.Redacted("opaque-data"),
      ThinkingBlock.Text("Call the tool.")
    )
  )

  "AssistantMessage thinking" should "round-trip through the AssistantMessage codec, blocks and signatures intact" in {
    read[AssistantMessage](write(signed)) shouldBe signed
  }

  it should "round-trip through the Message codec, as conversation history and checkpoints store it" in {
    read[Message](write[Message](signed)) shouldBe signed
    read[Conversation](write(Conversation(Seq(UserMessage("weather?"), signed)))).messages(1) shouldBe signed
  }

  it should "read JSON written before the field existed as no thinking" in {
    read[AssistantMessage]("""{"contentOpt":"Hello","toolCalls":[]}""") shouldBe AssistantMessage("Hello")
    read[Message]("""{"type":"assistant","data":{"contentOpt":null,"toolCalls":[]}}""") shouldBe AssistantMessage()
  }

  it should "not write a thinking key for a message without thinking" in {
    ujson.read(write(AssistantMessage("Hello"))).obj.keySet shouldBe Set("contentOpt", "toolCalls")
  }

  it should "expose the text of its text blocks and not of redacted ones" in {
    signed.thinkingText shouldBe Some("The user wants the weather.Call the tool.")
    signed.hasThinking shouldBe true
    AssistantMessage(thinking = Seq(ThinkingBlock.Redacted("x"))).thinkingText shouldBe None
    AssistantMessage("Hi").hasThinking shouldBe false
  }

  it should "be set from text, an empty text clearing it" in {
    AssistantMessage("Hi").withThinking("hmm").thinking shouldBe Seq(ThinkingBlock.Text("hmm"))
    AssistantMessage("Hi").withThinking("hmm").withThinking("").thinking shouldBe Seq.empty
  }

  it should "be kept by withContent and withToolCalls" in {
    signed.withContent("Changed").thinking shouldBe signed.thinking
    signed.withToolCalls(Seq.empty).thinking shouldBe signed.thinking
  }

  it should "be what Completion.thinking reports" in {
    val completion = Completion("id", 0L, "Checking.", "m", signed, signed.toolCalls.toList)
    completion.thinking shouldBe signed.thinkingText
    completion.hasThinking shouldBe true
  }

  "AssistantMessage.validate" should "accept thinking with content or tool calls" in {
    signed.validate shouldBe Right(signed)
  }

  it should "accept a signed block with empty text, as Anthropic returns for omitted thinking" in {
    val omitted = AssistantMessage("Hi").withThinking(Seq(ThinkingBlock.Text("", Some("sig"))))
    omitted.validate shouldBe Right(omitted)
  }

  it should "refuse thinking alone" in {
    AssistantMessage().withThinking("only thinking").validate.isLeft shouldBe true
  }

  it should "refuse an empty unsigned block or empty redacted data" in {
    AssistantMessage("Hi").withThinking(Seq(ThinkingBlock.Text(""))).validate.isLeft shouldBe true
    AssistantMessage("Hi").withThinking(Seq(ThinkingBlock.Redacted(""))).validate.isLeft shouldBe true
  }
}
