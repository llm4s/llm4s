package org.llm4s.llmconnect.provider

import org.llm4s.util.Redaction
import org.llm4s.error.{ AuthenticationError, ConfigurationError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.error.ThrowableOps._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming._
import org.llm4s.model.TransformationResult
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result
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
 * LLMClient implementation for Google Gemini models.
 */
class GeminiClient(
  config: GeminiConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
) extends LLMClient
    with MetricsRecording {
  private val logger                = LoggerFactory.getLogger(getClass)
  private val httpClient            = HttpClient.newHttpClient()
  private val closed: AtomicBoolean = new AtomicBoolean(false)

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = withMetrics("gemini", config.model) {
    validateNotClosed.flatMap { _ =>
      TransformationResult.transform(config.model, options, conversation.messages, dropUnsupported = true).flatMap {
        transformed =>
          val transformedConversation = conversation.copy(messages = transformed.messages)
          val requestBody             = buildRequestBody(transformedConversation, transformed.options)
          val url                     = s"${config.baseUrl}/models/${config.model}:generateContent?key=${config.apiKey}"

          logger.debug(s"[Gemini] Sending request to ${config.baseUrl}/models/${config.model}:generateContent")
          logger.debug(s"[Gemini] Request body: ${Redaction.redactForLogging(requestBody.render())}")

          val request = HttpRequest
            .newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMinutes(2))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.render()))
            .build()

          val attempt = Try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
              parseCompletionResponse(response.body())
            } else {
              handleErrorResponse(response.statusCode(), response.body())
            }
          }.toEither.left
            .map(e => e.toLLMError)
            .flatten

          attempt
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
  ): Result[Completion] = withMetrics("gemini", config.model) {
    validateNotClosed.flatMap { _ =>
      TransformationResult.transform(config.model, options, conversation.messages, dropUnsupported = true).flatMap {
        transformed =>
          val transformedConversation = conversation.copy(messages = transformed.messages)
          val requestBody             = buildRequestBody(transformedConversation, transformed.options)
          val url = s"${config.baseUrl}/models/${config.model}:streamGenerateContent?key=${config.apiKey}&alt=sse"

          logger.debug(s"[Gemini] Starting stream to ${config.baseUrl}/models/${config.model}:streamGenerateContent")

          val request = HttpRequest
            .newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMinutes(10))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.render()))
            .build()

          val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())

          if (response.statusCode() < 200 || response.statusCode() >= 300) {
            val err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8)
            response.body().close()
            handleErrorResponse(response.statusCode(), err)
          } else {
            val accumulator = StreamingAccumulator.create()
            val messageId   = UUID.randomUUID().toString
            val reader      = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))

            val processEither = Try {
              var line: String = null
              while ({ line = reader.readLine(); line != null }) {
                val trimmed = line.trim
                if (trimmed.startsWith("data: ")) {
                  val jsonStr = trimmed.stripPrefix("data: ").trim
                  if (jsonStr.nonEmpty) {
                    Try(ujson.read(jsonStr)).foreach { json =>
                      parseStreamChunk(json, messageId).foreach { chunk =>
                        accumulator.addChunk(chunk)
                        onChunk(chunk)
                      }
                    }
                  }
                }
              }
            }.toEither

            Try(reader.close())
            Try(response.body().close())

            processEither.left
              .map(_.toLLMError)
              .flatMap(_ => accumulator.toCompletion.map(c => c.copy(model = config.model)))
          }
      }
    }
  }(
    extractUsage = _.usage,
    estimateCost = usage =>
      org.llm4s.model.ModelRegistry.lookup(config.model).toOption.flatMap { meta =>
        meta.pricing.estimateCost(usage.promptTokens.toInt, usage.completionTokens.toInt)
      }
  )

  override def getContextWindow(): Int = config.contextWindow
  override def getReserveCompletion(): Int = config.reserveCompletion

  private def buildRequestBody(
    conversation: Conversation,
    options: CompletionOptions
  ): ujson.Value = {
    val contents    = scala.collection.mutable.ArrayBuffer[ujson.Value]()
    var systemInstr = Option.empty[String]
    val toolCallIdToName = scala.collection.mutable.Map[String, String]()

    conversation.messages.foreach {
      case SystemMessage(content) =>
        systemInstr = Some(content)
      case UserMessage(content) =>
        contents += ujson.Obj("role" -> "user", "parts" -> ujson.Arr(ujson.Obj("text" -> content)))
      case AssistantMessage(contentOpt, toolCalls) =>
        if (toolCalls.nonEmpty) {
          val parts = scala.collection.mutable.ArrayBuffer[ujson.Value]()
          contentOpt.foreach(c => parts += ujson.Obj("text" -> c))
          toolCalls.foreach { tc =>
            toolCallIdToName(tc.id) = tc.name
            parts += ujson.Obj("functionCall" -> ujson.Obj("name" -> tc.name, "args" -> tc.arguments))
          }
          contents += ujson.Obj("role" -> "model", "parts" -> ujson.Arr(parts.toSeq: _*))
        } else {
          contentOpt.foreach { content =>
            contents += ujson.Obj("role" -> "model", "parts" -> ujson.Arr(ujson.Obj("text" -> content)))
          }
        }
      case ToolMessage(content, toolCallId) =>
        val functionName = toolCallIdToName.getOrElse(toolCallId, toolCallId)
        contents += ujson.Obj(
          "role" -> "user",
          "parts" -> ujson.Arr(ujson.Obj("functionResponse" -> ujson.Obj("name" -> functionName, "response" -> ujson.Obj("result" -> content))))
        )
    }

    val generationConfig = ujson.Obj("temperature" -> options.temperature, "topP" -> options.topP)
    options.maxTokens.foreach(mt => generationConfig("maxOutputTokens") = mt)

    val request = ujson.Obj("contents" -> ujson.Arr(contents.toSeq: _*), "generationConfig" -> generationConfig)

    systemInstr.foreach { sysContent =>
      request("systemInstruction") = ujson.Obj("parts" -> ujson.Arr(ujson.Obj("text" -> sysContent)))
    }

    if (options.tools.nonEmpty) {
      val functionDeclarations = options.tools.map(convertToolToGeminiFormat)
      request("tools") = ujson.Arr(ujson.Obj("functionDeclarations" -> ujson.Arr(functionDeclarations: _*)))
    }
    request
  }

  /**
   * Convert a tool to Gemini's function declaration format.
   * Gemini doesn't accept OpenAI-specific fields like 'strict' or 'additionalProperties' 
   * in schemas, so we strip them out to prevent 400 Bad Request errors.
   */
  private def convertToolToGeminiFormat(tool: ToolFunction[_, _]): ujson.Value = {
    // Generate base JSON schema without strict mode
    val schema = ujson.read(tool.schema.toJsonSchema(false).render())

    // Fix: Explicitly remove OpenAI-only fields to meet Gemini's contract
    schema.obj.remove("strict")
    schema.obj.remove("additionalProperties")

    // Recursively remove additionalProperties from all nested objects
    stripAdditionalProperties(schema)

    ujson.Obj(
      "name"        -> tool.name,
      "description" -> tool.description,
      "parameters"  -> schema
    )
  }

  private def stripAdditionalProperties(json: ujson.Value): Unit =
    json match {
      case obj: ujson.Obj =>
        obj.value.remove("additionalProperties")
        obj.value.get("properties").foreach(props => props.obj.values.foreach(stripAdditionalProperties))
        obj.value.get("items").foreach(stripAdditionalProperties)
        Seq("anyOf", "oneOf", "allOf").foreach { key =>
          obj.value.get(key).foreach(arr => arr.arr.foreach(stripAdditionalProperties))
        }
      case _ =>
    }

  private def parseCompletionResponse(responseText: String): Result[Completion] =
    Try {
      val json       = ujson.read(responseText)
      val candidates = json("candidates").arr
      if (candidates.isEmpty) {
        Left(ValidationError("response", "No candidates in Gemini response"))
      } else {
        val candidate = candidates.head
        val content   = candidate("content")
        val parts     = content("parts").arr
        val textContent = parts.filter(p => p.obj.contains("text")).map(_("text").str).mkString
        val toolCalls = parts.filter(p => p.obj.contains("functionCall")).map { p =>
          val fc = p("functionCall")
          ToolCall(id = UUID.randomUUID().toString, name = fc("name").str, arguments = fc("args"))
        }.toSeq

        val usageOpt = Try {
          val usage = json("usageMetadata")
          TokenUsage(
            promptTokens = usage("promptTokenCount").num.toInt,
            completionTokens = usage("candidatesTokenCount").num.toInt,
            totalTokens = usage("totalTokenCount").num.toInt
          )
        }.toOption

        Right(Completion(
          id = UUID.randomUUID().toString,
          content = textContent,
          model = config.model,
          toolCalls = toolCalls.toList,
          created = System.currentTimeMillis() / 1000,
          message = AssistantMessage(contentOpt = if (textContent.nonEmpty) Some(textContent) else None, toolCalls = toolCalls),
          usage = usageOpt
        ))
      }
    }.toEither.left.map(e => e.toLLMError).flatten

  private def parseStreamChunk(json: ujson.Value, messageId: String): Option[StreamedChunk] =
    Try {
      val candidates = json("candidates").arr
      if (candidates.nonEmpty) {
        val candidate = candidates.head
        val content   = candidate("content")
        val parts     = content("parts").arr
        val textContent = parts.filter(p => p.obj.contains("text")).map(_("text").str).mkString
        val finishReason = Try(candidate("finishReason").str).toOption
        val toolCallOpt = parts.filter(p => p.obj.contains("functionCall")).headOption.map { p =>
          val fc = p("functionCall")
          ToolCall(id = UUID.randomUUID().toString, name = fc("name").str, arguments = fc("args"))
        }
        Some(StreamedChunk(id = messageId, content = if (textContent.nonEmpty) Some(textContent) else None, toolCall = toolCallOpt, finishReason = finishReason))
      } else None
    }.toOption.flatten

  private def handleErrorResponse(statusCode: Int, body: String): Result[Nothing] = {
    logger.error(s"[Gemini] Error response: $statusCode")
    val errorMessage = Try(ujson.read(body)("error")("message").str).getOrElse(body)
    statusCode match {
      case 401 | 403 => Left(AuthenticationError("gemini", errorMessage))
      case 429        => Left(RateLimitError("gemini"))
      case 400        => Left(ValidationError("request", errorMessage))
      case _          => Left(ServiceError(statusCode, "gemini", s"Gemini API error: $errorMessage"))
    }
  }

  override def close(): Unit = if (closed.compareAndSet(false, true)) {}

  private def validateNotClosed: Result[Unit] =
    if (closed.get()) Left(ConfigurationError(s"Gemini client for model ${config.model} is already closed"))
    else Right(())
}

object GeminiClient {
  import org.llm4s.types.TryOps
  def apply(config: GeminiConfig): Result[GeminiClient] = Try(new GeminiClient(config)).toResult
  def apply(config: GeminiConfig, metrics: org.llm4s.metrics.MetricsCollector): Result[GeminiClient] = Try(new GeminiClient(config, metrics)).toResult
}