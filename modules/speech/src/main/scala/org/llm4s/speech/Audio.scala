package org.llm4s.speech

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import java.io.InputStream
import java.nio.file.Path

/**
 * Audio input representations used by speech components.
 */
sealed trait AudioInput extends Product with Serializable

object AudioInput {
  final case class FileAudio(path: Path)                                                   extends AudioInput
  final case class BytesAudio(bytes: Array[Byte], sampleRate: Int, numChannels: Int = 1)   extends AudioInput
  final case class StreamAudio(stream: InputStream, sampleRate: Int, numChannels: Int = 1) extends AudioInput
}

/**
 * Basic audio metadata.
 *
 * For [[AudioFormat.Mp3]] audio `bitDepth` is `0`, meaning "not applicable": MP3 is compressed, so it has no sample
 * width, and a duration or frame count cannot be computed from `data.length` and these fields. `sampleRate` and
 * `numChannels` are then the service's nominal values for the format it was asked for, not read from the stream.
 */
final case class AudioMeta(sampleRate: Int, numChannels: Int, bitDepth: Int)

/**
 * Output representation for generated audio.
 */
sealed trait AudioFormat extends Product with Serializable
object AudioFormat {
  case object WavPcm16 extends AudioFormat
  case object RawPcm16 extends AudioFormat

  /**
   * MP3 bytes exactly as a cloud TTS service returned them: not decoded, not resampled. Opt-in through
   * [[org.llm4s.speech.tts.TTSOptions]]`.outputFormat`; the PCM helpers (WAV writing, resampling, STT
   * preparation) reject it with a `ValidationError`.
   */
  case object Mp3 extends AudioFormat
}

final case class GeneratedAudio(
  data: Array[Byte],
  meta: AudioMeta,
  format: AudioFormat
) {

  /** True for the PCM formats; false for [[AudioFormat.Mp3]], whose `data` is a compressed stream. */
  def isPcm: Boolean = format != AudioFormat.Mp3

  /** This audio when it is PCM, else a `ValidationError` naming `operation`, so MP3 cannot reach PCM-only code. */
  def requirePcm(operation: String): Result[GeneratedAudio] =
    if (isPcm) Right(this)
    else
      Left(
        ValidationError(
          "audio",
          s"$operation needs PCM audio, but this is ${format} (compressed); it cannot be used as PCM"
        )
      )
}
