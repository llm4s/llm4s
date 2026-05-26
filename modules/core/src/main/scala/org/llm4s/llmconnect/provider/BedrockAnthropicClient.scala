package org.llm4s.llmconnect.provider

import com.anthropic.bedrock.backends.BedrockBackend
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.{
  MessageCreateParams,
  RawMessageStreamEvent,
  ThinkingConfigEnabled
}
import software.amazon.awssdk.regions.Region

import scala.collection.mutable
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.{ BedrockAnthropicConfig, ProviderConfig }
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.ProviderResultOps.*
import org.llm4s.llmconnect.streaming.*
import org.llm4s.model.{ ModelRegistryService, RequestTransformer, TransformationResult }
import org.llm4s.types.Result
import org.llm4s.error.{ AuthenticationError, RateLimitError, ValidationError }
import org.llm4s.error.ThrowableOps.*

import java.time.Instant
import scala.util.Try

/**
 * [[org.llm4s.llmconnect.LLMClient]] implementation for Anthropic Claude
 * models accessed through AWS Bedrock.
 *
 * Uses the Anthropic Java SDK's Bedrock transport (`AnthropicBedrockOkHttpClient`)
 * which authenticates via the AWS Default Credential Provider Chain. No explicit
 * API key is required — credentials are resolved from environment variables,
 * `~/.aws/credentials`, IAM roles, etc.
 *
 * Message format adaptations and streaming event handling are identical to
 * [[AnthropicClient]]; both mix in [[AnthropicMessageSupport]] for shared logic.
 *
 * @param config  `BedrockAnthropicConfig` carrying the region and model name.
 * @param metrics Receives per-call latency and token-usage events.
 *                Defaults to `MetricsCollector.noop`.
 */
