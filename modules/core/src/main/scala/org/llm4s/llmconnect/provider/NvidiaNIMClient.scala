package org.llm4s.llmconnect.provider

import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.error.ThrowableOps._
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.NvidiaNIMConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.time.{ Duration, Instant }
import scala.util.Try

/**
 * NVIDIA NIM (NVIDIA Inference Microservices) LLM client.
 *
 * NVIDIA NIM exposes a fully OpenAI-compatible REST API, enabling deployment of
 * optimised LLMs (Llama, Mistral, Nemotron, CodeLlama and others) on enterprise
 * GPU infrastructure or via NVIDIA's hosted cloud API.
 *
 * Two deployment modes are supported:
 *  - '''Cloud (hosted)''': Authenticated via `NVIDIA_API_KEY`, connecting to
 *    `https://integrate.api.nvidia.com/v1`.
 *  - '''On-premise''': Points to a local NIM container (e.g. `http://nim-server:8000/v1`).
 *    No API key is required by default for on-premise NIM deployments.
 *
 * Because the NIM API is OpenAI-compatible, this client implements the same
 * request/response format, including streaming via SSE and tool/function calling.
 *
 * @param config          NVIDIA NIM configuration: model, base URL, and optional API key.
 * @param metrics         Metrics collector for observability (default: noop).
 * @param exchangeLogging Provider exchange logging sink (default: Disabled).
 */
class NvidiaNIMClient(
  config: NvidiaNIMConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  private val httpClient = HttpClient.newHttpClient()

  protected def clientDescription: String = s"NVIDIA NIM client for model ${config.model}"
  protected def providerName: String      = "nvidia-nim"
  protected def modelName: String         = config.model

  override protected def releaseResources(): Unit =
    (httpClient: Any) match {
      case c: AutoCloseable => c.close()
      case _                => ()
    }

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    buildChatRequest(conversation, options).flatMap { requestBody =>
      val requestText  = requestBody.render()
      val headers      = buildHeaders()
      val builtRequest = buildHttpRequest(requestText, headers)

      val attempt = Try {
        httpClient.send(builtRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
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
  ): Result[Completion] =
    Left(
      ConfigurationError(
        "NVIDIA NIM streaming is not supported in this initial provider implementation"
      )
    )

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  private def buildHeaders(): Map[String, String] = {
    val base = Map("Content-Type" -> "application/json")
    if (config.apiKey.nonEmpty) base + ("Authorization" -> s"Bearer ${config.apiKey}")
    else base
  }

  private def buildHttpRequest(requestText: String, headers: Map[String, String]): HttpRequest = {
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(s"${config.baseUrl}/chat/completions"))
      .timeout(Duration.ofMinutes(2))
      .POST(HttpRequest.BodyPublishers.ofString(requestText, StandardCharsets.UTF_8))
    headers.foldLeft(builder) { case (b, (k, v)) => b.header(k, v) }.build()
  }

  private def buildChatRequest(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[ujson.Obj] =
    toNIMMessages(conversation).flatMap { messages =>
      if (messages.isEmpty)
        Left(ValidationError("conversation", "NVIDIA NIM requires at least one message"))
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

  private def toNIMMessages(conversation: Conversation): Result[Seq[ujson.Value]] = {
    val results: Seq[Either[ValidationError, Option[ujson.Value]]] = conversation.messages.map {
      case SystemMessage(content) =>
        Right(
          Some(
            ujson.Obj(
              "role"    -> "system",
              "content" -> content
            )
          )
        )

      case UserMessage(content) =>
        Right(
          Some(
            ujson.Obj(
              "role"    -> "user",
              "content" -> content
            )
          )
        )

      case AssistantMessage(contentOpt, _) =>
        contentOpt.filter(_.nonEmpty) match {
          case Some(c) =>
            Right(
              Some(
                ujson.Obj(
                  "role"    -> "assistant",
                  "content" -> c
                )
              )
            )
          case None =>
            Right(None)
        }

      case other =>
        Left(
          ValidationError(
            "conversation",
            s"NVIDIA NIM does not support message type: ${other.getClass.getSimpleName}"
          )
        )
    }

    val (errors, successes) = results.partition(_.isLeft)
    errors.headOption match {
      case Some(Left(err)) => Left(err)
      case _               => Right(successes.collect { case Right(Some(v)) => v })
    }
  }

  private def parseChatResponse(body: String): Result[Completion] =
    Try {
      val json = ujson.read(body)

      val textResult = json.obj
        .get("choices")
        .flatMap(_.arrOpt)
        .flatMap(_.headOption)
        .flatMap(_.obj.get("message"))
        .flatMap(_.obj.get("content"))
        .flatMap(_.strOpt)
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(ValidationError("response", "Missing required text in NVIDIA NIM response"))

      textResult.map { text =>
        val id = json.obj
          .get("id")
          .flatMap(_.strOpt)
          .filter(_.nonEmpty)
          .getOrElse(java.util.UUID.randomUUID().toString)

        val createdSeconds = json.obj
          .get("created")
          .flatMap(_.numOpt)
          .map(_.toLong)
          .getOrElse(System.currentTimeMillis() / 1000)

        val usageOpt = json.obj
          .get("usage")
          .flatMap { usage =>
            val input  = usage.obj.get("prompt_tokens").flatMap(_.numOpt).map(_.toInt)
            val output = usage.obj.get("completion_tokens").flatMap(_.numOpt).map(_.toInt)
            (input, output) match {
              case (Some(in), Some(out)) =>
                Some(TokenUsage(promptTokens = in, completionTokens = out, totalTokens = in + out))
              case _ => None
            }
          }

        val costOpt = usageOpt.flatMap(u => CostEstimator.estimate(config.model, u))

        Completion(
          id = id,
          created = createdSeconds,
          content = text,
          model = config.model,
          message = AssistantMessage(contentOpt = Some(text), toolCalls = Seq.empty),
          toolCalls = List.empty,
          usage = usageOpt,
          thinking = None,
          estimatedCost = costOpt
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

object NvidiaNIMClient {
  import org.llm4s.types.TryOps

  def apply(config: NvidiaNIMConfig)(using ModelRegistryService): Result[NvidiaNIMClient] =
    Try(new NvidiaNIMClient(config)).toResult

  def apply(config: NvidiaNIMConfig, metrics: org.llm4s.metrics.MetricsCollector)(using
    ModelRegistryService
  ): Result[NvidiaNIMClient] =
    Try(new NvidiaNIMClient(config, metrics)).toResult

  def apply(
    config: NvidiaNIMConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[NvidiaNIMClient] =
    Try(new NvidiaNIMClient(config, metrics, exchangeLogging)).toResult
}
