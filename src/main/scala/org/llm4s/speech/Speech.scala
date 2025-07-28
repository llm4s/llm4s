package org.llm4s.speech

import org.llm4s.speech.config.SpeechProviderConfig
import org.llm4s.speech.model._
import org.llm4s.speech.provider.SpeechProvider

object Speech {

  /** Factory method for getting a TTS client with the right configuration */
  def ttsClient(
    provider: SpeechProvider,
    config: SpeechProviderConfig
  ): TTSClient = SpeechConnect.getTTSClient(provider, config)

  /** Factory method for getting an ASR client with the right configuration */
  def asrClient(
    provider: SpeechProvider,
    config: SpeechProviderConfig
  ): ASRClient = SpeechConnect.getASRClient(provider, config)

  /** Convenience method for quick text-to-speech conversion */
  def synthesize(
    text: String,
    provider: SpeechProvider,
    config: SpeechProviderConfig,
    options: TTSSynthesisOptions = TTSSynthesisOptions()
  ): Either[SpeechError, AudioResponse] =
    ttsClient(provider, config).synthesize(text, options)

  /** Convenience method for quick speech-to-text conversion */
  def transcribe(
    audioData: Array[Byte],
    provider: SpeechProvider,
    config: SpeechProviderConfig,
    options: ASRTranscriptionOptions = ASRTranscriptionOptions()
  ): Either[SpeechError, TranscriptionResponse] =
    asrClient(provider, config).transcribe(audioData, options)

  /** Get a TTS client based on environment variables */
  def ttsClient(): TTSClient = SpeechConnect.getTTSClient()

  /** Get an ASR client based on environment variables */
  def asrClient(): ASRClient = SpeechConnect.getASRClient()

  /** Convenience method for quick text-to-speech using environment variables */
  def synthesizeWithEnv(
    text: String,
    options: TTSSynthesisOptions = TTSSynthesisOptions()
  ): Either[SpeechError, AudioResponse] =
    ttsClient().synthesize(text, options)

  /** Convenience method for quick speech-to-text using environment variables */
  def transcribeWithEnv(
    audioData: Array[Byte],
    options: ASRTranscriptionOptions = ASRTranscriptionOptions()
  ): Either[SpeechError, TranscriptionResponse] =
    asrClient().transcribe(audioData, options)
}
