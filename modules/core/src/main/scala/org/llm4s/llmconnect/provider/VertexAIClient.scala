// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordFinally
package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, ConfigurationError }
import org.llm4s.error.ThrowableOps._
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.VertexAIConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.ProviderResultOps._
import org.llm4s.llmconnect.streaming._
import org.llm4s.model.{ ModelRegistryService, TransformationResult }
import org.llm4s.toolapi.ToolFunction
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
 * Calls the Vertex AI REST API (Gemini-on-Vertex and Claude-on-Vertex) using
 * [[org.llm4s.http.Llm4sHttpClient]].
 *
 * == Authentication ==
 *
 * Vertex AI uses bearer-token authentication. The access token must be obtained
 * externally (e.g. via Application Default Credentials or a service account key)
 * and supplied in [[VertexAIConfig.accessToken]]. An empty access token results
 * in a [[org.llm4s.error.ConfigurationError]] on the first request.
 *
 * == Regional endpoints ==
 *
 * The endpoint URL includes the GCP region. For example, `us-central1` and
 * `europe-west1` produce different base URLs. Supply a custom `baseUrl` in
 * [[VertexAIConfig]] to override the default regional endpoint.
 *
 * == Response format ==
 *
 * Gemini-on-Vertex returns the same JSON structure as the public Gemini API
 * (candidates array with content parts). Claude-on-Vertex returns Anthropic's
 * messages format (content blocks with type and text fields).
 *
 * == Streaming ==
 *
 * Streaming uses the same SSE format as the public Gemini API.
 *
 * @param config         [[VertexAIConfig]] with project, location, model, and access token.
 * @param metrics        Receives per-call latency and token-usage events.
 * @param exchangeLogging Controls provider exchange recording.
 * @param httpClient     HTTP client used for all requests; injectable for testing.
 */
