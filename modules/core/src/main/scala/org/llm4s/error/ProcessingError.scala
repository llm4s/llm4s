package org.llm4s.error

import org.llm4s.annotation.Stable

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
@Stable
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

  /**
   * Creates a processing error for a failed `operation`.
   *
   * The message is `Processing failed during <operation>: <message>`. `context` holds `operation` and,
   * when a `cause` is given, `cause` set to that exception's `getMessage` (`null` if the exception has
   * no message).
   *
   * @param operation the processing operation that failed (e.g. `"audio-resample"`)
   * @param message what went wrong, appended after the operation name in the error message
   * @param cause the underlying exception, if any
   * @return the processing error
   */
  def apply(operation: String, message: String, cause: Option[Throwable] = None): ProcessingError =
    new ProcessingError(s"Processing failed during $operation: $message", operation, cause)

  /** Unapply extractor for pattern matching */
  def unapply(error: ProcessingError): Option[(String, String, Option[Throwable])] =
    Some((error.message, error.operation, error.cause))

  // Audio-specific processing errors
  /**
   * Creates a processing error for a failed audio resampling: `operation` is `"audio-resample"`.
   *
   * @example
   * {{{
   * val error = ProcessingError.audioResample("bad rate")
   * error.operation // "audio-resample"
   * error.message   // "Processing failed during audio-resample: bad rate"
   * }}}
   *
   * @param message what went wrong, appended after the operation name in the error message
   * @param cause the underlying exception, if any
   * @return the processing error
   */
  def audioResample(message: String, cause: Option[Throwable] = None): ProcessingError =
    apply("audio-resample", message, cause)

  /**
   * Creates a processing error for a failed audio conversion: `operation` is `"audio-conversion"`.
   *
   * @param message what went wrong, appended after the operation name in the error message
   * @param cause the underlying exception, if any
   * @return the processing error
   */
  def audioConversion(message: String, cause: Option[Throwable] = None): ProcessingError =
    apply("audio-conversion", message, cause)

  /**
   * Creates a processing error for a failed audio trimming: `operation` is `"audio-trimming"`.
   *
   * @param message what went wrong, appended after the operation name in the error message
   * @param cause the underlying exception, if any
   * @return the processing error
   */
  def audioTrimming(message: String, cause: Option[Throwable] = None): ProcessingError =
    apply("audio-trimming", message, cause)

  /**
   * Creates a processing error for a failed audio validation: `operation` is `"audio-validation"`.
   *
   * @param message what went wrong, appended after the operation name in the error message
   * @param cause the underlying exception, if any
   * @return the processing error
   */
  def audioValidation(message: String, cause: Option[Throwable] = None): ProcessingError =
    apply("audio-validation", message, cause)
}
