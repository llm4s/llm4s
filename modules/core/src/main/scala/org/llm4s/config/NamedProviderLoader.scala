package org.llm4s.config

import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.llmconnect.config.*
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.*
import pureconfig.ConfigSource

private[config] object NamedProviderLoader:

  def load(source: ConfigSource, providerName: String)(using ContextWindowResolver): Result[ProviderConfig] =
    val trimmed = providerName.trim
    if trimmed.isEmpty then Left(ConfigurationError("Named provider selection requires a non-empty provider name"))
    else
      for
        providers <- ProvidersConfigLoader.load(source)
        normalized <- providers.namedProviders
          .get(ProviderName(trimmed))
          .toRight(ConfigurationError(s"Configured provider '$trimmed' was not found"))
        config <- buildConfigFromNamedConfig(trimmed, normalized)
      yield config

  def loadProviderConfigs(
    source: ConfigSource
  )(using ContextWindowResolver): Result[(Map[ProviderName, LLMError], Map[ProviderName, ProviderConfig])] =
    for
      providers <- ProvidersConfigLoader.load(source)
      namedProviders = providers.namedProviders
      r              = getProviderConfigs(namedProviders)
    yield r

  def getProviderConfigs(
    namedProviders: Map[ProviderName, NamedProviderConfig]
  )(using ContextWindowResolver): (Map[ProviderName, LLMError], Map[ProviderName, ProviderConfig]) =
    namedProviders.toList.foldLeft((Map.empty[ProviderName, LLMError], Map.empty[ProviderName, ProviderConfig]))(
      (x, y) =>
        buildConfigFromNamedConfig(y._1.asName, y._2).fold(
          (error: LLMError) =>
            val kv = (y._1, error)
            (x._1 + kv, x._2)
          ,
          (providerConfig: ProviderConfig) =>
            val kv: (ProviderName, ProviderConfig) = (y._1, providerConfig)
            (x._1, x._2 + kv)
        )
    )

  private def buildConfigFromNamedConfig(
    providerName: String,
    section: NamedProviderConfig
  )(using ContextWindowResolver): Result[ProviderConfig] =
    def required(fieldName: String, value: Option[String], envHint: String): Result[String] =
      value.toRight(
        ConfigurationError(s"Configured provider '$providerName' is missing $fieldName ($envHint)")
      )

    def requiredApiKey(envHint: String): Result[String] =
      required("api key", section.apiKey.map(_.asKey), envHint)

    section.provider match
      case ProviderKind.OpenAI | ProviderKind.OpenRouter =>
        requiredApiKey("llm4s.providers.<name>.apiKey").map: apiKey =>
          val defaultBaseUrl =
            if section.provider == ProviderKind.OpenRouter then DefaultConfig.DEFAULT_OPENROUTER_BASE_URL
            else DefaultConfig.DEFAULT_OPENAI_BASE_URL
          val baseUrl = section.baseUrl.map(_.asUrl).getOrElse(defaultBaseUrl)
          val timeoutMs = section.timeoutMs.getOrElse(30000)
          OpenAIConfig.fromValues(section.model.asString, apiKey, section.organization, baseUrl, timeoutMs)
      case ProviderKind.Azure =>
        for
          endpoint <- required("endpoint", section.endpoint, "llm4s.providers.<name>.endpoint")
          apiKey   <- requiredApiKey("llm4s.providers.<name>.apiKey")
          apiVersion = section.apiVersion.getOrElse(DefaultConfig.DEFAULT_AZURE_V2025_01_01_PREVIEW)
          timeoutMs = section.timeoutMs.getOrElse(30000)
        yield AzureConfig.fromValues(section.model.asString, endpoint, apiKey, apiVersion, timeoutMs)
      case ProviderKind.Anthropic =>
        requiredApiKey("llm4s.providers.<name>.apiKey").map: apiKey =>
          val baseUrl = section.baseUrl.map(_.asUrl).getOrElse(DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL)
          val timeoutMs = section.timeoutMs.getOrElse(30000)
          AnthropicConfig.fromValues(section.model.asString, apiKey, baseUrl, timeoutMs)
      case ProviderKind.Ollama =>
        section.baseUrl
          .map(_.asUrl)
          .toRight(
            ConfigurationError(
              s"Configured provider '$providerName' is missing base URL (llm4s.providers.<name>.baseUrl)"
            )
          )
          .map: url =>
            val timeoutMs = section.timeoutMs.getOrElse(30000)
            OllamaConfig.fromValues(section.model.asString, url, timeoutMs)
      case ProviderKind.Zai =>
        requiredApiKey("llm4s.providers.<name>.apiKey").map: apiKey =>
          val baseUrl = section.baseUrl.map(_.asUrl).getOrElse(ZaiConfig.DEFAULT_BASE_URL)
          val timeoutMs = section.timeoutMs.getOrElse(30000)
          ZaiConfig.fromValues(section.model.asString, apiKey, baseUrl, timeoutMs)
      case ProviderKind.Gemini =>
        requiredApiKey("llm4s.providers.<name>.apiKey").map: apiKey =>
          val baseUrl = section.baseUrl.map(_.asUrl).getOrElse(DefaultConfig.DEFAULT_GEMINI_BASE_URL)
          val timeoutMs = section.timeoutMs.getOrElse(30000)
          GeminiConfig.fromValues(section.model.asString, apiKey, baseUrl, timeoutMs)
      case ProviderKind.DeepSeek =>
        requiredApiKey("llm4s.providers.<name>.apiKey").map: apiKey =>
          val baseUrl = section.baseUrl.map(_.asUrl).getOrElse(DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL)
          val timeoutMs = section.timeoutMs.getOrElse(30000)
          DeepSeekConfig.fromValues(section.model.asString, apiKey, baseUrl, timeoutMs)
      case ProviderKind.Cohere =>
        requiredApiKey("llm4s.providers.<name>.apiKey").map: apiKey =>
          val baseUrl = section.baseUrl.map(_.asUrl).getOrElse(CohereConfig.DEFAULT_BASE_URL)
          val timeoutMs = section.timeoutMs.getOrElse(30000)
          CohereConfig.fromValues(section.model.asString, apiKey, baseUrl, timeoutMs)
      case ProviderKind.Mistral =>
        requiredApiKey("llm4s.providers.<name>.apiKey").map: apiKey =>
          val baseUrl = section.baseUrl.map(_.asUrl).getOrElse(MistralConfig.DEFAULT_BASE_URL)
          val timeoutMs = section.timeoutMs.getOrElse(30000)
          MistralConfig.fromValues(section.model.asString, apiKey, baseUrl, timeoutMs)
