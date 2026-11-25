package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.annotation.Stable
import org.llm4s.syntax.toLLMError
import org.llm4s.http.{ HttpFailures, Llm4sHttpClient }
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.VertexAIConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.ProviderResultOps.*
import org.llm4s.llmconnect.streaming._
import org.llm4s.model.{ AdjustmentLog, ModelRegistryService, TransformationResult, UnmappedOptions }
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
 * @param config         [[org.llm4s.llmconnect.config.VertexAIConfig VertexAIConfig]] with project, location, model, and credential path.
 * @param metrics        Receives per-call latency and token-usage events.
 * @param exchangeLogging Controls whether raw request/response bodies are recorded.
 * @param httpClient     HTTP client (injectable for testing).
 *
 * Build one with the companion's factory, or let `LLMConnect.getClient` build it from a named section: the
 * constructor is not public, so that an option can be added without breaking a caller, and the class is final.
 */
@Stable
final class VertexAIClient private[provider] (
  config: VertexAIConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  httpClient: Llm4sHttpClient = Llm4sHttpClient.FromConfig
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  // The injected client, or one routed by the config's proxy and TLS settings when none was given.
  private[provider] val http: Llm4sHttpClient = Llm4sHttpClient.resolve(httpClient, config.network)

  private val logger = LoggerFactory.getLogger(getClass)

  /** How long a non-streaming call may take: the section's `timeouts.request`, else VertexAIClient.DefaultRequestTimeout. */
  private[provider] def requestTimeout: FiniteDuration =
    config.timeouts.requestOr(VertexAIClient.DefaultRequestTimeout)

  /** How long a streamed call may take: the section's `timeouts.stream`, else VertexAIClient.DefaultStreamTimeout. */
  private[provider] def streamTimeout: FiniteDuration = config.timeouts.streamOr(VertexAIClient.DefaultStreamTimeout)

  private val authProvider = new VertexAIAuthProvider(
    config.credentialFilePath,
    http,
    wellKnownCredentialPath = config.wellKnownCredentialPath
  )

  protected def clientDescription: String = s"Vertex AI client for model ${config.model}"
  protected def provider: ProviderId      = ProviderId("vertexai")
  private def providerName: String        = provider.asString
  protected def modelName: ModelName      = config.model

  // thought signatures are replayed only to the provider and model id they were produced by (see ReplayOrigin)
  private val replayOrigin = ReplayOrigin(providerName, config.model.asString)

  // the references Vertex AI takes: Cloud Storage objects
  private val fileUri = GeminiParts.vertexFileUri

  // what the transformer dropped, and what `reasoning` and `thinking` were sent as (see GeminiThinking), goes
  // on every Completion; this warns once per model and option
  private lazy val adjustmentLog = new AdjustmentLog(providerName)

  // the model's registry capabilities, looked up once: the registry is a static snapshot
  private lazy val capabilities = registryService.lookup(config.model).toOption.map(_.capabilities)

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
      // a part Gemini cannot take fails the request here, before anything is sent
      .flatMap(transformed =>
        GeminiParts
          .check(providerName, transformed.messages, fileUri)
          .map(_ => transformed)
      )
      .flatMap { transformed =>
        val thinkingPlan = GeminiThinking.plan(config.model.asString, transformed.options, capabilities)
        val (tierHeaders, tierAdjustments) = GeminiServiceTier.vertex(transformed.options)
        val adjustments = transformed.adjustments ++ thinkingPlan.adjustments ++
          UnmappedOptions.promptCacheAutomatic("Vertex AI", transformed.options) ++ tierAdjustments ++
          UnmappedOptions.penalties(GeminiUnsent.Penalties, transformed.options)
        adjustmentLog.record(config.model.asString, adjustments)
        val transformedConversation = conversation.withMessages(transformed.messages)
        val requestBody = buildRequestBody(transformedConversation, transformed.options, thinkingPlan.thinkingConfig)
        val requestText = requestBody.render()
        val url         = modelUrl("generateContent")

        logger.debug(s"[VertexAI] Sending request to $url")
        // a plain slf4j logger builds the message first: redact only for a line that is logged (#2631)
        if (logger.isDebugEnabled)
          logger.debug(s"[VertexAI] Request body: ${org.llm4s.util.Redaction.redactForLogging(requestText)}")

        for {
          token <- authProvider.getAccessToken()
          headers = Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $token") ++ tierHeaders
          attempt <- http.post(url, headers, requestText, timeout = requestTimeout) match {
            case Left(error) =>
              recordExchange(startedAt, requestText, None, Left(error))
              Left(error)
            case Right(response) =>
              val result = Try {
                if (response.statusCode >= 200 && response.statusCode < 300)
                  parseCompletionResponse(response.body).map(c =>
                    c.withMessage(
                      ThinkingReplay.bindOrigin(replayOrigin, c.message)
                    ).withAdjustments(adjustments)
                  )
                else handleErrorResponse(response.statusCode, response.body, response.headers)
              }.toEither.left.map(e => e.toLLMError).flatten
              recordExchange(startedAt, requestText, Some(response.body), result)
              result
          }
        } yield attempt
      }
  }

  override def streamComplete(conversation: Conversation, options: CompletionOptions = CompletionOptions())(
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
      // a part Gemini cannot take fails the request here, before anything is sent
      .flatMap(transformed =>
        GeminiParts
          .check(providerName, transformed.messages, fileUri)
          .map(_ => transformed)
      )
      .flatMap { transformed =>
        val thinkingPlan = GeminiThinking.plan(config.model.asString, transformed.options, capabilities)
        val (tierHeaders, tierAdjustments) = GeminiServiceTier.vertex(transformed.options)
        val adjustments = transformed.adjustments ++ thinkingPlan.adjustments ++
          UnmappedOptions.promptCacheAutomatic("Vertex AI", transformed.options) ++ tierAdjustments ++
          UnmappedOptions.penalties(GeminiUnsent.Penalties, transformed.options)
        adjustmentLog.record(config.model.asString, adjustments)
        val transformedConversation = conversation.withMessages(transformed.messages)
        val requestBody = buildRequestBody(transformedConversation, transformed.options, thinkingPlan.thinkingConfig)
        val requestText = requestBody.render()
        val url         = s"${modelUrl("streamGenerateContent")}?alt=sse"

        logger.debug(s"[VertexAI] Starting stream to $url")

        for {
          token <- authProvider.getAccessToken()
          headers = Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $token") ++ tierHeaders
          result <- http.postStream(url, headers, requestText, timeout = streamTimeout) match {
            case Left(error) =>
              recordExchange(startedAt, requestText, None, Left(error))
              Left(error)
            case Right(response) if response.statusCode < 200 || response.statusCode >= 300 =>
              val err =
                Using(response.body)(in => new String(in.readAllBytes(), StandardCharsets.UTF_8)).getOrElse("")
              val errorResult = handleErrorResponse(response.statusCode, err, response.headers)
              recordExchange(startedAt, requestText, Some(err), errorResult)
              errorResult
            case Right(response) =>
              val accumulator = StreamingAccumulator(config.model)
              val messageId   = UUID.randomUUID().toString
              val rawStream   = StringBuilder()
              val signatures =
                scala.collection.mutable.ArrayBuffer.empty[ThinkingBlock] // thought signatures, in order
              val citations = scala.collection.mutable.ArrayBuffer.empty[Citation] // grounding sources, in order
              var textLength = 0     // characters of answer text streamed so far: where a text signature sits
              var callsSeen  = false // whether a function call has streamed: a STOP after one is ToolCalls
              var lastUsage  = Option.empty[TokenUsage]  // the last usage a chunk reported
              var servedTier = Option.empty[ServiceTier] // the last trafficType a chunk named (#2746)
              // a chunk nested too deeply, or a filter stop: the stream fails with it, unread past it
              var refused = Option.empty[org.llm4s.error.LLMError]
              var finish  = Option.empty[String] // the last finish reason a chunk stated

              Using(new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))) { reader =>
                Iterator.continually(reader.readLine()).takeWhile(l => l != null && refused.isEmpty).foreach { line =>
                  rawStream.append(line).append('\n')
                  val trimmed = line.trim
                  // SSE: a `data:` line carries a JSON chunk; blank lines, `:` comments (keep-alives) and the other
                  // fields are not data and are ignored
                  if (trimmed.startsWith("data:")) {
                    val jsonStr = trimmed.stripPrefix("data:").trim
                    if (jsonStr.nonEmpty) {
                      // a chunk's functionCall.args is the model's JSON, native in the envelope: one nested too
                      // deeply fails the stream as the same reply fails `complete`, never parsed and sent back
                      // (#1562); a data line that is not JSON at all fails the stream too (RFC 0009, open question 5)
                      BoundedJson.read(jsonStr) match {
                        case Right(json) if GeminiStreamEnd.streamError(json, providerName).isDefined =>
                          // an error after the 200 status ends the stream with it (RFC 0009, rule 2)
                          refused = GeminiStreamEnd.streamError(json, providerName)
                        case Right(json) =>
                          GeminiBlocked.filtered(json, provider, textLength) match {
                            case Some(filtered) if filtered.usage.isEmpty =>
                              // the usage reported on an earlier chunk, when the filter's chunk states none
                              refused = Some(filtered.withUsage(lastUsage))
                            case Some(filtered) =>
                              // a filter stop ends the stream with its reason, as the same reply fails `complete`;
                              // the text already delivered stays delivered
                              refused = Some(filtered)
                            case None =>
                              GeminiBlocked.finishReason(json).foreach(reason => finish = Some(reason))
                              parseStreamChunk(json, messageId, textLength, callsSeen).foreach {
                                case (parsed, chunks) =>
                                  textLength += parsed.text.length
                                  callsSeen = callsSeen || parsed.calls.nonEmpty
                                  signatures ++= parsed.signatures
                                  chunks.foreach { chunk =>
                                    accumulator.addChunk(ToolCallRules.wholeCall(chunk))
                                    onChunk(chunk)
                                  }
                              }
                              // grounding metadata usually arrives with the last chunk; collect it from any chunk
                              Try(json("candidates").arr.headOption).toOption.flatten
                                .foreach(candidate => citations ++= GeminiGrounding.citations(candidate))
                              // token usage, thinking and cached tokens included, when this chunk reports it
                              GeminiUsage.fromResponse(json).foreach { usage =>
                                lastUsage = Some(usage)
                                accumulator.updateUsage(usage)
                              }
                              GeminiServiceTier.trafficType(json).foreach(tier => servedTier = Some(tier))
                          }
                        case Left(tooDeep @ BoundedJson.TooDeep()) =>
                          logger.warn(
                            s"[VertexAI] Stream refused: a chunk is nested more than ${BoundedJson.MaxDepth} levels deep"
                          )
                          refused = Some(tooDeep)
                        case Left(unparsed) =>
                          logger.warn("[VertexAI] Stream refused: a data line is not JSON")
                          refused = Some(GeminiStreamEnd.unreadable(unparsed.message, providerName))
                      }
                    }
                  }
                }
              }.toEither.left
                .map(HttpFailures.streamReadError(_, url, streamTimeout))
                .flatMap(_ => refused.toLeft(()))
                // a stream whose candidate never stated a finishReason was cut off (RFC 0009, rule 1)
                .filterOrElse(_ => finish.isDefined, GeminiStreamEnd.unfinished(url))
                .flatMap(_ =>
                  accumulator.toCompletion.map { c =>
                    val cost = c.usage.flatMap(u => CostEstimator.estimate(config.model, u))
                    // the accumulator holds the streamed thought summary as unsigned text; the signatures go beside it
                    val message = c.message.withThinking(c.message.thinking ++ signatures.toSeq)
                    val completion = c
                      .withEstimatedCost(cost)
                      .withCitations(citations.distinct.toList)
                      .withMessage(
                        ThinkingReplay.bindOrigin(replayOrigin, message)
                      )
                      .withAdjustments(adjustments)
                      .withServedTier(servedTier)
                    completion
                  }
                )
                // an abnormal finish with nothing produced fails, decided from the whole stream as `complete` does
                .flatMap(c => GeminiBlocked.malformedCall(finish, "Vertex AI").toLeft(c))
                .flatMap(c => GeminiBlocked.unanswered(finish, c, "Vertex AI").toLeft(c))
                .map { completion =>
                  recordExchange(startedAt, requestText, Some(rawStream.result()), Right(completion))
                  completion
                }
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

  override def contextWindow: Int     = config.contextWindow
  override def reserveCompletion: Int = config.reserveCompletion

  /**
   * Gets the configured publisher model, `GET <base>/publishers/google/models/<model>` (Vertex AI's
   * `publishers.models.get`), with the OAuth2 token a completion carries. `Right(())` means credentials were found
   * and exchanged, the regional endpoint answered, took the token and has the model. A refused token is an
   * `AuthenticationError` and an unknown model a `ServiceError` 404. The project is not checked: the request names
   * none, so a project the credentials may not call is found only by a completion.
   */
  override def validate(): Result[Unit] = validateNotClosed.flatMap { _ =>
    authProvider.getAccessToken().flatMap { token =>
      http
        .get(
          s"${config.computedBaseUrl}/publishers/google/models/${config.model}",
          Map("Authorization" -> s"Bearer $token"),
          timeout = requestTimeout.min(VertexAIClient.ValidationTimeout)
        )
        .flatMap { response =>
          if (response.statusCode >= 200 && response.statusCode < 300) Right(())
          else handleErrorResponse(response.statusCode, response.body, response.headers)
        }
    }
  }

  private def buildRequestBody(
    conversation: Conversation,
    options: CompletionOptions,
    thinkingConfig: Option[ujson.Obj]
  ): ujson.Value = {
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

      case um: UserMessage =>
        // checked before the body is built: GeminiParts covers every part
        contents += ujson.Obj(
          "role" -> "user",
          "parts" -> ujson.Arr.from(
            PartEncoding
              .withoutBlankText(um.parts)
              .map(GeminiParts.user(fileUri))
          )
        )

      case am: AssistantMessage =>
        // Tool call IDs map to function names so the following ToolMessages can be keyed by name
        am.toolCalls.foreach(tc => toolCallIdToName(tc.id.asString) = tc.name)
        // a functionCall part sent with Gemini's own id needs that id echoed on its functionResponse; an empty
        // id is "no id", as GeminiThoughtSignatures.parse and matchesCall read it, even though the signed part
        // goes back verbatim with it
        // text, then function calls, each part carrying the thought signature Gemini gave it (if it is still valid)
        val parts = GeminiThoughtSignatures.parts(provider, am)
        parts.foreach { p =>
          p.objOpt
            .flatMap(_.get("functionCall"))
            .flatMap(_.objOpt)
            .flatMap(_.get("id"))
            .flatMap(_.strOpt)
            .filter(_.nonEmpty)
            .foreach(callIdsSentWithId += _)
        }
        if (parts.nonEmpty && (am.toolCalls.nonEmpty || am.text.isDefined))
          contents += ujson.Obj("role" -> "model", "parts" -> ujson.Arr(parts: _*))

      case tm: ToolMessage =>
        val toolCallId   = tm.toolCallId
        val functionName = toolCallIdToName.getOrElse(toolCallId.asString, toolCallId.asString)
        contents += ujson.Obj(
          "role" -> "user",
          "parts" -> ujson.Arr(
            ujson.Obj(
              "functionResponse" -> {
                val response = ujson.Obj(
                  "name"     -> functionName,
                  "response" -> ujson.Obj("result" -> tm.content)
                )
                // a populated functionCall.id must come back on the matching functionResponse
                if (callIdsSentWithId.contains(toolCallId.asString)) response("id") = toolCallId.asString
                response
              }
            )
          )
        )
    }

    // the sampling options are sent only when the caller set them: unset, the model's own defaults apply
    val generationConfig = ujson.Obj()
    options.temperature.foreach(t => generationConfig("temperature") = t)
    options.topP.foreach(p => generationConfig("topP") = p)
    options.maxTokens.foreach(mt => generationConfig("maxOutputTokens") = mt)
    thinkingConfig.foreach(tc => generationConfig("thinkingConfig") = tc)

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

    // Function declarations, and the Google Search grounding tool when the section enables it
    val functions = Option.when(options.tools.nonEmpty)(
      ujson.Obj("functionDeclarations" -> ujson.Arr(options.tools.map(convertToolToVertexFormat): _*))
    )
    val search = Option.when(config.googleSearch)(ujson.Obj("googleSearch" -> ujson.Obj()))
    val tools  = functions.toSeq ++ search
    if (tools.nonEmpty) request("tools") = ujson.Arr(tools: _*)

    request
  }

  private[provider] def convertToolToVertexFormat(tool: ToolFunction[_]): ujson.Value = {
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
      GeminiBlocked
        .filtered(json, provider)
        .toLeft(())
        .flatMap(_ => parseCandidate(json))
        .flatMap(c => GeminiBlocked.malformedCall(GeminiBlocked.finishReason(json), "Vertex AI").toLeft(c))
        .flatMap(c => GeminiBlocked.unanswered(GeminiBlocked.finishReason(json), c, "Vertex AI").toLeft(c))
        // RFC 0009, Part 3: a call whose args are not an object, or in a reply MAX_TOKENS cut, is never run
        .flatMap(ToolCallRules.checked)
    }

  private def parseCandidate(json: ujson.Value): Result[Completion] =
    Try {
      val candidates = json("candidates").arr

      if (candidates.isEmpty) {
        Left(org.llm4s.error.ValidationError("response", "No candidates in Vertex AI response"))
      } else {
        val candidate = candidates.head
        val parts     = GeminiThoughtSignatures.partsOf(candidate) // none when the model wrote nothing

        // Answer text, function calls (Vertex AI doesn't provide tool call IDs: each gets a generated one), the
        // thought summary if one was requested, and the thought signatures, split by part
        val parsed      = GeminiThoughtSignatures.parse(provider, parts, 0, () => UUID.randomUUID().toString)
        val textContent = parsed.text
        val toolCalls   = parsed.calls

        val usageOpt = GeminiUsage.fromResponse(json)

        // the signatures are sealed thinking: ThinkingReplay binds them to the request in `complete`
        val thinking =
          Option.when(parsed.thought.nonEmpty)(ThinkingBlock.Text(parsed.thought)).toSeq ++ parsed.signatures
        val message = AssistantMessage(
          text = if (textContent.nonEmpty) Some(textContent) else None,
          toolCalls = toolCalls,
          thinking = thinking
        )
        val cost = usageOpt.flatMap(u => CostEstimator.estimate(config.model, u))

        Right(
          Completion(
            id = UUID.randomUUID().toString,
            model = config.model,
            created = Instant.now(),
            message = message,
            usage = usageOpt,
            estimatedCost = cost,
            citations = GeminiGrounding.citations(candidate),
            finishReason = GeminiBlocked.mapped(GeminiBlocked.finishReason(json), toolCalls.nonEmpty),
            servedTier = GeminiServiceTier.trafficType(json)
          )
        )
      }
    }.toEither.left.map(e => e.toLLMError).flatten

  /**
   * Parse a streaming chunk into the parts it held and the [[org.llm4s.llmconnect.model.StreamedChunk StreamedChunk]]s they make: one per function call
   * (a chunk holds a single call), or one when there is none. `textOffset` is the number of answer-text
   * characters streamed before this chunk, so a text signature records where it sits.
   */
  private def parseStreamChunk(
    json: ujson.Value,
    messageId: String,
    textOffset: Int,
    callsBefore: Boolean
  ): Option[(GeminiThoughtSignatures.Parsed, Seq[StreamedChunk])] =
    Try {
      val candidates = json("candidates").arr
      if (candidates.nonEmpty) {
        val candidate = candidates.head
        val parts     = GeminiThoughtSignatures.partsOf(candidate) // none: the chunk still ends the stream
        val parsed =
          GeminiThoughtSignatures.parse(provider, parts, textOffset, () => UUID.randomUUID().toString)
        // the finish reason, ToolCalls for a STOP after a function call in this chunk or an earlier one
        val finishReason = Try(candidate("finishReason").str).toOption
          .map(raw => GeminiBlocked.mapped(Some(raw), callsBefore || parsed.calls.nonEmpty))

        Some((parsed, GeminiThoughtSignatures.chunks(parsed, messageId, finishReason)))
      } else None
    }.toOption.flatten

  private def handleErrorResponse(
    statusCode: Int,
    body: String,
    headers: Map[String, Seq[String]]
  ): Result[Nothing] = {
    logger.error(s"[VertexAI] Error response: $statusCode")
    Left(
      HttpErrorMapper.naming(HttpErrorMapper.toError(statusCode, body, providerName, headers), config.model.asString)
    )
  }

  private def recordExchange(
    startedAt: Instant,
    requestBody: String,
    responseBody: Option[String],
    result: Result[?]
  ): Unit =
    ProviderExchangeRecorder.record(
      exchangeLogging,
      ProviderExchangeCall(
        provider = provider,
        startedAt = startedAt,
        requestBody = requestBody,
        model = Some(config.model),
        responseBody = responseBody
      ),
      result
    )

  // A client the caller supplied is the caller's to close (#2341).
  override protected def releaseResources(): Unit = Llm4sHttpClient.closeIfOwned(httpClient, http)
}

