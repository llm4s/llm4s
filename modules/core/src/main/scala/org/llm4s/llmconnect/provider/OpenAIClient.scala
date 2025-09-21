package org.llm4s.llmconnect.provider

import com.azure.ai.openai.models._
import com.azure.ai.openai.{ OpenAIClientBuilder, OpenAIServiceVersion, OpenAIClient => AzureOpenAIClient }
import com.azure.core.credential.{ AzureKeyCredential, KeyCredential }
import org.llm4s.error.ThrowableOps._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ AzureConfig, OpenAIConfig, ProviderConfig }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming._
import org.llm4s.toolapi.{ AzureToolHelper, ToolRegistry }
import org.llm4s.types.Result

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * OpenAIClient implementation for both OpenAI and Azure OpenAI.
 *
 * This client supports both OpenAI's API and Azure's OpenAI service.
 * It handles message conversion, completion requests, and tool calls.
 */
class OpenAIClient private (
  private val model: String,
  private val client: AzureOpenAIClient,
  private val config: ProviderConfig
) extends LLMClient {

  /* * Constructor for OpenAI (non-Azure) */
  def this(config: OpenAIConfig) = this(
    config.model,
    new OpenAIClientBuilder()
      .credential(new KeyCredential(config.apiKey))
      .endpoint(config.baseUrl)
      .buildClient(),
    config
  )

  /** Constructor for Azure OpenAI */
  def this(config: AzureConfig) = this(
    config.model,
    new OpenAIClientBuilder()
      .credential(new AzureKeyCredential(config.apiKey))
      .endpoint(config.endpoint)
      .serviceVersion(OpenAIServiceVersion.valueOf(config.apiVersion))
      .buildClient(),
    config
  )

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = {
    // Convert conversation to Azure format
    val chatMessages = convertToOpenAIMessages(conversation)

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

    val res = Try(client.getChatCompletions(model, chatOptions)).toEither
    for {
      completions <- res.left.map(e => e.toLLMError)
    } yield convertFromOpenAIFormat(completions)
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    // Convert conversation to Azure format
    val chatMessages = convertToOpenAIMessages(conversation)

    // Create chat options with streaming enabled
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

    // Create accumulator for building the final completion
    val accumulator = StreamingAccumulator.create()

    // Safely obtain and process the stream, mapping all failures to LLMError
    val attempt = Try {
      val stream = client.getChatCompletionsStream(model, chatOptions)

      stream.forEach { chatCompletions =>
        if (chatCompletions.getChoices != null && !chatCompletions.getChoices.isEmpty) {
          val choice = chatCompletions.getChoices.get(0)
          val delta  = choice.getDelta

          // Create StreamedChunk from delta
          val chunk = StreamedChunk(
            id = Option(chatCompletions.getId).getOrElse(""),
            content = Option(delta.getContent),
            toolCall = extractStreamingToolCall(delta),
            finishReason = Option(choice.getFinishReason).map(_.toString)
          )

          // Add to accumulator
          accumulator.addChunk(chunk)

          // Call the callback
          onChunk(chunk)

          // Check if streaming is complete
          if (choice.getFinishReason != null) {
            // Extract usage if available
            Option(chatCompletions.getUsage).foreach { usage =>
              accumulator.updateTokens(usage.getPromptTokens, usage.getCompletionTokens)
            }
          }
        }
      }
    }.toEither.left.map(e => e.toLLMError)

    // Convert accumulated chunks into a final Completion on success
    attempt.flatMap(_ => accumulator.toCompletion.map(c => c.copy(model = model)))
  }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  private def extractStreamingToolCall(delta: ChatResponseMessage): Option[ToolCall] =
    Option(delta.getToolCalls).flatMap { toolCalls =>
      if (!toolCalls.isEmpty) {
        val tc = toolCalls.get(0)
        tc match {
          case ftc: ChatCompletionsFunctionToolCall =>
            Some(
              ToolCall(
                id = ftc.getId,
                name = Option(ftc.getFunction).map(_.getName).getOrElse(""),
                arguments = Option(ftc.getFunction)
                  .flatMap(f => Option(f.getArguments))
                  .map(args => ujson.read(args))
                  .getOrElse(ujson.Null)
              )
            )
          case _ => None
        }
      } else None
    }

  private def convertToOpenAIMessages(conversation: Conversation): java.util.ArrayList[ChatRequestMessage] = {
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
          val openAIToolCools = new java.util.ArrayList[ChatCompletionsToolCall]()
          toolCalls.foreach { tc =>
            val function = new FunctionCall(tc.name, tc.arguments.render())
            val toolCall = new ChatCompletionsFunctionToolCall(tc.id, function)
            openAIToolCools.add(toolCall)
          }
          msg.setToolCalls(openAIToolCools)
        }
        messages.add(msg)
      case ToolMessage(toolCallId, content) =>
        messages.add(new ChatRequestToolMessage(content, toolCallId))
    }

    messages
  }

  private def convertFromOpenAIFormat(completions: ChatCompletions): Completion = {
    val choice           = completions.getChoices.get(0)
    val message          = choice.getMessage
    val toolCalls        = extractToolCalls(message)
    val assistantMessage = AssistantMessage(content = message.getContent, toolCalls = toolCalls)

    Completion(
      id = completions.getId,
      created = completions.getCreatedAt.toEpochSecond,
      content = message.getContent,
      model = completions.getModel,
      message = assistantMessage,
      toolCalls = toolCalls.toList,
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

object OpenAIClient {
  import org.llm4s.types.TryOps

  def create(config: OpenAIConfig): Result[OpenAIClient] =
    Try(new OpenAIClient(config)).toResult

  def create(config: AzureConfig): Result[OpenAIClient] =
    Try(new OpenAIClient(config)).toResult
}
