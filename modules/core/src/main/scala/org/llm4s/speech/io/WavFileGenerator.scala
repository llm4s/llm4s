package org.llm4s.speech.io

import org.llm4s.error.LLMError
import org.llm4s.types.Result
import org.llm4s.speech.{ GeneratedAudio, AudioMeta, AudioFormat }
import org.llm4s.resource.ManagedResource

import java.io.{ ByteArrayOutputStream, DataOutputStream }
import java.nio.file.{ Path, Files }
import javax.sound.sampled.{ AudioFileFormat, AudioFormat => JAudioFormat, AudioSystem }
import scala.util.Try
import org.llm4s.types.TryOps

/**
 * Eliminates code duplication in WAV file generation across the speech module.
 * Provides centralized WAV file creation, format conversion, metadata validation,
 * and temporary file management.
 *
 * All generation entry points validate [[AudioMeta]] up front via [[validateMetadata]]
 * so that invalid sample rates, channel counts, or bit depths fail fast with a
 * descriptive [[WavError]] rather than producing a corrupt WAV file.
 */
object WavFileGenerator {

  sealed trait WavError extends LLMError
  final case class WavGenerationFailed(message: String, override val context: Map[String, String] = Map.empty)
      extends WavError
  final case class WavSaveFailed(message: String, override val context: Map[String, String] = Map.empty)
      extends WavError

  /** Bit depths supported by this module's PCM WAV writer and reader. */
  val SupportedBitDepths: Set[Int] = Set(8, 16, 24, 32)

  /** Maximum number of channels permitted (mono through 7.1 surround). */
  val MaxChannels: Int = 8

  /**
   * Validate [[AudioMeta]] before it is used to generate or read a WAV file.
   *
   * Enforces that the sample rate is positive, the channel count is within
   * `[1, `[[MaxChannels]]`]`, and the bit depth is one of [[SupportedBitDepths]].
   *
   * @return the unchanged `meta` on success, or a [[WavGenerationFailed]] describing the first violation
   */
  def validateMetadata(meta: AudioMeta): Result[AudioMeta] =
    if (meta.sampleRate <= 0) {
      Left(WavGenerationFailed(s"Sample rate must be > 0, got: ${meta.sampleRate}"))
    } else if (meta.numChannels < 1 || meta.numChannels > MaxChannels) {
      Left(WavGenerationFailed(s"Number of channels must be between 1 and $MaxChannels, got: ${meta.numChannels}"))
    } else if (!SupportedBitDepths.contains(meta.bitDepth)) {
      Left(
        WavGenerationFailed(
          s"Bit depth must be one of ${SupportedBitDepths.toList.sorted.mkString(", ")}, got: ${meta.bitDepth}"
        )
      )
    } else {
      Right(meta)
    }

  /**
   * Create a temporary WAV file with the given prefix.
   */
  def createTempWavFile(prefix: String): Result[Path] =
    Try {
      Files.createTempFile(prefix, ".wav")
    }.toResult.left.map(_ => WavGenerationFailed(s"Failed to create temp WAV file with prefix: $prefix"))

  /**
   * Create a managed temporary WAV file that gets deleted automatically.
   */
  def managedTempWavFile(prefix: String): ManagedResource[Path] =
    ManagedResource.tempFile(prefix, ".wav")

  /**
   * Create a Java AudioFormat from [[AudioMeta]].
   */
  def createJavaAudioFormat(meta: AudioMeta): JAudioFormat =
    new JAudioFormat(
      meta.sampleRate.toFloat,
      meta.bitDepth,
      meta.numChannels,
      /* signed = */ true,
      /* bigEndian = */ false
    )

