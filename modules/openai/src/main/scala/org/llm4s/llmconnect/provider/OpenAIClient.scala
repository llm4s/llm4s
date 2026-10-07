package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable

import com.openai.azure.credential.AzureApiKeyCredential
import com.openai.azure.{ AzureOpenAIServiceVersion, AzureUrlPathMode }
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.client.{ OpenAIClient => SdkClient }
import com.openai.core.{ JsonField, ObjectMappers, RequestOptions }
import com.openai.core.http.StreamResponse
import com.openai.errors.{ OpenAIIoException, OpenAIServiceException }
import com.openai.models.chat.completions.{
  ChatCompletion,
  ChatCompletionAssistantMessageParam,
  ChatCompletionChunk,
  ChatCompletionCreateParams,
  ChatCompletionMessage,
  ChatCompletionMessageFunctionToolCall,
  ChatCompletionMessageParam,
  ChatCompletionStreamOptions,
  ChatCompletionSystemMessageParam,
  ChatCompletionToolMessageParam,
  ChatCompletionUserMessageParam
}
import com.openai.models.completions.CompletionUsage
import com.openai.models.{ ResponseFormatJsonObject, ResponseFormatJsonSchema }
import org.llm4s.error.LLMError
import org.llm4s.error.ThrowableOps._
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.{ AzureConfig, OpenAIConfig, ProviderConfig, ProviderTimeouts }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.OpenAICompatibleClient.StreamToolCalls
import org.llm4s.llmconnect.provider.ProviderResultOps.*
import org.llm4s.llmconnect.streaming._
import org.llm4s.model.{ ModelRegistryService, TransformationResult }
import org.llm4s.toolapi.{ OpenAIToolHelper, ToolRegistry }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.slf4j.{ Logger, LoggerFactory }

import java.time.Instant
import scala.annotation.nowarn
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.{ Try, Using }

/**
 * The two calls [[OpenAIClient]] makes. The model travels in `params`; for Azure it is the
 * deployment name, which the SDK puts in the request path.
 */
private[provider] trait OpenAIClientTransport {
  def createChatCompletion(params: ChatCompletionCreateParams): ChatCompletion
  def createChatCompletionStream(params: ChatCompletionCreateParams): StreamResponse[ChatCompletionChunk]

  /** Releases the transport's connections and threads. */
  def close(): Unit = ()
}

/**
 * LLMClient implementation for OpenAI, Azure OpenAI and Requesty, built on OpenAI's official
 * Java SDK (`com.openai:openai-java`).
 *
 * Handles message conversion between llm4s format and the chat-completions format, completion
 * requests, streaming responses, and tool calling (function calling). OpenAI and Requesty
 * are reached through an [[org.llm4s.llmconnect.config.OpenAIConfig]]; Azure OpenAI through an
 * [[org.llm4s.llmconnect.config.AzureConfig]], using the SDK's Azure support (an `api-key`
 * header, the deployment in the path and an `api-version` query parameter).
 *
 * Until [[https://github.com/llm4s/llm4s/issues/1132 #1132]] this client ran on Microsoft's
 * `com.azure:azure-ai-openai` SDK, which Microsoft has deprecated in favour of `openai-java`.
 *
 * == Reasoning ==
 *
 * `CompletionOptions.reasoning` is sent as `reasoning_effort` (`Low`, `Medium` and `High` as
 * `low`, `medium` and `high`; `None` sends nothing, leaving the model's default) to OpenAI
 * reasoning models - the o-series and the gpt-5 family, as the model registry flags them - and
 * never to other models, which reject it. On Azure a deployment name the registry cannot
 * resolve gets it whenever it is asked for. Reasoning models get `max_completion_tokens` rather
 * than `max_tokens`, and no `temperature`, `top_p` or penalties. The reasoning tokens a
 * response reports (`completion_tokens_details.reasoning_tokens`) become
 * `TokenUsage.thinkingTokens`, streamed or not; they are part of `completionTokens`, not added
 * to it. See [[OpenAIReasoning]].
 *
 * == Streamed usage ==
 *
 * Streaming requests set `stream_options.include_usage`, so the service reports token usage on
 * a final chunk with no choices, and a streamed `Completion` carries `usage` and
 * `estimatedCost` (Azure from api-version `2024-09-01-preview` on).
 *
 * For Anthropic Claude models with extended thinking, use `AnthropicClient` (in `llm4s-anthropic`), which has
 * full support for the `thinking` parameter with `budget_tokens`.
 *
 * @param model the model identifier (e.g., "gpt-4o"); for Azure, the deployment name
 * @param transport the SDK calls this client makes
 * @param config provider configuration containing context window and reserve completion settings
 * @param metrics metrics collector for observability (default: noop)
 * @param provider the provider this client serves - `openai`, `azure` or `requesty` - which labels
 *                 its metrics, exchange log and errors
 */
