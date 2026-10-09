package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.model.ModelRegistryService
import org.slf4j.LoggerFactory

/** Resolves context window and output-reserve token counts for a given model using the model registry. */
@Stable
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

  /**
   * The context window (input limit) the registry holds for `modelName` among `provider`'s own entries, and
   * nothing else.
   *
   * [[resolve]] is deliberately forgiving: the registry's own lookup falls back to a substring match over every
   * provider's entries, and to the bare model name, so a name it does not know under `provider` can still pick up
   * another provider's entry. That suits a dedicated client, whose model names are its vendor's own. It does not
   * suit an endpoint that serves arbitrary names, where another provider's window is a wrong answer and no answer
   * is better. This method is the strict form: an entry counts only when its provider is `provider` and its id is
   * `provider/modelName` or `modelName` (case-insensitively); a partial name, another provider's entry for the same
   * name, and an entry with no input limit all give `None`.
   *
   * @param provider  the registry provider, e.g. `groq` or `together_ai`
   * @param modelName the model id as the provider's API names it, without the `provider/` prefix
   * @return the entry's input limit when it is a positive number, otherwise `None`
   */
  def strictContextWindow(provider: String, modelName: String): Option[Int] =
    service
      .listByProvider(provider)
      .toOption
      .flatMap(
        _.find(entry =>
          entry.modelId.equalsIgnoreCase(s"$provider/$modelName") || entry.modelId.equalsIgnoreCase(modelName)
        )
      )
      .flatMap(_.maxInputTokens)
      .filter(_ > 0)

object ContextWindowResolver:
  private val logger = LoggerFactory.getLogger(classOf[ContextWindowResolver])
