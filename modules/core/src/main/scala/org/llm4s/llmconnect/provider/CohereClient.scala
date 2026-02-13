package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, ConfigurationError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.error.ThrowableOps._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming.{ SSEParser, StreamingAccumulator }
import org.llm4s.model.TransformationResult
import org.llm4s.types.Result
import org.llm4s.util.Redaction
import org.slf4j.LoggerFactory

import java.io.{ BufferedReader, InputStreamReader }
import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Try

/**
 * LLMClient implementation for Cohere Command-R models.
 *
 * Provides access to Cohere's chat API with SSE streaming support.
 *
 * @param config Cohere configuration with API key, model, and base URL
 * @param metrics metrics collector for observability (default: noop)
 */
class CohereClient(
  config: CohereConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
) extends LLMClient
    with MetricsRecording {
  private val httpClient            = HttpClient.newHttpClient()
  private val logger                = LoggerFactory.getLogger(getClass)
  private val closed: AtomicBoolean = new AtomicBoolean(false)

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = withMetrics("cohere", config.model) {
    validateNotClosed.flatMap { _ =>
      TransformationResult.transform(config.model, options, conversation.messages, dropUnsupported = true).flatMap {
        transformed =>
          val transformedConversation = conversation.copy(messages = transformed.messages)
          buildRequestBody(transformedConversation, transformed.options, stream = false).flatMap { requestBody =>
            val request = HttpRequest
              .newBuilder()
              .uri(URI.create(s"${config.baseUrl}/v1/chat"))
              .header("Content-Type", "application/json")
              .header("Authorization", s"Bearer ${config.apiKey}")
              .header("User-Agent", "llm4s/1.0")
              .timeout(Duration.ofMinutes(2))
              .POST(HttpRequest.BodyPublishers.ofString(requestBody.render()))
              .build()

            logger.debug(s"[Cohere] POST ${config.baseUrl}/v1/chat")
            logger.debug(s"[Cohere] Request body: ${Redaction.redactForLogging(requestBody.render())}")

            val attempt = Try {
              httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            }.toEither.left.map(_.toLLMError)

            attempt.flatMap(handleCompletionResponse)
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
  ): Result[Completion] = withMetrics("cohere", config.model) {
    validateNotClosed.flatMap { _ =>
      TransformationResult.transform(config.model, options, conversation.messages, dropUnsupported = true).flatMap {
        transformed =>
          val transformedConversation = conversation.copy(messages = transformed.messages)
          buildRequestBody(transformedConversation, transformed.options, stream = true).flatMap { requestBody =>
            val request = HttpRequest
              .newBuilder()
              .uri(URI.create(s"${config.baseUrl}/v1/chat"))
              .header("Content-Type", "application/json")
              .header("Authorization", s"Bearer ${config.apiKey}")
              .header("User-Agent", "llm4s/1.0")
              .timeout(Duration.ofMinutes(10))
              .POST(HttpRequest.BodyPublishers.ofString(requestBody.render()))
              .build()

            val responseResult = Try {
              httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            }.toEither.left.map(_.toLLMError)

            responseResult.flatMap { response =>
              if (response.statusCode() != 200) {
                val err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8)
                Try(response.body().close())
                handleStreamingError(response.statusCode(), err)
              } else {
                val accumulator = StreamingAccumulator.create()
                val parser      = SSEParser.createStreamingParser()
                val reader      = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))
                val streamId    = new StringBuilder(UUID.randomUUID().toString)

                val loopResult = Try {
                  try {
                    var line: String = null
                    while ({ line = reader.readLine(); line != null }) {
                      parser.addChunk(line + "\n")
                      while (parser.hasEvents)
                        parser.nextEvent().foreach { event =>
                          event.data.foreach { data =>
                            if (data != "[DONE]") {
                              val json = ujson.read(data)
                              handleStreamEvent(json, streamId, accumulator, onChunk)
                            }
                          }
                        }
                    }
                  } finally {
                    Try(reader.close())
                    Try(response.body().close())
                  }
                }.toEither.left.map(_.toLLMError)

                loopResult.flatMap(_ => accumulator.toCompletion.map(_.copy(model = config.model)))
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

  override def close(): Unit =
    if (closed.compareAndSet(false, true)) {
      logger.debug(s"Cohere client for model ${config.model} closed")
    }

  private def validateNotClosed: Result[Unit] =
    if (closed.get()) {
      Left(ConfigurationError(s"Cohere client for model ${config.model} is already closed"))
    } else {
      Right(())
    }

  private def buildRequestBody(
    conversation: Conversation,
    options: CompletionOptions,
    stream: Boolean
  ): Result[ujson.Obj] =
    splitConversation(conversation).map { case (preambleOpt, history, message) =>
      val body = ujson.Obj(
        "model"       -> config.model,
        "message"     -> message,
        "temperature" -> options.temperature,
        "p"           -> options.topP
      )

      if (history.nonEmpty) body("chat_history") = ujson.Arr.from(history)
      preambleOpt.foreach(preamble => body("preamble") = preamble)
      options.maxTokens.foreach(mt => body("max_tokens") = mt)
      if (stream) body("stream") = true
      body
    }

  private def splitConversation(
    conversation: Conversation
  ): Result[(Option[String], Seq[ujson.Value], String)] = {
    val messages       = conversation.messages
    val lastUserIndex  = messages.lastIndexWhere(_.isInstanceOf[UserMessage])
    val firstSystemIdx = messages.indexWhere(_.isInstanceOf[SystemMessage])

    if (lastUserIndex < 0) {
      Left(ValidationError("conversation", "Cohere chat requires at least one user message"))
    } else {
      val preambleOpt =
        if (firstSystemIdx >= 0) Some(messages(firstSystemIdx).asInstanceOf[SystemMessage].content) else None

      val history = scala.collection.mutable.ArrayBuffer[ujson.Value]()

      messages.zipWithIndex
        .takeWhile { case (_, idx) => idx <= lastUserIndex }
        .foreach {
          case (SystemMessage(content), idx) if idx != firstSystemIdx && content.trim.nonEmpty =>
            history += ujson.Obj("role" -> "SYSTEM", "message" -> content)
          case (UserMessage(content), idx) if idx != lastUserIndex =>
            history += ujson.Obj("role" -> "USER", "message" -> content)
          case (AssistantMessage(contentOpt, _), _) =>
            contentOpt.filter(_.nonEmpty).foreach { content =>
              history += ujson.Obj("role" -> "CHATBOT", "message" -> content)
            }
          case (_: ToolMessage, _) =>
          case _                   =>
        }

      val lastUserContent = messages(lastUserIndex).asInstanceOf[UserMessage].content
      Right((preambleOpt, history.toSeq, lastUserContent))
    }
  }

  private def handleCompletionResponse(response: HttpResponse[String]): Result[Completion] =
    response.statusCode() match {
      case 200 =>
        Try {
          val json = ujson.read(response.body())
          parseCompletion(json)
        }.toEither.left.map(_.toLLMError)
      case 400 =>
        Left(ValidationError("input", response.body()))
      case 401 =>
        Left(AuthenticationError("cohere", "Invalid API key"))
      case 429 =>
        Left(RateLimitError("cohere"))
      case status =>
        Left(ServiceError(status, "cohere", s"Cohere API error: ${Redaction.truncateForLog(response.body())}"))
    }

  private def handleStreamingError(status: Int, body: String): Result[Completion] =
    status match {
      case 400 => Left(ValidationError("input", body))
      case 401 => Left(AuthenticationError("cohere", "Invalid API key"))
      case 429 => Left(RateLimitError("cohere"))
      case _   => Left(ServiceError(status, "cohere", s"Cohere API error: ${Redaction.truncateForLog(body)}"))
    }

  private def handleStreamEvent(
    json: ujson.Value,
    streamId: StringBuilder,
    accumulator: StreamingAccumulator,
    onChunk: StreamedChunk => Unit
  ): Unit = {
    extractId(json).foreach { id =>
      if (id.nonEmpty) {
        streamId.clear()
        streamId.append(id)
      }
    }

    val eventType = json.obj.get("event_type").flatMap(_.strOpt).getOrElse("")
    eventType match {
      case "text-generation" | "text-generation-delta" | "text" | "generation" =>
        val textOpt = json.obj.get("text").flatMap(_.strOpt).orElse(json.obj.get("delta").flatMap(_.strOpt))
        textOpt.foreach { text =>
          val chunk = StreamedChunk(
            id = streamId.toString,
            content = Some(text),
            toolCall = None,
            finishReason = None
          )
          accumulator.addChunk(chunk)
          onChunk(chunk)
        }

      case "stream-end" | "stream_end" | "complete" =>
        val usageSource = json.obj.get("response").getOrElse(json)
        extractUsage(usageSource).foreach { usage =>
          accumulator.updateTokens(usage.promptTokens, usage.completionTokens)
        }
        val chunk = StreamedChunk(
          id = streamId.toString,
          content = None,
          toolCall = None,
          finishReason = Some("stop")
        )
        accumulator.addChunk(chunk)
        onChunk(chunk)

      case _ if eventType.isEmpty =>
        val textOpt = json.obj.get("text").flatMap(_.strOpt)
        textOpt.foreach { text =>
          val chunk = StreamedChunk(
            id = streamId.toString,
            content = Some(text),
            toolCall = None,
            finishReason = None
          )
          accumulator.addChunk(chunk)
          onChunk(chunk)
        }

      case _ =>
    }
  }

  private def parseCompletion(json: ujson.Value): Completion = {
    val id      = extractId(json).getOrElse(UUID.randomUUID().toString)
    val created = System.currentTimeMillis() / 1000
    val content = extractText(json)
    val usage   = extractUsage(json)

    Completion(
      id = id,
      created = created,
      content = content,
      model = config.model,
      message = AssistantMessage(content),
      usage = usage
    )
  }

  private def extractId(json: ujson.Value): Option[String] =
    json.obj.get("generation_id").flatMap(_.strOpt).orElse(json.obj.get("id").flatMap(_.strOpt))

  private def extractText(json: ujson.Value): String =
    json.obj
      .get("text")
      .flatMap(_.strOpt)
      .orElse(json.obj.get("message").flatMap(_.strOpt))
      .orElse(json.obj.get("content").flatMap(_.strOpt))
      .orElse(json.obj.get("response").flatMap(_.strOpt))
      .getOrElse("")

  private def extractUsage(json: ujson.Value): Option[TokenUsage] = {
    val metaCandidates = Seq(
      json.obj.get("meta"),
      json.obj.get("metadata"),
      json.obj.get("response").flatMap(_.obj.get("meta")),
      json.obj.get("response").flatMap(_.obj.get("metadata"))
    ).flatten

    val tokenSource = metaCandidates.flatMap { meta =>
      Seq(meta.obj.get("tokens"), meta.obj.get("billed_units"), meta.obj.get("usage")).flatten
    }.headOption

    tokenSource.flatMap { tokens =>
      val prompt     = readInt(tokens, Seq("input_tokens", "prompt_tokens", "inputTokens"))
      val completion = readInt(tokens, Seq("output_tokens", "completion_tokens", "outputTokens"))
      val total = readInt(tokens, Seq("total_tokens", "totalTokens"))
        .orElse(for {
          p <- prompt
          c <- completion
        } yield p + c)

      if (prompt.isDefined || completion.isDefined) {
        Some(TokenUsage(prompt.getOrElse(0), completion.getOrElse(0), total.getOrElse(0)))
      } else {
        None
      }
    }
  }

  private def readInt(value: ujson.Value, keys: Seq[String]): Option[Int] = {
    def asInt(v: ujson.Value): Option[Int] =
      v.numOpt.map(_.toInt).orElse(v.strOpt.flatMap(_.toIntOption))

    keys.iterator
      .flatMap(key => value.obj.get(key).flatMap(asInt))
      .toSeq
      .headOption
  }
}

object CohereClient {
  import org.llm4s.types.TryOps

  def apply(
    config: CohereConfig,
    metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
  ): Result[CohereClient] =
    Try(new CohereClient(config, metrics)).toResult
}
