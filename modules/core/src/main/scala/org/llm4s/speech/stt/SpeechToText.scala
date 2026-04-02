package org.llm4s.speech.stt

import org.llm4s.error.LLMError
import org.llm4s.types.Result
import org.llm4s.speech.{ AudioInput, AudioMeta }

/**
 * Options for speech-to-text transcription.
 *
 * @param language BCP 47 language tag (e.g., "en-US", "fr-FR")
 * @param prompt Optional context or dictionary to guide transcription
 * @param enableTimestamps Whether to include word-level timestamps
 * @param diarization Whether to detect and separate speakers
 * @param confidenceThreshold Minimum confidence (0.0-1.0) to include words
 */
final case class STTOptions(
  language: Option[String] = None,
  prompt: Option[String] = None,
  enableTimestamps: Boolean = false,
  diarization: Boolean = false,
  confidenceThreshold: Double = 0.0
) {
  require(confidenceThreshold >= 0.0 && confidenceThreshold <= 1.0, "Confidence threshold must be between 0.0 and 1.0")

  require(language.forall(_.matches("[a-z]{2}(-[A-Z]{2})?")), "Language must be valid BCP 47 tag")
}

/**
 * Word-level timestamp information from transcription with optional speaker identification.
 *
 * @param word The word text
 * @param startSec Start time in seconds (relative to audio start)
 * @param endSec End time in seconds
 * @param speakerId Optional speaker identifier for diarized content
 * @param confidence Optional confidence score (0.0-1.0)
 */
final case class WordTimestamp(
  word: String,
  startSec: Double,
  endSec: Double,
  speakerId: Option[Int] = None,
  confidence: Option[Double] = None
) {
  require(startSec >= 0 && endSec >= startSec, "Invalid timestamp: end must be >= start and >= 0")

  def duration: Double = endSec - startSec
}

/**
 * Complete transcription result from speech-to-text processing.
 *
 * @param text Full transcription text
 * @param language Detected or specified language
 * @param confidence Overall confidence of the transcription
 * @param timestamps Word-level timing information (only if enabled)
 * @param meta Source audio metadata
 * @param processingTimeMs Time taken to process (for metrics)
 */
final case class Transcription(
  text: String,
  language: Option[String],
  confidence: Option[Double] = None,
  timestamps: List[WordTimestamp] = Nil,
  meta: Option[AudioMeta] = None,
  processingTimeMs: Option[Long] = None
) {
  def hasTimestamps: Boolean = timestamps.nonEmpty
  def totalDuration: Option[Double] =
    if (timestamps.nonEmpty) Some(timestamps.last.endSec) else None

  def filterByConfidence(threshold: Double): Transcription =
    copy(timestamps = timestamps.filter(_.confidence.forall(_ >= threshold)))

  def uniqueSpeakers: Set[Int] = timestamps.flatMap(_.speakerId).toSet
}

/**
 * Errors that can occur during speech-to-text processing.
 */
sealed trait STTError extends LLMError {
  def retryable: Boolean = false
  def userFriendly: String
}

object STTError {

  /** Engine/provider is not available or not initialized */
  final case class EngineNotAvailable(
    message: String,
    override val context: Map[String, String] = Map.empty
  ) extends STTError {
    override val retryable: Boolean = true
    override def userFriendly       = "Speech recognition service is temporarily unavailable. Please try again."
  }

  /** Audio format is not supported by the engine */
  final case class UnsupportedFormat(
    message: String,
    format: String,
    supported: List[String],
    override val context: Map[String, String] = Map.empty
  ) extends STTError {
    override def userFriendly = s"Audio format '$format' not supported. Supported: ${supported.mkString(", ")}"
  }

  /** Processing failed (network, timeout, etc) */
  final case class ProcessingFailed(
    message: String,
    cause: Option[Throwable] = None,
    override val context: Map[String, String] = Map.empty
  ) extends STTError {
    override val retryable: Boolean = true
    override def userFriendly       = "Speech recognition failed. Please check your audio and try again."
  }

  /** Invalid input or configuration */
  final case class InvalidInput(
    message: String,
    override val context: Map[String, String] = Map.empty
  ) extends STTError {
    override def userFriendly = "Invalid audio or configuration provided."
  }
}

/**
 * Abstraction for speech-to-text conversion providers.
 *
 * Implementations should handle various audio formats and provide
 * optional features like word-level timestamps and speaker diarization.
 */
trait SpeechToText {

  /** Unique identifier/name of this provider */
  def name: String

  /**
   * Transcribe audio to text.
   *
   * @param input Audio data to transcribe
   * @param options Configuration for transcription
   * @return Result containing Transcription or STTError
   * @throws STTError if transcription fails (wrapped in Result)
   */
  def transcribe(input: AudioInput, options: STTOptions = STTOptions()): Result[Transcription]

  /**
   * Check if this provider is available/healthy.
   * Useful for failover logic and availability checks.
   */
  def isAvailable: Result[Boolean] = Right(true)

  /**
   * List supported audio formats (e.g., "audio/wav", "audio/mp3")
   */
  def supportedFormats: List[String]
}
