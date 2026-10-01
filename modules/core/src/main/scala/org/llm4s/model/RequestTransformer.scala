package org.llm4s.model

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ CompletionOptions, Message, ResponseFormat, SystemMessage, UserMessage }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Handles model-specific parameter validation and transformation.
 *
 * Uses [[ModelCapabilities]] from [[ModelRegistryService]] to apply constraints based on
 * what each model supports, as LiteLLM does. Rules a vendor knows but the registry does not
 * record live in that vendor's provider module, layered on with [[RequestTransformer.adjusted]].
 *
 * Example usage:
 * {{{
 *   val transformer = RequestTransformer.default(registryService)
 *   val result = transformer.transformOptions("gpt-4o", options, dropUnsupported = true)
 *   result match {
 *     case Right(transformed) => // use transformed options
 *     case Left(error) => // handle validation error
 *   }
 * }}}
 */
trait RequestTransformer {

  /**
   * Validate and transform completion options for a specific model.
   *
   * Checks model capabilities and either drops unsupported parameters or
   * returns validation errors, depending on the dropUnsupported flag.
   *
   * @param modelId The model identifier (e.g., "o1", "gpt-4o", "claude-3-7-sonnet")
   * @param options The completion options to transform
   * @param dropUnsupported If true, silently drop/adjust unsupported params; if false, return error
   * @return Transformed options or validation error
   */
  def transformOptions(
    modelId: String,
    options: CompletionOptions,
    dropUnsupported: Boolean = false
  ): Result[CompletionOptions]

  /**
   * Transform messages for model-specific requirements.
   *
   * For example, a model whose capabilities say it does not support system messages will have
   * its system messages converted to user messages with a "[System]:" prefix.
   *
   * @param modelId The model identifier
   * @param messages The messages to transform
   * @return Transformed messages
   */
  def transformMessages(
    modelId: String,
    messages: Seq[Message]
  ): Seq[Message]

  /**
   * Check if streaming needs to be faked for this model.
   *
   * A model whose capabilities say it does not support native streaming requires the client
   * to simulate streaming by returning the full response as a single chunk.
   *
   * @param modelId The model identifier
   * @return true if the model requires fake streaming
   */
  def requiresFakeStreaming(modelId: String): Boolean

  /**
   * Get the set of parameters that are not supported by this model.
   *
   * @param modelId The model identifier
   * @return Set of disallowed parameter names, empty if all are allowed
   */
  def disallowedParams(modelId: String): Set[String]
}

object RequestTransformer {

  /**
   * Default implementation using ModelRegistryService for capability lookups.
   */
  def default(service: ModelRegistryService): RequestTransformer =
    new DefaultRequestTransformer(Map.empty, service, (_, capabilities) => capabilities)

  /**
   * Create a transformer with custom model overrides.
   * Useful for testing or for models not yet in the registry.
   */
  def withOverrides(overrides: Map[String, ModelCapabilities], service: ModelRegistryService): RequestTransformer =
    new DefaultRequestTransformer(overrides, service, (_, capabilities) => capabilities)

  /**
   * A transformer whose looked-up capabilities pass through `adjust` before they are applied.
   *
   * This is how a provider module layers on rules it knows but the registry does not record,
   * such as constraints that follow from a vendor's model naming, without core knowing them.
   *
   * @param adjust given the model id and the registry's capabilities for it, the capabilities to use
   */
  def adjusted(service: ModelRegistryService)(
    adjust: (String, ModelCapabilities) => ModelCapabilities
  ): RequestTransformer =
    new DefaultRequestTransformer(Map.empty, service, adjust)
}

/**
 * Default implementation that uses ModelRegistryService for capability lookups.
 *
 * @param overrides model-specific capability overrides, checked before the registry
 * @param adjust    applied to the capabilities found, whatever their source
 */
