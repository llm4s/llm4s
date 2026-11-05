package org.llm4s.model

import org.llm4s.annotation.Stable
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{
  CompletionOptions,
  Message,
  RequestAdjustment,
  RequestOption,
  ResponseFormat,
  SystemMessage,
  UserMessage
}
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
@Stable
trait RequestTransformer {

  /**
   * Validate and transform completion options for a specific model.
   *
   * Checks model capabilities and either drops unsupported parameters or
   * returns validation errors, depending on the dropUnsupported flag.
   *
   * @param modelId The model identifier (e.g., "o1", "gpt-4o", "claude-3-7-sonnet")
   * @param options The completion options to transform
   * @param dropUnsupported If true, drop or adjust unsupported params; if false, return error.
   *                        [[TransformationResult.transform]] also reports what it dropped.
   * @return Transformed options or validation error
   */
  def transformOptions(
    modelId: String,
    options: CompletionOptions,
    dropUnsupported: Boolean = false
  ): Result[CompletionOptions]

  /**
   * The options with everything `modelId` cannot take dropped or adjusted, as
   * `transformOptions(modelId, options, dropUnsupported = true)` returns them, and a
   * [[org.llm4s.llmconnect.model.RequestAdjustment]] for each change. [[TransformationResult.transform]]
   * calls it. A `Left` from `transformOptions` is returned as it is, so a transformer can still refuse a
   * request outright.
   *
   * This hook is `private[llm4s]`: only llm4s's own transformers report adjustments. A transformer
   * written outside llm4s cannot override it, so the default applies, which reports none. Whether to
   * make the hook public is left to a later change under RFC 0008.
   */
  private[llm4s] def adjustOptions(
    modelId: String,
    options: CompletionOptions
  ): Result[(CompletionOptions, List[RequestAdjustment])] =
    transformOptions(modelId, options, dropUnsupported = true).map(_ -> List.empty)

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
  ): Result[CompletionOptions] =
    if (dropUnsupported) adjustOptions(modelId, options).map(_._1)
    else
      violations(modelId, options, getCapabilities(modelId)).flatMap(_.error) match {
        case Nil    => Right(options)
        case errors => Left(ValidationError(errors.mkString("; "), "options"))
      }

  // Pure: the client that sends the request logs what was adjusted (AdjustmentLog), once.
  override private[llm4s] def adjustOptions(
    modelId: String,
    options: CompletionOptions
  ): Result[(CompletionOptions, List[RequestAdjustment])] = {
    val found = violations(modelId, options, getCapabilities(modelId))
    Right((found.foldLeft(options)((adjusted, violation) => violation.drop(adjusted)), found.map(_.adjustment)))
  }

  /**
   * An option the model does not support.
   *
   * @param drop       removes or adjusts it, when unsupported options may be dropped
   * @param adjustment what `drop` does, reported to the caller
   * @param error      why the options are invalid when they may not be dropped; `None` when the
   *                   option is sent anyway
   */
  final private case class Violation(
    drop: CompletionOptions => CompletionOptions,
    adjustment: RequestAdjustment,
    error: Option[String]
  )

  /** The options `capabilities` rule out, in the order their errors are reported. */
  private def violations(
    modelId: String,
    options: CompletionOptions,
    capabilities: ModelCapabilities
  ): List[Violation] = {
    val disallowed = capabilities.disallowedParams.getOrElse(Set.empty)

    val temperature = capabilities.temperatureConstraint.collect {
      case (min, max) if options.temperature < min || options.temperature > max =>
        Violation(
          _.withTemperature(min),
          RequestAdjustment.Adjusted(
            RequestOption.Temperature,
            options.temperature.toString,
            min.toString,
            s"allowed range for $modelId is $min-$max"
          ),
          Some(s"Temperature ${options.temperature} not allowed for $modelId (must be between $min and $max)")
        )
    }

    val topP = Option.when(disallowed.contains("top_p") && options.topP != 1.0)(
      Violation(
        _.withTopP(1.0),
        RequestAdjustment.Dropped(RequestOption.TopP, s"top_p not supported for $modelId"),
        Some(s"top_p parameter not supported for $modelId")
      )
    )

    val presencePenalty = Option.when(disallowed.contains("presence_penalty") && options.presencePenalty != 0.0)(
      Violation(
        _.withPresencePenalty(0.0),
        RequestAdjustment.Dropped(RequestOption.PresencePenalty, s"presence_penalty not supported for $modelId"),
        Some(s"presence_penalty parameter not supported for $modelId")
      )
    )

    val frequencyPenalty = Option.when(disallowed.contains("frequency_penalty") && options.frequencyPenalty != 0.0)(
      Violation(
        _.withFrequencyPenalty(0.0),
        RequestAdjustment.Dropped(RequestOption.FrequencyPenalty, s"frequency_penalty not supported for $modelId"),
        Some(s"frequency_penalty parameter not supported for $modelId")
      )
    )

    val tools = Option.when(options.tools.nonEmpty && !capabilities.supportsFunctionCalling.getOrElse(true))(
      Violation(
        _.withTools(Seq.empty),
        RequestAdjustment.Dropped(RequestOption.Tools, s"function calling not supported for $modelId"),
        Some(s"Function calling not supported for $modelId")
      )
    )

    // Structured output. Json is a best-effort fallback: when the model does not support it, it
    // is dropped if allowed, else sent anyway with no error. JsonSchema is strict: the caller is
    // told the constraint cannot be applied.
    val responseFormat = options.responseFormat.filter(_ => capabilities.supportsResponseSchema.contains(false)).map {
      case ResponseFormat.Json =>
        Violation(
          _.withResponseFormat(None),
          RequestAdjustment.Dropped(RequestOption.ResponseFormat, s"structured output not supported for $modelId"),
          None
        )
      case _: ResponseFormat.JsonSchema =>
        Violation(
          _.withResponseFormat(None),
          RequestAdjustment
            .Dropped(RequestOption.ResponseFormat, s"structured output (JSON schema) not supported for $modelId"),
          Some(s"Structured output (JSON schema) not supported for model $modelId")
        )
    }

    List(temperature, topP, presencePenalty, frequencyPenalty, tools, responseFormat).flatten
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
 *
 * @param adjustments what the transformation dropped or changed from the requested options, in the
 *                    order found; the client puts them on its [[org.llm4s.llmconnect.model.Completion]]
 */
@Stable
final case class TransformationResult private (
  options: CompletionOptions,
  messages: Seq[Message],
  requiresFakeStreaming: Boolean,
  adjustments: List[RequestAdjustment]
) {

  /** Sets the transformed completion options. */
  def withOptions(options: CompletionOptions): TransformationResult = copy(options = options)

  /** Sets the transformed messages. */
  def withMessages(messages: Seq[Message]): TransformationResult = copy(messages = messages)

  /** Sets whether the client must fake streaming. */
  def withRequiresFakeStreaming(requiresFakeStreaming: Boolean): TransformationResult =
    copy(requiresFakeStreaming = requiresFakeStreaming)

  /** Sets what the transformation dropped or changed. */
  def withAdjustments(adjustments: List[RequestAdjustment]): TransformationResult = copy(adjustments = adjustments)
}

object TransformationResult {

  /**
   * Creates a [[TransformationResult]]; streaming is real unless `requiresFakeStreaming` says
   * otherwise, and nothing was adjusted unless `adjustments` says so.
   */
  def apply(
    options: CompletionOptions,
    messages: Seq[Message],
    requiresFakeStreaming: Boolean = false,
    adjustments: List[RequestAdjustment] = List.empty
  ): TransformationResult =
    new TransformationResult(options, messages, requiresFakeStreaming, adjustments)

  /**
   * The `apply` before `adjustments` was added, with none. Kept for Java and Kotlin, which pass every
   * argument because they do not see Scala's defaults.
   */
  def apply(options: CompletionOptions, messages: Seq[Message], requiresFakeStreaming: Boolean): TransformationResult =
    new TransformationResult(options, messages, requiresFakeStreaming, List.empty)

  /**
   * Transforms both options and messages in one call. With `dropUnsupported`, what was dropped or
   * changed is in [[TransformationResult.adjustments]]; without it, an unsupported option fails.
   */
  def transform(
    modelId: String,
    options: CompletionOptions,
    messages: Seq[Message],
    transformer: RequestTransformer,
    dropUnsupported: Boolean = true
  ): Result[TransformationResult] = {
    val transformedOptions =
      if (dropUnsupported) transformer.adjustOptions(modelId, options)
      else transformer.transformOptions(modelId, options).map(_ -> List.empty[RequestAdjustment])
    transformedOptions.map { (transformed, adjustments) =>
      TransformationResult(
        options = transformed,
        messages = transformer.transformMessages(modelId, messages),
        requiresFakeStreaming = transformer.requiresFakeStreaming(modelId),
        adjustments = adjustments
      )
    }
  }
}
