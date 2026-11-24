package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.annotation.Stable
import org.llm4s.error.{
  LLMError,
  ModelNotFoundError,
  NetworkError,
  ServiceError,
  TroubleshootingLinks,
  ValidationError
}
import org.llm4s.http.{ HttpFailures, Llm4sHttpClient }
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.media.MediaSource
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.model.{ AdjustmentLog, ModelRegistryService, UnmappedOptions }
import org.llm4s.syntax.toResult
import org.llm4s.types.Result
import org.llm4s.util.{ BoundedJson, Redaction }
import org.slf4j.LoggerFactory

import java.io.{ BufferedReader, InputStreamReader }
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.duration.*
import scala.util.{ Try, Using }

/**
 * [[LLMClient]] implementation for locally-hosted Ollama models.
 *
 * Connects to an Ollama server via its HTTP chat API (`/api/chat`).
 * All Ollama-specific protocol details (JSON-lines streaming, token-count
 * field names) are handled internally.
 *
 * == Tool calling ==
 *
 * Tools in `CompletionOptions.tools` are sent as the `tools` field
 * (`{type: "function", function: {name, description, parameters}}`). A reply's
 * `message.tool_calls` - whole, or streamed - becomes `ToolCall`s. Ollama's native API
 * sends no call ids, so the client synthesizes them (`call_<12 hex>_<index>`, a fresh prefix per
 * reply or stream); an id the server does send is kept. An assistant turn's tool calls are sent
 * back as Ollama's native history records them: each with its `id`, its position in the turn as
 * `function.index` (Ollama reads an omitted index as `0`, so parallel calls would collide) and
 * object arguments. A `ToolMessage` is sent as `role: tool` with the `tool_call_id` it answers
 * and, when that call is in the conversation, its `tool_name`. A malformed `tool_calls` entry is a `ProcessingError`.
 * A model without the tools capability makes Ollama answer HTTP 400 (`... does not support tools`); that is a
 * [[org.llm4s.error.ValidationError]] on `tools` naming the model, and the request is not retried without its tools.
 * Whether the model calls tools at all depends on the model.
 *
 * == Thinking ==
 *
 * A reply's `message.thinking` becomes the returned message's [[org.llm4s.llmconnect.model.AssistantMessage.thinking AssistantMessage.thinking]] (and so
 * [[org.llm4s.llmconnect.model.Completion.thinking Completion.thinking]]); streamed, each line's `thinking` is a [[org.llm4s.llmconnect.model.StreamedChunk.thinkingDelta StreamedChunk.thinkingDelta]] and
 * the accumulated text is the message's thinking. `CompletionOptions.thinking` and `reasoning` are sent as `think`:
 * `true` or `false`, or a level for a model that takes levels, as `/api/show` lists them (see [[OllamaThink]]).
 * An assistant message's thinking text is sent back
 * as its `thinking`, as Ollama's tool-calling guide asks: a thinking model's follow-up request after
 * a tool call carries its earlier reasoning with the content and tool calls (#1381).
 *
 * == Structured output ==
 *
 * [[org.llm4s.llmconnect.model.CompletionOptions.responseFormat CompletionOptions.responseFormat]] is honoured through the top-level `format`
 * field of `/api/chat`, on both the streaming and non-streaming paths:
 *
 *  - `None` sends no `format` key.
 *  - [[org.llm4s.llmconnect.model.ResponseFormat.Json ResponseFormat.Json]] sends `"format": "json"` (JSON mode).
 *  - [[org.llm4s.llmconnect.model.ResponseFormat.JsonSchema ResponseFormat.JsonSchema]] sends `"format": <schema object>` (structured outputs,
 *    which requires Ollama 0.5 or later; older servers reject or ignore the object).
 *    The `name` and `strict` parameters have no Ollama equivalent and are ignored.
 *
 * == Streaming ==
 *
 * Token counts (`prompt_eval_count`, `eval_count`) are only present in the
 * final JSON-lines chunk (`done: true`). The accumulator updates its count
 * at that point; chunks before the final one report zero tokens.
 *
 * == Timeouts ==
 *
 * Non-streaming requests time out after 120 seconds; streaming requests
 * after 600 seconds. A section's `timeouts { request = ..., stream = ... }` block overrides
 * either ([[org.llm4s.llmconnect.config.OllamaConfig.timeouts OllamaConfig.timeouts]]).
 *
 * @param config  Ollama configuration containing the model name and base URL.
 * @param metrics Receives per-call latency and token-usage events.
 *                Defaults to `MetricsCollector.noop`.
 *
 * Build one with the companion's factory, or let `LLMConnect.getClient` build it from a named section: the
 * constructor is not public, so that an option can be added without breaking a caller, and the class is final.
 */
