package org.llm4s.llmconnect.model

import org.llm4s.types.ProviderModelTypes.ModelName
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
    FinishReason.Unknown.name shouldBe ""
  }

  "FinishReason" should "round-trip through JSON, Other with its raw value" in {
    Seq(
      FinishReason.Stop,
      FinishReason.ToolCalls,
      FinishReason.Length,
      FinishReason.Other("SAFETY"),
      FinishReason.Unknown
    ).foreach(r => upickle.default.read[FinishReason](upickle.default.write(r)) shouldBe r)
  }

  // the format durable events (`ModelCallCompleted.finishReason`) are stored in: it must not drift (#2809)
  it should "be written as its case name, and Other as a tagged object" in {
    upickle.default.write[FinishReason](FinishReason.Stop) shouldBe "\"Stop\""
    upickle.default.write[FinishReason](FinishReason.ToolCalls) shouldBe "\"ToolCalls\""
    upickle.default.write[FinishReason](FinishReason.Length) shouldBe "\"Length\""
    upickle.default.write[FinishReason](FinishReason.Unknown) shouldBe "\"Unknown\""
    upickle.default.write[FinishReason](FinishReason.Other("SAFETY")) shouldBe """{"$type":"Other","raw":"SAFETY"}"""
  }

  it should "read the empty Other that meant 'none read' before Unknown existed as Unknown" in {
    upickle.default.read[FinishReason]("""{"$type":"Other","raw":""}""") shouldBe FinishReason.Unknown
    upickle.default.read[FinishReason]("""{"$type":"Other","raw":"SAFETY"}""") shouldBe FinishReason.Other("SAFETY")
    upickle.default.read[FinishReason]("\"Stop\"") shouldBe FinishReason.Stop
  }

  "Completion.finishReason" should "be Unknown unless set: a client that forgets it never claims a natural stop (#2807)" in {
    Completion(
      id = "c",
      created = created,
      model = ModelName("m"),
      message = AssistantMessage("a")
    ).finishReason shouldBe
      FinishReason.Unknown
  }

  it should "be set by withFinishReason and by apply" in {
    val c = Completion(
      id = "c",
      created = created,
      model = ModelName("m"),
      message = AssistantMessage("a"),
      finishReason = FinishReason.Length
    )
    c.finishReason shouldBe FinishReason.Length
    c.withFinishReason(FinishReason.ToolCalls).finishReason shouldBe FinishReason.ToolCalls
    c.withFinishReason(FinishReason.ToolCalls).withModel(ModelName("b")).finishReason shouldBe FinishReason.ToolCalls
  }

  "StreamingAccumulator" should "put the last reason it saw on the Completion it builds" in {
    val acc = StreamingAccumulator(ModelName("test-model"))
    acc.addChunk(StreamedChunk(id = "s", contentDelta = Some("Hel")))
    acc.addChunk(StreamedChunk(id = "s", contentDelta = Some("lo"), finishReason = Some(FinishReason.Length)))
    acc.isComplete shouldBe true
    acc.toCompletion(created).map(_.finishReason) shouldBe Right(FinishReason.Length)
  }

  it should "keep a real reason when a later chunk carries an empty one" in {
    val acc = StreamingAccumulator(ModelName("test-model"))
    acc.addChunk(StreamedChunk(id = "s", contentDelta = Some("Hel"), finishReason = Some(FinishReason.Length)))
    acc.addChunk(StreamedChunk(id = "s", contentDelta = None, finishReason = Some(FinishReason.Unknown)))
    acc.toCompletion(created).map(_.finishReason) shouldBe Right(FinishReason.Length)
  }

  it should "build Other(\"\") when the stream carried no reason, never a Stop the provider didn't send" in {
    val acc = StreamingAccumulator(ModelName("test-model"))
    acc.addChunk(StreamedChunk(id = "s", contentDelta = Some("Hel")))
    acc.isComplete shouldBe false
    acc.toCompletion(created).map(_.finishReason) shouldBe Right(FinishReason.Unknown)
  }
}
