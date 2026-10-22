// Probe H: implement LLMClient in Scala 2.13 (a fake client for tests, a custom provider).
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk}

final class CannedClient extends LLMClient {
  def complete(conversation: Conversation, options: CompletionOptions): Either[LLMError, Completion] =
    Right(Completion(id = "1", created = 0L, content = "canned", model = "fake", message = AssistantMessage("canned")))
  def streamComplete(conversation: Conversation, options: CompletionOptions, onChunk: StreamedChunk => Unit): Either[LLMError, Completion] =
    complete(conversation, options)
  def getContextWindow(): Int     = 8192
  def getReserveCompletion(): Int = 1024
}

object Probe extends App {
  println(s"PASS: ${new CannedClient().complete(Conversation(Seq.empty), CompletionOptions()).map(_.content)}")
}
