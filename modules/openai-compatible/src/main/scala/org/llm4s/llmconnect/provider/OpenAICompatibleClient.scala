package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.error.ValidationError
import org.llm4s.http.{ HttpFailures, Llm4sHttpClient }
import org.llm4s.llmconnect.config.{ OpenAICompatibleConfig, ProviderTimeouts }
import org.llm4s.llmconnect.provider.OpenAICompatibleClient.StreamToolCalls
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
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.duration.{ DurationInt, FiniteDuration }
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
 *    key, `HttpErrorMapper` for non-2xx replies, cost estimation, and a
 *    two-minute timeout ([[OpenAICompatibleClient.RequestTimeout]]);
 *  - `streamComplete`: the same with `"stream": true`, read as server-sent
 *    events until `[DONE]`, each delta fanned out to one chunk per tool call,
 *    with a five-minute timeout ([[OpenAICompatibleClient.StreamTimeout]]);
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
@Stable
class OpenAICompatibleClient(
  settings: OpenAICompatibleClient.Settings,
  dialect: OpenAICompatibleDialect,
  protected val metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  // Scoped to the provider package so specs can substitute one
  protected[provider] val httpClient: Llm4sHttpClient = Llm4sHttpClient.create()
  private val logger                                  = org.slf4j.LoggerFactory.getLogger(getClass)
  private val endpoint                                = s"${settings.baseUrl}/chat/completions"

  protected def clientDescription: String = s"${settings.displayName} client for model ${settings.model}"
  protected def providerName: String      = settings.providerName
  protected def modelName: String         = settings.model

  // sealed thinking is replayed only to the provider and model that produced it (see ReplayOrigin);
  // this is the origin a request is about to be sent to
  private val replayOrigin = ReplayOrigin(settings.providerName, settings.model)

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    renderRequest(conversation, options, stream = false).flatMap { requestText =>
      httpClient
        .post(endpoint, requestHeaders, requestText, requestTimeout)
        .tapLeft(error => recordExchange(startedAt, requestText, None, Left(error)))
        .flatMap { response =>
          val body = response.body
          logger.debug(s"Response status: ${response.statusCode}")
          logger.debug(s"Response body: ${Redaction.redactForLogging(body)}")
          val result =
            if (response.statusCode >= 200 && response.statusCode < 300)
              Try(parseCompletion(ujson.read(body))).toResult.map(bindThinking(_, conversation, options))
            else HttpErrorMapper.mapHttpError(response.statusCode, body, providerName, response.headers)
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
        httpClient
          .postStream(endpoint, requestHeaders, requestText, streamTimeout)
          .flatMap(response => consumeStream(response.statusCode, response.body, rawStream, onChunk, response.headers))
          .map(bindThinking(_, conversation, options))
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
    onChunk: StreamedChunk => Unit,
    headers: Map[String, Seq[String]] = Map.empty
  ): Result[Completion] =
    if (statusCode != 200) {
      val errorBody =
        Try(Using.resource(body)(in => new String(in.readAllBytes(), StandardCharsets.UTF_8)))
          .getOrElse("<error body unreadable>")
      rawStream.append(errorBody)
      HttpErrorMapper.mapHttpError(statusCode, errorBody, providerName, headers)
    } else
      // A failure while reading the open body is classified as a transport failure would be
      Try(Using.resource(body)(readStream(_, rawStream, onChunk))).toEither.left
        .map(HttpFailures.streamReadError(_, endpoint, streamTimeout))
        .flatten

  /** Reads an SSE body to `[DONE]` or end of stream; the caller closes it. */
  private def readStream(
    body: InputStream,
    rawStream: StringBuilder,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    val accumulator = StreamingAccumulator.create()
    val toolCalls   = new StreamToolCalls
    val details     = Vector.newBuilder[ujson.Value]
    val sseParser   = SSEParser.createStreamingParser()
    val reader      = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))
    var usage       = Option.empty[TokenUsage]
    var servedModel = Option.empty[String]
    Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach { line =>
      rawStream.append(line).append('\n')
      sseParser.addChunk(line + "\n")
      while (sseParser.hasEvents)
        sseParser.nextEvent().foreach { event =>
          event.data.filter(_ != "[DONE]").foreach { data =>
            val json = ujson.read(data)
            // Usage arrives on the last event, alongside the final delta or on an event of its
            // own with no choices. A later report replaces an earlier one; one without both
            // counts is ignored rather than failing the stream.
            streamedUsage(json).foreach(u => usage = Some(u))
            // the model that served the request, which a router may choose; reported as `Completion.model`
            json.obj.get("model").flatMap(_.strOpt).filter(_.nonEmpty).foreach(m => servedModel = Some(m))
            details ++= streamedThinkingDetails(json)
            parseStreamingEvent(json, toolCalls).foreach { (chunk, rawArguments) =>
              // The accumulator concatenates argument fragments, so it gets each fragment
              // verbatim. The parsed form handed to `onChunk` cannot be concatenated safely:
              // a fragment that is itself valid JSON, such as `"Paris"`, parses to the bare
              // string and would lose its quotes.
              accumulator.addChunk(
                chunk.withToolCall(chunk.toolCall.map(_.copy(arguments = ujson.Str(rawArguments))))
              )
              onChunk(chunk)
            }
          }
        }
    }
    // `StreamingAccumulator.toCompletion` puts the tool calls on the message only; a
    // non-streaming `complete` also reports them as `Completion.toolCalls`, so this does too.
    val opaqueThinking = dialect.decodeThinkingDetails(details.result())
    accumulator.toCompletion.map { c =>
      val finalUsage = usage.orElse(c.usage)
      val message =
        if (opaqueThinking.isEmpty) c.message else c.message.withThinking(c.message.thinking ++ opaqueThinking)
      c.withMessage(message)
        .withModel(servedModel.getOrElse(settings.model))
        .withToolCalls(c.message.toolCalls.toList)
        .withUsage(finalUsage)
        .withEstimatedCost(finalUsage.flatMap(u => CostEstimator.estimate(settings.model, u)))
    }
  }

  /** The replay data on a streamed event's delta, if it has one. */
  private def streamedThinkingDetails(json: ujson.Value): Seq[ujson.Value] =
    json.obj
      .get("choices")
      .flatMap(_.arrOpt)
      .flatMap(_.headOption)
      .flatMap(_.obj.get("delta"))
      .toSeq
      .flatMap(dialect.thinkingDetails)

  /**
   * `completion` with any sealed thinking on its message bound to the request it answered, so that
   * it is replayed only while that request is unchanged (see [[ThinkingReplay]]). A completion with
   * no sealed thinking is returned as it is.
   *
   * The origin is the configured model, the one the next request is checked against, not the model
   * the response reports: a router (OpenRouter's `openrouter/auto`, model fallbacks) chooses the
   * serving model per request and reports it in `completion.model`, and OpenRouter requires the
   * complete `reasoning_details` sequence on a tool-call continuation, so binding to the served
   * model would unseal every routed turn and drop that sequence. Changing the configured model
   * still unseals every earlier turn.
   */
  private def bindThinking(completion: Completion, conversation: Conversation, options: CompletionOptions): Completion =
    if (!completion.message.hasSealedThinking) completion
    else completion.withMessage(ThinkingReplay.bind(replayOrigin, completion.message, conversation.messages, options))

  private def renderRequest(conversation: Conversation, options: CompletionOptions, stream: Boolean): Result[String] =
    // An empty `messages` array is rejected by every chat-completions endpoint; saying so here
    // costs no round trip and names the problem. The check is on the messages that will be
    // sent, not the conversation: a dialect that drops empty assistant turns (Mistral) can
    // reduce a non-empty conversation to nothing.
    Either
      .cond(
        sendableMessages(conversation, options).nonEmpty,
        (),
        ValidationError("conversation", s"${settings.displayName} requires at least one message")
      )
      .flatMap { _ =>
        Try {
          val body = createRequestBody(conversation, options)
          if (stream) {
            body("stream") = true
            if (dialect.streamUsageOption) body("stream_options") = ujson.Obj("include_usage" -> true)
          }
          body.render()
        }.toResult
      }
      .tapRight { requestText =>
        logger.debug(s"Sending request to ${settings.displayName} API at $endpoint")
        logger.debug(s"Request body: ${Redaction.redactForLogging(requestText)}")
      }
      .tapLeft(error => recordExchange(Instant.now(), "", None, Left(error)))

  /**
   * How long `complete` waits for a response before failing with a timeout. It used to wait
   * forever: `complete` set no timeout, as the old `DeepSeekClient`, `ZaiClient` and
   * `OpenRouterClient` had not (#912), so an endpoint that accepted the connection and never
   * answered hung the caller. Scoped to the provider package so specs can shorten it.
   */
  protected[provider] def requestTimeout: FiniteDuration =
    settings.timeouts.requestOr(OpenAICompatibleClient.RequestTimeout)

  /** The timeout `streamComplete` sends with its request. See [[requestTimeout]]. */
  protected[provider] def streamTimeout: FiniteDuration =
    settings.timeouts.streamOr(OpenAICompatibleClient.StreamTimeout)

  /**
   * The headers every request carries. A header the dialect repeats is sent once, its values
   * comma-joined in order, which HTTP defines as equivalent (RFC 9110 section 5.3). Scoped to the
   * provider package so specs can inspect them.
   */
  protected[provider] def requestHeaders: Map[String, String] =
    Map("Content-Type" -> "application/json") ++
      settings.apiKey.map(key => "Authorization" -> s"Bearer $key") ++
      OpenAICompatibleClient.combineRepeated(dialect.headers)

  /**
   * The messages of `conversation` that go into a request, as they may be replayed: every assistant
   * turn whose sealed thinking no longer matches the conversation before it unsealed
   * ([[ThinkingReplay.replayable]]), and then all of them, except an assistant
   * turn with no text, no tool calls and no thinking the dialect encodes, when the dialect does
   * not send empty turns ([[OpenAICompatibleDialect.sendEmptyAssistantTurns]]). Both the request
   * body and the empty-conversation check use this, so they cannot disagree.
   *
   * A thinking-only turn - a generation that hit its token limit while still reasoning - is not
   * empty for a dialect that encodes thinking: Mistral asks for the full assistant message,
   * thinking chunks included, to be replayed, and its thinking chunk is `content`. For a dialect
   * that drops thinking ([[OpenAICompatibleDialect.encodeThinking]] adds nothing) the turn is
   * still empty and still left out.
   */
  private def sendableMessages(conversation: Conversation, options: CompletionOptions): Seq[Message] =
    ThinkingReplay.replayable(replayOrigin, conversation.messages, options).filterNot {
      case am: AssistantMessage =>
        !dialect.sendEmptyAssistantTurns && am.contentOpt.forall(_.isEmpty) && am.toolCalls.isEmpty &&
        !encodesThinking(am)
      case _ => false
    }

  /** Whether the dialect adds anything to an assistant message for `am`'s thinking. */
  private def encodesThinking(am: AssistantMessage): Boolean =
    am.hasThinking && {
      val probe = ujson.Obj("role" -> "assistant")
      dialect.encodeThinking(probe, am.thinking)
      probe.value.keySet != Set("role")
    }

  /**
   * Builds the request body for `conversation`, without the `stream` flag.
   * Scoped to the provider package so specs can inspect it.
   */
  protected[provider] def createRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj = {
    val messages = sendableMessages(conversation, options).map {
      case UserMessage(content) =>
        ujson.Obj("role" -> "user", "content" -> dialect.encodeContent(content))
      case SystemMessage(content) =>
        ujson.Obj("role" -> dialect.systemRole, "content" -> dialect.encodeContent(content))
      case am: AssistantMessage =>
        val content   = am.contentOpt
        val toolCalls = am.toolCalls
        val message   = ujson.Obj("role" -> "assistant")
        content.filter(_.nonEmpty) match {
          case Some(text) => message("content") = dialect.encodeContent(text)
          case None if dialect.alwaysSendAssistantContent =>
            message("content") = content.fold[ujson.Value](ujson.Null)(ujson.Str(_))
          case None => ()
        }
        if (toolCalls.nonEmpty) {
          message("tool_calls") = ujson.Arr.from(toolCalls.map { tc =>
            ujson.Obj(
              "id"       -> dialect.encodeToolCallId(tc.id),
              "type"     -> "function",
              "function" -> ujson.Obj("name" -> tc.name, "arguments" -> tc.arguments.render())
            )
          })
        }
        if (am.hasThinking) dialect.encodeThinking(message, am.thinking)
        message
      case ToolMessage(content, toolCallId) =>
        ujson.Obj(
          "role"         -> "tool",
          "tool_call_id" -> dialect.encodeToolCallId(toolCallId),
          "content"      -> dialect.encodeContent(content)
        )
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
      dialect.encodeResponseFormat(fmt).foreach(rf => body("response_format") = rf)
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
    val thinkingText = dialect.thinking(message).orElse(dialect.thinking(choice)).filter(_.nonEmpty)
    val thinking =
      thinkingText.map(ThinkingBlock.Text(_)).toSeq ++
        dialect.decodeThinkingDetails(dialect.thinkingDetails(message))

    Completion(
      id = json.obj.get("id").flatMap(_.strOpt).getOrElse(""),
      created = json.obj.get("created").flatMap(_.numOpt).map(_.toLong).getOrElse(0L),
      content = content.getOrElse(""),
      model = json.obj.get("model").flatMap(_.strOpt).getOrElse(settings.model),
      message = AssistantMessage(contentOpt = content, toolCalls = toolCalls.toList, thinking = thinking),
      toolCalls = toolCalls.toList,
      usage = usage,
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

  /**
   * The token usage a streamed event reports, if it reports a usable one.
   *
   * Providers that report usage on a stream do so on its last event - Mistral and DeepSeek
   * always, OpenAI and servers following it when asked with `stream_options.include_usage`
   * ([[OpenAICompatibleDialect.streamUsageOption]]) - either on the final delta or on an event
   * of its own with empty `choices`, and send `"usage": null` or omit it elsewhere. Unlike [[parseUsage]] on a completion, a malformed report here is
   * dropped rather than failing a stream whose text has already been delivered.
   */
  private def streamedUsage(json: ujson.Value): Option[TokenUsage] =
    json.objOpt.flatMap(_.get("usage")).flatMap(u => Try(parseUsage(u)).toOption.flatten)

  /**
   * One streamed event as chunks: one per tool call, the first also carrying the text, finish
   * reason and thinking. Scoped to the provider package so specs can inspect it.
   *
   * `toolCalls` is the state of the stream this event belongs to - see [[StreamToolCalls]]. The
   * default, a fresh one, is right only for an event read on its own.
   */
  protected[provider] def parseStreamingChunks(
    json: ujson.Value,
    toolCalls: StreamToolCalls = new StreamToolCalls
  ): Seq[StreamedChunk] =
    parseStreamingEvent(json, toolCalls).map(_._1)

  /** As [[parseStreamingChunks]], pairing each chunk with its raw argument fragment. */
  private def parseStreamingEvent(json: ujson.Value, toolCalls: StreamToolCalls): Seq[(StreamedChunk, String)] =
    json.obj.get("choices").flatMap(_.arrOpt).flatMap(_.headOption) match {
      case None => Seq.empty
      case Some(choice) =>
        val delta        = choice.obj.getOrElse("delta", ujson.Obj())
        val content      = delta.obj.get("content").flatMap(dialect.decodeContent)
        val finishReason = choice.obj.get("finish_reason").flatMap(_.strOpt).filter(_ != "null")
        val thinking     = dialect.thinking(delta)
        val chunkId      = json.obj.get("id").flatMap(_.strOpt).getOrElse("")

        val calls = delta.obj.get("tool_calls").flatMap(_.arrOpt).toSeq.flatten.zipWithIndex.collect {
          case (call, position) if call.obj.contains("function") =>
            val function = call("function")
            val raw      = function.obj.get("arguments").flatMap(_.strOpt).getOrElse("")
            val (id, name) = toolCalls.resolve(
              index = call.obj.get("index").flatMap(_.numOpt).map(_.toInt).getOrElse(position),
              id = call.obj.get("id").flatMap(_.strOpt).filter(_.nonEmpty),
              name = function.obj.get("name").flatMap(_.strOpt).filter(_.nonEmpty)
            )
            (ToolCall(id, name, StreamingToolArgumentParser.parse(raw)), raw)
        }

        val first = (
          StreamedChunk(chunkId, content, calls.headOption.map(_._1), finishReason, thinking),
          calls.headOption.fold("")(_._2)
        )
        first +: calls.drop(1).map((tc, raw) => (StreamedChunk(chunkId, None, Some(tc), None, None), raw))
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

  override protected def releaseResources(): Unit = httpClient.close()
}

object OpenAICompatibleClient {

  /** `headers` with each repeated name (matched case-insensitively) sent once, its values comma-joined in order. */
  private[provider] def combineRepeated(headers: Seq[(String, String)]): Seq[(String, String)] =
    headers
      .groupBy(_._1.toLowerCase)
      .values
      .map(group => group.head._1 -> group.map(_._2).mkString(", "))
      .toSeq

  /**
   * The timeout on `complete`'s request: two minutes, what the old `MistralClient` and
   * `CohereClient` used, and what `OllamaClient`, `GeminiClient` and `VertexAIClient` use. This is
   * the default: a section's `timeouts.request` replaces it
   * ([[https://github.com/llm4s/llm4s/issues/712 #712]]).
   */
  val RequestTimeout: FiniteDuration = 2.minutes

  /**
   * The timeout on `streamComplete`'s request: five minutes, as in the clients this one replaced. A
   * section's `timeouts.stream` replaces it.
   */
  val StreamTimeout: FiniteDuration = 5.minutes

  /**
   * The tool calls seen so far in one stream, by their `index`.
   *
   * A streamed tool call is split across deltas: the first carries its `id`, `name` and the
   * start of its arguments, and each continuation carries only its `index` and the next
   * argument fragment. Several calls can be interleaved, told apart by `index`. Continuations
   * are given the id and name their index was first seen with, so the chunks a caller receives
   * - and the ones `StreamingAccumulator`, which keys calls by id and skips a chunk with none,
   * accumulates - all name their call. (Before #1132 each client defaulted a missing id to `""`,
   * and every continuation's arguments were dropped.)
   */
  final class StreamToolCalls {
    private val byIndex = scala.collection.mutable.Map.empty[Int, (String, String)]

    /** The id and name for a call at `index`, recording what this delta supplies. */
    private[provider] def resolve(index: Int, id: Option[String], name: Option[String]): (String, String) = {
      val (knownId, knownName) = byIndex.getOrElse(index, ("", ""))
      val resolved             = (id.getOrElse(knownId), name.getOrElse(knownName))
      if (resolved._1.nonEmpty) byIndex(index) = resolved
      resolved
    }
  }

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
    reserveCompletion: Int,
    timeouts: ProviderTimeouts = ProviderTimeouts.default
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
      reserveCompletion = config.reserveCompletion,
      timeouts = config.timeouts
    )

  /**
   * A client for a generic OpenAI-compatible endpoint: the standard dialect,
   * plus `config.headers` on every request, asking for streamed usage unless
   * `config.streamUsage` is off.
   */
  def apply(
    config: OpenAICompatibleConfig,
    metrics: MetricsCollector = MetricsCollector.noop,
    exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
  )(using ModelRegistryService): Result[OpenAICompatibleClient] =
    Try(
      new OpenAICompatibleClient(
        settings(config),
        OpenAICompatibleDialect.standard(config.headers.toSeq, config.streamUsage),
        metrics,
        exchangeLogging
      )
    ).toResult
}
