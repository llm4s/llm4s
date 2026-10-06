package org.llm4s.javaapi

import org.llm4s.agent.{ AgentEventBuffer, AgentResult, AgentRun }
import org.llm4s.core.safety.Safety
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.annotation.tailrec
import scala.util.Using

/**
 * A streaming agent turn from [[JAgent.stream]], [[JAgent.streamResume]] or [[JAgent.streamRecover]],
 * whose events are being delivered to its [[AgentStreamListener]]: cancel it, or await its result.
 *
 * {{{
 * AgentStream stream = agent.stream(threadId, "Explain monads", listener).get();
 * LlmResult<AgentResult> result = stream.await();   // or stream.cancel()
 * }}}
 */
final class AgentStream private (run: AgentRun, buffer: AgentEventBuffer, listener: AgentStreamListener) {

  private val cancelled = new AtomicBoolean(false)
  private val delivered = new CountDownLatch(1)
  private val outcome =
    new AtomicReference[Result[AgentResult]](Left(ValidationError("stream", "the stream ended without an outcome")))
  private val deliverer: Thread =
    Thread.ofVirtual().name(s"llm4s-java-stream-${run.threadId.value}").unstarted(() => deliver())

  /**
   * Cancels the turn and returns once it has ended; the thread is left for [[JAgent.streamRecover]].
   * The listener receives no event that it was not already handling, then [[AgentStreamListener.onError]]
   * with the cancellation - or `onComplete`, for a turn that had already ended. Safe to call more than
   * once, from any thread, the listener's included.
   */
  def cancel(): Unit = {
    cancelled.set(true)
    run.cancel()
    run.await(): Unit
    buffer.close()
  }

  /**
   * Blocks until the listener has returned from its last call - [[AgentStreamListener.onComplete]] or
   * [[AgentStreamListener.onError]] - then returns the same outcome. A call whose thread is
   * interrupted first returns a failed result (`CancelledError`), with the interrupt flag still set,
   * and the turn carries on: only [[cancel]] stops it. A call from the listener itself, which would
   * wait on itself, returns a failed result at once.
   */
  def await(): LlmResult[AgentResult] =
    if (Thread.currentThread() eq deliverer)
      LlmResult.failure(ValidationError("await", "called from the stream's own listener, which it would wait on"))
    else LlmResult.from(CancelledError.attempt("AgentStream.await")(Right(delivered.await())).flatMap(_ => outcome.get))

  /** Hands the buffer's events to the listener, then the turn's outcome; always opens `delivered`. */
  private def deliver(): Unit =
    Using.resource(new AutoCloseable { def close(): Unit = delivered.countDown() }) { _ =>
      val result = events()
      outcome.set(result)
      val terminal = Safety.safely(result.fold(e => listener.onError(new LlmException(e)), listener.onComplete))
      terminal.left.foreach(e =>
        AgentStream.logger.warn(s"An agent stream listener's terminal callback failed: ${e.message}")
      )
    }

  /**
   * Delivers events until the buffer ends - or the turn is cancelled - then returns the turn's
   * outcome. A consumer that stops (a disconnected subscription, or a listener that throws or
   * interrupts its own thread) cancels the turn and fails with why it stopped.
   */
  @tailrec private def events(): Result[AgentResult] = {
    val step = for {
      taken <- guarded(buffer.take()).flatten
      more <- taken.filterNot(_ => cancelled.get) match {
        case Some(event) => guarded(listener.onEvent(event)).map(_ => true)
        case None        => Right(false)
      }
    } yield more
    step match {
      case Right(true)  => events()
      case Right(false) => run.await()
      case Left(error) =>
        cancel()
        Left(error)
    }
  }

  /** `body`, with a throwable - an `InterruptedException` included, clearing the flag - as `Left`. */
  private def guarded[A](body: => A): Result[A] =
    CancelledError
      .catchInterrupt(Safety.safely(body))
      .fold(e => Left(CancelledError("AgentStream.listener", Some(e))), identity)
}

private[javaapi] object AgentStream {
  private val logger = LoggerFactory.getLogger(classOf[AgentStream])

  /** Events buffered between the turn's subscription and the listener, as for the fs2 and ZIO streams. */
  val BufferSize: Int = 256

  /** Starts delivering `buffer`'s events, from `run`, to `listener`. */
  def start(run: AgentRun, buffer: AgentEventBuffer, listener: AgentStreamListener): AgentStream = {
    val stream = new AgentStream(run, buffer, listener)
    stream.deliverer.start()
    stream
  }
}
