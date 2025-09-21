package org.llm4s.core.safety

import org.llm4s.error._

/** Maps arbitrary Throwables into domain-specific LLMError values. */
trait ErrorMapper {
  def apply(t: Throwable): LLMError
}

/** Default mapping that preserves existing behavior. */
object DefaultErrorMapper extends ErrorMapper {
  def apply(t: Throwable): LLMError = t match {
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
