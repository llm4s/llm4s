package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.ProviderResultOps.*
import org.llm4s.llmconnect.streaming.{ SSEParser, StreamingAccumulator, StreamingToolArgumentParser }
import org.llm4s.llmconnect.{ BaseLifecycleLLMClient, ProviderExchangeLogging }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.util.Redaction

import java.io.{ BufferedReader, InputStream, InputStreamReader }
import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.time.{ Duration, Instant }
import scala.util.{ Try, Using }

/**
 * An [[org.llm4s.llmconnect.LLMClient]] for any endpoint speaking the OpenAI
 * `/chat/completions` API, with no SDK.
 *
 * This is the one implementation behind the generic `openai-compatible`
 * provider and behind `DeepSeekClient`, `ZaiClient` and `OpenRouterClient`,
 * which used to be three near-identical copies of it
 * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]). What differs between
 * providers is an [[OpenAICompatibleDialect]]; everything else is here:
 *
 *  - `complete`: POST `<baseUrl>/chat/completions`, Bearer auth when there is a
 *    key, `HttpErrorMapper` for non-2xx replies, cost estimation;
 *  - `streamComplete`: the same with `"stream": true`, read as server-sent
 *    events until `[DONE]`, each delta fanned out to one chunk per tool call;
 *  - request building: role mapping, sampling parameters, tools and
 *    `response_format`;
 *  - one provider exchange recorded per call, success or failure; and the
 *    response body closed on every path, success or failure.
 *
 * @param settings        where to send requests and what to report.
 * @param dialect         how this provider departs from the standard format.
 * @param metrics         receives per-call latency and token-usage events.
 * @param exchangeLogging where raw request/response exchanges are recorded, if anywhere.
 */
