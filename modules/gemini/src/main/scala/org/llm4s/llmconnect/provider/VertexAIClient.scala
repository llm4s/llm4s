package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.error.ThrowableOps._
import org.llm4s.http.{ HttpFailures, Llm4sHttpClient }
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.VertexAIConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.ProviderResultOps.*
import org.llm4s.llmconnect.streaming._
import org.llm4s.model.{ ModelRegistryService, TransformationResult }
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result
import org.llm4s.util.BoundedJson
import org.slf4j.LoggerFactory

import java.io.{ BufferedReader, InputStreamReader }
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import scala.util.{ Try, Using }

/**
 * [[LLMClient]] implementation for Google Cloud Vertex AI.
 *
 * Calls the Vertex AI REST API (`aiplatform.googleapis.com`) using the same
 * Gemini-compatible JSON format as [[GeminiClient]], but with:
 *  - A region-scoped endpoint: `https://{location}-aiplatform.googleapis.com/v1/projects/{project}/...`
 *  - OAuth2 bearer-token auth via [[VertexAIAuthProvider]] (ADC) instead of an API-key query param.
 *
 * == Authentication ==
 *
 * Tokens are obtained and cached by [[VertexAIAuthProvider]], which supports
 * `authorized_user` refresh-token credentials (from `gcloud auth application-default login`),
 * `service_account` JWT credentials, and the GCE/GKE metadata server (Workload Identity).
 * Set `GOOGLE_ACCESS_TOKEN` as an escape hatch for testing.
 *
 * == Message format ==
 *
 * Identical to [[GeminiClient]]:
 *  - Roles are `"user"` and `"model"`.
 *  - System messages go into `systemInstruction`.
 *  - Tool results are sent as `functionResponse` parts keyed by function name.
 *  - Synthetic UUIDs are generated for tool-call IDs (Vertex AI does not return them).
 *
 * @param config         [[VertexAIConfig]] with project, location, model, and credential path.
 * @param metrics        Receives per-call latency and token-usage events.
 * @param exchangeLogging Controls whether raw request/response bodies are recorded.
 * @param httpClient     HTTP client (injectable for testing).
 */
