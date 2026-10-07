package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort, ThinkingBlock, ToolCall }
import org.llm4s.llmconnect.serialization.StandardToolCallDeserializer
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * [[org.llm4s.llmconnect.LLMClient]] implementation for the OpenRouter unified model gateway.
 *
 * Sends requests to the OpenRouter REST API using the OpenAI-compatible
 * `/chat/completions` endpoint. Accepts `OpenAIConfig` — there is no
 * separate `OpenRouterConfig`; `LLMConnect` detects OpenRouter by checking
 * whether `baseUrl` contains `"openrouter.ai"` and routes accordingly.
 *
 * An [[OpenAICompatibleClient]] with [[OpenRouterDialect]].
 *
 * == Required headers ==
 *
 * OpenRouter's usage policy requires two additional headers on every
 * request. This client sends them automatically:
 *  - `HTTP-Referer: https://github.com/llm4s/llm4s`
 *  - `X-Title: LLM4S`
 *
 * == Reasoning / extended thinking ==
 *
 * Model type is detected by substring matching on the lower-cased model name:
 *  - Names containing `"claude"` or `"anthropic"` → Anthropic-style
 *    `thinking` object (`type: "enabled"`, `budget_tokens`).
 *  - Names containing `"o1"`, `"o3"`, or `"o4"` → OpenAI-style
 *    `reasoning_effort` string parameter.
 *  - All other models → reasoning configuration is silently omitted.
 *
 * The thinking budget is clamped to `[1024, maxTokens - 1]` for Anthropic
 * models, matching the Anthropic API constraint.
 *
 * == Thinking content ==
 *
 * Extended thinking text is extracted from whichever field the model
 * populates: `message.thinking`, `message.reasoning`, or
 * `choice.thinking` (checked in that order).
 *
 * When the underlying model returns signed, summarised or encrypted reasoning (Claude, Gemini,
 * OpenAI reasoning models), OpenRouter also returns it as `message.reasoning_details`, and
 * requires the whole sequence back unchanged when a conversation continues after tool calls. Each
 * item is kept as a sealed [[org.llm4s.llmconnect.model.ThinkingBlock.Opaque]] block beside the
 * reasoning text, bound to the request it answered, and sent back as `reasoning_details` with the
 * same order and fields while the conversation before it is unchanged; once it changes (pruned,
 * compressed, edited) the details are dropped and only the `reasoning` text is sent.
 *
 * @param config  `OpenAIConfig` whose `baseUrl` must contain `"openrouter.ai"`;
 *                carries the API key and model name. A config with `workloadIdentity` or a
 *                blank `apiKey` is refused with an `IllegalArgumentException`.
 * @param metrics Receives per-call latency and token-usage events.
 *                Defaults to `MetricsCollector.noop`.
 * @param exchangeLogging where raw request/response exchanges are recorded, if anywhere
 */
@Stable
class OpenRouterClient(
  config: OpenAIConfig,
  metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using ModelRegistryService)
    extends OpenAICompatibleClient(
      OpenAICompatibleClient.Settings(
        providerName = "openrouter",
        displayName = "OpenRouter",
        model = config.model,
        baseUrl = config.baseUrl,
        credential = OpenRouterClient.credential(config),
        contextWindow = config.contextWindow,
        reserveCompletion = config.reserveCompletion
      ),
      OpenRouterDialect,
      metrics,
      exchangeLogging
    )

object OpenRouterClient {

  private val WorkloadIdentityRefusal: String =
    "OpenRouter does not support workloadIdentity: OpenAI workload identity mints an OpenAI credential, which " +
      "OpenRouter does not accept - set apiKey to an OpenRouter key instead"

  /**
   * The bearer credential for `config`, refusing what OpenRouter cannot use: `workloadIdentity`, whose
   * exchanged token is an OpenAI credential, and a blank `apiKey` (refused by [[OpenAICompatibleClient]]).
   * The constructor calls this, so a config passed to it directly is refused rather than its identity being
   * dropped in favour of an empty static key.
   */
  private def credential(config: OpenAIConfig): OpenAICompatibleClient.Credential =
    if (config.workloadIdentity.isDefined) throw new IllegalArgumentException(WorkloadIdentityRefusal)
    OpenAICompatibleClient.Credential.Static(config.apiKey)

