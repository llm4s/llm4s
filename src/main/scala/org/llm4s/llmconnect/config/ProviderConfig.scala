package org.llm4s.llmconnect.config

import org.llm4s.config.{ ConfigKeys, ConfigReader }
import ConfigKeys._
import org.llm4s.config.DefaultConfig._
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

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

  def apply(modelName: String, config: ConfigReader): Result[OpenAIConfig] =
    for {
      apiKey <- config.require(OPENAI_API_KEY)
      base = config.getOrElse(OPENAI_BASE_URL, DEFAULT_OPENAI_BASE_URL)
      org  = config.get(OPENAI_ORG)
    } yield OpenAIConfig(apiKey = apiKey, model = modelName, organization = org, baseUrl = base)
}

case class AzureConfig(
  endpoint: String,
  apiKey: String,
  model: String,
  apiVersion: String
) extends ProviderConfig

object AzureConfig {

  def apply(modelName: String, config: ConfigReader): Result[AzureConfig] =
    for {
      endpoint <- config.require(AZURE_API_BASE)
      apiKey   <- config.require(AZURE_API_KEY)
      apiVersion = config.get(AZURE_API_VERSION).getOrElse(DEFAULT_AZURE_V2025_01_01_PREVIEW)
    } yield AzureConfig(endpoint = endpoint, apiKey = apiKey, model = modelName, apiVersion = apiVersion)
}

case class AnthropicConfig(
  apiKey: String,
  model: String,
  baseUrl: String
) extends ProviderConfig

object AnthropicConfig {

  def apply(modelName: String, config: ConfigReader): Result[AnthropicConfig] =
    for {
      apiKey <- config.require(ANTHROPIC_API_KEY)
      base = config.getOrElse(ANTHROPIC_BASE_URL, DEFAULT_ANTHROPIC_BASE_URL)
    } yield AnthropicConfig(apiKey = apiKey, model = modelName, baseUrl = base)

}

case class OllamaConfig(
  model: String,
  baseUrl: String
) extends ProviderConfig

object OllamaConfig {

  def apply(modelName: String, config: ConfigReader): Result[OllamaConfig] =
    for {
      baseUrl <- config.require(OLLAMA_BASE_URL)
    } yield OllamaConfig(model = modelName, baseUrl = baseUrl)
}

/**
 * Central loader for model spec to concrete provider config.
 * Keeps logic minimal and cross-version compatible.
 */
object ProviderConfigLoader {

  /** Parses a model spec like "openai/gpt-4o" and loads provider config */
  def apply(modelSpec: String, config: ConfigReader): Result[ProviderConfig] = {
    val normalized = Option(modelSpec).map(_.trim).getOrElse("")
    if (normalized.isEmpty)
      Left(ConfigurationError(s"Missing model spec: set ${ConfigKeys.LLM_MODEL}"))
    else {
      val parts = normalized.split("/", 2)
      val (prefix, modelName) =
        if (parts.length == 2) (parts(0).toLowerCase, parts(1))
        else (inferProviderFromBaseUrl(config), parts(0))

      prefix match {
        case "openai"     => OpenAIConfig(modelName, config)
        case "openrouter" => OpenAIConfig(modelName, config)
        case "azure"      => AzureConfig(modelName, config)
        case "anthropic"  => AnthropicConfig(modelName, config)
        case "ollama"     => OllamaConfig(modelName, config)
        case other if other.nonEmpty =>
          Left(ConfigurationError(s"Unknown provider prefix: $other in '$modelSpec'"))
        case _ =>
          Left(ConfigurationError(s"Unable to infer provider for model '$modelSpec'"))
      }
    }
  }

  private def inferProviderFromBaseUrl(config: ConfigReader): String = {
    val base = config.getOrElse(OPENAI_BASE_URL, DEFAULT_OPENAI_BASE_URL)
    if (base.contains("openrouter.ai")) "openrouter" else "openai"
  }
}