@Stable
final class OllamaClient private[provider] (
  config: OllamaConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  httpClient: Llm4sHttpClient = Llm4sHttpClient.FromConfig
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  // The injected client, or one routed by the config's proxy and TLS settings when none was given.
  private[provider] val http: Llm4sHttpClient = Llm4sHttpClient.resolve(httpClient, config.network)

  protected def clientDescription: String = s"Ollama client for model ${config.model}"
  protected def providerName: String      = "ollama"
  protected def modelName: ModelName      = config.model

  /** How long a non-streaming call may take: the section's `timeouts.request`, else [[OllamaClient.DefaultRequestTimeout]]. */
  private[provider] def requestTimeout: FiniteDuration = config.timeouts.requestOr(OllamaClient.DefaultRequestTimeout)

  /** How long a streamed call may take: the section's `timeouts.stream`, else [[OllamaClient.DefaultStreamTimeout]]. */
  private[provider] def streamTimeout: FiniteDuration = config.timeouts.streamOr(OllamaClient.DefaultStreamTimeout)

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    connect(conversation, options)
  }

  private def connect(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
    val think = thinkPlan(options)
    requestBody(conversation, options, stream = false, think).flatMap { body =>
      val adjustments = unsent(options, think)
      send(body, options).map(_.withAdjustments(adjustments))
    }
  }

  // what the request does not send goes on every Completion; this warns once per model and option
  private lazy val adjustmentLog = new AdjustmentLog(providerName)

  /**
   * What `options` asks for that the request does not send as asked: `think`'s report, any prompt cache, and any
   * service tier, which Ollama has none of.
   */
  private def unsent(options: CompletionOptions, think: OllamaThink.Plan): List[RequestAdjustment] = {
    val adjustments = think.adjustments ++ UnmappedOptions.promptCache(config.model.asString, options) ++
      UnmappedOptions.serviceTier("Ollama", options)
    adjustmentLog.record(config.model.asString, adjustments)
    adjustments
  }

  // the `think` values the model takes, from `/api/show`, once a reply has said; see `thinkControls`
  @volatile private var discoveredThink: Option[OllamaThink.Controls] = None

  /**
   * The `think` values the model takes. `/api/show` lists them in `thinking.values`, so it is asked once, on the first
   * request that sets `thinking` or `reasoning`, and its answer kept. A reply without them (a server older than the
   * metadata) is read from its `capabilities` (see [[OllamaThink.fromShow]]), or else kept as the controls
   * [[OllamaThink.byName]] gives; a failed call is not kept, so a later request asks again, and this one uses
   * [[OllamaThink.byName]].
   */
  private def thinkControls(): OllamaThink.Controls =
    discoveredThink.getOrElse {
      val headers = Map("Content-Type" -> "application/json")
      val body    = ujson.Obj("model" -> config.model.asString).render()
      http.post(s"${config.baseUrl}/api/show", headers, body, timeout = requestTimeout) match {
        case Right(response) if response.statusCode >= 200 && response.statusCode < 300 =>
          val controls = Try(ujson.read(response.body)).toOption
            .flatMap(OllamaThink.fromShow(config.model.asString, _))
            .getOrElse(OllamaThink.byName(config.model.asString))
          discoveredThink = Some(controls)
          controls
        case other =>
          val why = other.fold(_.message, response => s"HTTP ${response.statusCode}")
          OllamaClient.logger.debug(s"Ollama /api/show for ${config.model} failed ($why); think follows the model name")
          OllamaThink.byName(config.model.asString)
      }
    }

  /** The `think` field for `options`, asking for the model's controls only when it sets thinking or reasoning. */
  private def thinkPlan(options: CompletionOptions): OllamaThink.Plan =
    if (!OllamaThink.asked(options)) OllamaThink.Plan(None, Nil)
    else OllamaThink.plan(config.model.asString, options, thinkControls())

  private def send(requestBody: ujson.Obj, options: CompletionOptions): Result[Completion] = {
    val requestText = requestBody.render()
    val url         = s"${config.baseUrl}/api/chat"
    val headers     = Map("Content-Type" -> "application/json")
    val startedAt   = Instant.now()
    http.post(url, headers, requestText, timeout = requestTimeout) match {
      case Left(error) =>
        recordingExchange(startedAt, requestText, "")(Left(OllamaClient.notRunningHint(error)))
      case Right(response) =>
        val result =
          if (response.statusCode >= 200 && response.statusCode < 300) {
            Try(ujson.read(response.body)).toResult
              .flatMap(json => Try(parseCompletion(json)).toResult.flatMap(identity))
              // RFC 0009, Part 3: a reply `length` cut while it called a tool runs no call
              .flatMap(ToolCallRules.checked)
          } else {
            mapError(response.statusCode, response.body, response.headers, options)
          }
        recordingExchange(startedAt, requestText, response.body)(result)
    }
  }

  /**
   * Maps a non-2xx reply. An HTTP 400 whose message says the model has no tool support, on a request
   * that sent tools, becomes a [[org.llm4s.error.ValidationError]] on `tools` that names the model: Ollama
   * answers that way (`... does not support tools`) to a model whose capabilities lack tool calling.
   * Every other reply goes through [[HttpErrorMapper]]. The request is never retried without its tools:
   * dropping them would change what the caller asked for.
   */
  private def mapError(
    status: Int,
    body: String,
    headers: Map[String, Seq[String]],
    options: CompletionOptions
  ): Result[Nothing] =
    if (status == 400 && options.tools.nonEmpty && OllamaClient.reportsNoToolSupport(body))
      Left(
        ValidationError(
          "tools",
          s"Ollama model '${config.model}' does not support tool calling " +
            s"(the server said: ${OllamaClient.serverMessage(body)}). " +
            "Use a model whose Ollama page lists the 'tools' capability, or send no tools."
        )
      )
    else if (status == 404) {
      // Ollama answers 404 for a model it has not pulled: say which command fetches it (#2285).
      val details = HttpErrorMapper.extractErrorDetails(body, status, providerName)
      val action  = TroubleshootingLinks.ollamaModelMissingAction(config.model.asString)
      val entry   = TroubleshootingLinks.Entry.OllamaModelMissing
      Left(
        // `model "x" not found` is the typed error; any other 404 (a wrong path) keeps its status
        if (ProviderErrorSignals.isModelNotFound(status, HttpErrorMapper.errorCodes(body), details))
          ModelNotFoundError(providerName, details)
            .withModel(config.model.asString)
            .withHttpStatus(status)
            .withHint(action, entry)
        else ServiceError(404, providerName, details).withHint(action, entry)
      )
    } else Left(HttpErrorMapper.naming(HttpErrorMapper.toError(status, body, providerName, headers), config.model.asString))

  private def recordExchange(
    startedAt: Instant,
    requestBody: String,
    responseBody: String,
    result: Result[Completion]
  ): Unit =
    ProviderExchangeRecorder.record(
      exchangeLogging,
      ProviderExchangeCall(
        provider = ProviderId(providerName),
        startedAt = startedAt,
        requestBody = requestBody,
        model = Some(config.model),
        responseBody = Option(responseBody).filter(_.nonEmpty)
      ),
      result
    )

  /**
   * Records the provider exchange and returns `result` unchanged. Isolated so the
   * eight record-then-return call sites in `connect` and `streamComplete` (one per
   * success/error branch) don't each repeat the pairing.
   */
  private def recordingExchange(
    startedAt: Instant,
    requestBody: String,
    responseBody: String
  )(result: Result[Completion]): Result[Completion] = {
    recordExchange(startedAt, requestBody, responseBody, result)
    result
  }

  override def streamComplete(conversation: Conversation, options: CompletionOptions = CompletionOptions())(
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val think = thinkPlan(options)
    requestBody(conversation, options, stream = true, think).flatMap { body =>
      val adjustments = unsent(options, think)
      stream(body, options, onChunk).map(_.withAdjustments(adjustments))
    }
  }

  private def stream(
    requestBody: ujson.Obj,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    val requestText = requestBody.render()
    val url         = s"${config.baseUrl}/api/chat"
    val headers     = Map("Content-Type" -> "application/json")
    val startedAt   = Instant.now()
    val rawResponse = new StringBuilder

    http.postStream(url, headers, requestText, timeout = streamTimeout) match {
      case Left(error) =>
        recordingExchange(startedAt, requestText, "")(Left(OllamaClient.notRunningHint(error)))
      case Right(response) if response.statusCode != 200 =>
        val err = Using(response.body)(in => new String(in.readAllBytes(), StandardCharsets.UTF_8)).getOrElse("")
        recordingExchange(startedAt, requestText, err)(
          mapError(response.statusCode, err, response.headers, options)
        )
      case Right(response) =>
        val accumulator = StreamingAccumulator(config.model)
        val ids         = new OllamaClient.CallIds
        var failure     = Option.empty[org.llm4s.error.LLMError]
        var sawCalls    = false
        var sawDone     = false // `done: true` ends the reply; a stream without it was cut off (RFC 0009, rule 1)
        val processResult = Using(new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))) {
          reader =>
            // Check `failure` before reading: a downstream predicate runs only after the next
            // `readLine()`, which would block on a server that holds the connection open.
            Iterator.continually(if (failure.isEmpty) reader.readLine() else null).takeWhile(_ != null).foreach {
              line =>
                rawResponse.append(line).append('\n')
                val trimmed = line.trim
                if (trimmed.nonEmpty) {
                  val json = ujson.read(trimmed)
                  // Ollama streams incremental content in json lines
                  val done = json.obj.get("done").exists(_.bool)
                  sawDone = sawDone || done
                  val contentOpt = json.obj
                    .get("message")
                    .flatMap(_.obj.get("content"))
                    .flatMap(_.strOpt)
                    .filter(_.nonEmpty)
                  val thinkingOpt = OllamaClient.thinking(json.obj.get("message"))
                  val id          = json.obj.get("id").flatMap(_.strOpt).getOrElse("")

                  // an error after the 200 status, `{"error":"..."}`, ends the stream with it (RFC 0009, rule 2)
                  OllamaClient.streamError(json, providerName).foreach(error => failure = Some(error))
                  if (failure.isEmpty) OllamaClient.parseToolCalls(json.obj.get("message"), ids) match {
                    case Left(error) => failure = Some(error)
                    case Right(calls) =>
                      sawCalls = sawCalls || calls.nonEmpty
                      val finish = Option.when(done)(OllamaClient.finishReason(json, sawCalls))
                      val head = StreamedChunk(
                        id = id,
                        contentDelta = contentOpt,
                        toolCallDelta = None,
                        finishReason = finish,
                        thinkingDelta = thinkingOpt
                      )
                      // a line with calls and nothing else needs no empty content chunk
                      val chunks =
                        if (calls.isEmpty) Seq(head)
                        else {
                          val callChunks = calls
                            .map(c => StreamedChunk(id = id, contentDelta = None, toolCallDelta = Some(c), finishReason = None))
                          val withText =
                            if (contentOpt.isDefined || thinkingOpt.isDefined) Seq(head.withFinishReason(None))
                            else Seq.empty
                          val closing =
                            if (finish.isDefined)
                              Seq(head.withContentDelta(None: Option[String]).withThinkingDelta(None: Option[String]))
                            else Seq.empty
                          withText ++ callChunks ++ closing
                        }
                      chunks.foreach { chunk =>
                        accumulator.addChunk(ToolCallRules.wholeCall(chunk))
                        onChunk(chunk)
                      }

                      // token counts (if present) only appear at the end
                      if (done) {
                        val prompt = json.obj.get("prompt_eval_count").flatMap(_.numOpt).map(_.toInt).getOrElse(0)
                        val comp   = json.obj.get("eval_count").flatMap(_.numOpt).map(_.toInt).getOrElse(0)
                        accumulator.updateUsage(TokenUsage(prompt, comp, prompt + comp))
                      }
                  }
                }
            }
        }.toEither.left.map(HttpFailures.streamReadError(_, url, streamTimeout))

        val result = processResult
          .flatMap(_ => failure.toLeft(()))
          .filterOrElse(_ => sawDone, OllamaClient.unfinished(url))
          .flatMap(_ => accumulator.toCompletion)
          .map { c =>
            // the accumulator reports streamed calls on the message only
            val cost = c.usage.flatMap(u => CostEstimator.estimate(config.model, u))
            c.withEstimatedCost(cost)
          }

        recordingExchange(startedAt, requestText, rawResponse.result())(result)
    }
  }

  private[provider] def createRequestBody(
    conversation: Conversation,
    options: CompletionOptions,
    stream: Boolean
  ): Result[ujson.Obj] = requestBody(conversation, options, stream, thinkPlan(options))

  private def requestBody(
    conversation: Conversation,
    options: CompletionOptions,
    stream: Boolean,
    think: OllamaThink.Plan
  ): Result[ujson.Obj] =
    PartEncoding
      .check(providerName, conversation.messages, OllamaClient.userPart, PartEncoding.textOnly)
      .map(_ => encode(conversation, options, stream, think))

  private def encode(
    conversation: Conversation,
    options: CompletionOptions,
    stream: Boolean,
    think: OllamaThink.Plan
  ): ujson.Obj = {
    val toolNames = conversation.messages
      .collect { case am: AssistantMessage => am.toolCalls }
      .flatten
      .map(tc => tc.id -> tc.name)
      .toMap
    val msgs = ujson.Arr.from(conversation.messages.map {
      case SystemMessage(content) => ujson.Obj("role" -> "system", "content" -> content)
      case um: UserMessage        =>
        // checked above: every part is text or an inline image
        val encoded = um.parts.map(OllamaClient.userPart)
        val message =
          ujson.Obj("role" -> "user", "content" -> encoded.collect { case Left(text) => text }.mkString("\n"))
        val images = encoded.collect { case Right(image) => ujson.Str(image) }
        if (images.nonEmpty) message("images") = ujson.Arr.from(images)
        message
      case am: AssistantMessage =>
        val message = ujson.Obj("role" -> "assistant", "content" -> am.content)
        am.thinkingText.foreach(thinking => message("thinking") = thinking)
        if (am.toolCalls.nonEmpty)
          message("tool_calls") = ujson.Arr.from(am.toolCalls.zipWithIndex.map { case (tc, index) =>
            ujson.Obj(
              "id" -> tc.id.asString,
              "function" -> ujson.Obj(
                "index"     -> index,
                "name"      -> tc.name,
                "arguments" -> OllamaClient.requestArguments(tc.name, tc.arguments)
              )
            )
          })
        message
      case tm: ToolMessage =>
        val toolCallId = tm.toolCallId
        val message    = ujson.Obj("role" -> "tool", "content" -> tm.content, "tool_call_id" -> toolCallId.asString)
        toolNames.get(toolCallId).foreach(name => message("tool_name") = name)
        message
    })

    // the sampling options are sent only when the caller set them: unset, the model's own defaults apply
    val opts = ujson.Obj()
    options.temperature.foreach(t => opts("temperature") = t)
    options.topP.foreach(p => opts("top_p") = p)
    options.maxTokens.foreach(t => opts("num_predict") = t)

    val body = ujson.Obj(
      "model"    -> config.model.asString,
      "messages" -> msgs,
      "stream"   -> stream,
      "options"  -> opts
    )
    if (options.tools.nonEmpty)
      body("tools") = ujson.Arr.from(options.tools.map(t => OllamaClient.encodeTool(t.toolDefinition(strict = false))))
    options.responseFormat.foreach(rf => body("format") = OllamaClient.encodeFormat(rf))
    think.think.foreach(value => body("think") = value)
    body
  }

  private def parseCompletion(json: ujson.Value): Result[Completion] = {
    val id      = json.obj.get("id").flatMap(_.strOpt).getOrElse(java.util.UUID.randomUUID().toString)
    val created = Instant.now()
    val content = json.obj
      .get("message")
      .flatMap(_.obj.get("content"))
      .flatMap(_.strOpt)
      .getOrElse("")

    val usage = for {
      prompt <- json.obj.get("prompt_eval_count").flatMap(_.numOpt).map(_.toInt)
      comp   <- json.obj.get("eval_count").flatMap(_.numOpt).map(_.toInt)
    } yield TokenUsage(prompt, comp, prompt + comp)

    // Estimate cost using CostEstimator
    val cost = usage.flatMap(u => CostEstimator.estimate(config.model, u))

    OllamaClient.parseToolCalls(json.obj.get("message"), new OllamaClient.CallIds).map { calls =>
      Completion(
        id = id,
        created = created,
        usage = usage,
        model = config.model,
        message = (if (calls.isEmpty) AssistantMessage(content)
                   else AssistantMessage(Some(content).filter(_.nonEmpty), calls))
          .withThinking(OllamaClient.thinking(json.obj.get("message")).getOrElse("")),
        estimatedCost = cost,
        finishReason = OllamaClient.finishReason(json, calls.nonEmpty)
      )
    }
  }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  /**
   * Asks the server for the configured model with `POST /api/show`, which loads nothing and generates nothing.
   * `Right(())` means the server answered and has the model. A model that is not pulled is the `ServiceError` 404 a
   * completion gets, with the `ollama pull` hint; nothing listening at the base URL is a `NetworkError` with the
   * hint to start the server. Ollama takes no key, so none is checked.
   */
  override def validate(): Result[Unit] = validateNotClosed.flatMap { _ =>
    val headers = Map("Content-Type" -> "application/json")
    val body    = ujson.Obj("model" -> config.model.asString).render()
    http
      .post(s"${config.baseUrl}/api/show", headers, body, timeout = requestTimeout.min(OllamaClient.ValidationTimeout))
      .left
      .map(OllamaClient.notRunningHint)
      .flatMap { response =>
        if (response.statusCode >= 200 && response.statusCode < 300) Right(())
        else mapError(response.statusCode, response.body, response.headers, CompletionOptions())
      }
  }

  // A client the caller supplied is the caller's to close (#2341).
  override protected def releaseResources(): Unit = Llm4sHttpClient.closeIfOwned(httpClient, http)
}

