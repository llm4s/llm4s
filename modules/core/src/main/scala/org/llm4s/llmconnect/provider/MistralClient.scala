package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, ConfigurationError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.error.ThrowableOps._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.MistralConfig
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
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
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
) extends LLMClient
    with MetricsRecording {

  private val httpClient            = HttpClient.newHttpClient()
  private val closed: AtomicBoolean = new AtomicBoolean(false)

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] =
    withMetrics(
      provider = "mistral",
      model = config.model,
      operation = validateNotClosed.flatMap { _ =>
        buildChatRequest(conversation, options).flatMap { requestBody =>
          val request = HttpRequest
            .newBuilder()
            .uri(URI.create(s"${config.baseUrl}/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", s"Bearer ${config.apiKey}")
            .timeout(Duration.ofMinutes(2))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.render(), StandardCharsets.UTF_8))
            .build()

          val attempt = Try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
          }.toEither.left.map(_.toLLMError)

          attempt.flatMap { response =>
            val status = response.statusCode()
            if (status >= 200 && status < 300) {
              parseChatResponse(response.body())
            } else {
              handleErrorResponse(status, response.body())
            }
          }
        }
      },
      extractUsage = (c: Completion) => c.usage,
      extractCost = (c: Completion) => c.estimatedCost
    )

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    Left(
      ConfigurationError(
        "Mistral streaming is not supported in this minimal v1 provider implementation"
      )
    )

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  override def close(): Unit =
    if (closed.compareAndSet(false, true)) {
      ()
    }

  private def validateNotClosed: Result[Unit] =
    if (closed.get()) {
      Left(ConfigurationError(s"Mistral client for model ${config.model} is already closed"))
    } else {
      Right(())
    }

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

  private val MaxErrorLength = 256

  private def sanitizeErrorDetail(raw: String): String = {
    val trimmed = raw.trim
    if (trimmed.length <= MaxErrorLength) trimmed
    else trimmed.take(MaxErrorLength) + "…[truncated]"
  }

  private def handleErrorResponse(statusCode: Int, body: String): Result[Nothing] = {
    val details = sanitizeErrorDetail(
      Try {
        val json = ujson.read(body)
        json.obj
          .get("message")
          .flatMap(_.strOpt)
          .orElse(
            json.obj
              .get("error")
              .flatMap(v => v.obj.get("message").flatMap(_.strOpt).orElse(v.strOpt))
          )
          .getOrElse(s"Mistral API error (HTTP $statusCode)")
      }.getOrElse(s"Mistral API error (HTTP $statusCode)")
    )

    statusCode match {
      case 401 | 403 => Left(AuthenticationError("mistral", details))
      case 429       => Left(RateLimitError("mistral"))
      case 400       => Left(ValidationError("request", details))
      case s         => Left(ServiceError(s, "mistral", details))
    }
  }

}

object MistralClient {
  import org.llm4s.types.TryOps

  def apply(config: MistralConfig): Result[MistralClient] =
    Try(new MistralClient(config)).toResult

  def apply(config: MistralConfig, metrics: org.llm4s.metrics.MetricsCollector): Result[MistralClient] =
    Try(new MistralClient(config, metrics)).toResult
}
