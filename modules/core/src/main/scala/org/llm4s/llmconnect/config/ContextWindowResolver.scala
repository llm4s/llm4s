package org.llm4s.llmconnect.config

import org.llm4s.model.ModelRegistryService
import org.slf4j.LoggerFactory

/** Resolves context window and output-reserve token counts for a given model using the model registry. */
class ContextWindowResolver(service: ModelRegistryService):
  import ContextWindowResolver.logger

  /**
   * Looks up context-window size and output-token reserve for a model, falling back to provided defaults.
   *
   * @param lookupProviders     ordered list of provider names to try when querying the registry
   * @param modelName           name of the model whose limits should be resolved
   * @param defaultContextWindow fallback maximum context-window size in tokens
   * @param defaultReserve      fallback maximum output-token reserve
   * @param fallbackResolver    function returning (contextWindow, reserve) when the registry has no entry
   * @param logPrefix           optional string prepended to log messages for disambiguation
   * @return pair of (contextWindow, reserveTokens) for the resolved model
   */
  def resolve(
    lookupProviders: Seq[String],
    modelName: String,
    defaultContextWindow: Int,
    defaultReserve: Int,
    fallbackResolver: String => (Int, Int),
    logPrefix: String = ""
  ): (Int, Int) =
    val registryResult =
      lookupProviders.view
        .flatMap(p => service.lookup(p, modelName).toOption)
        .headOption
        .orElse(service.lookup(modelName).toOption)

    registryResult match
      case Some(metadata) =>
        val contextWindow = metadata.maxInputTokens.getOrElse(defaultContextWindow)
        val reserve       = metadata.maxOutputTokens.getOrElse(defaultReserve)
        logger.debug(
          s"Using model registry metadata for ${logPrefix}$modelName: context=$contextWindow, reserve=$reserve"
        )
        (contextWindow, reserve)
      case None =>
        logger.debug(s"Model $modelName not found in registry, using fallback values")
        fallbackResolver(modelName)

object ContextWindowResolver:
  private val logger = LoggerFactory.getLogger(classOf[ContextWindowResolver])
