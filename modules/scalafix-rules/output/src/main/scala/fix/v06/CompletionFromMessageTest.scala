package fix.v06

import java.time.Instant

import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, ToolCall }
import org.llm4s.types.ProviderModelTypes.ModelName

object CompletionFromMessageTest {
  def reply(text: String, calls: List[ToolCall]): Completion = {
    val message = AssistantMessage(text, calls)
    Completion(
      id = "1",
      created = Instant.EPOCH,
      model = ModelName("m"),
      message = message
    )
  }

  val named = Completion(id = "2", created = Instant.EPOCH, model = ModelName("m"), message = AssistantMessage("hi"))

  val last = Completion(id = "3", created = Instant.EPOCH, model = ModelName("m"), message = AssistantMessage("hi"))

  val positional = Completion("4", Instant.EPOCH, ModelName("m"), AssistantMessage("hi"))

  val positionalCalls = Completion("5", Instant.EPOCH, ModelName("m"), AssistantMessage("hi"), None)

  def text(completion: Completion): String = completion.content.trim

  def priced(completion: Completion, cost: Double): Completion =
    completion.withEstimatedCost(BigDecimal.decimal(cost)).withEstimatedCost(Some(BigDecimal.decimal(cost / 2))).withEstimatedCost(None)

  def model(completion: Completion) = completion match {
    case Completion(_, _, model, _, _, _, _, _, _, _) => model
  }
}
