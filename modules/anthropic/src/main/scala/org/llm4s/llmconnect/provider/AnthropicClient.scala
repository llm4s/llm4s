package org.llm4s.llmconnect.provider
import org.llm4s.annotation.Stable

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.config.{
  AuthenticationConfig,
  AuthenticationType,
  IdentityTokenConfig,
  InMemoryProfileConfigProvider,
  ProfileConfig
}
import com.anthropic.core.{ JsonObject, ObjectMappers }
import com.anthropic.models.messages.{
  Message,
  MessageCreateParams,
  RawMessageStreamEvent,
  ThinkingConfigEnabled,
  Tool
}

import scala.collection.mutable
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.{ AnthropicConfig, ProviderConfig }
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.ProviderResultOps.*
import org.llm4s.llmconnect.streaming.*
import org.llm4s.model.{ ModelRegistryService, RequestTransformer, TransformationResult }
import org.llm4s.toolapi.{ ObjectSchema, ToolFunction }
import org.llm4s.types.Result
import org.llm4s.error.{ AuthenticationError, LLMError, RateLimitError, UnknownError, ValidationError }
import org.llm4s.util.Redaction
import org.llm4s.error.ThrowableOps.*

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }

/**
 * [[LLMClient]] implementation for Anthropic Claude models.
 *
 * Uses the official Anthropic Java SDK (`AnthropicOkHttpClient`) for all
 * API calls. SDK exceptions are mapped to the appropriate [[org.llm4s.error.LLMError]]
 * subtypes before being returned.
 *
 * == Message format adaptations ==
 *
 * The Anthropic Messages API differs from the OpenAI convention in several
 * ways that this client handles transparently:
 *
 *  - **Default system prompt**: if the conversation contains no
 *    `SystemMessage`, the client injects `"You are Claude, a helpful AI
 *    assistant."` automatically. Supply an explicit `SystemMessage` to
 *    override this.
 *
 *  - **Tool results as user messages**: the Anthropic API does not accept
 *    native tool-result messages in the same turn structure as OpenAI.
 *    `ToolMessage` values are therefore forwarded as user messages with
 *    the prefix `"[Tool result for <toolCallId>]: "`.
 *
 *  - **Assistant messages with tool calls are skipped**: when an
 *    `AssistantMessage` carries pending tool calls, it is not forwarded —
 *    Anthropic infers the assistant turn from the subsequent tool-result
 *    user messages.
 *
 *  - **Schema sanitisation**: OpenAI-specific fields (`strict`,
 *    `additionalProperties`) are stripped from tool schemas before sending,
 *    because Anthropic's API rejects them.
 *
 * == Extended thinking ==
 *
 * When `CompletionOptions.reasoning` is set, a `thinking` block is added
 * to the request. The token budget is clamped to `[1024, maxTokens - 1]`
 * to satisfy the Anthropic API constraint; the effective budget may
 * therefore differ from what was requested.
 *
 * `maxTokens` defaults to 2048 when not set in `CompletionOptions` because
 * the Anthropic API requires the field.
 *
 * @param config  `AnthropicConfig` carrying the API key, model name, and base URL.
 * @param metrics Receives per-call latency and token-usage events.
 *                Defaults to `MetricsCollector.noop`.
 */
