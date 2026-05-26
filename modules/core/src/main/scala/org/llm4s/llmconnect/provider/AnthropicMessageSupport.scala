package org.llm4s.llmconnect.provider

import com.anthropic.core.ObjectMappers
import com.anthropic.models.messages.{
  Message,
  MessageCreateParams,
  RawMessageStreamEvent,
  Tool
}

import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.{ ObjectSchema, ToolFunction }

import scala.jdk.CollectionConverters.*

/**
 * Shared protocol logic for Anthropic-compatible clients (direct API and Bedrock).
 *
 * Encapsulates message format conversion, tool schema sanitization, response
 * parsing, and serialization helpers that are identical regardless of transport.
 */
private[provider] trait AnthropicMessageSupport:

  protected def registryService: ModelRegistryService
  private given ModelRegistryService = registryService

  private[provider] def addMessagesToParams(
    conversation: Conversation,
    paramsBuilder: MessageCreateParams.Builder
  ): Unit =
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

  private[provider] def convertToolToAnthropicTool(toolFunction: ToolFunction[_, _]): Tool =
    val objectSchema = toolFunction.schema.asInstanceOf[ObjectSchema[_]]
    val jsonSchemaStr = objectSchema.toJsonSchema(false).render()

    val jsonNode = ujson.read(jsonSchemaStr)

    jsonNode.obj.remove("strict")
    jsonNode.obj.remove("additionalProperties")

    stripAdditionalProperties(jsonNode)

    val sanitizedSchemaStr = jsonNode.render()
    val jsonSchema: com.anthropic.core.JsonObject =
      ObjectMappers.jsonMapper().readValue(sanitizedSchemaStr, classOf[com.anthropic.core.JsonObject])
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

  private[provider] def convertFromAnthropicResponse(response: Message, modelName: String): Completion =
    val contentBlocks = response.content().asScala.toList

    val textContent: Option[String] =
      val texts = contentBlocks.filter(_.isText).map(_.asText().text())
      if (texts.nonEmpty) Some(texts.mkString) else None

    val thinkingContent: Option[String] =
      val thinkingTexts = contentBlocks.filter(_.isThinking).map(_.asThinking().thinking())
      if (thinkingTexts.nonEmpty) Some(thinkingTexts.mkString) else None

    val toolCalls = extractToolCalls(response)
    val message   = AssistantMessage(contentOpt = textContent, toolCalls = toolCalls)

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

    val cost = CostEstimator.estimate(modelName, tokenUsage)

    Completion(
      id = response.id(),
      content = message.content,
      model = response.model().asString(),
      toolCalls = toolCalls.toList,
      created = System.currentTimeMillis() / 1000,
      message = message,
      usage = Some(tokenUsage),
      thinking = thinkingContent,
      estimatedCost = cost
    )

  private[provider] def extractToolCalls(response: Message): Seq[ToolCall] =
    response.content().asScala.toList.filter(_.isToolUse).map { cb =>
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

  private[provider] def clampBudgetTokens(budgetTokens: Int, maxTokens: Int): Int =
    math.max(1024, math.min(budgetTokens, maxTokens - 1))

  private[provider] def applySamplingParameters(
    builder: MessageCreateParams.Builder,
    options: CompletionOptions
  ): Unit =
    builder.temperature(options.temperature.floatValue())

  private[provider] def serializeRequestBody(params: MessageCreateParams): String =
    ObjectMappers.jsonMapper().writeValueAsString(params._body())

  private[provider] def serializeResponseBody(message: Message): String =
    ObjectMappers.jsonMapper().writeValueAsString(message)

  private[provider] def serializeStreamEvent(event: RawMessageStreamEvent): String =
    ObjectMappers.jsonMapper().writeValueAsString(event)
