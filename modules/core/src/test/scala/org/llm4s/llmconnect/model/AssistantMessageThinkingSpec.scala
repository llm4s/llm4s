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

  // signed with the redacted block dropped and the signature stripped
  private val unsealed = Seq(
    ThinkingBlock.Text("The user wants the weather."),
    ThinkingBlock.Text("Call the tool.")
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

  it should "be sealed when it holds a signed or redacted block" in {
    signed.hasSealedThinking shouldBe true
    AssistantMessage(thinking = Seq(ThinkingBlock.Redacted("x"))).hasSealedThinking shouldBe true
    AssistantMessage("Hi").withThinking("plain").hasSealedThinking shouldBe false
    AssistantMessage("Hi").withThinking(Seq(ThinkingBlock.Text("t", Some("")))).hasSealedThinking shouldBe false
  }

  it should "be unsealed by a changed content: redacted blocks and signatures dropped, the text kept" in {
    val changed = signed.withContent("Changed")
    changed.content shouldBe "Changed"
    changed.toolCalls shouldBe signed.toolCalls
    changed.thinking shouldBe unsealed
    changed.hasSealedThinking shouldBe false
    changed.thinkingText shouldBe signed.thinkingText
    signed.withContent(None: Option[String]).thinking shouldBe unsealed
  }

  it should "be unsealed by changed tool calls" in {
    val edited = signed.withToolCalls(Seq(call.copy(arguments = ujson.Obj("city" -> "Lyon"))))
    edited.thinking shouldBe unsealed
    signed.withToolCalls(Seq.empty).thinking shouldBe unsealed
  }

  it should "stay sealed when a setter is given the current value" in {
    signed.withContent("Checking.") shouldBe signed
    signed.withContent(Some("Checking.")) shouldBe signed
    signed.withToolCalls(Seq(call)) shouldBe signed
  }

  it should "drop a signed block with no text when unsealed, rather than leave an empty block" in {
    val omitted = AssistantMessage("Hi").withThinking(Seq(ThinkingBlock.Text("", Some("sig"))))
    omitted.withContent("Bye").thinking shouldBe Seq.empty
    omitted.withContent("Bye").validate.isRight shouldBe true
  }

  it should "keep unsigned thinking through any change" in {
    val plain = AssistantMessage("Hi", Seq(call)).withThinking("reasoning")
    plain.withContent("Changed").thinking shouldBe Seq(ThinkingBlock.Text("reasoning"))
    plain.withToolCalls(Seq.empty).thinking shouldBe Seq(ThinkingBlock.Text("reasoning"))
  }

  it should "be replaced as given by withThinking, sealed or not" in {
    AssistantMessage("Hi").withThinking(signed.thinking).thinking shouldBe signed.thinking
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
