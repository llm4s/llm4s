package org.llm4s.context.tokens

import org.llm4s.identity.TokenizerId
import org.slf4j.LoggerFactory

/**
 * Maps LLM model names to appropriate tokenizers for accurate token counting.
 * Supports OpenAI, Anthropic, and Azure OpenAI models.
 */
object TokenizerMapping {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Get the appropriate tokenizer for a given model name
   */
  def getTokenizerId(modelName: String): TokenizerId = {
    val tokenizerId = modelName.toLowerCase match {
      case name if isOpenAIGPT4o(name)  => TokenizerId.O200K_BASE
      case name if isOpenAIGPT4(name)   => TokenizerId.CL100K_BASE
      case name if isOpenAIGPT3_5(name) => TokenizerId.CL100K_BASE
      case name if isOpenAIGPT3(name)   => TokenizerId.R50K_BASE
      case name if isAnthropic(name)    => TokenizerId.CL100K_BASE
      case name if isAzureOpenAI(name)  => getAzureTokenizerId(name)
      case name if isOllama(name)       => TokenizerId.CL100K_BASE
      case _                            => getDefaultTokenizerId(modelName)
    }

    logger.debug(s"Mapped model '$modelName' to tokenizer '$tokenizerId'")
    tokenizerId
  }

  private def isOpenAIGPT4o(modelName: String): Boolean =
    modelName.contains("gpt-4o") || modelName.contains("o1-")

  private def isOpenAIGPT4(modelName: String): Boolean =
    modelName.contains("gpt-4") && !modelName.contains("gpt-4o")

  private def isOpenAIGPT3_5(modelName: String): Boolean =
    modelName.contains("gpt-3.5")

  private def isOpenAIGPT3(modelName: String): Boolean =
    modelName.contains("gpt-3") && !modelName.contains("gpt-3.5")

  private def isAnthropic(modelName: String): Boolean =
    modelName.startsWith("anthropic/") || modelName.contains("claude")

  private def isAzureOpenAI(modelName: String): Boolean =
    modelName.startsWith("azure/")

  private def isOllama(modelName: String): Boolean =
    modelName.startsWith("ollama/")

  private def getAzureTokenizerId(modelName: String): TokenizerId = {
    // Azure uses same tokenizers as OpenAI, extract model from deployment name
    val deploymentModel = modelName.toLowerCase
    if (deploymentModel.contains("gpt-4o")) TokenizerId.O200K_BASE
    else if (deploymentModel.contains("gpt-4")) TokenizerId.CL100K_BASE
    else if (deploymentModel.contains("gpt-3")) TokenizerId.CL100K_BASE
    else TokenizerId.CL100K_BASE // Default for Azure
  }

  private def getDefaultTokenizerId(modelName: String): TokenizerId = {
    logger.warn(s"Unknown model '$modelName', using cl100k_base as fallback")
    TokenizerId.CL100K_BASE
  }

  /**
   * Get accuracy information for a model's tokenizer mapping
   */
  def getAccuracyInfo(modelName: String): TokenizerAccuracy =
    modelName.toLowerCase match {
      case name if isOpenAIGPT4o(name) || isOpenAIGPT4(name) || isOpenAIGPT3_5(name) =>
        TokenizerAccuracy.Exact("Native OpenAI tokenizer")

      case name if isAzureOpenAI(name) =>
        TokenizerAccuracy.Exact("Azure uses OpenAI tokenizers")

      case name if isAnthropic(name) =>
        TokenizerAccuracy.Approximate(
          "Claude uses proprietary tokenizer. cl100k_base approximation may be 20-30% off.",
          accuracy = 0.75
        )

      case name if isOllama(name) =>
        TokenizerAccuracy.Approximate(
          "Ollama models use various tokenizers. cl100k_base approximation.",
          accuracy = 0.80
        )

      case _ =>
        TokenizerAccuracy.Unknown("Unknown model, using cl100k_base fallback")
    }

  /**
   * Check if the tokenizer mapping is exact or approximate for a model
   */
  def isExactMapping(modelName: String): Boolean =
    getAccuracyInfo(modelName).isExact
}

/**
 * Represents the accuracy of tokenizer mapping for a model
 */
sealed trait TokenizerAccuracy {
  def isExact: Boolean
  def description: String
}

object TokenizerAccuracy {
  case class Exact(description: String) extends TokenizerAccuracy {
    val isExact = true
  }

  case class Approximate(description: String, accuracy: Double) extends TokenizerAccuracy {
    val isExact = false
  }

  case class Unknown(description: String) extends TokenizerAccuracy {
    val isExact = false
  }
}
