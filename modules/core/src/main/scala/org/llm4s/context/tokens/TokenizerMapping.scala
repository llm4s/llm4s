package org.llm4s.context.tokens

import org.llm4s.annotation.Stable
import org.llm4s.identity.TokenizerId
import org.slf4j.LoggerFactory

/**
 * Maps LLM model names to appropriate tokenizers for accurate token counting.
 *
 * Different LLM providers use different tokenization schemes, and even within
 * a provider, newer models may use updated tokenizers. This object provides
 * the mapping logic to select the correct tokenizer for a given model.
 *
 * ==Supported Providers==
 *
 * {{{
 * | Provider   | Model Pattern           | Tokenizer      | Accuracy |
 * |------------|-------------------------|----------------|----------|
 * | OpenAI     | gpt-4o, o1-*            | o200k_base     | Exact    |
 * | OpenAI     | gpt-4, gpt-3.5          | cl100k_base    | Exact    |
 * | OpenAI     | gpt-3 (legacy)          | r50k_base      | Exact    |
 * | Azure      | (same as OpenAI)        | (inherited)    | Exact    |
 * | Anthropic  | claude-*                | cl100k_base    | ~75%     |
 * | Ollama     | *                       | cl100k_base    | ~80%     |
 * | Unknown    | *                       | cl100k_base    | Unknown  |
 * }}}
 *
 * ==Model Name Formats==
 *
 * The mapper accepts various model name formats:
 *  - Plain: `gpt-4o`, `claude-3-sonnet`
 *  - Provider-prefixed: `openai/gpt-4o`, `anthropic/claude-3-sonnet`
 *  - Azure: `azure/my-gpt4o-deployment`
 *  - Ollama: `ollama/llama2`
 *
 * ==Accuracy Considerations==
 *
 * For non-OpenAI models, token counts are '''approximations'''. Claude uses
 * a proprietary tokenizer that may differ 20-30% from cl100k_base. Always
 * check [[TokenizerAccuracy]] to understand the expected accuracy.
 *
 * @see [[ConversationTokenCounter.forModel]] for the recommended entry point
 * @see [[TokenizerAccuracy]] for accuracy information
 */
@Stable
object TokenizerMapping {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Get the appropriate tokenizer ID for a given model name.
   *
   * @param modelName The model identifier (e.g., "gpt-4o", "openai/gpt-4o")
   * @return The recommended tokenizer ID for this model
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
   * Get accuracy information for a model's tokenizer mapping.
   *
   * This helps callers understand how reliable the token counts will be.
   * For OpenAI models, counts are exact. For other providers, they are
   * approximations that may differ from actual usage.
   *
   * @param modelName The model identifier
   * @return Accuracy information including description and estimated accuracy
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
   * Check if the tokenizer mapping is exact or approximate for a model.
   *
   * Exact mappings are available for OpenAI and Azure OpenAI models.
   * Other providers use approximations.
   *
   * @param modelName The model identifier
   * @return True if token counts will be exact, false if approximate
   */
  def isExactMapping(modelName: String): Boolean =
    getAccuracyInfo(modelName).isExact
}

/**
 * Represents the accuracy of tokenizer mapping for a model.
 *
 * This sealed trait hierarchy captures three levels of accuracy:
 *
 *  - '''Exact''': Native tokenizer available, counts are precise
 *  - '''Approximate''': Using a similar tokenizer, counts may differ
 *  - '''Unknown''': Unknown model, using fallback tokenizer
 *
 * @see [[TokenizerMapping.getAccuracyInfo]]
 */
@Stable
sealed trait TokenizerAccuracy {

  /** True if token counts will match actual API usage. */
  def isExact: Boolean

  /** Human-readable description of the accuracy level. */
  def description: String
}

/**
 * Accuracy level variants for tokenizer mappings.
 */
object TokenizerAccuracy {

  /**
   * Exact tokenizer available (OpenAI, Azure).
   * Token counts will match actual API usage.
   */
  case class Exact(description: String) extends TokenizerAccuracy {
    val isExact = true
  }

  /**
   * Approximate tokenizer (Anthropic, Ollama).
   * Token counts may differ by the specified accuracy percentage.
   *
   * @param accuracy Expected accuracy as a decimal (0.75 = 75% accurate)
   */
  case class Approximate(description: String, accuracy: Double) extends TokenizerAccuracy {
    val isExact = false
  }

  /**
   * Unknown model, using fallback tokenizer.
   * Token counts are best-effort estimates.
   */
  case class Unknown(description: String) extends TokenizerAccuracy {
    val isExact = false
  }
}
