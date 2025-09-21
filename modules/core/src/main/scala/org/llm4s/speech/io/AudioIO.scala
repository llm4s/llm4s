package org.llm4s.speech.io

import org.llm4s.speech.GeneratedAudio
import org.llm4s.error.LLMError
import org.llm4s.types.Result
import org.llm4s.resource.ManagedResource

import java.nio.file.Path
import scala.util.Try
import org.llm4s.types.TryOps

object AudioIO {

  sealed trait AudioIOError extends LLMError
  final case class SaveFailed(message: String, override val context: Map[String, String] = Map.empty)
      extends AudioIOError

  /** Save PCM16 WAV bytes to a file using WavFileGenerator. */
  def saveWav(audio: GeneratedAudio, path: Path): Result[Path] =
    WavFileGenerator.saveAsWav(audio, path)

  /** Save raw PCM16 little-endian to a file using ManagedResource. */
  def saveRawPcm16(audio: GeneratedAudio, path: Path): Result[Path] =
    ManagedResource.fileOutputStream(path).use { fos =>
      Try {
        fos.write(audio.data)
        path
      }.toResult.left.map(_ => SaveFailed("Failed to save raw PCM"))
    }
}
