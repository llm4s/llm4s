package org.llm4s.llmconnect.provider

import org.llm4s.error.{
  AuthenticationError,
  CancelledError,
  LLMError,
  NetworkError,
  RateLimitError,
  ServiceError,
  ValidationError
}
import org.llm4s.error.ThrowableOps.*
import org.llm4s.llmconnect.{ BaseLifecycleLLMClient, ProviderExchangeLogging }
import org.llm4s.llmconnect.config.{ BedrockConfig, ProviderConfig }
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.ProviderResultOps.*
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.model.{ ModelRegistryService, RequestTransformer, TransformationResult }
import org.llm4s.toolapi.{ ObjectSchema, ToolFunction }
import org.llm4s.types.Result

import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  AwsCredentials,
  AwsCredentialsProvider,
  AwsSessionCredentials,
  DefaultCredentialsProvider,
  ProfileCredentialsProvider,
  StaticCredentialsProvider
}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.http.Protocol
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.{ BedrockRuntimeAsyncClient, BedrockRuntimeClient }
import software.amazon.awssdk.services.bedrockruntime.model.{
  AccessDeniedException,
  BedrockRuntimeException,
  ContentBlock,
  ConversationRole,
  ConverseRequest,
  ConverseResponse,
  ConverseStreamRequest,
  ConverseStreamResponseHandler,
  InferenceConfiguration,
  Message => BedrockMessage,
  ReasoningContentBlock,
  ReasoningTextBlock,
  ServiceQuotaExceededException,
  SystemContentBlock,
  ThrottlingException,
  Tool,
  ToolConfiguration,
  ToolInputSchema,
  ToolResultBlock,
  ToolResultContentBlock,
  ToolSpecification,
  ToolUseBlock,
  ValidationException
}

import java.net.URI
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * [[org.llm4s.llmconnect.LLMClient]] for the AWS Bedrock Converse and ConverseStream APIs.
 *
 * Reaches the models Bedrock hosts - Anthropic Claude, Meta Llama, Mistral, Amazon Nova and Titan,
 * and others - through Bedrock's one Converse interface.
 *
 * == Authentication ==
 *
 * Explicit [[org.llm4s.llmconnect.config.BedrockCredentials]] when configured (with a session token
 * for temporary credentials); otherwise the config's `profile`; otherwise the AWS default
 * credential chain (environment variables, `~/.aws/credentials`, EC2 instance profile, ECS task
 * role, ...).
 *
 * == Streaming ==
 *
 * `streamComplete` uses `ConverseStream`, which the AWS SDK offers on its asynchronous client. Its
 * callbacks run on SDK threads; they only enqueue events, and the calling thread drains the queue,
 * so `onChunk` runs on the caller's thread, in order, and interrupting the caller cancels the
 * request and returns `Left(CancelledError)`.
 *
 * == Errors ==
 *
 * `ThrottlingException` and `ServiceQuotaExceededException` become `RateLimitError`,
 * `ValidationException` `ValidationError`, `AccessDeniedException` (and any 401/403)
 * `AuthenticationError`, other service errors `ServiceError`, and a failure to reach Bedrock at all
 * `NetworkError`.
 *
 * @param config          region, model, credentials and endpoint.
 * @param metrics         receives per-call latency and token-usage events.
 * @param exchangeLogging optional provider exchange logging.
 */
