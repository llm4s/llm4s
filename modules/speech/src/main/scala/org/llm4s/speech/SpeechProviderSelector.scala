// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.speech

import org.llm4s.error.ConfigurationError
import org.llm4s.speech.config.{ STTConfig, SpeechConfigLoader, TTSConfig }
import org.llm4s.speech.stt.SpeechToText
import org.llm4s.speech.stt.provider.{ AzureSTTClient, OpenAISTTClient }
import org.llm4s.speech.tts.TextToSpeech
import org.llm4s.speech.tts.provider.{ AzureTTSClient, ElevenLabsTTSClient, OpenAITTSClient }
import org.llm4s.types.Result
import pureconfig.ConfigSource

/**
 * Routes speech provider configuration to the appropriate TTS or STT client.
 *
 * Provider selection follows the same `provider/model` prefix pattern used for LLM providers.
 * The model format, set through `SPEECH_TTS_MODEL` / `SPEECH_STT_MODEL` (see
 * [[org.llm4s.speech.config.SpeechConfigLoader]]), is:
 *   openai/tts-1, elevenlabs/<voice-id>, azure/<voice-name>   (TTS)
 *   openai/whisper-1, azure/en-US                             (STT)
 */
object SpeechProviderSelector {

  /** The TTS client selected by `llm4s.speech.tts.model`, with credentials read from configuration. */
  def tts(): Result[TextToSpeech] = tts(ConfigSource.default)

  /** As [[tts()*]], reading `source`. */
  def tts(source: ConfigSource): Result[TextToSpeech] = SpeechConfigLoader.tts(source).flatMap(getTTSClient)

  /** The STT client selected by `llm4s.speech.stt.model`, with credentials read from configuration. */
  def stt(): Result[SpeechToText] = stt(ConfigSource.default)

  /** As [[stt()*]], reading `source`. */
  def stt(source: ConfigSource): Result[SpeechToText] = SpeechConfigLoader.stt(source).flatMap(getSTTClient)

  /**
   * Returns a [[TextToSpeech]] implementation for the given configuration.
   * Dispatches based on `cfg.provider`:
   *   - "openai"      -> [[org.llm4s.speech.tts.provider.OpenAITTSClient]]
   *   - "elevenlabs"  -> [[org.llm4s.speech.tts.provider.ElevenLabsTTSClient]]
   *   - "azure"       -> [[org.llm4s.speech.tts.provider.AzureTTSClient]]
   *
   * @param cfg TTS provider configuration
   * @return Right(TextToSpeech) on success, Left(ConfigurationError) for unknown provider
   */
  def getTTSClient(cfg: TTSConfig): Result[TextToSpeech] =
    cfg.provider.toLowerCase match {
      case "openai"     => Right(new OpenAITTSClient(cfg))
      case "elevenlabs" => Right(new ElevenLabsTTSClient(cfg))
      case "azure"      => Right(new AzureTTSClient(cfg))
      case unknown =>
        Left(
          ConfigurationError(
            s"Unknown TTS provider '$unknown'. Supported providers: openai, elevenlabs, azure"
          )
        )
    }

  /**
   * Returns a [[SpeechToText]] implementation for the given configuration.
   * Dispatches based on `cfg.provider`:
   *   - "openai" -> [[org.llm4s.speech.stt.provider.OpenAISTTClient]]
   *   - "azure"  -> [[org.llm4s.speech.stt.provider.AzureSTTClient]]
   *
   * @param cfg STT provider configuration
   * @return Right(SpeechToText) on success, Left(ConfigurationError) for unknown provider
   */
  def getSTTClient(cfg: STTConfig): Result[SpeechToText] =
    cfg.provider.toLowerCase match {
      case "openai" => Right(new OpenAISTTClient(cfg))
      case "azure"  => Right(new AzureSTTClient(cfg))
      case unknown =>
        Left(
          ConfigurationError(
            s"Unknown STT provider '$unknown'. Supported providers: openai, azure"
          )
        )
    }

  /**
   * Parses a `provider/model` format string and returns the provider and model parts.
   *
   * @param modelSpec Format: "provider/model", e.g. "openai/tts-1", "elevenlabs/voice-id"
   * @return Right((provider, model)) or Left(ConfigurationError) if format is invalid
   */
  def parseModelSpec(modelSpec: String): Result[(String, String)] =
    modelSpec.split("/", 2).toList match {
      case provider :: model :: Nil if provider.trim.nonEmpty && model.trim.nonEmpty =>
        Right((provider.trim.toLowerCase, model.trim))
      case _ =>
        Left(
          ConfigurationError(
            s"Invalid model spec '$modelSpec'. Expected format: 'provider/model', e.g. 'openai/tts-1'"
          )
        )
    }
}
