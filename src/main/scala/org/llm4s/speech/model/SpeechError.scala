package org.llm4s.speech.model

import scala.util.control.NoStackTrace

/**
 * Represents errors that can occur during speech processing operations.
 */
sealed trait SpeechError extends Exception with NoStackTrace {
  def message: String
  override def getMessage: String = message
}

/**
 * Authentication error - invalid API key or credentials
 */
case class SpeechAuthenticationError(message: String) extends SpeechError

/**
 * Rate limit error - too many requests
 */
case class SpeechRateLimitError(message: String) extends SpeechError

/**
 * Validation error - invalid input parameters
 */
case class SpeechValidationError(message: String) extends SpeechError

/**
 * Unknown or unexpected error
 */
case class SpeechUnknownError(cause: Throwable) extends SpeechError {
  def message: String = s"Unknown speech error: ${cause.getMessage}"
}
