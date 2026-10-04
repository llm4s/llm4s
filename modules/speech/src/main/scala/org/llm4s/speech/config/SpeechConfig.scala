package org.llm4s.speech.config

import org.llm4s.util.Redaction

/**
 * Configuration for a cloud TTS provider, as produced by [[SpeechConfigLoader.tts]].
 *
 * @param provider  Provider name: "openai", "elevenlabs", or "azure"
 * @param model     Model identifier (OpenAI: `tts-1`; ElevenLabs: the `model_id`; Azure: unused)
 * @param voice     Voice (OpenAI: `alloy`; ElevenLabs: voice id; Azure: voice name)
 * @param apiKey    API key for the provider
 * @param baseUrl   API base URL, without a trailing path (the endpoint path is added by the client)
 * @param region    Azure Speech region, when the provider is Azure
 */
final case class TTSConfig(
  provider: String,
  model: String,
  voice: String,
  apiKey: String,
  baseUrl: String,
  region: Option[String] = None
) {
  override def toString: String =
    s"TTSConfig(provider=$provider, model=$model, voice=$voice, apiKey=${Redaction.secret(apiKey)}, " +
      s"baseUrl=$baseUrl, region=$region)"
}

object TTSConfig {
  val DEFAULT_OPENAI_BASE_URL: String     = "https://api.openai.com"
  val DEFAULT_ELEVENLABS_BASE_URL: String = "https://api.elevenlabs.io"
  val DEFAULT_OPENAI_MODEL: String        = "tts-1"
  val DEFAULT_OPENAI_VOICE: String        = "alloy"
  val DEFAULT_ELEVENLABS_MODEL_ID: String = "eleven_multilingual_v2"
}

/**
 * Configuration for a cloud STT provider, as produced by [[SpeechConfigLoader.stt]].
 *
 * @param provider  Provider name: "openai" or "azure"
 * @param model     Model identifier (OpenAI: `whisper-1`; Azure: the default recognition language, e.g. `en-US`)
 * @param apiKey    API key for the provider
 * @param baseUrl   API base URL, without a trailing path (the endpoint path is added by the client)
 * @param region    Azure Speech region, when the provider is Azure
 */
final case class STTConfig(
  provider: String,
  model: String,
  apiKey: String,
  baseUrl: String,
  region: Option[String] = None
) {
  override def toString: String =
    s"STTConfig(provider=$provider, model=$model, apiKey=${Redaction.secret(apiKey)}, " +
      s"baseUrl=$baseUrl, region=$region)"
}

object STTConfig {
  val DEFAULT_OPENAI_BASE_URL: String = "https://api.openai.com"
  val DEFAULT_OPENAI_MODEL: String    = "whisper-1"
  val DEFAULT_AZURE_LANGUAGE: String  = "en-US"
}
