package org.llm4s.llmconnect.config

import org.slf4j.LoggerFactory
import org.llm4s.util.Redaction

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
) extends ProviderConfig {
  override def toString: String =
    s"OpenAIConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, organization=$organization, baseUrl=$baseUrl, " +
      s"contextWindow=$contextWindow, reserveCompletion=$reserveCompletion)"
}

object OpenAIConfig {
  private val standardReserve = 4096

  private def openAIFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("gpt-4o")        => (128000, standardReserve)
      case name if name.contains("gpt-4-turbo")   => (128000, standardReserve)
      case name if name.contains("gpt-4")         => (8192, standardReserve)
      case name if name.contains("gpt-3.5-turbo") => (16384, standardReserve)
      case name if name.contains("o1-")           => (128000, standardReserve)
      case _                                      => (8192, standardReserve)
    }

  def fromValues(
    modelName: String,
    apiKey: String,
    organization: Option[String],
    baseUrl: String
  ): OpenAIConfig = {
    require(apiKey.trim.nonEmpty, "OpenAI apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "OpenAI baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("openai"),
      modelName = modelName,
      defaultContextWindow = 8192,
      defaultReserve = standardReserve,
      fallbackResolver = openAIFallback
    )
    OpenAIConfig(
      apiKey = apiKey,
      model = modelName,
      organization = organization,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}

case class AzureConfig(
  endpoint: String,
  apiKey: String,
  model: String,
  apiVersion: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig {
  override def toString: String =
    s"AzureConfig(endpoint=$endpoint, apiKey=${Redaction.secret(apiKey)}, model=$model, apiVersion=$apiVersion, " +
      s"contextWindow=$contextWindow, reserveCompletion=$reserveCompletion)"
}

object AzureConfig {
  private val standardReserve = 4096

  private def azureFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("gpt-4o")        => (128000, standardReserve)
      case name if name.contains("gpt-4-turbo")   => (128000, standardReserve)
      case name if name.contains("gpt-4")         => (8192, standardReserve)
      case name if name.contains("gpt-3.5-turbo") => (16384, standardReserve)
      case name if name.contains("o1-")           => (128000, standardReserve)
      case _                                      => (8192, standardReserve)
    }

  def fromValues(
    modelName: String,
    endpoint: String,
    apiKey: String,
    apiVersion: String
  ): AzureConfig = {
    require(endpoint.trim.nonEmpty, "Azure endpoint must be non-empty")
    require(apiKey.trim.nonEmpty, "Azure apiKey must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("azure", "openai"),
      modelName = modelName,
      defaultContextWindow = 8192,
      defaultReserve = standardReserve,
      fallbackResolver = azureFallback,
      logPrefix = "Azure "
    )
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
) extends ProviderConfig {
  override def toString: String =
    s"AnthropicConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"
}

object AnthropicConfig {
  private val standardReserve = 4096

  private def anthropicFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("claude-3")       => (200000, standardReserve)
      case name if name.contains("claude-3.5")     => (200000, standardReserve)
      case name if name.contains("claude-instant") => (100000, standardReserve)
      case _                                       => (200000, standardReserve)
    }

  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  ): AnthropicConfig = {
    require(apiKey.trim.nonEmpty, "Anthropic apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "Anthropic baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("anthropic"),
      modelName = modelName,
      defaultContextWindow = 200000,
      defaultReserve = standardReserve,
      fallbackResolver = anthropicFallback
    )
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
  private val standardReserve = 4096

  private def ollamaFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("llama2")    => (4096, standardReserve)
      case name if name.contains("llama3")    => (8192, standardReserve)
      case name if name.contains("codellama") => (16384, standardReserve)
      case name if name.contains("mistral")   => (32768, standardReserve)
      case _                                  => (8192, standardReserve)
    }

  def fromValues(
    modelName: String,
    baseUrl: String
  ): OllamaConfig = {
    require(baseUrl.trim.nonEmpty, "Ollama baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("ollama"),
      modelName = modelName,
      defaultContextWindow = 8192,
      defaultReserve = standardReserve,
      fallbackResolver = ollamaFallback
    )
    OllamaConfig(
      model = modelName,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}

case class ZaiConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig {
  override def toString: String =
    s"ZaiConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"
}

object ZaiConfig {
  private val standardReserve = 4096

  val DEFAULT_BASE_URL: String = "https://api.z.ai/api/paas/v4"

  private def zaiFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("GLM-4.7") => (128000, standardReserve)
      case name if name.contains("GLM-4.5") => (32000, standardReserve)
      case _                                => (128000, standardReserve)
    }

  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  ): ZaiConfig = {
    require(apiKey.trim.nonEmpty, "Zai apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "Zai baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("zai"),
      modelName = modelName,
      defaultContextWindow = 128000,
      defaultReserve = standardReserve,
      fallbackResolver = zaiFallback
    )
    ZaiConfig(
      apiKey = apiKey,
      model = modelName,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}

case class GeminiConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig {
  override def toString: String =
    s"GeminiConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"
}

object GeminiConfig {
  private val standardReserve = 8192

  private def geminiFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("gemini-2")     => (1048576, standardReserve)
      case name if name.contains("gemini-1.5")   => (1048576, standardReserve)
      case name if name.contains("gemini-1.0")   => (32768, standardReserve)
      case name if name.contains("gemini-pro")   => (1048576, standardReserve)
      case name if name.contains("gemini-flash") => (1048576, standardReserve)
      case _                                     => (1048576, standardReserve)
    }

  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  ): GeminiConfig = {
    require(apiKey.trim.nonEmpty, "Gemini apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "Gemini baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("gemini", "google"),
      modelName = modelName,
      defaultContextWindow = 1048576,
      defaultReserve = standardReserve,
      fallbackResolver = geminiFallback
    )
    GeminiConfig(
      apiKey = apiKey,
      model = modelName,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}

case class DeepSeekConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig {
  override def toString: String =
    s"DeepSeekConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"
}

object DeepSeekConfig {
  private val logger          = LoggerFactory.getLogger(getClass)
  private val standardReserve = 8192

  val DEFAULT_BASE_URL: String = "https://api.deepseek.com"

  private def deepSeekFallback(modelName: String): (Int, Int) =
    // Explicit allowlist based on official DeepSeek API (as of Feb 2026)
    // Source: https://api-docs.deepseek.com/quick_start/pricing
    modelName.toLowerCase match {
      case "deepseek-chat" | "deepseek/deepseek-chat" | "deepseek-reasoner" | "deepseek/deepseek-reasoner" =>
        (128000, standardReserve)
      case "deepseek-chat-r1" | "deepseek/deepseek-chat-r1" | "deepseek-r1-distill" | "deepseek/deepseek-r1-distill" |
          "deepseek-coder" | "deepseek/deepseek-coder" | "deepseek-v3" | "deepseek/deepseek-v3" =>
        logger.warn(s"Legacy/variant model $modelName - may not be available via official API")
        (128000, standardReserve)
      case _ =>
        logger.warn(s"Unknown DeepSeek model: $modelName, using conservative 128K fallback")
        (128000, standardReserve)
    }

  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  ): DeepSeekConfig = {
    require(apiKey.trim.nonEmpty, "DeepSeek apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "DeepSeek baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("deepseek"),
      modelName = modelName,
      defaultContextWindow = 64000,
      defaultReserve = standardReserve,
      fallbackResolver = deepSeekFallback
    )
    DeepSeekConfig(
      apiKey = apiKey,
      model = modelName,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}

case class CohereConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig {
  override def toString: String =
    s"CohereConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"
}

object CohereConfig {
  private val DefaultContextWindow     = 128000
  private val DefaultReserveCompletion = 4096

  val DEFAULT_BASE_URL: String = "https://api.cohere.com"

  private val cohereFallback: String => (Int, Int) = _ => (DefaultContextWindow, DefaultReserveCompletion)

  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  ): CohereConfig = {
    require(apiKey.trim.nonEmpty, "Cohere apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "Cohere baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("cohere"),
      modelName = modelName,
      defaultContextWindow = DefaultContextWindow,
      defaultReserve = DefaultReserveCompletion,
      fallbackResolver = cohereFallback
    )
    CohereConfig(
      apiKey = apiKey,
      model = modelName,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}
