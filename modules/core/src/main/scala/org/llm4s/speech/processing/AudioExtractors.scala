package org.llm4s.speech.processing

import org.llm4s.speech.{ AudioMeta, AudioInput }
import org.llm4s.types.Result
import org.llm4s.error.ProcessingError
import java.nio.file.{ Files, Path }
import java.io.InputStream

/**
 * Advanced audio processing patterns with variance annotations.
 * Implements the extractor pattern suggested in PR review comments.
 */

/**
 * Covariant AudioProcessor - can process audio and produce results of type A or its supertypes.
 * Covariance allows substitution: AudioProcessor[String] can be used where AudioProcessor[Any] is expected.
 */
trait AudioProcessor[+A] {
  def process(input: AudioInput): Result[A]
  def name: String
}

/**
 * Contravariant AudioConsumer - can consume audio data of type A or its subtypes.
 * Contravariance allows substitution: AudioConsumer[Any] can be used where AudioConsumer[String] is expected.
 */
trait AudioConsumer[-A] {
  def consume(audio: A): Result[Unit]
  def name: String
}

/**
 * Audio input extractors for different sources
 */
object AudioInputExtractors {

  /**
   * Extract raw bytes and metadata from AudioInput
   */
  object BytesExtractor extends AudioProcessor[(Array[Byte], AudioMeta)] {
    def process(input: AudioInput): Result[(Array[Byte], AudioMeta)] = input match {
      case AudioInput.FileAudio(path) =>
        scala.util
          .Try {
            val bytes = Files.readAllBytes(path)
            val meta  = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)
            (bytes, meta)
          }
          .toEither
          .left
          .map {
            case ex: java.io.IOException =>
              ProcessingError.audioValidation(s"Failed to read audio file: $path", Some(ex))
            case ex: Exception => ProcessingError.audioValidation(s"Error processing audio file: $path", Some(ex))
          }

      case AudioInput.BytesAudio(bytes, sampleRate, numChannels) =>
        val meta = AudioMeta(sampleRate = sampleRate, numChannels = numChannels, bitDepth = 16)
        Right((bytes, meta))

      case AudioInput.StreamAudio(stream, sampleRate, numChannels) =>
        scala.util
          .Try {
            val bytes = stream.readAllBytes()
            val meta  = AudioMeta(sampleRate = sampleRate, numChannels = numChannels, bitDepth = 16)
            (bytes, meta)
          }
          .toEither
          .left
          .map {
            case ex: java.io.IOException => ProcessingError.audioValidation("Failed to read audio stream", Some(ex))
            case ex: Exception           => ProcessingError.audioValidation("Error processing audio stream", Some(ex))
          }
    }

    def name: String = "bytes-extractor"
  }

  /**
   * Extract metadata only from AudioInput
   */
  object MetadataExtractor extends AudioProcessor[AudioMeta] {
    def process(input: AudioInput): Result[AudioMeta] = input match {
      case AudioInput.FileAudio(_) =>
        // In a real implementation, this would parse file headers
        Right(AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16))

      case AudioInput.BytesAudio(_, sampleRate, numChannels) =>
        Right(AudioMeta(sampleRate = sampleRate, numChannels = numChannels, bitDepth = 16))
      case AudioInput.StreamAudio(_, sampleRate, numChannels) =>
        Right(AudioMeta(sampleRate = sampleRate, numChannels = numChannels, bitDepth = 16))
    }

    def name: String = "metadata-extractor"
  }

  /**
   * Extract file path from AudioInput (if available)
   */
  object PathExtractor extends AudioProcessor[Option[Path]] {
    def process(input: AudioInput): Result[Option[Path]] = input match {
      case AudioInput.FileAudio(path) => Right(Some(path))
      case _                          => Right(None)
    }

    def name: String = "path-extractor"
  }

  /**
   * Extract stream from AudioInput (if available)
   */
  object StreamExtractor extends AudioProcessor[Option[InputStream]] {
    def process(input: AudioInput): Result[Option[InputStream]] = input match {
      case AudioInput.StreamAudio(stream, _, _) => Right(Some(stream))
      case _                                    => Right(None)
    }

    def name: String = "stream-extractor"
  }
}

/**
 * Audio consumers for different output targets
 */
object AudioConsumers {

