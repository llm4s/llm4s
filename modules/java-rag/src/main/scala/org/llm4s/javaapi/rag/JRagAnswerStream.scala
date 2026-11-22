package org.llm4s.javaapi.rag

import org.llm4s.core.safety.Safety
import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.javaapi.{ FacadeInternals, LlmException, LlmResult }
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.rag.{ RAGAnswerResult, RAGSearchResult }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.concurrent.{ CancellationException, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong, AtomicReference }
import scala.util.Using

/**
 * An answer being streamed from [[JRag.askStream]] to its [[JRagStreamListener]] (#2727): cancel it, or await the
 * answer. It is `AutoCloseable`, and `close()` cancels, so a try-with-resources block stops a call its body leaves.
 * It works as `JLlmClient.stream`'s `CompletionStream` does: the search and the model call run on a virtual thread of
 * their own, and cancelling interrupts that thread.
 *
 * {{{
 * try (JRagAnswerStream stream = rag.askStream(question, listener).get()) {
 *     LlmResult<JRagAnswer> answer = stream.await();
 * }
 * }}}
 */
final class JRagAnswerStream private[rag] (
  token: RagFacadeToken,
  call: (Seq[RAGSearchResult] => Unit, StreamedChunk => Unit) => Result[RAGAnswerResult],
  listener: JRagStreamListener,
  cancelWaitMillis: Long
) extends AutoCloseable {

  RagFacadeToken.check(token, "JRagAnswerStream")

  private val stopped   = new AtomicBoolean(false)
  private val failure   = new AtomicReference[Option[LLMError]](None)
  private val delivered = new CountDownLatch(1)
  private val outcome   = new AtomicReference[Option[Result[JRagAnswer]]](None)
  private val lock      = new Object
  // set just before the thread starts, so nothing the thread runs reads a field still being initialised
  private val worker = new AtomicReference[Thread]()

  /**
   * Cancels the call: its thread is interrupted, which is how llm4s providers are cancelled, and no further source or
   * piece is delivered. The listener then receives [[JRagStreamListener.onError]] with the cancellation - or
   * `onComplete`, for a call that had already ended. Returns once the listener has heard, or after 5 seconds when a
   * provider ignores the interrupt (logged at WARN). Safe to call more than once, from any thread, the listener's
   * included.
   */
  def cancel(): Unit = {
    stop()
    if (!inListener && !JRagAnswerStream.awaitQuietly(delivered, cancelWaitMillis))
      JRagAnswerStream.logger.warn(s"${worker.get.getName}: the call ignored its interrupt for $cancelWaitMillis ms")
  }

  /** Cancels the call without waiting for the listener to hear: for a caller that must not block, such as Reactor. */
  private[llm4s] def stop(): Unit = {
    stopped.set(true)
    worker.get.interrupt()
  }

  /**
   * Whether this thread is the stream's own: the worker, or a provider's callback thread inside the listener (which
   * holds `lock`). Waiting there for the listener to return would wait on itself.
   */
  private def inListener: Boolean = (Thread.currentThread() eq worker.get) || Thread.holdsLock(lock)

  /** Cancels the call; see [[cancel]]. */
  override def close(): Unit = cancel()

  /**
   * Blocks until the listener has returned from its last call - [[JRagStreamListener.onComplete]] or
   * [[JRagStreamListener.onError]] - then returns the same outcome. A call whose thread is interrupted first returns a
   * failed result (`CANCELLED`) with the interrupt flag still set, and the stream carries on. A call from the listener
   * itself, which would wait on itself, returns a failed result at once.
   */
  def await(): LlmResult[JRagAnswer] =
    if (inListener)
      FacadeInternals.result(
        Left(ValidationError("await", "called from the stream's own listener, which it would wait on"))
      )
    else
      FacadeInternals.result(
        CancelledError
          .attempt("JRagAnswerStream.await")(Right(delivered.await()))
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
      val called = CancelledError.attempt("JRag.askStream")(
        Safety.safely(call(deliverSources, chunk => chunk.content.foreach(deliverChunk))).flatten
      )
      // a listener's own failure decides the outcome; a cancelled call that failed reports the cancellation, whatever
      // error the interrupted call returned, and one that had already completed keeps its answer
      val ended = failure.get
        .map(Left(_))
        .getOrElse(called match {
          case Left(_) if stopped.get => Left(CancelledError("JRagAnswerStream.cancel"))
          case other                  => other
        })
      val result = ended.map(JRag.answer)
      outcome.set(Some(result))
      Thread.interrupted(): Unit
      val terminal = Safety.safely(lock.synchronized {
        result.fold(e => listener.onError(new LlmException(e)), listener.onComplete)
      })
      terminal.left.foreach(e =>
        JRagAnswerStream.logger.warn(s"A RAG answer stream listener's terminal callback failed: ${e.message}")
      )
    }

  /**
   * The sources, unless the stream has stopped. Throwing here - after a cancel, or when the listener threw - makes
   * the pipeline end before it asks the model.
   */
  private def deliverSources(contexts: Seq[RAGSearchResult]): Unit = {
    if (!stopped.get) listening(listener.onSources(JRag.sources(contexts)))
    if (stopped.get) throw new CancellationException("the answer stream stopped before the model was asked")
  }

  /** One piece, unless the stream has stopped; a listener that throws stops it, interrupting the call. */
  private def deliverChunk(text: String): Unit =
    if (text.nonEmpty && !stopped.get) listening(listener.onChunk(text))

  private def listening(callback: => Unit): Unit =
    Safety.safely(lock.synchronized(callback)).left.foreach { error =>
      failure.compareAndSet(None, Some(error)): Unit
      stop()
    }

  // last, once every field above is set: the worker reads them all
  private val started =
    Thread.ofVirtual().name(s"llm4s-java-rag-stream-${JRagAnswerStream.ids.incrementAndGet()}").unstarted(() => run())
  worker.set(started)
  started.start()
}

private[rag] object JRagAnswerStream {
  private val logger = LoggerFactory.getLogger(classOf[JRagAnswerStream])
  private val ids    = new AtomicLong(0)

  /** How long `cancel()` waits for the listener to hear the outcome. */
  val CancelWaitMillis: Long = 5000L

  /** Waits up to `millis` for `latch`, keeping the caller's interrupt; whether it opened. */
  private def awaitQuietly(latch: CountDownLatch, millis: Long): Boolean = {
    val interrupted = Thread.interrupted()
    val opened      = CancelledError.catchInterrupt(latch.await(millis, TimeUnit.MILLISECONDS)).getOrElse(false)
    if (interrupted) Thread.currentThread().interrupt()
    opened
  }
}
