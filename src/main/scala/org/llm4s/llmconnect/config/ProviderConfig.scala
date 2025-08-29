package org.llm4s.llmconnect.config

import org.llm4s.config.ConfigReader

sealed trait ProviderConfig {
  def model: String
}

case class OpenAIConfig(
  apiKey: String,
  model: String = "gpt-4o",
  organization: Option[String] = None,
  baseUrl: String = "https://api.openai.com/v1"
) extends ProviderConfig

object OpenAIConfig {

  def from(modelName: String, reader: ConfigReader): OpenAIConfig = {
    val read = reader.get _
    OpenAIConfig(
      apiKey = read("OPENAI_API_KEY").getOrElse(
        throw new IllegalArgumentException("OPENAI_API_KEY not set, required when using openai/ model.")
      ),
      model = modelName,
      organization = read("OPENAI_ORGANIZATION"),
      baseUrl = read("OPENAI_BASE_URL").getOrElse("https://api.openai.com/v1")
    )
  }
}

case class AzureConfig(
  endpoint: String,
  apiKey: String,
  model: String,
  apiVersion: String = "V2025_01_01_PREVIEW"
) extends ProviderConfig

object AzureConfig {

  def from(modelName: String, reader: ConfigReader): AzureConfig = {
    val read = reader.get _
    val endpoint = read("AZURE_API_BASE").getOrElse(
      throw new IllegalArgumentException("AZURE_API_BASE not set, required when using azure/ model.")
    )
    val apiKey = read("AZURE_API_KEY").getOrElse(
      throw new IllegalArgumentException("AZURE_API_KEY not set, required when using azure/ model.")
    )
    val apiVersion = read("AZURE_API_VERSION").getOrElse("V2025_01_01_PREVIEW")

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

  def from(modelName: String, reader: ConfigReader): AnthropicConfig = {
    val read = reader.get _
    AnthropicConfig(
      apiKey = read("ANTHROPIC_API_KEY").getOrElse(
        throw new IllegalArgumentException("ANTHROPIC_API_KEY not set, required when using anthropic/ model.")
      ),
      model = modelName,
      baseUrl = read("ANTHROPIC_BASE_URL").getOrElse("https://api.anthropic.com")
    )
  }
}

case class OllamaConfig(
  model: String,
  baseUrl: String
) extends ProviderConfig

object OllamaConfig {
  def from(modelName: String, reader: ConfigReader): OllamaConfig = {
    val baseUrl = reader.get("OLLAMA_BASE_URL").getOrElse("http://localhost:11434")
    OllamaConfig(model = modelName, baseUrl = baseUrl)
  }
}