@Stable
class AnthropicClient(
  config: AnthropicConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  // Store config for budget calculations
  private val providerConfig: ProviderConfig = config

  /** The configured credentials, which an error built from a remote reply must not repeat. */
  private val credentialSecrets: Seq[String] = AnthropicClient.credentialSecrets(config)

  // A config built with the constructor or `copy` skipped `fromValues`: `apply` refuses it as a
  // ConfigurationError, and the constructor here, so a plain-http baseUrl never receives the identity token.
  AnthropicConfig.validate(config).left.foreach(error => throw new IllegalArgumentException(error.message))

  // Initialize Anthropic client
  private val client = {
    val builder = AnthropicOkHttpClient.builder().baseUrl(config.baseUrl)
    config.workloadIdentity match {
      case None => builder.apiKey(config.apiKey)
      case Some(wi) =>
        val auth = AuthenticationConfig
          .builder()
          .`type`(AuthenticationType.OIDC_FEDERATION)
          .federationRuleId(wi.federationRuleId)
          .identityToken(IdentityTokenConfig.builder().source("file").path(wi.identityTokenFile.toString).build())
        wi.serviceAccountId.foreach(auth.serviceAccountId)
        val profile = ProfileConfig
          .builder()
          .authentication(auth.build())
          .baseUrl(config.baseUrl)
          .organizationId(wi.organizationId)
        wi.workspaceId.foreach(profile.workspaceId)
        builder.configurationProvider(InMemoryProfileConfigProvider.of(profile.build()))
    }
    builder.build()
  }

  protected def clientDescription: String = s"Anthropic client for model ${config.model}"
  protected def providerName: String      = "anthropic"
  protected def modelName: String         = config.model

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    // Transform options and messages for model-specific constraints
    TransformationResult
      .transform(
        config.model,
        options,
        conversation.messages,
        RequestTransformer.default(registryService),
        dropUnsupported = true
      )
      .flatMap { transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)

        // Create message parameters builder
        val paramsBuilder = MessageCreateParams
          .builder()
          .model(config.model)
        applySamplingParameters(paramsBuilder, transformed.options)

        // Add max tokens if specified
        // max tokens is required by the api
        val maxTokens = transformed.options.maxTokens.getOrElse(2048)
        paramsBuilder.maxTokens(maxTokens)

        // Add extended thinking configuration if requested
        // Minimum budget is 1024 tokens, must be less than max_tokens
        transformed.options.effectiveBudgetTokens.foreach { budgetTokens =>
          val effectiveBudget = clampBudgetTokens(budgetTokens, maxTokens)
          paramsBuilder.thinking(
            ThinkingConfigEnabled.builder().budgetTokens(effectiveBudget.toLong).build()
          )
        }

        // Add tools if specified
        if (transformed.options.tools.nonEmpty) {
          transformed.options.tools.foreach(tool => paramsBuilder.addTool(convertToolToAnthropicTool(tool)))
        }

        // Add messages from conversation
        addMessagesToParams(transformedConversation, paramsBuilder, transformed.options)

        // Build the parameters
        val messageParams = paramsBuilder.build()
        val requestBody   = serializeRequestBody(messageParams)

        val messageService = client.messages()
        // Make API call
        val attempt = Try(messageService.create(messageParams)).toEither.left
          .map(AnthropicClient.mapError(_, credentialSecrets))
        val result       = attempt.map(convertFromAnthropicResponse)
        val responseBody = attempt.toOption.map(serializeResponseBody)
        recordingExchange(startedAt, requestBody)(result)(responseBody)
      }
  }

  /*
curl https://api.anthropic.com/v1/messages \
     --header "x-api-key: $ANTHROPIC_API_KEY" \
     --header "anthropic-version: 2023-06-01" \
     --header "content-type: application/json" \
     --data \
'{
    "model": "claude-3-7-sonnet-20250219",
    "max_tokens": 1024,
    "tools": [{
        "name": "get_weather",
        "description": "Get the current weather in a given location",
        "input_schema": {
          "type":"object",
          "properties":{
            "location":{"type":"string","description":"City and country e.g. Bogotá, Colombia"},
            "units":{"type":"string","description":"Units the temperature will be returned in.","enum":["celsius","fahrenheit"]}
          },
          "additionalProperties": {}
        }
    }],
    "messages": [{"role": "user", "content": "What is the weather like in San Francisco?"}]
}'
   */
  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    // Transform options and messages for model-specific constraints
    TransformationResult
      .transform(
        config.model,
        options,
        conversation.messages,
        RequestTransformer.default(registryService),
        dropUnsupported = true
      )
      .flatMap { transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)

        // Build parameters
        val paramsBuilder = MessageCreateParams
          .builder()
          .model(config.model)
        applySamplingParameters(paramsBuilder, transformed.options)

        // Add max tokens if specified (required by the API)
        val maxTokens = transformed.options.maxTokens.getOrElse(2048)
        paramsBuilder.maxTokens(maxTokens)

        // Add extended thinking configuration if requested
        transformed.options.effectiveBudgetTokens.foreach { budgetTokens =>
          val effectiveBudget = clampBudgetTokens(budgetTokens, maxTokens)
          paramsBuilder.thinking(
            ThinkingConfigEnabled.builder().budgetTokens(effectiveBudget.toLong).build()
          )
        }

        // Add tools if specified
        if (transformed.options.tools.nonEmpty)
          transformed.options.tools.foreach(t => paramsBuilder.addTool(convertToolToAnthropicTool(t)))
        // Add messages from conversation
        addMessagesToParams(transformedConversation, paramsBuilder, transformed.options)
        // Build the parameters
        val messageParams = paramsBuilder.build()
        val requestBody   = serializeRequestBody(messageParams)

        // Create accumulator for building the final completion
        val accumulator                      = StreamingAccumulator.create()
        var currentMessageId: Option[String] = None
        val blockIndexToToolId               = mutable.Map.empty[Long, String]
        val rawStream                        = StringBuilder()

        // Process the stream
        val attempt = Try {
          val messageService = client.messages()
          Using.resource(messageService.createStreaming(messageParams)) { streamResponse =>
            import scala.jdk.StreamConverters._
            val stream: Iterator[RawMessageStreamEvent] = streamResponse.stream().toScala(Iterator)
            stream.foreach { event =>
              rawStream.append(serializeStreamEvent(event)).append('\n')
              // Process different event types using the event's accessor methods
              // Check for message start event
              val messageStartOpt = event.messageStart()
              if (messageStartOpt != null && messageStartOpt.isPresent) {
                val msgStart = messageStartOpt.get()
                currentMessageId = Some(msgStart.message().id())
              }

              // Check for content block delta event
              val contentDeltaOpt = event.contentBlockDelta()
              if (contentDeltaOpt != null && contentDeltaOpt.isPresent) {
                val contentDelta = contentDeltaOpt.get()
                val delta        = contentDelta.delta()

                // Handle text content delta
                Try(delta.text()).foreach { textOpt =>
                  if (textOpt != null && textOpt.isPresent) {
                    val textDelta = textOpt.get()
                    val text      = textDelta.text()
                    if (text != null && text.nonEmpty) {
                      val chunk = StreamedChunk(
                        id = currentMessageId.getOrElse(""),
                        content = Some(text),
                        toolCall = None,
                        finishReason = None
                      )
                      accumulator.addChunk(chunk)
                      onChunk(chunk)
                    }
                  }
                }

                // Handle thinking content delta
                Try(delta.thinking()).foreach { thinkingOpt =>
                  if (thinkingOpt != null && thinkingOpt.isPresent) {
                    val thinkingDelta = thinkingOpt.get()
                    val thinkingText  = thinkingDelta.thinking()
                    if (thinkingText != null && thinkingText.nonEmpty) {
                      val chunk = StreamedChunk(
                        id = currentMessageId.getOrElse(""),
                        content = None,
                        toolCall = None,
                        finishReason = None,
                        thinkingDelta = Some(thinkingText)
                      )
                      accumulator.addChunk(chunk)
                      onChunk(chunk)
                    }
                  }
                }

                // Handle input_json_delta for tool call arguments
                Try(delta.inputJson()).foreach { inputJsonOpt =>
                  if (inputJsonOpt != null && inputJsonOpt.isPresent) {
                    val fragment   = inputJsonOpt.get().partialJson()
                    val toolCallId = blockIndexToToolId.getOrElse(contentDelta.index(), "")
                    if (fragment != null && fragment.nonEmpty && toolCallId.nonEmpty) {
                      val chunk = StreamedChunk(
                        id = currentMessageId.getOrElse(""),
                        content = None,
                        toolCall = Some(ToolCall(id = toolCallId, name = "", arguments = ujson.Str(fragment))),
                        finishReason = None
                      )
                      accumulator.addChunk(chunk)
                      onChunk(chunk)
                    }
                  }
                }
              }

              val contentStartOpt = event.contentBlockStart()
              if (contentStartOpt != null && contentStartOpt.isPresent) {
                val contentStart = contentStartOpt.get()
                val block        = contentStart.contentBlock()
                if (block.isToolUse) {
                  val toolUse = block.asToolUse()
                  blockIndexToToolId(contentStart.index()) = toolUse.id()
                  val chunk = StreamedChunk(
                    id = currentMessageId.getOrElse(""),
                    content = None,
                    toolCall = Some(ToolCall(id = toolUse.id(), name = toolUse.name(), arguments = ujson.Obj())),
                    finishReason = None
                  )
                  accumulator.addChunk(chunk)
                  onChunk(chunk)
                }
              }

              val messageStopOpt = event.messageStop()
              if (messageStopOpt != null && messageStopOpt.isPresent) {
                val chunk = StreamedChunk(
                  id = currentMessageId.getOrElse(""),
                  content = None,
                  toolCall = None,
                  finishReason = Some("stop")
                )
                accumulator.addChunk(chunk)
                onChunk(chunk)
              }

              val messageDeltaOpt = event.messageDelta()
              if (messageDeltaOpt != null && messageDeltaOpt.isPresent) {
                val msgDelta = messageDeltaOpt.get()
                Try(msgDelta.usage()).foreach { usage =>
                  if (usage != null) {
                    val inputTokens = Option(usage.inputTokens()) match {
                      case Some(opt: java.util.Optional[_]) if opt.isPresent =>
                        Option(opt.get())
                          .collect { case n: java.lang.Number => n.intValue() }
                          .getOrElse(0)
                      case _ => 0
                    }
                    val outputTokens = Option(usage.outputTokens()).map(_.toInt).getOrElse(0)
                    if (inputTokens > 0 || outputTokens > 0) accumulator.updateTokens(inputTokens, outputTokens)
                  }
                }
              }
            }
          }
        }.toEither.left
          .map(AnthropicClient.mapError(_, credentialSecrets))

        // Return the accumulated completion
        val result = attempt.flatMap(_ =>
          accumulator.toCompletion.map { c =>
            val cost = c.usage.flatMap(u => CostEstimator.estimate(config.model, u))
            c.withModel(config.model).withEstimatedCost(cost)
          }
        )

        recordingExchange(startedAt, requestBody)(result)(
          successResponse = Some(rawStream.result()),
          failureResponse = Option.when(rawStream.nonEmpty)(rawStream.result())
        )
      }
  }

  override def getContextWindow(): Int = providerConfig.contextWindow

  override def getReserveCompletion(): Int = providerConfig.reserveCompletion

  /**
   * Clamps an extended-thinking budget to the range `[1024, maxTokens - 1]`.
   *
   * The Anthropic API requires `budgetTokens >= 1024` and `budgetTokens < maxTokens`.
   * Values outside this range are silently adjusted; callers should prefer
   * supplying valid budgets rather than relying on clamping.
   *
   * @param budgetTokens requested thinking-token budget; may be any non-negative value
   * @param maxTokens    effective `max_tokens` for the request; determines the upper bound
   * @return the clamped budget in `[1024, maxTokens - 1]`
   */
  private[provider] def clampBudgetTokens(budgetTokens: Int, maxTokens: Int): Int =
    math.max(1024, math.min(budgetTokens, maxTokens - 1))

  // Add messages from conversation to the parameters builder
  private[provider] def addMessagesToParams(
    conversation: Conversation,
    paramsBuilder: MessageCreateParams.Builder,
    options: CompletionOptions
  ): Unit = {
    // Track if we've seen a system message
    var hasSystemMessage = false

    // Process messages in order
    conversation.messages.foreach {
      case SystemMessage(content) =>
        paramsBuilder.system(appendJsonInstruction(content, options))
        hasSystemMessage = true

      case UserMessage(content) =>
        paramsBuilder.addUserMessage(content)

      case AssistantMessage(contentOpt, toolCalls) =>
        // For AssistantMessages with tool calls, we skip sending them back to Anthropic
        // The tool results will be sent as ToolMessages, which Anthropic converts to user messages
        if (toolCalls.isEmpty) {
          // Only send AssistantMessages without tool calls
          paramsBuilder.addAssistantMessage(contentOpt.getOrElse(""))
        }
      // If there are tool calls, we don't send this message - Anthropic will infer it from the tool results

      case ToolMessage(content, toolCallId) =>
        // Anthropic API expects tool results to be sent in user messages
        // We prefix the content to make it clear this is a tool result
        paramsBuilder.addUserMessage(s"[Tool result for $toolCallId]: $content")
    }

    // Add a default system message if none was provided; the JSON instruction is appended to it as
    // well so structured-output requests keep the instruction when the caller supplied no system prompt
    if (!hasSystemMessage) {
      val base = "You are Claude, a helpful AI assistant."
      paramsBuilder.system(appendJsonInstruction(base, options))
    }
  }

  private[provider] def appendJsonInstruction(system: String, options: CompletionOptions): String =
    options.responseFormat match {
      case None => system
      case Some(ResponseFormat.Json) =>
        s"$system\n\nYou MUST respond with valid JSON only. No prose, no markdown, no explanation — only the raw JSON object."
      case Some(js: ResponseFormat.JsonSchema) =>
        val schemaStr = js.schema.render()
        s"$system\n\nYou MUST respond with valid JSON only, conforming exactly to this schema:\n$schemaStr\nNo prose, no markdown, no explanation — only the raw JSON object."
    }

  /**
   * Convert a ToolFunction to Anthropic's Tool format.
   * Strips OpenAI-specific fields like 'strict' and 'additionalProperties' from the schema
   * to maintain compatibility with the Anthropic API.
   */
  private[provider] def convertToolToAnthropicTool(toolFunction: ToolFunction[_, _]): Tool = {
    val objectSchema = toolFunction.schema.asInstanceOf[ObjectSchema[_]]
    // Generate raw schema without 'strict' mode
    val jsonSchemaStr = objectSchema.toJsonSchema(false).render()

    // Parse the JSON and sanitize the schema
    val jsonNode = ujson.read(jsonSchemaStr)

    // Fix: Remove OpenAI-only top-level fields
    jsonNode.obj.remove("strict")
    jsonNode.obj.remove("additionalProperties")

    // Recursively strip additionalProperties from nested parts
    stripAdditionalProperties(jsonNode)

    val sanitizedSchemaStr = jsonNode.render()
    val jsonSchema: JsonObject =
      ObjectMappers.jsonMapper().readValue(sanitizedSchemaStr, classOf[JsonObject])
    val jsonSchemaMap = jsonSchema.values()

    val inputSchemaBuilder = Tool.InputSchema.builder()
    val propertiesValue    = jsonSchemaMap.get("properties")
    if (propertiesValue != null) {
      val propertiesObj = ObjectMappers
        .jsonMapper()
        .readValue(
          ObjectMappers.jsonMapper().writeValueAsString(propertiesValue),
          classOf[Tool.InputSchema.Properties]
        )
      inputSchemaBuilder.properties(propertiesObj)
    }

    Tool
      .builder()
      .name(toolFunction.name)
      .description(toolFunction.description)
      .inputSchema(inputSchemaBuilder.build().validate())
      .build()
  }

  /**
   * Recursively strip 'additionalProperties' from all levels of a JSON schema.
   * This ensures compatibility with providers that don't support OpenAI-specific schema extensions.
   */
  private[provider] def stripAdditionalProperties(json: ujson.Value): Unit =
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

  // Convert Anthropic response to our model
  private def convertFromAnthropicResponse(response: Message): Completion = {
    val contentBlocks = response.content().asScala.toList

    // Extract text content
    val textContent: Option[String] = {
      val texts = contentBlocks.filter(_.isText).map(_.asText().text())
      if (texts.nonEmpty) Some(texts.mkString) else None
    }

    // Extract thinking content (for extended thinking responses)
    val thinkingContent: Option[String] = {
      val thinkingTexts = contentBlocks.filter(_.isThinking).map(_.asThinking().thinking())
      if (thinkingTexts.nonEmpty) Some(thinkingTexts.mkString) else None
    }

    // Extract tool calls if present
    val toolCalls = extractToolCalls(response)
    val message   = AssistantMessage(contentOpt = textContent, toolCalls = toolCalls)

    // Extract token usage, including thinking tokens if available
    val usage = response.usage()

    val cachedTokens: Option[Int] =
      Option(usage.cacheReadInputTokens())
        .filter(_.isPresent)
        .map(_.get().toInt)

    val cacheCreationTokens: Option[Int] =
      Option(usage.cacheCreationInputTokens())
        .filter(_.isPresent)
        .map(_.get().toInt)

    val tokenUsage = TokenUsage(
      promptTokens = usage.inputTokens().toInt,
      completionTokens = usage.outputTokens().toInt,
      totalTokens = (usage.inputTokens() + usage.outputTokens()).toInt,
      cachedTokens = cachedTokens,
      cacheCreationTokens = cacheCreationTokens
    )

    // Estimate cost using CostEstimator
    val cost = CostEstimator.estimate(config.model, tokenUsage)

    // Create completion
    Completion(
      id = response.id(),
      content = message.content,
      model = response.model().asString(),
      toolCalls = toolCalls.toList,
      created = System.currentTimeMillis() / 1000, // Use current time as created timestamp
      message = message,
      usage = Some(tokenUsage),
      thinking = thinkingContent,
      estimatedCost = cost
    )
  }

  // Extract tool calls from Anthropic response
  private def extractToolCalls(response: Message): Seq[ToolCall] = {
    val toolCalls = response.content().asScala.toList.filter(_.isToolUse).map { cb =>
      val toolUse   = cb.asToolUse()
      val toolId    = toolUse.id()
      val toolName  = toolUse.name()
      val rawParams = toolUse._input()
      val arguments = ujson.read(ObjectMappers.jsonMapper().writeValueAsString(rawParams))

      ToolCall(
        id = toolId,
        name = toolName,
        arguments = arguments
      )
    }

    toolCalls
  }

  private def serializeStreamEvent(event: RawMessageStreamEvent): String =
    ObjectMappers.jsonMapper().writeValueAsString(event)

  // anthropic-java 2.42 deprecated `temperature`: models released after Claude Opus 4.6
  // reject any value other than 1.0 with a 400 error. We omit the parameter entirely for
  // those models so that even a default `CompletionOptions()` (temperature 0.7) works;
  // models that still accept it receive the configured value. The deprecation warning is
  // unavoidable on the supported-model path, hence the suppression.
  //
  // Anthropic rejects some requests that specify both temperature and top_p, so we prefer
  // temperature as the single sampling control for our default path.
  @scala.annotation.nowarn("cat=deprecation")
  private def applySamplingParameters(
    builder: MessageCreateParams.Builder,
    options: CompletionOptions
  ): Unit =
    if (modelSupportsTemperature)
      builder.temperature(options.temperature.doubleValue())

  /**
   * Whether `config.model` accepts the (deprecated) `temperature` sampling parameter.
   *
   * A model-registry capability override (`disallowedParams` containing `"temperature"`)
   * takes precedence; otherwise we fall back to a name-based check for the Anthropic models
   * that dropped sampling support (Claude Opus 4.7 and newer).
   */
  private def modelSupportsTemperature: Boolean = {
    val disallowedByRegistry =
      registryService
        .lookup(config.model)
        .toOption
        .flatMap(_.capabilities.disallowedParams)
        .exists(_.contains("temperature"))
    !disallowedByRegistry && !AnthropicClient.rejectsSamplingTemperature(config.model)
  }

  override protected def releaseResources(): Unit =
    client.close()

  private[provider] def serializeRequestBody(params: MessageCreateParams): String =
    ObjectMappers.jsonMapper().writeValueAsString(params._body())

  private[provider] def serializeResponseBody(message: Message): String =
    ObjectMappers.jsonMapper().writeValueAsString(message)

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

  /**
   * Records the exchange and returns `result` unchanged, for call sites where
   * success and failure share the same response body (the body is captured
   * from the SDK call before conversion, so it's available for either outcome).
   */
  private def recordingExchange(
    startedAt: Instant,
    requestBody: String
  )(result: Result[Completion])(responseBody: => Option[String]): Result[Completion] =
    result
      .tapRight(c => recordExchange(startedAt, requestBody, responseBody, Right(c)))
      .tapLeft(e => recordExchange(startedAt, requestBody, responseBody, Left(e)))

  /**
   * Records the exchange and returns `result` unchanged, for call sites where
   * the response body differs between outcomes - streaming only accumulates a
   * body worth recording once at least one chunk has arrived.
   */
  private def recordingExchange(
    startedAt: Instant,
    requestBody: String
  )(
    result: Result[Completion]
  )(successResponse: => Option[String], failureResponse: => Option[String]): Result[Completion] =
    result
      .tapRight(c => recordExchange(startedAt, requestBody, successResponse, Right(c)))
      .tapLeft(e => recordExchange(startedAt, requestBody, failureResponse, Left(e)))
}

