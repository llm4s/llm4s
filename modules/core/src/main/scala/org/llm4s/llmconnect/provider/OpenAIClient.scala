package org.llm4s.llmconnect.provider

import com.azure.ai.openai.models._
import com.azure.ai.openai.{ OpenAIClientBuilder, OpenAIServiceVersion, OpenAIClient => AzureOpenAIClient }
import com.azure.core.credential.{ AzureKeyCredential, KeyCredential }
import com.azure.core.util.IterableStream
import org.llm4s.error.ConfigurationError
import org.llm4s.error.ThrowableOps._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ AzureConfig, OpenAIConfig, ProviderConfig }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming._
import org.llm4s.model.TransformationResult
import org.llm4s.toolapi.{ AzureToolHelper, ToolRegistry }
import org.llm4s.types.Result
import org.slf4j.{ Logger, LoggerFactory }

import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._
import scala.util.Try

private[provider] trait OpenAIClientTransport {
  def getChatCompletions(model: String, options: ChatCompletionsOptions): ChatCompletions
  def getChatCompletionsStream(model: String, options: ChatCompletionsOptions): IterableStream[ChatCompletions]
}

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
 * == Extended Thinking / Reasoning Support ==
 *
 * For OpenAI o1/o3/o4 models with reasoning capabilities, use [[OpenRouterClient]] instead,
 * which fully supports the `reasoning_effort` parameter. The Azure SDK used by this client
 * does not yet expose the `reasoning_effort` API parameter.
 *
 * For Anthropic Claude models with extended thinking, use [[AnthropicClient]] which has
 * full support for the `thinking` parameter with `budget_tokens`.
 *
 * @param model the model identifier (e.g., "gpt-4", "gpt-3.5-turbo")
 * @param client configured Azure OpenAI client instance
 * @param config provider configuration containing context window and reserve completion settings
 * @param metrics metrics collector for observability (default: noop)
 */