object OllamaClient {

  /**
   * The failure a streamed line reports, `{"error":"..."}`, which Ollama sends once the 200 status is out (a model
   * that fails to load, a runner that crashes). It states no status, so it is a 502, retried like a bad gateway.
   */
  private[provider] def streamError(json: ujson.Value, provider: String): Option[LLMError] =
    json.objOpt
      .flatMap(_.get("error"))
      .filter(error => error != ujson.Null && error != ujson.Str(""))
      .map { error =>
        val message = error.strOpt.orElse(error.objOpt.flatMap(_.get("message")).flatMap(_.strOpt))
        HttpErrorMapper.mapStreamError(502, message.getOrElse(error.render()), provider)
      }

  /** A stream that ended without `done: true`: cut off, so a retry may succeed (RFC 0009, rule 1). */
  private[provider] def unfinished(url: String): NetworkError =
    NetworkError("stream ended without done: true; the reply is incomplete", None, url)

  /**
   * A refused connection - nothing listening at the section's `baseUrl` - with the command that starts the
   * server and its troubleshooting link (#2285). Any other error is returned unchanged.
   */
  private[provider] def notRunningHint(error: LLMError): LLMError = error match {
    case network: NetworkError if network.message.contains(": connection failed:") =>
      network.withHint(TroubleshootingLinks.OllamaNotRunningAction, TroubleshootingLinks.Entry.OllamaNotRunning)
    case other => other
  }

