package org.llm4s.core.safety

import org.llm4s.annotation.Stable
import org.llm4s.error._
import org.llm4s.util.Redaction

import scala.util.matching.Regex

/** Maps arbitrary Throwables into domain-specific LLMError values. */
@Stable
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
 *
 * An exception whose message names HTTP status 401 becomes an [[AuthenticationError]] (code `401`,
 * carrying the redacted message), and one naming 429 a [[RateLimitError]]; 401 wins when a message
 * names both. A message names a status only in an HTTP context, at a word boundary: after `HTTP`,
 * `status`, `status code`, `error code` or `response code` (`HTTP 401`, `Status Code: 429`),
 * before its reason phrase (`401 Unauthorized`, `429 Too Many Requests`), or leading the message
 * as `openai-java` and `anthropic-java` write it (`401: <body>`, also behind a wrapping exception's
 * `ClassName: `). A message that merely contains the digits - an index, an id, a port or a count -
 * is an [[UnknownError]] carrying the exception as its cause, as is anything else not matched above.
 */
@Stable
object DefaultErrorMapper extends ErrorMapper {

  private val Unauthorized    = 401
  private val TooManyRequests = 429

  /** The patterns that name an HTTP status; each captures the status in one of its groups. */
  private val StatusPatterns: List[Regex] = List(
    // "HTTP 401", "HTTP/1.1 429", "HTTP error 401", "status 429", "Status Code: 401", "statusCode=429",
    // "Error code: 429", "response code 401"
    """(?i)\b(?:http(?:/\d(?:\.\d)?)?(?:\s+(?:error|status))?|status(?:[\s_-]*code)?|error[\s_-]*code|response[\s_-]*code)\s*[:=]?\s*(401|429)\b""".r,
    // "401 Unauthorized", "Error: 401 - Unauthorized", "429 Too Many Requests"
    """(?i)\b(?:(401)\s*[-:]?\s*unauthori[sz]ed|(429)\s*[-:]?\s*too\s+many\s+requests)\b""".r,
    // openai-java and anthropic-java: "401: <body>", or "com.x.SomeException: 401: <body>" once wrapped
    """^(?:[\w$.]*(?:Exception|Error):\s+)*(401|429):\s""".r
  )

  /** The HTTP statuses (401 and 429 only) that `message` names. */
  private def statusesIn(message: String): Set[Int] =
    StatusPatterns.iterator
      .flatMap(_.findAllMatchIn(message))
      .flatMap(_.subgroups.flatMap(Option(_)))
      .map(_.toInt)
      .toSet

  def apply(t: Throwable): LLMError = t match {
    case ex if CancelledError.isCancellation(ex) =>
      CancelledError("unknown", Some(ex))
    case _: java.net.SocketTimeoutException =>
      NetworkError("Request timeout", Some(t), "unknown")
    case _: java.net.ConnectException =>
      NetworkError("Connection failed", Some(t), "unknown")
    case ex =>
      val message  = Option(ex.getMessage)
      val statuses = message.map(statusesIn).getOrElse(Set.empty)
      if (statuses.contains(Unauthorized))
        AuthenticationError("unknown", Redaction.redact(message.getOrElse("")), Unauthorized.toString)
      else if (statuses.contains(TooManyRequests))
        // RateLimitError has no field for a cause or a detail message; the exception cannot be kept.
        RateLimitError("unknown")
      else
        UnknownError(message.getOrElse("Unknown error"), ex)
  }
}
