package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, CancelledError, LLMError, ProcessingError, ServiceError, ValidationError }
import org.llm4s.http.{ HttpFailures, Llm4sHttpClient }
import org.llm4s.llmconnect.{ BaseLifecycleLLMClient, ProviderExchangeLogging }
import org.llm4s.llmconnect.config.WatsonXConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.media.MediaSource
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.util.{ BoundedJson, Redaction }

import java.io.{ BufferedReader, InputStreamReader }
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.{ Try, Using }

/**
 * [[LLMClient]] for IBM watsonx.ai chat (`POST /ml/v1/text/chat` and `/ml/v1/text/chat_stream`).
 *
 * '''Beta - never run against the live service.''' There is no watsonx account behind this project, so nothing
 * here has been checked against the real service (see https://github.com/llm4s/llm4s/issues/1314). The request
 * and response shapes are those IBM's public pages report, cross-checked against IBM's own open-source client
 * code (see ''Evidence'' below). The module's API is not frozen. It replaces the text-generation endpoints
 * (`/ml/v1/text/generation` and `/generation_stream`), which IBM's February 2026 release notes deprecate
 * without giving a removal date.
 *
 * == Evidence ==
 *
 * A detail is ''SDK-evidenced'' when IBM's own code shows it. That proves IBM's client sends it, which is strong
 * evidence the service accepts it, but it is not a live check. The sources, pinned:
 *  - `NODE`: https://github.com/IBM/watsonx-ai-node-sdk at 47a4a0c (2026-08-05), IBM's Node.js SDK;
 *  - `LCIBM`: https://github.com/langchain-ai/langchain-ibm at 6c32b1d (2026-10-05), IBM's LangChain integration,
 *    `libs/ibm/langchain_ibm/chat_models.py`;
 *  - `PY`: IBM's Python SDK, `ibm-watsonx-ai` 1.8.0 on PyPI;
 *  - `JAVA`: https://github.com/IBM/watsonx-ai-java-sdk at 02246d5 (2026-10-22), IBM's Java SDK, whose test
 *    resources include replies recorded from the service (`modules/watsonx-ai/src/test/...`).
 *
 * The `@Cloud` suite `WatsonXAssumptionProbeSpec` (modules/it) re-checks each of these against a real account and
 * prints which held.
 *
 * == Authentication ==
 *
 * The IBM Cloud API key is exchanged at the IAM endpoint for a bearer token that lives an hour.
 * The token is cached and refreshed lazily, five minutes before it expires.
 *
 * == Request format ==
 *
 * The conversation is sent as structured `messages` with roles (`system`, `user`, `assistant`, `tool`), so
 * content is data and cannot forge a turn: there is no prompt string with role markers, and no stop
 * sequences standing in for them. The model goes in `model_id`, the project or space in `project_id` or
 * `space_id`, and the API version in the `version` query parameter (`WatsonXConfig.apiVersion`, by default the date PY sends:
 * [[org.llm4s.llmconnect.config.WatsonXConfig.DEFAULT_API_VERSION]]).
 * `temperature` is always sent, `max_tokens` when set (SDK-evidenced: NODE types/vml_v1.ts:790-797, a deprecated
 * alias of `max_completion_tokens`), and `top_p` when not 1.0.
 *
 * == Tools ==
 *
 * `CompletionOptions.tools` are sent as `tools` (`type: function`, `function: {name, description,
 * parameters}`; the OpenAI `strict` flag is dropped) with `tool_choice_option: "auto"`. Tool calls in a reply
 * (`message.tool_calls`, whole, or streamed as `delta.tool_calls` pieces merged by `index`) become
 * `ToolCall`s. An assistant turn's calls go back as `tool_calls`, and a [[org.llm4s.llmconnect.model.ToolMessage ToolMessage]] as a `tool` message
 * with its `tool_call_id`. `arguments` is a JSON string on the wire (SDK-evidenced: NODE types/messages.ts:19-24)
 * and a JSON object in a `ToolCall`. A call without an `id` gets a generated one, defensively: the SDK's type makes
 * `id` required (NODE types/messages.ts:29-30), and a stream sends it on a call's first piece only (LCIBM
 * chat_models.py:324). Whether the model calls tools, or supports them at all, depends on the model.
 *
 * == Penalties and response format ==
 *
 * `presencePenalty` and `frequencyPenalty` are sent as `presence_penalty` and `frequency_penalty` when not 0.0,
 * their default, so a request that leaves them alone is unchanged (SDK-evidenced: NODE vml_v1.ts:2808,2815 for
 * `textChat` and 3032,3039 for `textChatStream`; PY `foundation_models/schema/_api.py:270,273`; LCIBM
 * chat_models.py:939,969,1664,1670). Their range is the service's to check.
 *
 * `responseFormat` is sent as `response_format` ([[WatsonXClient.encodeResponseFormat]]): `ResponseFormat.Json` as
 * `{"type":"json_object"}` (NODE types/vml_v1.ts:3283-3310; PY `schema/_api.py:225`; LCIBM chat_models.py:2147-2149),
 * and `ResponseFormat.JsonSchema` as `{"type":"json_schema","json_schema":{"name","schema","strict"}}` (PY
 * `schema/_api.py:226,231-234,255-257`; LCIBM chat_models.py:2160-2182). The Node SDK's type names only
 * `json_object` but takes any string, so `json_schema` rests on the Python SDK and IBM's LangChain integration.
 * IBM's SDK notes that JSON mode needs the prompt to ask for JSON as well (NODE types/vml_v1.ts:3284-3292); this
 * client adds no instruction. Whether a model honours either format depends on the model.
 *
 * == Reasoning ==
 *
 * `CompletionOptions.reasoning` is sent as `reasoning_effort` (`low`, `medium` or `high`; SDK-evidenced: NODE
 * types/vml_v1.ts:860-864 and vml_v1.ts:2828,3053 for `textChat` and `textChatStream`; PY `schema/_api.py:289`;
 * LCIBM chat_models.py:1010), but only to the models known to take it ([[WatsonXClient.takesReasoningEffort]]):
 * OpenAI's gpt-oss models, `openai/gpt-oss-*`, which IBM's clients send it to (`JAVA` ChatServiceThinkingTest;
 * LCIBM chat_models.py:657-690). Any other model is sent nothing, and a warning is logged once per process: IBM's
 * Model Gateway rejects the parameter for models without it (`JAVA` ModelGatewayChatServiceIT), and whether the
 * chat API does is unchecked. The chat API has no value that turns reasoning off, so `ReasoningEffort.None` is
 * sent as `low`, the least, with a warning logged once per process ([[WatsonXClient.reasoningEffortValue]]).
 * `include_reasoning` is not sent: it defaults to true (NODE types/vml_v1.ts:858).
 *
 * A reply's reasoning is read as thinking, from any model: `message.reasoning_content` on `complete` (NODE
 * types/vml_v1.ts:3383-3384) and `delta.reasoning_content` on a stream, passed on as `StreamedChunk.thinkingDelta`
 * (`JAVA` gpt_oss_thinking_response.txt and gpt_oss_thinking_streaming_response.txt, recorded replies of
 * `openai/gpt-oss-120b`); `reasoning`, which IBM's LangChain integration also reads (LCIBM chat_models.py:150-153,
 * 332-335), when `reasoning_content` is absent. Thinking is not sent back on a later turn: the chat API's assistant
 * message has no field for it. Reasoning tokens are not reported: the chat API's usage has only `prompt_tokens`,
 * `completion_tokens` and `total_tokens` (NODE types/vml_v1.ts:3496-3503, and the recorded replies above), so
 * `TokenUsage.thinkingTokens` stays empty. `budgetTokens` has no counterpart and is ignored.
 *
 * == Finish reasons and stream endings ==
 *
 * A stream must end with a choice that carries a `finish_reason`. One that ends without it, or whose reason
 * is in [[WatsonXClient.ErrorFinishReasons]], is a `Left(ServiceError)` naming the reason; text received
 * so far is not returned as a success. Every other reason (`stop`, `length`, `tool_calls`, unknown values) is
 * a normal stop. `complete` applies the same rule to `choices[0].finish_reason` (a missing one is fine). The
 * usage arrives in a final chunk that may have no choices (SDK-evidenced: LCIBM chat_models.py:379-383). Streamed tool calls are reported once, whole, after the last delta, never as fragments.
 *
 * An `event: error` frame ends the stream with IBM's error: its `data:` line holds IBM's error JSON, whose
 * `status_code` picks the `LLMError` as an HTTP status would (401/403 `AuthenticationError`, 429
 * `RateLimitError`, 400 `ValidationError`, otherwise `ServiceError`; none is `ServiceError(502)`) and whose
 * first `errors` entry's `code` and `message`, redacted, are the message (SDK-evidenced: PY
 * `foundation_models/inference/fm_model_inference.py:683-689` raises on that frame).
 *
 * == Timeouts ==
 *
 * A chat call times out after two minutes and a streamed one after ten, unless the section's
 * `timeouts { request = ..., stream = ... }` block sets either ([[org.llm4s.llmconnect.config.WatsonXConfig.timeouts WatsonXConfig.timeouts]]). The IAM
 * token exchange keeps its own 30 seconds.
 *
 * @param config          model, credentials, project or space and endpoints.
 * @param metrics         receives per-call latency and token-usage events.
 * @param exchangeLogging optional provider exchange logging.
 * @param httpClient      used for both the model calls and the IAM exchange.
 */
