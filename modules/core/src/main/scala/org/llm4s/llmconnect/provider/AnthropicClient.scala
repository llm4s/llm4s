package org.llm4s.llmconnect.provider
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.{ JsonObject, ObjectMappers }
import com.anthropic.models.messages.{ Message, MessageCreateParams, RawMessageStreamEvent, Tool }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ AnthropicConfig, ProviderConfig }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming._
import org.llm4s.toolapi.{ ObjectSchema, ToolFunction }
import org.llm4s.types.Result
import org.llm4s.error.{ AuthenticationError, RateLimitError, ValidationError }
import org.llm4s.error.ThrowableOps._

import java.util.Optional
import scala.jdk.CollectionConverters._
import scala.util.Try

class AnthropicClient(config: AnthropicConfig) extends LLMClient {
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
  ): Result[Completion] = {
    // Create message parameters builder
    val paramsBuilder = MessageCreateParams
      .builder()
      .model(config.model)
      .temperature(options.temperature.floatValue())
      .topP(options.topP.floatValue())

    // Add max tokens if specified
    // max tokens is required by the api
    val maxTokens = options.maxTokens.getOrElse(2048)
    paramsBuilder.maxTokens(maxTokens)

    // Add tools if specified
    if (options.tools.nonEmpty) {
      options.tools.foreach(tool => paramsBuilder.addTool(convertToolToAnthropicTool(tool)))
    }

    // Add messages from conversation
    addMessagesToParams(conversation, paramsBuilder)

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
  ): Result[Completion] = {
    // Build parameters
    val paramsBuilder = MessageCreateParams
      .builder()
      .model(config.model)
      .temperature(options.temperature.floatValue())
      .topP(options.topP.floatValue())

    // Add max tokens if specified (required by the API)
    val maxTokens = options.maxTokens.getOrElse(2048)
    paramsBuilder.maxTokens(maxTokens)
    // Add tools if specified
    if (options.tools.nonEmpty) options.tools.foreach(t => paramsBuilder.addTool(convertToolToAnthropicTool(t)))
    // Add messages from conversation
    addMessagesToParams(conversation, paramsBuilder)
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

      case AssistantMessage(content, _) =>
        paramsBuilder.addAssistantMessage(content.getOrElse(""))

      case ToolMessage(toolCallId, content) =>
        // Anthropic doesn't have a direct equivalent to tool messages
        // We'll add it as a user message with a prefix
        paramsBuilder.addUserMessage(s"Tool result for $toolCallId: $content")
    }

    // Add a default system message if none was provided
    if (!hasSystemMessage) {
      paramsBuilder.system("You are Claude, a helpful AI assistant.")
    }
  }

  // Convert our ToolFunction to Anthropic's Tool
  private def convertToolToAnthropicTool(toolFunction: ToolFunction[_, _]): Tool = {
    // note: in case of debug set this environment variable -- `ANTHROPIC_LOG=debug`

    val objectSchema = toolFunction.schema.asInstanceOf[ObjectSchema[_]]
    val jsonSchema: JsonObject =
      ObjectMappers.jsonMapper().readValue(objectSchema.toJsonSchema(false).render(), classOf[JsonObject])
    val jsonSchemaMap   = jsonSchema.values()
    val inputProperties = jsonSchemaMap.get("properties")

    val inputSchemaBuilder = Tool.InputSchema.builder()
    inputSchemaBuilder.properties(inputProperties)
    objectSchema.properties.map(p => p.required)

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
    // Extract content
    val content: Option[String] = Some(
      response
        .content()
        .asScala
        .toList
        .filter(_.isText)
        .map(_.asText().text())
        .mkString
    )

    // Extract tool calls if present
    val toolCalls = extractToolCalls(response)
    val message   = AssistantMessage(contentOpt = content, toolCalls = toolCalls)
    // Create completion
    Completion(
      id = response.id(),
      content = message.content,
      model = response.model().asString(),
      toolCalls = toolCalls.toList,
      created = System.currentTimeMillis() / 1000, // Use current time as created timestamp
      message = message,
      usage = Some(
        TokenUsage(
          promptTokens = response.usage().inputTokens().toInt,
          completionTokens = response.usage().outputTokens().toInt,
          totalTokens = (response.usage().inputTokens() + response.usage().outputTokens()).toInt
        )
      )
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