class BedrockClient(
  config: BedrockConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  import BedrockClient.*

  private val providerConfig: ProviderConfig = config

  private val credentialsProvider: AwsCredentialsProvider = credentialsFor(config)

  private val sdkClient: BedrockRuntimeClient = {
    val builder = BedrockRuntimeClient
      .builder()
      .region(Region.of(config.region))
      .credentialsProvider(credentialsProvider)
    config.endpointUrl.foreach(url => builder.endpointOverride(URI.create(url)))
    builder.build()
  }

  // Only streaming needs it, and it brings a Netty event loop, so it is built on first use.
  private lazy val asyncClient: BedrockRuntimeAsyncClient = {
    val builder = BedrockRuntimeAsyncClient
      .builder()
      .region(Region.of(config.region))
      .credentialsProvider(credentialsProvider)
    config.endpointUrl.foreach { url =>
      builder.endpointOverride(URI.create(url))
      // The SDK's async client speaks HTTP/2, which over a cleartext URL means h2c with prior
      // knowledge: an HTTP/1.1-only proxy or stub server cannot answer it. TLS endpoints
      // negotiate the protocol, so only a plain `http` override is pinned to HTTP/1.1.
      if (url.regionMatches(true, 0, "http://", 0, 7))
        builder.httpClientBuilder(NettyNioAsyncHttpClient.builder().protocol(Protocol.HTTP1_1))
    }
    builder.build()
  }

  @volatile private var asyncClientStarted = false

  protected def clientDescription: String = s"Bedrock client for model ${config.model}"
  protected def providerName: String      = "bedrock"
  protected def modelName: String         = config.model

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    transformed(conversation, options).flatMap { (conv, opts) =>
      val startedAt   = Instant.now()
      val requestJson = serializeRequestForLogging(conv, opts)
      buildConverseRequest(conv, opts).flatMap { request =>
        val outcome = Try(sdkClient.converse(request)).toEither.left.map(mapException)
        outcome
          .map { response =>
            val completion = parseConverseResponse(response)
            recordExchange(startedAt, requestJson, Some(serializeResponseForLogging(response)), Right(completion))
            completion
          }
          .tapLeft(err => recordExchange(startedAt, requestJson, None, Left(err)))
      }
    }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    transformed(conversation, options).flatMap { (conv, opts) =>
      val startedAt   = Instant.now()
      val requestJson = serializeRequestForLogging(conv, opts)
      val raw         = new StringBuilder()
      buildConverseStreamRequest(conv, opts)
        .flatMap(request => runStream(request, onChunk, raw))
        .tapRight(c => recordExchange(startedAt, requestJson, Some(raw.result()), Right(c)))
        .tapLeft(e => recordExchange(startedAt, requestJson, Option.when(raw.nonEmpty)(raw.result()), Left(e)))
    }
  }

  override def getContextWindow(): Int     = providerConfig.contextWindow
  override def getReserveCompletion(): Int = providerConfig.reserveCompletion

  override protected def releaseResources(): Unit = {
    sdkClient.close()
    if (asyncClientStarted) asyncClient.close()
  }

  private def transformed(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[(Conversation, CompletionOptions)] =
    TransformationResult
      .transform(
        config.model,
        options,
        conversation.messages,
        RequestTransformer.default(registryService),
        dropUnsupported = true
      )
      .map(t => (conversation.copy(messages = t.messages), t.options))

  // ---- streaming ----

  /**
   * Runs one ConverseStream request to completion on the calling thread.
   *
   * The SDK delivers events on its own threads; each is put on a queue and handled here, so the
   * accumulator and `onChunk` are only ever touched by the caller. A thread interrupt while
   * waiting cancels the request.
   */
  private def runStream(
    request: ConverseStreamRequest,
    onChunk: StreamedChunk => Unit,
    raw: StringBuilder
  ): Result[Completion] = {
    val queue = new LinkedBlockingQueue[StreamSignal]()

    val visitor = ConverseStreamResponseHandler.Visitor
      .builder()
      .onContentBlockStart { e =>
        Option(e.start().toolUse()).foreach { tu =>
          queue.put(StreamSignal.ToolStart(e.contentBlockIndex(), tu.toolUseId(), tu.name()))
        }
      }
      .onContentBlockDelta { e =>
        val delta = e.delta()
        Option(delta.text()).filter(_.nonEmpty).foreach(t => queue.put(StreamSignal.Text(t)))
        Option(delta.toolUse()).flatMap(tu => Option(tu.input())).filter(_.nonEmpty).foreach { fragment =>
          queue.put(StreamSignal.ToolArgs(e.contentBlockIndex(), fragment))
        }
        Option(delta.reasoningContent()).foreach { reasoning =>
          Option(reasoning.text()).filter(_.nonEmpty).foreach { t =>
            queue.put(StreamSignal.Reasoning(e.contentBlockIndex(), t))
          }
          Option(reasoning.signature()).filter(_.nonEmpty).foreach { sig =>
            queue.put(StreamSignal.ReasoningSignature(e.contentBlockIndex(), sig))
          }
          Option(reasoning.redactedContent()).map(_.asByteArray()).filter(_.nonEmpty).foreach { bytes =>
            queue.put(StreamSignal.ReasoningRedacted(e.contentBlockIndex(), BedrockClient.encodeRedacted(bytes)))
          }
        }
      }
      .onMessageStop(e => queue.put(StreamSignal.Stop(e.stopReasonAsString())))
      .onMetadata { e =>
        Option(e.usage()).foreach { u =>
          queue.put(
            StreamSignal
              .Usage(Option(u.inputTokens()).fold(0)(_.intValue), Option(u.outputTokens()).fold(0)(_.intValue))
          )
        }
      }
      .build()

    val handler = ConverseStreamResponseHandler
      .builder()
      .subscriber(visitor)
      .onComplete(() => queue.put(StreamSignal.Finished(None)))
      .onError(t => queue.put(StreamSignal.Finished(Some(t))))
      .build()

    asyncClientStarted = true
    val future = asyncClient.converseStream(request, handler)
    // A request that fails before the event stream starts fails the future without reaching
    // the visitor; a Finished already queued by the handler makes this one a no-op.
    future.whenComplete((_, error) => if (error != null) queue.put(StreamSignal.Finished(Some(error))))

    val accumulator = StreamingAccumulator.create()
    val messageId   = java.util.UUID.randomUUID().toString
    val toolIds     = scala.collection.mutable.Map.empty[Int, String]
    // reasoning blocks by content-block index, so each keeps its own text and signature
    val reasoning = scala.collection.mutable.TreeMap.empty[Int, BedrockClient.StreamedReasoning]

    def emit(chunk: StreamedChunk): Unit = {
      accumulator.addChunk(chunk)
      onChunk(chunk)
    }

    var stopped = false

    def drain(): Result[Completion] =
      CancelledError.catchInterrupt(queue.take()) match {
        case Left(interrupted) =>
          future.cancel(true)
          Thread.currentThread().interrupt()
          Left(CancelledError("bedrock.streamComplete", Some(interrupted)))
        case Right(signal) =>
          raw.append(signal.toString).append('\n')
          signal match {
            case StreamSignal.Text(text) =>
              emit(StreamedChunk(messageId, Some(text), None, None))
              drain()
            case StreamSignal.Reasoning(index, text) =>
              reasoning.getOrElseUpdate(index, BedrockClient.StreamedReasoning()).text.append(text)
              emit(StreamedChunk(messageId, None, None, None, thinkingDelta = Some(text)))
              drain()
            case StreamSignal.ReasoningSignature(index, signature) =>
              reasoning.getOrElseUpdate(index, BedrockClient.StreamedReasoning()).signature = Some(signature)
              drain()
            case StreamSignal.ReasoningRedacted(index, data) =>
              reasoning.getOrElseUpdate(index, BedrockClient.StreamedReasoning()).redacted = Some(data)
              drain()
            case StreamSignal.ToolStart(index, id, name) =>
              toolIds(index) = id
              emit(StreamedChunk(messageId, None, Some(ToolCall(id, name, ujson.Obj())), None))
              drain()
            case StreamSignal.ToolArgs(index, fragment) =>
              toolIds.get(index).foreach { id =>
                emit(StreamedChunk(messageId, None, Some(ToolCall(id, "", ujson.Str(fragment))), None))
              }
              drain()
            case StreamSignal.Stop(reason) =>
              stopped = true
              emit(StreamedChunk(messageId, None, None, Some(reason)))
              drain()
            case StreamSignal.Usage(input, output) =>
              accumulator.updateTokens(input, output)
              drain()
            case StreamSignal.Finished(Some(error))      => Left(mapException(error))
            case StreamSignal.Finished(None) if !stopped =>
              // The connection ended cleanly but the model never said it was done: what was
              // delivered is a truncated answer, and a truncated tool call is worse.
              Left(NetworkError("ConverseStream ended before messageStop", None, endpointLabel))
            case StreamSignal.Finished(None) =>
              accumulator.toCompletion.map { c =>
                // the accumulator knows only the reasoning text; the blocks carry their signatures
                val message =
                  if (reasoning.isEmpty) c.message
                  else c.message.withThinking(reasoning.values.map(_.toBlock).toSeq)
                c.withModel(config.model)
                  .withMessage(message)
                  .withToolCalls(c.message.toolCalls.toList)
                  .withEstimatedCost(c.usage.flatMap(u => CostEstimator.estimate(config.model, u)))
              }
          }
      }

    drain()
  }

  // ---- request building ----

  private def partitionMessages(conversation: Conversation): Result[(Seq[String], Seq[BedrockMessage])] = {
    val systemTexts = conversation.messages.collect { case SystemMessage(content) => content }
    val messages = mergeAdjacentRoles(convertMessages(conversation.messages.filterNot(_.isInstanceOf[SystemMessage])))
    if (messages.isEmpty)
      Left(
        ValidationError(
          "messages",
          "Bedrock needs at least one user or assistant message; a system-only conversation has none"
        )
      )
    else Right((systemTexts, messages))
  }

  private def inferenceConfig(options: CompletionOptions): InferenceConfiguration = {
    val cfg = InferenceConfiguration.builder().temperature(options.temperature.floatValue())
    options.maxTokens.foreach(m => cfg.maxTokens(m))
    // Only an explicit top-p is sent: 1.0 is the CompletionOptions default, and some models reject
    // temperature and top-p together.
    if (options.topP != 1.0) cfg.topP(options.topP.floatValue())
    cfg.build()
  }

  private def toolConfig(options: CompletionOptions): Option[ToolConfiguration] =
    Option.when(options.tools.nonEmpty)(
      ToolConfiguration.builder().tools(options.tools.map(convertTool).asJava).build()
    )

  private def buildConverseRequest(conversation: Conversation, options: CompletionOptions): Result[ConverseRequest] =
    partitionMessages(conversation).map { (systemTexts, messages) =>
      val builder = ConverseRequest.builder().modelId(config.model).messages(messages.asJava)
      if (systemTexts.nonEmpty) builder.system(systemTexts.map(SystemContentBlock.fromText).asJava)
      builder.inferenceConfig(inferenceConfig(options))
      toolConfig(options).foreach(builder.toolConfig)
      builder.build()
    }

  private def buildConverseStreamRequest(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[ConverseStreamRequest] =
    partitionMessages(conversation).map { (systemTexts, messages) =>
      val builder = ConverseStreamRequest.builder().modelId(config.model).messages(messages.asJava)
      if (systemTexts.nonEmpty) builder.system(systemTexts.map(SystemContentBlock.fromText).asJava)
      builder.inferenceConfig(inferenceConfig(options))
      toolConfig(options).foreach(builder.toolConfig)
      builder.build()
    }

  /**
   * Converse needs user and assistant turns to alternate. llm4s keeps one message per tool result,
   * so an assistant turn with parallel tool calls is followed by several user messages (and an
   * empty assistant message is dropped between two user ones); each run of same-role messages
   * becomes one turn carrying all their content blocks, in order - except that a user turn's tool
   * results come first, since Converse rejects a tool result after text in the same turn.
   */
  private def mergeAdjacentRoles(messages: Seq[BedrockMessage]): Seq[BedrockMessage] =
    messages.foldLeft(Vector.empty[BedrockMessage]) { (acc, next) =>
      acc.lastOption match {
        case Some(prev) if prev.role() == next.role() =>
          val blocks = prev.content().asScala.toSeq ++ next.content().asScala
          val ordered =
            if (prev.role() == ConversationRole.USER) {
              val (results, rest) = blocks.partition(_.toolResult() != null)
              results ++ rest
            } else blocks
          acc.init :+ BedrockMessage.builder().role(prev.role()).content(ordered.asJava).build()
        case _ => acc :+ next
      }
    }

  /**
   * A tool call goes out only if its result is in the run of tool messages straight after it, and
   * that result as a native tool result; Converse rejects a call without its result and a result not
   * right after its call. Any other tool result goes as user text (see [[ToolResultPairing]]).
   */
  private def convertMessages(messages: Seq[Message]): Seq[BedrockMessage] = {
    val pairing = ToolResultPairing.of(messages)
    messages.zipWithIndex.flatMap {
      case (UserMessage(content), _) =>
        Some(BedrockMessage.builder().role(ConversationRole.USER).content(ContentBlock.fromText(content)).build())

      case (original: AssistantMessage, index) =>
        // only the paired calls; dropping one unseals the thinking (see ToolResultPairing.replayable)
        val msg = pairing.replayable(index, original)
        // signed and redacted reasoning goes back first, unchanged; unsigned reasoning (from
        // another provider, or unsealed above) is left out, since Bedrock's Claude models reject it
        val reasoningBlocks = msg.thinking.collect {
          case ThinkingBlock.Text(text, Some(signature)) if signature.nonEmpty =>
            ContentBlock.fromReasoningContent(
              ReasoningContentBlock
                .builder()
                .reasoningText(ReasoningTextBlock.builder().text(text).signature(signature).build())
                .build()
            )
          case ThinkingBlock.Redacted(data) =>
            ContentBlock.fromReasoningContent(
              ReasoningContentBlock.builder().redactedContent(BedrockClient.decodeRedacted(data)).build()
            )
        }
        val textBlocks = msg.contentOpt.filter(_.nonEmpty).map(ContentBlock.fromText).toSeq
        val toolBlocks = msg.toolCalls.map { tc =>
          ContentBlock.fromToolUse(
            ToolUseBlock.builder().toolUseId(tc.id).name(tc.name).input(ujsonToDocument(tc.arguments)).build()
          )
        }
        val blocks = reasoningBlocks ++ textBlocks ++ toolBlocks
        Option.when(textBlocks.nonEmpty || toolBlocks.nonEmpty)(
          BedrockMessage.builder().role(ConversationRole.ASSISTANT).content(blocks.asJava).build()
        )

      case (msg: ToolMessage, index) if !pairing.resultPaired(index) =>
        val text = s"[Tool result for ${msg.toolCallId}]: ${msg.content}"
        Some(BedrockMessage.builder().role(ConversationRole.USER).content(ContentBlock.fromText(text)).build())

      case (msg: ToolMessage, _) =>
        val resultBlock = ToolResultBlock
          .builder()
          .toolUseId(msg.toolCallId)
          .content(ToolResultContentBlock.fromText(msg.content))
          .build()
        Some(
          BedrockMessage.builder().role(ConversationRole.USER).content(ContentBlock.fromToolResult(resultBlock)).build()
        )

      case (_: SystemMessage, _) => None
    }
  }

  private def convertTool(toolFunction: ToolFunction[?, ?]): Tool = {
    val objectSchema = toolFunction.schema.asInstanceOf[ObjectSchema[?]]
    val schemaDoc    = ujsonToDocument(ujson.read(objectSchema.toJsonSchema(false).render()))
    val toolSpec = ToolSpecification
      .builder()
      .name(toolFunction.name)
      .description(toolFunction.description)
      .inputSchema(ToolInputSchema.builder().json(schemaDoc).build())
      .build()
    Tool.builder().toolSpec(toolSpec).build()
  }

  // ---- response parsing ----

  private def parseConverseResponse(response: ConverseResponse): Completion = {
    val blocks = response.output().message().content().asScala.toList

    val textContent = blocks
      .filter(_.`type`() == ContentBlock.Type.TEXT)
      .flatMap(b => Option(b.text()).filter(_.nonEmpty))
      .mkString

    // every reasoning block with its signature, and redacted reasoning, in order
    val thinking: Seq[ThinkingBlock] = blocks
      .filter(_.`type`() == ContentBlock.Type.REASONING_CONTENT)
      .flatMap(b => Option(b.reasoningContent()))
      .flatMap { r =>
        Option(r.reasoningText())
          .map(t => ThinkingBlock.Text(Option(t.text()).getOrElse(""), Option(t.signature()).filter(_.nonEmpty)))
          .orElse(
            Option(r.redactedContent())
              .map(bytes => ThinkingBlock.Redacted(BedrockClient.encodeRedacted(bytes.asByteArray())))
          )
      }

    val toolCalls = blocks
      .filter(_.`type`() == ContentBlock.Type.TOOL_USE)
      .map { block =>
        val tu = block.toolUse()
        ToolCall(id = tu.toolUseId(), name = tu.name(), arguments = documentToUjson(tu.input()))
      }

    val message =
      AssistantMessage(contentOpt = Option(textContent).filter(_.nonEmpty), toolCalls = toolCalls, thinking = thinking)

    val usage = Option(response.usage()).map { u =>
      TokenUsage(
        promptTokens = u.inputTokens(),
        completionTokens = u.outputTokens(),
        totalTokens = u.totalTokens()
      )
    }

    Completion(
      id = java.util.UUID.randomUUID().toString,
      created = System.currentTimeMillis() / 1000,
      content = textContent,
      model = config.model,
      message = message,
      toolCalls = toolCalls,
      usage = usage,
      estimatedCost = usage.flatMap(u => CostEstimator.estimate(config.model, u))
    )
  }

  // ---- errors ----

  private def endpointLabel: String =
    config.endpointUrl.getOrElse(s"bedrock-runtime.${config.region}.amazonaws.com")

  private def mapException(throwable: Throwable): LLMError =
    unwrap(throwable) match {
      case _: ThrottlingException           => RateLimitError("bedrock")
      case _: ServiceQuotaExceededException => RateLimitError("bedrock")
      case e: ValidationException           => ValidationError("request", e.getMessage)
      case e: AccessDeniedException         => AuthenticationError("bedrock", e.getMessage)
      case e: BedrockRuntimeException if e.statusCode() == 401 || e.statusCode() == 403 =>
        AuthenticationError("bedrock", e.getMessage)
      case e: BedrockRuntimeException => ServiceError(e.statusCode(), "bedrock", e.getMessage)
      // The SDK reports "no credentials" as a client exception; retrying cannot fix it.
      case e: SdkClientException if Option(e.getMessage).exists(_.toLowerCase.contains("credentials")) =>
        AuthenticationError("bedrock", e.getMessage)
      case e: SdkClientException =>
        NetworkError(e.getMessage, Some(e), endpointLabel)
      case e => e.toLLMError
    }

  /** The async client reports failures wrapped in `CompletionException`. */
  private def unwrap(t: Throwable): Throwable =
    t match {
      case e: java.util.concurrent.CompletionException if e.getCause != null => unwrap(e.getCause)
      case other                                                             => other
    }

  // ---- exchange logging ----

  private def serializeRequestForLogging(conversation: Conversation, options: CompletionOptions): String = {
    val msgs = conversation.messages.map {
      case SystemMessage(content) => ujson.Obj("role" -> "system", "content" -> content)
      case UserMessage(content)   => ujson.Obj("role" -> "user", "content" -> content)
      case msg: AssistantMessage  => ujson.Obj("role" -> "assistant", "content" -> msg.content)
      case msg: ToolMessage => ujson.Obj("role" -> "tool", "toolCallId" -> msg.toolCallId, "content" -> msg.content)
    }
    ujson
      .Obj(
        "modelId"     -> config.model,
        "region"      -> config.region,
        "temperature" -> options.temperature,
        "messages"    -> ujson.Arr(msgs*)
      )
      .render()
  }

  private def serializeResponseForLogging(response: ConverseResponse): String = {
    val text = response
      .output()
      .message()
      .content()
      .asScala
      .filter(_.`type`() == ContentBlock.Type.TEXT)
      .flatMap(b => Option(b.text()))
      .mkString
    ujson.Obj("stopReason" -> response.stopReasonAsString(), "content" -> text).render()
  }

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

object BedrockClient {
  import org.llm4s.types.TryOps

  /** What the SDK's stream callbacks hand to the thread that is waiting on the stream. */
  private[provider] enum StreamSignal {
    case Text(text: String)
    case Reasoning(index: Int, text: String)
    case ReasoningSignature(index: Int, signature: String)
    case ReasoningRedacted(index: Int, data: String)
    case ToolStart(index: Int, id: String, name: String)
    case ToolArgs(index: Int, fragment: String)
    case Stop(reason: String)
    case Usage(inputTokens: Int, outputTokens: Int)
    case Finished(error: Option[Throwable])
  }

  /** A reasoning block being assembled from a stream. */
  final private[provider] case class StreamedReasoning(
    text: StringBuilder = new StringBuilder,
    var signature: Option[String] = None,
    var redacted: Option[String] = None
  ) {
    def toBlock: ThinkingBlock =
      redacted.fold[ThinkingBlock](ThinkingBlock.Text(text.toString, signature))(ThinkingBlock.Redacted(_))
  }

  /** Redacted reasoning is bytes on the wire; [[ThinkingBlock.Redacted]] holds them base64-encoded. */
  private[provider] def encodeRedacted(bytes: Array[Byte]): String =
    java.util.Base64.getEncoder.encodeToString(bytes)

  /** The bytes of a [[ThinkingBlock.Redacted]]; data that is not base64 is sent as its UTF-8 bytes. */
  private[provider] def decodeRedacted(data: String): SdkBytes =
    SdkBytes.fromByteArray(
      Try(java.util.Base64.getDecoder.decode(data)).getOrElse(data.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    )

  def apply(
    config: BedrockConfig,
    metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
    exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
  )(using ModelRegistryService): Result[BedrockClient] =
    Try(new BedrockClient(config, metrics, exchangeLogging)).toResult

  private def credentialsFor(config: BedrockConfig): AwsCredentialsProvider =
    config.credentials match {
      case Some(c) =>
        // An empty AWS_SESSION_TOKEN is "no token", not a token of "".
        val credentials: AwsCredentials = c.sessionToken.filter(_.trim.nonEmpty) match {
          case Some(token) => AwsSessionCredentials.create(c.accessKeyId, c.secretAccessKey, token)
          case None        => AwsBasicCredentials.create(c.accessKeyId, c.secretAccessKey)
        }
        StaticCredentialsProvider.create(credentials)
      case None =>
        config.profile match {
          case Some(profile) => ProfileCredentialsProvider.create(profile)
          case None          => DefaultCredentialsProvider.builder().build()
        }
    }

  private[provider] def ujsonToDocument(value: ujson.Value): Document =
    value match {
      case ujson.Str(s) => Document.fromString(s)
      case ujson.Num(n) =>
        // Whole numbers go as integers: a tool schema of `integer` rejects "3.0".
        if (n.isWhole && math.abs(n) < 1e15) Document.fromNumber(java.math.BigDecimal.valueOf(n.toLong))
        else Document.fromNumber(java.math.BigDecimal.valueOf(n))
      case ujson.Bool(b) => Document.fromBoolean(b)
      case ujson.Null    => Document.fromNull()
      case ujson.Arr(arr) =>
        Document.fromList(arr.map(ujsonToDocument).toList.asJava)
      case ujson.Obj(obj) =>
        val m = new java.util.LinkedHashMap[String, Document]()
        obj.foreach { case (k, v) => m.put(k, ujsonToDocument(v)) }
        Document.fromMap(m)
    }

  private[provider] def documentToUjson(doc: Document): ujson.Value = {
    val visitor = new software.amazon.awssdk.core.document.DocumentVisitor[ujson.Value] {
      override def visitNull(): ujson.Value = ujson.Null
      override def visitBoolean(b: java.lang.Boolean): ujson.Value =
        ujson.Bool(b.booleanValue())
      override def visitNumber(n: software.amazon.awssdk.core.SdkNumber): ujson.Value =
        ujson.Num(n.doubleValue())
      override def visitString(s: String): ujson.Value = ujson.Str(s)
      override def visitList(list: java.util.List[Document]): ujson.Value =
        ujson.Arr(list.asScala.map(_.accept(this)).toSeq*)
      override def visitMap(map: java.util.Map[String, Document]): ujson.Value =
        ujson.Obj.from(map.asScala.map { case (k, v) => k -> v.accept(this) })
    }
    doc.accept(visitor)
  }
}
