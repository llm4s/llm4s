package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, ServiceError, ValidationError }
import org.llm4s.http.{ HttpFailures, Llm4sHttpClient }
import org.llm4s.llmconnect.{ BaseLifecycleLLMClient, ProviderExchangeLogging }
import org.llm4s.llmconnect.config.WatsonXConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }

import java.io.{ BufferedReader, InputStreamReader }
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.duration.*
import scala.util.{ Try, Using }

/**
 * [[LLMClient]] for IBM watsonx.ai text generation
 * (`POST /ml/v1/text/generation` and `/ml/v1/text/generation_stream`).
 *
 * '''Beta - built on deprecated endpoints.''' IBM's February 2026 release notes
 * (https://www.ibm.com/docs/en/software-hub/5.3.x?topic=new-watsonxai) deprecate the watsonx.ai
 * "Infer text" and "Infer text event stream" endpoints (`/ml/v1/text/generation` and
 * `/generation_stream`) this module uses; IBM points to the chat API. This module has never been run
 * against the live service (no watsonx account), its API is not frozen, and tools are unsupported
 * because of the endpoint. Migration to the chat API: https://github.com/llm4s/llm4s/issues/1314.
 *
 * == Authentication ==
 *
 * The IBM Cloud API key is exchanged at the IAM endpoint for a bearer token that lives an hour.
 * The token is cached and refreshed lazily, five minutes before it expires.
 *
 * == Request format ==
 *
 * The text-generation API is not a chat API: the conversation is flattened into one `input`
 * string with `[SYSTEM]:`, `[USER]:`, `[ASSISTANT]:` and `[TOOL_RESULT:<id>]:` prefixes, ending in
 * an open `[ASSISTANT]:` turn. Content is not escaped, so user content can forge those markers
 * (a prompt-injection surface inherent to the flattened format). Requests carry `stop_sequences`
 * ([[WatsonXClient.StopSequences]]) so a model cannot go on to write the next turn itself.
 *
 * == Unsupported options ==
 *
 *  - '''Tools are rejected.''' text-generation has no tool calling: `complete` and `streamComplete`
 *    return a `Left(ValidationError("tools", ...))` when `CompletionOptions.tools` is non-empty, before
 *    any HTTP call (the IAM exchange included).
 *  - '''Ignored without error:''' `presencePenalty`, `frequencyPenalty`, `responseFormat`,
 *    `reasoning` and `budgetTokens`. Only `temperature`, `maxTokens` and `topP` (when not 1.0) are sent.
 *
 * == Stream endings ==
 *
 * A stream must end with a terminal event (a `stop_reason` other than `not_finished`). One that
 * ends without it, or whose reason is in [[WatsonXClient.ErrorStopReasons]], is a
 * `Left(ServiceError)` naming the reason; text received so far is not returned as a success. Every
 * other reason (`eos_token`, `stop_sequence`, `max_tokens`, `token_limit`, unknown values) is a
 * normal stop. `complete` applies the same rule to `results[0].stop_reason` (a missing one is fine).
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

  final private case class IamToken(value: String, expiresAt: Long)

  private val tokenCache = new AtomicReference[Option[IamToken]](None)

  // Single-flight: concurrent first calls (or calls racing a refresh) wait for one IAM exchange
  // instead of each making their own. A ReentrantLock, not `synchronized`, so a virtual thread
  // blocked here does not pin its carrier and an interrupted waiter wakes with the interrupt.
  private val tokenLock = new ReentrantLock()

  private def freshCached(): Option[String] =
    tokenCache.get().filter(token => nowSeconds() < token.expiresAt - TOKEN_REFRESH_BUFFER_SECONDS).map(_.value)

  /** The cached IAM token, or a fresh one when none is cached or it expires within the buffer. */
  private[provider] def bearerToken(): Result[String] =
    freshCached() match {
      case Some(token) => Right(token)
      case None =>
        tokenLock.lockInterruptibly()
        Using.resource(new AutoCloseable { def close(): Unit = tokenLock.unlock() }) { _ =>
          freshCached() match {
            case Some(token) => Right(token)
            case None        => fetchToken()
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
            s"IAM token exchange failed (HTTP ${response.statusCode}): ${scrub(response.body, "").take(256)}"
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

  private def rejectTools(options: CompletionOptions): Result[Unit] =
    Either.cond(
      options.tools.isEmpty,
      (),
      ValidationError(
        "tools",
        "watsonx text generation does not support tool calling; remove the tools from CompletionOptions"
      )
    )

  private def endpoint(path: String): String = s"${config.baseUrl}$path?version=${config.apiVersion}"

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    completeWithMetrics {
      rejectTools(options).flatMap(_ => bearerToken()).flatMap { token =>
        val requestText = createRequestBody(conversation, options).render()
        val startedAt   = Instant.now()
        httpClient
          .post(endpoint("/ml/v1/text/generation"), apiHeaders(token, "application/json"), requestText, 120.seconds)
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
    rejectTools(options).flatMap(_ => bearerToken()).flatMap { token =>
      val requestText = createRequestBody(conversation, options).render()
      val url         = endpoint("/ml/v1/text/generation_stream")
      val startedAt   = Instant.now()
      val raw         = new StringBuilder

      val result = httpClient.postStream(url, apiHeaders(token, "text/event-stream"), requestText, 10.minutes).flatMap {
        response =>
          if (response.statusCode >= 200 && response.statusCode < 300)
            readStream(response.body, url, raw, onChunk)
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
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    val accumulator                   = StreamingAccumulator.create()
    var promptTokens, generatedTokens = 0
    var terminal: Option[String]      = None
    val read = Using(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) { reader =>
      Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach { line =>
        raw.append(line).append('\n')
        val trimmed = line.trim
        if (trimmed.startsWith("data:")) {
          val data = trimmed.drop("data:".length).trim
          if (data.nonEmpty && data != "[DONE]") {
            val json   = ujson.read(data)
            val result = json.obj.get("results").flatMap(_.arrOpt).flatMap(_.headOption)
            val text   = result.flatMap(_.obj.get("generated_text")).flatMap(_.strOpt).filter(_.nonEmpty)
            val stop = result
              .flatMap(_.obj.get("stop_reason"))
              .flatMap(_.strOpt)
              .map(normalizeStopReason)
              .filter(reason => reason.nonEmpty && reason != NOT_FINISHED)
            result.foreach { r =>
              r.obj.get("input_token_count").flatMap(_.numOpt).foreach(n => promptTokens = n.toInt)
              r.obj.get("generated_token_count").flatMap(_.numOpt).foreach(n => generatedTokens = n.toInt)
            }
            stop.foreach(reason => terminal = Some(reason))
            if (text.isDefined || stop.isDefined) {
              val chunk = StreamedChunk(id = "", content = text, toolCall = None, finishReason = stop)
              accumulator.addChunk(chunk)
              onChunk(chunk)
            }
          }
        }
      }
    }.toEither.left.map(HttpFailures.streamReadError(_, url, 10.minutes))

    read
      .flatMap(_ => checkStreamEnding(terminal))
      .flatMap { _ =>
        accumulator.updateTokens(promptTokens, generatedTokens)
        accumulator.toCompletion
      }
      .map(c => c.withModel(config.model).withEstimatedCost(c.usage.flatMap(estimateCost)))
  }

  private def checkStreamEnding(terminal: Option[String]): Result[Unit] = terminal match {
    case None =>
      Left(
        ServiceError(
          502,
          providerName,
          "stream ended without a terminal event (no stop_reason); the response is incomplete"
        )
      )
    case Some(reason) => checkStopReason(reason)
  }

  /** `reason` must already be normalised by [[WatsonXClient.normalizeStopReason]]. */
  private def checkStopReason(reason: String): Result[Unit] =
    if (ErrorStopReasons.contains(reason))
      Left(ServiceError(502, providerName, s"generation ended abnormally with stop_reason '$reason'"))
    else Right(())

  private def estimateCost(usage: TokenUsage): Option[Double] = CostEstimator.estimate(config.model, usage)

  private[provider] def createRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj = {
    val parameters = ujson.Obj("temperature" -> options.temperature)
    parameters("stop_sequences") = ujson.Arr.from(StopSequences)
    options.maxTokens.foreach(max => parameters("max_new_tokens") = max)
    if (options.topP != 1.0) parameters("top_p") = options.topP

    val request = ujson.Obj(
      "model_id"   -> config.model,
      "input"      -> formatInput(conversation),
      "parameters" -> parameters
    )
    config.spaceId match {
      case Some(space) => request("space_id") = space
      case None        => request("project_id") = config.projectId
    }
    request
  }

  private def formatInput(conversation: Conversation): String = {
    val turns = conversation.messages.flatMap {
      case SystemMessage(content)   => Some(s"[SYSTEM]: $content")
      case UserMessage(content)     => Some(s"[USER]: $content")
      case am: AssistantMessage     => Some(am.content).filter(_.nonEmpty).map(c => s"[ASSISTANT]: $c")
      case ToolMessage(content, id) => Some(s"[TOOL_RESULT:$id]: $content")
    }
    (turns :+ "[ASSISTANT]: ").mkString("\n")
  }

  private def parseCompletion(body: String): Result[Completion] =
    Try(ujson.read(body)).toResult.flatMap { json =>
      json.objOpt.flatMap(_.get("results")).flatMap(_.arrOpt).flatMap(_.headOption).flatMap(_.objOpt) match {
        case None =>
          Left(
            org.llm4s.error.ValidationError(
              "responseBody",
              "watsonx response has no 'results' entry"
            )
          )
        case Some(first) =>
          val text = first.get("generated_text").flatMap(_.strOpt).getOrElse("")
          checkStopReason(first.get("stop_reason").flatMap(_.strOpt).map(normalizeStopReason).getOrElse("")).map { _ =>
            val usage = for {
              prompt <- first.get("input_token_count").flatMap(_.numOpt).map(_.toInt)
              gen    <- first.get("generated_token_count").flatMap(_.numOpt).map(_.toInt)
            } yield TokenUsage(prompt, gen, prompt + gen)
            Completion(
              id = json.objOpt.flatMap(_.get("id")).flatMap(_.strOpt).getOrElse(java.util.UUID.randomUUID().toString),
              created = nowSeconds(),
              content = text,
              toolCalls = List.empty,
              usage = usage,
              model = config.model,
              message = AssistantMessage(text),
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

  private val IAM_TIMEOUT: FiniteDuration        = 30.seconds
  private val TOKEN_REFRESH_BUFFER_SECONDS: Long = 300L
  private val DEFAULT_TOKEN_TTL_SECONDS: Long    = 3600L
  private val NOT_FINISHED                       = "not_finished"

  /**
   * Strings that stop generation, so a base or instruct model cannot write the next turn of the
   * flattened prompt itself: the role markers `[USER]:`, `[SYSTEM]:` and `[TOOL_RESULT:` at the start
   * of a line. Sent as `parameters.stop_sequences` (unverified against IBM's reference; the
   * API documents an array of at most six strings).
   */
  val StopSequences: Seq[String] = Seq("\n[USER]:", "\n[SYSTEM]:", "\n[TOOL_RESULT:")

  /**
   * The single point where a `stop_reason` is normalised (trimmed, lower-cased). IBM documents the
   * values in upper case (`NOT_FINISHED`, `EOS_TOKEN`, ...); everything after reading (terminal
   * detection, [[ErrorStopReasons]], messages, `StreamedChunk.finishReason`) uses this form.
   */
  private[provider] def normalizeStopReason(raw: String): String = raw.trim.toLowerCase(java.util.Locale.ROOT)

  /**
   * The `stop_reason` values that mean a generation did not finish: `error`, `cancelled` and
   * `time_limit`. Compared after [[normalizeStopReason]], so any case matches. Anything else is a normal stop:
   * `eos_token`, `stop_sequence`, `max_tokens` and `token_limit` (length stops, as for other
   * providers) and any value IBM adds later.
   */
  val ErrorStopReasons: Set[String] = Set("error", "cancelled", "time_limit")

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
