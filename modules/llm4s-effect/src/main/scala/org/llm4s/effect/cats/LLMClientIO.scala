package org.llm4s.effect.cats

import cats.effect.kernel.{ Async, Resource }
import cats.syntax.flatMap.*
import fs2.{ Chunk, Stream }
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

  /** Runs the streaming call on the blocking pool, emits all chunks as an fs2 [[Stream]]. */
  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Stream[F, StreamedChunk]

  /** Creates an [[AgentIO]] backed by this client. */
  def agent(): AgentIO[F]
}

object LLMClientIO {

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
      Stream.evalUnChunk {
        F.blocking {
          val buf = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
          (underlying.streamComplete(conversation, options, buf += _), buf)
        }.flatMap { case (result, buf) =>
          result match {
            case Right(_)  => F.pure(Chunk.from(buf.toSeq))
            case Left(err) => F.raiseError(new LLMException(err))
          }
        }
      }

    def agent(): AgentIO[F] = AgentIO[F](new Agent(underlying))
  }
}
