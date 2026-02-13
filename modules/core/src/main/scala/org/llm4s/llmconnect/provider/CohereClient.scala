package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.TransformationResult
import org.llm4s.types.Result
import org.llm4s.error.{ AuthenticationError, ConfigurationError, RateLimitError, ValidationError, ProcessingError }
import org.llm4s.error.ThrowableOps._
import org.slf4j.{ Logger, LoggerFactory }
import sttp.client4._
import ujson.{ Arr, Null, Obj }
import java.io.{ BufferedReader, InputStreamReader }
import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Try

/**
 * LLMClient implementation for Cohere's Command models.
 *
 * Provides access to Cohere's language models including Command-R and Command-R+.
 * Supports chat completions and streaming responses.
 *
 * == Usage ==
 * {{{
 * val config = CohereConfig.fromValues(
 *   modelName = "command-r-plus",
 *   apiKey = sys.env("COHERE_API_KEY"),
 *   baseUrl = CohereConfig.DEFAULT_BASE_URL
 * )
 * val client = CohereClient(config).getOrElse(sys.error("Failed to create client"))
 * }}}
 *
 * @param config Cohere configuration with API key and model settings
 * @param metrics metrics collector for observability
 */
class CohereClient(
  config: CohereConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
) extends LLMClient
    with MetricsRecording {

  private lazy val logger: Logger   = LoggerFactory.getLogger(getClass)
  private val closed: AtomicBoolean = new AtomicBoolean(false)
  private val backend               = DefaultSyncBackend()
  private val httpClient            = HttpClient.newHttpClient()

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = withMetrics("cohere", config.model) {
    validateNotClosed.flatMap { _ =>
      TransformationResult.transform(config.model, options, conversation.messages, dropUnsupported = true).flatMap {
        transformed =>
          val transformedConversation = conversation.copy(messages = transformed.messages)
          validateConversationForCohere(transformedConversation, transformed.options).flatMap { _ =>
            val payload = buildChatRequest(transformedConversation, transformed.options)
            val url     = uri"${config.baseUrl}/v1/chat"
            logger.debug(s"[CohereClient] POST $url model=${config.model}")
            val attempt = Try {
              basicRequest
                .post(url)
                .header("Authorization", s"Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .body(payload.render())
                .send(backend)
            }.toEither.left.map(e => e.toLLMError)
            attempt.flatMap { response =>
              response.body match {
                case Right(body)    => parseResponse(body)
                case Left(errorMsg) => parseErrorResponse(errorMsg, response.code.code)
              }
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
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = withMetrics("cohere", config.model) {
    validateNotClosed.flatMap { _ =>
      TransformationResult.transform(config.model, options, conversation.messages, dropUnsupported = true).flatMap {
        transformed =>
          val transformedConversation = conversation.copy(messages = transformed.messages)
          validateConversationForCohere(transformedConversation, transformed.options).flatMap { _ =>
            val payload = buildChatRequest(transformedConversation, transformed.options, stream = true)
            val url     = uri"${config.baseUrl}/v1/chat"
            logger.debug(s"[CohereClient] POST $url model=${config.model} (streaming)")
            val request = HttpRequest
              .newBuilder()
              .uri(URI.create(url.toString()))
              .header("Authorization", s"Bearer ${config.apiKey}")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(payload.render()))
              .build()

            val attempt = Try {
              httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            }.toEither.left.map(e => e.toLLMError)

            attempt.flatMap { response =>
              if (response.statusCode() >= 200 && response.statusCode() < 300) {
                val reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))
                val result = processStreamingResponse(reader, onChunk)
                Try(reader.close()); Try(response.body().close())
                result
              } else {
                val errorMsg = new String(response.body().readAllBytes(), StandardCharsets.UTF_8)
                Try(response.body().close())
                parseErrorResponse(errorMsg, response.statusCode())
              }
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

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  override def validate(): Result[Unit] = validateNotClosed

  override def close(): Unit =
    if (!closed.getAndSet(true)) {
      Try(backend.close()).failed.foreach(e => logger.warn(s"Error closing Cohere client backend: ${e.getMessage}"))
      logger.debug("CohereClient closed")
    }

  private def validateNotClosed: Result[Unit] =
    if (closed.get()) Left(ConfigurationError("Cohere client has been closed"))
    else Right(())

  private def validateConversationForCohere(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Unit] = {
    val hasToolMessages = conversation.messages.exists {
      case _: ToolMessage                                       => true
      case AssistantMessage(_, toolCalls) if toolCalls.nonEmpty => true
      case _                                                    => false
    }

    if (options.tools.nonEmpty || hasToolMessages) {
      Left(ValidationError("tools", "Cohere tool calling is not supported yet"))
    } else {
      conversation.messages.lastOption match {
        case Some(UserMessage(_)) => Right(())
        case Some(_) => Left(ValidationError("conversation", "Cohere requires the last message to be a user prompt"))
        case None    => Left(ValidationError("conversation", "Cohere requires at least one user message"))
      }
    }
  }

  private def buildChatRequest(
    conversation: Conversation,
    options: CompletionOptions,
    stream: Boolean = false
  ): Obj = {
    val payload = Obj(
      "model"  -> config.model,
      "stream" -> stream
    )

    val userMessages = conversation.messages.collect { case UserMessage(content) => content }
    if (userMessages.nonEmpty) {
      payload("message") = userMessages.last
    }

    val historyMessages = conversation.messages.dropRight(1).flatMap {
      case UserMessage(content)  => Some(Obj("role" -> "USER", "message" -> content))
      case msg: AssistantMessage => Some(Obj("role" -> "CHATBOT", "message" -> msg.content))
      case _                     => None
    }
    if (historyMessages.nonEmpty) {
      payload("chat_history") = Arr.from(historyMessages)
    }

    val systemMessages = conversation.messages.collect { case SystemMessage(content) => content }
    if (systemMessages.nonEmpty) {
      payload("preamble") = systemMessages.mkString("\n")
    }

    if (options.temperature != 1.0) payload("temperature") = options.temperature
    if (options.topP != 1.0) payload("p") = options.topP
    options.maxTokens.foreach(mt => payload("max_tokens") = mt)

    payload
  }

  private def parseResponse(body: String): Result[Completion] =
    Try {
      val json       = ujson.read(body)
      val text       = json("text").str
      val responseId = json("generation_id").str

      val usage = if (json.obj.contains("meta")) {
        val meta        = json("meta")
        val billedUnits = if (meta.obj.contains("billed_units")) meta("billed_units") else Null
        val inputTokens =
          if (billedUnits != Null && billedUnits.obj.contains("input_tokens"))
            billedUnits("input_tokens").num.toInt
          else 0
        val outputTokens =
          if (billedUnits != Null && billedUnits.obj.contains("output_tokens"))
            billedUnits("output_tokens").num.toInt
          else 0
        Some(TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens))
      } else None

      Completion(
        id = responseId,
        created = Instant.now().toEpochMilli,
        content = text,
        model = config.model,
        message = AssistantMessage(text),
        usage = usage
      )
    }.toEither.left.map { ex =>
      logger.error(s"Failed to parse Cohere response: ${ex.getMessage}")
      ProcessingError("cohere", s"Failed to parse response: ${ex.getMessage}")
    }

  private def processStreamingResponse(reader: BufferedReader, onChunk: StreamedChunk => Unit): Result[Completion] =
    Try {
      val contentBuilder                 = new StringBuilder
      var lastResponseId                 = ""
      var finalUsage: Option[TokenUsage] = None
      var line: String                   = null

      while ({ line = reader.readLine(); line != null }) {
        val trimmed = line.trim
        if (trimmed.startsWith("data: ")) {
          val jsonStr = trimmed.stripPrefix("data: ").trim
          if (jsonStr.nonEmpty && jsonStr != "[DONE]") {
            Try(ujson.read(jsonStr)).foreach { json =>
              json.obj.get("event_type").foreach { eventType =>
                eventType.str match {
                  case "stream-start" =>
                    lastResponseId = json("generation_id").str

                  case "text-generation" =>
                    val text = json("text").str
                    contentBuilder.append(text)
                    onChunk(
                      StreamedChunk(
                        id = lastResponseId,
                        content = Some(text),
                        toolCall = None,
                        finishReason = None,
                        thinkingDelta = None
                      )
                    )

                  case "stream-end" =>
                    if (json.obj.contains("response")) {
                      val response = json("response")
                      if (response.obj.contains("meta")) {
                        val meta        = response("meta")
                        val billedUnits = if (meta.obj.contains("billed_units")) meta("billed_units") else Null
                        val inputTokens =
                          if (billedUnits != Null && billedUnits.obj.contains("input_tokens"))
                            billedUnits("input_tokens").num.toInt
                          else 0
                        val outputTokens =
                          if (billedUnits != Null && billedUnits.obj.contains("output_tokens"))
                            billedUnits("output_tokens").num.toInt
                          else 0
                        finalUsage = Some(TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens))
                      }
                    }

                  case _ =>
                }
              }
            }
          }
        }
      }

      Completion(
        id = lastResponseId,
        created = Instant.now().toEpochMilli,
        content = contentBuilder.toString,
        model = config.model,
        message = AssistantMessage(contentBuilder.toString),
        usage = finalUsage
      )
    }.toEither.left.map { ex =>
      logger.error(s"Failed to process Cohere streaming response: ${ex.getMessage}")
      ProcessingError("cohere", s"Failed to process streaming response: ${ex.getMessage}")
    }

  private def parseErrorResponse(errorMsg: String, statusCode: Int): Result[Completion] =
    Try {
      val json    = ujson.read(errorMsg)
      val message = if (json.obj.contains("message")) json("message").str else errorMsg
      statusCode match {
        case 401 => Left(AuthenticationError("cohere", message))
        case 429 => Left(RateLimitError("cohere"))
        case 400 => Left(ValidationError("input", message))
        case _   => Left(ProcessingError("cohere", message))
      }
    }.getOrElse {
      statusCode match {
        case 401 => Left(AuthenticationError("cohere", errorMsg))
        case 429 => Left(RateLimitError("cohere"))
        case 400 => Left(ValidationError("input", errorMsg))
        case _   => Left(ProcessingError("cohere", errorMsg))
      }
    }
}

/**
 * Factory methods for creating CohereClient instances.
 */
object CohereClient {
  import org.llm4s.types.TryOps

  def apply(config: CohereConfig, metrics: org.llm4s.metrics.MetricsCollector): Result[CohereClient] =
    Try(new CohereClient(config, metrics)).toResult

  def apply(config: CohereConfig): Result[CohereClient] =
    Try(new CohereClient(config, org.llm4s.metrics.MetricsCollector.noop)).toResult
}
