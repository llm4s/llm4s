// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.speech.config

import org.llm4s.error.ConfigurationError
import org.llm4s.speech.SpeechProviderSelector
import org.llm4s.types.Result
import pureconfig.ConfigSource

/**
 * Loads [[TTSConfig]] and [[STTConfig]] from the `llm4s.speech` block of the application
 * configuration, whose defaults and environment variables are declared in this module's
 * `reference.conf`.
 *
 * Provider selection is the `provider/model` form used for chat models:
 *
 * {{{
 * SPEECH_TTS_MODEL=openai/tts-1            OPENAI_API_KEY=...
 * SPEECH_TTS_MODEL=elevenlabs/<voice-id>   ELEVENLABS_API_KEY=...
 * SPEECH_TTS_MODEL=azure/en-US-JennyNeural AZURE_SPEECH_KEY=... AZURE_SPEECH_REGION=...
 * SPEECH_STT_MODEL=openai/whisper-1        OPENAI_API_KEY=...
 * SPEECH_STT_MODEL=azure/en-US             AZURE_SPEECH_KEY=... AZURE_SPEECH_REGION=...
 * }}}
 *
 * Only the selected provider's credentials are required.
 */
object SpeechConfigLoader {

  private type Section = Map[String, String]

  private def load(source: ConfigSource): Result[Map[String, Section]] =
    source
      .at("llm4s.speech")
      .load[Map[String, Section]]
      .left
      .map(failures =>
        ConfigurationError(s"Failed to load llm4s.speech config: ${failures.toList.map(_.description).mkString("; ")}")
      )

  private def nonBlank(section: Section, key: String): Option[String] =
    section.get(key).map(_.trim).filter(_.nonEmpty)

  private def required(section: Section, path: String, key: String, envVar: String): Result[String] =
    nonBlank(section, key).toRight(
      ConfigurationError(s"llm4s.speech.$path.$key is not set (environment variable $envVar)", List(s"$path.$key"))
    )

  private def stripSlash(url: String): String = url.trim.stripSuffix("/")

  private def azureBase(section: Section, overrideKey: String, hostPart: String, region: String): String =
    nonBlank(section, overrideKey).map(stripSlash).getOrElse(s"https://$region.$hostPart.speech.microsoft.com")

  private def unknownProvider(kind: String, key: String, provider: String, supported: String): ConfigurationError =
    ConfigurationError(
      s"Unknown $kind provider '$provider' in llm4s.speech.$key. Supported providers: $supported",
      List(key)
    )

  /**
   * The TTS configuration selected by `llm4s.speech.tts.model`.
   *
   * @param source configuration to read, e.g. `ConfigSource.default`
   * @return the provider's configuration, or a [[org.llm4s.error.ConfigurationError]] naming the
   *         missing or invalid key
   */
  def tts(source: ConfigSource): Result[TTSConfig] =
    for {
      root <- load(source)
      spec <- required(root.getOrElse("tts", Map.empty[String, String]), "tts", "model", "SPEECH_TTS_MODEL")
      cfg  <- ttsFrom(root, spec)
    } yield cfg

  /**
   * The TTS configuration for an explicit `provider/model` (e.g. `openai/tts-1`), with that
   * provider's credentials and endpoint read from `source`. For callers that choose the model
   * themselves instead of through `llm4s.speech.tts.model`.
   */
  def tts(modelSpec: String, source: ConfigSource): Result[TTSConfig] =
    load(source).flatMap(ttsFrom(_, modelSpec))

  /** `tts(modelSpec, source)` against the current environment. */
  def tts(modelSpec: String): Result[TTSConfig] = tts(modelSpec, ConfigSource.default)

