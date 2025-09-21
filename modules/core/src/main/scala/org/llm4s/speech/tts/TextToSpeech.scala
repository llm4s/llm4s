package org.llm4s.speech.tts

import org.llm4s.error.LLMError
import org.llm4s.types.Result
import org.llm4s.speech.{ AudioFormat, GeneratedAudio }

/**
 * Models for text-to-speech.
 */
final case class TTSOptions(
  voice: Option[String] = None,
  language: Option[String] = None,
  speakingRate: Option[Double] = None, // 1.0 = normal
  pitchSemitones: Option[Double] = None,
  volumeGainDb: Option[Double] = None,
  outputFormat: AudioFormat = AudioFormat.WavPcm16
)

sealed trait TTSError extends LLMError
object TTSError {
  final case class EngineNotAvailable(message: String, override val context: Map[String, String] = Map.empty)
      extends TTSError
  final case class SynthesisFailed(message: String, override val context: Map[String, String] = Map.empty)
      extends TTSError
}

/**
 * Abstraction for TTS providers.
 */
trait TextToSpeech {
  def name: String
  def synthesize(text: String, options: TTSOptions = TTSOptions()): Result[GeneratedAudio]
}