  /**
   * The user-message parts Ollama's `/api/chat` takes: text, joined into `content`, and inline images,
   * sent raw base64 in `images`. It fetches no URL and has no files, and takes no documents.
   */
  private[provider] val userPart: PartialFunction[ContentPart, Either[String, String]] = {
    case ContentPart.Text(text) => Left(text)
    case ContentPart.Image(inline: MediaSource.Inline, _) =>
      Right(PartEncoding.base64(inline))
  }

  /** The timeout of a non-streaming call when the section sets no `timeouts.request`: two minutes. */
  val DefaultRequestTimeout: FiniteDuration = 120.seconds

  /** The longest `validate()` waits for its one request: ten seconds. */
  val ValidationTimeout: FiniteDuration = 10.seconds

  /** The timeout of a streamed call when the section sets no `timeouts.stream`: ten minutes. */
  val DefaultStreamTimeout: FiniteDuration = 10.minutes

  import org.llm4s.syntax.toResult

  private val logger = LoggerFactory.getLogger(getClass)

  /** Whether an error body says the model cannot do tool calling (matched whatever the case). */
  private[provider] def reportsNoToolSupport(body: String): Boolean =
    body.toLowerCase(java.util.Locale.ROOT).contains("does not support tools")

  /**
   * The `error` text of an Ollama error body, or the body itself when it is not that JSON, redacted and then cut to 200
   * characters. Redaction runs on the whole decoded text before the cut, so a credential the server echoes cannot
   * survive as a fragment the patterns no longer recognise (#1674).
   */
  private[provider] def serverMessage(body: String): String =
    Redaction.safeBody(
      Try(ujson.read(body)).toOption
        .flatMap(_.objOpt)
        .flatMap(_.get("error"))
        .flatMap(_.strOpt)
        .getOrElse(body)
        .trim,
      200
    )

