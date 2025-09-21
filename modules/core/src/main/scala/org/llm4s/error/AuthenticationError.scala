package org.llm4s.error

/**
 * Authentication-related errors
 */
final case class AuthenticationError private (
  override val message: String,
  provider: String,
  override val code: Option[String]
) extends LLMError
    with NonRecoverableError {
  override val context: Map[String, String] = Map("provider" -> provider) ++
    code.map("code" -> _).toMap
}

object AuthenticationError {

  /** Create authentication error with auto-generated message */
  def apply(provider: String, details: String): AuthenticationError =
    AuthenticationError(s"Authentication failed for $provider: $details", provider, None)

  /** Unapply extractor for pattern matching */
  def unapply(error: AuthenticationError): Option[(String, String, Option[String])] =
    Some((error.message, error.provider, error.code))

  /** Create authentication error with specific code */
  def apply(provider: String, details: String, code: String): AuthenticationError =
    AuthenticationError(s"Authentication failed for $provider: $details", provider, Some(code))

  /** Create from HTTP 401 response */
  def unauthorized(provider: String): AuthenticationError =
    apply(provider, "Unauthorized access", "401")

  /** Create for invalid API key */
  def invalidApiKey(provider: String): AuthenticationError =
    apply(provider, "Invalid API key", "INVALID_KEY")
}
