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
import org.llm4s.error.{ AuthenticationError, ConfigurationError, RateLimitError, ValidationError }
import org.llm4s.error.ThrowableOps._

import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._
import scala.util.Try

class AnthropicClient(
  config: AnthropicConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
) extends LLMClient
    with MetricsRecording {
  private val providerConfig: ProviderConfig = config

  private val client = AnthropicOkHttpClient
    .builder()
    .apiKey(config.apiKey)
    .baseUrl(config.baseUrl)
    .build()

  private val closed: AtomicBoolean = new AtomicBoolean(false)

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = withMetrics("anthropic", config.model) {
    validateNotClosed.flatMap { _ =>
      TransformationResult.transform(config.model, options, conversation.messages, dropUnsupported = true).flatMap {
        transformed =>
          val transformedConversation = conversation.copy(messages = transformed.messages)
          val paramsBuilder = MessageCreateParams
            .builder()
            .model(config.model)
            .temperature(transformed.options.temperature.floatValue())
            .topP(transformed.options.topP.floatValue())

          val maxTokens = transformed.options.maxTokens.getOrElse(2048)
          paramsBuilder.maxTokens(maxTokens)

          transformed.options.effectiveBudgetTokens.foreach { budgetTokens =>
            val effectiveBudget = math.max(1024, math.min(budgetTokens, maxTokens - 1))
            paramsBuilder.thinking(
              ThinkingConfigEnabled.builder().budgetTokens(effectiveBudget.toLong).build()
            )
          }

          if (transformed.options.tools.nonEmpty) {
            transformed.options.tools.foreach(tool => paramsBuilder.addTool(convertToolToAnthropicTool(tool)))
          }

          addMessagesToParams(transformedConversation, paramsBuilder)
          val messageParams = paramsBuilder.build()
          val messageService = client.messages()
          
          val attempt = Try(messageService.create(messageParams)).toEither.left.map {
            case e: com.anthropic.errors.UnauthorizedException         => AuthenticationError("anthropic", e.getMessage)
            case _: com.anthropic.errors.RateLimitException            => RateLimitError("anthropic")
            case e: com.anthropic.errors.AnthropicInvalidDataException => ValidationError("input", e.getMessage)
            case e: Exception                                          => e.toLLMError
          }
          attempt.map(convertFromAnthropicResponse)
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
  ): Result[Completion] = withMetrics("anthropic", config.model) {
    validateNotClosed.flatMap { _ =>
      TransformationResult.transform(config.model, options, conversation.messages, dropUnsupported = true).flatMap {
        transformed =>
          val transformedConversation = conversation.copy(messages = transformed.messages)
          val paramsBuilder = MessageCreateParams
            .builder()
            .model(config.model)
            .temperature(transformed.options.temperature.floatValue())
            .topP(transformed.options.topP.floatValue())

          val maxTokens = transformed.options.maxTokens.getOrElse(2048)
          paramsBuilder.maxTokens(maxTokens)

          transformed.options.effectiveBudgetTokens.foreach { budgetTokens =>
            val effectiveBudget = math.max(1024, math.min(budgetTokens, maxTokens - 1))
            paramsBuilder.thinking(
              ThinkingConfigEnabled.builder().budgetTokens(effectiveBudget.toLong).build()
            )
          }

          if (transformed.options.tools.nonEmpty)
            transformed.options.tools.foreach(t => paramsBuilder.addTool(convertToolToAnthropicTool(t)))
          
          addMessagesToParams(transformedConversation, paramsBuilder)
          val messageParams = paramsBuilder.build()
          val accumulator                      = StreamingAccumulator.create()
          var currentMessageId: Option[String] = None

          val attempt = Try {
            val messageService = client.messages()
            val streamResponse = messageService.createStreaming(messageParams)

            import scala.jdk.StreamConverters._
            import scala.jdk.OptionConverters._
            val stream: Iterator[RawMessageStreamEvent] = streamResponse.stream().toScala(Iterator)
            val loopTry = Try {
              stream.foreach { event =>
                val messageStartOpt = event.messageStart()
                if (messageStartOpt != null && messageStartOpt.isPresent) {
                  currentMessageId = Some(messageStartOpt.get().message().id())
                }

                val contentDeltaOpt = event.contentBlockDelta()
                if (contentDeltaOpt != null && contentDeltaOpt.isPresent) {
                  val delta = contentDeltaOpt.get().delta()
                  Try(delta.text()).foreach { textOpt =>
                    if (textOpt != null && textOpt.isPresent) {
                      val text = textOpt.get().text()
                      if (text != null && text.nonEmpty) {
                        val chunk = StreamedChunk(id = currentMessageId.getOrElse(""), content = Some(text), toolCall = None, finishReason = None)
                        accumulator.addChunk(chunk); onChunk(chunk)
                      }
                    }
                  }
                  Try(delta.thinking()).foreach { thinkingOpt =>
                    if (thinkingOpt != null && thinkingOpt.isPresent) {
                      val thinkingText = thinkingOpt.get().thinking()
                      if (thinkingText != null && thinkingText.nonEmpty) {
                        val chunk = StreamedChunk(id = currentMessageId.getOrElse(""), content = None, toolCall = None, finishReason = None, thinkingDelta = Some(thinkingText))
                        accumulator.addChunk(chunk); onChunk(chunk)
                      }
                    }
                  }
                }

                val contentStartOpt = event.contentBlockStart()
                if (contentStartOpt != null && contentStartOpt.isPresent) {
                  val block = contentStartOpt.get().contentBlock()
                  if (block.isToolUse) {
                    val toolUse = block.asToolUse()
                    val chunk = StreamedChunk(id = currentMessageId.getOrElse(""), content = None, toolCall = Some(ToolCall(id = toolUse.id(), name = toolUse.name(), arguments = ujson.Null)), finishReason = None)
                    accumulator.addChunk(chunk); onChunk(chunk)
                  }
                }

                val messageStopOpt = event.messageStop()
                if (messageStopOpt != null && messageStopOpt.isPresent) {
                  val chunk = StreamedChunk(id = currentMessageId.getOrElse(""), content = None, toolCall = None, finishReason = Some("stop"))
                  accumulator.addChunk(chunk); onChunk(chunk)
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
            Try(streamResponse.close())
            loopTry.get
          }.toEither.left.map {
            case e: com.anthropic.errors.UnauthorizedException => AuthenticationError("anthropic", e.getMessage)
            case _: com.anthropic.errors.RateLimitException    => RateLimitError("anthropic")
            case e: com.anthropic.errors.AnthropicInvalidDataException => ValidationError("input", e.getMessage)
            case e: Exception                                          => e.toLLMError
          }
          attempt.flatMap(_ => accumulator.toCompletion.map(c => c.copy(model = config.model)))
      }
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

  private def addMessagesToParams(
    conversation: Conversation,
    paramsBuilder: MessageCreateParams.Builder
  ): Unit = {
    var hasSystemMessage = false
    conversation.messages.foreach {
      case SystemMessage(content) =>
        paramsBuilder.system(content)
        hasSystemMessage = true
      case UserMessage(content) =>
        paramsBuilder.addUserMessage(content)
      case AssistantMessage(contentOpt, toolCalls) =>
        if (toolCalls.isEmpty) {
          paramsBuilder.addAssistantMessage(contentOpt.getOrElse(""))
        }
      case ToolMessage(content, toolCallId) =>
        paramsBuilder.addUserMessage(s"[Tool result for $toolCallId]: $content")
    }
    if (!hasSystemMessage) {
      paramsBuilder.system("You are Claude, a helpful AI assistant.")
    }
  }

  /**
   * Convert a ToolFunction to Anthropic's Tool format.
   * Strips OpenAI-specific fields like 'strict' and 'additionalProperties' from the schema
   * to maintain compatibility with the Anthropic API.
   */
  private def convertToolToAnthropicTool(toolFunction: ToolFunction[_, _]): Tool = {
    val objectSchema  = toolFunction.schema.asInstanceOf[ObjectSchema[_]]
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
    val propertiesValue = jsonSchemaMap.get("properties")
    if (propertiesValue != null) {
      val propertiesObj = ObjectMappers.jsonMapper().readValue(
          ObjectMappers.jsonMapper().writeValueAsString(propertiesValue),
          classOf[Tool.InputSchema.Properties]
        )
      inputSchemaBuilder.properties(propertiesObj)
    }

    Tool.builder()
      .name(toolFunction.name)
      .description(toolFunction.description)
      .inputSchema(inputSchemaBuilder.build().validate())
      .build()
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

  private def convertFromAnthropicResponse(response: Message): Completion = {
    val contentBlocks = response.content().asScala.toList
    val textContent: Option[String] = {
      val texts = contentBlocks.filter(_.isText).map(_.asText().text())
      if (texts.nonEmpty) Some(texts.mkString) else None
    }
    val thinkingContent: Option[String] = {
      val thinkingTexts = contentBlocks.filter(_.isThinking).map(_.asThinking().thinking())
      if (thinkingTexts.nonEmpty) Some(thinkingTexts.mkString) else None
    }
    val toolCalls = extractToolCalls(response)
    val message   = AssistantMessage(contentOpt = textContent, toolCalls = toolCalls)
    val usage = response.usage()
    val tokenUsage = TokenUsage(
      promptTokens = usage.inputTokens().toInt,
      completionTokens = usage.outputTokens().toInt,
      totalTokens = (usage.inputTokens() + usage.outputTokens()).toInt
    )

    Completion(
      id = response.id(),
      content = message.content,
      model = response.model().asString(),
      toolCalls = toolCalls.toList,
      created = System.currentTimeMillis() / 1000,
      message = message,
      usage = Some(tokenUsage),
      thinking = thinkingContent
    )
  }

  private def extractToolCalls(response: Message): Seq[ToolCall] = {
    response.content().asScala.toList.filter(_.isToolUse).map { cb =>
      val toolUse    = cb.asToolUse()
      val toolId     = toolUse.id()
      val toolName   = toolUse.name()
      val rawParams = toolUse._input()
      val arguments = ujson.read(ObjectMappers.jsonMapper().writeValueAsString(rawParams))
      ToolCall(id = toolId, name = toolName, arguments = arguments)
    }
  }

  override def close(): Unit = if (closed.compareAndSet(false, true)) {}

  private def validateNotClosed: Result[Unit] =
    if (closed.get()) Left(ConfigurationError(s"Anthropic client for model ${config.model} is already closed"))
    else Right(())
}

object AnthropicClient {
  import org.llm4s.types.TryOps
  def apply(config: AnthropicConfig, metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop): Result[AnthropicClient] =
    Try(new AnthropicClient(config, metrics)).toResult
}