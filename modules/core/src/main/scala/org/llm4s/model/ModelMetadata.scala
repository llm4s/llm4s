package org.llm4s.model

import org.llm4s.types.Result
import org.llm4s.error.ValidationError
import upickle.default._

/**
 * Comprehensive metadata for an LLM model.
 * Based on litellm's model_prices_and_context_window.json schema.
 *
 * This provides a centralized, type-safe representation of model capabilities,
 * pricing, and constraints that can be queried at runtime.
 *
 * @param modelId The unique identifier for this model (e.g., "gpt-4o", "claude-3-7-sonnet-latest")
 * @param provider The LLM provider (openai, anthropic, azure, etc.)
 * @param mode The model mode (chat, embedding, image_generation, etc.)
 * @param maxInputTokens Maximum input tokens supported
 * @param maxOutputTokens Maximum output tokens supported
 * @param inputCostPerToken Cost per input token (in dollars)
 * @param outputCostPerToken Cost per output token (in dollars)
 * @param capabilities Model capabilities and features
 * @param pricing Detailed pricing information
 * @param deprecationDate Optional deprecation date (YYYY-MM-DD)
 */
case class ModelMetadata(
  modelId: String,
  provider: String,
  mode: ModelMode,
  maxInputTokens: Option[Int],
  maxOutputTokens: Option[Int],
  inputCostPerToken: Option[Double],
  outputCostPerToken: Option[Double],
  capabilities: ModelCapabilities,
  pricing: ModelPricing,
  deprecationDate: Option[String]
) {

  /**
   * Get the effective context window size.
   * Prefers maxInputTokens, falls back to maxOutputTokens if available.
   */
  def contextWindow: Option[Int] = maxInputTokens.orElse(maxOutputTokens)

  /**
   * Get the reserve completion tokens (output capacity).
   */
  def reserveCompletion: Option[Int] = maxOutputTokens

  /**
   * Check if this model supports a specific capability.
   */
  def supports(capability: String): Boolean = capability.toLowerCase match {
    case "function_calling" | "tools"     => capabilities.supportsFunctionCalling.getOrElse(false)
    case "parallel_function_calling"      => capabilities.supportsParallelFunctionCalling.getOrElse(false)
    case "vision" | "images"              => capabilities.supportsVision.getOrElse(false)
    case "prompt_caching" | "caching"     => capabilities.supportsPromptCaching.getOrElse(false)
    case "reasoning"                      => capabilities.supportsReasoning.getOrElse(false)
    case "response_schema" | "structured" => capabilities.supportsResponseSchema.getOrElse(false)
    case "system_messages"                => capabilities.supportsSystemMessages.getOrElse(false)
    case "pdf_input" | "pdf"              => capabilities.supportsPdfInput.getOrElse(false)
    case "audio_input"                    => capabilities.supportsAudioInput.getOrElse(false)
    case "audio_output"                   => capabilities.supportsAudioOutput.getOrElse(false)
    case "web_search"                     => capabilities.supportsWebSearch.getOrElse(false)
    case "computer_use"                   => capabilities.supportsComputerUse.getOrElse(false)
    case "assistant_prefill" | "prefill"  => capabilities.supportsAssistantPrefill.getOrElse(false)
    case "tool_choice"                    => capabilities.supportsToolChoice.getOrElse(false)
    case "native_streaming" | "streaming" => capabilities.supportsNativeStreaming.getOrElse(true)
    case _                                => false
  }

  /**
   * Check if the model is deprecated.
   */
  def isDeprecated: Boolean = deprecationDate.exists { date =>
    scala.util
      .Try {
        val deprecation = java.time.LocalDate.parse(date)
        val now         = java.time.LocalDate.now()
        !now.isBefore(deprecation)
      }
      .getOrElse(false)
  }

  /**
   * Get a human-readable description of the model.
   */
  def description: String = {
    val caps = List(
      if (capabilities.supportsFunctionCalling.getOrElse(false)) Some("function-calling") else None,
      if (capabilities.supportsVision.getOrElse(false)) Some("vision") else None,
      if (capabilities.supportsPromptCaching.getOrElse(false)) Some("caching") else None,
      if (capabilities.supportsReasoning.getOrElse(false)) Some("reasoning") else None
    ).flatten

    val capsStr = if (caps.nonEmpty) caps.mkString(", ") else "basic"
    val ctxStr  = contextWindow.map(c => s"${c / 1000}K").getOrElse("unknown")
    val deprStr = if (isDeprecated) " [DEPRECATED]" else ""
    s"$modelId ($provider, ${mode.name}, ${ctxStr} context, $capsStr)$deprStr"
  }
}

object ModelMetadata {
  implicit val rw: ReadWriter[ModelMetadata] = macroRW

