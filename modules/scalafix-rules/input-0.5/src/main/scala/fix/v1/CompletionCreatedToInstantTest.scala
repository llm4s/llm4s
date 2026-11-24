/*
rules = [CompletionCreatedToInstant, CompletionFromMessage]
*/
package fix.v1

import org.llm4s.llmconnect.model.{ AssistantMessage, Completion }
import org.llm4s.llmconnect.streaming.StreamingAccumulator

object CompletionCreatedToInstantTest {
  val completion = Completion(id = "1", created = 1700000000L, content = "hi", model = "m", message = AssistantMessage("hi"))

  val fixture = Completion("2", 0L, "hi", "m", AssistantMessage("hi"))

  def restamp(c: Completion, seconds: Long): Completion = c.withCreated(seconds)

  def copied(c: Completion, from: Completion): Completion = c.withCreated(from.created)

  def finish(accumulator: StreamingAccumulator) = accumulator.toCompletion(System.currentTimeMillis() / 1000)

  val seconds: Long = completion.created
}
