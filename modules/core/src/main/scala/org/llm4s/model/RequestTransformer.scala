package org.llm4s.model

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ CompletionOptions, Message, SystemMessage, UserMessage }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Handles model-specific parameter validation and transformation.
 *
 * Uses ModelCapabilities from ModelRegistry to apply constraints based on
 * what each model supports. This mirrors LiteLLM's approach to handling
 * model-specific quirks (e.g., O-series temperature restrictions).
 *
 * Example usage:
 * {{{
 *   val transformer = RequestTransformer.default
 *   val result = transformer.transformOptions("o1", options, dropUnsupported = true)
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
   * For example, O-series models that don't support system messages will have
   * their system messages converted to user messages with a "[System]:" prefix.
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
   * Some models (like O1) don't support native streaming and require
   * the client to simulate streaming by returning the full response
   * as a single chunk.
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
  def getDisallowedParams(modelId: String): Set[String]
}

object RequestTransformer {

  /**
   * Default implementation using ModelRegistry for capability lookups.
   */
  val default: RequestTransformer = new DefaultRequestTransformer()

  /**
   * Create a transformer with custom model overrides.
   * Useful for testing or for models not yet in the registry.
   */
  def withOverrides(overrides: Map[String, ModelCapabilities]): RequestTransformer =
    new DefaultRequestTransformer(overrides)
}

/**
 * Default implementation that uses ModelRegistry for capability lookups.
 *
 * @param overrides Optional map of model-specific capability overrides
 */
class DefaultRequestTransformer(
  private val overrides: Map[String, ModelCapabilities] = Map.empty
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

  override def getDisallowedParams(modelId: String): Set[String] = {
    val capabilities = getCapabilities(modelId)
    capabilities.disallowedParams.getOrElse(Set.empty)
  }

  /**
   * Get capabilities for a model, checking overrides first, then registry.
   * O-series models get special constraints merged regardless of source.
   */
  private def getCapabilities(modelId: String): ModelCapabilities = {
    // Check overrides first, then registry, then default
    val baseCapabilities = overrides
      .get(modelId)
      .orElse {
        // Then check registry
        ModelRegistry.lookup(modelId).toOption.map(_.capabilities)
      }
      .getOrElse(ModelCapabilities())

    // For O-series reasoning models, merge special constraints
    // O-series models have strict requirements not always in metadata
    if (isOSeriesModel(modelId)) {
      mergeWithOSeriesConstraints(baseCapabilities)
    } else {
      baseCapabilities
    }
  }

  /**
   * Merge base capabilities with O-series specific constraints.
   * O-series constraints take precedence where specified.
   */
  private def mergeWithOSeriesConstraints(base: ModelCapabilities): ModelCapabilities =
    base.copy(
      supportsReasoning = base.supportsReasoning.orElse(oSeriesCapabilities.supportsReasoning),
      supportsNativeStreaming = oSeriesCapabilities.supportsNativeStreaming, // Always override
      supportsSystemMessages = oSeriesCapabilities.supportsSystemMessages,   // Always override
      temperatureConstraint = oSeriesCapabilities.temperatureConstraint,     // Always override
      disallowedParams = oSeriesCapabilities.disallowedParams                // Always override
    )

  /**
   * Check if a model is an O-series reasoning model based on naming patterns.
   */
  private def isOSeriesModel(modelId: String): Boolean = {
    val normalized = modelId.toLowerCase
    normalized.startsWith("o1") ||
    normalized.startsWith("o3") ||
    normalized.contains("/o1") ||
    normalized.contains("/o3")
  }

  /**
   * Default capabilities for O-series models.
   * These models have strict parameter requirements.
   */
  private val oSeriesCapabilities = ModelCapabilities(
    supportsReasoning = Some(true),
    supportsNativeStreaming = Some(false),
    supportsSystemMessages = Some(false),
    temperatureConstraint = Some((1.0, 1.0)),
    disallowedParams = Some(Set("top_p", "presence_penalty", "frequency_penalty", "logprobs"))
  )
}

/**
 * Transformation result containing both transformed options and any warnings.
 */
case class TransformationResult(
  options: CompletionOptions,
  messages: Seq[Message],
  warnings: Seq[String] = Seq.empty,
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
    dropUnsupported: Boolean = true,
    transformer: RequestTransformer = RequestTransformer.default
  ): Result[TransformationResult] =
    transformer.transformOptions(modelId, options, dropUnsupported).map { transformedOptions =>
      TransformationResult(
        options = transformedOptions,
        messages = transformer.transformMessages(modelId, messages),
        requiresFakeStreaming = transformer.requiresFakeStreaming(modelId)
      )
    }
}