  /** `config` unless it carries `workloadIdentity`, which OpenRouter cannot use; then the rules of `OpenAIConfig.validate`. */
  private def validate(config: OpenAIConfig): Result[OpenAIConfig] =
    Either
      .cond(
        config.workloadIdentity.isEmpty,
        config,
        ConfigurationError(WorkloadIdentityRefusal, List("workloadIdentity"))
      )
      .flatMap(OpenAIConfig.validate)

  /**
   * Constructs an [[OpenRouterClient]], wrapping any construction-time
   * exception in a `Left`.
   *
   * @param config  `OpenAIConfig` with the OpenRouter API key, model, and
   *                a `baseUrl` that contains `"openrouter.ai"`.
   * @param metrics Receives per-call latency and token-usage events.
   *                Defaults to `MetricsCollector.noop`.
   * @return `Right(client)` on success; `Left(ConfigurationError)` for a config with
   *         `workloadIdentity` or a blank `apiKey`; `Left(LLMError)` if construction fails.
   */
  def apply(
    config: OpenAIConfig,
    metrics: MetricsCollector = MetricsCollector.noop
  )(using ModelRegistryService): Result[OpenRouterClient] =
    validate(config).flatMap(valid => Try(new OpenRouterClient(valid, metrics)).toResult)

  def apply(
    config: OpenAIConfig,
    metrics: MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[OpenRouterClient] =
    validate(config).flatMap(valid => Try(new OpenRouterClient(valid, metrics, exchangeLogging)).toResult)
}

/**
 * OpenRouter's departures from the standard format:
 *
 *  - the `HTTP-Referer` and `X-Title` headers its usage policy asks for;
 *  - an assistant turn always carries `content`, `""` or `null` when it has no text;
 *  - reasoning requested per underlying model family (see [[addReasoning]]);
 *  - thinking read from `thinking` or `reasoning` (on the message, then the
 *    choice, and on a streamed delta), and `usage.reasoning_tokens`; an assistant turn's
 *    thinking sent back as its `reasoning`, which OpenRouter documents for preserving reasoning;
 *  - `reasoning_details` read whole or streamed into opaque blocks and sent back unchanged (see
 *    [[decodeThinkingDetails]] and [[encodeThinking]]);
 *  - strict tool-call parsing: a call missing its `id` or `name`, or with
 *    unparseable arguments, fails the completion rather than being defaulted.
 */
private[llm4s] object OpenRouterDialect extends OpenAICompatibleDialect:

  /** The provider id on the [[ThinkingBlock.Opaque]] blocks that hold OpenRouter's `reasoning_details`. */
  val ProviderId: String = "openrouter"

  override val headers: Seq[(String, String)] =
    Seq("HTTP-Referer" -> "https://github.com/llm4s/llm4s", "X-Title" -> "LLM4S")

  override val alwaysSendAssistantContent: Boolean = true

  /**
   * Not sent. OpenRouter documents `stream_options.include_usage` as deprecated with no effect:
   * full usage is always included on a stream.
   */
  override val streamUsageOption: Boolean = false

  /**
   * Model type is detected by substring matching on the lower-cased model name.
   * Anthropic models receive a `thinking` object; OpenAI o1/o3/o4 models receive
   * `reasoning_effort`; all others are left unchanged (reasoning silently ignored).
   * An explicit thinking budget with no effort set also enables Anthropic thinking.
   */
  override def addReasoning(body: ujson.Obj, model: String, options: CompletionOptions): Unit = {
    val modelLower       = model.toLowerCase
    val isAnthropicModel = modelLower.contains("claude") || modelLower.contains("anthropic")
    val isOpenAIReasoningModel =
      modelLower.contains("o1") || modelLower.contains("o3") || modelLower.contains("o4")

    def anthropicThinking(budgetTokens: Int): ujson.Obj = {
      val maxTokens = options.maxTokens.getOrElse(2048)
      ujson.Obj("type" -> "enabled", "budget_tokens" -> math.max(1024, math.min(budgetTokens, maxTokens - 1)))
    }

    options.reasoning match {
      case Some(effort) if effort != ReasoningEffort.None =>
        if (isAnthropicModel)
          body("thinking") = anthropicThinking(
            options.effectiveBudgetTokens.getOrElse(ReasoningEffort.defaultBudgetTokens(effort))
          )
        else if (isOpenAIReasoningModel) body("reasoning_effort") = effort.name
      case Some(_) => ()
      case None =>
        options.budgetTokens.filter(_ > 0).foreach { budget =>
          if (isAnthropicModel) body("thinking") = anthropicThinking(budget)
        }
    }
  }

