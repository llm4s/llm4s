package org.llm4s.speech.io

import org.llm4s.speech.{ AudioFormat, GeneratedAudio }
import org.llm4s.error.{ LLMError, NonRecoverableError, ValidationError }
import org.llm4s.types.Result
import org.llm4s.resource.ManagedResource

import java.nio.file.Path
import scala.util.Try
import org.llm4s.types.TryOps

object AudioIO {

  /** Audio could not be saved; a [[NonRecoverableError]], as a core `ProcessingError` is. */
  sealed trait AudioIOError extends LLMError with NonRecoverableError
  final case class SaveFailed(message: String, override val context: Map[String, String] = Map.empty)
      extends AudioIOError

  /** Save PCM16 WAV bytes to a file using WavFileGenerator. */
  def saveWav(audio: GeneratedAudio, path: Path): Result[Path] =
    WavFileGenerator.saveAsWav(audio, path)

  /** Save raw PCM16 little-endian to a file using ManagedResource. */
  def saveRawPcm16(audio: GeneratedAudio, path: Path): Result[Path] =
    audio.requirePcm("Saving raw PCM").flatMap(_ => writeBytes(audio, path, "Failed to save raw PCM"))

  /** Save MP3 audio (see [[org.llm4s.speech.AudioFormat.Mp3]]) to a file as returned by the service. */
  def saveMp3(audio: GeneratedAudio, path: Path): Result[Path] =
    if (audio.format == AudioFormat.Mp3) writeBytes(audio, path, "Failed to save MP3")
    else Left(ValidationError("audio", s"Saving MP3 needs MP3 audio, but this is ${audio.format}"))

  private def writeBytes(audio: GeneratedAudio, path: Path, failure: String): Result[Path] =
    ManagedResource.fileOutputStream(path).use { fos =>
      Try {
        fos.write(audio.data)
        path
      }.toResult.left.map(_ => SaveFailed(failure))
    }
}
