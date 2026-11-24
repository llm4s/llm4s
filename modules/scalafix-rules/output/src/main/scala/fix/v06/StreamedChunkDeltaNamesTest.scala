package fix.v06

import org.llm4s.llmconnect.model.{ Completion, StreamedChunk, ToolCall }

object StreamedChunkDeltaNamesTest {
  def text(chunk: StreamedChunk): String = chunk.contentDelta.getOrElse("")

  def print(chunk: StreamedChunk): Unit = chunk.contentDelta.foreach(Console.print)

  def callName(chunk: StreamedChunk): Option[String] = chunk.toolCallDelta.map(_.name)

  val first = StreamedChunk(id = "c1", contentDelta = Some("Hel"), toolCallDelta = None)

  val second = StreamedChunk("c1", contentDelta = Some("lo"))

  def withText(chunk: StreamedChunk): StreamedChunk = chunk.withContentDelta("lo")

  def withCall(chunk: StreamedChunk, call: ToolCall): StreamedChunk = chunk.withToolCallDelta(call).withContentDelta(None)

  // a Completion's content is the whole answer, and keeps its name
  def answer(completion: Completion): String = completion.content
}
