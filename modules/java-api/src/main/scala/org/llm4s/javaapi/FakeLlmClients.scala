package org.llm4s.javaapi

import org.llm4s.error.{
  AuthenticationError,
  CancelledError,
  ConfigurationError,
  LLMError,
  NetworkError,
  ProcessingError,
  RateLimitError,
  ServiceError,
  TimeoutError,
  ValidationError
}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  UserMessage
}
import org.llm4s.types.Result

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** What [[FakeLlmClient]] is made of: its script entries and the scripted `LLMClient` behind it (#1497). */
private[javaapi] object FakeLlmClients {

  /** One answer of a script: a reply, or a failure. */
  final class Entry private[FakeLlmClients] (private[FakeLlmClients] val answer: Either[LLMError, String])

  def reply(text: String): Entry = new Entry(Right(String.valueOf(text)))

  /** A failure of `kind` (`null` reads as `OTHER`), whose error `LlmException.getKind` reads back as `kind`. */
  def failure(kind: LlmErrorKind, message: String): Entry =
    new Entry(Left(errorOf(Option(kind).getOrElse(LlmErrorKind.OTHER), String.valueOf(message))))

  /** An `LLMClient` answering call `n` with entry `n`, recording every prompt; thread-safe. */
  final class Scripted(entries: java.util.List[Entry]) extends LLMClient {
    private val script   = entries.asScala.toVector.map(_.answer)
    private val lock     = new Object
    private var recorded = Vector.empty[String]

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val prompt = lastUserText(conversation)
      val index  = lock.synchronized { recorded = recorded :+ prompt; recorded.size - 1 }
      script.lift(index) match {
        case Some(Right(text)) => Right(Completion(s"fake-${index + 1}", 0L, text, "fake", AssistantMessage(text)))
        case Some(Left(error)) => Left(error)
        case None =>
          Left(
            ProcessingError(
              "FakeLlmClient",
              s"no reply scripted for call ${index + 1} (the script has ${script.size}); its prompt was: $prompt"
            )
          )
      }
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(conversation, options).map { completion =>
        onChunk(StreamedChunk(id = completion.id, content = Some(completion.content), finishReason = Some("stop")))
        completion
      }

    override def getContextWindow(): Int     = 128000
    override def getReserveCompletion(): Int = 4096

    def callCount: Int                  = lock.synchronized(recorded.size)
    def prompts: java.util.List[String] = java.util.List.copyOf(lock.synchronized(recorded).asJava)
  }

  private def errorOf(kind: LlmErrorKind, message: String): LLMError = kind match {
    case LlmErrorKind.AUTHENTICATION => AuthenticationError("fake", message)
    case LlmErrorKind.RATE_LIMIT     => RateLimitError("fake")
    case LlmErrorKind.TIMEOUT        => TimeoutError(message, 30.seconds, "FakeLlmClient")
    case LlmErrorKind.NETWORK        => NetworkError(message, None, "fake")
    case LlmErrorKind.SERVICE        => ServiceError(500, "fake", message)
    case LlmErrorKind.VALIDATION     => ValidationError("fake", message)
    case LlmErrorKind.CONFIGURATION  => ConfigurationError(message)
    case LlmErrorKind.CANCELLED      => CancelledError("FakeLlmClient")
    case LlmErrorKind.OTHER          => ProcessingError("FakeLlmClient", message)
  }

  private def lastUserText(conversation: Conversation): String =
    conversation.messages.collect { case u: UserMessage => u.content }.lastOption.getOrElse("")
}