class WatsonXClient(
  config: WatsonXConfig,
  protected val metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  private[provider] val httpClient: Llm4sHttpClient = Llm4sHttpClient.create(),
  private[provider] val nowSeconds: () => Long = () => System.currentTimeMillis() / 1000L
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  import WatsonXClient.*

  protected def clientDescription: String = s"watsonx client for model ${config.model}"
  protected def providerName: String      = "watsonx"
  protected def modelName: String         = config.model

  /** How long a non-streaming call may take: the section's `timeouts.request`, else [[WatsonXClient.DefaultRequestTimeout]]. */
  protected[provider] def requestTimeout: FiniteDuration = config.timeouts.requestOr(DefaultRequestTimeout)

  /** How long a streamed call may take: the section's `timeouts.stream`, else [[WatsonXClient.DefaultStreamTimeout]]. */
  protected[provider] def streamTimeout: FiniteDuration = config.timeouts.streamOr(DefaultStreamTimeout)

  final private case class IamToken(value: String, expiresAt: Long)

  private val tokenCache = new AtomicReference[Option[IamToken]](None)

  // Single-flight: concurrent first calls (or calls racing a refresh) wait for one IAM exchange
  // instead of each making their own. A ReentrantLock, not `synchronized`, so a virtual thread
  // blocked here does not pin its carrier and an interrupted waiter wakes with the interrupt.
  private val tokenLock = new ReentrantLock()

  private def freshCached(): Option[String] =
    tokenCache.get().filter(token => nowSeconds() < token.expiresAt - TOKEN_REFRESH_BUFFER_SECONDS).map(_.value)

  /**
   * The cached IAM token, or a fresh one when none is cached or it expires within the buffer.
   *
   * A thread interrupted while it waits for another thread's exchange (or that arrives already interrupted)
   * gets `Left(CancelledError)` with its interrupt flag set again, never a thrown `InterruptedException`.
   */
  private[provider] def bearerToken(): Result[String] =
    freshCached() match {
      case Some(token) => Right(token)
      case None =>
        CancelledError.catchInterrupt(tokenLock.lockInterruptibly()) match {
          case Left(interrupted) =>
            Thread.currentThread().interrupt()
            Left(CancelledError(s"$providerName.iam-token", Some(interrupted)))
          case Right(()) =>
            Using.resource(new AutoCloseable { def close(): Unit = tokenLock.unlock() }) { _ =>
              freshCached() match {
                case Some(token) => Right(token)
                case None        => fetchToken()
              }
            }
        }
    }

  /** Forgets `token` if it is still the cached one (a newer token fetched meanwhile is kept). */
  private def invalidate(token: String): Unit =
    tokenCache.updateAndGet(cached => cached.filterNot(_.value == token)): Unit

  /** A 401 means the token we sent is no good (revoked, or expired early): never reuse it. */
  private def noteStatus(status: Int, token: String): Unit =
    if (status == 401) invalidate(token)

  /** `text` with the API key and `token` blanked, for a body that goes into an error message. */
  private def scrub(text: String, token: String): String =
    Seq(config.apiKey, token).filter(_.nonEmpty).foldLeft(text)(_.replace(_, "[REDACTED]"))

  private def fetchToken(): Result[String] = {
    val body =
      s"grant_type=urn%3Aibm%3Aparams%3Aoauth%3Agrant-type%3Aapikey&apikey=${URLEncoder.encode(config.apiKey, "UTF-8")}"
    val headers = Map("Content-Type" -> "application/x-www-form-urlencoded", "Accept" -> "application/json")
    httpClient.post(config.iamUrl, headers, body, IAM_TIMEOUT).flatMap { response =>
      if (response.statusCode >= 200 && response.statusCode < 300) parseToken(response.body)
      else
        Left(
          AuthenticationError(
            providerName,
            s"IAM token exchange failed (HTTP ${response.statusCode}): ${Redaction.safeBody(scrub(response.body, ""), 256)}"
          )
        )
    }
  }

  private def parseToken(body: String): Result[String] =
    Try(ujson.read(body)).toResult.flatMap { json =>
      val fields = json.objOpt
      fields.flatMap(_.get("access_token")).flatMap(_.strOpt).filter(_.nonEmpty) match {
        case Some(token) =>
          val ttl =
            fields.flatMap(_.get("expires_in")).flatMap(_.numOpt).map(_.toLong).getOrElse(DEFAULT_TOKEN_TTL_SECONDS)
          tokenCache.set(Some(IamToken(token, nowSeconds() + ttl)))
          Right(token)
        case None =>
          Left(AuthenticationError(providerName, "IAM response contained no access_token"))
      }
    }

  private def apiHeaders(token: String, accept: String): Map[String, String] =
    Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $token", "Accept" -> accept)

  private def endpoint(path: String): String = s"${config.baseUrl}$path?version=${config.apiVersion}"

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    completeWithMetrics {
      // the parts are checked before the IAM exchange: a refused request sends nothing
      createRequestBody(conversation, options).flatMap(body => bearerToken().map(_ -> body)).flatMap { (token, body) =>
        val requestText = body.render()
        val startedAt   = Instant.now()
        httpClient
          .post(endpoint("/ml/v1/text/chat"), apiHeaders(token, "application/json"), requestText, requestTimeout)
          .flatMap { response =>
            noteStatus(response.statusCode, token)
            val result =
              if (response.statusCode >= 200 && response.statusCode < 300) parseCompletion(response.body)
              else
                HttpErrorMapper.mapHttpError(
                  response.statusCode,
                  scrub(response.body, token),
                  providerName,
                  response.headers
                )
            recordExchange(startedAt, requestText, response.body, result)
            result
          }
      }
    }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    createRequestBody(conversation, options).flatMap(body => bearerToken().map(_ -> body)).flatMap { (token, body) =>
      val requestText = body.render()
      val url         = endpoint("/ml/v1/text/chat_stream")
      val startedAt   = Instant.now()
      val raw         = new StringBuilder

      val result = httpClient
        .postStream(url, apiHeaders(token, "text/event-stream"), requestText, streamTimeout)
        .flatMap { response =>
          if (response.statusCode >= 200 && response.statusCode < 300)
            readStream(response.body, url, raw, token, onChunk)
          else {
            val err = Using(response.body)(in => new String(in.readAllBytes(), StandardCharsets.UTF_8)).getOrElse("")
            raw.append(err)
            noteStatus(response.statusCode, token)
            HttpErrorMapper.mapHttpError(response.statusCode, scrub(err, token), providerName, response.headers)
          }
        }
      recordExchange(startedAt, requestText, raw.result(), result)
      result
    }
  }

  private def readStream(
    in: java.io.InputStream,
    url: String,
    raw: StringBuilder,
    token: String,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    val accumulator                   = StreamingAccumulator.create()
    val calls                         = mutable.LinkedHashMap.empty[Int, PartialCall]
    var promptTokens, generatedTokens = 0
    var terminal: Option[String]      = None
    var responseId                    = ""
    // The `event:` field of the SSE event being read; a blank line ends the event and resets it.
    var eventType                     = ""
    var streamError: Option[LLMError] = None

    def emit(chunk: StreamedChunk): Unit = {
      accumulator.addChunk(chunk)
      onChunk(chunk)
    }

    // One `delta.tool_calls` entry: its pieces share an `index`, the id and name come once, the argument
    // text in fragments that are appended in order.
    def mergeToolCall(entry: ujson.Value, position: Int): Unit =
      entry.objOpt.foreach { fields =>
        val index = fields.get("index").flatMap(_.numOpt).map(_.toInt).getOrElse(position)
        val call  = calls.getOrElseUpdate(index, new PartialCall)
        fields.get("id").flatMap(_.strOpt).filter(_.nonEmpty).foreach(id => call.id = id)
        fields.get("function").flatMap(_.objOpt).foreach { function =>
          function.get("name").flatMap(_.strOpt).filter(_.nonEmpty).foreach(name => call.name = name)
          function.get("arguments").foreach {
            case ujson.Str(fragment) => call.arguments.append(fragment)
            case ujson.Null          => ()
            case other               => call.arguments.append(ujson.write(other))
          }
        }
      }

    val read = Using(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) { reader =>
      // reading stops at an error event: IBM's SDK raises on it, nothing after it belongs to this reply
      Iterator.continually(reader.readLine()).takeWhile(line => line != null && streamError.isEmpty).foreach { line =>
        raw.append(line).append('\n')
        val trimmed = line.trim
        if (trimmed.isEmpty) {
          if (eventType == "error") streamError = Some(streamErrorEvent(None, token))
          eventType = ""
        } else if (trimmed.startsWith("event:")) {
          eventType = trimmed.drop("event:".length).trim
        } else if (trimmed.startsWith("data:") && eventType == "error") {
          // SDK-evidenced: an `event: error` line is followed by a `data:` line holding IBM's error
          // (PY foundation_models/inference/fm_model_inference.py:683-689).
          streamError = Some(streamErrorEvent(Some(trimmed.drop("data:".length).trim), token))
        } else if (trimmed.startsWith("data:")) {
          val data = trimmed.drop("data:".length).trim
          if (data.nonEmpty && data != "[DONE]") {
            ujson.read(data).objOpt.foreach { chunk =>
              chunk.get("id").flatMap(_.strOpt).filter(_.nonEmpty).foreach(id => responseId = id)
              parseUsage(chunk.get("usage")).foreach { usage =>
                promptTokens = usage.promptTokens
                generatedTokens = usage.completionTokens
              }
              chunk.get("choices").flatMap(_.arrOpt).flatMap(_.headOption).flatMap(_.objOpt).foreach { choice =>
                val delta = choice.get("delta").flatMap(_.objOpt)
                delta.flatMap(reasoningText).foreach { thinking =>
                  emit(
                    StreamedChunk(
                      id = responseId,
                      content = None,
                      toolCall = None,
                      finishReason = None,
                      thinkingDelta = Some(thinking)
                    )
                  )
                }
                delta.flatMap(_.get("content")).flatMap(_.strOpt).filter(_.nonEmpty).foreach { text =>
                  emit(StreamedChunk(id = responseId, content = Some(text), toolCall = None, finishReason = None))
                }
                delta.flatMap(_.get("tool_calls")).flatMap(_.arrOpt).foreach { entries =>
                  entries.zipWithIndex.foreach { case (entry, position) => mergeToolCall(entry, position) }
                }
                choice
                  .get("finish_reason")
                  .flatMap(_.strOpt)
                  .map(normalizeFinishReason)
                  .filter(_.nonEmpty)
                  .foreach(reason => terminal = Some(reason))
              }
            }
          }
        }
      }
    }.toEither.left.map(HttpFailures.streamReadError(_, url, streamTimeout))

    // an `event: error` the stream ended on before its data line is still an error
    val errorEvent =
      streamError.orElse(Option.when(eventType == "error")(streamErrorEvent(None, token)))

    val idPrefix = callIdPrefix()
    read
      .flatMap(_ => errorEvent.toLeft(()))
      .flatMap(_ => checkStreamEnding(terminal))
      .flatMap { reason =>
        finishToolCalls(calls.toSeq.sortBy(_._1).map(_._2), idPrefix).map { toolCalls =>
          toolCalls.foreach { call =>
            emit(StreamedChunk(id = responseId, content = None, toolCall = Some(call), finishReason = None))
          }
          emit(StreamedChunk(id = responseId, content = None, toolCall = None, finishReason = Some(reason)))
          accumulator.updateTokens(promptTokens, generatedTokens)
        }
      }
      .flatMap(_ => accumulator.toCompletion)
      .map { c =>
        // the accumulator reports streamed calls on the message only
        c.withModel(config.model)
          .withToolCalls(c.message.toolCalls.toList)
          .withEstimatedCost(c.usage.flatMap(estimateCost))
      }
  }

  /**
   * The error an `event: error` frame reports, from its `data:` JSON (`None` when the frame had no data line).
   *
   * IBM's error body is `{"errors":[{"code","message","more_info"}],"trace","status_code"}`. A `status_code`
   * outside 2xx is mapped as an HTTP response with that status would be ([[HttpErrorMapper]]: 401/403
   * `AuthenticationError`, 429 `RateLimitError`, 400 `ValidationError`, else `ServiceError`); without one it is
   * a `ServiceError(502)`. The message carries IBM's `code` and `message`, with the API key and bearer token
   * removed and the rest redacted and truncated; a body that is not JSON is quoted the same way. A 401 forgets
   * the bearer token, as an HTTP 401 does.
   */
  private def streamErrorEvent(data: Option[String], token: String): LLMError = {
    val prefix = "stream error event"
    val parsed = data.flatMap(d => BoundedJson.read(d).toOption).flatMap(_.objOpt)
    val status = parsed.flatMap(_.get("status_code")).flatMap(_.numOpt).map(_.toInt).filterNot(s => s >= 200 && s < 300)
    val first  = parsed.flatMap(_.get("errors")).flatMap(_.arrOpt).flatMap(_.headOption).flatMap(_.objOpt)
    val code   = first.flatMap(_.get("code")).flatMap(_.strOpt).filter(_.nonEmpty)
    val text   = first.flatMap(_.get("message")).flatMap(_.strOpt).filter(_.nonEmpty)
    val detail = (code, text, data.filter(_.nonEmpty)) match {
      case (None, None, None)       => s"$prefix with no data"
      case (None, None, Some(body)) => s"$prefix: $body"
      case _                        => s"$prefix: ${Seq(code, text).flatten.mkString(": ")}"
    }
    val safe = Redaction.safeBody(scrub(detail, token), 512)
    status.foreach(noteStatus(_, token))
    status match {
      case Some(s) =>
        HttpErrorMapper
          .mapHttpError(s, ujson.Obj("message" -> safe).render(), providerName)
          .left
          .getOrElse(ServiceError(s, providerName, safe))
      case None => ServiceError(502, providerName, safe)
    }
  }

  /** The stream's closing finish reason; a stream with none, or an abnormal one, is a failure. */
  private def checkStreamEnding(terminal: Option[String]): Result[String] = terminal match {
    case None =>
      Left(
        ServiceError(
          502,
          providerName,
          "stream ended without a finish_reason; the response is incomplete"
        )
      )
    case Some(reason) => checkFinishReason(reason).map(_ => reason)
  }

  /** `reason` must already be normalised by [[WatsonXClient.normalizeFinishReason]]. */
  private def checkFinishReason(reason: String): Result[Unit] =
    if (ErrorFinishReasons.contains(reason))
      Left(ServiceError(502, providerName, s"generation ended abnormally with finish_reason '$reason'"))
    else Right(())

  private def estimateCost(usage: TokenUsage): Option[Double] = CostEstimator.estimate(config.model, usage)

  private[provider] def createRequestBody(conversation: Conversation, options: CompletionOptions): Result[ujson.Obj] =
    PartEncoding
      .check(providerName, conversation.messages, WatsonXClient.userPart, PartEncoding.textOnly)
      .map(_ => requestBody(conversation, options))

  private def requestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj = {
    val body = ujson.Obj(
      "model_id"    -> config.model,
      "messages"    -> encodeMessages(conversation),
      "temperature" -> options.temperature
    )
    config.spaceId match {
      case Some(space) => body("space_id") = space
      case None        => body("project_id") = config.projectId
    }
    // SDK-evidenced: the chat body names the limit `max_tokens`, not text generation's `max_new_tokens`; it is
    // deprecated in favour of `max_completion_tokens`, which a later change may adopt (NODE vml_v1.ts:2812-2813,
    // types/vml_v1.ts:790-803; LCIBM chat_models.py:953-959).
    options.maxTokens.foreach(max => body("max_tokens") = max)
    if (options.topP != 1.0) body("top_p") = options.topP
    // SDK-evidenced (see the class Scaladoc): sent only when set away from their 0.0 default
    if (options.presencePenalty != 0.0) body("presence_penalty") = options.presencePenalty
    if (options.frequencyPenalty != 0.0) body("frequency_penalty") = options.frequencyPenalty
    options.responseFormat.foreach(format => body("response_format") = encodeResponseFormat(format))
    // see "Reasoning" in the class Scaladoc
    options.reasoning.foreach { effort =>
      if (takesReasoningEffort(config.model)) body("reasoning_effort") = reasoningEffortValue(config.model, effort)
      else warnReasoningNotSent(config.model)
    }
    if (options.tools.nonEmpty) {
      body("tools") = ujson.Arr.from(options.tools.map(tool => encodeTool(tool.toOpenAITool(strict = false))))
      // SDK-evidenced: IBM's LangChain integration sends "auto" by default (LCIBM chat_models.py:1764). The Node
      // SDK's own doc comment says `auto` is not yet supported (NODE types/vml_v1.ts:751-761); that integration
      // contradicts it, so the probe suite checks this against a real account.
      body("tool_choice_option") = "auto"
    }
    body
  }

  private def encodeMessages(conversation: Conversation): ujson.Arr =
    ujson.Arr.from(conversation.messages.flatMap {
      case SystemMessage(content) => Some(ujson.Obj("role" -> "system", "content" -> content))
      case um: UserMessage        =>
        // checked by createRequestBody: every part is text or an inline image
        val content = um.parts match {
          case Vector(ContentPart.Text(text)) => ujson.Str(text)
          case parts                          => ujson.Arr.from(parts.map(WatsonXClient.userPart))
        }
        Some(ujson.Obj("role" -> "user", "content" -> content))
      case am: AssistantMessage =>
        // an assistant turn with neither text nor calls says nothing: it is dropped
        if (am.content.isEmpty && am.toolCalls.isEmpty) None
        else {
          val message = ujson.Obj("role" -> "assistant")
          // SDK-evidenced: `content` is optional when `tool_calls` is given (NODE types/messages.ts:58-59); IBM's
          // LangChain integration sends null instead (LCIBM chat_models.py:258-259).
          if (am.content.nonEmpty) message("content") = am.content
          if (am.toolCalls.nonEmpty)
            message("tool_calls") = ujson.Arr.from(am.toolCalls.map { call =>
              ujson.Obj(
                "id"       -> call.id,
                "type"     -> "function",
                "function" -> ujson.Obj("name" -> call.name, "arguments" -> requestArguments(call.arguments))
              )
            })
          Some(message)
        }
      case tm: ToolMessage =>
        Some(ujson.Obj("role" -> "tool", "tool_call_id" -> tm.toolCallId, "content" -> tm.content))
    })

  private def parseCompletion(body: String): Result[Completion] =
    Try(ujson.read(body)).toResult.flatMap { json =>
      json.objOpt.flatMap(_.get("choices")).flatMap(_.arrOpt).flatMap(_.headOption).flatMap(_.objOpt) match {
        case None =>
          Left(ValidationError("responseBody", "watsonx response has no 'choices' entry"))
        case Some(choice) =>
          val message = choice.get("message").flatMap(_.objOpt)
          for {
            toolCalls <- parseToolCalls(message.flatMap(_.get("tool_calls")), callIdPrefix())
            _ <- checkFinishReason(
              choice.get("finish_reason").flatMap(_.strOpt).map(normalizeFinishReason).getOrElse("")
            )
          } yield {
            val text     = message.flatMap(_.get("content")).flatMap(_.strOpt).getOrElse("")
            val thinking = message.flatMap(reasoningText).getOrElse("")
            val usage    = parseUsage(json.objOpt.flatMap(_.get("usage")))
            Completion(
              id = json.objOpt.flatMap(_.get("id")).flatMap(_.strOpt).getOrElse(java.util.UUID.randomUUID().toString),
              created = nowSeconds(),
              content = text,
              toolCalls = toolCalls,
              usage = usage,
              model = config.model,
              message = AssistantMessage(Some(text).filter(_.nonEmpty), toolCalls).withThinking(thinking),
              estimatedCost = usage.flatMap(estimateCost)
            )
          }
      }
    }

  private def recordExchange(
    startedAt: Instant,
    requestBody: String,
    responseBody: String,
    result: Result[Completion]
  ): Unit =
    ProviderExchangeRecorder.record(
      exchangeLogging = exchangeLogging,
      provider = providerName,
      model = Some(config.model),
      startedAt = startedAt,
      requestBody = requestBody,
      responseBody = Option(responseBody).filter(_.nonEmpty),
      result = result
    )

  override def getContextWindow(): Int     = config.contextWindow
  override def getReserveCompletion(): Int = config.reserveCompletion

  override protected def releaseResources(): Unit = httpClient.close()
}