object AnthropicClient {

  /**
   * An SDK failure as an [[org.llm4s.error.LLMError]]. The SDK's exception messages carry the service's
   * reply - or, with workload identity, the federation token endpoint's - which may repeat a credential:
   * every message that becomes part of the error is scrubbed of `secrets` and of credential JSON fields,
   * passed through the general redactor and truncated.
   */
  private[provider] def mapError(e: Throwable, secrets: Seq[String]): LLMError = {
    def safe(message: String): String = Redaction.remoteBody(Option(message).getOrElse(""), secrets)
    e match {
      case e: com.anthropic.errors.UnauthorizedException         => AuthenticationError("anthropic", safe(e.getMessage))
      case _: com.anthropic.errors.RateLimitException            => RateLimitError("anthropic")
      case e: com.anthropic.errors.AnthropicInvalidDataException => ValidationError("input", safe(e.getMessage))
      case e =>
        e.toLLMError match {
          case unknown: UnknownError => UnknownError(safe(unknown.message), e)
          case other                 => other
        }
    }
  }

  /**
   * The configured values an error must not repeat: the API key, or with workload identity the ids
   * `AnthropicWorkloadIdentity.toString` redacts.
   */
  private[provider] def credentialSecrets(config: AnthropicConfig): Seq[String] =
    config.workloadIdentity match {
      case None     => Seq(config.apiKey)
      case Some(wi) => Seq(wi.federationRuleId, wi.organizationId) ++ wi.serviceAccountId ++ wi.workspaceId
    }
  import org.llm4s.types.TryOps

