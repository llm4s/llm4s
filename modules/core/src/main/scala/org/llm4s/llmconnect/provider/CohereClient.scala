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
 * Minimal Cohere provider client (v1 scope).
 *
 * Supported:
 * - Non-streaming chat completion via Cohere v1 `/chat` API.
 *
 * Intentionally not supported in v1:
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
  ): Result[Completion] = withMetrics("cohere", config.model) {
    validateNotClosed.flatMap { _ =>
      buildChatRequest(conversation, options).flatMap { requestBody =>
        val request = HttpRequest
          .newBuilder()
          .uri(URI.create(s"${config.baseUrl}/v1/chat"))
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
    }
  }(
    extractUsage = _.usage,
    estimateCost = usage =>
      org.llm4s.model.ModelRegistry.lookup(config.model).toOption.flatMap { meta =>
        meta.pricing.estimateCost(usage.promptTokens, usage.completionTokens)
      }
  )

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    Left(
      ConfigurationError(
        "Cohere streaming is not supported in this minimal v1 provider implementation"
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
      Left(ConfigurationError(s"Cohere client for model ${config.model} is already closed"))
    } else {
      Right(())
    }

  private def buildChatRequest(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[ujson.Obj] = {
    val systemPreamble = conversation.messages.collectFirst { case SystemMessage(content) => content }

    val lastUserMessageOpt = conversation.messages.reverse.collectFirst { case UserMessage(content) => content }

    val lastUserMessage =
      lastUserMessageOpt.toRight(ValidationError("conversation", "Cohere requires a final user message"))

    lastUserMessage.map { message =>
      val chatHistory = toChatHistory(conversation)

      val req = ujson.Obj(
        "model"   -> config.model,
        "message" -> message
      )

      systemPreamble.foreach(p => req("preamble") = p)
      if (chatHistory.nonEmpty)
        req("chat_history") = ujson.Arr(chatHistory: _*)

      // Map supported completion options (minimal v1 scope).
      req("temperature") = options.temperature
      options.maxTokens.foreach(mt => req("max_tokens") = mt)

      req
    }
  }

  private def toChatHistory(conversation: Conversation): Seq[ujson.Value] = {
    val systemIndexOpt = conversation.messages.indexWhere(_.isInstanceOf[SystemMessage]) match {
      case -1 => None
      case i  => Some(i)
    }

    val lastUserIndexOpt = {
      val idx = conversation.messages.lastIndexWhere(_.isInstanceOf[UserMessage])
      if (idx == -1) None else Some(idx)
    }

    (systemIndexOpt, lastUserIndexOpt) match {
      case (_, None) =>
        Seq.empty

      case (sysIdxOpt, Some(lastUserIdx)) =>
        val start = sysIdxOpt.map(_ + 1).getOrElse(0)
        val end   = math.max(start, lastUserIdx)

        conversation.messages.slice(start, end).flatMap {
          case UserMessage(content) =>
            Some(ujson.Obj("role" -> "USER", "message" -> content))

          case AssistantMessage(contentOpt, _) =>
            contentOpt.filter(_.nonEmpty).map(c => ujson.Obj("role" -> "CHATBOT", "message" -> c))

          case _ =>
            None
        }
    }
  }

  private def parseChatResponse(body: String): Result[Completion] =
    Try {
      val json           = ujson.read(body)
      val text           = json.obj.get("text").flatMap(_.strOpt).getOrElse("")
      val generationId   = json.obj.get("generation_id").flatMap(_.strOpt).getOrElse("")
      val createdSeconds = System.currentTimeMillis() / 1000

      val usageOpt = json.obj
        .get("meta")
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

      val assistantMessage =
        AssistantMessage(contentOpt = if (text.nonEmpty) Some(text) else None, toolCalls = Seq.empty)

      Right(
        Completion(
          id = if (generationId.nonEmpty) generationId else java.util.UUID.randomUUID().toString,
          created = createdSeconds,
          content = text,
          model = config.model,
          message = assistantMessage,
          toolCalls = List.empty,
          usage = usageOpt,
          thinking = None
        )
      )
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