class OpenAIClient private (
  private val model: String,
  private val transport: OpenAIClientTransport,
  private val config: ProviderConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector
) extends LLMClient
    with MetricsRecording {

  private lazy val logger: Logger   = LoggerFactory.getLogger(getClass)
  private val closed: AtomicBoolean = new AtomicBoolean(false)

  /**
   * Creates an OpenAI client for direct OpenAI API access.
   *
   * @param config OpenAI configuration with API key and base URL
   * @param metrics metrics collector (default: noop)
   */
  def this(config: OpenAIConfig, metrics: org.llm4s.metrics.MetricsCollector) = this(
    config.model,
    OpenAIClientTransport.azure(
      new OpenAIClientBuilder()
        .credential(new KeyCredential(config.apiKey))
        .endpoint(config.baseUrl)
        .buildClient()
    ),
    config,
    metrics
  )

  /**
   * Creates an OpenAI client for Azure OpenAI service.
   *
   * @param config Azure configuration with API key, endpoint, and API version
   * @param metrics metrics collector (default: noop)
   */
  def this(config: AzureConfig, metrics: org.llm4s.metrics.MetricsCollector) = this(
    config.model,
    OpenAIClientTransport.azure(
      new OpenAIClientBuilder()
        .credential(new AzureKeyCredential(config.apiKey))
        .endpoint(config.endpoint)
        .serviceVersion(OpenAIServiceVersion.valueOf(config.apiVersion))
        .buildClient()
    ),
    config,
    metrics
  )

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = withMetrics("openai", model) {
    validateNotClosed.flatMap { _ =>
      // Transform options and messages for model-specific constraints
      for {
        transformed <- TransformationResult.transform(
          model,
          options,
          conversation.messages,
          dropUnsupported = true
        )
        transformedConversation = conversation.copy(messages = transformed.messages)
        chatOptions = prepareChatOptions(
          transformedConversation,
          transformed.options,
          transformed.requiresMaxCompletionTokens
        )
        completions <- Try(transport.getChatCompletions(model, chatOptions)).toEither.left
          .map { e =>
            logger.error(s"OpenAI completion failed for model $model", e)
            e.toLLMError
          }
      } yield convertFromOpenAIFormat(completions)
    }
  }(
    extractUsage = _.usage,
    estimateCost = usage =>
      org.llm4s.model.ModelRegistry.lookup(model).toOption.flatMap { meta =>
        meta.pricing.estimateCost(usage.promptTokens, usage.completionTokens)
      }
  )

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = withMetrics("openai", model) {
    validateNotClosed.flatMap { _ =>
      // Transform options and messages for model-specific constraints
      TransformationResult.transform(model, options, conversation.messages, dropUnsupported = true).flatMap {
        transformed =>
          val transformedConversation = conversation.copy(messages = transformed.messages)
          val chatOptions =
            prepareChatOptions(transformedConversation, transformed.options, transformed.requiresMaxCompletionTokens)

          if (transformed.requiresFakeStreaming) {
            executeFakeStreaming(chatOptions, onChunk)
          } else {
            executeNativeStreaming(chatOptions, onChunk)
          }
      }
    }
  }(
    extractUsage = _.usage,
    estimateCost = usage =>
      org.llm4s.model.ModelRegistry.lookup(model).toOption.flatMap { meta =>
        meta.pricing.estimateCost(usage.promptTokens, usage.completionTokens)
      }
  )

  override def close(): Unit =
    // Mark client as closed to prevent further operations.
    // Note: AzureOpenAIClient does not implement AutoCloseable,
    // so we only track the logical closed state for thread-safety.
    if (closed.compareAndSet(false, true)) {
      logger.debug(s"OpenAI client for model $model closed")
    }

  /**
   * Validates that the client is not closed before performing operations.
   *
   * Note: There is an inherent TOCTOU (time-of-check to time-of-use) gap between
   * this validation and the actual operation. This is acceptable because the
   * underlying AzureOpenAIClient does not implement AutoCloseable, so we only
   * track logical closed state. Operations may still succeed even if close()
   * is called concurrently, but subsequent operations will fail validation.
   *
   * @return Right(()) if client is open, Left(ConfigurationError) if closed
   */
  private def validateNotClosed: Result[Unit] =
    if (closed.get()) {
      Left(ConfigurationError(s"OpenAI client for model $model is already closed"))
    } else {
      Right(())
    }

  /**
   * Handles fake streaming for models that don't support native streaming.
   * Makes a regular completion call and emits the result as chunks.
   *
   * @param chatOptions prepared chat options for the API call
   * @param onChunk callback invoked for each chunk emitted
   * @return Result containing the completion or an error
   */
  private def executeFakeStreaming(
    chatOptions: ChatCompletionsOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    val attempt = Try(transport.getChatCompletions(model, chatOptions)).toEither.left
      .map { e =>
        logger.error(s"OpenAI fake streaming failed for model $model", e)
        e.toLLMError
      }

    attempt.flatMap { completions =>
      val completion = convertFromOpenAIFormat(completions)
      emitCompletionAsChunks(completion, onChunk)
      Right(completion)
    }
  }

  /**
   * Handles native streaming for models that support streaming.
   * Processes streaming chunks and accumulates the final completion.
   *
   * @param chatOptions prepared chat options for the API call
   * @param onChunk callback invoked for each chunk emitted
   * @return Result containing the completion or an error
   */
  private def executeNativeStreaming(
    chatOptions: ChatCompletionsOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    val accumulator = StreamingAccumulator.create()

    val attempt = Try {
      val stream = transport.getChatCompletionsStream(model, chatOptions)
      processStreamingResponse(stream, accumulator, onChunk)
    }.toEither.left.map { e =>
      logger.error(s"OpenAI native streaming failed for model $model", e)
      e.toLLMError
    }

    attempt.flatMap(_ => accumulator.toCompletion.map(_.copy(model = model)))
  }

  /**
   * Processes streaming response chunks from the OpenAI API.
   *
   * @param stream the streaming response from the API
   * @param accumulator accumulator for building the final completion
   * @param onChunk callback invoked for each chunk
   */
  private def processStreamingResponse(
    stream: IterableStream[ChatCompletions],
    accumulator: StreamingAccumulator,
    onChunk: StreamedChunk => Unit
  ): Unit =
    stream.forEach { chatCompletions =>
      Option(chatCompletions.getChoices)
        .filterNot(_.isEmpty)
        .foreach(_ => processStreamingChoice(chatCompletions, accumulator, onChunk))
    }

  /**
   * Processes a single streaming choice and emits chunks.
   *
   * @param chatCompletions the chat completions response containing the choice
   * @param accumulator accumulator for building the final completion
   * @param onChunk callback invoked for each chunk
   */
  private def processStreamingChoice(
    chatCompletions: ChatCompletions,
    accumulator: StreamingAccumulator,
    onChunk: StreamedChunk => Unit
  ): Unit = {
    val choice       = chatCompletions.getChoices.get(0)
    val delta        = choice.getDelta
    val toolCalls    = extractStreamingToolCalls(delta)
    val contentOpt   = Option(delta.getContent)
    val finishReason = Option(choice.getFinishReason).map(_.toString)
    val chunkId      = Option(chatCompletions.getId).getOrElse("")

    emitStreamingChunks(chunkId, contentOpt, toolCalls, finishReason, accumulator, onChunk)

    // Update token usage when streaming completes
    Option(choice.getFinishReason).foreach { _ =>
      Option(chatCompletions.getUsage).foreach { usage =>
        accumulator.updateTokens(usage.getPromptTokens, usage.getCompletionTokens)
      }
    }
  }

  /**
   * Emits streaming chunks for content and tool calls.
   *
   * @param chunkId the chunk identifier
   * @param contentOpt optional content text
   * @param toolCalls sequence of tool calls
   * @param finishReason optional finish reason
   * @param accumulator accumulator for building the final completion
   * @param onChunk callback invoked for each chunk
   */
  private def emitStreamingChunks(
    chunkId: String,
    contentOpt: Option[String],
    toolCalls: Seq[ToolCall],
    finishReason: Option[String],
    accumulator: StreamingAccumulator,
    onChunk: StreamedChunk => Unit
  ): Unit =
    if (toolCalls.isEmpty) {
      val chunk = StreamedChunk(
        id = chunkId,
        content = contentOpt,
        toolCall = None,
        finishReason = finishReason
      )
      accumulator.addChunk(chunk)
      onChunk(chunk)
    } else {
      // Emit first tool call with content
      val firstChunk = StreamedChunk(
        id = chunkId,
        content = contentOpt,
        toolCall = Some(toolCalls.head),
        finishReason = finishReason
      )
      accumulator.addChunk(firstChunk)
      onChunk(firstChunk)

      // Emit remaining tool calls
      toolCalls.drop(1).foreach { tc =>
        val extra = StreamedChunk(
          id = chunkId,
          content = None,
          toolCall = Some(tc),
          finishReason = None
        )
        accumulator.addChunk(extra)
        onChunk(extra)
      }
    }

  /**
   * Emits a completed completion as chunks (for fake streaming).
   *
   * @param completion the completion to emit as chunks
   * @param onChunk callback invoked for each chunk
   */
  private def emitCompletionAsChunks(
    completion: Completion,
    onChunk: StreamedChunk => Unit
  ): Unit = {
    val contentOpt = if (completion.content.nonEmpty) Some(completion.content) else None

    completion.toolCalls.headOption match {
      case Some(first) =>
        // Emit first tool call with content
        val chunk = StreamedChunk(
          id = completion.id,
          content = contentOpt,
          toolCall = Some(first),
          finishReason = Some("stop")
        )
        onChunk(chunk)

        // Emit remaining tool calls
        completion.toolCalls.drop(1).foreach { tc =>
          onChunk(
            StreamedChunk(
              id = completion.id,
              content = None,
              toolCall = Some(tc),
              finishReason = None
            )
          )
        }

      case None =>
        val chunk = StreamedChunk(
          id = completion.id,
          content = contentOpt,
          toolCall = None,
          finishReason = Some("stop")
        )
        onChunk(chunk)
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
   * @param useMaxCompletionTokens if true, use max_completion_tokens instead of max_tokens
   * @return configured ChatCompletionsOptions ready for API call
   */
  private def prepareChatOptions(
    conversation: Conversation,
    options: CompletionOptions,
    useMaxCompletionTokens: Boolean
  ): ChatCompletionsOptions = {
    // Convert conversation to Azure format
    val chatMessages = convertToOpenAIMessages(conversation)

    // Create chat options
    val chatOptions = new ChatCompletionsOptions(chatMessages)

    // Set options
    chatOptions.setTemperature(options.temperature.doubleValue())
    options.maxTokens.foreach { mt =>
      if (useMaxCompletionTokens)
        chatOptions.setMaxCompletionTokens(mt)
      else
        chatOptions.setMaxTokens(mt)
    }
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
  private def extractStreamingToolCalls(delta: ChatResponseMessage): Seq[ToolCall] =
    Option(delta.getToolCalls)
      .map(
        _.asScala.toSeq.collect { case ftc: ChatCompletionsFunctionToolCall =>
          val function = Option(ftc.getFunction)
          val rawArgs  = function.flatMap(f => Option(f.getArguments)).getOrElse("")
          ToolCall(
            id = ftc.getId,
            name = function.map(_.getName).getOrElse(""),
            arguments = parseStreamingArguments(rawArgs)
          )
        }
      )
      .getOrElse(Seq.empty)

  private def parseStreamingArguments(raw: String): ujson.Value =
    if (raw.isEmpty) ujson.Null else Try(ujson.read(raw)).getOrElse(ujson.Str(raw))

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
// Refactored to use idiomatic Scala collections instead of mutable java.util.ArrayList
  private def convertToOpenAIMessages(
    conversation: Conversation
  ): java.util.ArrayList[ChatRequestMessage] = {

    val scalaMessages =
      conversation.messages.map {
        case UserMessage(content) =>
          new ChatRequestUserMessage(content)

        case SystemMessage(content) =>
          new ChatRequestSystemMessage(content)

        case AssistantMessage(content, toolCalls) =>
          val msg = new ChatRequestAssistantMessage(content.getOrElse(""))

          if (toolCalls.nonEmpty) {
            val openAIToolCalls: java.util.List[ChatCompletionsToolCall] =
              toolCalls.map { tc =>
                val function = new FunctionCall(tc.name, tc.arguments.render())
                new ChatCompletionsFunctionToolCall(tc.id, function): ChatCompletionsToolCall
              }.asJava

            msg.setToolCalls(openAIToolCalls)
          }

          msg

        case ToolMessage(content, toolCallId) =>
          new ChatRequestToolMessage(content, toolCallId)
      }

    new java.util.ArrayList(scalaMessages.asJava)
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

  private[provider] def forTest(
    model: String,
    transport: OpenAIClientTransport,
    config: ProviderConfig,
    metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
  ): OpenAIClient =
    new OpenAIClient(model, transport, config, metrics)

  /**
   * Creates an OpenAI client for direct OpenAI API access.
   *
   * @param config OpenAI configuration with API key, model, and base URL
   * @param metrics metrics collector for observability
   * @return Right(OpenAIClient) on success, Left(LLMError) if client creation fails
   */
  def apply(config: OpenAIConfig, metrics: org.llm4s.metrics.MetricsCollector): Result[OpenAIClient] =
    Try(new OpenAIClient(config, metrics)).toResult

  /**
   * Convenience overload with noop metrics.
   */
  def apply(config: OpenAIConfig): Result[OpenAIClient] =
    Try(new OpenAIClient(config, org.llm4s.metrics.MetricsCollector.noop)).toResult

  /**
   * Creates an OpenAI client for Azure OpenAI service.
   *
   * @param config Azure configuration with API key, model, endpoint, and API version
   * @param metrics metrics collector for observability
   * @return Right(OpenAIClient) on success, Left(LLMError) if client creation fails
   */
  def apply(config: AzureConfig, metrics: org.llm4s.metrics.MetricsCollector): Result[OpenAIClient] =
    Try(new OpenAIClient(config, metrics)).toResult

  /**
   * Convenience overload with noop metrics.
   */
  def apply(config: AzureConfig): Result[OpenAIClient] =
    Try(new OpenAIClient(config, org.llm4s.metrics.MetricsCollector.noop)).toResult
}

private[provider] object OpenAIClientTransport {
  def azure(client: AzureOpenAIClient): OpenAIClientTransport =
    new OpenAIClientTransport {
      override def getChatCompletions(model: String, options: ChatCompletionsOptions): ChatCompletions =
        client.getChatCompletions(model, options)

      override def getChatCompletionsStream(
        model: String,
        options: ChatCompletionsOptions
      ): IterableStream[ChatCompletions] =
        client.getChatCompletionsStream(model, options)
    }
}
