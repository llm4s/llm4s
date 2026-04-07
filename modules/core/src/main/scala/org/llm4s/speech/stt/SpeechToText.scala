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
}

object STTOptions {

  /**
   * Validate BCP 47 language tag format.
   * Accepts standard tags like "en", "en-US", "zh", etc.
   * Rejects full English words like "english" or invalid formats.
   *
   * BCP 47 format:
   * - Language (2 letters, lowercase): en, fr, de
   * - Region (2 letters, uppercase): US, GB, FR
   * - Script (4 letters, title case): Hans (for zh-Hans)
   *
   * @param tag Language tag to validate
   * @return Right(tag) if valid, Left(error) if invalid
   */
  def validateBcp47(tag: String): Result[String] =
    if (tag.trim.isEmpty) {
      Left(STTError.InvalidInput("Language tag cannot be empty"))
    } else if (tag.length > 35) {
      // BCP 47 tags should not exceed ~35 characters
      Left(STTError.InvalidInput(s"Language tag '$tag' is too long"))
    } else {
      // Pattern for BCP 47 tags: language[-script][-region]
      // Language: 2-3 lowercase letters
      // Script: 4 letters (title case: first uppercase, rest lowercase)
      // Region: 2 uppercase letters
      val bcp47Pattern =
        """^[a-z]{2}(?:-[A-Z][a-z]{3})?(?:-[A-Z]{2})?$""".r

      if (bcp47Pattern.matches(tag)) {
        Right(tag)
      } else {
        Left(STTError.InvalidInput(s"Language tag '$tag' is not a valid BCP 47 tag"))
      }
    }

  /**
   * Create STTOptions with typed validation (Result-based).
   * Validates language tag format (BCP 47) and confidence threshold [0.0, 1.0].
   *
   * @param language Optional BCP 47 language tag
   * @param prompt Optional context for transcription
   * @param enableTimestamps Whether to request word-level timestamps
   * @param diarization Whether to detect/separate speakers
   * @param confidenceThreshold Minimum confidence [0.0, 1.0]
   * @return Right(STTOptions) if all validations pass, Left(STTError.InvalidInput) otherwise
   */
  def validate(
    language: Option[String] = None,
    prompt: Option[String] = None,
    enableTimestamps: Boolean = false,
    diarization: Boolean = false,
    confidenceThreshold: Double = 0.0
  ): Result[STTOptions] = {
    val errors = scala.collection.mutable.ListBuffer[String]()

    // Validate confidence threshold
    if (confidenceThreshold < 0.0 || confidenceThreshold > 1.0) {
      errors += s"Confidence threshold must be between 0.0 and 1.0, got $confidenceThreshold"
    }

    // Validate language format (BCP 47) using proper locale validation
    language.foreach(lang => validateBcp47(lang).left.foreach(error => errors += error.message))

    // Validate prompt length (if provided)
    if (prompt.isDefined && prompt.get.length > 4000) {
      errors += s"Prompt must be <= 4000 characters, got ${prompt.get.length}"
    }

    if (errors.isEmpty) {
      // Safe to construct since we already validated
      try
        Right(STTOptions(language, prompt, enableTimestamps, diarization, confidenceThreshold))
      catch {
        case e: IllegalArgumentException =>
          Left(STTError.InvalidInput(e.getMessage, context = Map("validation_step" -> "final")))
        case e: Throwable =>
          Left(
            STTError.InvalidInput(
              "Unexpected error during validation",
              context = Map("error" -> e.toString)
            )
          )
      }
    } else {
      Left(
        STTError.InvalidInput(
          errors.mkString("; "),
          context = Map(
            "language"            -> language.getOrElse("None"),
            "confidenceThreshold" -> confidenceThreshold.toString,
            "enableTimestamps"    -> enableTimestamps.toString
          )
        )
      )
    }
  }

  /**
   * Batch validate multiple STTOptions configurations.
   * Useful for testing or bulk configuration loading.
   */
  def validateBatch(options: Seq[STTOptions]): Result[Seq[STTOptions]] =
    // Since direct construction uses require(), catch any failures
    try
      Right(options)
    catch {
      case e: IllegalArgumentException =>
        Left(STTError.InvalidInput(s"Configuration validation failed: ${e.getMessage}"))
    }
}

/**
 * Word-level timestamp information from transcription with optional speaker identification.
 *
 * @param word The word text
 * @param startSec Start time in seconds (relative to audio start)
 * @param endSec End time in seconds
 * @param speakerId Optional speaker identifier for diarized content (int-based for efficiency)
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
  require(word.nonEmpty, "Word must not be empty")
  require(
    confidence.forall(c => c >= 0.0 && c <= 1.0),
    s"Confidence must be in [0.0, 1.0], got ${confidence.getOrElse("N/A")}"
  )

  def duration: Double = endSec - startSec

  /**
   * Check if this word meets or exceeds minimum confidence threshold
   * (treats missing confidence as passing - provider doesn't provide it)
   */
  def meetsConfidence(minConfidence: Double): Boolean =
    confidence.forall(_ >= minConfidence)

  /**
   * Create a new WordTimestamp with adjusted times (e.g., trimming silence)
   */
  def withTimeAdjustment(startOffset: Double = 0.0, endOffset: Double = 0.0): WordTimestamp =
    copy(startSec = Math.max(0, startSec + startOffset), endSec = endSec + endOffset)
}

