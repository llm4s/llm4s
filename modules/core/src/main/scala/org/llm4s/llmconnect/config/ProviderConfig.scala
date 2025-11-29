package org.llm4s.llmconnect.config

import org.llm4s.model.ModelRegistry
import org.slf4j.LoggerFactory

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
  private val logger = LoggerFactory.getLogger(getClass)

  def fromValues(
    modelName: String,
    apiKey: String,
    organization: Option[String],
    baseUrl: String
  ): OpenAIConfig = {
    require(apiKey.trim.nonEmpty, "OpenAI apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "OpenAI baseUrl must be non-empty")
    val (cw, rc) = getContextWindowForModel(modelName)
    OpenAIConfig(
      apiKey = apiKey,
      model = modelName,
      organization = organization,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }

  private def getContextWindowForModel(modelName: String): (Int, Int) = {
    val standardReserve = 4096 // 4K tokens reserved for completion

    val registryResult =
      ModelRegistry
        .lookup("openai", modelName)
        .toOption
        .orElse(ModelRegistry.lookup(modelName).toOption)

    registryResult match {
      case Some(metadata) =>
        val contextWindow = metadata.maxInputTokens.getOrElse(8192)
        val reserve       = metadata.maxOutputTokens.getOrElse(standardReserve)
        logger.debug(s"Using ModelRegistry metadata for $modelName: context=$contextWindow, reserve=$reserve")
        (contextWindow, reserve)
      case None =>
        logger.debug(s"Model $modelName not found in registry, using fallback values")
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
  private val logger = LoggerFactory.getLogger(getClass)

  private def getContextWindowForModel(modelName: String): (Int, Int) = {
    val standardReserve = 4096

    val registryResult = ModelRegistry
      .lookup("azure", modelName)
      .toOption
      .orElse(ModelRegistry.lookup("openai", modelName).toOption)
      .orElse(ModelRegistry.lookup(modelName).toOption)

    registryResult match {
      case Some(metadata) =>
        val contextWindow = metadata.maxInputTokens.getOrElse(8192)
        val reserve       = metadata.maxOutputTokens.getOrElse(standardReserve)
        logger.debug(s"Using ModelRegistry metadata for Azure $modelName: context=$contextWindow, reserve=$reserve")
        (contextWindow, reserve)
      case None =>
        logger.debug(s"Model $modelName not found in registry, using fallback values")
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

  def fromValues(
    modelName: String,
    endpoint: String,
    apiKey: String,
    apiVersion: String
  ): AzureConfig = {
    require(endpoint.trim.nonEmpty, "Azure endpoint must be non-empty")
    require(apiKey.trim.nonEmpty, "Azure apiKey must be non-empty")
    val (cw, rc) = getContextWindowForModel(modelName)
    AzureConfig(
      endpoint = endpoint,
      apiKey = apiKey,
      model = modelName,
      apiVersion = apiVersion,
      contextWindow = cw,
      reserveCompletion = rc
    )
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
  private val logger = LoggerFactory.getLogger(getClass)

  private def getContextWindowForModel(modelName: String): (Int, Int) = {
    val standardReserve = 4096 // 4K tokens reserved for completion

    val registryResult =
      ModelRegistry
        .lookup("anthropic", modelName)
        .toOption
        .orElse(ModelRegistry.lookup(modelName).toOption)

    registryResult match {
      case Some(metadata) =>
        val contextWindow = metadata.maxInputTokens.getOrElse(200000)
        val reserve       = metadata.maxOutputTokens.getOrElse(standardReserve)
        logger.debug(s"Using ModelRegistry metadata for $modelName: context=$contextWindow, reserve=$reserve")
        (contextWindow, reserve)
      case None =>
        logger.debug(s"Model $modelName not found in registry, using fallback values")
        modelName match {
          case name if name.contains("claude-3")       => (200000, standardReserve)
          case name if name.contains("claude-3.5")     => (200000, standardReserve)
          case name if name.contains("claude-instant") => (100000, standardReserve)
          case _                                       => (200000, standardReserve)
        }
    }
  }

  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  ): AnthropicConfig = {
    require(apiKey.trim.nonEmpty, "Anthropic apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "Anthropic baseUrl must be non-empty")
    val (cw, rc) = getContextWindowForModel(modelName)
    AnthropicConfig(
      apiKey = apiKey,
      model = modelName,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}

case class OllamaConfig(
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig

object OllamaConfig {
  private val logger = LoggerFactory.getLogger(getClass)

  private def getContextWindowForModel(modelName: String): (Int, Int) = {
    val standardReserve = 4096 // 4K tokens reserved for completion

    val registryResult =
      ModelRegistry
        .lookup("ollama", modelName)
        .toOption
        .orElse(ModelRegistry.lookup(modelName).toOption)

    registryResult match {
      case Some(metadata) =>
        val contextWindow = metadata.maxInputTokens.getOrElse(8192)
        val reserve       = metadata.maxOutputTokens.getOrElse(standardReserve)
        logger.debug(s"Using ModelRegistry metadata for $modelName: context=$contextWindow, reserve=$reserve")
        (contextWindow, reserve)
      case None =>
        logger.debug(s"Model $modelName not found in registry, using fallback values")
        modelName match {
          case name if name.contains("llama2")    => (4096, standardReserve)
          case name if name.contains("llama3")    => (8192, standardReserve)
          case name if name.contains("codellama") => (16384, standardReserve)
          case name if name.contains("mistral")   => (32768, standardReserve)
          case _                                  => (8192, standardReserve)
        }
    }
  }

  def fromValues(
    modelName: String,
    baseUrl: String
  ): OllamaConfig = {
    require(baseUrl.trim.nonEmpty, "Ollama baseUrl must be non-empty")
    val (cw, rc) = getContextWindowForModel(modelName)
    OllamaConfig(
      model = modelName,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}