object WatsonXClient {

  /**
   * The user-message parts the watsonx.ai chat API takes, in its OpenAI-style content array: text, and inline
   * images as a base64 `data:` URL in `image_url`. The service fetches no remote URL and takes no documents.
   */
  private[provider] val userPart: PartialFunction[ContentPart, ujson.Value] = {
    case ContentPart.Text(text) => ujson.Obj("type" -> "text", "text" -> text)
    case ContentPart.Image(inline: MediaSource.Inline, mediaType) =>
      ujson.Obj("type" -> "image_url", "image_url" -> ujson.Obj("url" -> PartEncoding.dataUrl(inline, mediaType)))
  }

  /** The timeout of a non-streaming call when the section sets no `timeouts.request`: two minutes. */
  val DefaultRequestTimeout: FiniteDuration = 120.seconds

  /** The timeout of a streamed call when the section sets no `timeouts.stream`: ten minutes. */
  val DefaultStreamTimeout: FiniteDuration = 10.minutes

  private val IAM_TIMEOUT: FiniteDuration        = 30.seconds
  private val TOKEN_REFRESH_BUFFER_SECONDS: Long = 300L
  private val DEFAULT_TOKEN_TTL_SECONDS: Long    = 3600L

  /**
   * The single point where a `finish_reason` is normalised (trimmed, lower-cased). Everything after
   * reading (the abnormal-ending check, messages, `StreamedChunk.finishReason`) uses this form.
   */
  private[provider] def normalizeFinishReason(raw: String): String = raw.trim.toLowerCase(java.util.Locale.ROOT)

