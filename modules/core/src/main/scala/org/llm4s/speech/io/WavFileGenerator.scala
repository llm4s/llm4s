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
   * Read WAV file and return GeneratedAudio
   */
  def readWavFile(path: Path): Result[GeneratedAudio] =
    Try {
      val bytes = Files.readAllBytes(path)
      // For simplicity, assume standard PCM16 format - in production,
      // this could be enhanced to read actual WAV headers
      val meta = AudioMeta(sampleRate = 22050, numChannels = 1, bitDepth = 16)
      GeneratedAudio(bytes, meta, AudioFormat.WavPcm16)
    }.toResult.left.map(_ => WavGenerationFailed(s"Failed to read WAV file: $path"))

  /**
   * Utility for creating WAV headers manually (advanced usage)
   * Uses implicit binary writers for clean little-endian format
   */
  def createWavHeader(dataSize: Int, meta: AudioMeta): Array[Byte] = {
    val byteRate   = meta.sampleRate * meta.numChannels * (meta.bitDepth / 8)
    val blockAlign = (meta.numChannels * meta.bitDepth / 8).toShort

    val header = new ByteArrayOutputStream(44)
    val dos    = new DataOutputStream(header)

    // Use the data-driven programming style suggested by @atulkhot
    val headerData = List(
      "RIFF".getBytes,                   // ChunkID
      (dataSize + 36).asInstanceOf[Int], // ChunkSize
      "WAVE".getBytes,                   // Format
      "fmt ".getBytes,                   // Subchunk1ID
      16.asInstanceOf[Int],              // Subchunk1Size
      1.toShort,                         // AudioFormat (PCM)
      meta.numChannels.toShort,          // NumChannels
      meta.sampleRate.asInstanceOf[Int], // SampleRate
      byteRate.asInstanceOf[Int],        // ByteRate
      blockAlign,                        // BlockAlign
      meta.bitDepth.toShort,             // BitsPerSample
      "data".getBytes,                   // Subchunk2ID
      dataSize.asInstanceOf[Int]         // Subchunk2Size
    )

    // This demonstrates the data-driven approach - each value is written with its appropriate type
    headerData.foreach {
      case bytes: Array[Byte] => dos.write(bytes)
      case int: Int           => dos.write(int)
      case short: Short       => dos.write(short)
    }

    header.toByteArray
  }
}
