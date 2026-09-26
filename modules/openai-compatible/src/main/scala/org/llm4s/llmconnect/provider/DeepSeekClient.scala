package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.DeepSeekConfig
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * DeepSeek LLM client implementation using the OpenAI-compatible API.
 *
 * Provides access to DeepSeek models including DeepSeek-Chat (V3) and
 * DeepSeek-Reasoner (R1) for advanced reasoning tasks.
 *
 * An [[OpenAICompatibleClient]] with [[DeepSeekDialect]]: the standard
 * chat-completions format plus DeepSeek's `User-Agent`, and
 * `reasoning_content` - the reasoner's chain of thought - read back as
 * `Completion.thinking` and as streamed thinking deltas.
 *
 * @param config DeepSeek configuration containing API key, model, base URL, and context settings
 * @param metrics MetricsCollector for recording request metrics
 * @param exchangeLogging where raw request/response exchanges are recorded, if anywhere
 */
class DeepSeekClient(
  config: DeepSeekConfig,
  metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using ModelRegistryService)
    extends OpenAICompatibleClient(
      OpenAICompatibleClient.Settings(
        providerName = "deepseek",
        displayName = "DeepSeek",
        model = config.model,
        baseUrl = config.baseUrl,
        apiKey = Some(config.apiKey),
        contextWindow = config.contextWindow,
        reserveCompletion = config.reserveCompletion
      ),
      DeepSeekDialect,
      metrics,
      exchangeLogging
    )

object DeepSeekClient {

  def apply(
    config: DeepSeekConfig,
    metrics: MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[DeepSeekClient] =
    Try(new DeepSeekClient(config, metrics, exchangeLogging)).toResult

  def apply(config: DeepSeekConfig, metrics: MetricsCollector)(using ModelRegistryService): Result[DeepSeekClient] =
    Try(new DeepSeekClient(config, metrics)).toResult

  def apply(config: DeepSeekConfig)(using ModelRegistryService): Result[DeepSeekClient] =
    Try(new DeepSeekClient(config, MetricsCollector.noop)).toResult
}

/**
 * DeepSeek's departures from the standard format: a `User-Agent`, and the
 * reasoner's `reasoning_content`, which is its thinking. The old
 * `DeepSeekClient` ignored `reasoning_content`, so `deepseek-reasoner`'s
 * thinking was dropped ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 * DeepSeek reports reasoning tokens under `completion_tokens_details`.
 */
private[llm4s] object DeepSeekDialect extends OpenAICompatibleDialect:
  override val headers: Seq[(String, String)] = Seq("User-Agent" -> "llm4s/1.0")

  override def thinking(obj: ujson.Value): Option[String] =
    OpenAICompatibleDialect.firstString(obj, "reasoning_content")

  override def reasoningTokens(usage: ujson.Value): Option[Int] =
    usage.obj
      .get("completion_tokens_details")
      .flatMap(_.objOpt)
      .flatMap(_.get("reasoning_tokens"))
      .flatMap(_.numOpt)
      .map(_.toInt)
