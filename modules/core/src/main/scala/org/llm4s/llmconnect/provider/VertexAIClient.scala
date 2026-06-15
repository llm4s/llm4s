// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordFinally
package org.llm4s.llmconnect.provider

import org.llm4s.error.ThrowableOps._
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.VertexAIConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.ProviderResultOps.*
import org.llm4s.llmconnect.streaming._
import org.llm4s.model.{ ModelRegistryService, TransformationResult }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.io.{ BufferedReader, InputStreamReader }
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.util.Try

/**
 * [[LLMClient]] implementation for Google Vertex AI.
 *
 * Supports Gemini models and Claude-on-Vertex models via the Vertex AI REST API.
 * Authentication uses an OAuth2 Bearer token (Application Default Credentials or
 * service-account JSON); the token is passed as the `Authorization: Bearer <token>` header.
 *
 * == Endpoint routing ==
 *
 * For Gemini models the endpoint is:
 * `https://{location}-aiplatform.googleapis.com/v1/projects/{project}/locations/{location}/publishers/google/models/{model}:generateContent`
 *
 * For Claude-on-Vertex models (model name contains `"claude"`) the endpoint follows the
 * Anthropic-compatible Vertex proxy:
 * `https://{location}-aiplatform.googleapis.com/v1/projects/{project}/locations/{location}/publishers/anthropic/models/{model}:rawPredict`
 *
 * == Streaming ==
 *
 * SSE streaming uses the `:streamGenerateContent?alt=sse` suffix for Gemini models
 * and `:streamRawPredict` for Claude models.
 *
 * == Error mapping ==
 *
 * - HTTP 401 / 403 → [[org.llm4s.error.AuthenticationError]]
 * - HTTP 429       → [[org.llm4s.error.RateLimitError]]
 * - HTTP 400       → [[org.llm4s.error.ValidationError]]
 * - Other 4xx/5xx  → [[org.llm4s.error.ServiceError]]
 *
 * @param config         [[VertexAIConfig]] with GCP project, location, model, and access token.
 * @param metrics        Receives per-call latency and token-usage events.
 * @param exchangeLogging Controls provider exchange recording.
 * @param httpClient     Injected HTTP client; defaults to [[Llm4sHttpClient.create()]].
 */
