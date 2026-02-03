package org.llm4s.llmconnect.provider
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.{ JsonObject, ObjectMappers }
import com.anthropic.models.messages.{
  Message,
  MessageCreateParams,
  RawMessageStreamEvent,
  ThinkingConfigEnabled,
  Tool
}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ AnthropicConfig, ProviderConfig }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming._
import org.llm4s.model.TransformationResult
import org.llm4s.toolapi.{ ObjectSchema, ToolFunction }
import org.llm4s.types.Result
import org.llm4s.error.{ AuthenticationError, RateLimitError, ValidationError }
import org.llm4s.error.ThrowableOps._

import java.util.Optional
import scala.jdk.CollectionConverters._
import scala.util.Try

class AnthropicClient(
  config: AnthropicConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
) extends LLMClient
    with MetricsRecording {
  // Store config for budget calculations
  private val providerConfig: ProviderConfig = config

  // Initialize Anthropic client
  private val client = AnthropicOkHttpClient
    .builder()
    .apiKey(config.apiKey)
    .baseUrl(config.baseUrl)
    .build()

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = withMetrics("anthropic", config.model) {
    // Transform options and messages for model-specific constraints
    TransformationResult.transform(config.model, options, conversation.messages, dropUnsupported = true).flatMap {
      transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)

        // Create message parameters builder
        val paramsBuilder = MessageCreateParams
          .builder()
          .model(config.model)
          .temperature(transformed.options.temperature.floatValue())
          .topP(transformed.options.topP.floatValue())

        // Add max tokens if specified
        // max tokens is required by the api
        val maxTokens = transformed.options.maxTokens.getOrElse(2048)
        paramsBuilder.maxTokens(maxTokens)

        // Add extended thinking configuration if requested
        // Minimum budget is 1024 tokens, must be less than max_tokens
        transformed.options.effectiveBudgetTokens.foreach { budgetTokens =>
          val effectiveBudget = math.max(1024, math.min(budgetTokens, maxTokens - 1))
          paramsBuilder.thinking(
            ThinkingConfigEnabled.builder().budgetTokens(effectiveBudget.toLong).build()
          )
        }

        // Add tools if specified
        if (transformed.options.tools.nonEmpty) {
          transformed.options.tools.foreach(tool => paramsBuilder.addTool(convertToolToAnthropicTool(tool)))
        }

        // Add messages from conversation
        addMessagesToParams(transformedConversation, paramsBuilder)

        // Build the parameters
        val messageParams = paramsBuilder.build()

        val messageService = client.messages()
        // Make API call
        val attempt = Try(messageService.create(messageParams)).toEither.left.map {
          case e: com.anthropic.errors.UnauthorizedException         => AuthenticationError("anthropic", e.getMessage)
          case _: com.anthropic.errors.RateLimitException            => RateLimitError("anthropic")
          case e: com.anthropic.errors.AnthropicInvalidDataException => ValidationError("input", e.getMessage)
          case e: Exception                                          => e.toLLMError
        }
        attempt.map(convertFromAnthropicResponse) // Convert response to our model
    }
  }(
    extractUsage = _.usage,
    estimateCost = usage =>
      org.llm4s.model.ModelRegistry.lookup(config.model).toOption.flatMap { meta =>
        meta.pricing.estimateCost(usage.promptTokens, usage.completionTokens)
      }
  )

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
  ): Result[Completion] = withMetrics("anthropic", config.model) {
    // Transform options and messages for model-specific constraints
    TransformationResult.transform(config.model, options, conversation.messages, dropUnsupported = true).flatMap {
      transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)

        // Build parameters
        val paramsBuilder = MessageCreateParams
          .builder()
          .model(config.model)
          .temperature(transformed.options.temperature.floatValue())
          .topP(transformed.options.topP.floatValue())

        // Add max tokens if specified (required by the API)
        val maxTokens = transformed.options.maxTokens.getOrElse(2048)
        paramsBuilder.maxTokens(maxTokens)

        // Add extended thinking configuration if requested
        transformed.options.effectiveBudgetTokens.foreach { budgetTokens =>
          val effectiveBudget = math.max(1024, math.min(budgetTokens, maxTokens - 1))
          paramsBuilder.thinking(
            ThinkingConfigEnabled.builder().budgetTokens(effectiveBudget.toLong).build()
          )
        }

        // Add tools if specified
        if (transformed.options.tools.nonEmpty)
          transformed.options.tools.foreach(t => paramsBuilder.addTool(convertToolToAnthropicTool(t)))
        // Add messages from conversation
        addMessagesToParams(transformedConversation, paramsBuilder)
        // Build the parameters
        val messageParams = paramsBuilder.build()

        // Create accumulator for building the final completion
        val accumulator                      = StreamingAccumulator.create()
        var currentMessageId: Option[String] = None

        // Process the stream
        val attempt = Try {
          val messageService = client.messages()
          val streamResponse = messageService.createStreaming(messageParams)

          import scala.jdk.StreamConverters._
          import scala.jdk.OptionConverters._
          val stream: Iterator[RawMessageStreamEvent] = streamResponse.stream().toScala(Iterator)
          val loopTry = Try {
            stream.foreach { event =>
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
              }

              val contentStartOpt = event.contentBlockStart()
              if (contentStartOpt != null && contentStartOpt.isPresent) {
                val contentStart = contentStartOpt.get()
                val block        = contentStart.contentBlock()
                if (block.isToolUse) {
                  val toolUse = block.asToolUse()
                  val chunk = StreamedChunk(
                    id = currentMessageId.getOrElse(""),
                    content = None,
                    toolCall = Some(ToolCall(id = toolUse.id(), name = toolUse.name(), arguments = ujson.Null)),
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
                      case Some(opt: Optional[_]) => opt.toScala.map(_.toInt).getOrElse(0)
                      case _                      => 0
                    }
                    val outputTokens = Option(usage.outputTokens()).map(_.toInt).getOrElse(0)
                    if (inputTokens > 0 || outputTokens > 0) accumulator.updateTokens(inputTokens, outputTokens)
                  }
                }
              }
            }
          }
          Try(streamResponse.close());
          loopTry.get
        }.toEither.left
          .map {
            case e: com.anthropic.errors.UnauthorizedException         => AuthenticationError("anthropic", e.getMessage)
            case _: com.anthropic.errors.RateLimitException            => RateLimitError("anthropic")
            case e: com.anthropic.errors.AnthropicInvalidDataException => ValidationError("input", e.getMessage)
            case e: Exception                                          => e.toLLMError
          }

        // Return the accumulated completion
        attempt.flatMap(_ => accumulator.toCompletion.map(c => c.copy(model = config.model)))
    }
  }(
    extractUsage = _.usage,
    estimateCost = usage =>
      org.llm4s.model.ModelRegistry.lookup(config.model).toOption.flatMap { meta =>
        meta.pricing.estimateCost(usage.promptTokens, usage.completionTokens)
      }
  )

  override def getContextWindow(): Int = providerConfig.contextWindow

  override def getReserveCompletion(): Int = providerConfig.reserveCompletion

  // Add messages from conversation to the parameters builder
  private def addMessagesToParams(
    conversation: Conversation,
    paramsBuilder: MessageCreateParams.Builder
  ): Unit = {
    // Track if we've seen a system message
    var hasSystemMessage = false

    // Process messages in order
    conversation.messages.foreach {
      case SystemMessage(content) =>
        paramsBuilder.system(content)
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

    // Add a default system message if none was provided
    if (!hasSystemMessage) {
      paramsBuilder.system("You are Claude, a helpful AI assistant.")
    }
  }

  // Convert our ToolFunction to Anthropic's Tool
  private def convertToolToAnthropicTool(toolFunction: ToolFunction[_, _]): Tool = {
    // note: in case of debug set this environment variable -- `ANTHROPIC_LOG=debug`

    val objectSchema  = toolFunction.schema.asInstanceOf[ObjectSchema[_]]
    val jsonSchemaStr = objectSchema.toJsonSchema(false).render()

    // Parse the JSON schema and extract properties
    val jsonSchema: JsonObject =
      ObjectMappers.jsonMapper().readValue(jsonSchemaStr, classOf[JsonObject])
    val jsonSchemaMap = jsonSchema.values()

    // Build the input schema using raw JSON value for properties
    // The new SDK requires proper Properties type, so we use the putAdditionalProperty approach
    val inputSchemaBuilder = Tool.InputSchema.builder()

    // Convert the properties JsonValue to Properties type
    val propertiesValue = jsonSchemaMap.get("properties")
    if (propertiesValue != null) {
      // Use the properties via JsonValue wrapper
      val propertiesObj = ObjectMappers
        .jsonMapper()
        .readValue(
          ObjectMappers.jsonMapper().writeValueAsString(propertiesValue),
          classOf[Tool.InputSchema.Properties]
        )
      inputSchemaBuilder.properties(propertiesObj)
    }

    val tool = Tool
      .builder()
      .name(toolFunction.name)
      .description(toolFunction.description)
      .inputSchema(inputSchemaBuilder.build().validate())
      .build()
    tool
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
    val baseTokenUsage = TokenUsage(
      promptTokens = usage.inputTokens().toInt,
      completionTokens = usage.outputTokens().toInt,
      totalTokens = (usage.inputTokens() + usage.outputTokens()).toInt
    )

    // Check for thinking tokens in cache usage (Anthropic reports cache_read_input_tokens for thinking)
    // Note: The SDK may expose thinking tokens differently - adjust as needed
    val tokenUsage = baseTokenUsage

    // Create completion
    Completion(
      id = response.id(),
      content = message.content,
      model = response.model().asString(),
      toolCalls = toolCalls.toList,
      created = System.currentTimeMillis() / 1000, // Use current time as created timestamp
      message = message,
      usage = Some(tokenUsage),
      thinking = thinkingContent
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
}

object AnthropicClient {
  import org.llm4s.types.TryOps

  def apply(
    config: AnthropicConfig,
    metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
  ): Result[AnthropicClient] =
    Try(new AnthropicClient(config, metrics)).toResult
}
