package org.llm4s.llmconnect.config

import org.llm4s.model.ModelRegistry
import org.slf4j.LoggerFactory

/**
 * Centralized resolver for model context window and reserve completion tokens.
 *
 * Replaces duplicated getContextWindowForModel logic across provider configs.
 * Performs registry lookup, then applies provider-specific fallbacks when not found.
 */
object ContextWindowResolver {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Resolve (contextWindow, reserveCompletion) for a model.
   *
   * @param lookupProviders       Providers to try in order (e.g., Seq("azure", "openai") for Azure)
   * @param modelName             Model identifier
   * @param defaultContextWindow  Default when registry hit but maxInputTokens missing
   * @param defaultReserve        Default when registry hit but maxOutputTokens missing
   * @param fallbackResolver      Function to compute fallback when registry miss
   * @param logPrefix             Optional prefix for debug log (e.g., "Azure " for Azure provider)
   * @return (contextWindow, reserveCompletion)
   */
  def resolve(
    lookupProviders: Seq[String],
    modelName: String,
    defaultContextWindow: Int,
    defaultReserve: Int,
    fallbackResolver: String => (Int, Int),
    logPrefix: String = ""
  ): (Int, Int) = {
    val registryResult = lookupProviders.view
      .flatMap(p => ModelRegistry.lookup(p, modelName).toOption)
      .headOption
      .orElse(ModelRegistry.lookup(modelName).toOption)

    registryResult match {
      case Some(metadata) =>
        val contextWindow = metadata.maxInputTokens.getOrElse(defaultContextWindow)
        val reserve       = metadata.maxOutputTokens.getOrElse(defaultReserve)
        logger.debug(
          s"Using ModelRegistry metadata for ${logPrefix}$modelName: context=$contextWindow, reserve=$reserve"
        )
        (contextWindow, reserve)
      case None =>
        logger.debug(s"Model $modelName not found in registry, using fallback values")
        fallbackResolver(modelName)
    }
  }
}