  private def ttsFrom(root: Map[String, Section], spec: String): Result[TTSConfig] =
    for {
      providerModel <- SpeechProviderSelector.parseModelSpec(spec)
      provider = providerModel._1
      id       = providerModel._2
      voice    = nonBlank(root.getOrElse("tts", Map.empty[String, String]), "voice")
      cfg <- provider match {
        case "openai" =>
          val section = root.getOrElse("openai", Map.empty[String, String])
          required(section, "openai", "apiKey", "OPENAI_API_KEY").map(key =>
            TTSConfig(
              provider,
              model = id,
              voice = voice.getOrElse(TTSConfig.DEFAULT_OPENAI_VOICE),
              apiKey = key,
              baseUrl = stripSlash(section.getOrElse("baseUrl", TTSConfig.DEFAULT_OPENAI_BASE_URL))
            )
          )
        case "elevenlabs" =>
          val section = root.getOrElse("elevenlabs", Map.empty[String, String])
          required(section, "elevenlabs", "apiKey", "ELEVENLABS_API_KEY").map(key =>
            TTSConfig(
              provider,
              model = nonBlank(section, "modelId").getOrElse(TTSConfig.DEFAULT_ELEVENLABS_MODEL_ID),
              voice = voice.getOrElse(id),
              apiKey = key,
              baseUrl = stripSlash(section.getOrElse("baseUrl", TTSConfig.DEFAULT_ELEVENLABS_BASE_URL))
            )
          )
        case "azure" =>
          val section = root.getOrElse("azure", Map.empty[String, String])
          for {
            key    <- required(section, "azure", "apiKey", "AZURE_SPEECH_KEY")
            region <- required(section, "azure", "region", "AZURE_SPEECH_REGION")
          } yield TTSConfig(
            provider,
            model = id,
            voice = voice.getOrElse(id),
            apiKey = key,
            baseUrl = azureBase(section, "ttsBaseUrl", "tts", region),
            region = Some(region)
          )
        case other => Left(unknownProvider("TTS", "tts.model", other, "openai, elevenlabs, azure"))
      }
    } yield cfg

  /**
   * The STT configuration selected by `llm4s.speech.stt.model`.
   *
   * @param source configuration to read, e.g. `ConfigSource.default`
   * @return the provider's configuration, or a [[org.llm4s.error.ConfigurationError]] naming the
   *         missing or invalid key
   */
  def stt(source: ConfigSource): Result[STTConfig] =
    for {
      root <- load(source)
      spec <- required(root.getOrElse("stt", Map.empty[String, String]), "stt", "model", "SPEECH_STT_MODEL")
      cfg  <- sttFrom(root, spec)
    } yield cfg

  /** As `tts(modelSpec, source)`, for speech-to-text: an explicit `provider/model` such as `openai/whisper-1`. */
  def stt(modelSpec: String, source: ConfigSource): Result[STTConfig] =
    load(source).flatMap(sttFrom(_, modelSpec))

  /** `stt(modelSpec, source)` against the current environment. */
  def stt(modelSpec: String): Result[STTConfig] = stt(modelSpec, ConfigSource.default)

  private def sttFrom(root: Map[String, Section], spec: String): Result[STTConfig] =
    for {
      providerModel <- SpeechProviderSelector.parseModelSpec(spec)
      provider = providerModel._1
      id       = providerModel._2
      cfg <- provider match {
        case "openai" =>
          val section = root.getOrElse("openai", Map.empty[String, String])
          required(section, "openai", "apiKey", "OPENAI_API_KEY").map(key =>
            STTConfig(
              provider,
              model = id,
              apiKey = key,
              baseUrl = stripSlash(section.getOrElse("baseUrl", STTConfig.DEFAULT_OPENAI_BASE_URL))
            )
          )
        case "azure" =>
          val section = root.getOrElse("azure", Map.empty[String, String])
          for {
            key    <- required(section, "azure", "apiKey", "AZURE_SPEECH_KEY")
            region <- required(section, "azure", "region", "AZURE_SPEECH_REGION")
          } yield STTConfig(
            provider,
            model = id,
            apiKey = key,
            baseUrl = azureBase(section, "sttBaseUrl", "stt", region),
            region = Some(region)
          )
        case other => Left(unknownProvider("STT", "stt.model", other, "openai, azure"))
      }
    } yield cfg

  /** `tts(source)` against the current environment: system properties, `application.conf` and every `reference.conf`. */
  def tts(): Result[TTSConfig] = tts(ConfigSource.default)

  /** `stt(source)` against the current environment. */
  def stt(): Result[STTConfig] = stt(ConfigSource.default)
}
