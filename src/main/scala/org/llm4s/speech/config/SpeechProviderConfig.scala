package org.llm4s.speech.config

sealed trait SpeechProviderConfig {
  def model: String
}

object SpeechProviderConfig {
  def readEnv(key: String): Option[String] =
    sys.env.get(key)
}

case class OpenAISpeechConfig(
  apiKey: String,
  model: String = "tts-1",
  baseUrl: String = "https://api.openai.com/v1"
) extends SpeechProviderConfig

object OpenAISpeechConfig {

  /**
   * Create an OpenAISpeechConfig from environment variables
   */
  def fromEnv(modelName: String): OpenAISpeechConfig = {
    val readEnv = SpeechProviderConfig.readEnv _

    OpenAISpeechConfig(
      apiKey = readEnv("OPENAI_API_KEY").getOrElse(
        throw new IllegalArgumentException("OPENAI_API_KEY not set, required when using openai/ model.")
      ),
      model = modelName,
      baseUrl = readEnv("OPENAI_BASE_URL").getOrElse("https://api.openai.com/v1")
    )
  }
}

case class AzureSpeechConfig(
  apiKey: String,
  region: String,
  model: String = "en-US-JennyNeural",
  baseUrl: String = "https://%s.tts.speech.microsoft.com/cognitiveservices/v1"
) extends SpeechProviderConfig

object AzureSpeechConfig {

  /**
   * Create an AzureSpeechConfig from environment variables
   */
  def fromEnv(modelName: String): AzureSpeechConfig = {
    val readEnv = SpeechProviderConfig.readEnv _

    AzureSpeechConfig(
      apiKey = readEnv("AZURE_SPEECH_API_KEY").getOrElse(
        throw new IllegalArgumentException("AZURE_SPEECH_API_KEY not set, required when using azure/ model.")
      ),
      region = readEnv("AZURE_SPEECH_REGION").getOrElse(
        throw new IllegalArgumentException("AZURE_SPEECH_REGION not set, required when using azure/ model.")
      ),
      model = modelName,
      baseUrl = readEnv("AZURE_SPEECH_BASE_URL").getOrElse("https://%s.tts.speech.microsoft.com/cognitiveservices/v1")
    )
  }
}

case class GoogleSpeechConfig(
  apiKey: String,
  model: String = "latest",
  baseUrl: String = "https://texttospeech.googleapis.com/v1"
) extends SpeechProviderConfig

object GoogleSpeechConfig {

  /**
   * Create a GoogleSpeechConfig from environment variables
   */
  def fromEnv(modelName: String): GoogleSpeechConfig = {
    val readEnv = SpeechProviderConfig.readEnv _

    GoogleSpeechConfig(
      apiKey = readEnv("GOOGLE_SPEECH_API_KEY").getOrElse(
        throw new IllegalArgumentException("GOOGLE_SPEECH_API_KEY not set, required when using google/ model.")
      ),
      model = modelName,
      baseUrl = readEnv("GOOGLE_SPEECH_BASE_URL").getOrElse("https://texttospeech.googleapis.com/v1")
    )
  }
}

case class ElevenLabsConfig(
  apiKey: String,
  model: String = "eleven_monolingual_v1",
  baseUrl: String = "https://api.elevenlabs.io/v1"
) extends SpeechProviderConfig

object ElevenLabsConfig {

  /**
   * Create an ElevenLabsConfig from environment variables
   */
  def fromEnv(modelName: String): ElevenLabsConfig = {
    val readEnv = SpeechProviderConfig.readEnv _

    ElevenLabsConfig(
      apiKey = readEnv("ELEVENLABS_API_KEY").getOrElse(
        throw new IllegalArgumentException("ELEVENLABS_API_KEY not set, required when using elevenlabs/ model.")
      ),
      model = modelName,
      baseUrl = readEnv("ELEVENLABS_BASE_URL").getOrElse("https://api.elevenlabs.io/v1")
    )
  }
}
