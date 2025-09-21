package org.llm4s.speech

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

/** Basic audio metadata */
final case class AudioMeta(sampleRate: Int, numChannels: Int, bitDepth: Int)

/**
 * Output representation for generated audio.
 */
sealed trait AudioFormat extends Product with Serializable
object AudioFormat {
  case object WavPcm16 extends AudioFormat
  case object RawPcm16 extends AudioFormat
}

final case class GeneratedAudio(
  data: Array[Byte],
  meta: AudioMeta,
  format: AudioFormat
)
