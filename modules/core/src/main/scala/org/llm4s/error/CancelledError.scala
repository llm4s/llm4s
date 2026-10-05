package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.types.Result

import java.nio.channels.ClosedByInterruptException
import java.util.{ Collections, IdentityHashMap }

/**
 * The operation was cancelled by interrupting its thread.
 *
 * Interruption is how llm4s cancels work: an interrupted provider or tool call returns
 * `Left(CancelledError)` and leaves the thread's interrupt flag set, so its caller can still see
 * the interrupt. It is never retried.
 *
 * @param operation what was cancelled, e.g. `"http.POST"` or `"openai.complete"`
 */
@Stable
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
   * is an `InterruptedException` or a `ClosedByInterruptException`.
   *
   * The flag test matters on a virtual thread, where an interrupt during a blocking socket read
   * closes the socket and surfaces as an ordinary `SocketException`. A bare
   * `java.io.InterruptedIOException` (including `SocketTimeoutException`) is not enough on its
   * own: OkHttp reports its call timeout as `InterruptedIOException("timeout")`, and a timeout
   * stays a timeout. It counts only through the flag or an `InterruptedException` beneath it.
   */
  def isCancellation(t: Throwable): Boolean =
    Thread.currentThread().isInterrupted || causedByInterruption(t)

  /**
   * `Some(CancelledError)` if `t` is a cancellation (see [[isCancellation]]); otherwise `None`.
   *
   * Classifies only: it never sets the interrupt flag. The flag belongs to the thread that was
   * interrupted, and the code mapping `t` may be running on another one - a `Future` callback on
   * a pool thread, say. Code that itself caught an `InterruptedException` restores the flag.
   */
  def fromThrowable(t: Throwable, operation: String): Option[CancelledError] =
    Option.when(isCancellation(t))(CancelledError(operation, Some(t)))

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

  /** Whether `t`'s cause chain holds an interruption; a cause cycle is walked once. */
  private def causedByInterruption(t: Throwable): Boolean = {
    val seen = Collections.newSetFromMap(new IdentityHashMap[Throwable, java.lang.Boolean]())
    Iterator
      .iterate(t)(_.getCause)
      .takeWhile(c => c != null && seen.add(c))
      .exists {
        case _: InterruptedException | _: ClosedByInterruptException => true
        case _                                                       => false
      }
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
