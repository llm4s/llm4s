package org.llm4s.pekko

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.llm4s.agent.{ Agent, AgentBuilder }
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result

import scala.concurrent.{ blocking, ExecutionContext, Future }

/**
 * Apache Pekko Streams wrapper for [[LLMClient]].
 *
 * `streamComplete` is a `Source` of the provider's chunks; `complete` is a `Future`. The provider call
 * blocks, so it never runs on a stream or actor dispatcher thread: a stream runs it on a thread of its own,
 * and `complete` runs it on the `ExecutionContext` you pass, which should be a blocking one - for example
 * `system.dispatchers.lookup(Dispatchers.DefaultBlockingDispatcherId)`.
 *
 * Cancelling a stream interrupts the provider's thread, which is how llm4s providers are cancelled:
 * they keep the interrupt and return `Left(CancelledError)`. A `Future` cannot be cancelled; use
 * `streamComplete` when the call has to be.
 *
 * An [[LLMError]] is carried by an [[LLMException]], the failure of the stream or the `Future`.
 */
trait LLMClientPekko {

  /** The completion as a `Future`, run on `ec`; fails with [[LLMException]] when the provider returns a `Left`. */
  def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  )(using ec: ExecutionContext): Future[Completion]

  /**
   * The provider's chunks, as they arrive, as a `Source`. The call starts when the stream is
   * materialized, once per materialization.
   *
   *  - '''Backpressure''': the provider calls back on its own thread and cannot be paused, so its thread is
   *    blocked while `bufferSize` chunks wait for the consumer. Nothing is dropped, and no more than
   *    `bufferSize` chunks are held.
   *  - '''Cancellation''': cancelling the stream (or `take`, `via` a stage that finishes early, a failed
   *    downstream) interrupts the provider's thread.
   *  - '''Errors''': if the call fails mid-stream, the chunks already received are emitted first, then the stream
   *    fails with [[LLMException]]. A `bufferSize` below 1 fails the stream at once.
   */
  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    bufferSize: Int = LLMClientPekko.DefaultBufferSize
  ): Source[StreamedChunk, NotUsed]

  /**
   * An [[AgentPekko]] backed by this client: `Agent.builder(id, client)` with `configure` applied.
   * `Left` when the builder does not build.
   */
  def agent(id: String)(configure: AgentBuilder => AgentBuilder = identity): Result[AgentPekko]
}

object LLMClientPekko {

  /** Chunks held between the provider's thread and the consumer unless `bufferSize` says otherwise. */
  val DefaultBufferSize: Int = 64

  /** Wraps an already-constructed [[LLMClient]]. Does not manage its lifecycle. */
  def apply(underlying: LLMClient): LLMClientPekko = new Impl(underlying)

  final private class Impl(underlying: LLMClient) extends LLMClientPekko {

    def complete(conversation: Conversation, options: CompletionOptions)(using
      ec: ExecutionContext
    ): Future[Completion] =
      Future(blocking(underlying.complete(conversation, options))).flatMap(Impl.raised)

    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      bufferSize: Int
    ): Source[StreamedChunk, NotUsed] =
      BlockingSource("llm4s-pekko-llm", bufferSize)(() => new ChunkProducer(underlying, conversation, options))

    def agent(id: String)(configure: AgentBuilder => AgentBuilder): Result[AgentPekko] =
      configure(Agent.builder(id, underlying)).build().map(AgentPekko(_))
  }

  private object Impl {
    def raised[A](result: Result[A]): Future[A] =
      result match {
        case Right(a) => Future.successful(a)
        case Left(e)  => Future.failed(new LLMException(e))
      }
  }

  /** Feeds a provider's chunks to the stream; cancelling interrupts the thread this runs on. */
  final private class ChunkProducer(client: LLMClient, conversation: Conversation, options: CompletionOptions)
      extends Producer[StreamedChunk] {

    def run(emitter: Emitter[StreamedChunk]): Either[LLMError, Unit] =
      client.streamComplete(conversation, options, chunk => emitter.emit(chunk)).map(_ => ())

    def abandon(): Unit = ()
  }
}
