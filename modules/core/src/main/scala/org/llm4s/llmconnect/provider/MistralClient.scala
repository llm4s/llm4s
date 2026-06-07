package org.llm4s.llmconnect.provider

import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.error.ThrowableOps._
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.MistralConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.Duration
import scala.util.Try

/**
 * Mistral AI provider client using the OpenAI-compatible chat completions API.
 *
 * Supported:
 * - Non-streaming chat completion via Mistral `/v1/chat/completions` API.
 *
 * Intentionally not supported in v1:
 * - Streaming
 * - Tool calling
 * - Embeddings
 * - Multimodal inputs
 */
class MistralClient(
  config: MistralConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  private val httpClient = HttpClient.newHttpClient()

  protected def clientDescription: String = s"Mistral client for model ${config.model}"
  protected def providerName: String      = "mistral"
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
      val requestText = requestBody.render()
      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(s"${config.baseUrl}/v1/chat/completions"))
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
  ): Result[Completion] =
    // TODO: When Mistral streaming is implemented here, add raw streaming
    // exchange capture using the same completed-or-partial logging contract
    // used by the other streaming-capable providers.
    Left(
      ConfigurationError(
        "Mistral streaming is not supported in this minimal v1 provider implementation"
      )
    )

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  private def buildChatRequest(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[ujson.Obj] =
    toMistralMessages(conversation).flatMap { messages =>
      if (messages.isEmpty)
        Left(ValidationError("conversation", "Mistral requires at least one message"))
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

  private def toMistralMessages(conversation: Conversation): Result[Seq[ujson.Value]] = {
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
            Right(None) // skip empty assistant messages
        }

      case other =>
        Left(
          ValidationError(
            "conversation",
            s"Mistral does not support message type: ${other.getClass.getSimpleName}"
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

      // Monadic extraction of required text
      val textResult = json.obj
        .get("choices")
        .flatMap(_.arrOpt)
        .flatMap(_.headOption)
        .flatMap(_.obj.get("message"))
        .flatMap(_.obj.get("content"))
        .flatMap(_.strOpt)
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(ValidationError("response", "Missing required text in Mistral response"))

      textResult.map { text =>
        // Fallback to random UUID if id is missing in response; safe default for tracking
        val id = json.obj
          .get("id")
          .flatMap(_.strOpt)
          .filter(_.nonEmpty)
          .getOrElse(java.util.UUID.randomUUID().toString)

        // Fallback to current time if created is missing; safe default for tracking
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

object MistralClient {
  import org.llm4s.types.TryOps

  def apply(config: MistralConfig)(using ModelRegistryService): Result[MistralClient] =
    Try(new MistralClient(config)).toResult

  def apply(config: MistralConfig, metrics: org.llm4s.metrics.MetricsCollector)(using
    ModelRegistryService
  ): Result[MistralClient] =
    Try(new MistralClient(config, metrics)).toResult

  def apply(
    config: MistralConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[MistralClient] =
    Try(new MistralClient(config, metrics, exchangeLogging)).toResult
}
