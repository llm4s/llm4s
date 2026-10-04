package org.llm4s.llmconnect.provider

import org.llm4s.error.AuthenticationError
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
import scala.concurrent.duration.*
import scala.util.{ Try, Using }

/**
 * [[LLMClient]] for IBM watsonx.ai text generation
 * (`POST /ml/v1/text/generation` and `/ml/v1/text/generation_stream`).
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
 * an open `[ASSISTANT]:` turn. Tool calling is not supported, so a completion never carries tool
 * calls.
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

  /** The cached IAM token, or a fresh one when none is cached or it expires within the buffer. */
  private[provider] def bearerToken(): Result[String] =
    tokenCache.get() match {
      case Some(token) if nowSeconds() < token.expiresAt - TOKEN_REFRESH_BUFFER_SECONDS => Right(token.value)
      case _                                                                            => fetchToken()
    }

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
            s"IAM token exchange failed (HTTP ${response.statusCode}): ${response.body.take(256)}"
          )
        )
    }
  }

  private def parseToken(body: String): Result[String] =
    Try(ujson.read(body)).toResult.flatMap { json =>
      json.obj.get("access_token").flatMap(_.strOpt).filter(_.nonEmpty) match {
        case Some(token) =>
          val ttl = json.obj.get("expires_in").flatMap(_.numOpt).map(_.toLong).getOrElse(DEFAULT_TOKEN_TTL_SECONDS)
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
      bearerToken().flatMap { token =>
        val requestText = createRequestBody(conversation, options).render()
        val startedAt   = Instant.now()
        httpClient
          .post(endpoint("/ml/v1/text/generation"), apiHeaders(token, "application/json"), requestText, 120.seconds)
          .flatMap { response =>
            val result =
              if (response.statusCode >= 200 && response.statusCode < 300) parseCompletion(response.body)
              else HttpErrorMapper.mapHttpError(response.statusCode, response.body, providerName, response.headers)
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
    bearerToken().flatMap { token =>
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
            HttpErrorMapper.mapHttpError(response.statusCode, err, providerName, response.headers)
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
              .filter(reason => reason.nonEmpty && reason != NOT_FINISHED)
            result.foreach { r =>
              r.obj.get("input_token_count").flatMap(_.numOpt).foreach(n => promptTokens = n.toInt)
              r.obj.get("generated_token_count").flatMap(_.numOpt).foreach(n => generatedTokens = n.toInt)
            }
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
      .flatMap { _ =>
        accumulator.updateTokens(promptTokens, generatedTokens)
        accumulator.toCompletion
      }
      .map(c => c.withModel(config.model).withEstimatedCost(c.usage.flatMap(estimateCost)))
  }

  private def estimateCost(usage: TokenUsage): Option[Double] = CostEstimator.estimate(config.model, usage)

  private[provider] def createRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj = {
    val parameters = ujson.Obj("temperature" -> options.temperature)
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
      json.obj.get("results").flatMap(_.arrOpt).flatMap(_.headOption) match {
        case None =>
          Left(
            org.llm4s.error.ValidationError(
              "responseBody",
              "watsonx response has no 'results' entry"
            )
          )
        case Some(first) =>
          val text = first.obj.get("generated_text").flatMap(_.strOpt).getOrElse("")
          val usage = for {
            prompt <- first.obj.get("input_token_count").flatMap(_.numOpt).map(_.toInt)
            gen    <- first.obj.get("generated_token_count").flatMap(_.numOpt).map(_.toInt)
          } yield TokenUsage(prompt, gen, prompt + gen)
          Right(
            Completion(
              id = json.obj.get("id").flatMap(_.strOpt).getOrElse(java.util.UUID.randomUUID().toString),
              created = nowSeconds(),
              content = text,
              toolCalls = List.empty,
              usage = usage,
              model = config.model,
              message = AssistantMessage(text),
              estimatedCost = usage.flatMap(estimateCost)
            )
          )
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