class OpenAICompatibleClient(
  settings: OpenAICompatibleClient.Settings,
  dialect: OpenAICompatibleDialect,
  protected val metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  private val httpClient = HttpClient.newHttpClient()
  private val logger     = org.slf4j.LoggerFactory.getLogger(getClass)
  private val endpoint   = s"${settings.baseUrl}/chat/completions"

  protected def clientDescription: String = s"${settings.displayName} client for model ${settings.model}"
  protected def providerName: String      = settings.providerName
  protected def modelName: String         = settings.model

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    renderRequest(conversation, options, stream = false).flatMap { requestText =>
      send(requestText, HttpResponse.BodyHandlers.ofString(), timeout = None)
        .tapLeft(error => recordExchange(startedAt, requestText, None, Left(error)))
        .flatMap { response =>
          val body = response.body()
          logger.debug(s"Response status: ${response.statusCode()}")
          logger.debug(s"Response body: ${Redaction.redactForLogging(body)}")
          val result =
            if (response.statusCode() >= 200 && response.statusCode() < 300)
              Try(parseCompletion(ujson.read(body))).toResult
            else HttpErrorMapper.mapHttpError(response.statusCode(), body, providerName)
          recordExchange(startedAt, requestText, Some(body), result)
          result
        }
    }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    renderRequest(conversation, options, stream = true).flatMap { requestText =>
      val rawStream = new StringBuilder
      val result =
        send(requestText, HttpResponse.BodyHandlers.ofInputStream(), timeout = Some(Duration.ofMinutes(5)))
          .flatMap(response => consumeStream(response.statusCode(), response.body(), rawStream, onChunk))
      recordExchange(startedAt, requestText, Option.when(rawStream.nonEmpty)(rawStream.result()), result)
      result
    }
  }

  /**
   * Turns a streaming response into a completion, closing `body` on every path: an error
   * status, a malformed event, an exception from `onChunk`, or success. (Two of the three
   * clients this replaced leaked the body on an error status.) Everything read is appended to
   * `rawStream` for the exchange log.
   */
  protected[provider] def consumeStream(
    statusCode: Int,
    body: InputStream,
    rawStream: StringBuilder,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    if (statusCode != 200) {
      val errorBody =
        Try(Using.resource(body)(in => new String(in.readAllBytes(), StandardCharsets.UTF_8)))
          .getOrElse("<error body unreadable>")
      rawStream.append(errorBody)
      HttpErrorMapper.mapHttpError(statusCode, errorBody, providerName)
    } else Try(Using.resource(body)(readStream(_, rawStream, onChunk))).toResult.flatten

  /** Reads an SSE body to `[DONE]` or end of stream; the caller closes it. */
  private def readStream(
    body: InputStream,
    rawStream: StringBuilder,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    val accumulator = StreamingAccumulator.create()
    val sseParser   = SSEParser.createStreamingParser()
    val reader      = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))
    Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach { line =>
      rawStream.append(line).append('\n')
      sseParser.addChunk(line + "\n")
      while (sseParser.hasEvents)
        sseParser.nextEvent().foreach { event =>
          event.data.filter(_ != "[DONE]").foreach { data =>
            parseStreamingChunks(ujson.read(data)).foreach { chunk =>
              accumulator.addChunk(chunk)
              onChunk(chunk)
            }
          }
        }
    }
    accumulator.toCompletion.map { c =>
      c.copy(model = settings.model, estimatedCost = c.usage.flatMap(u => CostEstimator.estimate(settings.model, u)))
    }
  }

  private def renderRequest(conversation: Conversation, options: CompletionOptions, stream: Boolean): Result[String] =
    Try {
      val body = createRequestBody(conversation, options)
      if (stream) body("stream") = true
      body.render()
    }.toResult
      .tapRight { requestText =>
        logger.debug(s"Sending request to ${settings.displayName} API at $endpoint")
        logger.debug(s"Request body: ${Redaction.redactForLogging(requestText)}")
      }
      .tapLeft(error => recordExchange(Instant.now(), "", None, Left(error)))

  private def send[T](
    requestText: String,
    bodyHandler: HttpResponse.BodyHandler[T],
    timeout: Option[Duration]
  ): Result[HttpResponse[T]] =
    Try {
      val builder = HttpRequest
        .newBuilder()
        .uri(URI.create(endpoint))
        .header("Content-Type", "application/json")
      settings.apiKey.foreach(key => builder.header("Authorization", s"Bearer $key"))
      dialect.headers.foreach((name, value) => builder.header(name, value))
      timeout.foreach(builder.timeout)
      httpClient.send(builder.POST(HttpRequest.BodyPublishers.ofString(requestText)).build(), bodyHandler)
    }.toResult

  /**
   * Builds the request body for `conversation`, without the `stream` flag.
   * Scoped to the provider package so specs can inspect it.
   */
  protected[provider] def createRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj = {
    val messages = conversation.messages.map {
      case UserMessage(content) =>
        ujson.Obj("role" -> "user", "content" -> dialect.encodeContent(content))
      case SystemMessage(content) =>
        ujson.Obj("role" -> "system", "content" -> dialect.encodeContent(content))
      case AssistantMessage(content, toolCalls) =>
        val message = ujson.Obj("role" -> "assistant")
        content.filter(_.nonEmpty) match {
          case Some(text) => message("content") = dialect.encodeContent(text)
          case None if dialect.alwaysSendAssistantContent =>
            message("content") = content.fold[ujson.Value](ujson.Null)(ujson.Str(_))
          case None => ()
        }
        if (toolCalls.nonEmpty) {
          message("tool_calls") = ujson.Arr.from(toolCalls.map { tc =>
            ujson.Obj(
              "id"       -> tc.id,
              "type"     -> "function",
              "function" -> ujson.Obj("name" -> tc.name, "arguments" -> tc.arguments.render())
            )
          })
        }
        message
      case ToolMessage(content, toolCallId) =>
        ujson.Obj("role" -> "tool", "tool_call_id" -> toolCallId, "content" -> dialect.encodeContent(content))
    }

    val body = ujson.Obj(
      "model"       -> settings.model,
      "messages"    -> ujson.Arr.from(messages),
      "temperature" -> options.temperature,
      "top_p"       -> options.topP
    )

    options.maxTokens.foreach(mt => body("max_tokens") = mt)
    if (options.presencePenalty != 0) body("presence_penalty") = options.presencePenalty
    if (options.frequencyPenalty != 0) body("frequency_penalty") = options.frequencyPenalty
    if (options.tools.nonEmpty) body("tools") = new ToolRegistry(options.tools).getOpenAITools()
    options.responseFormat.foreach { fmt =>
      ResponseFormatMapper.toOpenAIResponseFormat(fmt).foreach(rf => body("response_format") = rf)
    }
    dialect.addReasoning(body, settings.model, options)
    body
  }

  /** Reads a non-streaming reply. Throws on a malformed one; `complete` turns that into a `Left`. */
  protected[provider] def parseCompletion(json: ujson.Value): Completion = {
    val choice    = json("choices")(0)
    val message   = choice("message")
    val toolCalls = message.obj.get("tool_calls").filterNot(_.isNull).map(dialect.parseToolCalls).getOrElse(Seq.empty)
    val content   = message.obj.get("content").flatMap(dialect.decodeContent)
    val usage     = json.obj.get("usage").flatMap(parseUsage)

    Completion(
      id = json.obj.get("id").flatMap(_.strOpt).getOrElse(""),
      created = json.obj.get("created").flatMap(_.numOpt).map(_.toLong).getOrElse(0L),
      content = content.getOrElse(""),
      model = json.obj.get("model").flatMap(_.strOpt).getOrElse(settings.model),
      message = AssistantMessage(contentOpt = content, toolCalls = toolCalls.toList),
      toolCalls = toolCalls.toList,
      usage = usage,
      thinking = dialect.thinking(message).orElse(dialect.thinking(choice)),
      estimatedCost = usage.flatMap(u => CostEstimator.estimate(settings.model, u))
    )
  }

  /** Token usage from a `usage` object, or from the first element of a `usage` array. */
  private def parseUsage(usage: ujson.Value): Option[TokenUsage] =
    usage.objOpt.orElse(usage.arrOpt.flatMap(_.headOption).flatMap(_.objOpt)).map { u =>
      val prompt     = u("prompt_tokens").num.toInt
      val completion = u("completion_tokens").num.toInt
      TokenUsage(
        promptTokens = prompt,
        completionTokens = completion,
        totalTokens = u.get("total_tokens").flatMap(_.numOpt).map(_.toInt).getOrElse(prompt + completion),
        thinkingTokens = dialect.reasoningTokens(ujson.Obj.from(u))
      )
    }

  /** One streamed event as chunks: one per tool call, the first also carrying the text, finish reason and thinking. */
  protected[provider] def parseStreamingChunks(json: ujson.Value): Seq[StreamedChunk] =
    json.obj.get("choices").flatMap(_.arrOpt).flatMap(_.headOption) match {
      case None => Seq.empty
      case Some(choice) =>
        val delta        = choice.obj.getOrElse("delta", ujson.Obj())
        val content      = delta.obj.get("content").flatMap(dialect.decodeContent)
        val finishReason = choice.obj.get("finish_reason").flatMap(_.strOpt).filter(_ != "null")
        val thinking     = dialect.thinking(delta)
        val chunkId      = json.obj.get("id").flatMap(_.strOpt).getOrElse("")

        val toolCalls = delta.obj.get("tool_calls").flatMap(_.arrOpt).toSeq.flatten.collect {
          case call if call.obj.contains("function") =>
            val function = call("function")
            ToolCall(
              id = call.obj.get("id").flatMap(_.strOpt).getOrElse(""),
              name = function.obj.get("name").flatMap(_.strOpt).getOrElse(""),
              arguments =
                StreamingToolArgumentParser.parse(function.obj.get("arguments").flatMap(_.strOpt).getOrElse(""))
            )
        }

        val first = StreamedChunk(chunkId, content, toolCalls.headOption, finishReason, thinking)
        first +: toolCalls.drop(1).map(tc => StreamedChunk(chunkId, None, Some(tc), None, None))
    }

  private def recordExchange(
    startedAt: Instant,
    requestBody: String,
    responseBody: Option[String],
    result: Result[?]
  ): Unit =
    ProviderExchangeRecorder.record(
      exchangeLogging = exchangeLogging,
      provider = providerName,
      model = Some(settings.model),
      startedAt = startedAt,
      requestBody = requestBody,
      responseBody = responseBody,
      result = result
    )

  override def getContextWindow(): Int = settings.contextWindow

  override def getReserveCompletion(): Int = settings.reserveCompletion

  override protected def releaseResources(): Unit =
    (httpClient: Any) match {
      case c: AutoCloseable => c.close()
      case _                => ()
    }
}

