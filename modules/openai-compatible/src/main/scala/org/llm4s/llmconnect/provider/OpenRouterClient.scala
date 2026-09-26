package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort, ToolCall }
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
 * @param config  `OpenAIConfig` whose `baseUrl` must contain `"openrouter.ai"`;
 *                carries the API key and model name.
 * @param metrics Receives per-call latency and token-usage events.
 *                Defaults to `MetricsCollector.noop`.
 * @param exchangeLogging where raw request/response exchanges are recorded, if anywhere
 */
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
        apiKey = Some(config.apiKey),
        contextWindow = config.contextWindow,
        reserveCompletion = config.reserveCompletion
      ),
      OpenRouterDialect,
      metrics,
      exchangeLogging
    )

object OpenRouterClient {

  /**
   * Constructs an [[OpenRouterClient]], wrapping any construction-time
   * exception in a `Left`.
   *
   * @param config  `OpenAIConfig` with the OpenRouter API key, model, and
   *                a `baseUrl` that contains `"openrouter.ai"`.
   * @param metrics Receives per-call latency and token-usage events.
   *                Defaults to `MetricsCollector.noop`.
   * @return `Right(client)` on success; `Left(LLMError)` if construction fails.
   */
  def apply(
    config: OpenAIConfig,
    metrics: MetricsCollector = MetricsCollector.noop
  )(using ModelRegistryService): Result[OpenRouterClient] =
    Try(new OpenRouterClient(config, metrics)).toResult

  def apply(
    config: OpenAIConfig,
    metrics: MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[OpenRouterClient] =
    Try(new OpenRouterClient(config, metrics, exchangeLogging)).toResult
}

/**
 * OpenRouter's departures from the standard format:
 *
 *  - the `HTTP-Referer` and `X-Title` headers its usage policy asks for;
 *  - an assistant turn always carries `content`, `""` or `null` when it has no text;
 *  - reasoning requested per underlying model family (see [[addReasoning]]);
 *  - thinking read from `thinking` or `reasoning` (on the message, then the
 *    choice, and on a streamed delta), and `usage.reasoning_tokens`;
 *  - strict tool-call parsing: a call missing its `id` or `name`, or with
 *    unparseable arguments, fails the completion rather than being defaulted.
 */
private[llm4s] object OpenRouterDialect extends OpenAICompatibleDialect:
  override val headers: Seq[(String, String)] =
    Seq("HTTP-Referer" -> "https://github.com/llm4s/llm4s", "X-Title" -> "LLM4S")

  override val alwaysSendAssistantContent: Boolean = true

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

  override def reasoningTokens(usage: ujson.Value): Option[Int] =
    usage.obj.get("reasoning_tokens").flatMap(_.numOpt).map(_.toInt)

  override def parseToolCalls(toolCalls: ujson.Value): Seq[ToolCall] =
    StandardToolCallDeserializer.deserializeToolCalls(toolCalls)
