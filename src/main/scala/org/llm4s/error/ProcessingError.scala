package org.llm4s.error

/**
 * Processing errors for operations like image processing
 */
final case class ProcessingError private (
  override val message: String,
  operation: String,
  cause: Option[Throwable]
) extends LLMError
    with NonRecoverableError {
  override val context: Map[String, String] = Map("operation" -> operation) ++
    cause.map(c => Map("cause" -> c.getMessage)).getOrElse(Map.empty)
}

object ProcessingError {
  def apply(operation: String, message: String, cause: Option[Throwable] = None): ProcessingError =
    new ProcessingError(s"Processing failed during $operation: $message", operation, cause)

  /** Unapply extractor for pattern matching */
  def unapply(error: ProcessingError): Option[(String, String, Option[Throwable])] =
    Some((error.message, error.operation, error.cause))
}
