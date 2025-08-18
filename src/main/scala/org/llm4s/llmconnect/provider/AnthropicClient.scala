package org.llm4s.llmconnect.provider
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.{ JsonObject, ObjectMappers }
import com.anthropic.models.messages
import com.anthropic.models.messages.{ Message, MessageCreateParams, RawMessageStreamEvent, Tool }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming._
import org.llm4s.toolapi.{ ObjectSchema, ToolFunction }
import org.llm4s.types.Result
import org.llm4s.error.{ LLMError, AuthenticationError, RateLimitError, ValidationError }

import java.lang
import java.util.Optional
import scala.jdk.CollectionConverters._

class AnthropicClient(config: AnthropicConfig) extends LLMClient {
  // Initialize Anthropic client
  private val client = AnthropicOkHttpClient
    .builder()
    .apiKey(config.apiKey)
    .baseUrl(config.baseUrl)
    .build()

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] =
    try {
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
      val response: messages.Message = messageService.create(messageParams)

      // Convert response to our model
      Right(convertFromAnthropicResponse(response))
    } catch {
      case e: com.anthropic.errors.UnauthorizedException =>
        Left(AuthenticationError("anthropic", e.getMessage))
      case _: com.anthropic.errors.RateLimitException =>
        Left(RateLimitError("anthropic"))
      case e: com.anthropic.errors.AnthropicInvalidDataException =>
        Left(ValidationError("input", e.getMessage))
      case e: Exception =>
        Left(LLMError.fromThrowable(e))
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
  ): Result[Completion] =
    try {
      // Create message parameters builder
      val paramsBuilder = MessageCreateParams
        .builder()
        .model(config.model)
        .temperature(options.temperature.floatValue())
        .topP(options.topP.floatValue())

      // Add max tokens if specified (required by the API)
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

      // Create streaming response
      val messageService = client.messages()
      val streamResponse = messageService.createStreaming(messageParams)

      // Create accumulator for building the final completion
      val accumulator                      = StreamingAccumulator.create()
      var currentMessageId: Option[String] = None

      // Process the stream
      try {
        import scala.jdk.StreamConverters._
        import scala.jdk.OptionConverters._

        val stream: Iterator[RawMessageStreamEvent] = streamResponse.stream().toScala(Iterator)
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
            // Try to get text content from the delta
            try {
              val textOpt = delta.text()
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
            } catch {
              case _: Exception =>
              // Delta might be for tool use, skip for now
            }
          }

          // Check for content block start event
          val contentStartOpt = event.contentBlockStart()
          if (contentStartOpt != null && contentStartOpt.isPresent) {
            val contentStart = contentStartOpt.get()
            val block        = contentStart.contentBlock()
            if (block.isToolUse) {
              val toolUse = block.asToolUse()
              val chunk = StreamedChunk(
                id = currentMessageId.getOrElse(""),
                content = None,
                toolCall = Some(
                  ToolCall(
                    id = toolUse.id(),
                    name = toolUse.name(),
                    arguments = ujson.Null // Will be filled by deltas
                  )
                ),
                finishReason = None
              )
              accumulator.addChunk(chunk)
            }
          }

          // Check for message stop event
          val messageStopOpt = event.messageStop()
          if (messageStopOpt != null && messageStopOpt.isPresent) {
            // Message complete
            val chunk = StreamedChunk(
              id = currentMessageId.getOrElse(""),
              content = None,
              toolCall = None,
              finishReason = Some("stop")
            )
            accumulator.addChunk(chunk)
          }

          // Check for message delta event
          val messageDeltaOpt = event.messageDelta()
          if (messageDeltaOpt != null && messageDeltaOpt.isPresent) {
            val msgDelta = messageDeltaOpt.get()
            // Update usage if available
            try {
              val usage = msgDelta.usage()
              if (usage != null) {
                // Handle token counts - they might be Optional<Long> or Long
                val inputTokensRaw  = usage.inputTokens()
                val outputTokensRaw = usage.outputTokens()

                // inputTokens seems to return Optional<Long>
                val inputTokens = inputTokensRaw match {
                  case opt: Optional[_] =>
                    opt.asInstanceOf[Optional[lang.Long]].toScala.map(_.toInt).getOrElse(0)
                  case null => 0
                }

                // outputTokens seems to return Long directly
                val outputTokens = Option(outputTokensRaw).map(_.toInt).getOrElse(0)

                if (inputTokens > 0 || outputTokens > 0) {
                  accumulator.updateTokens(inputTokens, outputTokens)
                }
              }
            } catch {
              case _: Exception =>
              // Skip usage tracking if there's an issue
            }
          }
        }
      } finally
        // Clean up if needed
        streamResponse.close()

      // Return the accumulated completion
      accumulator.toCompletion()
    } catch {
      case e: com.anthropic.errors.UnauthorizedException =>
        Left(AuthenticationError("anthropic", e.getMessage))
      case _: com.anthropic.errors.RateLimitException =>
        Left(RateLimitError("anthropic"))
      case e: com.anthropic.errors.AnthropicInvalidDataException =>
        Left(ValidationError("input", e.getMessage))
      case e: Exception =>
        Left(LLMError.fromThrowable(e))
    }

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

    // Create completion
    Completion(
      id = response.id(),
      created = System.currentTimeMillis() / 1000, // Use current time as created timestamp
      message = AssistantMessage(
        contentOpt = content,
        toolCalls = toolCalls
      ),
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