object VertexAIClient {

  /** The timeout of a non-streaming call when the section sets no `timeouts.request`: two minutes. */
  val DefaultRequestTimeout: FiniteDuration = 120.seconds

  /** The timeout of a streamed call when the section sets no `timeouts.stream`: ten minutes. */
  val DefaultStreamTimeout: FiniteDuration = 10.minutes

  /** The longest `validate()` waits for its one request: ten seconds. */
  val ValidationTimeout: FiniteDuration = 10.seconds
  import org.llm4s.syntax.toResult

  /**
   * A [[VertexAIClient]] for `config`, or why one cannot be built: a config its provider refuses, or anything the
   * client's construction fails on, is a `Left`, never an exception.
   * `options.httpClient`, when set, carries every request and stays the caller's to close.
   *
   * @param config  the provider's configuration.
   * @param options the metrics collector, exchange logging and HTTP client of the client; none of them by default.
   */
  def apply(config: VertexAIConfig, options: LlmClientOptions = LlmClientOptions())(using
    ModelRegistryService
  ): Result[VertexAIClient] =
    Try(
      new VertexAIClient(
        config,
        options.metrics,
        options.exchangeLogging,
        Llm4sHttpClient.suppliedOrFromConfig(options.httpClient)
      )
    ).toResult
}
