package org.llm4s.speech

import org.llm4s.error.ConfigurationError
import org.llm4s.speech.config.{ STTConfig, TTSConfig }
import org.llm4s.speech.stt.SpeechToText
import org.llm4s.speech.stt.provider.{ AzureSTTClient, OpenAISTTClient }
import org.llm4s.speech.tts.TextToSpeech
import org.llm4s.speech.tts.provider.{ AzureTTSClient, ElevenLabsTTSClient, OpenAITTSClient }
import org.llm4s.types.Result

/**
 * Routes speech provider configuration to the appropriate TTS or STT client.
 *
 * Provider selection follows the same `provider/model` prefix pattern used for LLM providers.
 * The model env-var format is:
 *   SPEECH_TTS_MODEL=openai/tts-1
 *   SPEECH_TTS_MODEL=elevenlabs/<voice-id>
 *   SPEECH_TTS_MODEL=azure/<voice-name>
 *   SPEECH_STT_MODEL=openai/whisper-1
 *   SPEECH_STT_MODEL=azure/en-US
 */
object SpeechProviderSelector {

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
      case "openai"     => Right(OpenAITTSClient.fromConfig(cfg))
      case "elevenlabs" => Right(ElevenLabsTTSClient.fromConfig(cfg))
      case "azure"      => Right(AzureTTSClient.fromConfig(cfg))
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
      case "openai" => Right(OpenAISTTClient.fromConfig(cfg))
      case "azure"  => Right(AzureSTTClient.fromConfig(cfg))
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
