package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.ZaiConfig
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
        apiKey = Some(config.apiKey),
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
    Try(new ZaiClient(config, metrics)).toResult

  def apply(
    config: ZaiConfig,
    metrics: MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[ZaiClient] =
    Try(new ZaiClient(config, metrics, exchangeLogging)).toResult
}

/**
 * Z.ai's departures from the standard format: every message's text is sent as
 * an array of text parts, and a reply's `content` - in a completion or a
 * streamed delta - may come back either as a string or as such an array.
 */
private[llm4s] object ZaiDialect extends OpenAICompatibleDialect:
  override val headers: Seq[(String, String)] = Seq("User-Agent" -> "llm4s-coding-assistant/1.0")

  override def encodeContent(text: String): ujson.Value =
    ujson.Arr(ujson.Obj("type" -> "text", "text" -> ujson.Str(text)))

  override def decodeContent(content: ujson.Value): Option[String] =
    content.strOpt.orElse(
      content.arrOpt.flatMap(_.headOption).flatMap(_.objOpt).flatMap(_.get("text")).flatMap(_.strOpt)
    )
