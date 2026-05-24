package org.llm4s.speech.io

import org.llm4s.error.LLMError
import org.llm4s.types.Result
import org.llm4s.speech.{ GeneratedAudio, AudioMeta, AudioFormat }
import org.llm4s.resource.ManagedResource

import java.io.{ ByteArrayOutputStream, DataOutputStream }
import java.nio.file.{ Path, Files }
import javax.sound.sampled.{ AudioFileFormat, AudioFormat => JAudioFormat, AudioSystem }
import scala.util.{ Try, Using }
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
   * Validate AudioMeta values before file generation to catch bad parameters early.
   */
  def validateMetadata(meta: AudioMeta): Result[AudioMeta] = {
    val validBitDepths = Set(8, 16, 24, 32)
    val errors = List(
      Option.when(meta.sampleRate <= 0)(s"sampleRate must be positive, got ${meta.sampleRate}"),
      Option.when(meta.numChannels <= 0)(s"numChannels must be positive, got ${meta.numChannels}"),
      Option.when(!validBitDepths.contains(meta.bitDepth))(
        s"bitDepth must be one of $validBitDepths, got ${meta.bitDepth}"
      )
    ).flatten
    if (errors.isEmpty) Right(meta)
    else Left(WavGenerationFailed(errors.mkString("; ")))
  }

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
  def saveRawPcmAsWav(data: Array[Byte], meta: AudioMeta, path: Path): Result[Path] =
    for {
      validMeta <- validateMetadata(meta)
      result    <- saveAsWav(GeneratedAudio(data, validMeta, AudioFormat.WavPcm16), path)
    } yield result

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
      validMeta <- validateMetadata(meta)
      tempPath  <- createTempWavFile(prefix)
      savedPath <- saveAsWav(GeneratedAudio(data, validMeta, AudioFormat.WavPcm16), tempPath)
    } yield savedPath

  /**
   * Read WAV file and return GeneratedAudio.
   *
   * Uses AudioSystem.getAudioInputStream to delegate format parsing to the JDK,
   * which handles non-standard chunk layouts and avoids loading the entire file
   * into memory before we know where the audio data starts.
   */
  def readWavFile(path: Path): Result[GeneratedAudio] =
    Using(AudioSystem.getAudioInputStream(path.toFile)) { ais =>
      val fmt  = ais.getFormat
      val meta = AudioMeta(
        sampleRate  = fmt.getSampleRate.toInt,
        numChannels = fmt.getChannels,
        bitDepth    = fmt.getSampleSizeInBits
      )
      GeneratedAudio(ais.readAllBytes(), meta, AudioFormat.WavPcm16)
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