class VertexAIClient(
  config: VertexAIConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  private[provider] val httpClient: Llm4sHttpClient = Llm4sHttpClient.create()
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  private val logger = LoggerFactory.getLogger(getClass)

  protected def clientDescription: String = s"VertexAI client for model ${config.model}"
  protected def providerName: String      = "vertex"
  protected def modelName: String         = config.model

  /** True when the model identifier refers to a Claude-on-Vertex model. */
  private[provider] def isClaudeModel: Boolean = config.model.toLowerCase.contains("claude")

  /** Base URL for Vertex AI endpoints, derived from project and location. */
  private[provider] def vertexBaseUrl: String =
    s"https://${config.location}-aiplatform.googleapis.com/v1/projects/${config.projectId}/locations/${config.location}"

  /**
   * Non-streaming completion URL.
   *
   *  - Gemini  → `.../publishers/google/models/{model}:generateContent`
   *  - Claude  → `.../publishers/anthropic/models/{model}:rawPredict`
   */
  private[provider] def completionUrl: String =
    if (isClaudeModel)
      s"$vertexBaseUrl/publishers/anthropic/models/${config.model}:rawPredict"
    else
      s"$vertexBaseUrl/publishers/google/models/${config.model}:generateContent"

  /**
   * Streaming URL.
   *
   *  - Gemini  → `.../publishers/google/models/{model}:streamGenerateContent?alt=sse`
   *  - Claude  → `.../publishers/anthropic/models/{model}:streamRawPredict`
   */
  private[provider] def streamingUrl: String =
    if (isClaudeModel)
      s"$vertexBaseUrl/publishers/anthropic/models/${config.model}:streamRawPredict"
    else
      s"$vertexBaseUrl/publishers/google/models/${config.model}:streamGenerateContent?alt=sse"

  private def authHeaders: Map[String, String] =
    Map(
      "Content-Type"  -> "application/json",
      "Authorization" -> s"Bearer ${config.accessToken}"
    )

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    TransformationResult
      .transform(
        config.model,
        options,
        conversation.messages,
        dropUnsupported = true,
        org.llm4s.model.RequestTransformer.default(registryService)
      )
      .flatMap { transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)
        val requestBody             = buildRequestBody(transformedConversation, transformed.options)
        val requestText             = requestBody.render()
        val url                     = completionUrl

        logger.debug(s"[VertexAI] Sending request to $url")

        val attempt = Try {
          val response = httpClient.post(url, authHeaders, requestText, timeout = 120000)
          if (response.statusCode >= 200 && response.statusCode < 300) {
            val result = parseCompletionResponse(response.body)
            recordExchange(startedAt, requestText, Some(response.body), result)
            result
          } else {
            val errorResult = handleErrorResponse(response.statusCode, response.body)
            recordExchange(startedAt, requestText, Some(response.body), errorResult)
            errorResult
          }
        }.toEither.left.map(e => e.toLLMError).flatten

        attempt
      }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    TransformationResult
      .transform(
        config.model,
        options,
        conversation.messages,
        dropUnsupported = true,
        org.llm4s.model.RequestTransformer.default(registryService)
      )
      .flatMap { transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)
        val requestBody             = buildRequestBody(transformedConversation, transformed.options)
        val requestText             = requestBody.render()
        val url                     = streamingUrl

        logger.debug(s"[VertexAI] Starting stream to $url")

        Try(httpClient.postStream(url, authHeaders, requestText, timeout = 600000)).toEither.left
          .map(_.toLLMError)
          .flatMap { response =>
            if (response.statusCode < 200 || response.statusCode >= 300) {
              val err = new String(response.body.readAllBytes(), StandardCharsets.UTF_8)
              response.body.close()
              val errorResult = handleErrorResponse(response.statusCode, err)
              recordExchange(startedAt, requestText, Some(err), errorResult)
              errorResult
            } else {
              val accumulator = StreamingAccumulator.create()
              val messageId   = UUID.randomUUID().toString
              val reader      = new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))
              val rawStream   = StringBuilder()

              Try {
                try {
                  var line: String = null
                  while ({ line = reader.readLine(); line != null }) {
                    rawStream.append(line).append('\n')
                    val trimmed = line.trim
                    if (trimmed.startsWith("data: ")) {
                      val jsonStr = trimmed.stripPrefix("data: ").trim
                      if (jsonStr.nonEmpty && jsonStr != "[DONE]") {
                        Try(ujson.read(jsonStr)).foreach { json =>
                          parseStreamChunk(json, messageId).foreach { chunk =>
                            accumulator.addChunk(chunk)
                            onChunk(chunk)
                          }
                          // Extract token usage from usageMetadata if present (Gemini format)
                          for {
                            usage      <- Try(json("usageMetadata")).toOption
                            prompt     <- Try(usage("promptTokenCount").num.toInt).toOption
                            completion <- Try(usage("candidatesTokenCount").num.toInt).toOption
                          } accumulator.updateTokens(prompt, completion)
                        }
                      }
                    }
                  }
                } finally {
                  Try(reader.close())
                  Try(response.body.close())
                }
              }.toEither.left
                .map(_.toLLMError)
                .flatMap(_ =>
                  accumulator.toCompletion.map { c =>
                    val cost       = c.usage.flatMap(u => CostEstimator.estimate(config.model, u))
                    val completion = c.copy(model = config.model, estimatedCost = cost)
                    recordExchange(startedAt, requestText, Some(rawStream.result()), Right(completion))
                    completion
                  }
                )
                .tapLeft(error =>
                  recordExchange(
                    startedAt,
                    requestText,
                    Option.when(rawStream.nonEmpty)(rawStream.result()),
                    Left(error)
                  )
                )
            }
          }
      }
  }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  /**
   * Builds the Vertex AI / Gemini API request body.
   *
   * Uses the same Gemini JSON format for both Gemini models and Claude-on-Vertex
   * (the Vertex Anthropic proxy accepts the same generateContent format).
   * SystemMessage is extracted into `systemInstruction`; all other messages go into `contents`.
   */
  private[provider] def buildRequestBody(
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
        contents += ujson.Obj(
          "role"  -> "user",
          "parts" -> ujson.Arr(ujson.Obj("text" -> content))
        )

      case AssistantMessage(contentOpt, toolCalls) =>
        if (toolCalls.nonEmpty) {
          val parts = scala.collection.mutable.ArrayBuffer[ujson.Value]()
          contentOpt.foreach(c => parts += ujson.Obj("text" -> c))
          toolCalls.foreach { tc =>
            toolCallIdToName(tc.id) = tc.name
            parts += ujson.Obj(
              "functionCall" -> ujson.Obj(
                "name" -> tc.name,
                "args" -> tc.arguments
              )
            )
          }
          contents += ujson.Obj("role" -> "model", "parts" -> ujson.Arr(parts.toSeq: _*))
        } else {
          contentOpt.foreach { content =>
            contents += ujson.Obj(
              "role"  -> "model",
              "parts" -> ujson.Arr(ujson.Obj("text" -> content))
            )
          }
        }

      case ToolMessage(content, toolCallId) =>
        val functionName = toolCallIdToName.getOrElse(toolCallId, toolCallId)
        contents += ujson.Obj(
          "role" -> "user",
          "parts" -> ujson.Arr(
            ujson.Obj(
              "functionResponse" -> ujson.Obj(
                "name"     -> functionName,
                "response" -> ujson.Obj("result" -> content)
              )
            )
          )
        )
    }

    val generationConfig = ujson.Obj(
      "temperature" -> options.temperature,
      "topP"        -> options.topP
    )
    options.maxTokens.foreach(mt => generationConfig("maxOutputTokens") = mt)

    options.responseFormat.foreach {
      case ResponseFormat.Json =>
        generationConfig("responseMimeType") = "application/json"
      case js: ResponseFormat.JsonSchema =>
        generationConfig("responseMimeType") = "application/json"
        generationConfig("responseSchema") = js.schema
    }

    val request = ujson.Obj(
      "contents"         -> ujson.Arr(contents.toSeq: _*),
      "generationConfig" -> generationConfig
    )

    systemInstr.foreach { sysContent =>
      request("systemInstruction") = ujson.Obj(
        "parts" -> ujson.Arr(ujson.Obj("text" -> sysContent))
      )
    }

    if (options.tools.nonEmpty) {
      val functionDeclarations = options.tools.map(convertToolToVertexFormat)
      request("tools") = ujson.Arr(
        ujson.Obj("functionDeclarations" -> ujson.Arr(functionDeclarations: _*))
      )
    }

    request
  }

  /** Convert a tool to Vertex AI / Gemini function declaration format. */
  private[provider] def convertToolToVertexFormat(
    tool: org.llm4s.toolapi.ToolFunction[_, _]
  ): ujson.Value = {
    val schema = ujson.read(tool.schema.toJsonSchema(false).render())
    schema.obj.remove("strict")
    schema.obj.remove("additionalProperties")
    stripAdditionalProperties(schema)
    ujson.Obj(
      "name"        -> tool.name,
      "description" -> tool.description,
      "parameters"  -> schema
    )
  }

  private[provider] def stripAdditionalProperties(json: ujson.Value): Unit =
    json match {
      case obj: ujson.Obj =>
        obj.value.remove("additionalProperties")
        obj.value.get("properties").foreach(props => props.obj.values.foreach(stripAdditionalProperties))
        obj.value.get("items").foreach(stripAdditionalProperties)
        Seq("anyOf", "oneOf", "allOf").foreach { key =>
          obj.value.get(key).foreach(arr => arr.arr.foreach(stripAdditionalProperties))
        }
      case _ => ()
    }

  private def parseCompletionResponse(responseText: String): Result[Completion] =
    Try {
      val json       = ujson.read(responseText)
      val candidates = json("candidates").arr

      if (candidates.isEmpty) {
        Left(org.llm4s.error.ValidationError("response", "No candidates in Vertex AI response"))
      } else {
        val candidate = candidates.head
        val content   = candidate("content")
        val parts     = content("parts").arr

        val textContent = parts.filter(p => p.obj.contains("text")).map(_("text").str).mkString

        val toolCalls = parts
          .filter(p => p.obj.contains("functionCall"))
          .map { p =>
            val fc = p("functionCall")
            ToolCall(
              id = UUID.randomUUID().toString,
              name = fc("name").str,
              arguments = fc("args")
            )
          }
          .toSeq

        val usageOpt = Try {
          val usage = json("usageMetadata")
          TokenUsage(
            promptTokens = usage("promptTokenCount").num.toInt,
            completionTokens = usage("candidatesTokenCount").num.toInt,
            totalTokens = usage("totalTokenCount").num.toInt
          )
        }.toOption

        val message = AssistantMessage(
          contentOpt = if (textContent.nonEmpty) Some(textContent) else None,
          toolCalls = toolCalls
        )
        val cost = usageOpt.flatMap(u => CostEstimator.estimate(config.model, u))

        Right(
          Completion(
            id = UUID.randomUUID().toString,
            content = textContent,
            model = config.model,
            toolCalls = toolCalls.toList,
            created = System.currentTimeMillis() / 1000,
            message = message,
            usage = usageOpt,
            estimatedCost = cost
          )
        )
      }
    }.toEither.left.map(e => e.toLLMError).flatten

  private def parseStreamChunk(json: ujson.Value, messageId: String): Option[StreamedChunk] =
    Try {
      val candidates = json("candidates").arr
      if (candidates.nonEmpty) {
        val candidate    = candidates.head
        val content      = candidate("content")
        val parts        = content("parts").arr
        val textContent  = parts.filter(p => p.obj.contains("text")).map(_("text").str).mkString
        val finishReason = Try(candidate("finishReason").str).toOption
        val toolCallOpt = parts
          .filter(p => p.obj.contains("functionCall"))
          .headOption
          .map { p =>
            val fc = p("functionCall")
            ToolCall(id = UUID.randomUUID().toString, name = fc("name").str, arguments = fc("args"))
          }
        Some(
          StreamedChunk(
            id = messageId,
            content = if (textContent.nonEmpty) Some(textContent) else None,
            toolCall = toolCallOpt,
            finishReason = finishReason
          )
        )
      } else {
        None
      }
    }.toOption.flatten

  private def handleErrorResponse(statusCode: Int, body: String): Result[Nothing] = {
    logger.error(s"[VertexAI] Error response: $statusCode")
    HttpErrorMapper.mapHttpError(statusCode, body, providerName)
  }

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

  override protected def releaseResources(): Unit =
    (httpClient: Any) match {
      case c: AutoCloseable => c.close()
      case _                => ()
    }
}

object VertexAIClient {
  import org.llm4s.types.TryOps

  def apply(config: VertexAIConfig)(using ModelRegistryService): Result[VertexAIClient] =
    Try(new VertexAIClient(config)).toResult

  def apply(
    config: VertexAIConfig,
    metrics: org.llm4s.metrics.MetricsCollector
  )(using ModelRegistryService): Result[VertexAIClient] =
    Try(new VertexAIClient(config, metrics)).toResult

  def apply(
    config: VertexAIConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[VertexAIClient] =
    Try(new VertexAIClient(config, metrics, exchangeLogging)).toResult

  /** Test seam: inject a custom [[Llm4sHttpClient]] without using the default. */
  private[provider] def forTest(
    config: VertexAIConfig,
    httpClient: Llm4sHttpClient
  )(using ModelRegistryService): VertexAIClient =
    new VertexAIClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, httpClient)
}
