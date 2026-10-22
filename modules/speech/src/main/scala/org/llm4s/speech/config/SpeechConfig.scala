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

/**
 * Default values for [[TTSConfig]], one set per cloud provider. [[SpeechConfigLoader]] falls back to them
 * when the corresponding setting is absent.
 *
 * `TTSConfig#toString` redacts the API key (through `Redaction.secret`), so a config is safe to log.
 */
object TTSConfig {

  /** OpenAI: the base URL [[SpeechConfigLoader.tts]] uses when `llm4s.speech.openai.baseUrl` is not set. */
  val DEFAULT_OPENAI_BASE_URL: String = "https://api.openai.com"

  /** ElevenLabs: the base URL [[SpeechConfigLoader.tts]] uses when `llm4s.speech.elevenlabs.baseUrl` is not set. */
  val DEFAULT_ELEVENLABS_BASE_URL: String = "https://api.elevenlabs.io"

  /**
   * OpenAI: the TTS model name for `TTSConfig#model`. Not applied by [[SpeechConfigLoader]], which takes the
   * model from the `provider/model` spec; it is a reference value for callers building a [[TTSConfig]] by hand.
   */
  val DEFAULT_OPENAI_MODEL: String = "tts-1"

  /** OpenAI: the voice [[SpeechConfigLoader.tts]] uses when `llm4s.speech.tts.voice` is not set. */
  val DEFAULT_OPENAI_VOICE: String = "alloy"

  /** ElevenLabs: the `model_id` [[SpeechConfigLoader.tts]] uses when `llm4s.speech.elevenlabs.modelId` is blank. */
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

/**
 * Default values for [[STTConfig]], one set per cloud provider. [[SpeechConfigLoader]] falls back to them
 * when the corresponding setting is absent.
 *
 * `STTConfig#toString` redacts the API key (through `Redaction.secret`), so a config is safe to log.
 */
object STTConfig {

  /** OpenAI: the base URL [[SpeechConfigLoader.stt]] uses when `llm4s.speech.openai.baseUrl` is not set. */
  val DEFAULT_OPENAI_BASE_URL: String = "https://api.openai.com"

  /**
   * OpenAI: the STT model name for `STTConfig#model`. Not applied by [[SpeechConfigLoader]], which takes the
   * model from the `provider/model` spec; it is a reference value for callers building an [[STTConfig]] by hand.
   */
  val DEFAULT_OPENAI_MODEL: String = "whisper-1"

  /**
   * Azure: the recognition language that `STTConfig#model` carries for Azure. Not applied by
   * [[SpeechConfigLoader]], which takes the language from the `azure/<language>` spec; it is a reference value
   * for callers building an [[STTConfig]] by hand.
   */
  val DEFAULT_AZURE_LANGUAGE: String = "en-US"
}