class BedrockAnthropicClient(
  config: BedrockAnthropicConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient
    with AnthropicMessageSupport {

  private val providerConfig: ProviderConfig = config

  private val client = AnthropicOkHttpClient
    .builder()
    .backend(
      BedrockBackend
        .builder()
        .region(Region.of(config.region))
        .build()
    )
    .build()

  protected def clientDescription: String = s"Bedrock Anthropic client for model ${config.model}"
  protected def providerName: String      = "bedrock-anthropic"
  protected def modelName: String         = config.model

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    TransformationResult
      .transform(
        config.model,
        options,
        conversation.messages,
        dropUnsupported = true,
        RequestTransformer.default(registryService)
      )
      .flatMap { transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)

        val paramsBuilder = MessageCreateParams
          .builder()
          .model(config.model)
        applySamplingParameters(paramsBuilder, transformed.options)

        val maxTokens = transformed.options.maxTokens.getOrElse(2048)
        paramsBuilder.maxTokens(maxTokens)

        transformed.options.effectiveBudgetTokens.foreach { budgetTokens =>
          val effectiveBudget = clampBudgetTokens(budgetTokens, maxTokens)
          paramsBuilder.thinking(
            ThinkingConfigEnabled.builder().budgetTokens(effectiveBudget.toLong).build()
          )
        }

        if (transformed.options.tools.nonEmpty) {
          transformed.options.tools.foreach(tool => paramsBuilder.addTool(convertToolToAnthropicTool(tool)))
        }

        addMessagesToParams(transformedConversation, paramsBuilder)

        val messageParams = paramsBuilder.build()
        val requestBody   = serializeRequestBody(messageParams)

        val messageService = client.messages()
        val attempt = Try(messageService.create(messageParams)).toEither.left.map {
          case e: com.anthropic.errors.UnauthorizedException         => AuthenticationError("bedrock-anthropic", e.getMessage)
          case _: com.anthropic.errors.RateLimitException            => RateLimitError("bedrock-anthropic")
          case e: com.anthropic.errors.AnthropicInvalidDataException => ValidationError("input", e.getMessage)
          case e: Exception                                          => e.toLLMError
        }
        attempt
          .map { response =>
            val completionResult = Right(convertFromAnthropicResponse(response, config.model))
            recordExchange(startedAt, requestBody, Some(serializeResponseBody(response)), completionResult)
            completionResult
          }
          .tapLeft(error => recordExchange(startedAt, requestBody, None, Left(error)))
          .flatten
      }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    TransformationResult
      .transform(
        config.model,
        options,
        conversation.messages,
        dropUnsupported = true,
        RequestTransformer.default(registryService)
      )
      .flatMap { transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)

        val paramsBuilder = MessageCreateParams
          .builder()
          .model(config.model)
        applySamplingParameters(paramsBuilder, transformed.options)

        val maxTokens = transformed.options.maxTokens.getOrElse(2048)
        paramsBuilder.maxTokens(maxTokens)

        transformed.options.effectiveBudgetTokens.foreach { budgetTokens =>
          val effectiveBudget = clampBudgetTokens(budgetTokens, maxTokens)
          paramsBuilder.thinking(
            ThinkingConfigEnabled.builder().budgetTokens(effectiveBudget.toLong).build()
          )
        }

        if (transformed.options.tools.nonEmpty)
          transformed.options.tools.foreach(t => paramsBuilder.addTool(convertToolToAnthropicTool(t)))

        addMessagesToParams(transformedConversation, paramsBuilder)

        val messageParams = paramsBuilder.build()
        val requestBody   = serializeRequestBody(messageParams)

        val accumulator                      = StreamingAccumulator.create()
        var currentMessageId: Option[String] = None
        val blockIndexToToolId               = mutable.Map.empty[Long, String]
        val rawStream                        = StringBuilder()

        val attempt = Try {
          val messageService = client.messages()
          val streamResponse = messageService.createStreaming(messageParams)

          import scala.jdk.StreamConverters._
          val stream: Iterator[RawMessageStreamEvent] = streamResponse.stream().toScala(Iterator)
          val loopTry = Try {
            stream.foreach { event =>
              rawStream.append(serializeStreamEvent(event)).append('\n')

              val messageStartOpt = event.messageStart()
              if (messageStartOpt != null && messageStartOpt.isPresent) {
                val msgStart = messageStartOpt.get()
                currentMessageId = Some(msgStart.message().id())
              }

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
          Try(streamResponse.close());
          loopTry.get
        }.toEither.left
          .map {
            case e: com.anthropic.errors.UnauthorizedException         => AuthenticationError("bedrock-anthropic", e.getMessage)
            case _: com.anthropic.errors.RateLimitException            => RateLimitError("bedrock-anthropic")
            case e: com.anthropic.errors.AnthropicInvalidDataException => ValidationError("input", e.getMessage)
            case e: Exception                                          => e.toLLMError
          }

        attempt
          .flatMap(_ =>
            accumulator.toCompletion.map { c =>
              val cost       = c.usage.flatMap(u => CostEstimator.estimate(config.model, u))
              val completion = c.copy(model = config.model, estimatedCost = cost)
              recordExchange(startedAt, requestBody, Some(rawStream.result()), Right(completion))
              completion
            }
          )
          .tapLeft(error =>
            recordExchange(startedAt, requestBody, Option.when(rawStream.nonEmpty)(rawStream.result()), Left(error))
          )
      }
  }

  override def getContextWindow(): Int = providerConfig.contextWindow

  override def getReserveCompletion(): Int = providerConfig.reserveCompletion

  override protected def releaseResources(): Unit =
    client.close()

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
}

object BedrockAnthropicClient {
  import org.llm4s.types.TryOps

  def apply(
    config: BedrockAnthropicConfig,
    metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
  )(using ModelRegistryService): Result[BedrockAnthropicClient] =
    Try(new BedrockAnthropicClient(config, metrics)).toResult

  def apply(
    config: BedrockAnthropicConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[BedrockAnthropicClient] =
    Try(new BedrockAnthropicClient(config, metrics, exchangeLogging)).toResult
}
