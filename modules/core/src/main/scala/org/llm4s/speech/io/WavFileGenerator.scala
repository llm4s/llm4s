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

object WavFileGenerator {

  sealed trait WavError extends LLMError
  final case class WavGenerationFailed(
    message: String,
    override val context: Map[String, String] = Map.empty
  ) extends WavError

  final case class WavSaveFailed(
    message: String,
    override val context: Map[String, String] = Map.empty
  ) extends WavError

  /** Bit depths supported by this module's PCM WAV writer and reader. */
  val SupportedBitDepths: Set[Int] = Set(8, 16, 24, 32)

  /** Maximum number of channels permitted (mono through 7.1 surround). */
  val MaxChannels: Int = 8

  /**
   * Validate AudioMeta for correctness.
   * Only enforce minimal constraints (aligned with main + reviewer feedback).
   */
  def validateMetadata(meta: AudioMeta): Result[AudioMeta] =
    if (meta.sampleRate <= 0) {
      Left(WavGenerationFailed(s"Sample rate must be > 0, got: ${meta.sampleRate}"))
    } else if (meta.numChannels <= 0 || meta.numChannels > 8) {
      Left(WavGenerationFailed(s"Number of channels must be between 1 and 8, got: ${meta.numChannels}"))
    } else if (meta.bitDepth <= 0 || meta.bitDepth % 8 != 0) {
      Left(WavGenerationFailed(s"Bit depth must be a positive multiple of 8, got: ${meta.bitDepth}"))
    } else {
      Right(meta)
    }

  def createTempWavFile(prefix: String): Result[Path] =
    Try {
      Files.createTempFile(prefix, ".wav")
    }.toResult.left.map(_ => WavGenerationFailed(s"Failed to create temp WAV file with prefix: $prefix"))

  def managedTempWavFile(prefix: String): ManagedResource[Path] =
    ManagedResource.tempFile(prefix, ".wav")

  def createJavaAudioFormat(meta: AudioMeta): JAudioFormat =
    new JAudioFormat(
      meta.sampleRate.toFloat,
      meta.bitDepth,
      meta.numChannels,
      /* signed = */ true,
      /* bigEndian = */ false
    )

  /**
   * Save GeneratedAudio as WAV
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
   * Save raw PCM data as WAV (no double validation)
   */
  def saveRawPcmAsWav(data: Array[Byte], meta: AudioMeta, path: Path): Result[Path] = {
    val audio = GeneratedAudio(data, meta, AudioFormat.WavPcm16)
    saveAsWav(audio, path)
  }

  /**
   * Create GeneratedAudio from raw bytes
   */
  def createWavFromBytes(data: Array[Byte], meta: AudioMeta): Result[GeneratedAudio] =
    Right(GeneratedAudio(data, meta, AudioFormat.WavPcm16))

  /**
   * Write audio data to temporary WAV file
   */
  def writeToTempWav(
    data: Array[Byte],
    meta: AudioMeta,
    prefix: String = "llm4s-audio"
  ): Result[Path] =
    for {
      tempPath <- createTempWavFile(prefix)
      audio = GeneratedAudio(data, meta, AudioFormat.WavPcm16)
      savedPath <- saveAsWav(audio, tempPath)
    } yield savedPath

  /**
   * Read WAV file and return GeneratedAudio.
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

      val audioData = bytes.drop(44)

      val meta = AudioMeta(
        sampleRate = sampleRate,
        numChannels = numChannels,
        bitDepth = bitsPerSample
      )

      GeneratedAudio(audioData, meta, AudioFormat.WavPcm16)
    }.toResult.left.map(_ => WavGenerationFailed(s"Failed to read WAV file: $path"))

  /**
   * Create WAV header (low-level utility)
   * Returns Result instead of throwing (project convention)
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
