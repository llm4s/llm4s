package org.llm4s.error

/**
 * Raised when an internal processing operation fails, such as image or audio processing.
 *
 * This is a [[NonRecoverableError]]: it indicates a failure in processing
 * multimodal inputs (e.g., resampling audio, converting image formats) before
 * sending them to the provider. This usually requires fixing the input media
 * or addressing the underlying cause.
 *
 * @param message human-readable description of the processing failure
 * @param operation the specific processing operation that failed (e.g., "audio-resample")
 * @param cause optional underlying exception that caused the processing to fail
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

  // Audio-specific processing errors
  def audioResample(message: String, cause: Option[Throwable] = None): ProcessingError =
    apply("audio-resample", message, cause)

  def audioConversion(message: String, cause: Option[Throwable] = None): ProcessingError =
    apply("audio-conversion", message, cause)

  def audioTrimming(message: String, cause: Option[Throwable] = None): ProcessingError =
    apply("audio-trimming", message, cause)

  def audioValidation(message: String, cause: Option[Throwable] = None): ProcessingError =
    apply("audio-validation", message, cause)
}
