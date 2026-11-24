package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, FinishReason, ToolCall, ToolCallId }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** RFC 0009, Part 3, as every client's `complete` path applies it to a finished reply (`ToolCallRules.checked`). */
class ToolCallRulesSpec extends AnyFlatSpec with Matchers {

  private def reply(reason: FinishReason, calls: ToolCall*): Completion =
    Completion("r", Instant.EPOCH, ModelName("m"), AssistantMessage(None, calls.toList))
      .withFinishReason(reason)

  private val call = ToolCall(ToolCallId("c1"), "lookup", ujson.Obj("q" -> "x"))

  private def malformed(result: Either[?, ?]): Boolean = result match {
    case Left(e: ProcessingError) => e.message.contains("malformed tool call")
    case _                        => false
  }

  "checked" should "pass a reply whose calls have object arguments, and a cut reply with no call" in {
    ToolCallRules.checked(reply(FinishReason.ToolCalls, call)).isRight shouldBe true
    ToolCallRules.checked(reply(FinishReason.Length)).isRight shouldBe true
  }

  it should "fail any call in a reply the output limit cut (rule 3)" in {
    val cut = ToolCallRules.checked(reply(FinishReason.Length, call))
    malformed(cut) shouldBe true
    cut.left.map(_.message.contains("'lookup'")) shouldBe Left(true)
  }

  it should "fail a call whose arguments are not a JSON object (rule 1)" in {
    Seq[ujson.Value](ujson.Str("{\"q\":"), ujson.Arr(1), ujson.Num(1), ujson.Null).foreach { arguments =>
      malformed(
        ToolCallRules.checked(reply(FinishReason.ToolCalls, ToolCall(ToolCallId("c1"), "lookup", arguments)))
      ) shouldBe true
    }
  }

  "wholeCall" should "keep a whole call's explicit {} as arguments the accumulator reads, whatever the finish" in {
    val accumulator = org.llm4s.llmconnect.streaming.StreamingAccumulator(ModelName("test-model"))
    val chunk =
      org.llm4s.llmconnect.model.StreamedChunk("m", None, Some(ToolCall(ToolCallId("c1"), "now", ujson.Obj())), None)
    accumulator.addChunk(ToolCallRules.wholeCall(chunk))
    accumulator.addChunk(org.llm4s.llmconnect.model.StreamedChunk("m", None, None, Some(FinishReason.Other("OTHER"))))
    accumulator.toCompletion.map(_.message.toolCalls.map(_.arguments)) shouldBe Right(Seq(ujson.Obj()))
  }

  "blankArguments" should "accept no arguments only after ToolCalls or Stop (rule 2)" in {
    ToolCallRules.blankArguments("f", FinishReason.ToolCalls) shouldBe Right(())
    ToolCallRules.blankArguments("f", FinishReason.Stop) shouldBe Right(())
    malformed(ToolCallRules.blankArguments("f", FinishReason.Unknown)) shouldBe true
    malformed(ToolCallRules.blankArguments("f", FinishReason.Length)) shouldBe true
  }
}
