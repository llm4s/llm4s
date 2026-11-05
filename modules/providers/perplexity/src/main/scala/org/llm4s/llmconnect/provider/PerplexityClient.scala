package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, ProcessingError, ServiceError, ValidationError }
import org.llm4s.http.{ HttpFailures, Llm4sHttpClient }
import org.llm4s.llmconnect.{ BaseLifecycleLLMClient, ProviderExchangeLogging }
import org.llm4s.llmconnect.auth.CachingAccessTokenProvider
import org.llm4s.llmconnect.config.PerplexityConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.util.{ BoundedJson, Redaction }

import java.io.{ BufferedReader, InputStreamReader }
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.{ Try, Using }

/**
 * [[LLMClient]] for Perplexity's Agent API: `POST <baseUrl>/v1/agent`, Perplexity's canonical path for its
 * OpenAI Responses-format endpoint (`/v1/responses` is its alias).
 *
 * The request and response shapes are those of Perplexity's API reference ("Create Agent Response",
 * https://docs.perplexity.ai/api-reference/agent-post) and its Agent API guides, read 2026-11-05. Nothing here has
 * been run against the live service; the `@Cloud` suite `PerplexitySmokeSpec` in `modules/it` does that with a key.
 *
 * == Request ==
 *
 * The model goes in `model`, in the Agent API's `provider/model` form. System messages are joined, in order, into
 * `instructions`; every other message becomes an `input` item: a user or assistant turn a `message` item, a tool call
 * a `function_call` item (its `arguments` a JSON string) and a
 * [[org.llm4s.llmconnect.model.ToolMessage ToolMessage]] a `function_call_output` item carrying the call's
 * `call_id`. Only text parts are sent; any other part is refused before anything is sent.
 *
 * `temperature` is always sent, `top_p` when not 1.0 and `max_output_tokens` when set. The Agent API rejects an
 * `anthropic/` model without `max_output_tokens`, so such a request that sets no limit is sent the config's
 * `reserveCompletion`. `CompletionOptions.reasoning` is sent as `reasoning.effort` (`ReasoningEffort.None` as
 * `minimal`, the least the API takes; `budgetTokens` has no counterpart). The penalties have no counterpart in the
 * Agent API and are not sent. A [[ResponseFormat.JsonSchema]] is sent as `response_format`
 * (`{"type":"json_schema","json_schema":{"name","schema","strict"}}`); the Agent API takes no plain JSON mode, so
 * [[ResponseFormat.Json]] is refused before anything is sent.
 *
 * == Tools and web search ==
 *
 * With `webSearch` on (the default) every request offers Perplexity's `web_search` tool, which Perplexity runs
 * itself and bills per search; the model decides whether to search. `CompletionOptions.tools` are sent as `function`
 * tools. A `function_call` item in the reply becomes a [[ToolCall]] whose id is the item's `call_id`. A model may
 * attach a `thought_signature` to a call, which Perplexity says to send back with it: it is kept as a sealed
 * [[ThinkingBlock.Opaque]] block of provider `perplexity` and replayed on the call it came with, only to the same
 * provider and model (see `ThinkingReplay.bindOrigin`).
 *
 * == Reply ==
 *
 * The answer is the text of the `message` items' `output_text` parts. [[Completion.citations]] holds the `url_citation`
 * annotations on that text, then each result of the `search_results` items (URL, title, and the snippet as
 * `citedText`) whose URL is not already there: the answer's `[web:N]` markers point at those results. A response
 * whose `status` is `failed`, or anything but `completed` or `incomplete`, is an error; `incomplete` (a limit
 * reached) is returned as an answer cut short, with finish reason `length` on a stream. The usage's
 * `input_tokens_details` and `output_tokens_details.reasoning_tokens` fill the cached and thinking token counts,
 * and the cost Perplexity reports (`usage.cost.total_cost`, in USD) is the completion's `estimatedCost`.
 * The Agent API returns no reasoning text, so a completion has no thinking text.
 *
 * == Streaming ==
 *
 * `stream: true` returns server-sent events, each a JSON object discriminated by `type`. Each
 * `response.output_text.delta` is passed on as a content chunk; the stream's `response.completed` event carries the
 * whole response, which gives the tool calls (sent as whole calls after the text), citations and usage. A
 * `response.failed` event, or a stream that ends without `response.completed`, is an error.
 *
 * @param config          model, key, endpoint and web-search setting.
 * @param metrics         receives per-call latency and token-usage events.
 * @param exchangeLogging optional provider exchange logging.
 * @param httpClient      the HTTP client; by default one built from the config's network settings.
 */