  /**
   * The `finish_reason` values that mean a generation did not finish: `error`, `cancelled` and
   * `time_limit`. The chat API's values are `stop`, `length`, `tool_calls`, `time_limit`, `cancelled`, `error`, and
   * null while a response is incomplete (SDK-evidenced: NODE types/vml_v1.ts:3319-3330, 3362-3369). Calling the
   * last three failures is this client's policy: the SDK notes that on `time_limit` the text generated so far is
   * returned, which this client does not report as a success. Compared after [[normalizeFinishReason]], so any
   * case matches. Anything else is a normal stop: `stop`, `length`, `tool_calls` and any value IBM adds later.
   */
  val ErrorFinishReasons: Set[String] = Set("error", "cancelled", "time_limit")

  /** A tool call whose `delta` pieces are still arriving: ids and names come once, arguments in fragments. */
  final private class PartialCall {
    var id: String               = ""
    var name: String             = ""
    val arguments: StringBuilder = new StringBuilder
  }

  /** A fresh prefix per reply for the ids of calls the service sent without one. */
  private def callIdPrefix(): String = java.util.UUID.randomUUID().toString.take(12).replace("-", "")

  private def syntheticId(prefix: String, index: Int): String = s"call_${prefix}_$index"

  private def malformed(detail: String): org.llm4s.error.LLMError =
    ProcessingError("watsonx-tool-calls", s"malformed tool call: $detail")