final private[model] class DefaultRequestTransformer(
  overrides: Map[String, ModelCapabilities],
  service: ModelRegistryService,
  adjust: (String, ModelCapabilities) => ModelCapabilities
) extends RequestTransformer {

  private val logger = LoggerFactory.getLogger(getClass)

  override def transformOptions(
    modelId: String,
    options: CompletionOptions,
    dropUnsupported: Boolean
  ): Result[CompletionOptions] = {

    val capabilities = getCapabilities(modelId)
    var transformed  = options
    val errors       = scala.collection.mutable.ListBuffer[String]()

    // 1. Check temperature constraints
    capabilities.temperatureConstraint.foreach { case (min, max) =>
      if (options.temperature < min || options.temperature > max) {
        if (dropUnsupported) {
          logger.debug(
            s"Model $modelId: adjusting temperature from ${options.temperature} to $min (allowed range: $min-$max)"
          )
          transformed = transformed.copy(temperature = min)
        } else {
          errors += s"Temperature ${options.temperature} not allowed for $modelId (must be between $min and $max)"
        }
      }
    }

    // 2. Check disallowed parameters
    capabilities.disallowedParams.foreach { disallowed =>
      // Check top_p
      if (disallowed.contains("top_p") && options.topP != 1.0) {
        if (dropUnsupported) {
          logger.debug(s"Model $modelId: dropping top_p (not supported)")
          transformed = transformed.copy(topP = 1.0)
        } else {
          errors += s"top_p parameter not supported for $modelId"
        }
      }

      // Check presence_penalty
      if (disallowed.contains("presence_penalty") && options.presencePenalty != 0.0) {
        if (dropUnsupported) {
          logger.debug(s"Model $modelId: dropping presence_penalty (not supported)")
          transformed = transformed.copy(presencePenalty = 0.0)
        } else {
          errors += s"presence_penalty parameter not supported for $modelId"
        }
      }

      // Check frequency_penalty
      if (disallowed.contains("frequency_penalty") && options.frequencyPenalty != 0.0) {
        if (dropUnsupported) {
          logger.debug(s"Model $modelId: dropping frequency_penalty (not supported)")
          transformed = transformed.copy(frequencyPenalty = 0.0)
        } else {
          errors += s"frequency_penalty parameter not supported for $modelId"
        }
      }
    }

    // 3. Check function calling support
    if (options.tools.nonEmpty && !capabilities.supportsFunctionCalling.getOrElse(true)) {
      if (dropUnsupported) {
        logger.debug(s"Model $modelId: dropping tools (function calling not supported)")
        transformed = transformed.copy(tools = Seq.empty)
      } else {
        errors += s"Function calling not supported for $modelId"
      }
    }

    // 4. Check response format (structured output) support.
    //    Validation policy (explicit and consistent):
    //    - Json: allowed fallback — when provider does not support structured output, we either drop (if dropUnsupported)
    //      or keep and send (if !dropUnsupported). No validation error for Json so callers can still get best-effort.
    //    - JsonSchema: strict — when provider does not support it and dropUnsupported=false, we return a validation
    //      error so the caller knows the constraint was not applied. When dropUnsupported=true we drop it.
    options.responseFormat.foreach {
      case ResponseFormat.Json =>
        capabilities.supportsResponseSchema match {
          case Some(false) =>
            if (dropUnsupported) {
              logger.debug(s"Model $modelId: dropping responseFormat (structured output not supported)")
              transformed = transformed.copy(responseFormat = None)
            }
          // else: keep and send (Json is allowed fallback; provider may ignore or accept)
          case _ => () // true or None: keep and send
        }
      case _: ResponseFormat.JsonSchema =>
        capabilities.supportsResponseSchema match {
          case Some(false) =>
            if (dropUnsupported) {
              logger.debug(s"Model $modelId: dropping JsonSchema responseFormat (not supported)")
              transformed = transformed.copy(responseFormat = None)
            } else {
              errors += s"Structured output (JSON schema) not supported for model $modelId"
            }
          case _ => () // true or None: keep and send
        }
    }

    if (errors.nonEmpty) {
      Left(ValidationError(errors.mkString("; "), "options"))
    } else {
      Right(transformed)
    }
  }

  override def transformMessages(
    modelId: String,
    messages: Seq[Message]
  ): Seq[Message] = {
    val capabilities = getCapabilities(modelId)

    // Convert system messages to user messages if not supported
    if (!capabilities.supportsSystemMessages.getOrElse(true)) {
      logger.debug(s"Model $modelId: converting system messages to user messages (not supported)")
      messages.map {
        case SystemMessage(content) => UserMessage(s"[System]: $content")
        case other                  => other
      }
    } else {
      messages
    }
  }

  override def requiresFakeStreaming(modelId: String): Boolean = {
    val capabilities = getCapabilities(modelId)
    !capabilities.supportsNativeStreaming.getOrElse(true)
  }

  override def disallowedParams(modelId: String): Set[String] = {
    val capabilities = getCapabilities(modelId)
    capabilities.disallowedParams.getOrElse(Set.empty)
  }

  /** Capabilities for a model: overrides first, then the registry, then none; then `adjust`. */
  private def getCapabilities(modelId: String): ModelCapabilities =
    adjust(
      modelId,
      overrides
        .get(modelId)
        .orElse(service.lookup(modelId).toOption.map(_.capabilities))
        .getOrElse(ModelCapabilities())
    )
}

/**
 * Transformed options and messages for one request, and whether the client must fake streaming.
 */
case class TransformationResult(
  options: CompletionOptions,
  messages: Seq[Message],
  requiresFakeStreaming: Boolean = false
)

object TransformationResult {

  /**
   * Convenience method to transform both options and messages in one call.
   */
  def transform(
    modelId: String,
    options: CompletionOptions,
    messages: Seq[Message],
    transformer: RequestTransformer,
    dropUnsupported: Boolean = true
  ): Result[TransformationResult] =
    transformer.transformOptions(modelId, options, dropUnsupported).map { transformedOptions =>
      TransformationResult(
        options = transformedOptions,
        messages = transformer.transformMessages(modelId, messages),
        requiresFakeStreaming = transformer.requiresFakeStreaming(modelId)
      )
    }
}
