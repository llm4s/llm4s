package org.llm4s.zio

import java.util.concurrent.CountDownLatch

import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.{ CancelledError, LLMError }
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import zio.{ Queue, Unsafe, ZIO, ZLayer }
import zio.stream.{ Take, ZStream }

/**
 * ZIO wrapper for [[LLMClient]].
 *
 * Blocking LLM calls are shifted to ZIO's blocking thread pool via
 * `ZIO.attemptBlockingInterrupt`, keeping the fiber executor free. Interrupting the fiber (or a
 * `timeout`) interrupts the provider thread, which is how llm4s providers are cancelled: they keep
 * the interrupt and return `Left(CancelledError)`. An exception thrown by a provider is a defect.
 * `LLMError` is used directly as the error channel type — no wrapping needed.
 */
trait LLMClientZ {

  def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): ZIO[Any, LLMError, Completion]

  /**
   * Streams chunks incrementally as a [[ZStream]].
   *
   * The underlying blocking call runs on an interruptible blocking thread and pushes each chunk
   * into a bounded queue (backpressure: the provider thread blocks while the consumer lags).
   * Chunks are delivered as they arrive, not after the call completes. If the call fails
   * mid-stream, chunks already received are emitted first and the stream then fails with the
   * `LLMError`. Stopping consumption early (e.g. `take`) or interrupting the fiber interrupts
   * the blocking call.
   */
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

  /** Capacity of the bounded buffer between the provider thread and the consumer. */
  private val StreamBufferSize = 64

  /**
   * ZLayer that acquires an [[LLMClient]] from the environment (via [[Llm4sConfig]])
   * on the blocking thread pool and finalises it on scope exit.
   */
  val layer: ZLayer[Any, LLMError, LLMClientZ] =
    ZLayer.scoped {
      val acquireClient: ZIO[Any, LLMError, LLMClient] =
        ZIO.blocking {
          ZIO.fromEither {
            for {
              registry <- Llm4sConfig.modelRegistryService()
              config   <- Llm4sConfig.defaultProvider()
              client   <- LLMConnect.getClient(config)(using registry)
            } yield client
          }
        }
      // Acquisition sits inside the bracket so an interruption cannot leak the client.
      ZIO
        .acquireRelease(acquireClient)(c => ZIO.attemptBlocking(c.close()).orDie)
        .map(LLMClientZ(_))
    }

  final private class Impl(underlying: LLMClient) extends LLMClientZ {

    def complete(
      conversation: Conversation,
      options: CompletionOptions = CompletionOptions()
    ): ZIO[Any, LLMError, Completion] =
      ZIO
        .attemptBlockingInterrupt(underlying.complete(conversation, options))
        .orDie
        .flatMap(ZIO.fromEither(_))

    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions = CompletionOptions()
    ): ZStream[Any, LLMError, StreamedChunk] =
      ZStream.unwrapScoped {
        for {
          queue <- Queue.bounded[Take[LLMError, StreamedChunk]](StreamBufferSize)
          rt    <- ZIO.runtime[Any]
          // Runs on a blocking thread. `onChunk` offers each chunk to the bounded queue and blocks
          // the provider thread while it is full (backpressure). The terminal Take is enqueued
          // after every chunk, so chunks received before an error precede it.
          _ <- ZIO
            .attemptBlockingInterrupt {
              underlying.streamComplete(
                conversation,
                options,
                chunk =>
                  Unsafe.unsafe { implicit u =>
                    // Not `rt.unsafe.run`: if this thread is interrupted while the offer is parked
                    // on a full queue, `run` throws and leaves the offer fiber suspended forever.
                    val offer = rt.unsafe.fork(queue.offer(Take.single(chunk)).unit)
                    val done  = new CountDownLatch(1)
                    offer.unsafe.addObserver(_ => done.countDown())
                    CancelledError.catchInterrupt(done.await()).left.foreach { e =>
                      rt.unsafe.fork(offer.interrupt)
                      throw e
                    }
                  }
              )
            }
            .foldZIO(
              t => queue.offer(Take.die(t)),
              {
                case Right(_)  => queue.offer(Take.end)
                case Left(err) => queue.offer(Take.fail(err))
              }
            )
            // Scoped: the blocking thread is interrupted if the consumer stops early or fails.
            .forkScoped
        } yield ZStream.fromQueue(queue).flattenTake
      }

    def agent(): AgentZ = AgentZ(new Agent(underlying))
  }
}