  /**
   * Save [[GeneratedAudio]] as a WAV file using ManagedResource (eliminates duplication from AudioIO.saveWav).
   *
   * The audio metadata is validated before writing.
   */
  def saveAsWav(audio: GeneratedAudio, path: Path): Result[Path] =
    for {
      _ <- validateMetadata(audio.meta)
      result <- ManagedResource
        .audioInputStream(audio.data, createJavaAudioFormat(audio.meta))
        .use { ais =>
          Try {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, path.toFile)
            path
          }.toResult.left.map(_ => WavSaveFailed(s"Failed to save WAV to: $path"))
        }
    } yield result

  /**
   * Save raw PCM data as a WAV file (eliminates duplication from AudioIO.saveRawPcm16).
   *
   * Metadata validation happens once inside [[saveAsWav]] to avoid double validation.
   */
  def saveRawPcmAsWav(data: Array[Byte], meta: AudioMeta, path: Path): Result[Path] = {
    val audio = GeneratedAudio(data, meta, AudioFormat.WavPcm16)
    saveAsWav(audio, path)
  }

  /**
   * Create [[GeneratedAudio]] from raw bytes with metadata, validating the metadata first.
   */
  def createWavFromBytes(data: Array[Byte], meta: AudioMeta): Result[GeneratedAudio] =
    validateMetadata(meta).map(GeneratedAudio(data, _, AudioFormat.WavPcm16))

  /**
   * Write audio data to a temporary WAV file and return the path
   * (eliminates duplication in TTS implementations).
   */
  def writeToTempWav(data: Array[Byte], meta: AudioMeta, prefix: String = "llm4s-audio"): Result[Path] =
    for {
      tempPath <- createTempWavFile(prefix)
      audio = GeneratedAudio(data, meta, AudioFormat.WavPcm16)
      savedPath <- saveAsWav(audio, tempPath)
    } yield savedPath

  /**
   * Read a WAV file and return [[GeneratedAudio]], parsing actual RIFF/WAV header fields.
   *
   * The parsed metadata is validated via [[validateMetadata]] so that a malformed or
   * non-PCM file is rejected with a descriptive error rather than yielding garbage metadata.
   *
   * WAV header layout (little-endian):
   *   Offset 22: NumChannels (Short)
   *   Offset 24: SampleRate (Int)
   *   Offset 34: BitsPerSample (Short)
   *   Offset 44+: audio data
   */
  def readWavFile(path: Path): Result[GeneratedAudio] =
    Try {
      val bytes = Files.readAllBytes(path)
      import BinaryReader._
      val (numChannels, _)   = bytes.read[Short](22)
      val (sampleRate, _)    = bytes.read[Int](24)
      val (bitsPerSample, _) = bytes.read[Short](34)
      val meta               = AudioMeta(sampleRate = sampleRate, numChannels = numChannels, bitDepth = bitsPerSample)
      (bytes.drop(44), meta)
    }.toResult.left
      .map(_ => WavGenerationFailed(s"Failed to read WAV file: $path"))
      .flatMap { case (audioData, meta) =>
        validateMetadata(meta).map(GeneratedAudio(audioData, _, AudioFormat.WavPcm16))
      }

  /**
   * Utility for creating WAV headers manually (advanced usage).
   *
   * Validates the metadata, then uses BinaryWriter typeclass instances for correct
   * little-endian encoding. Returns a [[Result]] rather than throwing, per project convention.
   */
  def createWavHeader(dataSize: Int, meta: AudioMeta): Result[Array[Byte]] =
    for {
      _ <- validateMetadata(meta)
    } yield {
      val iw         = BinaryWriter.intWriter
      val sw         = BinaryWriter.shortWriter
      val byteRate   = meta.sampleRate * meta.numChannels * (meta.bitDepth / 8)
      val blockAlign = (meta.numChannels * meta.bitDepth / 8).toShort

      val header = new ByteArrayOutputStream(44)
      val dos    = new DataOutputStream(header)

      dos.write("RIFF".getBytes)
      iw.write(dos, dataSize + 36)
      dos.write("WAVE".getBytes)
      dos.write("fmt ".getBytes)
      iw.write(dos, 16)
      sw.write(dos, 1.toShort)
      sw.write(dos, meta.numChannels.toShort)
      iw.write(dos, meta.sampleRate)
      iw.write(dos, byteRate)
      sw.write(dos, blockAlign)
      sw.write(dos, meta.bitDepth.toShort)
      dos.write("data".getBytes)
      iw.write(dos, dataSize)

      header.toByteArray
    }
}