  /**
   * The call ids of one reply. Synthesizes them for entries that carry none - older Ollama servers send
   * none: `call_<12 hex>_<index>`, one prefix per reply - and refuses an id the reply already used.
   */
  final private[provider] class CallIds {
    private val prefix = "call_" + java.util.UUID.randomUUID().toString.replace("-", "").take(12)
    private var next   = 0
    private val used   = scala.collection.mutable.Set.empty[String]
    def nextId(): String = {
      val id = s"${prefix}_$next"
      next += 1
      used += id
      id
    }

    /** Records a server-sent id; false when the reply already used it. */
    def claim(id: String): Boolean = used.add(id)
  }

  private def malformed(detail: String): org.llm4s.error.LLMError =
    org.llm4s.error.ProcessingError("ollama-tool-calls", s"malformed tool call: $detail")

  /**
   * Arguments of a call as a JSON object; Ollama's request side takes an object, never a string.
   * An object is sent as it is, and a string that parses to an object is parsed. Anything else (a string
   * that is not JSON or not an object, an array, a number) cannot be sent: it goes as `{}`, with a
   * WARN naming the tool, never the arguments, which can hold secrets. A string is the model's own text
   * (a streamed call's arguments stay a `Str`), so one nested more than 512 levels deep is not parsed
   * either - the object it would build overflows the stack when the request is rendered - and goes as
   * `{}` like any other string that is not a JSON object (#1562).
   */
  private[provider] def requestArguments(toolName: String, arguments: ujson.Value): ujson.Value = {
    val parsed = arguments match {
      case o: ujson.Obj => Some(o)
      case ujson.Str(s) => BoundedJson.read(s).toOption.collect { case o: ujson.Obj => o }
      case _            => None
    }
    parsed.getOrElse {
      logger.warn(s"Sending an empty object as the arguments of tool call '$toolName': they are not a JSON object")
      ujson.Obj()
    }
  }

