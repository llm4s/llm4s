package org.llm4s.speech.config

import org.llm4s.util.Redaction

/**
 * Configuration for cloud TTS providers.
 *
 * @param provider  Provider name: "openai", "elevenlabs", or "azure"
 * @param model     Model/voice identifier (e.g. "tts-1", "tts-1-hd")
 * @param voice     Voice name (e.g. "alloy", "echo" for OpenAI)
 * @param apiKey    API key for the provider
 * @param baseUrl   API base URL (defaults to the official API endpoint)
 * @param region    Azure Speech region (only for Azure provider)
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
}

/**
 * Configuration for cloud STT providers.
 *
 * @param provider  Provider name: "openai" or "azure"
 * @param model     Model identifier (e.g. "whisper-1")
 * @param apiKey    API key for the provider
 * @param baseUrl   API base URL (defaults to the official API endpoint)
 * @param region    Azure Speech region (only for Azure provider)
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
}
