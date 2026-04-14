package org.llm4s.llmconnect.config

import org.llm4s.model.ModelRegistryService
import org.slf4j.LoggerFactory

class ContextWindowResolver(service: ModelRegistryService):
  import ContextWindowResolver.logger

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
