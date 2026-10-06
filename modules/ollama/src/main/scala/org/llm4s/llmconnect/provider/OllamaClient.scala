package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.error.ValidationError
import org.llm4s.http.{ HttpFailures, Llm4sHttpClient }
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }
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
 * == Structured output ==
 *
 * [[CompletionOptions.responseFormat]] is honoured through the top-level `format`
 * field of `/api/chat`, on both the streaming and non-streaming paths:
 *
 *  - `None` sends no `format` key.
 *  - [[ResponseFormat.Json]] sends `"format": "json"` (JSON mode).
 *  - [[ResponseFormat.JsonSchema]] sends `"format": <schema object>` (structured outputs,
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
 * after 600 seconds.
 *
 * @param config  Ollama configuration containing the model name and base URL.
 * @param metrics Receives per-call latency and token-usage events.
 *                Defaults to `MetricsCollector.noop`.
 */
@Stable
class OllamaClient(
  config: OllamaConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  private[provider] val httpClient: Llm4sHttpClient = Llm4sHttpClient.create()
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  protected def clientDescription: String = s"Ollama client for model ${config.model}"
  protected def providerName: String      = "ollama"
  protected def modelName: String         = config.model

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    connect(conversation, options)
  }

  private def connect(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
    val requestBody = createRequestBody(conversation, options, stream = false)
    val requestText = requestBody.render()
    val url         = s"${config.baseUrl}/api/chat"
    val headers     = Map("Content-Type" -> "application/json")
    val startedAt   = Instant.now()
    httpClient.post(url, headers, requestText, timeout = 120.seconds) match {
      case Left(error) =>
        recordingExchange(startedAt, requestText, "")(Left(error))
      case Right(response) =>
        val result =
          if (response.statusCode >= 200 && response.statusCode < 300) {
            Try(ujson.read(response.body)).toResult
              .flatMap(json => Try(parseCompletion(json)).toResult.flatMap(identity))
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
    else HttpErrorMapper.mapHttpError(status, body, providerName, headers)

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

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val requestBody = createRequestBody(conversation, options, stream = true)
    val requestText = requestBody.render()
    val url         = s"${config.baseUrl}/api/chat"
    val headers     = Map("Content-Type" -> "application/json")
    val startedAt   = Instant.now()
    val rawResponse = new StringBuilder

    httpClient.postStream(url, headers, requestText, timeout = 10.minutes) match {
      case Left(error) =>
        recordingExchange(startedAt, requestText, "")(Left(error))
      case Right(response) if response.statusCode != 200 =>
        val err = Using(response.body)(in => new String(in.readAllBytes(), StandardCharsets.UTF_8)).getOrElse("")
        recordingExchange(startedAt, requestText, err)(
          mapError(response.statusCode, err, response.headers, options)
        )
      case Right(response) =>
        val accumulator = StreamingAccumulator.create()
        val ids         = new OllamaClient.CallIds
        var failure     = Option.empty[org.llm4s.error.LLMError]
        var sawCalls    = false
        val processResult = Using(new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))) {
          reader =>
            Iterator.continually(reader.readLine()).takeWhile(_ != null).takeWhile(_ => failure.isEmpty).foreach {
              line =>
                rawResponse.append(line).append('\n')
                val trimmed = line.trim
                if (trimmed.nonEmpty) {
                  val json = ujson.read(trimmed)
                  // Ollama streams incremental content in json lines
                  val done = json.obj.get("done").exists(_.bool)
                  val contentOpt = json.obj
                    .get("message")
                    .flatMap(_.obj.get("content"))
                    .flatMap(_.strOpt)
                    .filter(_.nonEmpty)
                  val id = json.obj.get("id").flatMap(_.strOpt).getOrElse("")

                  OllamaClient.parseToolCalls(json.obj.get("message"), ids, fragments = true) match {
                    case Left(error) => failure = Some(error)
                    case Right(calls) =>
                      sawCalls = sawCalls || calls.nonEmpty
                      val finish = if (done) Some(if (sawCalls) "tool_calls" else "stop") else None
                      val head =
                        StreamedChunk(id = id, content = contentOpt, toolCall = None, finishReason = finish)
                      // a line with calls and nothing else needs no empty content chunk
                      val chunks =
                        if (calls.isEmpty) Seq(head)
                        else {
                          val callChunks = calls
                            .map(c => StreamedChunk(id = id, content = None, toolCall = Some(c), finishReason = None))
                          val withContent = if (contentOpt.isDefined) Seq(head.withFinishReason(None)) else Seq.empty
                          val closing = if (finish.isDefined) Seq(head.withContent(None: Option[String])) else Seq.empty
                          withContent ++ callChunks ++ closing
                        }
                      chunks.foreach { chunk =>
                        accumulator.addChunk(chunk)
                        onChunk(chunk)
                      }

                      // token counts (if present) only appear at the end
                      if (done) {
                        val prompt = json.obj.get("prompt_eval_count").flatMap(_.numOpt).map(_.toInt).getOrElse(0)
                        val comp   = json.obj.get("eval_count").flatMap(_.numOpt).map(_.toInt).getOrElse(0)
                        if (prompt > 0 || comp > 0) accumulator.updateTokens(prompt, comp)
                      }
                  }
                }
            }
        }.toEither.left.map(HttpFailures.streamReadError(_, url, 10.minutes))

        val result = processResult
          .flatMap(_ => failure.fold(accumulator.toCompletion)(Left(_)))
          .map { c =>
            val cost = c.usage.flatMap(u => CostEstimator.estimate(config.model, u))
            // the accumulator reports streamed calls on the message only
            c.withModel(config.model).withToolCalls(c.message.toolCalls.toList).withEstimatedCost(cost)
          }

        recordingExchange(startedAt, requestText, rawResponse.result())(result)
    }
  }

  private[provider] def createRequestBody(
    conversation: Conversation,
    options: CompletionOptions,
    stream: Boolean
  ): ujson.Obj = {
    val toolNames = conversation.messages
      .collect { case am: AssistantMessage => am.toolCalls }
      .flatten
      .map(tc => tc.id -> tc.name)
      .toMap
    val msgs = ujson.Arr.from(conversation.messages.map {
      case SystemMessage(content) => ujson.Obj("role" -> "system", "content" -> content)
      case UserMessage(content)   => ujson.Obj("role" -> "user", "content" -> content)
      case am: AssistantMessage =>
        val message = ujson.Obj("role" -> "assistant", "content" -> am.content)
        if (am.toolCalls.nonEmpty)
          message("tool_calls") = ujson.Arr.from(am.toolCalls.zipWithIndex.map { case (tc, index) =>
            ujson.Obj(
              "id" -> tc.id,
              "function" -> ujson.Obj(
                "index"     -> index,
                "name"      -> tc.name,
                "arguments" -> OllamaClient.requestArguments(tc.name, tc.arguments)
              )
            )
          })
        message
      case ToolMessage(content, toolCallId) =>
        val message = ujson.Obj("role" -> "tool", "content" -> content, "tool_call_id" -> toolCallId)
        toolNames.get(toolCallId).foreach(name => message("tool_name") = name)
        message
    })

    val opts = ujson.Obj(
      "temperature" -> options.temperature,
      "top_p"       -> options.topP
    )
    options.maxTokens.foreach(t => opts("num_predict") = t)

    val body = ujson.Obj(
      "model"    -> config.model,
      "messages" -> msgs,
      "stream"   -> stream,
      "options"  -> opts
    )
    if (options.tools.nonEmpty)
      body("tools") = ujson.Arr.from(options.tools.map(t => OllamaClient.encodeTool(t.toOpenAITool(strict = false))))
    options.responseFormat.foreach(rf => body("format") = OllamaClient.encodeFormat(rf))
    body
  }

  private def parseCompletion(json: ujson.Value): Result[Completion] = {
    val id      = json.obj.get("id").flatMap(_.strOpt).getOrElse(java.util.UUID.randomUUID().toString)
    val created = System.currentTimeMillis() / 1000
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

    OllamaClient.parseToolCalls(json.obj.get("message"), new OllamaClient.CallIds, fragments = false).map { calls =>
      Completion(
        id = id,
        created = created,
        content = content,
        toolCalls = calls.toList,
        usage = usage,
        model = config.model,
        message =
          if (calls.isEmpty) AssistantMessage(content)
          else AssistantMessage(Some(content).filter(_.nonEmpty), calls),
        estimatedCost = cost
      )
    }
  }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  override protected def releaseResources(): Unit =
    (httpClient: Any) match {
      case c: AutoCloseable => c.close()
      case _                => ()
    }
}