  /**
   * Create ModelMetadata from raw JSON values.
   */
  def fromJson(modelId: String, data: ujson.Value): Result[ModelMetadata] = {
    import org.llm4s.types.TryOps
    scala.util
      .Try {
        val obj      = data.obj
        val provider = obj.get("litellm_provider").map(_.str).getOrElse("unknown")
        val mode     = obj.get("mode").map(v => ModelMode.fromString(v.str)).getOrElse(ModelMode.Chat)

        val maxInputTokens  = obj.get("max_input_tokens").flatMap(v => if (v.isNull) None else Some(v.num.toInt))
        val maxOutputTokens = obj.get("max_output_tokens").flatMap(v => if (v.isNull) None else Some(v.num.toInt))

        val inputCost  = obj.get("input_cost_per_token").flatMap(v => if (v.isNull) None else Some(v.num))
        val outputCost = obj.get("output_cost_per_token").flatMap(v => if (v.isNull) None else Some(v.num))

        val capabilities = ModelCapabilities.fromJson(data)
        val pricing      = ModelPricing.fromJson(data)

        val deprecationDate =
          obj.get("deprecation_date").flatMap(v => if (v.isNull || v.str.isEmpty) None else Some(v.str))

        ModelMetadata(
          modelId = modelId,
          provider = provider,
          mode = mode,
          maxInputTokens = maxInputTokens,
          maxOutputTokens = maxOutputTokens,
          inputCostPerToken = inputCost,
          outputCostPerToken = outputCost,
          capabilities = capabilities,
          pricing = pricing,
          deprecationDate = deprecationDate
        )
      }
      .toResult
      .left
      .map(e => ValidationError(s"Failed to parse model metadata for $modelId: ${e.message}", "modelId"))
  }
}

/**
 * Model operation mode.
 */
sealed trait ModelMode {
  def name: String
}

object ModelMode {
  case object Chat               extends ModelMode { val name = "chat"                }
  case object Embedding          extends ModelMode { val name = "embedding"           }
  case object Completion         extends ModelMode { val name = "completion"          }
  case object ImageGeneration    extends ModelMode { val name = "image_generation"    }
  case object AudioTranscription extends ModelMode { val name = "audio_transcription" }
  case object AudioSpeech        extends ModelMode { val name = "audio_speech"        }
  case object Moderation         extends ModelMode { val name = "moderation"          }
  case object Rerank             extends ModelMode { val name = "rerank"              }
  case object Search             extends ModelMode { val name = "search"              }
  case object Unknown            extends ModelMode { val name = "unknown"             }

  def fromString(s: String): ModelMode = s.toLowerCase match {
    case "chat"                => Chat
    case "embedding"           => Embedding
    case "completion"          => Completion
    case "image_generation"    => ImageGeneration
    case "audio_transcription" => AudioTranscription
    case "audio_speech"        => AudioSpeech
    case "moderation"          => Moderation
    case "rerank"              => Rerank
    case "search"              => Search
    case _                     => Unknown
  }

  implicit val rw: ReadWriter[ModelMode] = readwriter[String].bimap(_.name, fromString)
}

/**
 * Model capabilities and features.
 *
 * @param supportsFunctionCalling Whether the model supports function/tool calling
 * @param supportsParallelFunctionCalling Whether the model supports parallel tool calls
 * @param supportsVision Whether the model supports vision/image inputs
 * @param supportsPromptCaching Whether the model supports prompt caching
 * @param supportsReasoning Whether the model supports reasoning (O-series, Claude thinking)
 * @param supportsResponseSchema Whether the model supports structured response schemas
 * @param supportsSystemMessages Whether the model supports system messages (false for some O-series)
 * @param supportsPdfInput Whether the model supports PDF file inputs
 * @param supportsAudioInput Whether the model supports audio inputs
 * @param supportsAudioOutput Whether the model supports audio outputs
 * @param supportsWebSearch Whether the model supports web search
 * @param supportsComputerUse Whether the model supports computer use
 * @param supportsAssistantPrefill Whether the model supports assistant message prefill
 * @param supportsToolChoice Whether the model supports tool_choice parameter
 * @param supportsNativeStreaming Whether the model supports native streaming (false = needs fake streaming)
 * @param supportedRegions List of supported deployment regions
 * @param disallowedParams Set of parameter names that are not supported by this model
 * @param temperatureConstraint Temperature constraint: None = any, Some((min, max)) = restricted range
 */
case class ModelCapabilities(
  supportsFunctionCalling: Option[Boolean] = None,
  supportsParallelFunctionCalling: Option[Boolean] = None,
  supportsVision: Option[Boolean] = None,
  supportsPromptCaching: Option[Boolean] = None,
  supportsReasoning: Option[Boolean] = None,
  supportsResponseSchema: Option[Boolean] = None,
  supportsSystemMessages: Option[Boolean] = None,
  supportsPdfInput: Option[Boolean] = None,
  supportsAudioInput: Option[Boolean] = None,
  supportsAudioOutput: Option[Boolean] = None,
  supportsWebSearch: Option[Boolean] = None,
  supportsComputerUse: Option[Boolean] = None,
  supportsAssistantPrefill: Option[Boolean] = None,
  supportsToolChoice: Option[Boolean] = None,
  supportsNativeStreaming: Option[Boolean] = None,
  supportedRegions: Option[List[String]] = None,
  disallowedParams: Option[Set[String]] = None,
  temperatureConstraint: Option[(Double, Double)] = None
)

