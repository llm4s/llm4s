package org.llm4s.llmconnect.model

import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

// RFC 0009 Part 1: one vocabulary for why the model's turn ended, on the Completion and on each chunk.
class FinishReasonSpec extends AnyFlatSpec with Matchers {

  private val created = Instant.parse("2026-01-01T00:00:00Z")

  "FinishReason.name" should "be the chat-completions name, or the raw value the provider sent" in {
    FinishReason.Stop.name shouldBe "stop"
    FinishReason.ToolCalls.name shouldBe "tool_calls"
    FinishReason.Length.name shouldBe "length"
    FinishReason.Other("content_filter").name shouldBe "content_filter"
    FinishReason.Other("").name shouldBe ""
  }

  "FinishReason" should "round-trip through JSON, Other with its raw value" in {
    Seq(FinishReason.Stop, FinishReason.ToolCalls, FinishReason.Length, FinishReason.Other("SAFETY")).foreach { r =>
      upickle.default.read[FinishReason](upickle.default.write(r)) shouldBe r
    }
  }

  "Completion.finishReason" should "default to Stop, so existing construction sites compile (open question 1)" in {
    Completion(
      id = "c",
      created = created,
      content = "a",
      model = "m",
      message = AssistantMessage("a")
    ).finishReason shouldBe
      FinishReason.Stop
  }

  it should "be set by withFinishReason and by apply" in {
    val c = Completion(
      id = "c",
      created = created,
      content = "a",
      model = "m",
      message = AssistantMessage("a"),
      finishReason = FinishReason.Length
    )
    c.finishReason shouldBe FinishReason.Length
    c.withFinishReason(FinishReason.ToolCalls).finishReason shouldBe FinishReason.ToolCalls
    c.withFinishReason(FinishReason.ToolCalls).withContent("b").finishReason shouldBe FinishReason.ToolCalls
  }

  it should "be Stop from the previous apply, which takes every argument but the reason" in {
    Completion("c", created, "a", "m", AssistantMessage("a"), Nil, None, None, Nil, Nil).finishReason shouldBe
      FinishReason.Stop
  }

  "StreamingAccumulator" should "put the last reason it saw on the Completion it builds" in {
    val acc = StreamingAccumulator.create()
    acc.addChunk(StreamedChunk(id = "s", content = Some("Hel")))
    acc.addChunk(StreamedChunk(id = "s", content = Some("lo"), finishReason = Some(FinishReason.Length)))
    acc.isComplete shouldBe true
    acc.toCompletion(created).map(_.finishReason) shouldBe Right(FinishReason.Length)
  }

  it should "keep a real reason when a later chunk carries an empty one" in {
    val acc = StreamingAccumulator.create()
    acc.addChunk(StreamedChunk(id = "s", content = Some("Hel"), finishReason = Some(FinishReason.Length)))
    acc.addChunk(StreamedChunk(id = "s", content = None, finishReason = Some(FinishReason.Other(""))))
    acc.toCompletion(created).map(_.finishReason) shouldBe Right(FinishReason.Length)
  }

  it should "build Other(\"\") when the stream carried no reason, never a Stop the provider didn't send" in {
    val acc = StreamingAccumulator.create()
    acc.addChunk(StreamedChunk(id = "s", content = Some("Hel")))
    acc.isComplete shouldBe false
    acc.toCompletion(created).map(_.finishReason) shouldBe Right(FinishReason.Other(""))
  }
}
