package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * Raised when the LLM provider rejects the request due to authentication failures.
 *
 * It is not [[LLMError.isRetryable retryable]]: it cannot be fixed by retrying. The user must
 * provide valid credentials (e.g., API key, token) to resolve this error.
 * Examples include invalid API keys, expired tokens, or unauthorized access to a model.
 *
 * @param message human-readable description of the authentication failure
 * @param provider the name of the LLM provider (e.g., "openai", "anthropic")
 * @param code optional error code returned by the provider (e.g., "401", "INVALID_KEY")
 * @param httpStatus the HTTP status of the provider's response (401 or 403), when the error was mapped from one
 * @param requestId the provider's id for the refused request, when the response carried one
 */
@Stable
final case class AuthenticationError private (
  override val message: String,
  provider: String,
  override val code: Option[String],
  override val httpStatus: Option[Int],
  override val requestId: Option[String]
) extends LLMError {

  /** This error, carrying the HTTP status of the response it was mapped from. */
  def withHttpStatus(status: Int): AuthenticationError = copy(httpStatus = Some(status))

  /** This error, carrying the provider's id for the refused request. */
  def withRequestId(id: String): AuthenticationError = copy(requestId = Some(id))

  /** This error with `sentence` and a troubleshooting link appended to its message (#2285). */
  private[llm4s] def withHint(sentence: String, entry: TroubleshootingLinks.Entry): AuthenticationError =
    copy(message = TroubleshootingLinks.withAction(message, sentence, entry))

  override val context: Map[String, String] = Map("provider" -> provider) ++
    code.map("code" -> _).toMap ++ requestId.map("requestId" -> _)
}

object AuthenticationError {

  /** Create authentication error with auto-generated message */
  def apply(provider: String, details: String): AuthenticationError =
    new AuthenticationError(s"Authentication failed for $provider: $details", provider, None, None, None)

  /** Extracts the message, provider and code; the arity is fixed, and the fields added later are read by name. */
  def unapply(error: AuthenticationError): Option[(String, String, Option[String])] =
    Some((error.message, error.provider, error.code))

  /**
   * Create authentication error with specific code. A `code` that is an HTTP status of a refused credential
   * (`"401"`, `"403"`) is also the error's `httpStatus`.
   */
  def apply(provider: String, details: String, code: String): AuthenticationError =
    new AuthenticationError(
      s"Authentication failed for $provider: $details",
      provider,
      Some(code),
      Some(code).filter(c => c == "401" || c == "403").map(_.toInt),
      None
    )
}
