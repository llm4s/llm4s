package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Experimental
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model.ResponseFormat
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.syntax.toResult
import org.llm4s.types.Result

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  httpClient: Llm4sHttpClient = Llm4sHttpClient.FromConfig
)(using ModelRegistryService)
    extends OpenAICompatibleClient(
      OpenAICompatibleClient.Settings(
        providerName = "cohere",
        displayName = "Cohere",
        model = config.model.asString,
        baseUrl = CohereConfig.compatibilityBaseUrl(config.baseUrl),
        credential = OpenAICompatibleClient.keyCredential(config.apiKey, config.apiKeyProvider),
        contextWindow = config.contextWindow,
        reserveCompletion = config.reserveCompletion,
        timeouts = config.timeouts,
        network = config.network
      ),
      CohereDialect,
      metrics,
      exchangeLogging,
      httpClient
    )

@Experimental
object CohereClient {

  def apply(config: CohereConfig)(using ModelRegistryService): Result[CohereClient] =
    OpenAICompatibleClient
      .requireKey("Cohere", config.apiKey, config.apiKeyProvider)
      .flatMap(_ => Try(new CohereClient(config)).toResult)

  def apply(config: CohereConfig, metrics: MetricsCollector)(using ModelRegistryService): Result[CohereClient] =
    OpenAICompatibleClient
      .requireKey("Cohere", config.apiKey, config.apiKeyProvider)
      .flatMap(_ => Try(new CohereClient(config, metrics)).toResult)

  def apply(
    config: CohereConfig,
    metrics: MetricsCollector,
    exchangeLogging: ProviderExchangeLogging,
    httpClient: Llm4sHttpClient = Llm4sHttpClient.FromConfig
  )(using ModelRegistryService): Result[CohereClient] =
    OpenAICompatibleClient
      .requireKey("Cohere", config.apiKey, config.apiKeyProvider)
      .flatMap(_ => Try(new CohereClient(config, metrics, exchangeLogging, httpClient)).toResult)
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

  /**
   * `GET <root>/v1/models/<model>`, Cohere's native "Get a Model" request, where `<root>` is the API root the
   * Compatibility base URL sits under (https://docs.cohere.com/reference/get-model, read 2026-11-24). The
   * Compatibility API itself documents chat completions and embeddings only, so it has no such request; the native
   * one takes the same bearer key, answers 401 to a wrong one and 404 to a model it does not have.
   */
  override def validationUrl(baseUrl: String, model: String): Option[String] =
    Option.when(baseUrl.endsWith(CohereConfig.COMPATIBILITY_PATH)) {
      val root = baseUrl.stripSuffix(CohereConfig.COMPATIBILITY_PATH)
      s"$root/v1/models/${URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20")}"
    }

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
