package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.{ ZaiConfig, ProviderConfig }
import org.llm4s.llmconnect.model.ThinkingBlock
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * LLM client for the Z.ai API.
 *
 * Z.ai uses an OpenAI-compatible `/chat/completions` endpoint with one important
 * difference: message content is always an array of typed objects
 * (`[{"type":"text","text":"..."}]`) rather than a plain string.  This applies
 * to user, system, assistant, and tool messages alike.  Sending a plain string
 * causes a rejection from the Z.ai API.
 *
 * Both non-streaming (`complete`) and streaming (`streamComplete`) are supported.
 * Tool calling follows the standard OpenAI function-calling format.
 *
 * An [[OpenAICompatibleClient]] with [[ZaiDialect]].
 *
 * @param config  Z.ai connection configuration (API key, model, base URL, context window)
 * @param metrics records per-call latency and token-usage events;
 *                use [[org.llm4s.metrics.MetricsCollector.noop]] when metrics are not needed
 * @param exchangeLogging where raw request/response exchanges are recorded, if anywhere
 */
@Stable
class ZaiClient(
  config: ZaiConfig,
  metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using ModelRegistryService)
    extends OpenAICompatibleClient(
      OpenAICompatibleClient.Settings(
        providerName = "zai",
        displayName = "Z.ai",
        model = config.model,
        baseUrl = config.baseUrl,
        credential = OpenAICompatibleClient.Credential.Static(config.apiKey),
        contextWindow = config.contextWindow,
        reserveCompletion = config.reserveCompletion
      ),
      ZaiDialect,
      metrics,
      exchangeLogging
    )

object ZaiClient {

  def apply(
    config: ZaiConfig,
    metrics: MetricsCollector = MetricsCollector.noop
  )(using ModelRegistryService): Result[ZaiClient] =
    ProviderConfig.nonEmpty("Z.ai", "apiKey", config.apiKey).flatMap(_ => Try(new ZaiClient(config, metrics)).toResult)

  def apply(
    config: ZaiConfig,
    metrics: MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[ZaiClient] =
    ProviderConfig
      .nonEmpty("Z.ai", "apiKey", config.apiKey)
      .flatMap(_ => Try(new ZaiClient(config, metrics, exchangeLogging)).toResult)
}

/**
 * Z.ai's departures from the standard format: every message's text is sent as
 * an array of text parts, and a reply's `content` - in a completion or a
 * streamed delta - may come back either as a string or as such an array. A GLM thinking
 * model's reasoning is its `reasoning_content`, read as thinking and sent back unchanged on the
 * assistant turn, as Z.ai's thinking-mode guide asks (preserved thinking, and tool calls).
 */
private[llm4s] object ZaiDialect extends OpenAICompatibleDialect:
  override val headers: Seq[(String, String)] = Seq("User-Agent" -> "llm4s-coding-assistant/1.0")

  /**
   * Not sent. Z.ai's chat-completion reference has no `stream_options` parameter; it documents
   * `usage` as returned when the call ends.
   */
  override val streamUsageOption: Boolean = false

  override def encodeContent(text: String): ujson.Value =
    ujson.Arr(ujson.Obj("type" -> "text", "text" -> ujson.Str(text)))

  override def decodeContent(content: ujson.Value): Option[String] =
    content.strOpt.orElse(
      content.arrOpt.flatMap(_.headOption).flatMap(_.objOpt).flatMap(_.get("text")).flatMap(_.strOpt)
    )

  override def thinking(obj: ujson.Value): Option[String] =
    OpenAICompatibleDialect.firstString(obj, "reasoning_content").filter(_.nonEmpty)

  override def encodeThinking(message: ujson.Obj, thinking: Seq[ThinkingBlock]): Unit =
    ThinkingBlock.text(thinking).foreach(text => message("reasoning_content") = text)
