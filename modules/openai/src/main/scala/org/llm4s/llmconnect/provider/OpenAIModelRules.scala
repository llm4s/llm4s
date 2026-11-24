package org.llm4s.llmconnect.provider

import com.openai.models.{ ReasoningEffort => SdkReasoningEffort }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ CompletionOptions, RequestAdjustment, RequestOption, Thinking }
import org.llm4s.model.{ ModelCapabilities, ModelRegistryService, RequestTransformer }
import org.llm4s.types.Result

/**
 * OpenAI's request rules that follow from model naming rather than from registry data.
 *
 * The o-series reasoning models take no system message, no native streaming, temperature 1
 * only and no sampling penalties, whatever the registry says; they and gpt-5 take
 * `max_completion_tokens` in place of `max_tokens`. These were core's `DefaultRequestTransformer`
 * defaults until they moved here, so other providers' clients no longer apply them.
 *
 * Some models restrict tool calls on Chat Completions, the only API `OpenAIClient` speaks ([[toolsOnChatCompletions]]):
 * `gpt-6-luna` calls functions there only with `reasoning_effort: none`, and `gpt-6.1-terra` calls tools only through
 * the Responses API (OpenAI's model pages, checked 2026-11-24).
 */
private[llm4s] object OpenAIModelRules {

  /** The request transformer `OpenAIClient` uses: the registry's capabilities, adjusted by [[adjust]]. */
  def transformer(service: ModelRegistryService): RequestTransformer =
    RequestTransformer.adjusted(service)(adjust)

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

    /** Not at all: tool calls need the Responses API, which `OpenAIClient` does not speak. */
    case ResponsesApiOnly
  }

  /**
   * How `modelId` may call tools on Chat Completions, by OpenAI's model pages: `gpt-6-luna` only with
   * `reasoning_effort: none`, `gpt-6.1-terra` only through the Responses API. A dated snapshot, a router prefix
   * (`openai/`) and a fine-tune are judged by the model named.
   */
  def toolsOnChatCompletions(modelId: String): ToolCalls = {
    val name = OpenAIReasoning.baseModel(modelId).trim.toLowerCase.split('/').last
    def is(family: String) =
      name == family || name.startsWith(family + "-") && name.drop(family.length).matches(Snapshot)
    if (is("gpt-6-luna")) ToolCalls.OnlyWithoutReasoning
    else if (is("gpt-6.1-terra")) ToolCalls.ResponsesApiOnly
    else ToolCalls.Unrestricted
  }

  /** A dated snapshot's suffix, `-2026-09-08`. */
  private val Snapshot = "-\\d{4}-\\d{2}-\\d{2}"

  /**
   * Refuses a request with tools to a model that calls tools only through the Responses API: sent over Chat
   * Completions, it would fail at OpenAI or call no tool. Any other request passes.
   */
  def checkTools(modelId: String, options: CompletionOptions): Result[Unit] =
    if (options.tools.nonEmpty && toolsOnChatCompletions(modelId) == ToolCalls.ResponsesApiOnly)
      Left(
        ValidationError(
          "tools",
          s"$modelId calls tools only through OpenAI's Responses API, and llm4s-openai uses Chat Completions: " +
            "use a model that calls tools on Chat Completions (such as gpt-6-luna or gpt-5.6-terra), or send no tools"
        )
      )
    else Right(())

  /**
   * The `reasoning_effort` a request with tools must send to `modelId`, and what that overrides of what was asked;
   * `None` when the model takes any effort with tools. For `gpt-6-luna` it is `none`: the asked effort, or the
   * model's default when none was asked, is reported `Adjusted`, as is a `thinking` other than `Off`.
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
