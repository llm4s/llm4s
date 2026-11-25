package org.llm4s.llmconnect.provider

import com.openai.models.{ ReasoningEffort => SdkReasoningEffort }
import org.llm4s.llmconnect.model.{ CompletionOptions, RequestAdjustment, RequestOption, Thinking }
import org.llm4s.model.{ ModelCapabilities, ModelRegistryService, RequestTransformer }

/**
 * OpenAI's request rules that follow from model naming rather than from registry data.
 *
 * The o-series reasoning models take no system message, no native streaming, temperature 1
 * only and no sampling penalties, whatever the registry says; they and gpt-5 take
 * `max_completion_tokens` in place of `max_tokens`. These were core's `DefaultRequestTransformer`
 * defaults until they moved here, so other providers' clients no longer apply them.
 *
 * The GPT 5.6, 6 and 6.1 models restrict tool calls on Chat Completions, the only API `OpenAIClient` speaks
 * ([[toolsOnChatCompletions]]): they call functions there only with `reasoning_effort: none`, and answer anything
 * else, a request that leaves the field out included, with a 400 naming the Responses API (requests sent to
 * `gpt-5.6-luna`, `gpt-5.6-terra`, `gpt-6-luna` and `gpt-6.1-terra` on 2026-11-25, #2940).
 */
private[llm4s] object OpenAIModelRules {

  /** The request transformer `OpenAIClient` uses: the registry's capabilities, adjusted by [[adjust]]. */
  def transformer(service: ModelRegistryService): RequestTransformer =
    RequestTransformer.adjusted(service)((modelId, capabilities) => adjust(modelId.asString, capabilities))

  /** Applies the o-series constraints on top of `capabilities`; other models pass through. */
  def adjust(modelId: String, capabilities: ModelCapabilities): ModelCapabilities =
    if (isOSeriesModel(modelId))
      capabilities
        .withSupportsReasoning(capabilities.supportsReasoning.orElse(Some(true)))
        .withSupportsNativeStreaming(Some(false))
        .withSupportsSystemMessages(Some(false))
        .withTemperatureConstraint(Some((1.0, 1.0)))
        .withDisallowedParams(Some(Set("top_p", "presence_penalty", "frequency_penalty", "logprobs")))
    else capabilities

  /**
   * Whether the model takes `max_completion_tokens` instead of `max_tokens`: the o-series, and GPT from 5 on
   * (`gpt-5.6-luna`, `gpt-6-luna`, `gpt-6.1-terra`), bare, prefixed or as a fine-tune's base.
   */
  def requiresMaxCompletionTokens(modelId: String): Boolean =
    isOSeriesModel(modelId) || GptFiveOrLater.findFirstIn(modelId.toLowerCase).isDefined

  /** `gpt-5` to `gpt-9`, with or without the hyphen, not followed by another digit (so not Azure's `gpt-35-turbo`). */
  private val GptFiveOrLater = "gpt-?[5-9](?!\\d)".r

  /** How a model may call tools on Chat Completions. */
  enum ToolCalls {

    /** As any model: no restriction. */
    case Unrestricted

    /** Only with `reasoning_effort: none`, which the client then sends whatever was asked. */
    case OnlyWithoutReasoning
  }

  /**
   * How `modelId` may call tools on Chat Completions: a model of the 5.6, 6 or 6.1 generation only with
   * `reasoning_effort: none`. The rule is by generation, not by variant: every model tried had it, and a model
   * wrongly included still calls its tools, where one wrongly left out fails every request with tools. A dated
   * snapshot, a router prefix (`openai/`) and a fine-tune are judged by the model named.
   */
  def toolsOnChatCompletions(modelId: String): ToolCalls = {
    val name = OpenAIReasoning.baseModel(modelId).trim.toLowerCase.split('/').last
    if (ToolsOnlyWithoutReasoning.matches(name)) ToolCalls.OnlyWithoutReasoning else ToolCalls.Unrestricted
  }

  /** `gpt-5.6`, `gpt-6` or `gpt-6.1`, bare or with a variant and snapshot (`gpt-6.1-terra-2026-09-08`). */
  private val ToolsOnlyWithoutReasoning = "gpt-(5\\.6|6|6\\.1)(-.*)?".r

  /**
   * The `reasoning_effort` a request with tools must send to `modelId`, and what that overrides of what was asked;
   * `None` when the model takes any effort with tools. For a model that calls tools
   * [[ToolCalls.OnlyWithoutReasoning]] it is `none`: the asked effort, or the model's default when none was asked, is reported `Adjusted`, as is a `thinking` other than `Off`.
   */
  def forcedToolEffort(
    modelId: String,
    options: CompletionOptions
  ): Option[(SdkReasoningEffort, List[RequestAdjustment])] =
    Option.when(options.tools.nonEmpty && toolsOnChatCompletions(modelId) == ToolCalls.OnlyWithoutReasoning) {
      val reason = s"$modelId calls tools on Chat Completions only with reasoning_effort none"
      // `thinking = Off` alone already asks for no reasoning: nothing was overridden
      val askedNoReasoning = options.reasoning.isEmpty && options.thinking.contains(Thinking.Off)
      val reasoning = Option.unless(askedNoReasoning)(
        RequestAdjustment.Adjusted(
          RequestOption.Reasoning,
          options.reasoning.fold("model default")(_.name),
          "none",
          reason
        )
      )
      val thinking = options.thinking.toList.collect {
        case t if t != Thinking.Off => RequestAdjustment.Adjusted(RequestOption.Thinking, t.toString, "Off", reason)
      }
      (SdkReasoningEffort.NONE, reasoning.toList ++ thinking)
    }

  /** An o-series reasoning model, by name, bare or vendor-prefixed (`openai/o1`). */
  def isOSeriesModel(modelId: String): Boolean = {
    val normalized = modelId.toLowerCase
    normalized.startsWith("o1") || normalized.startsWith("o3") ||
    normalized.contains("/o1") || normalized.contains("/o3")
  }
}
