package org.llm4s.llmconnect.provider

import com.azure.ai.openai.models._
import com.azure.ai.openai.{ OpenAIClientBuilder, OpenAIServiceVersion, OpenAIClient => AzureOpenAIClient }
import com.azure.core.credential.{ AzureKeyCredential, KeyCredential }
import org.llm4s.error.ThrowableOps._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ AzureConfig, OpenAIConfig, ProviderConfig }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming._
import org.llm4s.model.{ RequestTransformer, TransformationResult }
import org.llm4s.toolapi.{ AzureToolHelper, ToolRegistry }
import org.llm4s.types.Result

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * LLMClient implementation supporting both OpenAI and Azure OpenAI services.
 *
 * Provides a unified interface for interacting with OpenAI's API and Azure's OpenAI service.
 * Handles message conversion between llm4s format and OpenAI format, completion requests,
 * streaming responses, and tool calling (function calling) capabilities.
 *
 * Uses Azure's OpenAI client library internally, which supports both direct OpenAI and
 * Azure-hosted OpenAI endpoints.
 *
 * @param model the model identifier (e.g., "gpt-4", "gpt-3.5-turbo")
 * @param client configured Azure OpenAI client instance
 * @param config provider configuration containing context window and reserve completion settings
 */
class OpenAIClient private (
  private val model: String,
  private val client: AzureOpenAIClient,
  private val config: ProviderConfig
) extends LLMClient {

  /**
   * Creates an OpenAI client for direct OpenAI API access.
   *
   * @param config OpenAI configuration with API key and base URL
   */
  def this(config: OpenAIConfig) = this(
    config.model,
    new OpenAIClientBuilder()
      .credential(new KeyCredential(config.apiKey))
      .endpoint(config.baseUrl)
      .buildClient(),
    config
  )

  /**
   * Creates an OpenAI client for Azure OpenAI service.
   *
   * @param config Azure configuration with API key, endpoint, and API version
   */
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
    // Transform options and messages for model-specific constraints
    for {
      transformed <- TransformationResult.transform(
        model,
        options,
        conversation.messages,
        dropUnsupported = true
      )
      transformedConversation = conversation.copy(messages = transformed.messages)
      chatOptions             = prepareChatOptions(transformedConversation, transformed.options)
      completions <- Try(client.getChatCompletions(model, chatOptions)).toEither.left.map(e => e.toLLMError)
    } yield convertFromOpenAIFormat(completions)
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    // Transform options and messages for model-specific constraints
    TransformationResult.transform(model, options, conversation.messages, dropUnsupported = true).flatMap {
      transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)
        val chatOptions             = prepareChatOptions(transformedConversation, transformed.options)

        // Create accumulator for building the final completion
        val accumulator = StreamingAccumulator.create()

        // Check if this model requires fake streaming
        if (transformed.requiresFakeStreaming) {
          // For models that don't support native streaming (e.g., O-series),
          // make a regular completion call and emit the result as a single chunk
          val attempt = Try(client.getChatCompletions(model, chatOptions)).toEither.left.map(e => e.toLLMError)
          attempt.flatMap { completions =>
            val completion = convertFromOpenAIFormat(completions)
            // Emit a single chunk with the full content
            val chunk = StreamedChunk(
              id = completion.id,
              content = Some(completion.content),
              toolCall = completion.toolCalls.headOption,
              finishReason = Some("stop")
            )
            onChunk(chunk)
            Right(completion)
          }
        } else {
          // Normal streaming path
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
    }
  }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  /**
   * Prepares ChatCompletionsOptions from conversation and completion options.
   *
   * Converts llm4s conversation format to OpenAI ChatCompletionsOptions, applying temperature,
   * token limits, penalties, and tools. Shared between complete() and streamComplete().
   *
   * @param conversation llm4s conversation to convert
   * @param options completion options to apply
   * @return configured ChatCompletionsOptions ready for API call
   */
  private def prepareChatOptions(conversation: Conversation, options: CompletionOptions): ChatCompletionsOptions = {
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

    chatOptions
  }

  /**
   * Extracts tool call information from a streaming response delta.
   *
   * Parses the first tool call from the delta message, converting function call details
   * into llm4s ToolCall format. Used during streaming to capture tool calling requests.
   *
   * @param delta streaming response message delta from OpenAI
   * @return Some(ToolCall) if a function tool call is present, None otherwise
   */
  private def extractStreamingToolCall(delta: ChatResponseMessage): Option[ToolCall] =
    for {
      toolCalls <- Option(delta.getToolCalls)
      tc        <- toolCalls.asScala.headOption
      result <- Option(tc).collect { case ftc: ChatCompletionsFunctionToolCall =>
        ToolCall(
          id = ftc.getId,
          name = Option(ftc.getFunction).map(_.getName).getOrElse(""),
          arguments = Option(ftc.getFunction)
            .flatMap(f => Option(f.getArguments))
            .flatMap(args => Try(ujson.read(args)).toOption)
            .getOrElse(ujson.Null)
        )
      }
    } yield result

  /**
   * Converts llm4s Conversation to OpenAI ChatRequestMessage format.
   *
   * Transforms each message type (User, System, Assistant, Tool) into the corresponding
   * OpenAI message format. Handles tool calls in assistant messages by converting them
   * to ChatCompletionsFunctionToolCall objects.
   *
   * @param conversation llm4s conversation to convert
   * @return ArrayList of ChatRequestMessage suitable for OpenAI API
   */
  // TODO: Refactor to use idiomatic Scala collections with folding instead of mutable java.util.ArrayList
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
      case ToolMessage(content, toolCallId) =>
        messages.add(new ChatRequestToolMessage(content, toolCallId))
    }

    messages
  }

  /**
   * Converts OpenAI ChatCompletions response to llm4s Completion format.
   *
   * Extracts the first choice from the response and converts it to llm4s format,
   * including content, tool calls, and token usage information.
   *
   * @param completions OpenAI API response
   * @return llm4s Completion with all response data
   */
  private def convertFromOpenAIFormat(completions: ChatCompletions): Completion = {
    val choice    = completions.getChoices.get(0)
    val message   = choice.getMessage
    val toolCalls = extractToolCalls(message)
    val content   = Option(message.getContent).getOrElse("")
    val assistantMessage =
      AssistantMessage(contentOpt = if (content.isEmpty) None else Some(content), toolCalls = toolCalls)

    Completion(
      id = completions.getId,
      created = completions.getCreatedAt.toEpochSecond,
      content = content,
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

  /**
   * Extracts tool calls from an OpenAI response message.
   *
   * Parses function tool calls from the message and converts them to llm4s ToolCall format.
   * Returns empty sequence if no tool calls are present.
   *
   * @param message OpenAI response message potentially containing tool calls
   * @return sequence of ToolCall objects, empty if no tool calls present
   */
  private def extractToolCalls(message: ChatResponseMessage): Seq[ToolCall] =
    Option(message.getToolCalls)
      .map(_.asScala.toSeq.collect {
        case ftc: ChatCompletionsFunctionToolCall if Try(ujson.read(ftc.getFunction.getArguments)).isSuccess =>
          ToolCall(
            id = ftc.getId,
            name = ftc.getFunction.getName,
            arguments = Try(ujson.read(ftc.getFunction.getArguments)).getOrElse(ujson.Null)
          )
      })
      .getOrElse(Seq.empty)
}

/**
 * Factory methods for creating OpenAIClient instances.
 *
 * Provides safe construction of OpenAI clients with error handling via Result type.
 */
object OpenAIClient {
  import org.llm4s.types.TryOps

  /**
   * Creates an OpenAI client for direct OpenAI API access.
   *
   * @param config OpenAI configuration with API key, model, and base URL
   * @return Right(OpenAIClient) on success, Left(LLMError) if client creation fails
   */
  def apply(config: OpenAIConfig): Result[OpenAIClient] =
    Try(new OpenAIClient(config)).toResult

  /**
   * Creates an OpenAI client for Azure OpenAI service.
   *
   * @param config Azure configuration with API key, model, endpoint, and API version
   * @return Right(OpenAIClient) on success, Left(LLMError) if client creation fails
   */
  def apply(config: AzureConfig): Result[OpenAIClient] =
    Try(new OpenAIClient(config)).toResult
}
