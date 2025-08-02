package org.llm4s.llmconnect.config

import org.llm4s.config.EnvLoader

sealed trait ProviderConfig {
  def model: String
}

object ProviderConfig {
  def readEnv(key: String): Option[String] =
    EnvLoader.get(key)
}

case class OpenAIConfig(
  apiKey: String,
  model: String = "gpt-4o",
  organization: Option[String] = None,
  baseUrl: String = "https://api.openai.com/v1"
) extends ProviderConfig

object OpenAIConfig {

  /**
   * Create an OpenAIConfig from environment variables
   */
  def fromEnv(modelName: String): OpenAIConfig = {
    val readEnv = ProviderConfig.readEnv _

    OpenAIConfig(
      apiKey = readEnv("OPENAI_API_KEY").getOrElse(
        throw new IllegalArgumentException("OPENAI_API_KEY not set, required when using openai/ model.")
      ),
      model = modelName,
      organization = readEnv("OPENAI_ORGANIZATION"),
      baseUrl = readEnv("OPENAI_BASE_URL").getOrElse("https://api.openai.com/v1")
    )
  }
}

case class AzureConfig(
  endpoint: String,
  apiKey: String,
  model: String,
  apiVersion: String = "2023-12-01-preview"
) extends ProviderConfig

object AzureConfig {

  /**
   * Create an AzureConfig from environment variables
   */
  def fromEnv(modelName: String): AzureConfig = {
    val readEnv = ProviderConfig.readEnv _

    val endpoint = readEnv("AZURE_API_BASE").getOrElse(
      throw new IllegalArgumentException("AZURE_API_BASE not set, required when using azure/ model.")
    )
    val apiKey = readEnv("AZURE_API_KEY").getOrElse(
      throw new IllegalArgumentException("AZURE_API_KEY not set, required when using azure/ model.")
    )
    val apiVersion = readEnv("AZURE_API_VERSION").getOrElse("2023-12-01-preview")

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
  model: String = "claude-3-opus-20240229",
  baseUrl: String = "https://api.anthropic.com"
) extends ProviderConfig

object AnthropicConfig {

  /**
   * Create an AnthropicConfig from environment variables
   */
  def fromEnv(modelName: String): AnthropicConfig = {
    val readEnv = ProviderConfig.readEnv _

    AnthropicConfig(
      apiKey = readEnv("ANTHROPIC_API_KEY").getOrElse(
        throw new IllegalArgumentException("ANTHROPIC_API_KEY not set, required when using anthropic/ model.")
      ),
      model = modelName,
      baseUrl = readEnv("ANTHROPIC_BASE_URL").getOrElse("https://api.anthropic.com")
    )
  }
}
