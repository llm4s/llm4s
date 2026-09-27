package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.MistralConfig
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.Try

/**
 * Mistral AI client, over Mistral's OpenAI-format `/v1/chat/completions` API.
 *
 * An [[OpenAICompatibleClient]] with [[MistralDialect]], so it has everything the shared
 * client has: completion, streaming (including streamed tool calls and usage), tool calling,
 * `response_format` (JSON object and JSON schema) and one exchange recorded per call. Until
 * [[https://github.com/llm4s/llm4s/issues/1132 #1132]] it was a separate non-streaming client
 * whose `streamComplete` returned a `Left` ([[https://github.com/llm4s/llm4s/issues/925 #925]])
 * and which refused tool messages.
 *
 * Requests go to `<baseUrl>/v1/chat/completions`, where `baseUrl` is the API root
 * (`https://api.mistral.ai` by default); see [[org.llm4s.llmconnect.config.MistralConfig.apiBaseUrl]].
 *
 * @param config          Mistral configuration: API key, model, base URL and context settings.
 * @param metrics         receives per-call latency and token-usage events.
 * @param exchangeLogging where raw request/response exchanges are recorded, if anywhere.
 */
class MistralClient(
  config: MistralConfig,
  metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using ModelRegistryService)
    extends OpenAICompatibleClient(
      OpenAICompatibleClient.Settings(
        providerName = "mistral",
        displayName = "Mistral",
        model = config.model,
        baseUrl = MistralConfig.apiBaseUrl(config.baseUrl),
        apiKey = Some(config.apiKey),
        contextWindow = config.contextWindow,
        reserveCompletion = config.reserveCompletion
      ),
      MistralDialect,
      metrics,
      exchangeLogging
    )

object MistralClient {

  def apply(config: MistralConfig)(using ModelRegistryService): Result[MistralClient] =
    Try(new MistralClient(config)).toResult

  def apply(config: MistralConfig, metrics: MetricsCollector)(using ModelRegistryService): Result[MistralClient] =
    Try(new MistralClient(config, metrics)).toResult

  def apply(
    config: MistralConfig,
    metrics: MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[MistralClient] =
    Try(new MistralClient(config, metrics, exchangeLogging)).toResult
}

/**
 * Mistral's departures from the standard chat-completions format.
 *
 *  - '''Tool-call ids.''' Mistral rejects a request whose tool-call id is not exactly nine
 *    ASCII letters or digits ("Tool call id was ... but must be a-z, A-Z, 0-9, with a length
 *    of 9"). Its own ids already have that shape and pass through unchanged; any other id -
 *    one from a different provider earlier in the conversation, or one a caller made up - is
 *    mapped to a stable nine-character id derived from it, so a call and its answer still
 *    match.
 *  - '''Empty assistant turns.''' Mistral rejects an assistant message with neither
 *    `content` nor `tool_calls`, so such a turn is left out of the request, as the old client
 *    did.
 *  - '''Content as chunks.''' A reasoning (Magistral) model returns `content` as an array of
 *    chunks rather than a string: `{"type":"thinking","thinking":[{"type":"text",...}]}`
 *    for its reasoning and `{"type":"text","text":...}` for the answer. Streamed deltas take
 *    either shape. The text chunks are the content; the thinking chunks are `thinking`, on a
 *    completion and as streamed thinking deltas.
 *
 * Everything else - roles, `tools`, `response_format`, `usage`, SSE framing with `[DONE]`,
 * streamed tool calls with an `index` - is the standard format. `CompletionOptions.reasoning`
 * is not sent: Mistral's `reasoning_effort` is accepted only by some models, and sending it
 * to the rest would turn a setting documented as "ignored for non-reasoning models" into an
 * error.
 */
private[llm4s] object MistralDialect extends OpenAICompatibleDialect:

  private val MistralToolCallId = "^[a-zA-Z0-9]{9}$".r
  private val Alphabet          = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

  override val sendEmptyAssistantTurns: Boolean = false

  /**
   * Not sent. `stream_options` is not in Mistral's chat-completions schema, and Mistral rejects
   * a request carrying a field outside it with a 422 ("Extra inputs are not permitted"). It
   * reports usage on a stream's last event unasked.
   */
  override val streamUsageOption: Boolean = false

  override def encodeToolCallId(id: String): String =
    if MistralToolCallId.matches(id) then id
    else {
      val digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8))
      digest.iterator.take(9).map(b => Alphabet.charAt((b & 0xff) % Alphabet.length)).mkString
    }

  override def decodeContent(content: ujson.Value): Option[String] =
    content.strOpt.orElse(content.arrOpt.flatMap { chunks =>
      val text = chunks.iterator.filter(chunkType(_).contains("text")).flatMap(chunkText).mkString
      Option.when(text.nonEmpty)(text)
    })

  override def thinking(obj: ujson.Value): Option[String] =
    obj.objOpt.flatMap(_.get("content")).flatMap(_.arrOpt).flatMap { chunks =>
      val text = chunks.iterator
        .filter(chunkType(_).contains("thinking"))
        .flatMap(_.obj.get("thinking"))
        .flatMap(inner => inner.strOpt.orElse(inner.arrOpt.map(_.iterator.flatMap(chunkText).mkString)))
        .mkString
      Option.when(text.nonEmpty)(text)
    }

  private def chunkType(chunk: ujson.Value): Option[String] =
    chunk.objOpt.flatMap(_.get("type")).flatMap(_.strOpt)

  private def chunkText(chunk: ujson.Value): Option[String] =
    chunk.objOpt.flatMap(_.get("text")).flatMap(_.strOpt)
