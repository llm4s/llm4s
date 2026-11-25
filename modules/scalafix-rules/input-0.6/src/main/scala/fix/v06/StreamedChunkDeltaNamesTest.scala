/*
rule = StreamedChunkDeltaNames
*/
package fix.v06

import org.llm4s.llmconnect.model.{ Completion, StreamedChunk, ToolCall }

object StreamedChunkDeltaNamesTest {
  def text(chunk: StreamedChunk): String = chunk.content.getOrElse("")

  def print(chunk: StreamedChunk): Unit = chunk.content.foreach(Console.print)

  def callName(chunk: StreamedChunk): Option[String] = chunk.toolCall.map(_.name)

  val first = StreamedChunk(id = "c1", content = Some("Hel"), toolCall = None)

  val second = StreamedChunk("c1", content = Some("lo"))

  def withText(chunk: StreamedChunk): StreamedChunk = chunk.withContent("lo")

  def withCall(chunk: StreamedChunk, call: ToolCall): StreamedChunk = chunk.withToolCall(call).withContent(None)

  // a Completion's content is the whole answer, and keeps its name
  def answer(completion: Completion): String = completion.content
}
