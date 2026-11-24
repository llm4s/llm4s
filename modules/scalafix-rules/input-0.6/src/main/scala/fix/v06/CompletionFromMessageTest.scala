/*
rule = CompletionFromMessage
*/
package fix.v06

import java.time.Instant

import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, ToolCall }

object CompletionFromMessageTest {
  def reply(text: String, calls: List[ToolCall]): Completion = {
    val message = AssistantMessage(text, calls)
    Completion(
      id = "1",
      created = Instant.EPOCH,
      content = text,
      model = "m",
      message = message,
      toolCalls = calls
    )
  }

  val named = Completion(id = "2", created = Instant.EPOCH, content = "hi", model = "m", message = AssistantMessage("hi"))

  val last = Completion(id = "3", created = Instant.EPOCH, model = "m", message = AssistantMessage("hi"), content = "hi")

  val positional = Completion("4", Instant.EPOCH, "hi", "m", AssistantMessage("hi"))

  val positionalCalls = Completion("5", Instant.EPOCH, "hi", "m", AssistantMessage("hi"), Nil, None)

  def text(completion: Completion): String = completion.asText.trim

  def priced(completion: Completion, cost: Double): Completion =
    completion.withEstimatedCost(cost).withEstimatedCost(Some(cost / 2)).withEstimatedCost(None)

  def model(completion: Completion): String = completion match {
    case Completion(_, _, _, model, _, _, _, _, _, _) => model
  }
}
