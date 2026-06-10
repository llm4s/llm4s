package org.llm4s.llmconnect.provider

import org.llm4s.error.ValidationError
import org.llm4s.error.ThrowableOps._
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.ProviderResultOps.*
import org.llm4s.llmconnect.streaming.{ SSEParser, StreamingAccumulator }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }

import java.io.{ BufferedReader, InputStreamReader }
import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import scala.util.Try

/**
 * Cohere provider client (v2 API).
 *
 * Supported:
 * - Non-streaming chat completion via Cohere v2 `/chat` API.
 * - Streaming via SSE using the Cohere v2 streaming event format.
 *
 * Intentionally not supported:
 * - Tool calling
 * - Embeddings
 * - Multimodal inputs
 */
class CohereClient(
  config: CohereConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  private val httpClient = HttpClient.newHttpClient()

  protected def clientDescription: String = s"Cohere client for model ${config.model}"
  protected def providerName: String      = "cohere"
  protected def modelName: String         = config.model

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    buildChatRequest(conversation, options).flatMap { requestBody =>
      val requestText = requestBody.render()
      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(s"${config.baseUrl}/v2/chat"))
        .header("Content-Type", "application/json")
        .header("Authorization", s"Bearer ${config.apiKey}")
        .timeout(Duration.ofMinutes(2))
        .POST(HttpRequest.BodyPublishers.ofString(requestText, StandardCharsets.UTF_8))
        .build()

      val attempt = Try {
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      }.toEither.left.map(_.toLLMError)

      attempt.flatMap { response =>
        val status = response.statusCode()
        if (status >= 200 && status < 300) {
          val completionResult = parseChatResponse(response.body())
          recordExchange(startedAt, requestText, Some(response.body()), completionResult)
          completionResult
        } else {
          val errorResult = handleErrorResponse(status, response.body())
          recordExchange(startedAt, requestText, Some(response.body()), errorResult)
          errorResult
        }
      }
    }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    buildChatRequest(conversation, options).flatMap { requestBody =>
      requestBody("stream") = true
      val requestText       = requestBody.render()
      val streamAccumulator = StreamingAccumulator.create()
      val rawStream         = StringBuilder()

      // Network-level failure: record immediately and propagate.
      val responseOrError = Try {
        val request = HttpRequest
          .newBuilder()
          .uri(URI.create(s"${config.baseUrl}/v2/chat"))
          .header("Content-Type", "application/json")
          .header("Authorization", s"Bearer ${config.apiKey}")
          .timeout(Duration.ofMinutes(5))
          .POST(HttpRequest.BodyPublishers.ofString(requestText, StandardCharsets.UTF_8))
          .build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
      }.toResult.tapLeft(error => recordExchange(startedAt, requestText, None, Left(error)))

      // HTTP error: record with response body and propagate.
      val streamOrError = responseOrError.flatMap { response =>
        if (response.statusCode() == 200) {
          Right(response)
        } else {
          val errorBody = Try(new String(response.body().readAllBytes(), StandardCharsets.UTF_8))
            .getOrElse("<error body unreadable>")
          val errorResult = HttpErrorMapper.mapHttpError(response.statusCode(), errorBody, providerName)
          recordExchange(startedAt, requestText, Some(errorBody), errorResult)
          errorResult
        }
      }

      // SSE parsing: record on I/O or parse failure.
      streamOrError
        .flatMap { response =>
          Try {
            val sseParser = SSEParser.createStreamingParser()
            val reader    = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))
            try {
              var line: String = null
              while ({ line = reader.readLine(); line != null }) {
                rawStream.append(line).append('\n')
                sseParser.addChunk(line + "\n")
                while (sseParser.hasEvents)
                  sseParser.nextEvent().foreach { event =>
                    event.data.foreach { data =>
                      parseCohereStreamingChunk(data, streamAccumulator).foreach(c => onChunk(c))
                    }
                  }
              }
            } finally {
              Try(reader.close())
              Try(response.body().close())
            }
          }.toEither.left.map { e =>
            val err = e.toLLMError
            recordExchange(startedAt, requestText, Option.when(rawStream.nonEmpty)(rawStream.result()), Left(err))
            err
          }
        }
        .flatMap(_ =>
          streamAccumulator.toCompletion.map { c =>
            val cost       = c.usage.flatMap(u => CostEstimator.estimate(config.model, u))
            val completion = c.copy(model = config.model, estimatedCost = cost)
            recordExchange(startedAt, requestText, Some(rawStream.result()), Right(completion))
            completion
          }
        )
    }
  }

  private def parseCohereStreamingChunk(data: String, accumulator: StreamingAccumulator): Option[StreamedChunk] =
    Try(ujson.read(data)).toOption.flatMap { json =>
      json.obj.get("type").flatMap(_.strOpt) match {
        case Some("message-start") =>
          val id    = json.obj.get("id").flatMap(_.strOpt).getOrElse("")
          val chunk = StreamedChunk(id = id, content = None, toolCall = None, finishReason = None)
          accumulator.addChunk(chunk)
          None

        case Some("content-delta") =>
          val textOpt = json.obj
            .get("delta")
            .flatMap(_.obj.get("message"))
            .flatMap(_.obj.get("content"))
            .flatMap(_.obj.get("text"))
            .flatMap(_.strOpt)
          textOpt.map { text =>
            val chunk = StreamedChunk(id = "", content = Some(text), toolCall = None, finishReason = None)
            accumulator.addChunk(chunk)
            chunk
          }

        case Some("message-end") =>
          val finishReason = json.obj
            .get("delta")
            .flatMap(_.obj.get("finish_reason"))
            .flatMap(_.strOpt)
          json.obj
            .get("delta")
            .flatMap(_.obj.get("usage"))
            .flatMap(_.obj.get("tokens"))
            .foreach { tokens =>
              val input  = tokens.obj.get("input_tokens").flatMap(_.numOpt).map(_.toInt)
              val output = tokens.obj.get("output_tokens").flatMap(_.numOpt).map(_.toInt)
              (input, output) match {
                case (Some(in), Some(out)) => accumulator.updateTokens(in, out)
                case _                     => ()
              }
            }
          val chunk = StreamedChunk(id = "", content = None, toolCall = None, finishReason = finishReason)
          accumulator.addChunk(chunk)
          None

        case _ => None
      }
    }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  override protected def releaseResources(): Unit =
    (httpClient: Any) match {
      case c: AutoCloseable => c.close()
      case _                => ()
    }

  private def buildChatRequest(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[ujson.Obj] = {
    val messages = toCohereV2Messages(conversation)

    if (messages.isEmpty)
      Left(ValidationError("conversation", "Cohere requires at least one message"))
    else {
      val req = ujson.Obj(
        "model"    -> config.model,
        "messages" -> ujson.Arr(messages: _*)
      )

      req("temperature") = options.temperature
      options.maxTokens.foreach(mt => req("max_tokens") = mt)

      Right(req)
    }
  }

  private def toCohereV2Messages(conversation: Conversation): Seq[ujson.Value] =
    conversation.messages.flatMap {
      case SystemMessage(content) =>
        Some(
          ujson.Obj(
            "role" -> "system",
            "content" -> ujson.Arr(
              ujson.Obj(
                "type" -> "text",
                "text" -> content
              )
            )
          )
        )

      case UserMessage(content) =>
        Some(
          ujson.Obj(
            "role" -> "user",
            "content" -> ujson.Arr(
              ujson.Obj(
                "type" -> "text",
                "text" -> content
              )
            )
          )
        )

      case AssistantMessage(contentOpt, _) =>
        contentOpt.filter(_.nonEmpty).map { c =>
          ujson.Obj(
            "role" -> "assistant",
            "content" -> ujson.Arr(
              ujson.Obj(
                "type" -> "text",
                "text" -> c
              )
            )
          )
        }

      case _ =>
        None
    }

  private def parseChatResponse(body: String): Result[Completion] =
    Try {
      val json = ujson.read(body)

      val textOpt = json.obj
        .get("message")
        .flatMap(_.obj.get("content"))
        .flatMap(_.arrOpt)
        .flatMap { contentArr =>
          contentArr.collectFirst(Function.unlift { v =>
            v.obj
              .get("text")
              .flatMap(_.strOpt)
              .map(_.trim)
              .filter(_.nonEmpty)
          })
        }

      val text = textOpt.getOrElse("")

      val generationId   = json.obj.get("id").flatMap(_.strOpt).getOrElse("")
      val createdSeconds = System.currentTimeMillis() / 1000

      val usageOpt = json.obj
        .get("usage")
        .flatMap(_.obj.get("tokens"))
        .flatMap { tokens =>
          val input  = tokens.obj.get("input_tokens").flatMap(_.numOpt).map(_.toInt)
          val output = tokens.obj.get("output_tokens").flatMap(_.numOpt).map(_.toInt)
          (input, output) match {
            case (Some(in), Some(out)) =>
              Some(TokenUsage(promptTokens = in, completionTokens = out, totalTokens = in + out))
            case _ => None
          }
        }

      val costOpt = usageOpt.flatMap(u => CostEstimator.estimate(config.model, u))

      val assistantMessage =
        AssistantMessage(contentOpt = if (text.nonEmpty) Some(text) else None, toolCalls = Seq.empty)

      textOpt match {
        case None =>
          Left(ValidationError("response", "Missing required text in Cohere v2 response"))
        case Some(_) =>
          Right(
            Completion(
              id = if (generationId.nonEmpty) generationId else java.util.UUID.randomUUID().toString,
              created = createdSeconds,
              content = text,
              model = config.model,
              message = assistantMessage,
              toolCalls = List.empty,
              usage = usageOpt,
              thinking = None,
              estimatedCost = costOpt
            )
          )
      }
    }.toEither.left.map(_.toLLMError).flatten

  private def handleErrorResponse(statusCode: Int, body: String): Result[Nothing] =
    HttpErrorMapper.mapHttpError(statusCode, body, providerName)

  private def recordExchange(
    startedAt: Instant,
    requestBody: String,
    responseBody: Option[String],
    result: Result[?]
  ): Unit =
    ProviderExchangeRecorder.record(
      exchangeLogging = exchangeLogging,
      provider = providerName,
      model = Some(config.model),
      startedAt = startedAt,
      requestBody = requestBody,
      responseBody = responseBody,
      result = result
    )
}

object CohereClient {
  import org.llm4s.types.TryOps

  def apply(config: CohereConfig)(using ModelRegistryService): Result[CohereClient] =
    Try(new CohereClient(config)).toResult

  def apply(config: CohereConfig, metrics: org.llm4s.metrics.MetricsCollector)(using
    ModelRegistryService
  ): Result[CohereClient] =
    Try(new CohereClient(config, metrics)).toResult

  def apply(
    config: CohereConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[CohereClient] =
    Try(new CohereClient(config, metrics, exchangeLogging)).toResult
}
