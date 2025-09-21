package org.llm4s.error

/**
 * System errors for unexpected exceptions
 * @param message error message
 * @param cause error cause
 */
final case class SystemError private (
  override val message: String,
  cause: Option[Throwable]
) extends LLMError
    with RecoverableError {
  override val context: Map[String, String] =
    cause.map(c => Map("exceptionType" -> c.getClass.getSimpleName)).getOrElse(Map.empty)
}

object SystemError {
  def apply(message: String, cause: Option[Throwable] = None): SystemError =
    new SystemError(message, cause)
}
