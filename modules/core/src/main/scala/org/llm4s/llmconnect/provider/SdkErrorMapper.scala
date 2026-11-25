package org.llm4s.llmconnect.provider

import org.llm4s.error._
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.util.Redaction

import java.util.regex.Pattern

/**
 * Maps an exception thrown by a vendor SDK (openai-java, anthropic-java, the AWS SDK, a Spring AI model), which
 * often reports an HTTP failure only in its message.
 *
 * It is [[org.llm4s.error.Safety.fromThrowable Safety.fromThrowable]] - a cancellation, a timeout, a refused
 * connection - plus one step for what that leaves an [[org.llm4s.error.UnknownError UnknownError]]: an exception
 * whose message names HTTP status 401 becomes an [[org.llm4s.error.AuthenticationError AuthenticationError]] (code
 * `401`, carrying the redacted message), and one naming 429 a [[org.llm4s.error.RateLimitError RateLimitError]]
 * whose message ends with the redacted message; 401 wins when a message names both. A message names a status only
 * in an HTTP context, at a word boundary: after `HTTP`,
 * `status`, `status code`, `http status`, `error code` or `response code`, optionally quoted as a
 * JSON key (`HTTP 401`, `Status Code: 429`, `"status": 401`, `http_status=429`), before its reason
 * phrase (`401 Unauthorized`, `429 Too Many Requests`), or leading the message as `openai-java` and
 * `anthropic-java` write it (`401: <body>`, also behind up to eight wrapping exceptions'
 * `ClassName: `). A message that merely contains the digits - an index, an id, a port or a count -
 * stays an `UnknownError`.
 *
 * Known false positive: that last, SDK shape carries no other HTTP marker, so any message that
 * starts with `401: ` or `429: ` (optionally behind `SomeException: ` prefixes) is classified, even
 * when the number means something else, such as a line number in `429: unexpected token`. That is why this
 * reading is applied only where the exception is known to come from an SDK's HTTP call, and not by
 * `Safety.fromThrowable`, `toResult` or `toLLMError`.
 *
 * Only the first 4 KiB and the last 1 KiB of a message are scanned (see `ScanHead`), and every
 * pattern runs in linear time, so mapping an exception that carries a large response body is cheap.
 */
private[llm4s] object SdkErrorMapper {

  private val Unauthorized    = 401
  private val TooManyRequests = 429

  /**
   * The patterns that name an HTTP status; each captures the status in one of its groups.
   *
   * An exception's message can carry an arbitrary response body, so every pattern must run in linear
   * time: each whitespace or separator run is possessive (`*+`, `++`), so a failed match never
   * re-splits a long run between adjacent quantifiers (`\s*[:=]?\s*` over 20,000 spaces was quadratic
   * and took seconds), and the wrapper-prefix group of the last pattern is bounded, because an unbounded
   * group repetition recurses once per repetition in `java.util.regex` and overflowed the stack.
   */
  private[provider] val StatusPatterns: List[Pattern] = List(
    // "HTTP 401", "HTTP/1.1 429", "HTTP error 401", "http_status=401", "status 429", "Status Code: 401",
    // "statusCode=429", "\"status\": 401", "\"status_code\":\"429\"", "Error code: 429", "response code 401"
    """(?i)\b(?:http(?:/\d(?:\.\d)?)?(?:[\s_-]++(?:error|status)(?:[\s_-]*+code)?)?|status(?:[\s_-]*+code)?|error[\s_-]*+code|response[\s_-]*+code)"?\s*+(?:[:=]\s*+)?"?(401|429)\b""",
    // "401 Unauthorized", "Error: 401 - Unauthorized", "429 Too Many Requests"
    """(?i)\b(?:(401)\s*+(?:[-:]\s*+)?unauthori[sz]ed|(429)\s*+(?:[-:]\s*+)?too\s++many\s++requests)\b""",
    // openai-java and anthropic-java: "401: <body>", or "com.x.SomeException: 401: <body>" once wrapped,
    // behind at most eight wrappers. The class name is possessive and its suffix checked by lookbehind.
    """^(?:[\w$.]++(?<=Exception|Error):\s++){0,8}+(401|429):\s"""
  ).map(Pattern.compile)

  /**
   * How much of a message is scanned: its first `ScanHead` and last `ScanTail` characters. A status
   * leads the message in every shape above but one - the SDK's `401: `, behind at most eight wrapping
   * `ClassName: ` prefixes of well under a hundred characters each, and an HTTP library's `HTTP 401` or
   * `401 Unauthorized from POST <url>` - so 4 KiB of head covers them with room to spare. The AWS SDK
   * appends its status instead (`<message> (Service: BedrockRuntime, Status Code: 429, Request ID: <id>)`,
   * about a hundred characters), which the last 1 KiB covers. The patterns are linear already; the cap
   * bounds the work when a message embeds a whole response body or a megabyte of output.
   */
  private val ScanHead = 4096
  private val ScanTail = 1024

  /** The HTTP statuses (401 and 429 only) that `message` names. */
  private def statusesIn(message: String): Set[Int] = {
    val regions =
      if (message.length <= ScanHead + ScanTail) List(0 -> message.length)
      else List(0                                       -> ScanHead, (message.length - ScanTail) -> message.length)
    (for {
      (from, to) <- regions
      pattern    <- StatusPatterns
      status     <- statusesIn(pattern, message, from, to)
    } yield status).toSet
  }

  // Transparent bounds let `\b` see the characters either side of a region, so a cut through a number
  // is no word boundary; non-anchoring bounds keep `^` at the start of the message, not of the region.
  private def statusesIn(pattern: Pattern, message: String, from: Int, to: Int): List[Int] = {
    val matcher = pattern.matcher(message).region(from, to).useTransparentBounds(true).useAnchoringBounds(false)
    Iterator
      .continually(matcher.find())
      .takeWhile(identity)
      .flatMap(_ => (1 to matcher.groupCount).flatMap(group => Option(matcher.group(group))))
      .map(_.toInt)
      .toList
  }

  /**
   * The error for `t`, an exception from `provider`'s SDK.
   *
   * @param provider the provider name the `AuthenticationError` or `RateLimitError` carries
   */
  def apply(t: Throwable, provider: String): LLMError = Safety.fromThrowable(t) match {
    case unknown: UnknownError =>
      val message  = Option(t.getMessage)
      val statuses = message.map(statusesIn).getOrElse(Set.empty)
      if (statuses.contains(Unauthorized))
        AuthenticationError(ProviderId(provider), Redaction.safeBody(message.getOrElse("")), Unauthorized.toString)
      else if (statuses.contains(TooManyRequests))
        RateLimitError(ProviderId(provider))
          .withHttpStatus(TooManyRequests)
          .withDetail(Redaction.safeBody(message.getOrElse("")))
      else unknown
    case classified => classified
  }
}