class VertexAIClient(
  config: VertexAIConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  private[provider] val httpClient: Llm4sHttpClient = Llm4sHttpClient.create(),
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  private val logger = LoggerFactory.getLogger(getClass)

  protected def clientDescription: String = s"VertexAI client for model ${config.model}"
  protected def providerName: String      = "vertex"
  protected def modelName: String         = config.model

  /** True when the model is a Claude model served via Anthropic on Vertex. */
  private def isClaudeModel: Boolean = config.model.toLowerCase.contains("claude")

  /**
   * Builds the Vertex AI endpoint URL for a non-streaming request.
   * Gemini-on-Vertex uses `generateContent`; Claude-on-Vertex uses `rawPredict`.
   */
  private def endpointUrl: String = {
    val base = config.baseUrl.stripSuffix("/")
    if (isClaudeModel) {
      s"$base/projects/${config.project}/locations/${config.location}/publishers/anthropic/models/${config.model}:rawPredict"
    } else {
      s"$base/projects/${config.project}/locations/${config.location}/publishers/google/models/${config.model}:generateContent"
    }
  }

  /**
   * Builds the Vertex AI endpoint URL for a streaming request.
   */
  private def streamingEndpointUrl: String = {
    val base = config.baseUrl.stripSuffix("/")
    if (isClaudeModel) {
      s"$base/projects/${config.project}/locations/${config.location}/publishers/anthropic/models/${config.model}:streamRawPredict"
    } else {
      s"$base/projects/${config.project}/locations/${config.location}/publishers/google/models/${config.model}:streamGenerateContent?alt=sse"
    }
  }

  private def authHeaders: Map[String, String] = Map(
    "Content-Type"  -> "application/json",
    "Authorization" -> s"Bearer ${config.accessToken}",
  )

  override def complete(
    conversation: Conversation,
    options: CompletionOptions,
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    if (config.accessToken.trim.isEmpty) {
      Left(ConfigurationError("VertexAI accessToken is required but was empty"))
    } else TransformationResult
      .transform(
        config.model,
        options,
        conversation.messages,
        dropUnsupported = true,
        org.llm4s.model.RequestTransformer.default(registryService),
      )
      .flatMap { transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)
        val requestBody =
          if (isClaudeModel) buildClaudeRequestBody(transformedConversation, transformed.options)
          else buildGeminiRequestBody(transformedConversation, transformed.options)
        val requestText = requestBody.render()
        val url         = endpointUrl

        logger.debug(s"[VertexAI] Sending request to $url")

        val attempt = Try {
          val response = httpClient.post(url, authHeaders, requestText, timeout = 120000)
          if (response.statusCode >= 200 && response.statusCode < 300) {
            val completionResult =
              if (isClaudeModel) parseClaudeCompletionResponse(response.body)
              else parseGeminiCompletionResponse(response.body)
            recordExchange(startedAt, requestText, Some(response.body), completionResult)
            completionResult
          } else {
            val errorResult = handleErrorResponse(response.statusCode, response.body)
            recordExchange(startedAt, requestText, Some(response.body), errorResult)
            errorResult
          }
        }.toEither.left
          .map(e => e.toLLMError)
          .flatten

        attempt
      }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit,
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    if (config.accessToken.trim.isEmpty) {
      Left(ConfigurationError("VertexAI accessToken is required but was empty"))
    } else
      TransformationResult
        .transform(
          config.model,
          options,
          conversation.messages,
          dropUnsupported = true,
          org.llm4s.model.RequestTransformer.default(registryService),
        )
        .flatMap { transformed =>
          val transformedConversation = conversation.copy(messages = transformed.messages)
          val requestBody =
            if (isClaudeModel) buildClaudeRequestBody(transformedConversation, transformed.options)
            else buildGeminiRequestBody(transformedConversation, transformed.options)
          val requestText = requestBody.render()
          val url         = streamingEndpointUrl

          logger.debug(s"[VertexAI] Starting stream to $url")

          val response = httpClient.postStream(url, authHeaders, requestText, timeout = 600000)

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
            val rawStream   = new StringBuilder()

            Try {
              try {
                var line: String = null
                while ({ line = reader.readLine(); line != null }) {
                  rawStream.append(line).append('\n')
                  val trimmed = line.trim
                  if (trimmed.startsWith("data: ")) {
                    val jsonStr = trimmed.stripPrefix("data: ").trim
                    if (jsonStr.nonEmpty) {
                      Try(ujson.read(jsonStr)).foreach { json =>
                        parseGeminiStreamChunk(json, messageId).foreach { chunk =>
                          accumulator.addChunk(chunk)
                          onChunk(chunk)
                        }
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
                  Left(error),
                )
              )
          }
        }
  }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  /**
   * Builds the Gemini-on-Vertex API request body.
   *
   * Uses the same format as the public Gemini API (contents array,
   * systemInstruction, generationConfig).
   */
  private def buildGeminiRequestBody(
    conversation: Conversation,
    options: CompletionOptions,
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
          "parts" -> ujson.Arr(ujson.Obj("text" -> content)),
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
                "args" -> tc.arguments,
              )
            )
          }
          contents += ujson.Obj("role" -> "model", "parts" -> ujson.Arr(parts.toSeq: _*))
        } else {
          contentOpt.foreach { content =>
            contents += ujson.Obj(
              "role"  -> "model",
              "parts" -> ujson.Arr(ujson.Obj("text" -> content)),
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
                "response" -> ujson.Obj("result" -> content),
              )
            )
          ),
        )
    }

    val generationConfig = ujson.Obj(
      "temperature" -> options.temperature,
      "topP"        -> options.topP,
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
      "generationConfig" -> generationConfig,
    )

    systemInstr.foreach { sysContent =>
      request("systemInstruction") = ujson.Obj(
        "parts" -> ujson.Arr(ujson.Obj("text" -> sysContent))
      )
    }

    if (options.tools.nonEmpty) {
      val functionDeclarations = options.tools.map(convertToolToGeminiFormat)
      request("tools") = ujson.Arr(
        ujson.Obj("functionDeclarations" -> ujson.Arr(functionDeclarations: _*))
      )
    }

    request
  }

  /**
   * Builds the Claude-on-Vertex (Anthropic Messages API) request body.
   *
   * Claude served via Vertex AI uses the same JSON request format as the
   * direct Anthropic API, minus the API key header.
   */
  private def buildClaudeRequestBody(
    conversation: Conversation,
    options: CompletionOptions,
  ): ujson.Value = {
    val messages    = scala.collection.mutable.ArrayBuffer[ujson.Value]()
    var systemInstr = Option.empty[String]

    conversation.messages.foreach {
      case SystemMessage(content) =>
        systemInstr = Some(content)
      case UserMessage(content) =>
        messages += ujson.Obj("role" -> "user", "content" -> content)
      case AssistantMessage(contentOpt, _) =>
        contentOpt.foreach { c =>
          messages += ujson.Obj("role" -> "assistant", "content" -> c)
        }
      case ToolMessage(content, toolCallId) =>
        messages += ujson.Obj(
          "role"    -> "user",
          "content" -> s"[Tool result for $toolCallId]: $content",
        )
    }

    val request = ujson.Obj(
      "model"      -> config.model,
      "max_tokens" -> options.maxTokens.getOrElse(2048),
      "messages"   -> ujson.Arr(messages.toSeq: _*),
    )

    systemInstr.foreach(s => request("system") = s)

    request
  }

  /** Convert a tool to Gemini's function declaration format. */
  private[provider] def convertToolToGeminiFormat(tool: ToolFunction[_, _]): ujson.Value = {
    val schema = ujson.read(tool.schema.toJsonSchema(false).render())
    schema.obj.remove("strict")
    schema.obj.remove("additionalProperties")
    stripAdditionalProperties(schema)
    ujson.Obj(
      "name"        -> tool.name,
      "description" -> tool.description,
      "parameters"  -> schema,
    )
  }

  /** Recursively strip `additionalProperties` from a JSON schema. */
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

  /** Parse a Gemini-on-Vertex completion response. */
  private def parseGeminiCompletionResponse(responseText: String): Result[Completion] =
    Try {
      val json       = ujson.read(responseText)
      val candidates = json("candidates").arr

      if (candidates.isEmpty) {
        Left(org.llm4s.error.ValidationError("response", "No candidates in Vertex AI Gemini response"))
      } else {
        val candidate = candidates.head
        val content   = candidate("content")
        val parts     = content("parts").arr

        val textContent = parts
          .filter(p => p.obj.contains("text"))
          .map(_("text").str)
          .mkString

        val toolCalls = parts
          .filter(p => p.obj.contains("functionCall"))
          .map { p =>
            val fc = p("functionCall")
            ToolCall(
              id = UUID.randomUUID().toString,
              name = fc("name").str,
              arguments = fc("args"),
            )
          }
          .toSeq

        val usageOpt = Try {
          val usage = json("usageMetadata")
          TokenUsage(
            promptTokens = usage("promptTokenCount").num.toInt,
            completionTokens = usage("candidatesTokenCount").num.toInt,
            totalTokens = usage("totalTokenCount").num.toInt,
          )
        }.toOption

        val message = AssistantMessage(
          contentOpt = if (textContent.nonEmpty) Some(textContent) else None,
          toolCalls = toolCalls,
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
            estimatedCost = cost,
          )
        )
      }
    }.toEither.left.map(e => e.toLLMError).flatten

  /** Parse a Claude-on-Vertex (Anthropic messages format) completion response. */
  private def parseClaudeCompletionResponse(responseText: String): Result[Completion] =
    Try {
      val json    = ujson.read(responseText)
      val content = json("content").arr

      val textContent = content
        .filter(b => b.obj.get("type").flatMap(_.strOpt).contains("text"))
        .map(_("text").str)
        .mkString

      val usageOpt = Try {
        val usage = json("usage")
        val input  = usage("input_tokens").num.toInt
        val output = usage("output_tokens").num.toInt
        TokenUsage(promptTokens = input, completionTokens = output, totalTokens = input + output)
      }.toOption

      val message = AssistantMessage(
        contentOpt = if (textContent.nonEmpty) Some(textContent) else None,
        toolCalls = Seq.empty,
      )
      val id   = Try(json("id").str).getOrElse(UUID.randomUUID().toString)
      val cost = usageOpt.flatMap(u => CostEstimator.estimate(config.model, u))

      Right(
        Completion(
          id = id,
          content = textContent,
          model = config.model,
          toolCalls = List.empty,
          created = System.currentTimeMillis() / 1000,
          message = message,
          usage = usageOpt,
          estimatedCost = cost,
        )
      )
    }.toEither.left.map(e => e.toLLMError).flatten

  /** Parse a streaming chunk from Vertex AI (Gemini format). */
  private def parseGeminiStreamChunk(json: ujson.Value, messageId: String): Option[StreamedChunk] =
    Try {
      val candidates = json("candidates").arr
      if (candidates.nonEmpty) {
        val candidate = candidates.head
        val content   = candidate("content")
        val parts     = content("parts").arr

        val textContent = parts
          .filter(p => p.obj.contains("text"))
          .map(_("text").str)
          .mkString

        val finishReason = Try(candidate("finishReason").str).toOption

        val toolCallOpt = parts
          .filter(p => p.obj.contains("functionCall"))
          .headOption
          .map { p =>
            val fc = p("functionCall")
            ToolCall(
              id = UUID.randomUUID().toString,
              name = fc("name").str,
              arguments = fc("args"),
            )
          }

        Some(
          StreamedChunk(
            id = messageId,
            content = if (textContent.nonEmpty) Some(textContent) else None,
            toolCall = toolCallOpt,
            finishReason = finishReason,
          )
        )
      } else {
        None
      }
    }.toOption.flatten

  private def handleErrorResponse(statusCode: Int, body: String): Result[Nothing] = {
    logger.error(s"[VertexAI] Error response: $statusCode")
    val details = HttpErrorMapper.extractErrorDetails(body, statusCode, providerName)
    if (statusCode == 401 || statusCode == 403 || isAdcAuthFailure(details)) {
      Left(AuthenticationError(providerName, details))
    } else {
      HttpErrorMapper.mapHttpError(statusCode, body, providerName)
    }
  }

  private def isAdcAuthFailure(details: String): Boolean = {
    val lower = details.toLowerCase
    lower.contains("unauthenticated") || lower.contains("invalid credentials") ||
    lower.contains("permission denied") || lower.contains("access token")
  }

  private def recordExchange(
    startedAt: Instant,
    requestBody: String,
    responseBody: Option[String],
    result: Result[?],
  ): Unit =
    ProviderExchangeRecorder.record(
      exchangeLogging = exchangeLogging,
      provider = providerName,
      model = Some(config.model),
      startedAt = startedAt,
      requestBody = requestBody,
      responseBody = responseBody,
      result = result,
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

  def apply(config: VertexAIConfig, metrics: org.llm4s.metrics.MetricsCollector)(using
    ModelRegistryService
  ): Result[VertexAIClient] =
    Try(new VertexAIClient(config, metrics)).toResult

  def apply(
    config: VertexAIConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging,
  )(using ModelRegistryService): Result[VertexAIClient] =
    Try(new VertexAIClient(config, metrics, exchangeLogging)).toResult

  /** Test seam — injects a custom HTTP client without going through Try-wrapping. */
  private[provider] def forTest(
    config: VertexAIConfig,
    httpClient: Llm4sHttpClient,
  )(using ModelRegistryService): VertexAIClient =
    new VertexAIClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, httpClient)
}
