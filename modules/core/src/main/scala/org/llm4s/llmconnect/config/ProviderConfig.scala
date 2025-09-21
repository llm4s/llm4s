package org.llm4s.llmconnect.config

import org.llm4s.config.{ ConfigKeys, ConfigReader }
import ConfigKeys._
import org.llm4s.config.DefaultConfig._
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

sealed trait ProviderConfig {
  def model: String
  def contextWindow: Int
  def reserveCompletion: Int
}

case class OpenAIConfig(
  apiKey: String,
  model: String,
  organization: Option[String],
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig

object OpenAIConfig {

  def apply(modelName: String, config: ConfigReader): Result[OpenAIConfig] = {
    val (cw, rc) = getContextWindowForModel(modelName)
    for {
      apiKey <- config.require(OPENAI_API_KEY)
      base = config.getOrElse(OPENAI_BASE_URL, DEFAULT_OPENAI_BASE_URL)
      org  = config.get(OPENAI_ORG)
    } yield OpenAIConfig(
      apiKey = apiKey,
      model = modelName,
      organization = org,
      baseUrl = base,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }

  private def getContextWindowForModel(modelName: String): (Int, Int) = {
    val standardReserve = 4096 // 4K tokens reserved for completion

    modelName match {
      // GPT-4 family - 128K context window
      case name if name.contains("gpt-4o")      => (128000, standardReserve)
      case name if name.contains("gpt-4-turbo") => (128000, standardReserve)
      case name if name.contains("gpt-4")       => (8192, standardReserve) // Original GPT-4 was 8K
      // GPT-3.5 family - 16K context window
      case name if name.contains("gpt-3.5-turbo") => (16384, standardReserve)
      // o1 family - 128K context window
      case name if name.contains("o1-") => (128000, standardReserve)
      // Default fallback
      case _ => (8192, standardReserve)
    }
  }
}

case class AzureConfig(
  endpoint: String,
  apiKey: String,
  model: String,
  apiVersion: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig

object AzureConfig {

  def apply(modelName: String, config: ConfigReader): Result[AzureConfig] = {
    val (cw, rc) = getContextWindowForModel(modelName)
    for {
      endpoint <- config.require(AZURE_API_BASE)
      apiKey   <- config.require(AZURE_API_KEY)
      apiVersion = config.get(AZURE_API_VERSION).getOrElse(DEFAULT_AZURE_V2025_01_01_PREVIEW)
    } yield AzureConfig(
      endpoint = endpoint,
      apiKey = apiKey,
      model = modelName,
      apiVersion = apiVersion,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
  // Azure mirrors OpenAI models; reuse similar heuristics
  private def getContextWindowForModel(modelName: String): (Int, Int) = {
    val standardReserve = 4096
    modelName match {
      case name if name.contains("gpt-4o")        => (128000, standardReserve)
      case name if name.contains("gpt-4-turbo")   => (128000, standardReserve)
      case name if name.contains("gpt-4")         => (8192, standardReserve)
      case name if name.contains("gpt-3.5-turbo") => (16384, standardReserve)
      case name if name.contains("o1-")           => (128000, standardReserve)
      case _                                      => (8192, standardReserve)
    }
  }
}

case class AnthropicConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig

object AnthropicConfig {

  def apply(modelName: String, config: ConfigReader): Result[AnthropicConfig] = {
    val (cw, rc) = getContextWindowForModel(modelName)
    for {
      apiKey <- config.require(ANTHROPIC_API_KEY)
      base = config.getOrElse(ANTHROPIC_BASE_URL, DEFAULT_ANTHROPIC_BASE_URL)
    } yield AnthropicConfig(
      apiKey = apiKey,
      model = modelName,
      baseUrl = base,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
  private def getContextWindowForModel(modelName: String): (Int, Int) = {
    val standardReserve = 4096 // 4K tokens reserved for completion

    modelName match {
      // Claude-3 family - 200K context window
      case name if name.contains("claude-3")   => (200000, standardReserve)
      case name if name.contains("claude-3.5") => (200000, standardReserve)
      // Claude Instant - 100K context window
      case name if name.contains("claude-instant") => (100000, standardReserve)
      // Default fallback for Claude models
      case _ => (200000, standardReserve)
    }
  }
}

case class OllamaConfig(
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig

object OllamaConfig {

  def apply(modelName: String, config: ConfigReader): Result[OllamaConfig] = {
    val (cw, rc) = getContextWindowForModel(modelName)
    for {
      baseUrl <- config.require(OLLAMA_BASE_URL)
    } yield OllamaConfig(model = modelName, baseUrl = baseUrl, contextWindow = cw, reserveCompletion = rc)
  }
  private def getContextWindowForModel(modelName: String): (Int, Int) = {
    val standardReserve = 4096 // 4K tokens reserved for completion

    // Ollama model context windows vary, use reasonable defaults
    modelName match {
      case name if name.contains("llama2")    => (4096, standardReserve)
      case name if name.contains("llama3")    => (8192, standardReserve)
      case name if name.contains("codellama") => (16384, standardReserve)
      case name if name.contains("mistral")   => (32768, standardReserve)
      // Default fallback for unknown Ollama models
      case _ => (8192, standardReserve)
    }
  }
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
