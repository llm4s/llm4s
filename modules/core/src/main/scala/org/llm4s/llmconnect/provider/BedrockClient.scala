package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, RateLimitError, ValidationError }
import org.llm4s.error.ThrowableOps.*
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.{ BedrockConfig, ProviderConfig }
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.ProviderResultOps.*
import org.llm4s.model.{ ModelRegistryService, RequestTransformer, TransformationResult }
import org.llm4s.toolapi.{ ObjectSchema, ToolFunction }
import org.llm4s.types.Result

import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  DefaultCredentialsProvider,
  StaticCredentialsProvider
}
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import org.llm4s.error.ServiceError

import software.amazon.awssdk.services.bedrockruntime.model.{
  AccessDeniedException,
  BedrockRuntimeException,
  ContentBlock,
  ConverseRequest,
  ConversationRole,
  InferenceConfiguration,
  Message => BedrockMessage,
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
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * [[LLMClient]] implementation for the AWS Bedrock Converse API.
 *
 * Supports models from Anthropic (Claude), Meta (Llama), Amazon Titan, Mistral
 * and others available through AWS Bedrock's unified Converse endpoint.
 *
 * == Authentication ==
 *
 * Uses the AWS default credential chain when no explicit credentials are
 * configured: environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`),
 * `~/.aws/credentials`, EC2 instance profile, ECS task role, etc.
 * Explicit credentials can be supplied via [[BedrockConfig.accessKeyId]] and
 * [[BedrockConfig.secretAccessKey]].
 *
 * == Streaming ==
 *
 * `streamComplete` delegates to the non-streaming Converse API and emits the
 * full response as a single [[StreamedChunk]].  True server-sent event
 * streaming via the Bedrock ConverseStream binary protocol can be added in a
 * follow-up using the AWS SDK async client.
 *
 * @param config          [[BedrockConfig]] with region, model ID, and optional credentials.
 * @param metrics         Receives per-call latency and token-usage events.
 * @param exchangeLogging Optional provider exchange logging.
 */
class BedrockClient(
  config: BedrockConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  private val providerConfig: ProviderConfig = config

  private val sdkClient: BedrockRuntimeClient = {
    val credProvider = (config.accessKeyId, config.secretAccessKey) match {
      case (Some(keyId), Some(secret)) =>
        StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, secret))
      case _ =>
        DefaultCredentialsProvider.create()
    }
    val builder = BedrockRuntimeClient
      .builder()
      .region(Region.of(config.region))
      .credentialsProvider(credProvider)
    config.endpointUrl.foreach(url => builder.endpointOverride(URI.create(url)))
    builder.build()
  }

  protected def clientDescription: String = s"Bedrock client for model ${config.model}"
  protected def providerName: String      = "bedrock"
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
        doConverse(conversation.copy(messages = transformed.messages), transformed.options, startedAt)
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
        doConverse(conversation.copy(messages = transformed.messages), transformed.options, startedAt).map {
          completion =>
            val chunk = StreamedChunk(
              id = completion.id,
              content = Option(completion.content).filter(_.nonEmpty),
              toolCall = None,
              finishReason = Some("stop")
            )
            onChunk(chunk)
            completion
        }
      }
  }

  override def getContextWindow(): Int     = providerConfig.contextWindow
  override def getReserveCompletion(): Int = providerConfig.reserveCompletion

  override protected def releaseResources(): Unit = sdkClient.close()

  private def doConverse(
    conversation: Conversation,
    options: CompletionOptions,
    startedAt: Instant
  ): Result[Completion] = {
    val requestJson = serializeRequestForLogging(conversation, options)
    val request     = buildConverseRequest(conversation, options)

    val attempt = Try(sdkClient.converse(request)).toEither.left.map {
      case _: ThrottlingException           => RateLimitError("bedrock")
      case _: ServiceQuotaExceededException => RateLimitError("bedrock")
      case e: ValidationException           => ValidationError("request", e.getMessage)
      case e: AccessDeniedException         => AuthenticationError("bedrock", e.getMessage)
      case e: BedrockRuntimeException       => ServiceError(e.statusCode(), "bedrock", e.getMessage)
      case e                                => e.toLLMError
    }

    attempt
      .map { response =>
        val completion   = parseConverseResponse(response)
        val responseJson = serializeResponseForLogging(response)
        recordExchange(startedAt, requestJson, Some(responseJson), Right(completion))
        completion
      }
      .tapLeft(err => recordExchange(startedAt, requestJson, None, Left(err)))
  }

  private def buildConverseRequest(conversation: Conversation, options: CompletionOptions): ConverseRequest = {
    val builder = ConverseRequest.builder().modelId(config.model)

    val (sysMsgs, otherMsgs) = conversation.messages.partition(_.isInstanceOf[SystemMessage])

    if (sysMsgs.nonEmpty) {
      val sysBlocks = sysMsgs.collect { case SystemMessage(content) => SystemContentBlock.fromText(content) }
      builder.system(sysBlocks.asJava)
    }

    val bedrockMessages = convertMessages(otherMsgs)
    if (bedrockMessages.nonEmpty) builder.messages(bedrockMessages.asJava)

    val infCfg = InferenceConfiguration.builder().temperature(options.temperature.floatValue())
    options.maxTokens.foreach(m => infCfg.maxTokens(m))
    builder.inferenceConfig(infCfg.build())

    if (options.tools.nonEmpty) {
      val tools   = options.tools.map(convertTool)
      val toolCfg = ToolConfiguration.builder().tools(tools.asJava).build()
      builder.toolConfig(toolCfg)
    }

    builder.build()
  }

  private def convertMessages(messages: Seq[Message]): Seq[BedrockMessage] =
    messages.flatMap {
      case UserMessage(content) =>
        Some(
          BedrockMessage.builder()
            .role(ConversationRole.USER)
            .content(ContentBlock.fromText(content))
            .build()
        )

      case msg: AssistantMessage =>
        val textBlocks = msg.contentOpt.filter(_.nonEmpty).map(ContentBlock.fromText).toSeq
        val toolBlocks = msg.toolCalls.map { tc =>
          ContentBlock.fromToolUse(
            ToolUseBlock.builder()
              .toolUseId(tc.id)
              .name(tc.name)
              .input(ujsonToDocument(tc.arguments))
              .build()
          )
        }
        val blocks = textBlocks ++ toolBlocks
        if (blocks.nonEmpty)
          Some(BedrockMessage.builder().role(ConversationRole.ASSISTANT).content(blocks.asJava).build())
        else None

      case msg: ToolMessage =>
        val resultBlock = ToolResultBlock.builder()
          .toolUseId(msg.toolCallId)
          .content(ToolResultContentBlock.fromText(msg.content))
          .build()
        Some(
          BedrockMessage.builder()
            .role(ConversationRole.USER)
            .content(ContentBlock.fromToolResult(resultBlock))
            .build()
        )

      case _: SystemMessage => None
    }

  private def convertTool(toolFunction: ToolFunction[?, ?]): Tool = {
    val objectSchema = toolFunction.schema.asInstanceOf[ObjectSchema[?]]
    val schemaDoc    = ujsonToDocument(ujson.read(objectSchema.toJsonSchema(false).render()))
    val toolSpec = ToolSpecification.builder()
      .name(toolFunction.name)
      .description(toolFunction.description)
      .inputSchema(ToolInputSchema.builder().json(schemaDoc).build())
      .build()
    Tool.builder().toolSpec(toolSpec).build()
  }

  private def parseConverseResponse(
    response: software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
  ): Completion = {
    val outputMsg = response.output().message()
    val blocks    = outputMsg.content().asScala.toList

    val textContent = blocks
      .filter(_.`type`() == ContentBlock.Type.TEXT)
      .flatMap(b => Option(b.text()).filter(_.nonEmpty))
      .mkString

    val toolCalls = blocks
      .filter(_.`type`() == ContentBlock.Type.TOOL_USE)
      .map { block =>
        val tu = block.toolUse()
        ToolCall(id = tu.toolUseId(), name = tu.name(), arguments = documentToUjson(tu.input()))
      }

    val message = AssistantMessage(contentOpt = Option(textContent).filter(_.nonEmpty), toolCalls = toolCalls)

    val usage = Option(response.usage()).map { u =>
      TokenUsage(
        promptTokens = u.inputTokens(),
        completionTokens = u.outputTokens(),
        totalTokens = u.totalTokens()
      )
    }

    val cost = usage.flatMap(u => CostEstimator.estimate(config.model, u))

    Completion(
      id = java.util.UUID.randomUUID().toString,
      created = System.currentTimeMillis() / 1000,
      content = textContent,
      model = config.model,
      message = message,
      toolCalls = toolCalls,
      usage = usage,
      thinking = None,
      estimatedCost = cost
    )
  }

  private[provider] def ujsonToDocument(value: ujson.Value): Document =
    value match {
      case ujson.Str(s)  => Document.fromString(s)
      case ujson.Num(n)  => Document.fromNumber(java.math.BigDecimal.valueOf(n))
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
        val items = list.asScala.map(_.accept(this)).toArray
        ujson.Arr(items: _*)
      override def visitMap(map: java.util.Map[String, Document]): ujson.Value =
        val obj = ujson.Obj()
        map.asScala.foreach { case (k, v) => obj(k) = v.accept(this) }
        obj
    }
    doc.accept(visitor)
  }

  private def serializeRequestForLogging(conversation: Conversation, options: CompletionOptions): String = {
    val msgs = conversation.messages.map {
      case SystemMessage(content)    => ujson.Obj("role" -> "system", "content" -> content)
      case UserMessage(content)      => ujson.Obj("role" -> "user", "content" -> content)
      case msg: AssistantMessage     => ujson.Obj("role" -> "assistant", "content" -> msg.content)
      case msg: ToolMessage          => ujson.Obj("role" -> "tool", "toolCallId" -> msg.toolCallId, "content" -> msg.content)
    }
    ujson.Obj(
      "modelId"     -> config.model,
      "region"      -> config.region,
      "temperature" -> options.temperature,
      "messages"    -> ujson.Arr(msgs: _*)
    ).render()
  }

  private def serializeResponseForLogging(
    response: software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
  ): String = {
    val text = response.output().message().content().asScala
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

  def apply(
    config: BedrockConfig,
    metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
  )(using ModelRegistryService): Result[BedrockClient] =
    Try(new BedrockClient(config, metrics)).toResult

  def apply(
    config: BedrockConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[BedrockClient] =
    Try(new BedrockClient(config, metrics, exchangeLogging)).toResult
}
