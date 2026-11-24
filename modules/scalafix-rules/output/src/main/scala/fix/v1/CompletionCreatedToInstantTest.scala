package fix.v1

import org.llm4s.llmconnect.model.{ AssistantMessage, Completion }
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import java.time.Instant
import org.llm4s.types.ProviderModelTypes.ModelName

object CompletionCreatedToInstantTest {
  val completion = Completion(id = "1", created = Instant.ofEpochSecond(1700000000L), model = ModelName("m"), message = AssistantMessage("hi"))

  val fixture = Completion("2", Instant.EPOCH, ModelName("m"), AssistantMessage("hi"))

  def restamp(c: Completion, seconds: Long): Completion = c.withCreated(Instant.ofEpochSecond(seconds))

  def copied(c: Completion, from: Completion): Completion = c.withCreated(from.created)

  def finish(accumulator: StreamingAccumulator) = accumulator.toCompletion(Instant.ofEpochSecond(System.currentTimeMillis() / 1000))

  val seconds: Long = completion.created.getEpochSecond
}