  /** An OpenAI-format tool definition as Ollama takes it: no `strict`, which Ollama does not know. */
  private[provider] def encodeTool(tool: ujson.Value): ujson.Value = {
    val function = ujson.Obj.from(tool("function").obj.filterNot(_._1 == "strict"))
    ujson.Obj("type" -> "function", "function" -> function)
  }

  private def normalizeArguments(raw: Option[ujson.Value]): Result[ujson.Value] = raw match {
    case None | Some(ujson.Null)              => Right(ujson.Obj())
    case Some(o: ujson.Obj)                   => Right(o)
    case Some(ujson.Str(s)) if s.trim.isEmpty => Right(ujson.Obj())
    case Some(ujson.Str(s))                   =>
      // The string is the model's text inside the envelope's string literal, so this second parse is the
      // boundary: a document nested more than 512 levels deep is refused before it is parsed, since the
      // object it would build overflows the stack of whatever renders it (#1562).
      BoundedJson.read(s) match {
        case Right(o: ujson.Obj) => Right(o)
        case Left(BoundedJson.TooDeep()) =>
          Left(malformed(s"arguments are nested more than ${BoundedJson.MaxDepth} levels deep"))
        case _ => Left(malformed("arguments are not a JSON object"))
      }
    case Some(_) => Left(malformed("arguments are not a JSON object"))
  }

