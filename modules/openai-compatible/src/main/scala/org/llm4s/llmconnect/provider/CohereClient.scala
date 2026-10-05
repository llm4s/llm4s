package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model.ResponseFormat
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * Cohere client, over Cohere's OpenAI-compatibility API
 * (`https://api.cohere.ai/compatibility/v1/chat/completions`).
 *
 * An [[OpenAICompatibleClient]] with [[CohereDialect]], so it has everything the shared client
 * has: completion, streaming (including streamed tool calls and usage), tool calling,
 * structured output and one exchange recorded per call. Until
 * [[https://github.com/llm4s/llm4s/issues/1132 #1132]] it was a separate client for the native
 * v2 `/v2/chat` API that sent text only and whose `streamComplete` returned a `Left`
 * ([[https://github.com/llm4s/llm4s/issues/925 #925]]). The Compatibility API supports chat,
 * streaming, tools and `response_format`, so everything that client did carries over, and the
 * rest comes with the shared client rather than a second SSE parser for Cohere's native event
 * format.
 *
 * Requests go to `<baseUrl>/chat/completions`, where `baseUrl` is mapped through
 * [[org.llm4s.llmconnect.config.CohereConfig.compatibilityBaseUrl]], so a config naming the native root still works.
 *
 * @param config          Cohere configuration: API key, model, base URL and context settings.
 * @param metrics         receives per-call latency and token-usage events.
 * @param exchangeLogging where raw request/response exchanges are recorded, if anywhere.
 */
@Experimental
class CohereClient(
  config: CohereConfig,
  metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using ModelRegistryService)
    extends OpenAICompatibleClient(
      OpenAICompatibleClient.Settings(
        providerName = "cohere",
        displayName = "Cohere",
        model = config.model,
        baseUrl = CohereConfig.compatibilityBaseUrl(config.baseUrl),
        apiKey = Some(config.apiKey),
        contextWindow = config.contextWindow,
        reserveCompletion = config.reserveCompletion
      ),
      CohereDialect,
      metrics,
      exchangeLogging
    )

object CohereClient {

  def apply(config: CohereConfig)(using ModelRegistryService): Result[CohereClient] =
    Try(new CohereClient(config)).toResult

  def apply(config: CohereConfig, metrics: MetricsCollector)(using ModelRegistryService): Result[CohereClient] =
    Try(new CohereClient(config, metrics)).toResult

  def apply(
    config: CohereConfig,
    metrics: MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[CohereClient] =
    Try(new CohereClient(config, metrics, exchangeLogging)).toResult
}

/**
 * Cohere Compatibility API's departures from the standard format, from Cohere's
 * "Using Cohere models via the OpenAI SDK" documentation.
 *
 *  - '''System messages''' go under the `developer` role, which is how that documentation
 *    passes system instructions.
 *  - '''Structured output''' is `{"type": "json_object", "schema": <JSON schema>}` rather
 *    than OpenAI's `json_schema` wrapper; a plain JSON request is `{"type": "json_object"}`.
 *
 * Everything else is the standard format: `tools` and `tool_calls`, `tool` messages, streaming
 * with `[DONE]`, and `usage`. `CompletionOptions.reasoning` is not sent: the Compatibility API
 * accepts only `reasoning_effort` `none` or `high`, and only for reasoning models.
 */
private[llm4s] object CohereDialect extends OpenAICompatibleDialect:

  override val systemRole: String = "developer"

  /**
   * Not sent. `stream_options` is on neither the supported nor the unsupported parameter list
   * of Cohere's Compatibility API documentation, which does not say what an unknown field does.
   */
  override val streamUsageOption: Boolean = false

  override def encodeResponseFormat(format: ResponseFormat): Option[ujson.Value] =
    format match
      case ResponseFormat.Json => Some(ujson.Obj("type" -> "json_object"))
      case ResponseFormat.JsonSchema(schema, _, _) =>
        Some(ujson.Obj("type" -> "json_object", "schema" -> schema))