@Stable
class OpenAIClient private[provider] (
  private val model: String,
  private val transport: OpenAIClientTransport,
  private val config: ProviderConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector,
  exchangeLogging: ProviderExchangeLogging,
  provider: ProviderId = OpenAIProvider.id
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  private lazy val logger: Logger = LoggerFactory.getLogger(getClass)

  private val displayName: String = OpenAIClient.displayName(provider)

  protected def clientDescription: String = s"$displayName client for model $model"
  protected def providerName: String      = provider.asString
  protected def modelName: String         = model

  /**
   * Creates an OpenAI client for direct OpenAI API access (or Requesty, or any other
   * OpenAI-compatible base URL).
   *
   * @param config OpenAI configuration with API key and base URL
   * @param metrics metrics collector (default: noop)
   */
  def this(config: OpenAIConfig, metrics: org.llm4s.metrics.MetricsCollector)(using ModelRegistryService) =
    this(config.model, OpenAIClientTransport.openAI(config), config, metrics, ProviderExchangeLogging.Disabled)

  def this(
    config: OpenAIConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService) =
    this(config.model, OpenAIClientTransport.openAI(config), config, metrics, exchangeLogging)

  /**
   * Creates an OpenAI client for Azure OpenAI service.
   *
   * @param config Azure configuration with API key, endpoint, and API version
   * @param metrics metrics collector (default: noop)
   */
  def this(config: AzureConfig, metrics: org.llm4s.metrics.MetricsCollector)(using ModelRegistryService) =
    this(
      config.model,
      OpenAIClientTransport.azure(config),
      config,
      metrics,
      ProviderExchangeLogging.Disabled,
      config.providerId
    )

  def this(
    config: AzureConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService) =
    this(config.model, OpenAIClientTransport.azure(config), config, metrics, exchangeLogging, config.providerId)

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    // Transform options and messages for model-specific constraints
    val result = for {
      transformed <- TransformationResult.transform(
        model,
        options,
        conversation.messages,
        OpenAIModelRules.transformer(registryService),
        dropUnsupported = true
      )
      transformedConversation = conversation.copy(messages = transformed.messages)
      params <- buildParams(
        transformedConversation,
        transformed.options,
        OpenAIModelRules.requiresMaxCompletionTokens(model),
        streaming = false
      )
      response <- call("completion")(transport.createChatCompletion(params))
      completion = convertFromOpenAIFormat(response)
      _ = recordExchange(
        startedAt,
        Some(serializeParams(params)),
        Some(serialize(response)),
        Right(completion)
      )
    } yield completion

    result.tapLeft(error => recordExchange(startedAt, None, None, Left(error)))
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    // Transform options and messages for model-specific constraints.
    // Only a failure before the call is recorded here: executeFakeStreaming/executeNativeStreaming
    // already record their own exchange on both success and failure.
    TransformationResult
      .transform(
        model,
        options,
        conversation.messages,
        OpenAIModelRules.transformer(registryService),
        dropUnsupported = true
      )
      .flatMap { transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)
        // A faked stream is an ordinary completion call, which must not carry `stream_options`.
        buildParams(
          transformedConversation,
          transformed.options,
          OpenAIModelRules.requiresMaxCompletionTokens(model),
          streaming = !transformed.requiresFakeStreaming
        ).map(params => (transformed, params))
      }
      .tapLeft(error => recordExchange(startedAt, None, None, Left(error)))
      .flatMap { (transformed, params) =>
        val requestBody = serializeParams(params)
        if (transformed.requiresFakeStreaming) executeFakeStreaming(startedAt, requestBody, params, onChunk)
        else executeNativeStreaming(startedAt, requestBody, params, onChunk)
      }
  }

  override protected def releaseResources(): Unit = {
    Try(transport.close()).failed.foreach(e => logger.warn(s"Closing the $clientDescription failed", e))
    logger.debug(s"$clientDescription closed")
  }

  /** Runs one SDK call, logging and mapping any failure to an [[LLMError]]. */
  private def call[A](what: String)(body: => A): Result[A] =
    Try(body).toEither.left.map { e =>
      logger.error(s"$displayName $what failed for model $model", e)
      OpenAIClient.mapError(e, providerName)
    }

  /**
   * Handles fake streaming for models that don't support native streaming.
   * Makes a regular completion call and emits the result as chunks.
   */
  private def executeFakeStreaming(
    startedAt: Instant,
    requestBody: String,
    params: ChatCompletionCreateParams,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    call("fake streaming")(transport.createChatCompletion(params))
      .map { response =>
        val completion = convertFromOpenAIFormat(response)
        emitCompletionAsChunks(completion, onChunk)
        recordExchange(startedAt, Some(requestBody), Some(serialize(response)), Right(completion))
        completion
      }
      .tapLeft(error => recordExchange(startedAt, Some(requestBody), None, Left(error)))

  /**
   * Handles native streaming for models that support streaming.
   * Processes streaming chunks and accumulates the final completion.
   */
  private def executeNativeStreaming(
    startedAt: Instant,
    requestBody: String,
    params: ChatCompletionCreateParams,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    val accumulator = StreamingAccumulator.create()
    val toolCalls   = new StreamToolCalls
    val rawStream   = StringBuilder()
    var usage       = Option.empty[TokenUsage]

    val attempt = call("native streaming") {
      Using.resource(transport.createChatCompletionStream(params)) { response =>
        response.stream().forEach { chunk =>
          rawStream.append(serialize(chunk)).append('\n')
          processStreamingChunk(chunk, toolCalls, accumulator, onChunk).foreach(u => usage = Some(u))
        }
      }
    }

    recordingExchange(startedAt, Some(requestBody)) {
      attempt.flatMap(_ =>
        // `StreamingAccumulator.toCompletion` puts the tool calls on the message only; a
        // non-streaming `complete` also reports them as `Completion.toolCalls`, so this does too.
        // The usage is the service's own report, which carries the reasoning and cached-token
        // breakdown and the service's total; the accumulator only knows the two counts.
        accumulator.toCompletion.map { c =>
          val finalUsage = usage.orElse(c.usage)
          val cost       = finalUsage.flatMap(u => CostEstimator.estimate(model, u))
          c.withModel(model).withToolCalls(c.message.toolCalls.toList).withUsage(finalUsage).withEstimatedCost(cost)
        }
      )
    }(
      successResponse = _ => Some(rawStream.result()),
      failureResponse = Option.when(rawStream.nonEmpty)(rawStream.result())
    )
  }

  /**
   * Runs a completion-producing operation and records the provider exchange exactly
   * once, regardless of whether it succeeds or fails.
   */
  private def recordingExchange(
    startedAt: Instant,
    requestBody: Option[String]
  )(operation: => Result[Completion])(
    successResponse: Completion => Option[String],
    failureResponse: => Option[String]
  ): Result[Completion] =
    operation
      .tapRight(completion => recordExchange(startedAt, requestBody, successResponse(completion), Right(completion)))
      .tapLeft(error => recordExchange(startedAt, requestBody, failureResponse, Left(error)))

  /**
   * Processes one streamed chunk: emits its content and tool-call deltas, and returns any
   * token usage it carries. A chunk with no choices (Azure's prompt-filter chunk, or the
   * usage-only final chunk that `stream_options.include_usage` asks for) emits nothing.
   */
  private def processStreamingChunk(
    chunk: ChatCompletionChunk,
    toolCalls: StreamToolCalls,
    accumulator: StreamingAccumulator,
    onChunk: StreamedChunk => Unit
  ): Option[TokenUsage] = {
    known(chunk._choices()).flatMap(_.asScala.headOption).foreach { choice =>
      val delta        = known(choice._delta())
      val calls        = delta.map(d => streamingToolCalls(d, toolCalls)).getOrElse(Seq.empty)
      val contentOpt   = delta.flatMap(d => known(d._content()))
      val finishReason = known(choice._finishReason()).map(_.asString())
      val chunkId      = known(chunk._id()).getOrElse("")

      emitStreamingChunks(chunkId, contentOpt, calls, finishReason, accumulator, onChunk)
    }

    known(chunk._usage()).map { usage =>
      val tokens = toTokenUsage(usage)
      accumulator.updateTokens(tokens.promptTokens, tokens.completionTokens)
      tokens
    }
  }

  /**
   * The tool-call deltas in one streamed chunk, each paired with its raw argument fragment.
   *
   * A streamed tool call is split across deltas: the first carries its `id`, `name` and the
   * start of its arguments; each continuation carries only its `index` and the next fragment,
   * and calls can be interleaved. Continuations are matched by `index` through
   * [[OpenAICompatibleClient.StreamToolCalls]], so every chunk names its call. (On the Azure
   * SDK they were keyed by `id`, which a continuation does not carry, and their arguments were
   * lost.)
   */
  private def streamingToolCalls(
    delta: ChatCompletionChunk.Choice.Delta,
    state: StreamToolCalls
  ): Seq[(ToolCall, String)] =
    known(delta._toolCalls()).map(_.asScala.toSeq).getOrElse(Seq.empty).zipWithIndex.map { (call, position) =>
      val function = known(call._function())
      val raw      = function.flatMap(f => known(f._arguments())).getOrElse("")
      val (id, name) = state.resolve(
        index = known(call._index()).map(_.intValue).getOrElse(position),
        id = known(call._id()).filter(_.nonEmpty),
        name = function.flatMap(f => known(f._name())).filter(_.nonEmpty)
      )
      (ToolCall(id, name, StreamingToolArgumentParser.parse(raw)), raw)
    }

  /**
   * Emits streaming chunks for content and tool calls: one per tool call, the first also
   * carrying the text and finish reason.
   *
   * The accumulator gets each argument fragment verbatim, because it concatenates them; the
   * parsed form handed to `onChunk` cannot be concatenated safely (a fragment that is itself
   * valid JSON, such as `"Paris"`, parses to the bare string and would lose its quotes).
   */
  private def emitStreamingChunks(
    chunkId: String,
    contentOpt: Option[String],
    toolCalls: Seq[(ToolCall, String)],
    finishReason: Option[String],
    accumulator: StreamingAccumulator,
    onChunk: StreamedChunk => Unit
  ): Unit = {
    def emit(chunk: StreamedChunk, raw: String): Unit = {
      accumulator.addChunk(chunk.withToolCall(chunk.toolCall.map(_.copy(arguments = ujson.Str(raw)))))
      onChunk(chunk)
    }

    emit(
      StreamedChunk(id = chunkId, content = contentOpt, toolCall = toolCalls.headOption.map(_._1), finishReason),
      toolCalls.headOption.fold("")(_._2)
    )
    toolCalls.drop(1).foreach { (tc, raw) =>
      emit(StreamedChunk(id = chunkId, content = None, toolCall = Some(tc), finishReason = None), raw)
    }
  }

  /**
   * Emits a completed completion as chunks (for fake streaming).
   */
  private def emitCompletionAsChunks(
    completion: Completion,
    onChunk: StreamedChunk => Unit
  ): Unit = {
    val contentOpt = if (completion.content.nonEmpty) Some(completion.content) else None

    onChunk(
      StreamedChunk(
        id = completion.id,
        content = contentOpt,
        toolCall = completion.toolCalls.headOption,
        finishReason = Some("stop")
      )
    )
    completion.toolCalls.drop(1).foreach { tc =>
      onChunk(StreamedChunk(id = completion.id, content = None, toolCall = Some(tc), finishReason = None))
    }
  }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  /**
   * Whether this client's model is an OpenAI reasoning model, decided once: the registry
   * answers from static metadata, so the answer cannot change between calls.
   */
  private lazy val reasoningSupport: OpenAIReasoning.Support = OpenAIReasoning.support(model, registryService)

  /**
   * The `reasoning_effort` to send for `options`, if any.
   *
   * Sent for a reasoning model when a level other than `ReasoningEffort.None` is asked for, and
   * never for a model known not to reason, which OpenAI would reject. On Azure, `model` is a
   * deployment name, which may say nothing about the model behind it: a deployment the registry
   * cannot resolve gets the parameter whenever reasoning is asked for, since asking is the only
   * sign the caller has a reasoning model deployed.
   */
  private def reasoningEffortFor(options: CompletionOptions): Option[com.openai.models.ReasoningEffort] =
    options.reasoning.flatMap(OpenAIReasoning.toSdk).filter { _ =>
      reasoningSupport match {
        case OpenAIReasoning.Support.Reasoning    => true
        case OpenAIReasoning.Support.NonReasoning => false
        case OpenAIReasoning.Support.Unknown      => config.isInstanceOf[AzureConfig]
      }
    }

  /**
   * Builds the chat-completions request from conversation and completion options.
   *
   * Applies temperature, token limits, penalties, reasoning effort, tools and response format.
   * Shared between complete() and streamComplete().
   *
   * For a reasoning model - one the registry flags as such, or an Azure deployment sent a
   * reasoning effort - the request follows OpenAI's reasoning-model rules: the token limit goes
   * in `max_completion_tokens` (`max_tokens` is rejected), and `temperature`, `top_p` and the
   * penalties are left out, since those models reject any non-default value.
   *
   * @param useMaxCompletionTokens if true, use max_completion_tokens instead of max_tokens
   * @param streaming if true, ask for token usage on the stream's final chunk
   */
  private def buildParams(
    conversation: Conversation,
    options: CompletionOptions,
    useMaxCompletionTokens: Boolean,
    streaming: Boolean
  ): Result[ChatCompletionCreateParams] =
    Try {
      val effort         = reasoningEffortFor(options)
      val reasoningModel = reasoningSupport == OpenAIReasoning.Support.Reasoning || effort.isDefined

      val builder = ChatCompletionCreateParams
        .builder()
        .model(model)
        .messages(convertToOpenAIMessages(conversation).asJava)

      if (reasoningModel && OpenAIReasoning.restrictsSampling(model))
        logger.debug(s"$displayName model $model is a reasoning model: not sending temperature, top_p or penalties")
      else
        builder
          .temperature(options.temperature.doubleValue())
          .presencePenalty(options.presencePenalty.doubleValue())
          .frequencyPenalty(options.frequencyPenalty.doubleValue())
          .topP(options.topP.doubleValue())

      effort.foreach(e => builder.reasoningEffort(e))

      options.maxTokens.foreach { mt =>
        if (useMaxCompletionTokens || reasoningModel) builder.maxCompletionTokens(mt.toLong)
        else OpenAIClient.setMaxTokens(builder, mt.toLong)
      }

      if (streaming && OpenAIClient.acceptsStreamUsage(config))
        builder.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())

      if (options.tools.nonEmpty) OpenAIToolHelper.addToolsToParams(new ToolRegistry(options.tools), builder)

      options.responseFormat.foreach {
        case ResponseFormat.Json =>
          builder.responseFormat(ResponseFormatJsonObject.builder().build())
        case js: ResponseFormat.JsonSchema =>
          val schema = ObjectMappers
            .jsonMapper()
            .readValue(js.schema.render(), classOf[ResponseFormatJsonSchema.JsonSchema.Schema])
          builder.responseFormat(
            ResponseFormatJsonSchema
              .builder()
              .jsonSchema(
                ResponseFormatJsonSchema.JsonSchema.builder().name(js.name).schema(schema).strict(js.strict).build()
              )
              .build()
          )
      }

      builder.build()
    }.toEither.left.map(_.toLLMError)

  /**
   * Converts an llm4s Conversation to chat-completions request messages.
   */
  private def convertToOpenAIMessages(conversation: Conversation): Seq[ChatCompletionMessageParam] =
    conversation.messages.map {
      case UserMessage(content) =>
        ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(content).build())

      case SystemMessage(content) =>
        ChatCompletionMessageParam.ofSystem(ChatCompletionSystemMessageParam.builder().content(content).build())

      case AssistantMessage(content, toolCalls) =>
        val msg = ChatCompletionAssistantMessageParam.builder().content(content.getOrElse(""))
        toolCalls.foreach { tc =>
          msg.addToolCall(
            ChatCompletionMessageFunctionToolCall
              .builder()
              .id(tc.id)
              .function(
                ChatCompletionMessageFunctionToolCall.Function
                  .builder()
                  .name(tc.name)
                  .arguments(tc.arguments.render())
                  .build()
              )
              .build()
          )
        }
        ChatCompletionMessageParam.ofAssistant(msg.build())

      case ToolMessage(content, toolCallId) =>
        ChatCompletionMessageParam.ofTool(
          ChatCompletionToolMessageParam.builder().content(content).toolCallId(toolCallId).build()
        )
    }

  /**
   * Converts a chat-completions response to llm4s Completion format.
   *
   * Reads the first choice, including content, tool calls, token usage and estimated cost.
   * Fields are read leniently: a field the response omits is treated as absent rather than
   * failing the whole call.
   */
  private def convertFromOpenAIFormat(response: ChatCompletion): Completion = {
    val message   = known(response._choices()).flatMap(_.asScala.headOption).flatMap(c => known(c._message()))
    val toolCalls = message.map(extractToolCalls).getOrElse(Seq.empty)
    val content   = message.flatMap(m => known(m._content())).getOrElse("")
    val assistantMessage =
      AssistantMessage(contentOpt = if (content.isEmpty) None else Some(content), toolCalls = toolCalls)

    val usage = known(response._usage()).map(toTokenUsage)

    // Estimate cost using CostEstimator
    val cost = usage.flatMap(u => CostEstimator.estimate(this.model, u))

    Completion(
      id = known(response._id()).getOrElse(""),
      created = known(response._created()).fold(0L)(_.longValue),
      content = content,
      model = known(response._model()).getOrElse(model),
      message = assistantMessage,
      toolCalls = toolCalls.toList,
      usage = usage,
      estimatedCost = cost
    )
  }

  private def toTokenUsage(u: CompletionUsage): TokenUsage = {
    val promptTokens     = known(u._promptTokens()).fold(0)(_.intValue)
    val completionTokens = known(u._completionTokens()).fold(0)(_.intValue)
    TokenUsage(
      promptTokens = promptTokens,
      completionTokens = completionTokens,
      totalTokens = known(u._totalTokens()).fold(promptTokens + completionTokens)(_.intValue),
      thinkingTokens = known(u._completionTokensDetails()).flatMap(d => known(d._reasoningTokens())).map(_.intValue),
      cachedTokens = known(u._promptTokensDetails()).flatMap(d => known(d._cachedTokens())).map(_.intValue)
    )
  }

  /**
   * Extracts function tool calls from a response message, parsing each one's arguments once.
   * A call whose arguments are not valid JSON is dropped.
   */
  private def extractToolCalls(message: ChatCompletionMessage): Seq[ToolCall] =
    known(message._toolCalls())
      .map(_.asScala.toSeq)
      .getOrElse(Seq.empty)
      .filter(_.isFunction)
      .map(_.asFunction())
      .flatMap { ftc =>
        val function = known(ftc._function())
        function.flatMap(f => known(f._arguments())).flatMap(raw => Try(ujson.read(raw)).toOption).map { args =>
          ToolCall(
            id = known(ftc._id()).getOrElse(""),
            name = function.flatMap(f => known(f._name())).getOrElse(""),
            arguments = args
          )
        }
      }

  private def recordExchange(
    startedAt: Instant,
    requestBody: Option[String],
    responseBody: Option[String],
    result: Result[Completion]
  ): Unit =
    ProviderExchangeRecorder.record(
      exchangeLogging = exchangeLogging,
      provider = providerName,
      model = Some(model),
      startedAt = startedAt,
      requestBody = requestBody.getOrElse(""),
      responseBody = responseBody,
      result = result
    )

  private def known[T](field: JsonField[T]): Option[T] = field.asKnown().toScala

  private def serializeParams(params: ChatCompletionCreateParams): String = serialize(params._body())

  private def serialize(value: AnyRef): String =
    Try(ObjectMappers.jsonMapper().writeValueAsString(value)).getOrElse("")
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
    metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
    exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
    provider: ProviderId = OpenAIProvider.id
  )(using ModelRegistryService): OpenAIClient =
    new OpenAIClient(model, transport, config, metrics, exchangeLogging, provider)

  /**
   * A client for an OpenAI-compatible `config` that labels its metrics, exchange log and errors
   * with `provider` rather than the config's `providerId`. Requesty builds its client through
   * this, so an [[OpenAIConfig]] built by hand - whose `providerId` is inferred from the base URL
   * and reads `openai` - is still labelled `requesty`.
   */
  private[provider] def forProvider(
    config: OpenAIConfig,
    provider: ProviderId,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[OpenAIClient] =
    Try(
      new OpenAIClient(config.model, OpenAIClientTransport.openAI(config), config, metrics, exchangeLogging, provider)
    ).toResult

  /** How log lines and the "already closed" error name the provider. */
  private def displayName(provider: ProviderId): String = provider.asString match {
    case "openai"   => "OpenAI"
    case "azure"    => "Azure OpenAI"
    case "requesty" => "Requesty"
    case other      => other
  }

  /**
   * Maps an SDK failure to an [[LLMError]]. An HTTP error from the service keeps its status
   * code, body and headers, so a 401 becomes an `AuthenticationError`, a 429 a `RateLimitError`
   * carrying its `Retry-After`, and so on; an I/O failure is mapped by its cause, so a timeout
   * stays a `NetworkError`.
   */
  private[provider] def mapError(e: Throwable, provider: String): LLMError = e match {
    case service: OpenAIServiceException =>
      HttpErrorMapper
        .mapHttpError(
          service.statusCode(),
          Try(service.body().toString).getOrElse(""),
          provider,
          Try(headerMap(service.headers())).getOrElse(Map.empty)
        )
        .left
        .getOrElse(service.toLLMError)
    case io: OpenAIIoException if io.getCause != null => io.getCause.toLLMError
    case other                                        => other.toLLMError
  }

  /** The SDK's response headers as the multi-valued map `HttpErrorMapper` reads. */
  private def headerMap(headers: com.openai.core.http.Headers): Map[String, Seq[String]] =
    headers.names().asScala.map(name => name -> headers.values(name).asScala.toSeq).toMap

  /**
   * Whether a streaming request may carry `stream_options.include_usage`, which makes the service
   * send token usage on a final chunk with no choices.
   *
   * OpenAI and OpenAI-compatible endpoints take it. Azure OpenAI takes it from api-version
   * `2024-09-01-preview` on, which includes the GA `2024-10-21`, the default
   * `2025-01-01-preview` and the unified v1 API; an older, date-versioned `apiVersion` rejects
   * the unknown parameter, so it is not sent there and a stream reports no usage, as before.
   */
  private[provider] def acceptsStreamUsage(config: ProviderConfig): Boolean = config match {
    case azure: AzureConfig =>
      val wire = OpenAIClientTransport.azureApiVersionWire(azure.apiVersion)
      !wire.matches("\\d{4}-\\d{2}-\\d{2}.*") || wire.take(10) >= OpenAIClient.FirstAzureStreamUsageVersion
    case _ => true
  }

  /** The first Azure OpenAI api-version to accept `stream_options`. */
  private val FirstAzureStreamUsageVersion = "2024-09-01"

  /** `max_tokens` is deprecated by OpenAI for o-series models, but it is what older models and Azure deployments take. */
  @nowarn("cat=deprecation")
  private def setMaxTokens(builder: ChatCompletionCreateParams.Builder, maxTokens: Long): Unit =
    builder.maxTokens(maxTokens)

  /**
   * Creates an OpenAI client for direct OpenAI API access.
   *
   * @param config OpenAI configuration with API key, model, and base URL
   * @param metrics metrics collector for observability
   * @return Right(OpenAIClient) on success, Left(LLMError) if client creation fails
   */
  def apply(
    config: OpenAIConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[OpenAIClient] =
    Try(new OpenAIClient(config, metrics, exchangeLogging)).toResult

  def apply(
    config: OpenAIConfig,
    metrics: org.llm4s.metrics.MetricsCollector
  )(using ModelRegistryService): Result[OpenAIClient] =
    Try(new OpenAIClient(config, metrics)).toResult

  /**
   * Convenience overload with noop metrics.
   */
  def apply(config: OpenAIConfig)(using ModelRegistryService): Result[OpenAIClient] =
    Try(new OpenAIClient(config, org.llm4s.metrics.MetricsCollector.noop)).toResult

  /**
   * Creates an OpenAI client for Azure OpenAI service.
   *
   * @param config Azure configuration with API key, model, endpoint, and API version
   * @param metrics metrics collector for observability
   * @return Right(OpenAIClient) on success, Left(LLMError) if client creation fails
   */
  def apply(
    config: AzureConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[OpenAIClient] =
    Try(new OpenAIClient(config, metrics, exchangeLogging)).toResult

  def apply(
    config: AzureConfig,
    metrics: org.llm4s.metrics.MetricsCollector
  )(using ModelRegistryService): Result[OpenAIClient] =
    Try(new OpenAIClient(config, metrics)).toResult

  /**
   * Convenience overload with noop metrics.
   */
  def apply(config: AzureConfig)(using ModelRegistryService): Result[OpenAIClient] =
    Try(new OpenAIClient(config, org.llm4s.metrics.MetricsCollector.noop)).toResult
}

private[provider] object OpenAIClientTransport {

  /**
   * The SDK options carrying `timeout`, or none when the section sets no value, in which case the
   * SDK's own default applies, as before the `timeouts` block existed.
   *
   * The timeout goes on each call rather than on the SDK client, because the client's timeout is one
   * value for every call and covers a streamed response in full: a short `request` timeout set there
   * would also cut every stream, and `request` and `stream` are separate settings.
   */
  private[provider] def requestOptions(
    timeout: Option[scala.concurrent.duration.FiniteDuration]
  ): Option[RequestOptions] =
    timeout.map(t => RequestOptions.builder().timeout(java.time.Duration.ofNanos(t.toNanos)).build())

  /**
   * A transport over an `openai-java` client, closing it with the transport.
   *
   * @param timeouts the section's `timeouts`: `request` bounds a completion, `stream` a streamed one
   */
  def sdk(client: SdkClient, timeouts: ProviderTimeouts = ProviderTimeouts.default): OpenAIClientTransport =
    new OpenAIClientTransport {
      private val requestOpts = requestOptions(timeouts.request)
      private val streamOpts  = requestOptions(timeouts.stream)

      override def createChatCompletion(params: ChatCompletionCreateParams): ChatCompletion =
        requestOpts.fold(client.chat().completions().create(params))(o => client.chat().completions().create(params, o))

      override def createChatCompletionStream(
        params: ChatCompletionCreateParams
      ): StreamResponse[ChatCompletionChunk] =
        streamOpts.fold(client.chat().completions().createStreaming(params))(o =>
          client.chat().completions().createStreaming(params, o)
        )

      override def close(): Unit = client.close()
    }

  /**
   * OpenAI, Requesty or any OpenAI-compatible base URL: a bearer API key, the configured
   * organisation, and requests to `<baseUrl>/chat/completions`.
   */
  def openAI(config: OpenAIConfig): OpenAIClientTransport =
    sdk(
      OpenAIOkHttpClient
        .builder()
        .apiKey(config.apiKey)
        .baseUrl(config.baseUrl)
        .organization(config.organization.orNull)
        .build(),
      config.timeouts
    )

  /**
   * Azure OpenAI: an `api-key` header, and requests to
   * `<endpoint>/openai/deployments/<model>/chat/completions?api-version=<apiVersion>`,
   * where `model` is the deployment name - the URL the Azure SDK built.
   *
   * The path mode is set rather than left to the SDK's host-name detection, so an endpoint on
   * a custom domain (an API Management gateway, a private endpoint) is still treated as Azure,
   * as it was before.
   *
   * An endpoint ending in `/openai/v1` is Azure's newer unified ("v1") API, which takes the
   * model in the request body and is not versioned by date: there the `api-version` parameter
   * is sent only when `apiVersion` was set to something other than the default.
   */
  def azure(config: AzureConfig): OpenAIClientTransport = {
    val pathMode = azureUrlPathMode(config.endpoint)
    val builder = OpenAIOkHttpClient
      .builder()
      .baseUrl(config.endpoint)
      .credential(AzureApiKeyCredential.create(config.apiKey))
      .azureUrlPathMode(pathMode)
    if (pathMode == AzureUrlPathMode.LEGACY || config.apiVersion != AzureConfig.DEFAULT_API_VERSION)
      builder.azureServiceVersion(azureServiceVersion(config.apiVersion))
    sdk(builder.build(), config.timeouts)
  }

  private[provider] def azureUrlPathMode(endpoint: String): AzureUrlPathMode =
    if (endpoint.trim.stripSuffix("/").endsWith("/openai/v1")) AzureUrlPathMode.UNIFIED
    else AzureUrlPathMode.LEGACY

  /**
   * The `api-version` for an `apiVersion` setting, which may be in either of two forms: the
   * Azure SDK's enum-constant name (`V2025_01_01_PREVIEW`, the form `AzureConfig.DEFAULT_API_VERSION`
   * uses), or the wire value itself (`2025-01-01-preview`, the form the docs show). Both map to
   * the same version.
   */
  private[provider] def azureServiceVersion(apiVersion: String): AzureOpenAIServiceVersion =
    AzureOpenAIServiceVersion.fromString(azureApiVersionWire(apiVersion))

  /** The wire form of an `apiVersion` setting given in either form (see [[azureServiceVersion]]). */
  private[provider] def azureApiVersionWire(apiVersion: String): String = {
    val trimmed = apiVersion.trim
    if (trimmed.matches("(?i)v\\d{4}_\\d{2}_\\d{2}(_[a-z]+)?")) trimmed.drop(1).replace('_', '-').toLowerCase
    else trimmed
  }
}