object WordTimestamp {

  /**
   * Create WordTimestamp with typed validation (Result-based).
   *
   * @return Right(WordTimestamp) if valid, Left(STTError.InvalidInput) otherwise
   */
  def validate(
    word: String,
    startSec: Double,
    endSec: Double,
    speakerId: Option[Int] = None,
    confidence: Option[Double] = None
  ): Result[WordTimestamp] = {
    val errors = scala.collection.mutable.ListBuffer[String]()

    if (word.isEmpty) {
      errors += "Word must not be empty"
    }

    if (startSec < 0) {
      errors += s"Start time must be >= 0, got $startSec"
    }

    if (endSec < startSec) {
      errors += s"End time ($endSec) must be >= start time ($startSec)"
    }

    if (confidence.isDefined && (confidence.get < 0.0 || confidence.get > 1.0)) {
      errors += s"Confidence must be in [0.0, 1.0], got ${confidence.get}"
    }

    if (errors.isEmpty) {
      Right(WordTimestamp(word, startSec, endSec, speakerId, confidence))
    } else {
      Left(
        STTError.InvalidInput(
          errors.mkString("; "),
          context = Map(
            "word"       -> word,
            "startSec"   -> startSec.toString,
            "endSec"     -> endSec.toString,
            "confidence" -> confidence.map(_.toString).getOrElse("None")
          )
        )
      )
    }
  }
}

/**
 * Complete transcription result from speech-to-text processing.
 *
 * @param text Full transcription text
 * @param language Detected or specified language
 * @param confidence Overall confidence of the transcription
 * @param timestamps Word-level timing information (only if enabled)
 * @param meta Source audio metadata
 * @param processingTimeMs Time taken to process (for metrics/monitoring)
 */
final case class Transcription(
  text: String,
  language: Option[String],
  confidence: Option[Double] = None,
  timestamps: List[WordTimestamp] = Nil,
  meta: Option[AudioMeta] = None,
  processingTimeMs: Option[Long] = None
) {
  require(text.nonEmpty, "Transcription text must not be empty")

  def hasTimestamps: Boolean = timestamps.nonEmpty

  def totalDuration: Option[Double] =
    if (timestamps.nonEmpty) Some(timestamps.map(_.endSec).max) else None

  /**
   * Filter timestamps by minimum confidence threshold.
   * Useful for quality control and downstream processing.
   * Only keeps timestamps that have confidence scores >= threshold.
   * Timestamps without confidence scores are excluded.
   *
   * @param threshold Minimum confidence score [0.0, 1.0]
   * @return New Transcription with filtered timestamps
   */
  def filterByConfidence(threshold: Double): Transcription =
    copy(timestamps = timestamps.filter(_.confidence.exists(_ >= threshold)))

  /**
   * Get all unique speaker IDs in this transcription (if diarization was enabled)
   */
  def uniqueSpeakers: Set[Int] = timestamps.flatMap(_.speakerId).toSet

  /**
   * Get word count (based on timestamps if available, otherwise estimate from text)
   */
  def wordCount: Int =
    if (timestamps.nonEmpty) timestamps.length else text.split("\\s+").length

  /**
   * Get average confidence of all timestamped words
   * Only considers words that have confidence scores
   */
  def averageConfidence: Option[Double] = {
    val confidences = timestamps.flatMap(_.confidence)
    if (confidences.nonEmpty) Some(confidences.sum / confidences.length) else None
  }

  /**
   * Get minimum confidence score among timestamped words
   */
  def minConfidence: Option[Double] =
    if (timestamps.nonEmpty) timestamps.flatMap(_.confidence).minOption else None

  /**
   * Get maximum confidence score among timestamped words
   */
  def maxConfidence: Option[Double] =
    if (timestamps.nonEmpty) timestamps.flatMap(_.confidence).maxOption else None

  /**
   * Get all words spoken by a specific speaker (requires diarization)
   *
   * @param speakerId Speaker ID to filter by
   * @return List of words from that speaker in chronological order
   */
  def wordsBySpeaker(speakerId: Int): List[String] =
    timestamps.filter(_.speakerId.contains(speakerId)).map(_.word)

  /**
   * Get time segments for each speaker (requires diarization and timestamps)
   * Useful for speaker-specific processing or transcription verification
   *
   * @return Map of speaker ID -> List of (startSec, endSec) tuples
   */
  def speakerSegments: Map[Int, List[(Double, Double)]] =
    timestamps
      .groupBy(_.speakerId)
      .collect { case (Some(id), words) =>
        id -> words.map(w => (w.startSec, w.endSec))
      }

  /**
   * Check if transcription meets quality thresholds
   *
   * @param minConfidence Minimum overall/average confidence required
   * @param minWords Minimum number of words/timestamps required
   * @return true if quality thresholds are met
   */
  def meetsQualityThreshold(minConfidence: Double = 0.5, minWords: Int = 1): Boolean = {
    // If overall confidence is provided, it must meet threshold
    // If not, check average confidence from timestamps
    // If neither is provided, allow (pass confidence check)
    val confCheck = confidence match {
      case Some(conf) => conf >= minConfidence
      case None =>
        averageConfidence match {
          case Some(avgConf) => avgConf >= minConfidence
          case None          => true // No confidence data, allow pass
        }
    }
    val countCheck = wordCount >= minWords
    confCheck && countCheck
  }
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
