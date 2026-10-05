package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.http.{ HttpFailures, Llm4sHttpClient }
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }

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
 * == Tool calling limitation ==
 *
 * The Ollama chat API does not support tool results in multi-turn
 * conversations in the same way as cloud providers. As a result,
 * `ToolMessage` values are silently dropped when building the request —
 * only `SystemMessage`, `UserMessage`, and `AssistantMessage` entries
 * are forwarded to the model. Conversations that rely on tool call
 * round-trips should use a different provider.
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
              .flatMap(json => Try(parseCompletion(json)).toResult)
          } else {
            HttpErrorMapper.mapHttpError(response.statusCode, response.body, providerName, response.headers)
          }
        recordingExchange(startedAt, requestText, response.body)(result)
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
          HttpErrorMapper.mapHttpError(response.statusCode, err, providerName, response.headers)
        )
      case Right(response) =>
        val accumulator = StreamingAccumulator.create()
        val processResult = Using(new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))) {
          reader =>
            Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach { line =>
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

                val chunk = StreamedChunk(
                  id = json.obj.get("id").flatMap(_.strOpt).getOrElse(""),
                  content = contentOpt,
                  toolCall = None,
                  finishReason = if (done) Some("stop") else None
                )

                accumulator.addChunk(chunk)
                onChunk(chunk)

                // token counts (if present) only appear at the end
                if (done) {
                  val prompt = json.obj.get("prompt_eval_count").flatMap(_.numOpt).map(_.toInt).getOrElse(0)
                  val comp   = json.obj.get("eval_count").flatMap(_.numOpt).map(_.toInt).getOrElse(0)
                  if (prompt > 0 || comp > 0) accumulator.updateTokens(prompt, comp)
                }
              }
            }
        }.toEither.left.map(HttpFailures.streamReadError(_, url, 10.minutes))

        val result = processResult
          .flatMap(_ => accumulator.toCompletion)
          .map { c =>
            val cost = c.usage.flatMap(u => CostEstimator.estimate(config.model, u))
            c.withModel(config.model).withEstimatedCost(cost)
          }

        recordingExchange(startedAt, requestText, rawResponse.result())(result)
    }
  }

  private[provider] def createRequestBody(
    conversation: Conversation,
    options: CompletionOptions,
    stream: Boolean
  ): ujson.Obj = {
    val msgs = ujson.Arr.from(conversation.messages.collect {
      case SystemMessage(content) => ujson.Obj("role" -> "system", "content" -> content)
      case UserMessage(content)   => ujson.Obj("role" -> "user", "content" -> content)
      case am: AssistantMessage   => ujson.Obj("role" -> "assistant", "content" -> am.content)
      // Tool messages are not supported by Ollama chat API; drop them
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
    options.responseFormat.foreach(rf => body("format") = OllamaClient.encodeFormat(rf))
    body
  }

  private def parseCompletion(json: ujson.Value): Completion = {
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

    Completion(
      id = id,
      created = created,
      content = content,
      toolCalls = List.empty,
      usage = usage,
      model = config.model,
      message = AssistantMessage(content),
      estimatedCost = cost
    )
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
