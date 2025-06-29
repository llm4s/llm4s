package org.llm4s.llmconnect.provider

import com.azure.ai.openai.OpenAIClientBuilder
import com.azure.ai.openai.models._
import com.azure.core.credential.KeyCredential
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ AzureToolHelper, ToolRegistry }

import scala.jdk.CollectionConverters._

class OpenAIClient(config: OpenAIConfig) extends LLMClient {
  // Initialize Azure OpenAI client with OpenAI credentials
  private val client = new OpenAIClientBuilder()
    .credential(new KeyCredential(config.apiKey))
    .endpoint(config.baseUrl)
    .buildClient()

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Either[LLMError, Completion] =
    try {
      // Convert conversation to Azure format
      val chatMessages = convertToAzureMessages(conversation)

      // Create chat options
      val chatOptions = new ChatCompletionsOptions(chatMessages)

      // Set options
      chatOptions.setTemperature(options.temperature.doubleValue())
      options.maxTokens.foreach(mt => chatOptions.setMaxTokens(mt))
      chatOptions.setPresencePenalty(options.presencePenalty.doubleValue())
      chatOptions.setFrequencyPenalty(options.frequencyPenalty.doubleValue())
      chatOptions.setTopP(options.topP.doubleValue())

      // Add tools if specified
      if (options.tools.nonEmpty) {
        val toolRegistry = new ToolRegistry(options.tools)
        AzureToolHelper.addToolsToOptions(toolRegistry, chatOptions)
      }

      // Add organization header if specified
      // Note: Azure SDK doesn't directly support adding custom headers to ChatCompletionsOptions
      // This would need to be handled differently if organization header is required

      // Make API call
      val completions = client.getChatCompletions(config.model, chatOptions)

      // Convert response to our model
      Right(convertFromAzureCompletion(completions))
    } catch {
      case e: Exception => Left(UnknownError(e))
    }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Either[LLMError, Completion] =
    // Simplified implementation for now
    complete(conversation, options)

  // Convert our Conversation to Azure's message format
  private def convertToAzureMessages(conversation: Conversation): java.util.ArrayList[ChatRequestMessage] = {
    val messages = new java.util.ArrayList[ChatRequestMessage]()

    conversation.messages.foreach {
      case UserMessage(content) =>
        messages.add(new ChatRequestUserMessage(content))
      case SystemMessage(content) =>
        messages.add(new ChatRequestSystemMessage(content))
      case AssistantMessage(content, toolCalls) =>
        val msg = new ChatRequestAssistantMessage(content.getOrElse(""))
        // Add tool calls if needed
        if (toolCalls.nonEmpty) {
          val azureToolCalls = new java.util.ArrayList[ChatCompletionsToolCall]()
          toolCalls.foreach { tc =>
            val function = new FunctionCall(tc.name, tc.arguments.render())
            val toolCall = new ChatCompletionsFunctionToolCall(tc.id, function)
            azureToolCalls.add(toolCall)
          }
          msg.setToolCalls(azureToolCalls)
        }
        messages.add(msg)
      case ToolMessage(toolCallId, content) =>
        messages.add(new ChatRequestToolMessage(content, toolCallId))
    }

    messages
  }

  // Convert Azure completion to our model
  private def convertFromAzureCompletion(completions: ChatCompletions): Completion = {
    val choice  = completions.getChoices.get(0)
    val message = choice.getMessage

    // Extract tool calls if present
    val toolCalls = extractToolCalls(message)

    Completion(
      id = completions.getId,
      created = completions.getCreatedAt.toEpochSecond,
      message = AssistantMessage(
        contentOpt = Some(message.getContent),
        toolCalls = toolCalls
      ),
      usage = Option(completions.getUsage).map(u =>
        TokenUsage(
          promptTokens = u.getPromptTokens,
          completionTokens = u.getCompletionTokens,
          totalTokens = u.getTotalTokens
        )
      )
    )
  }

  private def extractToolCalls(message: ChatResponseMessage): Seq[ToolCall] =
    Option(message.getToolCalls)
      .map(_.asScala.toSeq.collect { case ftc: ChatCompletionsFunctionToolCall =>
        ToolCall(
          id = ftc.getId,
          name = ftc.getFunction.getName,
          arguments = ujson.read(ftc.getFunction.getArguments)
        )
      })
      .getOrElse(Seq.empty)
}