  /**
   * Whether an Anthropic model rejects the deprecated `temperature` sampling parameter.
   *
   * anthropic-java 2.42 deprecated `temperature`: models released after Claude Opus 4.6
   * accept only `1.0` and return a 400 for any other value. We detect those models by name
   * (Claude Opus 4.7+ and any Opus 5+) so the parameter can be omitted; this mirrors the
   * name-based handling of o-series models in `llm4s-openai`'s `OpenAIModelRules`.
   * Other models can be flagged via a registry `disallowedParams = ["temperature"]` override.
   *
   * Model identifiers may carry a provider prefix and/or a date suffix
   * (e.g. `anthropic/claude-opus-4-8`, `claude-opus-4-1-20250805`); only short (1-2 digit)
   * leading segments after `opus-` are treated as version numbers so date suffixes such as
   * `claude-opus-4-20250514` (Opus 4.0) and legacy names like `claude-3-opus-20240229` are
   * not misread as high versions.
   */
  private[provider] def rejectsSamplingTemperature(model: String): Boolean = {
    val normalized = model.toLowerCase
    val marker     = "opus-"
    val idx        = normalized.indexOf(marker)
    if (idx < 0) false
    else {
      val parts = normalized.substring(idx + marker.length).split("-")
      def shortVersion(s: String): Option[Int] =
        if (s.length <= 2 && s.nonEmpty && s.forall(_.isDigit)) s.toIntOption else None
      parts.headOption.flatMap(shortVersion).exists { major =>
        val minor = parts.lift(1).flatMap(shortVersion).getOrElse(0)
        major > 4 || (major == 4 && minor >= 7)
      }
    }
  }

  /**
   * Constructs an [[AnthropicClient]], wrapping any construction-time
   * exception in a `Left`.
   *
   * @param config  `AnthropicConfig` with API key, model, and base URL.
   * @param metrics Receives per-call latency and token-usage events.
   *                Defaults to `MetricsCollector.noop`.
   * @return `Right(client)` on success; `Left(LLMError)` if the underlying
   *         SDK client cannot be initialised.
   */
  def apply(
    config: AnthropicConfig,
    metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
  )(using ModelRegistryService): Result[AnthropicClient] =
    AnthropicConfig.validate(config).flatMap(valid => Try(new AnthropicClient(valid, metrics)).toResult)

  def apply(
    config: AnthropicConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[AnthropicClient] =
    AnthropicConfig
      .validate(config)
      .flatMap(valid => Try(new AnthropicClient(valid, metrics, exchangeLogging)).toResult)
}