  /**
   * One `tool_calls` entry, which is always a whole call: Ollama's tool parsers buffer the model's output
   * until a call is complete and only then emit it, with `arguments` an object (`api.ToolCallFunctionArguments`
   * marshals to one) and a fresh id per call. So a streamed entry is checked exactly as a non-streamed one -
   * a name, and arguments that are a JSON object - and an id the reply already used is malformed: it is never
   * a continuation to merge, and passing it on would make the streaming accumulator, which keys calls by id,
   * concatenate two calls' arguments.
   */
  private def parseToolCall(entry: ujson.Value, ids: CallIds): Result[ToolCall] =
    for {
      call     <- entry.objOpt.toRight(malformed("entry is not an object"))
      function <- call.get("function").flatMap(_.objOpt).toRight(malformed("entry has no `function` object"))
      name <- function
        .get("name")
        .flatMap(_.strOpt)
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(malformed("function has no name"))
      args <- normalizeArguments(function.get("arguments"))
      id <- call.get("id").flatMap(_.strOpt).filter(_.nonEmpty) match {
        case Some(serverId) =>
          Either.cond(ids.claim(serverId), serverId, malformed(s"tool call id '$serverId' is used more than once"))
        case None => Right(ids.nextId())
      }
    } yield ToolCall(ToolCallId(id), name, args)

