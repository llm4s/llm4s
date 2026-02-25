package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, ConfigurationError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.error.ThrowableOps._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Try

/**
 * Minimal Cohere provider client (v2 scope).
 *
 * Supported:
 * - Non-streaming chat completion via Cohere v2 `/chat` API.
 *
 * Intentionally not supported in v2:
 * - Streaming
 * - Tool calling
 * - Embeddings
 * - Multimodal inputs
 */
class CohereClient(
  config: CohereConfig,
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
      provider = "cohere",
      model = config.model,
      operation = validateNotClosed.flatMap { _ =>
        buildChatRequest(conversation, options).flatMap { requestBody =>
          val request = HttpRequest
            .newBuilder()
            .uri(URI.create(s"${config.baseUrl}/v2/chat"))
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
        "Cohere streaming is not supported in this minimal v2 provider implementation"
      )
    )

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  override def close(): Unit =
    if (closed.compareAndSet(false, true)) {
      (httpClient: Any) match {
        case c: AutoCloseable => c.close()
        case _                => ()
      }
    }

  private def validateNotClosed: Result[Unit] =
    if (closed.get()) {
      Left(ConfigurationError(s"Cohere client for model ${config.model} is already closed"))
    } else {
      Right(())
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

  private def handleErrorResponse(statusCode: Int, body: String): Result[Nothing] = {
    val details = Try {
      val json = ujson.read(body)
      json.obj
        .get("message")
        .flatMap(_.strOpt)
        .orElse(json.obj.get("error").flatMap(_.strOpt))
        .getOrElse(body)
    }.getOrElse(body)

    statusCode match {
      case 401 | 403     => Left(AuthenticationError("cohere", details))
      case 429           => Left(RateLimitError("cohere"))
      case 400           => Left(ValidationError("request", details))
      case s if s >= 500 => Left(ServiceError(s, "cohere", details))
      case s             => Left(ServiceError(s, "cohere", details))
    }
  }
}

object CohereClient {
  import org.llm4s.types.TryOps

  def apply(config: CohereConfig): Result[CohereClient] =
    Try(new CohereClient(config)).toResult

  def apply(config: CohereConfig, metrics: org.llm4s.metrics.MetricsCollector): Result[CohereClient] =
    Try(new CohereClient(config, metrics)).toResult
}