object OllamaClient {
  import org.llm4s.types.TryOps

  private val logger = LoggerFactory.getLogger(getClass)

  /** Whether an error body says the model cannot do tool calling (matched whatever the case). */
  private[provider] def reportsNoToolSupport(body: String): Boolean =
    body.toLowerCase(java.util.Locale.ROOT).contains("does not support tools")

  /** The `error` text of an Ollama error body, or the body itself when it is not that JSON; at most 200 characters. */
  private[provider] def serverMessage(body: String): String =
    Try(ujson.read(body)).toOption
      .flatMap(_.objOpt)
      .flatMap(_.get("error"))
      .flatMap(_.strOpt)
      .getOrElse(body)
      .trim
      .take(200)

  /** Synthesizes call ids - Ollama's native API sends none: `call_<12 hex>_<index>`, one prefix per reply. */
  final private[provider] class CallIds {
    private val prefix = "call_" + java.util.UUID.randomUUID().toString.replace("-", "").take(12)
    private var next   = 0
    def nextId(): String = {
      val id = s"${prefix}_$next"
      next += 1
      id
    }
  }

  private def malformed(detail: String): org.llm4s.error.LLMError =
    org.llm4s.error.ProcessingError("ollama-tool-calls", s"malformed tool call: $detail")

  /**
   * Arguments of a call as a JSON object; Ollama's request side takes an object, never a string.
   * An object is sent as it is, and a string that parses to an object is parsed. Anything else (a string
   * that is not JSON or not an object, an array, a number) cannot be sent: it goes as `{}`, with a
   * WARN naming the tool, never the arguments, which can hold secrets.
   */
  private[provider] def requestArguments(toolName: String, arguments: ujson.Value): ujson.Value = {
    val parsed = arguments match {
      case o: ujson.Obj => Some(o)
      case ujson.Str(s) => Try(ujson.read(s)).toOption.collect { case o: ujson.Obj => o }
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
    case Some(ujson.Str(s)) =>
      Try(ujson.read(s)).toOption match {
        case Some(o: ujson.Obj) => Right(o)
        case _                  => Left(malformed("arguments are not a JSON object"))
      }
    case Some(_) => Left(malformed("arguments are not a JSON object"))
  }

  private def parseToolCall(entry: ujson.Value, ids: CallIds, fragments: Boolean): Result[ToolCall] =
    for {
      call     <- entry.objOpt.toRight(malformed("entry is not an object"))
      function <- call.get("function").flatMap(_.objOpt).toRight(malformed("entry has no `function` object"))
      serverId = call.get("id").flatMap(_.strOpt).filter(_.nonEmpty)
      name     = function.get("name").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
      toolCall <- (serverId, name) match {
        // a continuation of a streamed call: named by its id, carrying a raw piece of the arguments
        case (Some(id), _) if fragments =>
          Right(ToolCall(id, name.getOrElse(""), function.get("arguments").filterNot(_.isNull).getOrElse(ujson.Obj())))
        case (_, None) => Left(malformed("function has no name"))
        case (_, Some(n)) =>
          normalizeArguments(function.get("arguments")).map(args => ToolCall(serverId.getOrElse(ids.nextId()), n, args))
      }
    } yield toolCall

  /** The `tool_calls` of a reply's `message`; none when absent, null or empty. */
  private[provider] def parseToolCalls(
    message: Option[ujson.Value],
    ids: CallIds,
    fragments: Boolean
  ): Result[Seq[ToolCall]] =
    message.flatMap(_.objOpt).flatMap(_.get("tool_calls")).filterNot(_.isNull) match {
      case None => Right(Seq.empty)
      case Some(ujson.Arr(entries)) =>
        entries.foldLeft[Result[Seq[ToolCall]]](Right(Seq.empty)) { (acc, entry) =>
          acc.flatMap(done => parseToolCall(entry, ids, fragments).map(done :+ _))
        }
      case Some(_) => Left(malformed("`tool_calls` is not an array"))
    }

  /** Value of the `/api/chat` `format` field for a [[ResponseFormat]]; `name` and `strict` are not sent. */
  private[provider] def encodeFormat(format: ResponseFormat): ujson.Value = format match {
    case ResponseFormat.Json                     => ujson.Str("json")
    case ResponseFormat.JsonSchema(schema, _, _) => schema
  }

  /**
   * Constructs an [[OllamaClient]], wrapping any construction-time exception
   * in a `Left`.
   *
   * @param config  Ollama configuration with model name and server base URL.
   * @param metrics Receives per-call latency and token-usage events.
   *                Defaults to `MetricsCollector.noop`.
   * @return `Right(client)` on success; `Left(LLMError)` if construction fails
   *         (e.g. invalid base URL).
   */
  def apply(
    config: OllamaConfig,
    metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
    exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
  )(using ModelRegistryService): Result[OllamaClient] =
    Try(new OllamaClient(config, metrics, exchangeLogging)).toResult
}