  /**
   * An OpenAI-format tool definition as watsonx takes it: no `strict`. The SDK's tool function type has only
   * `name`, `description` and `parameters` (NODE types/vml_v1.ts:3202-3218); IBM's LangChain integration passes
   * `strict` through only when a caller sets it (LCIBM chat_models.py:1718-1723).
   */
  private[provider] def encodeTool(tool: ujson.Value): ujson.Value = {
    val function = ujson.Obj.from(tool("function").obj.filterNot(_._1 == "strict"))
    ujson.Obj("type" -> "function", "function" -> function)
  }

  /**
   * A [[ResponseFormat]] as the chat API's `response_format`: `{"type":"json_object"}` for JSON mode, and
   * `{"type":"json_schema","json_schema":{"name","schema","strict"}}` for a schema (SDK-evidenced: PY
   * `foundation_models/schema/_api.py:224-257`; LCIBM chat_models.py:2147-2149, 2160-2182).
   */
  private[provider] def encodeResponseFormat(format: ResponseFormat): ujson.Value = format match {
    case ResponseFormat.Json => ujson.Obj("type" -> "json_object")
    case js: ResponseFormat.JsonSchema =>
      ujson.Obj(
        "type"        -> "json_schema",
        "json_schema" -> ujson.Obj("name" -> js.name, "schema" -> js.schema, "strict" -> js.strict)
      )
  }

  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[WatsonXClient])

  private val GptOss = """openai/gpt-oss-.+""".r

  /**
   * Whether `model` is sent `reasoning_effort`: OpenAI's gpt-oss models (`openai/gpt-oss-120b`, `openai/gpt-oss-20b`
   * and their variants, such as `-curated`), matched case-insensitively. They are the models IBM's own clients send
   * it to (see "Reasoning" in the class Scaladoc); any other model is sent nothing.
   */
  private[provider] def takesReasoningEffort(model: String): Boolean =
    GptOss.matches(model.trim.toLowerCase(java.util.Locale.ROOT))

  /**
   * A [[ReasoningEffort]] as the chat API's `reasoning_effort`, which takes `low`, `medium` and `high` (NODE
   * types/vml_v1.ts:860-864). `None` has no counterpart, since the models that take the field always reason: it is
   * sent as `low`, the least, and a warning naming `model` is logged once per process.
   */
  private[provider] def reasoningEffortValue(model: String, effort: ReasoningEffort): String = effort match {
    case ReasoningEffort.None =>
      warnReasoningNotDisabled(model)
      "low"
    case ReasoningEffort.Low    => "low"
    case ReasoningEffort.Medium => "medium"
    case ReasoningEffort.High   => "high"
  }

  /** Whether the `ReasoningEffort.None` warning has been logged in this process. */
  private[provider] val warnedReasoningNotDisabled = new AtomicBoolean(false)

  /** Whether the warning that a model is not sent `reasoning_effort` has been logged in this process. */
  private[provider] val warnedReasoningNotSent = new AtomicBoolean(false)

  private def warnReasoningNotDisabled(model: String): Unit =
    if (warnedReasoningNotDisabled.compareAndSet(false, true))
      logger.warn(
        s"watsonx model $model cannot turn reasoning off: ReasoningEffort.None is sent as reasoning_effort=low, " +
          "and the model still reasons. Logged once per process."
      )

  private def warnReasoningNotSent(model: String): Unit =
    if (warnedReasoningNotSent.compareAndSet(false, true))
      logger.warn(
        s"watsonx model $model is not known to take reasoning_effort (only openai/gpt-oss-* models are sent it): " +
          "CompletionOptions.reasoning is not sent. Logged once per process."
      )

  /**
   * The reasoning in a reply's `message` or a stream's `delta`: `reasoning_content`, else `reasoning`, when a
   * non-empty string; a stream sends empty `reasoning_content` pieces, which are skipped.
   */
  private def reasoningText(fields: collection.Map[String, ujson.Value]): Option[String] =
    fields
      .get("reasoning_content")
      .flatMap(_.strOpt)
      .filter(_.nonEmpty)
      .orElse(fields.get("reasoning").flatMap(_.strOpt).filter(_.nonEmpty))

  /**
   * The `arguments` of a call as the chat API takes them: a JSON string (SDK-evidenced: NODE types/messages.ts:19-24). An object is rendered; a string
   * that parses to an object is sent as it is; anything else is sent as `{}` rather than as text that is not JSON.
   */
  private[provider] def requestArguments(arguments: ujson.Value): String = arguments match {
    case obj: ujson.Obj => ujson.write(obj)
    case ujson.Str(text) =>
      Try(ujson.read(text)).toOption match {
        case Some(parsed: ujson.Obj) => ujson.write(parsed)
        case _                       => "{}"
      }
    case _ => "{}"
  }

  /** The `arguments` of a reply's call as a JSON object: an object, or a string holding one; none is `{}`. */
  private def normalizeArguments(raw: Option[ujson.Value]): Result[ujson.Value] = raw match {
    case None | Some(ujson.Null)                    => Right(ujson.Obj())
    case Some(obj: ujson.Obj)                       => Right(obj)
    case Some(ujson.Str(text)) if text.trim.isEmpty => Right(ujson.Obj())
    case Some(ujson.Str(text)) =>
      Try(ujson.read(text)).toOption match {
        case Some(parsed: ujson.Obj) => Right(parsed)
        case _                       => Left(malformed("the arguments are not a JSON object"))
      }
    case Some(_) => Left(malformed("the arguments are neither a JSON object nor a string"))
  }

  private def parseToolCall(entry: ujson.Value, index: Int, idPrefix: String): Result[ToolCall] =
    for {
      fields   <- entry.objOpt.toRight(malformed("an entry is not an object"))
      function <- fields.get("function").flatMap(_.objOpt).toRight(malformed("an entry has no `function` object"))
      name     <- function.get("name").flatMap(_.strOpt).filter(_.nonEmpty).toRight(malformed("an entry has no name"))
      args     <- normalizeArguments(function.get("arguments"))
    } yield ToolCall(
      fields.get("id").flatMap(_.strOpt).filter(_.nonEmpty).getOrElse(syntheticId(idPrefix, index)),
      name,
      args
    )

  /** The `tool_calls` of a reply's `message`: none when absent, null or empty. */
  private def parseToolCalls(raw: Option[ujson.Value], idPrefix: String): Result[List[ToolCall]] =
    raw.filterNot(_.isNull) match {
      case None => Right(Nil)
      case Some(entries: ujson.Arr) =>
        entries.value.toList.zipWithIndex.foldLeft[Result[List[ToolCall]]](Right(Nil)) { case (done, (entry, index)) =>
          done.flatMap(calls => parseToolCall(entry, index, idPrefix).map(calls :+ _))
        }
      case Some(_) => Left(malformed("`tool_calls` is not an array"))
    }

  /** The calls a stream's pieces added up to, in `index` order; a call that never got a name is malformed. */
  private def finishToolCalls(partials: Seq[PartialCall], idPrefix: String): Result[List[ToolCall]] =
    partials.zipWithIndex.foldLeft[Result[List[ToolCall]]](Right(Nil)) { case (done, (partial, index)) =>
      done.flatMap { calls =>
        if (partial.name.isEmpty) Left(malformed("a streamed call has no name"))
        else
          normalizeArguments(Some(ujson.Str(partial.arguments.result()))).map { args =>
            calls :+ ToolCall(if (partial.id.nonEmpty) partial.id else syntheticId(idPrefix, index), partial.name, args)
          }
      }
    }

  private def parseUsage(raw: Option[ujson.Value]): Option[TokenUsage] =
    for {
      usage      <- raw.flatMap(_.objOpt)
      prompt     <- usage.get("prompt_tokens").flatMap(_.numOpt).map(_.toInt)
      completion <- usage.get("completion_tokens").flatMap(_.numOpt).map(_.toInt)
    } yield TokenUsage(
      prompt,
      completion,
      usage.get("total_tokens").flatMap(_.numOpt).map(_.toInt).getOrElse(prompt + completion)
    )

  /**
   * Constructs a [[WatsonXClient]], wrapping any construction-time exception in a `Left`.
   *
   * @param config          model, credentials, project or space and endpoints.
   * @param metrics         receives per-call latency and token-usage events.
   * @param exchangeLogging optional provider exchange logging.
   */
  def apply(
    config: WatsonXConfig,
    metrics: MetricsCollector = MetricsCollector.noop,
    exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
  )(using ModelRegistryService): Result[WatsonXClient] =
    Try(new WatsonXClient(config, metrics, exchangeLogging)).toResult
}
