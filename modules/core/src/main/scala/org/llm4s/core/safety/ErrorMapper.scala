package org.llm4s.core.safety

import org.llm4s.error._

/** Maps arbitrary Throwables into domain-specific LLMError values. */
trait ErrorMapper {
  def apply(t: Throwable): LLMError
}

/**
 * Default mapping.
 *
 * A cancellation - mapped while the current thread is interrupted, or caused by an
 * `InterruptedException` or `ClosedByInterruptException` (see `CancelledError.isCancellation`) -
 * becomes a [[CancelledError]]. Mapping classifies only and never sets the interrupt flag: it may
 * run on a thread other than the interrupted one, such as a `Future` callback's pool thread.
 */
object DefaultErrorMapper extends ErrorMapper {
  def apply(t: Throwable): LLMError = t match {
    case ex if CancelledError.isCancellation(ex) =>
      CancelledError("unknown", Some(ex))
    case _: java.net.SocketTimeoutException =>
      NetworkError("Request timeout", Some(t), "unknown")
    case _: java.net.ConnectException =>
      NetworkError("Connection failed", Some(t), "unknown")
    case ex if Option(ex.getMessage).exists(_.contains("401")) =>
      AuthenticationError("unknown", "Authentication failed")
    case ex if Option(ex.getMessage).exists(_.contains("429")) =>
      RateLimitError("unknown")
    case ex =>
      UnknownError(Option(ex.getMessage).getOrElse("Unknown error"), ex)
  }
}
