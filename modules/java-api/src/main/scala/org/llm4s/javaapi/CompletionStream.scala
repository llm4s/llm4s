package org.llm4s.javaapi

import org.llm4s.core.safety.Safety
import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.util.Using

/**
 * A completion being streamed from [[JLlmClient.stream]] to its [[CompletionStreamListener]]: cancel it, or await its
 * reply. It is `AutoCloseable`, and `close()` cancels, so a try-with-resources block stops a call its body leaves.
 *
 * {{{
 * try (CompletionStream stream = client.stream(conversation, piece -> ui.append(piece)).get()) {
 *     LlmResult<JCompletion> reply = stream.await();
 * }
 * }}}
 */
final class CompletionStream private[javaapi] (
  token: FacadeToken,
  client: LLMClient,
  conversation: Conversation,
  options: CompletionOptions,
  listener: CompletionStreamListener,
  cancelWaitMillis: Long
) extends AutoCloseable {

  FacadeToken.check(token, "CompletionStream")

  private val stopped   = new AtomicBoolean(false)
  private val failure   = new AtomicReference[Option[LLMError]](None)
  private val delivered = new CountDownLatch(1)
  private val outcome   = new AtomicReference[Option[Result[JCompletion]]](None)
  private val lock      = new Object
  // set just before the thread starts, so nothing the thread runs reads a field still being initialised
  private val worker = new AtomicReference[Thread]()

  /**
   * Cancels the call: the provider's thread is interrupted, which is how llm4s providers are cancelled, and no further
   * piece is delivered. The listener then receives [[CompletionStreamListener.onError]] with the cancellation - or
   * `onComplete`, for a call that had already ended. Returns once the listener has heard, or after 5 seconds when a
   * provider ignores the interrupt (logged at WARN). Safe to call more than once, from any thread, the listener's
   * included.
   */
  def cancel(): Unit = {
    stopped.set(true)
    worker.get.interrupt()
    if (!inListener && !CompletionStream.awaitQuietly(delivered, cancelWaitMillis))
      CompletionStream.logger.warn(
        s"${worker.get.getName}: the provider ignored its interrupt for ${cancelWaitMillis} ms"
      )
  }

  /**
   * Whether this thread is the stream's own: the worker, or a provider's callback thread inside the listener (which
   * holds `lock`). Waiting there for the listener to return would wait on itself.
   */
  private def inListener: Boolean = (Thread.currentThread() eq worker.get) || Thread.holdsLock(lock)

  /** Cancels the call; see [[cancel]]. */
  override def close(): Unit = cancel()

  /**
   * Blocks until the listener has returned from its last call - [[CompletionStreamListener.onComplete]] or
   * [[CompletionStreamListener.onError]] - then returns the same outcome. A call whose thread is interrupted first
   * returns a failed result (`CancelledError`) with the interrupt flag still set, and the stream carries on. A call
   * from the listener itself, which would wait on itself, returns a failed result at once.
   */
  def await(): LlmResult[JCompletion] =
    if (inListener)
      LlmResult.failure(ValidationError("await", "called from the stream's own listener, which it would wait on"))
    else
      FacadeInternals.result(
        CancelledError
          .attempt("CompletionStream.await")(Right(delivered.await()))
          .flatMap(_ =>
            outcome.get.getOrElse(Left(ValidationError("listener", "the stream's listener failed fatally")))
          )
      )

  /**
   * The call, then exactly one terminal callback; always opens `delivered`, even when the listener throws a fatal error
   * (one `Safety` does not capture), which skips the terminal callback and leaves [[await]] a failed result.
   */
  private def run(): Unit =
    Using.resource(new AutoCloseable { def close(): Unit = delivered.countDown() }) { _ =>
      val called = CancelledError.attempt("JLlmClient.stream")(
        Safety.safely(client.streamComplete(conversation, options, chunk => chunk.content.foreach(deliver))).flatten
      )
      // a listener's own failure decides the outcome; a cancelled call that failed reports the cancellation, whatever
      // error the interrupted provider returned, and one that had already completed keeps its reply
      val ended = failure.get
        .map(Left(_))
        .getOrElse(called match {
          case Left(_) if stopped.get => Left(CancelledError("CompletionStream.cancel"))
          case other                  => other
        })
      val result = ended.flatMap(c => Safety.safely(FacadeInternals.completion(c)))
      outcome.set(Some(result))
      Thread.interrupted(): Unit
      val terminal = Safety.safely(lock.synchronized {
        result.fold(e => listener.onError(new LlmException(e)), listener.onComplete)
      })
      terminal.left.foreach(e =>
        CompletionStream.logger.warn(s"A completion stream listener's terminal callback failed: ${e.message}")
      )
    }

  /** One piece, unless the stream has stopped; a listener that throws stops it, interrupting the provider. */
  private def deliver(text: String): Unit =
    if (text.nonEmpty && !stopped.get)
      Safety.safely(lock.synchronized(listener.onPartial(text))).left.foreach { error =>
        failure.compareAndSet(None, Some(error)): Unit
        stopped.set(true)
        worker.get.interrupt()
      }

  // last, once every field above is set: the worker reads them all
  private val started =
    Thread.ofVirtual().name(s"llm4s-java-completion-${CompletionStream.ids.incrementAndGet()}").unstarted(() => run())
  worker.set(started)
  started.start()
}

private[javaapi] object CompletionStream {
  private val logger = LoggerFactory.getLogger(classOf[CompletionStream])
  private val ids    = new java.util.concurrent.atomic.AtomicLong(0)

  /** Waits up to `millis` for `latch`, keeping the caller's interrupt; whether it opened. */
  private def awaitQuietly(latch: CountDownLatch, millis: Long): Boolean = {
    val interrupted = Thread.interrupted()
    val opened      = CancelledError.catchInterrupt(latch.await(millis, TimeUnit.MILLISECONDS)).getOrElse(false)
    if (interrupted) Thread.currentThread().interrupt()
    opened
  }
}
