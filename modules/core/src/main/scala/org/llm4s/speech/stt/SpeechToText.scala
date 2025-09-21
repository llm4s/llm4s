package org.llm4s.speech.stt

import org.llm4s.error.LLMError
import org.llm4s.types.Result
import org.llm4s.speech.{ AudioInput, AudioMeta }

/**
 * Models for speech-to-text.
 */
final case class STTOptions(
  language: Option[String] = None,
  prompt: Option[String] = None,
  enableTimestamps: Boolean = false,
  diarization: Boolean = false
)

final case class WordTimestamp(word: String, startSec: Double, endSec: Double)

final case class Transcription(
  text: String,
  language: Option[String],
  confidence: Option[Double],
  timestamps: List[WordTimestamp] = Nil,
  meta: Option[AudioMeta] = None
)

sealed trait STTError extends LLMError
object STTError {
  final case class EngineNotAvailable(message: String, override val context: Map[String, String] = Map.empty)
      extends STTError
  final case class UnsupportedFormat(message: String, override val context: Map[String, String] = Map.empty)
      extends STTError
  final case class ProcessingFailed(message: String, override val context: Map[String, String] = Map.empty)
      extends STTError
}

/**
 * Abstraction for speech-to-text providers.
 */
trait SpeechToText {
  def name: String
  def transcribe(input: AudioInput, options: STTOptions = STTOptions()): Result[Transcription]
}
