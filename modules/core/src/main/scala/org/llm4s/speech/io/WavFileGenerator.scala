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
 * Provides centralized WAV file creation, format conversion, and temporary file management.
 */
object WavFileGenerator {

  sealed trait WavError extends LLMError
  final case class WavGenerationFailed(message: String, override val context: Map[String, String] = Map.empty)
      extends WavError
  final case class WavSaveFailed(message: String, override val context: Map[String, String] = Map.empty)
      extends WavError

  /**
   * Create a temporary WAV file with the given prefix
   */
  def createTempWavFile(prefix: String): Result[Path] =
    Try {
      Files.createTempFile(prefix, ".wav")
    }.toResult.left.map(_ => WavGenerationFailed(s"Failed to create temp WAV file with prefix: $prefix"))

  /**
   * Create a managed temporary WAV file that gets deleted automatically
   */
  def managedTempWavFile(prefix: String): ManagedResource[Path] =
    ManagedResource.tempFile(prefix, ".wav")

  /**
   * Create a Java AudioFormat from AudioMeta
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
   * Save GeneratedAudio as WAV file using ManagedResource (eliminates duplication from AudioIO.saveWav)
   */
  def saveAsWav(audio: GeneratedAudio, path: Path): Result[Path] =
    ManagedResource.audioInputStream(audio.data, createJavaAudioFormat(audio.meta)).use { ais =>
      Try {
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, path.toFile)
        path
      }.toResult.left.map(_ => WavSaveFailed(s"Failed to save WAV to: $path"))
    }

  /**
   * Save raw PCM data as WAV file (eliminates duplication from AudioIO.saveRawPcm16)
   */
  def saveRawPcmAsWav(data: Array[Byte], meta: AudioMeta, path: Path): Result[Path] = {
    val audio = GeneratedAudio(data, meta, AudioFormat.WavPcm16)
    saveAsWav(audio, path)
  }

  /**
   * Create WAV file from raw bytes with metadata
   */
  def createWavFromBytes(data: Array[Byte], meta: AudioMeta): Result[GeneratedAudio] =
    Try {
      GeneratedAudio(data, meta, AudioFormat.WavPcm16)
    }.toResult.left.map(_ => WavGenerationFailed("Failed to create WAV from bytes"))

  /**
   * Write audio data to temporary WAV file and return the path
   * (eliminates duplication in TTS implementations)
   */
  def writeToTempWav(data: Array[Byte], meta: AudioMeta, prefix: String = "llm4s-audio"): Result[Path] =
    for {
      tempPath <- createTempWavFile(prefix)
      audio = GeneratedAudio(data, meta, AudioFormat.WavPcm16)
      savedPath <- saveAsWav(audio, tempPath)
    } yield savedPath

  /**
   * Read WAV file and return GeneratedAudio, parsing actual RIFF/WAV header fields.
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
      val audioData          = bytes.drop(44)
      val meta               = AudioMeta(sampleRate = sampleRate, numChannels = numChannels, bitDepth = bitsPerSample)
      GeneratedAudio(audioData, meta, AudioFormat.WavPcm16)
    }.toResult.left.map(_ => WavGenerationFailed(s"Failed to read WAV file: $path"))

  /**
   * Utility for creating WAV headers manually (advanced usage)
   * Uses BinaryWriter typeclass instances for correct little-endian encoding.
   */
  def createWavHeader(dataSize: Int, meta: AudioMeta): Array[Byte] = {
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
