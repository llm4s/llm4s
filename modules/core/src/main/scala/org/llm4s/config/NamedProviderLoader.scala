package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.*
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.*
import pureconfig.ConfigSource

private[config] object NamedProviderLoader:

  def load(source: ConfigSource, providerName: String): Result[ProviderConfig] =
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

  private def buildConfigFromNamedConfig(
    providerName: String,
    section: NamedProviderConfig
  ): Result[ProviderConfig] =
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
          OpenAIConfig.fromValues(section.model.asString, apiKey, section.organization, baseUrl)
      case ProviderKind.Azure =>
        for
          endpoint <- required("endpoint", section.endpoint, "llm4s.providers.<name>.endpoint")
          apiKey   <- requiredApiKey("llm4s.providers.<name>.apiKey")
          apiVersion = section.apiVersion.getOrElse(DefaultConfig.DEFAULT_AZURE_V2025_01_01_PREVIEW)
        yield AzureConfig.fromValues(section.model.asString, endpoint, apiKey, apiVersion)
      case ProviderKind.Anthropic =>
        requiredApiKey("llm4s.providers.<name>.apiKey").map: apiKey =>
          val baseUrl = section.baseUrl.map(_.asUrl).getOrElse(DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL)
          AnthropicConfig.fromValues(section.model.asString, apiKey, baseUrl)
      case ProviderKind.Ollama =>
        section.baseUrl
          .map(_.asUrl)
          .toRight(
            ConfigurationError(
              s"Configured provider '$providerName' is missing base URL (llm4s.providers.<name>.baseUrl)"
            )
          )
          .map(url => OllamaConfig.fromValues(section.model.asString, url))
      case ProviderKind.Zai =>
        requiredApiKey("llm4s.providers.<name>.apiKey").map: apiKey =>
          val baseUrl = section.baseUrl.map(_.asUrl).getOrElse(ZaiConfig.DEFAULT_BASE_URL)
          ZaiConfig.fromValues(section.model.asString, apiKey, baseUrl)
      case ProviderKind.Gemini =>
        requiredApiKey("llm4s.providers.<name>.apiKey").map: apiKey =>
          val baseUrl = section.baseUrl.map(_.asUrl).getOrElse(DefaultConfig.DEFAULT_GEMINI_BASE_URL)
          GeminiConfig.fromValues(section.model.asString, apiKey, baseUrl)
      case ProviderKind.DeepSeek =>
        requiredApiKey("llm4s.providers.<name>.apiKey").map: apiKey =>
          val baseUrl = section.baseUrl.map(_.asUrl).getOrElse(DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL)
          DeepSeekConfig.fromValues(section.model.asString, apiKey, baseUrl)
      case ProviderKind.Cohere =>
        requiredApiKey("llm4s.providers.<name>.apiKey").map: apiKey =>
          val baseUrl = section.baseUrl.map(_.asUrl).getOrElse(CohereConfig.DEFAULT_BASE_URL)
          CohereConfig.fromValues(section.model.asString, apiKey, baseUrl)
      case ProviderKind.Mistral =>
        requiredApiKey("llm4s.providers.<name>.apiKey").map: apiKey =>
          val baseUrl = section.baseUrl.map(_.asUrl).getOrElse(MistralConfig.DEFAULT_BASE_URL)
          MistralConfig.fromValues(section.model.asString, apiKey, baseUrl)
