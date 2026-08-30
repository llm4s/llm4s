package org.llm4s.speech.processing

import org.llm4s.speech.AudioMeta
import org.llm4s.types.Result
import org.llm4s.error.ProcessingError

/**
 * Generic audio converter trait for transforming audio between different formats.
 * This provides a more flexible and extensible design for audio processing.
 */
trait AudioConverter[From, To] {
  def convert(input: From): Result[To]
  def name: String

  /** Compose this converter with another converter */
  def andThen[C](next: AudioConverter[To, C]): AudioConverter[From, C] =
    AudioConverter.CompositeConverter(this, next)

  /** Map over the result of this converter */
  def map[C](f: To => C): AudioConverter[From, C] =
    AudioConverter.MappedConverter(this, f)

  /** FlatMap for chaining converters that might fail */
  def flatMap[C](f: To => Result[C]): AudioConverter[From, C] =
    AudioConverter.FlatMappedConverter(this, f)

  /** Filter results based on a predicate */
  def filter(predicate: To => Boolean, errorMsg: String = "Filter condition not met"): AudioConverter[From, To] =
    AudioConverter.FilteredConverter(this, predicate, errorMsg)
}

/**
 * Audio format converter implementations
 */
object AudioConverter {

  /**
   * Converts audio bytes to mono format
   */
  case class MonoConverter() extends AudioConverter[(Array[Byte], AudioMeta), (Array[Byte], AudioMeta)] {
    def convert(input: (Array[Byte], AudioMeta)): Result[(Array[Byte], AudioMeta)] = {
      val (bytes, meta) = input
      AudioPreprocessing.toMono(bytes, meta)
    }

    def name: String = "mono-converter"
  }

  /**
   * Converts audio sample rate
   */
  case class ResampleConverter(targetRate: Int)
      extends AudioConverter[(Array[Byte], AudioMeta), (Array[Byte], AudioMeta)] {
    def convert(input: (Array[Byte], AudioMeta)): Result[(Array[Byte], AudioMeta)] = {
      val (bytes, meta) = input
      AudioPreprocessing.resamplePcm16(bytes, meta, targetRate)
    }

    def name: String = s"resample-converter-${targetRate}Hz"
  }

  /**
   * Trims silence from audio
   */
  case class SilenceTrimmer(threshold: Int = 512)
      extends AudioConverter[(Array[Byte], AudioMeta), (Array[Byte], AudioMeta)] {
    def convert(input: (Array[Byte], AudioMeta)): Result[(Array[Byte], AudioMeta)] = {
      val (bytes, meta) = input
      AudioPreprocessing.trimSilence(bytes, meta, threshold)
    }

    def name: String = s"silence-trimmer-${threshold}"
  }

  /**
   * Composes multiple converters in sequence
   */
  case class CompositeConverter[A, B, C](
    first: AudioConverter[A, B],
    second: AudioConverter[B, C]
  ) extends AudioConverter[A, C] {

    def convert(input: A): Result[C] =
      for {
        intermediate <- first.convert(input)
        result       <- second.convert(intermediate)
      } yield result

    def name: String = s"${first.name} -> ${second.name}"
  }

  /**
   * Identity converter (does nothing)
   */
  case class IdentityConverter[A]() extends AudioConverter[A, A] {
    def convert(input: A): Result[A] = Right(input)
    def name: String                 = "identity"
  }

  /**
   * Mapped converter for applying pure functions
   */
  case class MappedConverter[A, B, C](
    underlying: AudioConverter[A, B],
    f: B => C
  ) extends AudioConverter[A, C] {
    def convert(input: A): Result[C] = underlying.convert(input).map(f)
    def name: String                 = s"${underlying.name} -> mapped"
  }

  /**
   * FlatMapped converter for chaining fallible operations
   */
  case class FlatMappedConverter[A, B, C](
    underlying: AudioConverter[A, B],
    f: B => Result[C]
  ) extends AudioConverter[A, C] {
    def convert(input: A): Result[C] = underlying.convert(input).flatMap(f)
    def name: String                 = s"${underlying.name} -> flatMapped"
  }

  /**
   * Filtered converter for conditional processing
   */
  case class FilteredConverter[A, B](
    underlying: AudioConverter[A, B],
    predicate: B => Boolean,
    errorMsg: String
  ) extends AudioConverter[A, B] {
    def convert(input: A): Result[B] =
      underlying.convert(input).flatMap { result =>
        if (predicate(result)) Right(result)
        else Left(ProcessingError.audioValidation(errorMsg))
      }
    def name: String = s"${underlying.name} -> filtered"
  }

  /**
   * Standard STT preprocessing pipeline using functional composition
   */
  def sttPreprocessor(targetRate: Int = 16000): AudioConverter[(Array[Byte], AudioMeta), (Array[Byte], AudioMeta)] =
    MonoConverter()
      .andThen(ResampleConverter(targetRate))
      .andThen(SilenceTrimmer())

  /**
   * Alternative STT preprocessor that can be customized
   */
  def customSttPreprocessor(
    monoConversion: Boolean = true,
    targetRate: Option[Int] = Some(16000),
    silenceThreshold: Option[Int] = Some(512)
  ): AudioConverter[(Array[Byte], AudioMeta), (Array[Byte], AudioMeta)] = {
    type AudioData = (Array[Byte], AudioMeta)

    val mono: AudioConverter[AudioData, AudioData] =
      if (monoConversion) MonoConverter() else IdentityConverter[AudioData]()

    val resample: AudioConverter[AudioData, AudioData] =
      targetRate.fold(IdentityConverter[AudioData](): AudioConverter[AudioData, AudioData])(rate =>
        ResampleConverter(rate)
      )

    val silence: AudioConverter[AudioData, AudioData] =
      silenceThreshold.fold(IdentityConverter[AudioData](): AudioConverter[AudioData, AudioData])(threshold =>
        SilenceTrimmer(threshold)
      )

    mono.andThen(resample).andThen(silence)
  }
}