object OpenAICompatibleClient {

  /**
   * Where an [[OpenAICompatibleClient]] sends requests, and how it describes itself.
   *
   * @param providerName      metrics and exchange-log label, and the provider named in mapped HTTP errors, e.g. `"deepseek"`.
   * @param displayName       human-readable name used in log lines and the "already closed" error, e.g. `"DeepSeek"`.
   * @param model             model identifier sent in every request.
   * @param baseUrl           API base URL; requests go to `<baseUrl>/chat/completions`.
   * @param apiKey            sent as `Authorization: Bearer <key>`; `None` sends no `Authorization` header.
   * @param contextWindow     the model's total token capacity.
   * @param reserveCompletion tokens held back from the prompt for the reply.
   */
  final case class Settings(
    providerName: String,
    displayName: String,
    model: String,
    baseUrl: String,
    apiKey: Option[String],
    contextWindow: Int,
    reserveCompletion: Int
  ) {
    override def toString: String =
      s"Settings(providerName=$providerName, displayName=$displayName, model=$model, baseUrl=$baseUrl, " +
        s"apiKey=${Redaction.secretOpt(apiKey)}, contextWindow=$contextWindow, reserveCompletion=$reserveCompletion)"
  }

  /** The settings the generic `openai-compatible` provider derives from its config. */
  def settings(config: OpenAICompatibleConfig): Settings =
    Settings(
      providerName = OpenAICompatibleConfig.ProviderIdName,
      displayName = "OpenAI-compatible",
      model = config.model,
      baseUrl = config.baseUrl,
      apiKey = config.apiKey,
      contextWindow = config.contextWindow,
      reserveCompletion = config.reserveCompletion
    )

  /**
   * A client for a generic OpenAI-compatible endpoint: the standard dialect,
   * plus `config.headers` on every request.
   */
  def apply(
    config: OpenAICompatibleConfig,
    metrics: MetricsCollector = MetricsCollector.noop,
    exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
  )(using ModelRegistryService): Result[OpenAICompatibleClient] =
    Try(
      new OpenAICompatibleClient(
        settings(config),
        OpenAICompatibleDialect.standard(config.headers.toSeq),
        metrics,
        exchangeLogging
      )
    ).toResult
}