object ModelCapabilities {
  implicit val rw: ReadWriter[ModelCapabilities] = macroRW

  def fromJson(data: ujson.Value): ModelCapabilities = {
    val obj = data.obj

    def getBool(key: String): Option[Boolean] =
      obj.get(key).flatMap(v => if (v.isNull) None else Some(v.bool))

    def getStringList(key: String): Option[List[String]] =
      obj.get(key).flatMap { v =>
        if (v.isNull) None
        else scala.util.Try(v.arr.map(_.str).toList).toOption
      }

    def getStringSet(key: String): Option[Set[String]] =
      obj.get(key).flatMap { v =>
        if (v.isNull) None
        else scala.util.Try(v.arr.map(_.str).toSet).toOption
      }

    def getDoubleRange(key: String): Option[(Double, Double)] =
      obj.get(key).flatMap { v =>
        if (v.isNull) None
        else
          scala.util.Try {
            val arr = v.arr
            (arr(0).num, arr(1).num)
          }.toOption
      }

    ModelCapabilities(
      supportsFunctionCalling = getBool("supports_function_calling"),
      supportsParallelFunctionCalling = getBool("supports_parallel_function_calling"),
      supportsVision = getBool("supports_vision"),
      supportsPromptCaching = getBool("supports_prompt_caching"),
      supportsReasoning = getBool("supports_reasoning"),
      supportsResponseSchema = getBool("supports_response_schema"),
      supportsSystemMessages = getBool("supports_system_messages"),
      supportsPdfInput = getBool("supports_pdf_input"),
      supportsAudioInput = getBool("supports_audio_input"),
      supportsAudioOutput = getBool("supports_audio_output"),
      supportsWebSearch = getBool("supports_web_search"),
      supportsComputerUse = getBool("supports_computer_use"),
      supportsAssistantPrefill = getBool("supports_assistant_prefill"),
      supportsToolChoice = getBool("supports_tool_choice"),
      supportsNativeStreaming = getBool("supports_native_streaming"),
      supportedRegions = getStringList("supported_regions"),
      disallowedParams = getStringSet("disallowed_params"),
      temperatureConstraint = getDoubleRange("temperature_constraint")
    )
  }
}

/**
 * Detailed pricing information for a model.
 */
case class ModelPricing(
  inputCostPerToken: Option[Double] = None,
  outputCostPerToken: Option[Double] = None,
  cacheCreationInputTokenCost: Option[Double] = None,
  cacheReadInputTokenCost: Option[Double] = None,
  inputCostPerTokenBatches: Option[Double] = None,
  outputCostPerTokenBatches: Option[Double] = None,
  inputCostPerTokenPriority: Option[Double] = None,
  outputCostPerTokenPriority: Option[Double] = None,
  outputCostPerReasoningToken: Option[Double] = None,
  inputCostPerAudioToken: Option[Double] = None,
  outputCostPerAudioToken: Option[Double] = None,
  inputCostPerImage: Option[Double] = None,
  outputCostPerImage: Option[Double] = None,
  inputCostPerPixel: Option[Double] = None,
  outputCostPerPixel: Option[Double] = None
) {

  /**
   * Estimate the cost of a completion given token counts.
   *
   * @param inputTokens Number of input tokens
   * @param outputTokens Number of output tokens
   * @return Estimated cost in dollars
   */
  def estimateCost(inputTokens: Int, outputTokens: Int): Option[Double] =
    for {
      inCost  <- inputCostPerToken
      outCost <- outputCostPerToken
    } yield (inputTokens * inCost) + (outputTokens * outCost)
}

object ModelPricing {
  implicit val rw: ReadWriter[ModelPricing] = macroRW

  def fromJson(data: ujson.Value): ModelPricing = {
    val obj = data.obj

    def getDouble(key: String): Option[Double] =
      obj.get(key).flatMap(v => if (v.isNull) None else Some(v.num))

    ModelPricing(
      inputCostPerToken = getDouble("input_cost_per_token"),
      outputCostPerToken = getDouble("output_cost_per_token"),
      cacheCreationInputTokenCost = getDouble("cache_creation_input_token_cost"),
      cacheReadInputTokenCost = getDouble("cache_read_input_token_cost"),
      inputCostPerTokenBatches = getDouble("input_cost_per_token_batches"),
      outputCostPerTokenBatches = getDouble("output_cost_per_token_batches"),
      inputCostPerTokenPriority = getDouble("input_cost_per_token_priority"),
      outputCostPerTokenPriority = getDouble("output_cost_per_token_priority"),
      outputCostPerReasoningToken = getDouble("output_cost_per_reasoning_token"),
      inputCostPerAudioToken = getDouble("input_cost_per_audio_token"),
      outputCostPerAudioToken = getDouble("output_cost_per_audio_token"),
      inputCostPerImage = getDouble("input_cost_per_image"),
      outputCostPerImage = getDouble("output_cost_per_image"),
      inputCostPerPixel = getDouble("input_cost_per_pixel"),
      outputCostPerPixel = getDouble("output_cost_per_pixel")
    )
  }
}