@Stable
class VertexAIClient(
  config: VertexAIConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  private[provider] val httpClient: Llm4sHttpClient = Llm4sHttpClient.create()
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  private val logger = LoggerFactory.getLogger(getClass)

  /** How long a non-streaming call may take: the section's `timeouts.request`, else VertexAIClient.DefaultRequestTimeout. */
  protected[provider] def requestTimeout: FiniteDuration =
    config.timeouts.requestOr(VertexAIClient.DefaultRequestTimeout)

  /** How long a streamed call may take: the section's `timeouts.stream`, else VertexAIClient.DefaultStreamTimeout. */
  protected[provider] def streamTimeout: FiniteDuration = config.timeouts.streamOr(VertexAIClient.DefaultStreamTimeout)

  private val authProvider = new VertexAIAuthProvider(config.credentialFilePath, httpClient)

  protected def clientDescription: String = s"Vertex AI client for model ${config.model}"
  protected def providerName: String      = "vertexai"
  protected def modelName: String         = config.model

  // thought signatures are replayed only to the provider and model id they were produced by (see ReplayOrigin)
  private val replayOrigin = ReplayOrigin(providerName, config.model)

  private def modelUrl(suffix: String): String = {
    val base = config.computedBaseUrl
    s"$base/projects/${config.projectId}/locations/${config.location}/publishers/google/models/${config.model}:$suffix"
  }

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
        org.llm4s.model.RequestTransformer.default(registryService),
        dropUnsupported = true
      )
      .flatMap { transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)
        val requestBody             = buildRequestBody(transformedConversation, transformed.options)
        val requestText             = requestBody.render()
        val url                     = modelUrl("generateContent")

        logger.debug(s"[VertexAI] Sending request to $url")
        logger.debug(s"[VertexAI] Request body: $requestText")

        for {
          token <- authProvider.getAccessToken()
          headers = Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $token")
          attempt <- httpClient.post(url, headers, requestText, timeout = requestTimeout) match {
            case Left(error) =>
              recordExchange(startedAt, requestText, None, Left(error))
              Left(error)
            case Right(response) =>
              val result = Try {
                if (response.statusCode >= 200 && response.statusCode < 300)
                  parseCompletionResponse(response.body).map(c =>
                    c.withMessage(
                      ThinkingReplay.bindOrigin(replayOrigin, c.message)
                    )
                  )
                else handleErrorResponse(response.statusCode, response.body, response.headers)
              }.toEither.left.map(e => e.toLLMError).flatten
              recordExchange(startedAt, requestText, Some(response.body), result)
              result
          }
        } yield attempt
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
        org.llm4s.model.RequestTransformer.default(registryService),
        dropUnsupported = true
      )
      .flatMap { transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)
        val requestBody             = buildRequestBody(transformedConversation, transformed.options)
        val requestText             = requestBody.render()
        val url                     = s"${modelUrl("streamGenerateContent")}?alt=sse"

        logger.debug(s"[VertexAI] Starting stream to $url")

        for {
          token <- authProvider.getAccessToken()
          headers = Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $token")
          result <- httpClient.postStream(url, headers, requestText, timeout = streamTimeout) match {
            case Left(error) =>
              recordExchange(startedAt, requestText, None, Left(error))
              Left(error)
            case Right(response) if response.statusCode < 200 || response.statusCode >= 300 =>
              val err = Using(response.body)(in => new String(in.readAllBytes(), StandardCharsets.UTF_8)).getOrElse("")
              val errorResult = handleErrorResponse(response.statusCode, err, response.headers)
              recordExchange(startedAt, requestText, Some(err), errorResult)
              errorResult
            case Right(response) =>
              val accumulator = StreamingAccumulator.create()
              val messageId   = UUID.randomUUID().toString
              val rawStream   = StringBuilder()
              val signatures = scala.collection.mutable.ArrayBuffer.empty[ThinkingBlock] // thought signatures, in order
              var textLength = 0 // characters of answer text streamed so far: where a text signature sits
              // a chunk nested too deeply: the stream fails with it, unread
              var refused = Option.empty[org.llm4s.error.LLMError]

              Using(new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))) { reader =>
                Iterator.continually(reader.readLine()).takeWhile(l => l != null && refused.isEmpty).foreach { line =>
                  rawStream.append(line).append('\n')
                  val trimmed = line.trim
                  if (trimmed.startsWith("data: ")) {
                    val jsonStr = trimmed.stripPrefix("data: ").trim
                    if (jsonStr.nonEmpty) {
                      // a chunk's functionCall.args is the model's JSON, native in the envelope: one nested too
                      // deeply fails the stream as the same reply fails `complete`, never parsed and sent back
                      // (#1562); a chunk that is not JSON at all is skipped, as it always was
                      BoundedJson.read(jsonStr) match {
                        case Right(json) =>
                          parseStreamChunk(json, messageId, textLength).foreach { case (parsed, chunks) =>
                            textLength += parsed.text.length
                            signatures ++= parsed.signatures
                            chunks.foreach { chunk =>
                              accumulator.addChunk(chunk)
                              onChunk(chunk)
                            }
                          }
                          for {
                            usage      <- Try(json("usageMetadata")).toOption
                            prompt     <- Try(usage("promptTokenCount").num.toInt).toOption
                            completion <- Try(usage("candidatesTokenCount").num.toInt).toOption
                          } accumulator.updateTokens(prompt, completion)
                        case Left(tooDeep @ BoundedJson.TooDeep()) =>
                          logger.warn(
                            s"[VertexAI] Stream refused: a chunk is nested more than ${BoundedJson.MaxDepth} levels deep"
                          )
                          refused = Some(tooDeep)
                        case Left(_) => () // unreadable chunk: skipped
                      }
                    }
                  }
                }
              }.toEither.left
                .map(HttpFailures.streamReadError(_, url, streamTimeout))
                .flatMap(_ => refused.toLeft(()))
                .flatMap(_ =>
                  accumulator.toCompletion.map { c =>
                    val cost = c.usage.flatMap(u => CostEstimator.estimate(config.model, u))
                    // the accumulator holds the streamed thought summary as unsigned text; the signatures go beside it
                    val message = c.message.withThinking(c.message.thinking ++ signatures.toSeq)
                    val completion = c
                      .withModel(config.model)
                      .withToolCalls(
                        c.message.toolCalls.toList
                      ) // the accumulator keeps streamed calls on the message only
                      .withEstimatedCost(cost)
                      .withMessage(
                        ThinkingReplay.bindOrigin(replayOrigin, message)
                      )
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
        } yield result
      }
  }

  override def getContextWindow(): Int     = config.contextWindow
  override def getReserveCompletion(): Int = config.reserveCompletion

  private def buildRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Value = {
    val contents         = scala.collection.mutable.ArrayBuffer[ujson.Value]()
    var systemInstr      = Option.empty[String]
    val toolCallIdToName = scala.collection.mutable.Map[String, String]()
    // The ids of function calls sent with Gemini's own `functionCall.id`: their results echo it back
    val callIdsSentWithId = scala.collection.mutable.Set[String]()

    // Thought signatures are sent back only to the provider and model that produced them; unlike
    // Anthropic's prefix rule, Google wants them PRESERVED when earlier history is pruned or edited
    // (Gemini 3 answers 400 for a missing function-call signature), so the binding is origin-only
    // and a foreign or re-modelled signature is dropped here (see ThinkingReplay.bindOrigin)
    val messages = ThinkingReplay.replayableOrigin(replayOrigin, conversation.messages)

    messages.foreach {
      case SystemMessage(content) =>
        systemInstr = Some(content)

      case UserMessage(content) =>
        contents += ujson.Obj("role" -> "user", "parts" -> ujson.Arr(ujson.Obj("text" -> content)))

      case am: AssistantMessage =>
        // Tool call IDs map to function names so the following ToolMessages can be keyed by name
        am.toolCalls.foreach(tc => toolCallIdToName(tc.id) = tc.name)
        // a functionCall part sent with Gemini's own id needs that id echoed on its functionResponse; an empty
        // id is "no id", as GeminiThoughtSignatures.parse and matchesCall read it, even though the signed part
        // goes back verbatim with it
        // text, then function calls, each part carrying the thought signature Gemini gave it (if it is still valid)
        val parts = GeminiThoughtSignatures.parts(providerName, am)
        parts.foreach { p =>
          p.objOpt
            .flatMap(_.get("functionCall"))
            .flatMap(_.objOpt)
            .flatMap(_.get("id"))
            .flatMap(_.strOpt)
            .filter(_.nonEmpty)
            .foreach(callIdsSentWithId += _)
        }
        if (parts.nonEmpty && (am.toolCalls.nonEmpty || am.contentOpt.isDefined))
          contents += ujson.Obj("role" -> "model", "parts" -> ujson.Arr(parts: _*))

      case ToolMessage(content, toolCallId) =>
        val functionName = toolCallIdToName.getOrElse(toolCallId, toolCallId)
        contents += ujson.Obj(
          "role" -> "user",
          "parts" -> ujson.Arr(
            ujson.Obj(
              "functionResponse" -> {
                val response = ujson.Obj(
                  "name"     -> functionName,
                  "response" -> ujson.Obj("result" -> content)
                )
                // a populated functionCall.id must come back on the matching functionResponse
                if (callIdsSentWithId.contains(toolCallId)) response("id") = toolCallId
                response
              }
            )
          )
        )
    }

    val generationConfig = ujson.Obj("temperature" -> options.temperature, "topP" -> options.topP)
    options.maxTokens.foreach(mt => generationConfig("maxOutputTokens") = mt)

    options.responseFormat.foreach {
      case ResponseFormat.Json =>
        generationConfig("responseMimeType") = "application/json"
      case js: ResponseFormat.JsonSchema =>
        generationConfig("responseMimeType") = "application/json"
        generationConfig("responseSchema") = js.schema
    }

    val request = ujson.Obj("contents" -> ujson.Arr(contents.toSeq: _*), "generationConfig" -> generationConfig)

    systemInstr.foreach { sysContent =>
      request("systemInstruction") = ujson.Obj("parts" -> ujson.Arr(ujson.Obj("text" -> sysContent)))
    }

    if (options.tools.nonEmpty) {
      val functionDeclarations = options.tools.map(convertToolToVertexFormat)
      request("tools") = ujson.Arr(
        ujson.Obj("functionDeclarations" -> ujson.Arr(functionDeclarations: _*))
      )
    }

    request
  }

  private[provider] def convertToolToVertexFormat(tool: ToolFunction[_, _]): ujson.Value = {
    val schema = ujson.read(tool.schema.toJsonSchema(false).render())
    schema.obj.remove("strict")
    schema.obj.remove("additionalProperties")
    stripAdditionalProperties(schema)
    ujson.Obj("name" -> tool.name, "description" -> tool.description, "parameters" -> schema)
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

  // the envelope holds the model's functionCall.args as a native object, so this parse is the boundary it
  // crosses: a reply nested more than 512 levels deep is refused before it is parsed (#1562, as GeminiClient)
  private def parseCompletionResponse(responseText: String): Result[Completion] =
    BoundedJson.read(responseText).flatMap { json =>
      Try {
        val candidates = json("candidates").arr

        if (candidates.isEmpty) {
          Left(org.llm4s.error.ValidationError("response", "No candidates in Vertex AI response"))
        } else {
          val candidate = candidates.head
          val content   = candidate("content")
          val parts     = content("parts").arr

          // Answer text, function calls (Vertex AI doesn't provide tool call IDs: each gets a generated one), the
          // thought summary if one was requested, and the thought signatures, split by part
          val parsed = GeminiThoughtSignatures.parse(providerName, parts.toSeq, 0, () => UUID.randomUUID().toString)
          val textContent = parsed.text
          val toolCalls   = parsed.calls

          val usageOpt = Try {
            val usage = json("usageMetadata")
            TokenUsage(
              promptTokens = usage("promptTokenCount").num.toInt,
              completionTokens = usage("candidatesTokenCount").num.toInt,
              totalTokens = usage("totalTokenCount").num.toInt
            )
          }.toOption

          // the signatures are sealed thinking: ThinkingReplay binds them to the request in `complete`
          val thinking =
            Option.when(parsed.thought.nonEmpty)(ThinkingBlock.Text(parsed.thought)).toSeq ++ parsed.signatures
          val message = AssistantMessage(
            contentOpt = if (textContent.nonEmpty) Some(textContent) else None,
            toolCalls = toolCalls,
            thinking = thinking
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
    }

  /**
   * Parse a streaming chunk into the parts it held and the [[StreamedChunk]]s they make: one per function call
   * (a chunk holds a single call), or one when there is none. `textOffset` is the number of answer-text
   * characters streamed before this chunk, so a text signature records where it sits.
   */
  private def parseStreamChunk(
    json: ujson.Value,
    messageId: String,
    textOffset: Int
  ): Option[(GeminiThoughtSignatures.Parsed, Seq[StreamedChunk])] =
    Try {
      val candidates = json("candidates").arr
      if (candidates.nonEmpty) {
        val candidate = candidates.head
        val parts     = candidate("content")("parts").arr
        val parsed =
          GeminiThoughtSignatures.parse(providerName, parts.toSeq, textOffset, () => UUID.randomUUID().toString)
        val finishReason = Try(candidate("finishReason").str).toOption

        Some((parsed, GeminiThoughtSignatures.chunks(parsed, messageId, finishReason)))
      } else None
    }.toOption.flatten

  private def handleErrorResponse(
    statusCode: Int,
    body: String,
    headers: Map[String, Seq[String]]
  ): Result[Nothing] = {
    logger.error(s"[VertexAI] Error response: $statusCode")
    HttpErrorMapper.mapHttpError(statusCode, body, providerName, headers)
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

  /** The timeout of a non-streaming call when the section sets no `timeouts.request`: two minutes. */
  val DefaultRequestTimeout: FiniteDuration = 120.seconds

  /** The timeout of a streamed call when the section sets no `timeouts.stream`: ten minutes. */
  val DefaultStreamTimeout: FiniteDuration = 10.minutes
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
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[VertexAIClient] =
    Try(new VertexAIClient(config, metrics, exchangeLogging)).toResult
}
