package org.llm4s.speech.io

import org.llm4s.error.LLMError
import org.llm4s.types.Result
import org.llm4s.speech.{ GeneratedAudio, AudioMeta, AudioFormat }
import org.llm4s.resource.ManagedResource

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.{ Path, Files }
import javax.sound.sampled.{ AudioFileFormat, AudioFormat => JAudioFormat, AudioSystem }
import scala.util.{ Try, Using }
import org.llm4s.types.TryOps
import java.nio.charset.StandardCharsets

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
  final case class WavValidationFailed(message: String, override val context: Map[String, String] = Map.empty)
      extends WavError

  private val MinSampleRate  = 8000
  private val MaxSampleRate  = 192000
  private val ValidBitDepths = Set(8, 16, 24, 32)
  private val MaxChannels    = 8

  /** Prevent loading extremely large WAV files */
  private val MaxWavSizeBytes = 50 * 1024 * 1024 // 50MB

  /** llm4s currently supports only PCM16 WAV */
  private def detectFormat(bitDepth: Int): AudioFormat =
    AudioFormat.WavPcm16

  /**
   * Validates AudioMeta for correctness
   */
  def validateMetadata(meta: AudioMeta): Result[AudioMeta] =
    if (meta.sampleRate < MinSampleRate || meta.sampleRate > MaxSampleRate)
      Left(WavValidationFailed(s"Sample rate ${meta.sampleRate} out of range [$MinSampleRate, $MaxSampleRate]"))
    else if (!ValidBitDepths.contains(meta.bitDepth))
      Left(WavValidationFailed(s"Bit depth ${meta.bitDepth} not supported. Valid: $ValidBitDepths"))
    else if (meta.numChannels < 1 || meta.numChannels > MaxChannels)
      Left(WavValidationFailed(s"Number of channels ${meta.numChannels} out of range [1, $MaxChannels]"))
    else
      Right(meta)

  /**
   * Create a temporary WAV file
   */
  def createTempWavFile(prefix: String): Result[Path] =
    Try {
      Files.createTempFile(prefix, ".wav")
    }.toResult.left.map(_ => WavGenerationFailed(s"Failed to create temp WAV file with prefix: $prefix"))

  /**
   * Managed temp file (auto cleanup)
   */
  def managedTempWavFile(prefix: String): ManagedResource[Path] =
    ManagedResource.tempFile(prefix, ".wav")

  /**
   * Convert AudioMeta -> Java AudioFormat
   */
  def createJavaAudioFormat(meta: AudioMeta): JAudioFormat =
    new JAudioFormat(
      meta.sampleRate.toFloat,
      meta.bitDepth,
      meta.numChannels,
      true,
      false
    )

  /**
   * Save GeneratedAudio as WAV
   */
  def saveAsWav(audio: GeneratedAudio, path: Path): Result[Path] =
    for {
      _ <- validateMetadata(audio.meta)
      result <- ManagedResource.audioInputStream(audio.data, createJavaAudioFormat(audio.meta)).use { ais =>
        Try {
          AudioSystem.write(ais, AudioFileFormat.Type.WAVE, path.toFile)
          path
        }.toResult.left.map(_ => WavSaveFailed(s"Failed to save WAV to: $path"))
      }
    } yield result

  /**
   * Save PCM bytes as WAV
   */
  def saveRawPcmAsWav(data: Array[Byte], meta: AudioMeta, path: Path): Result[Path] =
    for {
      validMeta <- validateMetadata(meta)
      audio = GeneratedAudio(data, validMeta, detectFormat(validMeta.bitDepth))
      result <- saveAsWav(audio, path)
    } yield result

  /**
   * Construct GeneratedAudio from raw bytes
   */
  def createWavFromBytes(data: Array[Byte], meta: AudioMeta): Result[GeneratedAudio] =
    for {
      validMeta <- validateMetadata(meta)
    } yield GeneratedAudio(data, validMeta, detectFormat(validMeta.bitDepth))

  /**
   * Write audio bytes to temp WAV
   */
  def writeToTempWav(data: Array[Byte], meta: AudioMeta, prefix: String = "llm4s-audio"): Result[Path] =
    for {
      validMeta <- validateMetadata(meta)
      tempPath  <- createTempWavFile(prefix)
      audio = GeneratedAudio(data, validMeta, detectFormat(validMeta.bitDepth))
      savedPath <- saveAsWav(audio, tempPath)
    } yield savedPath

  /**
   * Read WAV file safely
   */
  def readWavFile(path: Path): Result[GeneratedAudio] =
    if (!Files.exists(path))
      Left(WavGenerationFailed(s"WAV file does not exist: $path"))
    else {

      val fileSize = Files.size(path)

      if (fileSize > MaxWavSizeBytes)
        Left(WavValidationFailed(s"WAV file too large: $fileSize bytes (max allowed $MaxWavSizeBytes)"))
      else
        Try {
          Using.resource(AudioSystem.getAudioInputStream(path.toFile)) { audioInputStream =>

            val javaFormat = audioInputStream.getFormat

            if (javaFormat.getEncoding != javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED)
              throw new IllegalArgumentException("Unsupported WAV encoding (only PCM_SIGNED supported)")

            val meta = AudioMeta(
              sampleRate = javaFormat.getSampleRate.toInt,
              numChannels = javaFormat.getChannels,
              bitDepth = javaFormat.getSampleSizeInBits
            )

            val out    = new ByteArrayOutputStream()
            val buffer = new Array[Byte](4096)

            var read = audioInputStream.read(buffer)
            while (read != -1) {
              out.write(buffer, 0, read)
              read = audioInputStream.read(buffer)
            }

            val audioData = out.toByteArray

            GeneratedAudio(audioData, meta, detectFormat(meta.bitDepth))
          }
        }.toResult.left.map(e => WavGenerationFailed(s"Failed to read WAV file: $path. Error: ${e.message}"))
    }

  /**
   * Create WAV header (low-level utility)
   */
  def createWavHeader(dataSize: Int, meta: AudioMeta): Array[Byte] =
    validateMetadata(meta) match {
      case Left(err) =>
        throw new IllegalArgumentException(err.message)

      case Right(_) =>
        val bytesPerSample = (meta.bitDepth + 7) / 8
        val byteRate       = meta.sampleRate * meta.numChannels * bytesPerSample
        val blockAlign     = (meta.numChannels * bytesPerSample).toShort

        val buffer = ByteBuffer.allocate(44)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII))
        buffer.putInt(dataSize + 36)

        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII))

        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1.toShort)
        buffer.putShort(meta.numChannels.toShort)
        buffer.putInt(meta.sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign)
        buffer.putShort(meta.bitDepth.toShort)

        buffer.put("data".getBytes(StandardCharsets.US_ASCII))
        buffer.putInt(dataSize)

        val header = buffer.array()
        require(header.length == 44, "Invalid WAV header size")

        header
    }
}
