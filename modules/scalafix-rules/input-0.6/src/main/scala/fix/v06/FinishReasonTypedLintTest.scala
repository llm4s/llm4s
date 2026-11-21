/*
rule = FinishReasonTyped
*/
package fix.v06

import org.llm4s.llmconnect.model.StreamedChunk

// What FinishReasonTyped reports instead of rewriting: a reason set from a String that is not a literal.
object FinishReasonTypedLintTest {
  def from(chunk: StreamedChunk, raw: String): StreamedChunk = chunk.withFinishReason(raw) // assert: FinishReasonTyped.finishReasonFromString

  def built(raw: Option[String]): StreamedChunk = StreamedChunk("1", None, finishReason = raw) // assert: FinishReasonTyped.finishReasonFromString
}
