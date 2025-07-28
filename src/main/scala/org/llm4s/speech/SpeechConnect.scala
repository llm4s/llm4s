package org.llm4s.speech

import org.llm4s.speech.config.{
  AzureSpeechConfig,
  ElevenLabsConfig,
  GoogleSpeechConfig,
  OpenAISpeechConfig,
  SpeechProviderConfig
}
import org.llm4s.speech.provider.{
  AzureSpeechClient,
  ElevenLabsClient,
  GoogleSpeechClient,
  OpenAISpeechClient,
  SpeechProvider
}

object SpeechConnect {
  private def readEnv(key: String): Option[String] =
    sys.env.get(key)

  /**
   * Get a TTS client based on environment variables
   */
  def getTTSClient(): TTSClient = {
    val SPEECH_MODEL_ENV_KEY = "SPEECH_MODEL"
    val model = readEnv(SPEECH_MODEL_ENV_KEY).getOrElse(
      throw new IllegalArgumentException(
        s"Please set the `$SPEECH_MODEL_ENV_KEY` environment variable to specify the default speech model"
      )
    )

    if (model.startsWith("openai/")) {
      val modelName = model.replace("openai/", "")
      val config    = OpenAISpeechConfig.fromEnv(modelName)
      new OpenAISpeechClient(config)
    } else if (model.startsWith("azure/")) {
      val modelName = model.replace("azure/", "")
      val config    = AzureSpeechConfig.fromEnv(modelName)
      new AzureSpeechClient(config)
    } else if (model.startsWith("google/")) {
      val modelName = model.replace("google/", "")
      val config    = GoogleSpeechConfig.fromEnv(modelName)
      new GoogleSpeechClient(config)
    } else if (model.startsWith("elevenlabs/")) {
      val modelName = model.replace("elevenlabs/", "")
      val config    = ElevenLabsConfig.fromEnv(modelName)
      new ElevenLabsClient(config)
    } else {
      throw new IllegalArgumentException(
        s"Model $model is not supported. Supported formats are: 'openai/model-name', 'azure/model-name', 'google/model-name', or 'elevenlabs/model-name'."
      )
    }
  }

  /**
   * Get an ASR client based on environment variables
   */
  def getASRClient(): ASRClient = {
    val SPEECH_MODEL_ENV_KEY = "SPEECH_MODEL"
    val model = readEnv(SPEECH_MODEL_ENV_KEY).getOrElse(
      throw new IllegalArgumentException(
        s"Please set the `$SPEECH_MODEL_ENV_KEY` environment variable to specify the default speech model"
      )
    )

    if (model.startsWith("openai/")) {
      val modelName = model.replace("openai/", "")
      val config    = OpenAISpeechConfig.fromEnv(modelName)
      new OpenAISpeechClient(config)
    } else if (model.startsWith("azure/")) {
      val modelName = model.replace("azure/", "")
      val config    = AzureSpeechConfig.fromEnv(modelName)
      new AzureSpeechClient(config)
    } else if (model.startsWith("google/")) {
      val modelName = model.replace("google/", "")
      val config    = GoogleSpeechConfig.fromEnv(modelName)
      new GoogleSpeechClient(config)
    } else {
      throw new IllegalArgumentException(
        s"Model $model is not supported for ASR. Supported formats are: 'openai/model-name', 'azure/model-name', or 'google/model-name'."
      )
    }
  }

  /**
   * Get a TTS client with explicit provider and configuration
   */
  def getTTSClient(provider: SpeechProvider, config: SpeechProviderConfig): TTSClient =
    provider match {
      case SpeechProvider.OpenAI =>
        new OpenAISpeechClient(config.asInstanceOf[OpenAISpeechConfig])
      case SpeechProvider.Azure =>
        new AzureSpeechClient(config.asInstanceOf[AzureSpeechConfig])
      case SpeechProvider.Google =>
        new GoogleSpeechClient(config.asInstanceOf[GoogleSpeechConfig])
      case SpeechProvider.ElevenLabs =>
        new ElevenLabsClient(config.asInstanceOf[ElevenLabsConfig])
      case SpeechProvider.Amazon =>
        throw new UnsupportedOperationException("Amazon Speech not yet implemented")
    }

  /**
   * Get an ASR client with explicit provider and configuration
   */
  def getASRClient(provider: SpeechProvider, config: SpeechProviderConfig): ASRClient =
    provider match {
      case SpeechProvider.OpenAI =>
        new OpenAISpeechClient(config.asInstanceOf[OpenAISpeechConfig])
      case SpeechProvider.Azure =>
        new AzureSpeechClient(config.asInstanceOf[AzureSpeechConfig])
      case SpeechProvider.Google =>
        new GoogleSpeechClient(config.asInstanceOf[GoogleSpeechConfig])
      case SpeechProvider.ElevenLabs =>
        throw new UnsupportedOperationException("ElevenLabs does not support ASR")
      case SpeechProvider.Amazon =>
        throw new UnsupportedOperationException("Amazon Speech not yet implemented")
    }
}
