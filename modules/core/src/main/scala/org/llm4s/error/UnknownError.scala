package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * Unknown/unexpected errors with full exception context
 */
@Stable
final case class UnknownError private (
  override val message: String,
  cause: Throwable
) extends LLMError
    with NonRecoverableError {
  override val context: Map[String, String] = Map(
    "exceptionType" -> cause.getClass.getSimpleName,
    "stackTrace"    -> cause.getStackTrace.take(3).mkString("; ")
  )
}

object UnknownError {
  def apply(message: String, cause: Throwable): UnknownError =
    new UnknownError(message, cause)

  /** Unapply extractor for pattern matching */
  def unapply(error: UnknownError): Option[(String, Throwable)] =
    Some((error.message, error.cause))
}
