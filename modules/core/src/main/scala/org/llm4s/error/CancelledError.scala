package org.llm4s.error

import org.llm4s.types.Result

import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import scala.annotation.tailrec

/**
 * The operation was cancelled by interrupting its thread.
 *
 * Interruption is how llm4s cancels work: an interrupted provider or tool call returns
 * `Left(CancelledError)` and leaves the thread's interrupt flag set, so its caller can still see
 * the interrupt. It is never retried.
 *
 * @param operation what was cancelled, e.g. `"http.POST"` or `"openai.complete"`
 */
final case class CancelledError private (
  message: String,
  operation: String,
  cause: Option[Throwable]
) extends LLMError
    with NonRecoverableError {
  override val context: Map[String, String] = Map("operation" -> operation)
}

object CancelledError {

  def apply(operation: String, cause: Option[Throwable] = None): CancelledError =
    new CancelledError(s"$operation was cancelled", operation, cause)

  /**
   * Whether `t` is a cancellation: the current thread is interrupted, or `t` or one of its causes
   * is an interruption. The flag test matters on a virtual thread, where an interrupt during a
   * blocking socket read closes the socket and surfaces as an ordinary `SocketException`.
   */
  def isCancellation(t: Throwable): Boolean =
    Thread.currentThread().isInterrupted || causedByInterruption(t)

  /** `Some(CancelledError)` if `t` is a cancellation, with the interrupt flag set; otherwise `None`. */
  def fromThrowable(t: Throwable, operation: String): Option[CancelledError] =
    Option.when(isCancellation(t)) {
      Thread.currentThread().interrupt()
      CancelledError(operation, Some(t))
    }

  /** A failure that ends while the current thread is interrupted is a cancellation. */
  def whenInterrupted[A](result: Result[A], operation: String): Result[A] =
    result match {
      case Left(_: CancelledError)                             => result
      case Left(other) if Thread.currentThread().isInterrupted => Left(CancelledError(operation, causeOf(other)))
      case _                                                   => result
    }

  /**
   * Runs `body`, returning a thrown `InterruptedException` as `Left(CancelledError)` with the flag
   * set, and applying [[whenInterrupted]] to what it returns. Other exceptions propagate.
   */
  def attempt[A](operation: String)(body: => Result[A]): Result[A] =
    catchInterrupt(body) match {
      case Right(result) => whenInterrupted(result, operation)
      case Left(e) =>
        Thread.currentThread().interrupt()
        Left(CancelledError(operation, Some(e)))
    }

  /**
   * Runs `body`, returning a thrown `InterruptedException` as `Left`; the flag is left as the throw
   * left it (cleared). llm4s modules use this instead of `try`/`catch`.
   *
   * Not `scala.util.control.Exception.catching`, which rethrows `InterruptedException` by design.
   */
  private[llm4s] def catchInterrupt[A](body: => A): Either[InterruptedException, A] = {
    // scalafix:off DisableSyntax.NoKeywordCatch
    val outcome: Either[InterruptedException, A] =
      try Right(body)
      catch {
        case e: InterruptedException => Left(e)
      }
    // scalafix:on DisableSyntax.NoKeywordCatch
    outcome
  }

  @tailrec private def causedByInterruption(t: Throwable): Boolean =
    if t == null then false
    else {
      // A SocketTimeoutException is an InterruptedIOException by inheritance only: it is a timeout.
      val interruption = t match {
        case _: java.net.SocketTimeoutException                                                  => false
        case _: InterruptedException | _: InterruptedIOException | _: ClosedByInterruptException => true
        case _                                                                                   => false
      }
      val next = t.getCause
      interruption || (next != null && (next ne t) && causedByInterruption(next))
    }

  private def causeOf(error: LLMError): Option[Throwable] =
    error match {
      case e: UnknownError   => Some(e.cause)
      case e: NetworkError   => e.cause
      case e: ExecutionError => e.cause
      case e: TimeoutError   => e.cause
      case _                 => None
    }
}
