package org.llm4s.llmconnect.config

import org.llm4s.config.{ ConfigKeys, ConfigReader }
import ConfigKeys._
import org.llm4s.config.DefaultConfig._
sealed trait ProviderConfig {
  def model: String
}

case class OpenAIConfig(
  apiKey: String,
  model: String,
  organization: Option[String],
  baseUrl: String
) extends ProviderConfig

object OpenAIConfig {

  def from(modelName: String, config: ConfigReader): OpenAIConfig =
    OpenAIConfig(
      apiKey = config
        .get(OPENAI_API_KEY)
        .getOrElse(
          throw new IllegalArgumentException("OPENAI_API_KEY not set, required when using openai/ model.")
        ),
      model = modelName,
      organization = config.get(OPENAI_ORG),
      baseUrl = config.getOrElse(OPENAI_BASE_URL, DEFAULT_OPENAI_BASE_URL)
    )
}

case class AzureConfig(
  endpoint: String,
  apiKey: String,
  model: String,
  apiVersion: String
) extends ProviderConfig

object AzureConfig {

  def from(modelName: String, config: ConfigReader): AzureConfig = {
    val endpoint = config
      .get(AZURE_API_BASE)
      .getOrElse(
        throw new IllegalArgumentException("AZURE_API_BASE not set, required when using azure/ model.")
      )
    val apiKey = config
      .get(AZURE_API_KEY)
      .getOrElse(
        throw new IllegalArgumentException("AZURE_API_KEY not set, required when using azure/ model.")
      )
    val apiVersion = config.get(AZURE_API_VERSION).getOrElse(DEFAULT_AZURE_V2025_01_01_PREVIEW)

    AzureConfig(
      endpoint = endpoint,
      apiKey = apiKey,
      model = modelName,
      apiVersion = apiVersion
    )
  }
}

case class AnthropicConfig(
  apiKey: String,
  model: String,
  baseUrl: String
) extends ProviderConfig

object AnthropicConfig {

  def from(modelName: String, config: ConfigReader): AnthropicConfig =
    AnthropicConfig(
      apiKey = config
        .get(ANTHROPIC_API_KEY)
        .getOrElse(
          throw new IllegalArgumentException("ANTHROPIC_API_KEY not set, required when using anthropic/ model.")
        ),
      model = modelName,
      baseUrl = config.getOrElse(ANTHROPIC_BASE_URL, DEFAULT_ANTHROPIC_BASE_URL)
    )
}

case class OllamaConfig(
  model: String,
  baseUrl: String
) extends ProviderConfig

object OllamaConfig {
  def from(modelName: String, config: ConfigReader): OllamaConfig = {
    val baseUrl = config
      .get(OLLAMA_BASE_URL)
      .getOrElse(throw new IllegalArgumentException("OLLAMA_BASE_URL must be set for ollama provider"))
    OllamaConfig(model = modelName, baseUrl = baseUrl)
  }
}
