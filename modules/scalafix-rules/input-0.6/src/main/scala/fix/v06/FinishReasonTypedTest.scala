/*
rule = FinishReasonTyped
*/
package fix.v06

import org.llm4s.llmconnect.model.StreamedChunk

object FinishReasonTypedTest {
  def stopped(chunk: StreamedChunk): Boolean = chunk.finishReason.contains("stop")

  def truncated(chunk: StreamedChunk): Boolean = chunk.finishReason == Some("length")

  def calling(chunk: StreamedChunk): Boolean = chunk.finishReason.exists(_ == "tool_calls")

  def running(chunk: StreamedChunk): Boolean = chunk.finishReason.forall(reason => reason != "stop")

  def code(chunk: StreamedChunk): Int = chunk.finishReason match {
    case Some("stop")   => 0
    case Some("length") => 1
    case None           => 2
    case _              => 3
  }

  def finished(chunk: StreamedChunk): Boolean = chunk.finishReason.isDefined

  def reason(chunk: StreamedChunk): String = chunk.finishReason.getOrElse("unknown")

  def log(chunk: StreamedChunk): String = s"finished: ${chunk.finishReason}"

  val last = StreamedChunk("1", None, finishReason = Some("stop"))

  val positional = StreamedChunk("2", Some("hi"), None, Some("length"))

  def ended(chunk: StreamedChunk): StreamedChunk = chunk.withFinishReason("tool_calls")

  def copied(chunk: StreamedChunk, from: StreamedChunk): StreamedChunk = chunk.withFinishReason(from.finishReason)

  // a raw value with no case of its own is still Other(raw), whose name is the raw value
  def malformed(chunk: StreamedChunk): Boolean = chunk.finishReason.contains("MALFORMED_FUNCTION_CALL")
}
