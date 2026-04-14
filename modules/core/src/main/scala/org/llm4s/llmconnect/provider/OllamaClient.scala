package org.llm4s.llmconnect.provider

import org.llm4s.error.{ ExecutionError, NetworkError, ServiceError }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }

import java.io.{ BufferedReader, IOException, InputStreamReader }
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.util.Try

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
    try {
      val response = httpClient.post(url, headers, requestText, timeout = 120000)
      val result =
        if (response.statusCode >= 200 && response.statusCode < 300) {
          Try(ujson.read(response.body)).toResult
            .flatMap(json => Try(parseCompletion(json)).toResult)
        } else {
          HttpErrorMapper.mapHttpError(response.statusCode, response.body, providerName)
        }
      recordExchange(startedAt, requestText, response.body, result)
      result
    } catch {
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        val error = ExecutionError(
          s"Ollama request interrupted: ${e.getMessage}",
          operation = "ollama.chat",
          exitCode = None,
          cause = Some(e),
          context = Map.empty
        )
        recordExchange(startedAt, requestText, "", Left(error))
        Left(
          ExecutionError(
            s"Ollama request interrupted: ${e.getMessage}",
            operation = "ollama.chat",
            exitCode = None,
            cause = Some(e),
            context = Map.empty
          )
        )
      case e: IOException =>
        val error = NetworkError("Failed to connect to Ollama", Some(e), config.baseUrl)
        recordExchange(startedAt, requestText, "", Left(error))
        Left(error)
      case scala.util.control.NonFatal(e) =>
        val error = ServiceError(500, "ollama", s"Unexpected error: ${e.getMessage}")
        recordExchange(startedAt, requestText, "", Left(error))
        Left(error)
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

    try {
      val response = httpClient.postStream(url, headers, requestText, timeout = 600000)
      if (response.statusCode != 200) {
        val err = new String(response.body.readAllBytes(), StandardCharsets.UTF_8)
        response.body.close()
        val result = HttpErrorMapper.mapHttpError(response.statusCode, err, providerName)
        recordExchange(startedAt, requestText, err, result)
        result
      } else {
        val accumulator = StreamingAccumulator.create()
        val reader      = new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))
        val processResult = Try {
          try {
            var line: String = null
            while ({ line = reader.readLine(); line != null }) {
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
          } finally {
            Try(reader.close())
            Try(response.body.close())
          }
        }.toResult

        val result = processResult
          .flatMap(_ => accumulator.toCompletion)
          .map { c =>
            val cost = c.usage.flatMap(u => CostEstimator.estimate(config.model, u))
            c.copy(model = config.model, estimatedCost = cost)
          }

        recordExchange(startedAt, requestText, rawResponse.result(), result)
        result
      }
    } catch {
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        val error = ExecutionError(
          s"Ollama streaming request interrupted: ${e.getMessage}",
          operation = "ollama.stream",
          exitCode = None,
          cause = Some(e),
          context = Map.empty
        )
        recordExchange(startedAt, requestText, rawResponse.result(), Left(error))
        Left(
          ExecutionError(
            s"Ollama streaming request interrupted: ${e.getMessage}",
            operation = "ollama.stream",
            exitCode = None,
            cause = Some(e),
            context = Map.empty
          )
        )
      case e: IOException =>
        val error = NetworkError("Failed to connect to Ollama stream", Some(e), config.baseUrl)
        recordExchange(startedAt, requestText, rawResponse.result(), Left(error))
        Left(error)
      case scala.util.control.NonFatal(e) =>
        val error = ServiceError(500, "ollama", s"Unexpected streaming error: ${e.getMessage}")
        recordExchange(startedAt, requestText, rawResponse.result(), Left(error))
        Left(error)
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

    ujson.Obj(
      "model"    -> config.model,
      "messages" -> msgs,
      "stream"   -> stream,
      "options"  -> opts
    )
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
