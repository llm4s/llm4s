/*
rule = FinishReasonTyped
*/
package fix.v06

import org.llm4s.llmconnect.model.StreamedChunk

// What FinishReasonTyped reports instead of rewriting: a reason set from a String that is not a literal, and a literal
// no rewrite gets right for every client.
object FinishReasonTypedLintTest {
  def from(chunk: StreamedChunk, raw: String): StreamedChunk = chunk.withFinishReason(raw) // assert: FinishReasonTyped.finishReasonFromString

  def built(raw: Option[String]): StreamedChunk = StreamedChunk("1", None, finishReason = raw) // assert: FinishReasonTyped.finishReasonFromString

  // A content filter is a Left(ContentFilteredError) now, never a finish reason: the comparison would be always false.
  def filtered(chunk: StreamedChunk): Boolean = chunk.finishReason.contains("content_filter") // assert: FinishReasonTyped.finishReasonIsAnError

  def blocked(chunk: StreamedChunk): Boolean = chunk.finishReason == Some("SAFETY") // assert: FinishReasonTyped.finishReasonIsAnError

  // Gemini's STOP is Stop or ToolCalls, by whether the reply called a tool: no one rewrite is right.
  def geminiStop(chunk: StreamedChunk): Boolean = chunk.finishReason.contains("STOP") // assert: FinishReasonTyped.finishReasonAmbiguous

  // 0.6.0's Bedrock and Gemini chunks carried these raw; RFC 0009 PR 2 maps them to a case, while a Spring AI model
  // still passes them through as Other(raw): neither rewrite is right for both.
  def bedrockDone(chunk: StreamedChunk): Boolean = chunk.finishReason.contains("end_turn") // assert: FinishReasonTyped.finishReasonVendorValue

  def bedrockCalling(chunk: StreamedChunk): Boolean = chunk.finishReason == Some("tool_use") // assert: FinishReasonTyped.finishReasonVendorValue

  def geminiCut(chunk: StreamedChunk): Boolean = chunk.finishReason.contains("MAX_TOKENS") // assert: FinishReasonTyped.finishReasonVendorValue

  // setting a filter's value or Gemini's STOP has no one case either
  def filteredChunk(chunk: StreamedChunk): StreamedChunk = chunk.withFinishReason("SAFETY") // assert: FinishReasonTyped.finishReasonIsAnError

  def geminiStopChunk(chunk: StreamedChunk): StreamedChunk = chunk.withFinishReason(Some("STOP")) // assert: FinishReasonTyped.finishReasonAmbiguous

  // in a shape the rule does not read, a name comparison would never match it either
  def orElse(chunk: StreamedChunk): Boolean = chunk.finishReason.getOrElse("") == "end_turn" // assert: FinishReasonTyped.finishReasonVendorValue

  // and a case's literal beside it is reported as left a String, which would compile and never match
  def either(chunk: StreamedChunk): Boolean = chunk.finishReason.exists(r =>
    r == "stop" || // assert: FinishReasonTyped.finishReasonLeftAsString
      r == "tool_use" // assert: FinishReasonTyped.finishReasonVendorValue
  )

  // a match with a vendor literal is reported and left whole, never half rewritten, its other cases with it
  def mixed(chunk: StreamedChunk): Int = chunk.finishReason match {
    case Some("stop")     => 0 // assert: FinishReasonTyped.finishReasonLeftAsString
    case Some("end_turn") => 1 // assert: FinishReasonTyped.finishReasonVendorValue
    case _                => 2
  }
}