@Experimental
class PerplexityClient(
  config: PerplexityConfig,
  protected val metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  httpClient: Llm4sHttpClient = Llm4sHttpClient.FromConfig
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  import PerplexityClient.*

  private[provider] val http: Llm4sHttpClient = Llm4sHttpClient.resolve(httpClient, config.network)

  protected def clientDescription: String = s"Perplexity Agent API client for model ${config.model}"
  protected def providerName: String      = ProviderName
  protected def modelName: String         = config.model

  private val replayOrigin = ReplayOrigin(ProviderName, config.model)

  /** How long a non-streaming call may take: the section's `timeouts.request`, else [[PerplexityClient.DefaultRequestTimeout]]. */
  protected[provider] def requestTimeout: FiniteDuration = config.timeouts.requestOr(DefaultRequestTimeout)

  /** How long a streamed call may take: the section's `timeouts.stream`, else [[PerplexityClient.DefaultStreamTimeout]]. */
  protected[provider] def streamTimeout: FiniteDuration = config.timeouts.streamOr(DefaultStreamTimeout)

  /** The key to send: asked of the config's `ApiKeyProvider` on every request when it has one, else `apiKey`. */
  private def currentApiKey(): Result[String] =
    config.apiKeyProvider.fold[Result[String]](Right(config.apiKey.reveal))(keys =>
      CachingAccessTokenProvider.guarded(keys.current()).map(_.asKey)
    )

  private def requestHeaders(key: String, accept: String): Map[String, String] =
    config.headers ++ Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $key", "Accept" -> accept)

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    completeWithMetrics {
      createRequestBody(conversation, options, stream = false).flatMap(body => currentApiKey().map(_ -> body)).flatMap {
        (key, body) =>
          val requestText = body.render()
          val startedAt   = Instant.now()
          http
            .post(config.agentUrl, requestHeaders(key, "application/json"), requestText, requestTimeout)
            .flatMap { response =>
              val result =
                if (response.statusCode >= 200 && response.statusCode < 300)
                  BoundedJson.read(response.body).flatMap(parseReply).map(completion)
                else
                  HttpErrorMapper.mapHttpError(
                    response.statusCode,
                    Redaction.scrubRemote(response.body),
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
    createRequestBody(conversation, options, stream = true).flatMap(body => currentApiKey().map(_ -> body)).flatMap {
      (key, body) =>
        val requestText = body.render()
        val url         = config.agentUrl
        val startedAt   = Instant.now()
        val raw         = new StringBuilder
        val result = http
          .postStream(url, requestHeaders(key, "text/event-stream"), requestText, streamTimeout)
          .flatMap { response =>
            if (response.statusCode >= 200 && response.statusCode < 300) readStream(response.body, url, raw, onChunk)
            else {
              val err = Using(response.body)(in => new String(in.readAllBytes(), StandardCharsets.UTF_8)).getOrElse("")
              raw.append(err)
              HttpErrorMapper
                .mapHttpError(response.statusCode, Redaction.scrubRemote(err), providerName, response.headers)
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
    val streamed                       = new StringBuilder
    var responseId                     = ""
    var completed: Option[ujson.Value] = None
    var failure: Option[LLMError]      = None
    def done: Boolean                  = completed.isDefined || failure.isDefined
    def idOf(response: ujson.Value): Unit =
      response.objOpt.flatMap(_.get("id")).flatMap(_.strOpt).filter(_.nonEmpty).foreach(id => responseId = id)

    val read = Using(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) { reader =>
      // nothing after response.completed or response.failed belongs to this reply
      Iterator.continually(reader.readLine()).takeWhile(line => line != null && !done).foreach { line =>
        raw.append(line).append('\n')
        val trimmed = line.trim
        if (trimmed.startsWith("data:")) {
          val data = trimmed.drop("data:".length).trim
          if (data.nonEmpty && data != "[DONE]")
            BoundedJson.read(data) match {
              case Left(_) =>
                failure = Some(ServiceError(502, providerName, "malformed stream event: the data is not JSON"))
              case Right(event) =>
                val fields = event.objOpt
                fields.flatMap(_.get("type")).flatMap(_.strOpt).getOrElse("") match {
                  case "response.created" | "response.in_progress" =>
                    fields.flatMap(_.get("response")).foreach(idOf)
                  case "response.output_text.delta" =>
                    fields.flatMap(_.get("delta")).flatMap(_.strOpt).filter(_.nonEmpty).foreach { delta =>
                      streamed.append(delta)
                      onChunk(StreamedChunk(id = responseId, content = Some(delta)))
                    }
                  case "response.completed" =>
                    completed = Some(fields.flatMap(_.get("response")).getOrElse(ujson.Null))
                  case "response.failed" =>
                    val error = fields
                      .flatMap(_.get("error"))
                      .orElse(fields.flatMap(_.get("response")).flatMap(_.objOpt).flatMap(_.get("error")))
                    failure = Some(failed(error))
                  case _ => ()
                }
            }
        }
      }
    }.toEither.left.map(HttpFailures.streamReadError(_, url, streamTimeout))

    for {
      _ <- read
      _ <- failure.toLeft(())
      response <- completed.toRight(
        ServiceError(502, providerName, "stream ended without response.completed; the response is incomplete")
      )
      reply <- parseReply(response)
    } yield {
      val whole = if (reply.text.nonEmpty) reply else reply.copy(text = streamed.result())
      val id    = if (whole.id.nonEmpty) whole.id else responseId
      whole.toolCalls.foreach(call => onChunk(StreamedChunk(id = id, content = None, toolCall = Some(call))))
      val reason = if (whole.toolCalls.nonEmpty) "tool_calls" else if (whole.incomplete) "length" else "stop"
      onChunk(StreamedChunk(id = id, content = None, finishReason = Some(reason)))
      completion(whole.copy(id = id))
    }
  }

  /** A [[Completion]] of a parsed reply, its sealed signatures bound to this client's provider and model. */
  private def completion(reply: Reply): Completion = {
    val message = ThinkingReplay.bindOrigin(
      replayOrigin,
      AssistantMessage(Some(reply.text).filter(_.nonEmpty), reply.toolCalls, reply.signatures)
    )
    Completion(
      id = if (reply.id.nonEmpty) reply.id else java.util.UUID.randomUUID().toString,
      created = reply.created.getOrElse(Instant.now()),
      content = reply.text,
      model = reply.model.getOrElse(config.model),
      message = message,
      toolCalls = reply.toolCalls,
      usage = reply.usage,
      estimatedCost = reply.cost.orElse(reply.usage.flatMap(CostEstimator.estimate(config.model, _))),
      citations = reply.citations
    )
  }

  private[provider] def createRequestBody(
    conversation: Conversation,
    options: CompletionOptions,
    stream: Boolean
  ): Result[ujson.Obj] =
    for {
      _ <- PartEncoding.check(providerName, conversation.messages, PartEncoding.textOnly, PartEncoding.textOnly)
      format <- (options.responseFormat match {
        case None                               => Right(None)
        case Some(ResponseFormat.Json)          => Left(JsonModeUnsupported)
        case Some(s: ResponseFormat.JsonSchema) => Right(Some(encodeJsonSchema(s)))
      }): Result[Option[ujson.Value]]
    } yield {
      val messages = ThinkingReplay.replayableOrigin(replayOrigin, conversation.messages)
      val body     = ujson.Obj("model" -> config.model, "input" -> encodeInput(messages))
      val system   = messages.collect { case SystemMessage(content) => content }.filter(_.nonEmpty)
      if (system.nonEmpty) body("instructions") = system.mkString("\n\n")
      body("temperature") = options.temperature
      if (options.topP != 1.0) body("top_p") = options.topP
      options.maxTokens
        .orElse(Option.when(isAnthropic(config.model))(anthropicDefaultMaxTokens(config.reserveCompletion)))
        .foreach(max => body("max_output_tokens") = max)
      options.reasoning.foreach(effort => body("reasoning") = ujson.Obj("effort" -> reasoningEffortValue(effort)))
      format.foreach(f => body("response_format") = f)
      val tools =
        (if (config.webSearch) Seq(ujson.Obj("type" -> "web_search")) else Seq.empty) ++
          options.tools.map(tool => functionTool(tool.toOpenAITool(strict = false)))
      if (tools.nonEmpty) body("tools") = ujson.Arr.from(tools)
      if (stream) body("stream") = true
      body
    }

  /** The `input` items of `messages` (system messages go to `instructions`, not here). */
  private def encodeInput(messages: Seq[Message]): ujson.Arr = {
    val callNames = mutable.Map.empty[String, String]
    ujson.Arr.from(messages.flatMap {
      case _: SystemMessage => Seq.empty
      case um: UserMessage  => Seq(messageItem("user", textContent(um.parts)))
      case am: AssistantMessage =>
        val signatures =
          am.thinking.collect { case ThinkingBlock.Opaque(ProviderName, data) => data }.flatMap(decode).toMap
        val text = Option.when(am.content.nonEmpty)(messageItem("assistant", ujson.Str(am.content)))
        val calls = am.toolCalls.map { call =>
          callNames(call.id) = call.name
          val item = ujson.Obj(
            "type"      -> "function_call",
            "call_id"   -> call.id,
            "name"      -> call.name,
            "arguments" -> requestArguments(call.arguments)
          )
          signatures.get(call.id).foreach(sig => item("thought_signature") = sig)
          item
        }
        text.toSeq ++ calls
      case tm: ToolMessage =>
        val item = ujson.Obj("type" -> "function_call_output", "call_id" -> tm.toolCallId, "output" -> tm.content)
        callNames.get(tm.toolCallId).foreach(name => item("name") = name)
        Seq(item)
    })
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

  override protected def releaseResources(): Unit = http.close()
}

@Experimental
object PerplexityClient {

  /** The provider id: the `provider` of this client's errors, metrics and sealed thought-signature blocks. */
  val ProviderName: String = "perplexity"

  /** The timeout of a non-streaming call when the section sets no `timeouts.request`: five minutes, for runs that search. */
  val DefaultRequestTimeout: FiniteDuration = 5.minutes

  /** The timeout of a streamed call when the section sets no `timeouts.stream`: ten minutes. */
  val DefaultStreamTimeout: FiniteDuration = 10.minutes

  /**
   * Builds a [[PerplexityClient]], wrapping any construction-time exception in a `Left`.
   *
   * @param config          model, key, endpoint and web-search setting.
   * @param metrics         receives per-call latency and token-usage events.
   * @param exchangeLogging optional provider exchange logging.
   */
  def apply(
    config: PerplexityConfig,
    metrics: MetricsCollector = MetricsCollector.noop,
    exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
  )(using ModelRegistryService): Result[PerplexityClient] =
    Try(new PerplexityClient(config, metrics, exchangeLogging)).toResult

  private val JsonModeUnsupported: LLMError = ValidationError(
    "responseFormat",
    "Perplexity's Agent API takes only a JSON schema response format: use ResponseFormat.JsonSchema"
  )

  /** A reply's parts, read from a response object (the non-streaming body, or a stream's `response.completed`). */
  final private[provider] case class Reply(
    id: String,
    created: Option[Instant],
    model: Option[String],
    text: String,
    toolCalls: List[ToolCall],
    signatures: Seq[ThinkingBlock],
    citations: List[Citation],
    usage: Option[TokenUsage],
    cost: Option[Double],
    incomplete: Boolean
  )

  private def isAnthropic(model: String): Boolean =
    model.trim.toLowerCase(java.util.Locale.ROOT).startsWith("anthropic/")

  private def anthropicDefaultMaxTokens(reserve: Int): Int =
    if (reserve > 0) reserve else PerplexityConfig.DEFAULT_RESERVE_COMPLETION

  /**
   * A [[ReasoningEffort]] as the Agent API's `reasoning.effort`, which takes `minimal`, `low`, `medium`, `high`,
   * `xhigh` and `max`. `None` has no "off": it is sent as `minimal`, the least.
   */
  private[provider] def reasoningEffortValue(effort: ReasoningEffort): String = effort match {
    case ReasoningEffort.None   => "minimal"
    case ReasoningEffort.Low    => "low"
    case ReasoningEffort.Medium => "medium"
    case ReasoningEffort.High   => "high"
  }

  private def encodeJsonSchema(format: ResponseFormat.JsonSchema): ujson.Value =
    ujson.Obj(
      "type"        -> "json_schema",
      "json_schema" -> ujson.Obj("name" -> format.name, "schema" -> format.schema, "strict" -> format.strict)
    )

  /** An OpenAI chat-format tool, `{"type":"function","function":{...}}`, as the Responses format's flat function tool. */
  private[provider] def functionTool(tool: ujson.Value): ujson.Value =
    ujson.Obj.from(Seq("type" -> ujson.Str("function")) ++ tool("function").obj)

  private def messageItem(role: String, content: ujson.Value): ujson.Obj =
    ujson.Obj("type" -> "message", "role" -> role, "content" -> content)

  /** A user turn's content: its text, or `input_text` parts when it has several. Checked text-only before. */
  private def textContent(parts: Vector[ContentPart]): ujson.Value = parts match {
    case Vector(ContentPart.Text(text)) => ujson.Str(text)
    case _ =>
      ujson.Arr.from(parts.collect { case ContentPart.Text(text) => ujson.Obj("type" -> "input_text", "text" -> text) })
  }

  /** A call's `arguments` as the Agent API takes them, a JSON string: an object rendered, anything else `{}`. */
  private[provider] def requestArguments(arguments: ujson.Value): String = arguments match {
    case obj: ujson.Obj => ujson.write(obj)
    case ujson.Str(text) =>
      Try(ujson.read(text)).toOption match {
        case Some(parsed: ujson.Obj) => ujson.write(parsed)
        case _                       => "{}"
      }
    case _ => "{}"
  }

  /** The data of a thought-signature block: the call it belongs to, and the signature. */
  private def encode(callId: String, signature: String): String =
    ujson.Obj("call" -> callId, "sig" -> signature).render()

  private def decode(data: String): Option[(String, String)] =
    Try(ujson.read(data)).toOption.flatMap(_.objOpt).flatMap { fields =>
      for {
        call <- fields.get("call").flatMap(_.strOpt)
        sig  <- fields.get("sig").flatMap(_.strOpt)
      } yield call -> sig
    }

  private def malformed(detail: String): LLMError =
    ProcessingError("perplexity-tool-calls", s"malformed function call: $detail")

  /** The error a failed response, or a `response.failed` event, reports: its `code` and `message`, redacted. */
  private[provider] def failed(error: Option[ujson.Value]): LLMError = {
    val fields  = error.flatMap(_.objOpt)
    val code    = fields.flatMap(_.get("code")).flatMap(_.strOpt).filter(_.nonEmpty)
    val message = fields.flatMap(_.get("message")).flatMap(_.strOpt).filter(_.nonEmpty)
    val detail  = Seq(code, message).flatten.mkString(": ")
    ServiceError(
      502,
      ProviderName,
      Redaction.safeBody(if (detail.isEmpty) "the response failed" else s"the response failed: $detail", 512)
    )
  }

  /** Reads a response object; see "Reply" in the class Scaladoc. */
  private[provider] def parseReply(json: ujson.Value): Result[Reply] =
    for {
      fields <- json.objOpt.toRight(ValidationError("responseBody", "perplexity response is not a JSON object"))
      status = fields.get("status").flatMap(_.strOpt).map(_.trim.toLowerCase(java.util.Locale.ROOT))
      _ <- status match {
        case None | Some("completed") | Some("incomplete") => Right(())
        case Some("failed")                                => Left(failed(fields.get("error")))
        case Some(other) =>
          Left(ServiceError(502, ProviderName, s"the response ended with status '$other', not completed"))
      }
      output <- fields
        .get("output")
        .flatMap(_.arrOpt)
        .toRight(ValidationError("responseBody", "perplexity response has no 'output' array"))
      calls <- output.toList
        .flatMap(_.objOpt)
        .filter(itemType(_) == "function_call")
        .foldLeft[Result[List[(ToolCall, Option[String])]]](
          Right(Nil)
        )((done, item) => done.flatMap(acc => parseCall(item).map(acc :+ _)))
    } yield {
      val items = output.toList.flatMap(_.objOpt)
      val parts = items
        .filter(itemType(_) == "message")
        .flatMap(_.get("content").flatMap(_.arrOpt).toList.flatten)
        .flatMap(_.objOpt)
        .filter(part => part.get("type").flatMap(_.strOpt).forall(_ == "output_text"))
      val text        = parts.flatMap(_.get("text").flatMap(_.strOpt)).mkString
      val annotations = parts.flatMap(_.get("annotations").flatMap(_.arrOpt).toList.flatten).flatMap(annotation)
      val results = items
        .filter(itemType(_) == "search_results")
        .flatMap(_.get("results").flatMap(_.arrOpt).toList.flatten)
        .flatMap(searchResult)
      val citations = (annotations ++ results)
        .foldLeft((List.empty[Citation], Set.empty[String])) { case ((kept, seen), c) =>
          if (seen.contains(c.url)) (kept, seen) else (kept :+ c, seen + c.url)
        }
        ._1
      val usage = fields.get("usage").flatMap(_.objOpt)
      Reply(
        id = fields.get("id").flatMap(_.strOpt).getOrElse(""),
        created = fields.get("created_at").flatMap(_.numOpt).map(s => Instant.ofEpochSecond(s.toLong)),
        model = fields.get("model").flatMap(_.strOpt).filter(_.nonEmpty),
        text = text,
        toolCalls = calls.map(_._1),
        signatures = calls.collect { case (call, Some(sig)) =>
          ThinkingBlock.Opaque(ProviderName, encode(call.id, sig))
        },
        citations = citations,
        usage = usage.flatMap(parseUsage),
        cost = usage.flatMap(parseCost),
        incomplete = status.contains("incomplete")
      )
    }

  private def itemType(item: collection.Map[String, ujson.Value]): String =
    item.get("type").flatMap(_.strOpt).getOrElse("")

  /** A `function_call` output item: its call, and its `thought_signature` when it has one. */
  private def parseCall(item: collection.Map[String, ujson.Value]): Result[(ToolCall, Option[String])] =
    for {
      callId <- item.get("call_id").flatMap(_.strOpt).filter(_.nonEmpty).toRight(malformed("it has no call_id"))
      name   <- item.get("name").flatMap(_.strOpt).filter(_.nonEmpty).toRight(malformed("it has no name"))
      args   <- arguments(item.get("arguments"))
    } yield ToolCall(callId, name, args) -> item.get("thought_signature").flatMap(_.strOpt).filter(_.nonEmpty)

  /** A call's `arguments`, a JSON string on the wire, as a JSON object; absent or blank is `{}`. */
  private def arguments(raw: Option[ujson.Value]): Result[ujson.Value] = raw match {
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

  /** A `url_citation` annotation; any other kind, or one without a URL, is skipped. */
  private def annotation(value: ujson.Value): Option[Citation] =
    for {
      fields <- value.objOpt
      if fields.get("type").flatMap(_.strOpt).forall(_ == "url_citation")
      url <- fields.get("url").flatMap(_.strOpt).filter(_.nonEmpty)
    } yield Citation(
      url = url,
      title = fields.get("title").flatMap(_.strOpt).filter(_.nonEmpty),
      startIndex = fields.get("start_index").flatMap(_.numOpt).map(_.toInt),
      endIndex = fields.get("end_index").flatMap(_.numOpt).map(_.toInt)
    )

  /** One result of a `search_results` item; one without a URL is skipped. */
  private def searchResult(value: ujson.Value): Option[Citation] =
    for {
      fields <- value.objOpt
      url    <- fields.get("url").flatMap(_.strOpt).filter(_.nonEmpty)
    } yield Citation(
      url = url,
      title = fields.get("title").flatMap(_.strOpt).filter(_.nonEmpty),
      citedText = fields.get("snippet").flatMap(_.strOpt).filter(_.nonEmpty)
    )

  private def count(fields: collection.Map[String, ujson.Value], key: String): Option[Int] =
    fields.get(key).flatMap(_.numOpt).map(_.toInt)

  private[provider] def parseUsage(usage: collection.Map[String, ujson.Value]): Option[TokenUsage] =
    for {
      input  <- count(usage, "input_tokens")
      output <- count(usage, "output_tokens")
    } yield {
      val inputDetails  = usage.get("input_tokens_details").flatMap(_.objOpt)
      val outputDetails = usage.get("output_tokens_details").flatMap(_.objOpt)
      TokenUsage(
        promptTokens = input,
        completionTokens = output,
        totalTokens = count(usage, "total_tokens").getOrElse(input + output),
        thinkingTokens = outputDetails.flatMap(count(_, "reasoning_tokens")),
        cachedTokens = inputDetails.flatMap(d => count(d, "cached_tokens").orElse(count(d, "cache_read_input_tokens"))),
        cacheCreationTokens = inputDetails.flatMap(count(_, "cache_creation_input_tokens"))
      )
    }

  /** The request's cost as Perplexity reports it, `usage.cost.total_cost`, when it is in USD. */
  private[provider] def parseCost(usage: collection.Map[String, ujson.Value]): Option[Double] =
    usage
      .get("cost")
      .flatMap(_.objOpt)
      .filter(_.get("currency").flatMap(_.strOpt).forall(_.equalsIgnoreCase("USD")))
      .flatMap(_.get("total_cost"))
      .flatMap(_.numOpt)
}
