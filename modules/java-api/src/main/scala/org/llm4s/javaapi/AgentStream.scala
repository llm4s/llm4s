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
  // set once the listener's events end; empty if the listener threw a fatal error
  private val outcome = new AtomicReference[Option[Result[AgentResult]]](None)
  private val deliverer: Thread =
    Thread.ofVirtual().name(s"llm4s-java-stream-${run.runId.value}").unstarted(() => deliver())

  /**
   * Cancels the turn and returns once it has ended; the thread is left for [[JAgent.streamRecover]].
   * The listener receives at most the event already being delivered, then
   * [[AgentStreamListener.onError]] with the cancellation - or `onComplete`, for a turn that had
   * already ended. Safe to call more than once, from any thread, the listener's included. An
   * interrupt does not cut the wait short: it is kept, and the thread's flag is set again on return.
   */
  def cancel(): Unit = {
    cancelled.set(true)
    run.cancel()
    val interrupted = awaitEnd(false)
    buffer.close()
    if (interrupted) Thread.currentThread().interrupt()
  }

  /**
   * Waits for the run's end, whatever interrupts the waiting thread: `run.await()` returns early, with
   * the flag set, when interrupted, so the flag is cleared and the wait repeated. Returns whether the
   * thread was interrupted, before or during the wait.
   */
  @tailrec private def awaitEnd(interrupted: Boolean): Boolean = {
    val cleared = Thread.interrupted()
    run.await(): Unit
    if (Thread.currentThread().isInterrupted) awaitEnd(true) else interrupted || cleared
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
    else
      LlmResult.from(
        CancelledError
          .attempt("AgentStream.await")(Right(delivered.await()))
          .flatMap(_ =>
            outcome.get.getOrElse(Left(ValidationError("listener", "the stream's listener failed fatally")))
          )
      )

  /**
   * Hands the buffer's events to the listener, then the turn's outcome; always opens `delivered`. A
   * fatal error from the listener (one `Safety` does not capture) skips the terminal callback; the
   * turn is cancelled and the buffer closed before the error ends this thread.
   */
  private def deliver(): Unit =
    Using.resource(new AutoCloseable {
      def close(): Unit = {
        if (outcome.get.isEmpty) cancel()
        delivered.countDown()
      }
    }) { _ =>
      val result = events()
      outcome.set(Some(result))
      // a listener that interrupted itself and then threw left the flag set (cancel() keeps it): clear it,
      // so the terminal callback is not interrupted. This is the stream's own thread; nothing else reads it.
      Thread.interrupted(): Unit
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
        case Some(event) => guarded(listener.onEvent(event)).flatMap(_ => notInterrupted)
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

  /**
   * After a listener call: a listener that set its own thread's interrupt flag has cancelled the run,
   * at once - not at a later blocking `take`, which never comes while events are queued. Clears the flag.
   */
  private def notInterrupted: Result[Boolean] =
    if (Thread.interrupted()) Left(CancelledError("AgentStream.listener")) else Right(true)

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
