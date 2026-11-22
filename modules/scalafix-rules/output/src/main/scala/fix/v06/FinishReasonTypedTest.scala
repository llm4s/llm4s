package fix.v06

import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.FinishReason

object FinishReasonTypedTest {
  def stopped(chunk: StreamedChunk): Boolean = chunk.finishReason.contains(FinishReason.Stop)

  def truncated(chunk: StreamedChunk): Boolean = chunk.finishReason == Some(FinishReason.Length)

  def calling(chunk: StreamedChunk): Boolean = chunk.finishReason.exists(_ == FinishReason.ToolCalls)

  def running(chunk: StreamedChunk): Boolean = chunk.finishReason.forall(reason => reason != FinishReason.Stop)

  def code(chunk: StreamedChunk): Int = chunk.finishReason match {
    case Some(FinishReason.Stop)   => 0
    case Some(FinishReason.Length) => 1
    case None           => 2
    case _              => 3
  }

  def finished(chunk: StreamedChunk): Boolean = chunk.finishReason.isDefined

  def reason(chunk: StreamedChunk): String = chunk.finishReason.map(_.name).getOrElse("unknown")

  def log(chunk: StreamedChunk): String = s"finished: ${chunk.finishReason.map(_.name)}"

  val last = StreamedChunk("1", None, finishReason = Some(FinishReason.Stop))

  val positional = StreamedChunk("2", Some("hi"), None, Some(FinishReason.Length))

  def ended(chunk: StreamedChunk): StreamedChunk = chunk.withFinishReason(FinishReason.ToolCalls)

  def copied(chunk: StreamedChunk, from: StreamedChunk): StreamedChunk = chunk.withFinishReason(from.finishReason)

  // a raw value with no case of its own is still Other(raw), whose name is the raw value
  def malformed(chunk: StreamedChunk): Boolean = chunk.finishReason.map(_.name).contains("MALFORMED_FUNCTION_CALL")

  // a chunk built with 0.6.0's raw Bedrock or Gemini value means the state it names
  def bedrockCut(chunk: StreamedChunk): StreamedChunk = chunk.withFinishReason(FinishReason.Length)

  val bedrockDone = StreamedChunk("3", None, finishReason = Some(FinishReason.Stop))

  // a vendor literal outside the comparison the read is part of is not about the reason
  def unrelated(chunk: StreamedChunk, kind: String): Boolean =
    chunk.finishReason.map(_.name).getOrElse("") == "x" && kind == "end_turn"
}
