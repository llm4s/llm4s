package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.types.Result
import org.llm4s.util.Redaction

import scala.util.{ Failure, Success, Try }

/**
 * Turns code that throws into a `Result`, and an exception into an [[LLMError]].
 *
 * The extensions in [[org.llm4s.syntax]] (`Try(...).toResult`, `throwable.toLLMError`) are these functions under
 * another spelling.
 */
@Stable
object Safety {

  /**
   * Runs `thunk` and returns its value as a `Right`; a non-fatal exception it throws becomes a `Left`, mapped by
   * [[fromThrowable]]. A fatal one, an `InterruptedException` included, propagates, as from `scala.util.Try`.
   */
  def safely[A](thunk: => A): Result[A] = fromTry(Try(thunk))

  /** The `Try`'s value as a `Right`, or its exception, mapped by [[fromThrowable]], as a `Left`. */
  def fromTry[A](t: Try[A]): Result[A] = t match {
    case Success(v) => Right(v)
    case Failure(e) => Left(fromThrowable(e))
  }

  /**
   * The error for an exception, by its type alone:
   *
   *  - a cancellation - mapped while the current thread is interrupted, or caused by an `InterruptedException` or
   *    a `ClosedByInterruptException` (see [[CancelledError.isCancellation]]) - is a [[CancelledError]]. Mapping
   *    classifies only and never sets the interrupt flag: it may run on a thread other than the interrupted one,
   *    such as a `Future` callback's pool thread;
   *  - a `java.io.InterruptedIOException` that is not a cancellation - a `SocketTimeoutException`, OkHttp's call
   *    timeout - is a [[NetworkError]] `Request timeout`;
   *  - a `java.net.ConnectException` is a [[NetworkError]] `Connection failed`;
   *  - anything else is an [[UnknownError]] carrying the exception as its cause.
   *
   * The exception's message is never read for meaning: an exception that says `HTTP 401` is an `UnknownError`
   * here. A client that knows it holds a vendor SDK's exception classifies it itself.
   *
   * The message of an `UnknownError` built here is redacted and capped (`Redaction.safeBody`), since an
   * exception's message often echoes a response body and with it a request header or a key. The cause is not:
   * it is the original exception, whose own `getMessage` and stack trace are unredacted, so a logger or handler
   * that prints the cause (`logger.error(msg, err.cause)`, `printStackTrace`) writes that text in the clear.
   */
  def fromThrowable(t: Throwable): LLMError = t match {
    case ex if CancelledError.isCancellation(ex) =>
      CancelledError("unknown", Some(ex))
    // A SocketTimeoutException, and OkHttp's call timeout (`InterruptedIOException("timeout")`, which the
    // openai-java and Anthropic SDKs surface): a real interruption was classified as a cancellation above.
    case _: java.io.InterruptedIOException =>
      NetworkError("Request timeout", Some(t), "unknown")
    case _: java.net.ConnectException =>
      NetworkError("Connection failed", Some(t), "unknown")
    case ex =>
      UnknownError(Option(ex.getMessage).fold("Unknown error")(Redaction.safeBody(_)), ex)
  }
}