  override def thinking(obj: ujson.Value): Option[String] =
    OpenAICompatibleDialect.firstString(obj, "thinking", "reasoning")

  /**
   * The turn's thinking text as `reasoning`, and its `reasoning_details` - the [[ThinkingBlock.Opaque]]
   * blocks this dialect read, in order and exactly as received - when it still has them. A turn
   * unsealed since (its history or content changed) has only its text, and sends only `reasoning`.
   * Opaque blocks from any other provider are ignored.
   */
  override def encodeThinking(message: ujson.Obj, thinking: Seq[ThinkingBlock]): Unit = {
    ThinkingBlock.text(thinking).foreach(text => message("reasoning") = text)
    val details = thinking.collect { case ThinkingBlock.Opaque(OpenRouterDialect.ProviderId, data) => ujson.read(data) }
    if (details.nonEmpty) message("reasoning_details") = ujson.Arr.from(details)
  }

  override def thinkingDetails(obj: ujson.Value): Seq[ujson.Value] =
    obj.objOpt.flatMap(_.get("reasoning_details")).flatMap(_.arrOpt).toSeq.flatten.filter(_.objOpt.isDefined)

  /**
   * One [[ThinkingBlock.Opaque]] per `reasoning_details` item, holding the item's JSON with every
   * field (`type`, `text`, `summary`, `data`, `signature`, `id`, `format`, `index` and any other).
   *
   * A stream sends an item in fragments that share its `index` and `type`: the `text` and `summary`
   * fragments are concatenated, and any other field takes the latest non-null value it is sent
   * with (a signature, say, arrives on a later fragment than the text). An item with no `index`, or
   * whose `type` differs from the item before at that index, starts a new item. A non-streaming
   * reply's items, each with its own index, come through unchanged.
   */
  override def decodeThinkingDetails(details: Seq[ujson.Value]): Seq[ThinkingBlock] = {
    val items = scala.collection.mutable.ArrayBuffer.empty[ujson.Obj]
    // the position in `items` of the latest item at each index
    val byIndex = scala.collection.mutable.Map.empty[Int, Int]
    details.flatMap(_.objOpt).foreach { fragment =>
      val index = fragment.get("index").flatMap(_.numOpt).map(_.toInt)
      val kind  = fragment.get("type").flatMap(_.strOpt)
      index.flatMap(byIndex.get).filter(i => items(i).value.get("type").flatMap(_.strOpt) == kind) match {
        case Some(position) =>
          val item = items(position)
          fragment.foreach {
            case (key @ ("text" | "summary"), ujson.Str(more)) =>
              item(key) = item.value.get(key).flatMap(_.strOpt).getOrElse("") + more
            case (_, ujson.Null) => ()
            case (key, value)    => item(key) = value
          }
        case None =>
          items += ujson.Obj.from(fragment)
          index.foreach(byIndex(_) = items.length - 1)
      }
    }
    items.toSeq.map(item => ThinkingBlock.Opaque(OpenRouterDialect.ProviderId, item.render()))
  }

  override def reasoningTokens(usage: ujson.Value): Option[Int] =
    usage.obj.get("reasoning_tokens").flatMap(_.numOpt).map(_.toInt)

  override def parseToolCalls(toolCalls: ujson.Value): Seq[ToolCall] =
    StandardToolCallDeserializer.deserializeToolCalls(toolCalls)
