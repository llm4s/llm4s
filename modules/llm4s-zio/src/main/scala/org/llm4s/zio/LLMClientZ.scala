package org.llm4s.zio

import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import zio.{ ZIO, ZLayer }
import zio.stream.ZStream

/**
 * ZIO wrapper for [[LLMClient]].
 *
 * Blocking LLM calls are shifted to ZIO's blocking thread pool via
 * `ZIO.blocking`, keeping the fiber executor free.
 * `LLMError` is used directly as the error channel type — no wrapping needed.
 */
trait LLMClientZ {

  def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): ZIO[Any, LLMError, Completion]

  /** Runs the streaming call on the blocking pool, emits all chunks as a [[ZStream]]. */
  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): ZStream[Any, LLMError, StreamedChunk]

  /** Creates an [[AgentZ]] backed by this client. */
  def agent(): AgentZ
}

object LLMClientZ {

  /** Wraps an already-constructed [[LLMClient]]. Does not manage its lifecycle. */
  def apply(underlying: LLMClient): LLMClientZ = new Impl(underlying)

  /**
   * ZLayer that acquires an [[LLMClient]] from the environment (via [[Llm4sConfig]])
   * on the blocking thread pool and finalises it on scope exit.
   */
  val layer: ZLayer[Any, LLMError, LLMClientZ] =
    ZLayer.scoped {
      ZIO
        .blocking {
          ZIO.fromEither {
            for {
              registry <- Llm4sConfig.modelRegistryService()
              config   <- Llm4sConfig.defaultProvider()
              client   <- LLMConnect.getClient(config)(using registry)
            } yield client
          }
        }
        .flatMap(client => ZIO.acquireRelease(ZIO.succeed(LLMClientZ(client)))(_ => ZIO.succeed(client.close())))
    }

  final private class Impl(underlying: LLMClient) extends LLMClientZ {

    def complete(
      conversation: Conversation,
      options: CompletionOptions = CompletionOptions()
    ): ZIO[Any, LLMError, Completion] =
      ZIO.blocking {
        ZIO.fromEither(underlying.complete(conversation, options))
      }

    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions = CompletionOptions()
    ): ZStream[Any, LLMError, StreamedChunk] = {
      val fetchChunks: ZIO[Any, LLMError, List[StreamedChunk]] = ZIO.blocking {
        ZIO.fromEither {
          val buf = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
          underlying.streamComplete(conversation, options, buf.append(_)).map(_ => buf.toList)
        }
      }
      ZStream.fromZIO(fetchChunks).flatMap(ZStream.fromIterable(_))
    }

    def agent(): AgentZ = AgentZ(new Agent(underlying))
  }
}
