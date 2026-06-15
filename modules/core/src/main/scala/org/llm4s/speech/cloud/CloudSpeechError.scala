package org.llm4s.speech.cloud

import org.llm4s.error.{ AuthenticationError, ConfigurationError, LLMError, NetworkError, RateLimitError, ServiceError }

/** Utilities for mapping HTTP status codes to typed LLMErrors for cloud speech providers. */
object CloudSpeechError {

  /** Map HTTP status code to appropriate LLMError for a given provider. */
  def fromHttpStatus(statusCode: Int, provider: String, body: String): LLMError =
    statusCode match {
      case 401 => AuthenticationError(provider, s"Unauthorized: $body")
      case 403 => AuthenticationError(provider, s"Forbidden: $body")
      case 429 => RateLimitError(provider)
      case _   => ServiceError(statusCode, provider, body)
    }

  /** Map a thrown exception to a NetworkError. */
  def fromThrowable(cause: Throwable, endpoint: String): NetworkError =
    NetworkError(s"Network error calling $endpoint: ${cause.getMessage}", Some(cause), endpoint)

  /** Build a ConfigurationError for missing API keys. */
  def missingKey(provider: String, keyName: String): ConfigurationError =
    ConfigurationError(
      s"Missing $keyName for $provider speech provider",
      List(keyName)
    )
}