  /**
   * Consumer that writes audio to a file
   */
  case class FileConsumer(path: Path) extends AudioConsumer[(Array[Byte], AudioMeta)] {
    def consume(audio: (Array[Byte], AudioMeta)): Result[Unit] = {
      val (bytes, meta) = audio
      import org.llm4s.speech.io.WavFileGenerator
      import org.llm4s.speech.{ GeneratedAudio, AudioFormat }

      val generatedAudio = GeneratedAudio(bytes, meta, AudioFormat.WavPcm16)
      WavFileGenerator.saveAsWav(generatedAudio, path).map(_ => ())
    }

    def name: String = s"file-consumer($path)"
  }

  /**
   * Consumer that validates audio data
   */
  case class ValidationConsumer(validator: AudioValidator[(Array[Byte], AudioMeta)])
      extends AudioConsumer[(Array[Byte], AudioMeta)] {

    def consume(audio: (Array[Byte], AudioMeta)): Result[Unit] =
      validator.validate(audio).map(_ => ())

    def name: String = s"validation-consumer(${validator.getClass.getSimpleName})"
  }

  /**
   * Consumer that logs audio information
   */
  object LoggingConsumer extends AudioConsumer[Any] {
    def consume(audio: Any): Result[Unit] = {
      audio match {
        case (bytes: Array[Byte], meta: AudioMeta) =>
          println(s"Audio: ${bytes.length} bytes, ${meta.sampleRate}Hz, ${meta.numChannels}ch, ${meta.bitDepth}bit")
        case meta: AudioMeta =>
          println(s"AudioMeta: ${meta.sampleRate}Hz, ${meta.numChannels}ch, ${meta.bitDepth}bit")
        case other =>
          println(s"Audio data: ${other.getClass.getSimpleName}")
      }
      Right(())
    }

    def name: String = "logging-consumer"
  }

  /**
   * Consumer that does nothing (null object pattern)
   */
  object NoOpConsumer extends AudioConsumer[Any] {
    def consume(audio: Any): Result[Unit] = Right(())
    def name: String                      = "noop-consumer"
  }
}

/**
 * Composition utilities for processors and consumers
 */
object AudioProcessing {

  /**
   * Pipe processor output to consumer
   */
  def pipe[A](processor: AudioProcessor[A], consumer: AudioConsumer[A]): AudioInput => Result[Unit] =
    (input: AudioInput) =>
      for {
        result <- processor.process(input)
        _      <- consumer.consume(result)
      } yield ()

  /**
   * Chain multiple processors (covariant composition)
   */
  def chain[A, B](
    first: AudioProcessor[A],
    second: A => AudioProcessor[B]
  ): AudioProcessor[B] = new AudioProcessor[B] {
    def process(input: AudioInput): Result[B] =
      for {
        intermediate <- first.process(input)
        result       <- second(intermediate).process(input)
      } yield result

    def name: String = s"${first.name} -> chained"
  }

  /**
   * Broadcast to multiple consumers (contravariant fan-out)
   */
  def broadcast[A](consumers: AudioConsumer[A]*): AudioConsumer[A] = new AudioConsumer[A] {
    def consume(audio: A): Result[Unit] = {
      val results  = consumers.map(_.consume(audio))
      val failures = results.collect { case Left(error) => error }

      if (failures.nonEmpty) {
        Left(ProcessingError.audioValidation(s"Broadcast failed: ${failures.map(_.message).mkString(", ")}"))
      } else {
        Right(())
      }
    }

    def name: String = s"broadcast(${consumers.map(_.name).mkString(", ")})"
  }

  /**
   * Conditional processor based on predicate
   */
  def conditional[A](
    predicate: AudioInput => Boolean,
    ifTrue: AudioProcessor[A],
    ifFalse: AudioProcessor[A]
  ): AudioProcessor[A] = new AudioProcessor[A] {
    def process(input: AudioInput): Result[A] =
      if (predicate(input)) ifTrue.process(input) else ifFalse.process(input)

    def name: String = s"conditional(${ifTrue.name}, ${ifFalse.name})"
  }

  /**
   * Error recovery processor
   */
  def withFallback[A](
    primary: AudioProcessor[A],
    fallback: AudioProcessor[A]
  ): AudioProcessor[A] = new AudioProcessor[A] {
    def process(input: AudioInput): Result[A] =
      primary.process(input).orElse(fallback.process(input))

    def name: String = s"${primary.name} with fallback ${fallback.name}"
  }
}