  /**
   * The reply's [[FinishReason]] from its last line's `done_reason` (RFC 0009): `stop` is `ToolCalls` when the reply
   * called a tool and `Stop` otherwise, `length` is `Length`, any other value `Other`, and none `Unknown`.
   */
  private[provider] def finishReason(json: ujson.Value, calledTools: Boolean): FinishReason =
    json.obj.get("done_reason").flatMap(_.strOpt).filter(_.nonEmpty) match {
      case Some("stop")   => if (calledTools) FinishReason.ToolCalls else FinishReason.Stop
      case Some("length") => FinishReason.Length
      case Some("")       => FinishReason.Unknown
      case Some(other)    => FinishReason.Other(other)
      case None           => FinishReason.Unknown
    }

  /** The `thinking` of a reply's `message`, or of one streamed line; none when absent, null or empty. */
  private[provider] def thinking(message: Option[ujson.Value]): Option[String] =
    message.flatMap(_.objOpt).flatMap(_.get("thinking")).flatMap(_.strOpt).filter(_.nonEmpty)

  /** The `tool_calls` of a reply's `message`, or of one streamed line; none when absent, null or empty. */
  private[provider] def parseToolCalls(message: Option[ujson.Value], ids: CallIds): Result[Seq[ToolCall]] =
    message.flatMap(_.objOpt).flatMap(_.get("tool_calls")).filterNot(_.isNull) match {
      case None => Right(Seq.empty)
      case Some(ujson.Arr(entries)) =>
        entries.foldLeft[Result[Seq[ToolCall]]](Right(Seq.empty)) { (acc, entry) =>
          acc.flatMap(done => parseToolCall(entry, ids).map(done :+ _))
        }
      case Some(_) => Left(malformed("`tool_calls` is not an array"))
    }

  /** Value of the `/api/chat` `format` field for a [[org.llm4s.llmconnect.model.ResponseFormat ResponseFormat]]; `name` and `strict` are not sent. */
  private[provider] def encodeFormat(format: ResponseFormat): ujson.Value = format match {
    case ResponseFormat.Json                     => ujson.Str("json")
    case ResponseFormat.JsonSchema(schema, _, _) => schema
  }

  /**
   * A [[OllamaClient]] for `config`, or why one cannot be built: a config its provider refuses, or anything the
   * client's construction fails on, is a `Left`, never an exception.
   * `options.httpClient`, when set, carries every request and stays the caller's to close.
   *
   * @param config  the provider's configuration.
   * @param options the metrics collector, exchange logging and HTTP client of the client; none of them by default.
   */
  def apply(config: OllamaConfig, options: LlmClientOptions = LlmClientOptions())(using
    ModelRegistryService
  ): Result[OllamaClient] =
    Try(
      new OllamaClient(
        config,
        options.metrics,
        options.exchangeLogging,
        Llm4sHttpClient.suppliedOrFromConfig(options.httpClient)
      )
    ).toResult
}
