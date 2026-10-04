package org.llm4s.effect.cats

import cats.effect.kernel.{ Async, Resource }
import cats.effect.std.{ Dispatcher, Queue }
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import fs2.Stream
import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }

/**
 * cats-effect wrapper for [[LLMClient]].
 *
 * All blocking LLM calls are shifted to the blocking thread pool via
 * `Async[F].blocking`, keeping the compute pool free for fibers.
 * `LLMError` values are surfaced as [[LLMException]] in the `F` error channel.
 */
trait LLMClientIO[F[_]] {

  def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): F[Completion]

  /**
   * Streams chunks incrementally as an fs2 [[Stream]].
   *
   * The underlying blocking call runs on an interruptible blocking thread and pushes each chunk
   * into a bounded queue (backpressure: the provider thread blocks while the consumer lags).
   * Chunks are delivered as they arrive, not after the call completes. If the call fails
   * mid-stream, chunks already received are emitted first and the stream then fails with
   * [[LLMException]]. Stopping consumption early (e.g. `take`) or cancelling the fiber
   * interrupts the blocking call.
   */
  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Stream[F, StreamedChunk]

  /** Creates an [[AgentIO]] backed by this client. */
  def agent(): AgentIO[F]
}

object LLMClientIO {

  /** Capacity of the bounded buffer between the provider thread and the consumer. */
  private val StreamBufferSize = 64

  /** Wraps an already-constructed [[LLMClient]]. Does not manage its lifecycle. */
  def apply[F[_]: Async](underlying: LLMClient): LLMClientIO[F] =
    new Impl[F](underlying)

  /**
   * Creates a [[Resource]] that acquires an [[LLMClient]] from the environment
   * (via [[Llm4sConfig]]) on the blocking thread pool and releases it on scope exit.
   */
  def resource[F[_]](using F: Async[F]): Resource[F, LLMClientIO[F]] =
    Resource
      .fromAutoCloseable {
        F.blocking {
          for {
            registry <- Llm4sConfig.modelRegistryService()
            config   <- Llm4sConfig.defaultProvider()
            client   <- LLMConnect.getClient(config)(using registry)
          } yield client
        }.flatMap {
          case Right(client) => F.pure(client)
          case Left(err)     => F.raiseError(new LLMException(err))
        }
      }
      .map(apply[F])

  final private class Impl[F[_]](underlying: LLMClient)(using F: Async[F]) extends LLMClientIO[F] {

    def complete(
      conversation: Conversation,
      options: CompletionOptions = CompletionOptions()
    ): F[Completion] =
      F.blocking(underlying.complete(conversation, options)).flatMap {
        case Right(c) => F.pure(c)
        case Left(e)  => F.raiseError(new LLMException(e))
      }

    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions = CompletionOptions()
    ): Stream[F, StreamedChunk] =
      Stream.eval(Queue.bounded[F, Option[Either[Throwable, StreamedChunk]]](StreamBufferSize)).flatMap { queue =>
        Stream.resource(Dispatcher.sequential[F]).flatMap { dispatcher =>
          // Runs on a blocking thread. `onChunk` offers each chunk to the bounded queue and blocks
          // the provider thread while the queue is full (backpressure). The terminal element is
          // enqueued after every chunk, so chunks received before an error precede it.
          val producer: F[Unit] =
            F.interruptible {
              underlying.streamComplete(
                conversation,
                options,
                chunk => dispatcher.unsafeRunSync(queue.offer(Some(Right(chunk))))
              )
            }.attempt
              .flatMap { outcome =>
                val terminal: Option[Throwable] = outcome match {
                  case Right(Right(_))  => None
                  case Right(Left(err)) => Some(new LLMException(err))
                  case Left(t)          => Some(t)
                }
                terminal.fold(F.unit)(t => queue.offer(Some(Left(t)))) >> queue.offer(None)
              }

          // `concurrently` cancels the producer (interrupting the blocking thread) when the
          // consumer stops early, fails, or is cancelled.
          Stream
            .fromQueueNoneTerminated(queue)
            .flatMap {
              case Right(chunk) => Stream.emit(chunk)
              case Left(t)      => Stream.raiseError[F](t)
            }
            .concurrently(Stream.eval(producer))
        }
      }

    def agent(): AgentIO[F] = AgentIO[F](new Agent(underlying))
  }
}
