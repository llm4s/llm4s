package org.llm4s.repl

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import scala.concurrent.duration.*

/**
 * The session's client: every completion, whichever helper asked for it (`completeStructuredOf` included), is timed
 * and recorded as the session's [[CallStats]].
 */
final private[repl] class RecordingClient(underlying: LLMClient, session: Session) extends LLMClient {

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    timed(underlying.complete(conversation, options))

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    timed(underlying.streamComplete(conversation, options, onChunk))

  override def getContextWindow(): Int = underlying.getContextWindow()

  override def getReserveCompletion(): Int = underlying.getReserveCompletion()

  override def validate(): Result[Unit] = underlying.validate()

  override def providerId: Option[ProviderId] = underlying.providerId

  override def close(): Unit = underlying.close()

  private def timed(call: => Result[Completion]): Result[Completion] = {
    val start  = session.nanoTime()
    val result = call
    val took   = math.max(0L, session.nanoTime() - start).nanos
    result.foreach(completion =>
      session.record(new CallStats(completion.model, completion.usage, took, completion.estimatedCost))
    )
    result
  }
}
