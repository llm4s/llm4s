package org.llm4s.trace

import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS }
import scala.util.Try

/**
 * An [[LLMClient]] that traces each of its calls (#2275): a completion - plain or streamed - as one
 * [[TraceEvent.CompletionReceived]] carrying the call's provider, request model, server address, options, duration,
 * finish reasons, usage and messages, and a failed call as one [[TraceEvent.ErrorOccurred]] naming the operation
 * (`chat`), model, provider and error type. A backend that follows the OpenTelemetry GenAI conventions turns each into
 * the call's `chat <model>` span; backends record message content only when told to.
 *
 * {{{
 * val traced = TracingLLMClient(client, tracing, providerConfig)
 * traced.complete(conversation)   // one `chat <model>` span
 * }}}
 *
 * A tracing failure never fails the call: the client returns what the wrapped client returned. `close` closes the
 * wrapped client.
 */
@Experimental
final class TracingLLMClient private (
  underlying: LLMClient,
  tracing: Tracing,
  requestModel: Option[String],
  serverAddress: Option[String]
) extends LLMClient {

  def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
    val startedAt = System.nanoTime()
    val result    = underlying.complete(conversation, options)
    traced(conversation, options, result, elapsed(startedAt), streamed = false, finishReason = None)
  }

  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    val finishReason = new AtomicReference[Option[String]](None)
    val startedAt    = System.nanoTime()
    val result = underlying.streamComplete(
      conversation,
      options,
      chunk => {
        Option(chunk).flatMap(_.finishReason).filter(_.nonEmpty).foreach(r => finishReason.set(Some(r)))
        onChunk(chunk)
      }
    )
    traced(conversation, options, result, elapsed(startedAt), streamed = true, finishReason.get)
  }

  def getContextWindow(): Int                 = underlying.getContextWindow()
  def getReserveCompletion(): Int             = underlying.getReserveCompletion()
  override def validate(): Result[Unit]       = underlying.validate()
  override def close(): Unit                  = underlying.close()
  override def providerId: Option[ProviderId] = underlying.providerId

  private def provider: Option[String] = providerId.map(_.asString)

  private def elapsed(startedAt: Long): FiniteDuration =
    FiniteDuration(math.max(0L, System.nanoTime() - startedAt), NANOSECONDS)

  private def traced(
    conversation: Conversation,
    options: CompletionOptions,
    result: Result[Completion],
    duration: FiniteDuration,
    streamed: Boolean,
    finishReason: Option[String]
  ): Result[Completion] = {
    val at = Instant.now()
    val event: TraceEvent = result match {
      case Right(completion) =>
        TraceEvent.CompletionReceived(
          id = completion.id,
          model = requestModel.getOrElse(completion.model),
          toolCalls = Option(completion.message).map(_.toolCalls.size).getOrElse(completion.toolCalls.size),
          content = completion.content,
          timestamp = at,
          provider = provider,
          serverAddress = serverAddress,
          streamed = streamed,
          duration = Some(duration),
          responseModel = Option(completion.model).filter(_.nonEmpty),
          finishReasons = finishReason.toSeq,
          usage = completion.usage,
          options = Some(options),
          input = conversation.messages
        )
      case Left(error) =>
        TraceEvent.ErrorOccurred(
          new RuntimeException(error.message),
          TraceAttributeNames.Chat,
          timestamp = at,
          operation = Some(TraceAttributeNames.Chat),
          model = requestModel,
          provider = provider,
          errorType = Some(error.getClass.getSimpleName),
          duration = Some(duration)
        )
    }
    Try(tracing.traceEvent(event)): Unit
    result
  }
}

@Experimental
object TracingLLMClient {

  /** Traces `client`'s calls to `tracing`; the request model is each completion's own, and no server address. */
  def apply(client: LLMClient, tracing: Tracing): TracingLLMClient =
    new TracingLLMClient(client, tracing, None, None)

  /**
   * Traces `client`'s calls to `tracing`, with `config`'s model as the request model and the host of its endpoint, when
   * it has one, as the server address.
   */
  def apply(client: LLMClient, tracing: Tracing, config: ProviderConfig): TracingLLMClient =
    new TracingLLMClient(client, tracing, Option(config.model).filter(_.nonEmpty), config.endpointUrl.flatMap(host))

  private def host(url: String): Option[String] =
    Try(Option(URI.create(url).getHost)).toOption.flatten.filter(_.nonEmpty)
}

/** The GenAI conventions' operation names that core's events carry. */
private[llm4s] object TraceAttributeNames {
  val Chat       = "chat"
  val Embeddings = "embeddings"
}
