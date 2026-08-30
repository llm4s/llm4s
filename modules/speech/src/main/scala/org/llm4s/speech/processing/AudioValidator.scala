package org.llm4s.speech.processing

import cats.Semigroup
import cats.data.ValidatedNel
import cats.implicits.{ catsSyntaxEither, catsSyntaxTuple2Semigroupal, catsSyntaxTuple4Semigroupal }
import org.llm4s.error.ProcessingError
import org.llm4s.speech.AudioMeta
import org.llm4s.speech.processing.AudioValidations._
import org.llm4s.types.Result

/**
 * Generic audio validator trait for validating audio data and metadata.
 * This provides a more flexible and extensible design for audio validation.
 * Made covariant in A as suggested in the review.
 */
trait AudioValidator[+A] {
  def validate[B >: A](input: B): Result[B]
  def name: String
}

/**
 * Audio validation implementations
 */
object AudioValidator {
  implicit val errorSemigroup: Semigroup[ProcessingError] = (x, y) =>
    ProcessingError.audioValidation(s"${x.message}, ${y.message}")

  /**
   * Validates audio metadata for STT processing
   */
  case class STTMetadataValidator() extends AudioValidator[AudioMeta] {
    def validate[B >: AudioMeta](input: B): Result[B] = input match {
      case meta: AudioMeta => validateMeta(meta).asInstanceOf[Result[B]]
      case _               => Left(ProcessingError.audioValidation("Input must be AudioMeta"))
    }

    private def validateMeta(meta: AudioMeta): Result[AudioMeta] =
      (
        validateSampleRate(meta),
        validateNumChannels(meta),
        validateBitDepth(meta),
        validateSampleRateIsNotTooHigh(meta)
      ).mapN((_, _, _, _) => meta)
        .toEither
        .left
        .map(nel =>
          ProcessingError.audioValidation(s"""Validation failed: ${nel.map(_.message).toList.mkString(",")}""")
        )

    def name: String = "stt-metadata-validator"
  }

  /**
   * Validates audio data length matches metadata
   */
  case class AudioDataValidator() extends AudioValidator[(Array[Byte], AudioMeta)] {
    def validate[B >: (Array[Byte], AudioMeta)](input: B): Result[B] = input match {
      case data: (Array[Byte], AudioMeta) @unchecked => validateData(data).asInstanceOf[Result[B]]
      case _ => Left(ProcessingError.audioValidation("Input must be (Array[Byte], AudioMeta)"))
    }

    private def validateData(input: (Array[Byte], AudioMeta)): Result[(Array[Byte], AudioMeta)] =
      validateFrameSize(input).toEither.leftMap(_.reduce)

    def name: String = "audio-data-validator"
  }

  /**
   * Validates audio is not empty
   */
  case class NonEmptyAudioValidator() extends AudioValidator[(Array[Byte], AudioMeta)] {
    def validate[B >: (Array[Byte], AudioMeta)](input: B): Result[B] = input match {
      case data: (Array[Byte], AudioMeta) @unchecked => validateNonEmpty(data).asInstanceOf[Result[B]]
      case _ => Left(ProcessingError.audioValidation("Input must be (Array[Byte], AudioMeta)"))
    }

    private def validateNonEmpty(input: (Array[Byte], AudioMeta)): Result[(Array[Byte], AudioMeta)] =
      validateInputNotEmpty(input).toEither.leftMap(_.reduce)

    def name: String = "non-empty-audio-validator"
  }

  /**
   * Composes multiple validators
   */
  case class CompositeValidator[A](
    validators: List[AudioValidator[A]]
  ) extends AudioValidator[A] {

    def validate[B >: A](input: B): Result[B] =
      validators.foldLeft(Right(input): Result[B])((acc, validator) => acc.flatMap(validator.validate))

    def name: String = validators.map(_.name).mkString(" + ")
  }

  /**
   * Standard STT validation pipeline
   */
  def sttValidator: AudioValidator[(Array[Byte], AudioMeta)] =
    CompositeValidator(
      List(
        NonEmptyAudioValidator(),
        AudioDataValidator()
      )
    )

  /**
   * Validated version of STTMetadataValidator using Cats Validated
   */
  case class ValidatedSTTMetadataValidator() {
    def validate(meta: AudioMeta): ValidatedNel[ProcessingError, AudioMeta] =
      (
        validateSampleRate(meta),
        validateNumChannels(meta),
        validateBitDepth(meta),
        validateSampleRateIsNotTooHigh(meta)
      ).mapN((_, _, _, _) => meta)

    def name: String = "validated-stt-metadata-validator"
  }

  /**
   * Convert the validated STT validator result to Result for existing API compatibility
   */
  def validatedSttValidatorAsResult(input: (Array[Byte], AudioMeta)): Result[(Array[Byte], AudioMeta)] =
    (
      validateInputNotEmpty(input),
      validateFrameSize(input)
    ).mapN((_, validated) => validated).toEither.left.map(_.reduce)
}
