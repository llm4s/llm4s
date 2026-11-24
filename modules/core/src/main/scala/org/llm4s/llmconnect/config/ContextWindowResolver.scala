package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.model.ModelRegistryService
import org.slf4j.LoggerFactory

/**
 * A model's token limits, as a provider config carries them.
 *
 * @param contextWindow     the tokens a prompt and its completion may take together
 * @param reserveCompletion the tokens held back from the prompt for the completion
 *
 * Build one with [[ContextLimits.apply]] and change it with the `with*` setters; the constructor and `copy` are
 * private, so a further limit never breaks callers.
 */
@Stable
final case class ContextLimits private (contextWindow: Int, reserveCompletion: Int):

  /** Sets the tokens a prompt and its completion may take together. */
  def withContextWindow(contextWindow: Int): ContextLimits = copy(contextWindow = contextWindow)

  /** Sets the tokens held back from the prompt for the completion. */
  def withReserveCompletion(reserveCompletion: Int): ContextLimits = copy(reserveCompletion = reserveCompletion)

object ContextLimits:

  /** Creates the limits from the context window and the completion reserve. */
  def apply(contextWindow: Int, reserveCompletion: Int): ContextLimits =
    new ContextLimits(contextWindow, reserveCompletion)

/** Resolves context window and output-reserve token counts for a given model using the model registry. */
@Stable
final class ContextWindowResolver(service: ModelRegistryService):
  import ContextWindowResolver.logger

  /**
   * Looks up context-window size and output-token reserve for a model, falling back to provided defaults.
   *
   * @param lookupProviders     ordered list of provider names to try when querying the registry
   * @param modelName           name of the model whose limits should be resolved
   * @param defaultContextWindow the context window used when the registry knows the model but not its input limit
   * @param defaultReserve      the reserve used when the registry knows the model but not its output limit
   * @param fallbackResolver    the limits of a model the registry has no entry for, by model name
   * @return the limits of the resolved model
   */
  def resolve(
    lookupProviders: Seq[String],
    modelName: String,
    defaultContextWindow: Int,
    defaultReserve: Int,
    fallbackResolver: String => ContextLimits
  ): ContextLimits =
    val registryResult =
      lookupProviders.view
        .flatMap(p => service.lookup(p, modelName).toOption)
        .headOption
        .orElse(service.lookup(modelName).toOption)

    registryResult match
      case Some(metadata) =>
        val limits = ContextLimits(
          metadata.maxInputTokens.getOrElse(defaultContextWindow),
          metadata.maxOutputTokens.getOrElse(defaultReserve)
        )
        logger.debug(
          s"Using model registry metadata for ${lookupProviders.headOption.fold("")(_ + "/")}$modelName: " +
            s"context=${limits.contextWindow}, reserve=${limits.reserveCompletion}"
        )
        limits
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

  /**
   * The resolver of the [[org.llm4s.model.ModelRegistryService]] in scope, so a provider config's
   * `fromValues` needs only the registry that `LLMConnect.getClient` needs too (#2340):
   *
   * {{{
   * for {
   *   registry <- ModelRegistryService.default()
   *   given ModelRegistryService = registry
   *   config <- OpenAIConfig.fromValues("gpt-4o-mini", apiKey, OpenAIConfig.DEFAULT_BASE_URL)
   *   client <- LLMConnect.getClient(config)
   * } yield client
   * }}}
   *
   * A `ContextWindowResolver` given in lexical scope still takes precedence.
   */
  given fromModelRegistry(using service: ModelRegistryService): ContextWindowResolver =
    ContextWindowResolver(service)
