// scalafix:off DisableSyntax.NoKeywordFinally
package org.llm4s.trace

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{ Span, SpanContext, SpanKind, StatusCode, TraceFlags, TraceState, Tracer }
import io.opentelemetry.context.Context
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.{ IdGenerator, SdkTracerProvider, SpanProcessor }
import io.opentelemetry.sdk.trace.`export`.{ BatchSpanProcessor, SimpleSpanProcessor, SpanExporter }
import org.llm4s.error.UnknownError
import org.llm4s.llmconnect.config.OpenTelemetryConfig
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Message, TokenUsage, ToolMessage }
import org.llm4s.types.Result
import org.llm4s.util.Redaction

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Exports trace events as OpenTelemetry spans over OTLP/gRPC.
 *
 * An event placed in a trace ([[TraceEvent.span]], design 4.58) becomes a span with exactly the placement's ids: its
 * trace and span id, its parent - which may be a remote parent, a span another process exported - its start time and,
 * as its end, the event's timestamp. An agent's run is so one tree: `invoke_agent <agent>` (from `AgentRunEnded`) over
 * `chat <model>` (`agent.model_call_completed`), `execute_tool <tool>` (`agent.tool_executed`) and the run's other
 * events, and each delegated child's `invoke_agent` under the `execute_tool task` that started it, whichever process
 * ran it. An event without a placement becomes a span of its own, as before. The agent's spans carry no message
 * content (Stage 5 rule 10).
 *
 * Model calls follow the OpenTelemetry GenAI semantic conventions, version
 * [[OpenTelemetryTracing.SemconvVersion]] (#2275): a completion - plain or streamed - is a `CLIENT` span
 * `chat <model>`, an embedding call one named `embeddings <model>`, with `gen_ai.operation.name`,
 * `gen_ai.provider.name`, the request and response model, the response id and finish reasons, token usage, the request
 * parameters and `server.address`; a failed call is that span with `error.type` and an `ERROR` status. Agent spans
 * carry `gen_ai.provider.name` too.
 *
 * Message content - prompts, completions, an agent's query and tool input and output - is recorded only with
 * `captureContent` (`llm4s.tracing.opentelemetry.captureContent`, off by default), as the conventions'
 * `gen_ai.input.messages` / `gen_ai.output.messages`, with secrets redacted first.
 */
class OpenTelemetryTracing private[trace] (
  serviceName: String,
  newProcessor: () => SpanProcessor,
  captureContent: Boolean
) extends Tracing {

  def this(serviceName: String, endpoint: String, headers: Map[String, String], captureContent: Boolean = false) =
    this(
      serviceName,
      () => {
        val spanExporterBuilder = OtlpGrpcSpanExporter
          .builder()
          .setEndpoint(endpoint)
        headers.foreach { case (k, v) =>
          spanExporterBuilder.addHeader(k, v)
        }
        BatchSpanProcessor
          .builder(spanExporterBuilder.build())
          .setMaxExportBatchSize(512)
          .setScheduleDelay(5, TimeUnit.SECONDS)
          .build()
      },
      captureContent
    )

  // the ids of the placed span being started on this thread, which the SDK draws from its id generator
  private val ids = new PlacedIds

  // Initialize OpenTelemetry SDK and Tracer, deferring errors to usage
  private val initializationResult: Either[Throwable, (OpenTelemetrySdk, Tracer)] = Try {
    val resource = Resource.getDefault.toBuilder
      .put(AttributeKey.stringKey("service.name"), serviceName)
      .build()

    val tracerProvider = SdkTracerProvider
      .builder()
      .setResource(resource)
      .setIdGenerator(ids)
      .addSpanProcessor(newProcessor())
      .build()

    val openTelemetry = OpenTelemetrySdk
      .builder()
      .setTracerProvider(tracerProvider)
      .build()

    val tracer = openTelemetry.getTracerProvider
      .tracerBuilder("org.llm4s")
      .setSchemaUrl(OpenTelemetryTracing.SemconvSchemaUrl)
      .build()
    (openTelemetry, tracer)
  }.toEither

  // Log initialization failure if any
  initializationResult match {
    case Left(error) =>
      org.slf4j.LoggerFactory.getLogger(getClass).error("Failed to initialize OpenTelemetry tracing", error)
    case Right(_) => // Successfully initialized
  }

  private val tracer: Option[Tracer] = initializationResult.map(_._2).toOption

  /** Why the SDK could not start, if it could not; `OpenTelemetryTracingBackend` reports it. */
  private[trace] def initializationFailure: Option[Throwable] = initializationResult.left.toOption

  override def shutdown(): Unit =
    initializationResult.foreach { case (sdk, _) =>
      val provider = sdk.getSdkTracerProvider
      provider.shutdown().join(10, TimeUnit.SECONDS)
    }

  override def traceEvent(event: TraceEvent): Result[Unit] =
    tracer match {
      case Some(t) =>
        val (spanName, attributes) = mapEventToAttributes(event)

        val spanBuilder = t
          .spanBuilder(spanName)
          .setAllAttributes(attributes)
          .setSpanKind(getSpanKind(event))

        event.span.foreach { placed =>
          placed.parent match {
            case Some(parent) => spanBuilder.setParent(Context.root().`with`(Span.wrap(remote(parent))))
            case None         => spanBuilder.setNoParent()
          }
          spanBuilder.setStartTimestamp(placed.startedAt)
        }
        // an unplaced model call spans its duration, ending at the event
        val duration = callDuration(event).filter(_ => event.span.isEmpty)
        duration.foreach(d => spanBuilder.setStartTimestamp(event.timestamp.minusNanos(d.toNanos)))

        val span = ids.startingAs(event.span.map(_.context))(spanBuilder.startSpan())

        try annotate(span, spanName, event)
        finally if (event.span.isDefined || duration.isDefined) span.end(event.timestamp) else span.end()
        Right(())

      case None =>
        initializationResult match {
          case Left(e)  => Left(UnknownError("Failed to initialize OpenTelemetry", e))
          case Right(_) => Right(()) // Should not happen
        }
    }

  private[trace] def getSpanKind(event: TraceEvent): SpanKind = event match {
    case _: TraceEvent.AgentInitialized | _: TraceEvent.AgentRunEnded | _: TraceEvent.TokenUsageRecorded =>
      SpanKind.INTERNAL
    // the GenAI conventions: a model call is a client span, a tool's execution an internal one
    case e: TraceEvent.CustomEvent if e.name == TraceAttributes.AgentModelCallCompleted => SpanKind.CLIENT
    case e: TraceEvent.CustomEvent if e.name == TraceAttributes.AgentToolExecuted       => SpanKind.INTERNAL
    case e: TraceEvent.CustomEvent if e.span.isDefined                                  => SpanKind.INTERNAL
    case _ =>
      SpanKind.CLIENT
  }

  /** How long a model call took, for an event that reports it. */
  private def callDuration(event: TraceEvent): Option[FiniteDuration] = event match {
    case e: TraceEvent.CompletionReceived                     => e.duration
    case e: TraceEvent.EmbeddingUsageRecorded                 => e.duration
    case e: TraceEvent.ErrorOccurred if e.operation.isDefined => e.duration
    case _                                                    => None
  }

  /**
   * Records an error event's exception on its span, and an `ERROR` status for what [[failed]] names. A failed model
   * call's status carries its error message, redacted.
   */
  private def annotate(span: Span, spanName: String, event: TraceEvent): Unit = {
    event match {
      case e: TraceEvent.ErrorOccurred if e.operation.isDefined =>
        span.setStatus(StatusCode.ERROR, Redaction.redact(Option(e.error.getMessage).getOrElse(""))): Unit
      case e: TraceEvent.ErrorOccurred if spanName == "Error" => span.recordException(e.error): Unit
      case _                                                  =>
    }
    if (failed(event)) span.setStatus(StatusCode.ERROR): Unit
  }

  /** A run that did not end well, and a tool call that errored: their spans' status is `ERROR`. */
  private def failed(event: TraceEvent): Boolean = event match {
    case e: TraceEvent.AgentRunEnded => Set("failed", "cancelled", "timed_out").contains(e.status)
    case e: TraceEvent.CustomEvent if e.name == TraceAttributes.AgentToolExecuted =>
      TraceAttributes.payload(e).flatMap(_.get("outcome")).exists(_.toString.contains("Errored"))
    case _ => false
  }

  private def remote(context: TraceContext): SpanContext =
    SpanContext.createFromRemoteParent(
      context.traceId,
      context.spanId,
      if (context.sampled) TraceFlags.getSampled else TraceFlags.getDefault,
      TraceState.getDefault
    )

  private[trace] def mapEventToAttributes(event: TraceEvent): (String, Attributes) = {
    event match {
      case e: TraceEvent.AgentInitialized =>
        val builder = Attributes
          .builder()
          .put(TraceAttributes.EventType, "trace-create")
          .put("tools", e.tools.mkString(", "))
        if (captureContent) builder.put("input", content(e.query))
        ("LLM4S Agent Run", builder.build())

      case e: TraceEvent.CompletionReceived =>
        val builder = Attributes
          .builder()
          .put(TraceAttributes.EventType, "generation-create")
          .put(TraceAttributes.OperationName, TraceAttributes.Chat)
          .put(TraceAttributes.RequestModel, e.model)
          .put(TraceAttributes.ToolCalls, e.toolCalls.toLong)
        if (e.id.nonEmpty) builder.put(TraceAttributes.ResponseId, e.id)
        e.provider.foreach(p => builder.put(TraceAttributes.ProviderName, TraceAttributes.providerName(p.asString)))
        e.responseModel.foreach(m => builder.put(TraceAttributes.ResponseModel, m))
        if (e.finishReasons.nonEmpty) builder.put(TraceAttributes.FinishReasons, e.finishReasons.asJava)
        e.usage.foreach { u =>
          builder.put(TraceAttributes.InputTokens, u.promptTokens.toLong)
          builder.put(TraceAttributes.OutputTokens, u.completionTokens.toLong)
        }
        e.options.foreach(requestParameters(builder, _))
        e.serverAddress.foreach(a => builder.put(TraceAttributes.ServerAddress, a))
        if (e.streamed) builder.put(TraceAttributes.Streamed, true)
        if (captureContent) {
          if (e.input.nonEmpty) builder.put(TraceAttributes.InputMessages, inputMessages(e.input))
          builder.put(TraceAttributes.OutputMessages, outputMessage(e.content, e.finishReasons.headOption))
        }
        (TraceAttributes.spanName(TraceAttributes.Chat, e.model), builder.build())

      case e: TraceEvent.ToolExecuted =>
        val builder = Attributes
          .builder()
          .put(TraceAttributes.EventType, "span-create")
          .put(TraceAttributes.OperationName, TraceAttributes.ExecuteTool)
          .put(TraceAttributes.ToolName, e.name)
          .put(TraceAttributes.GenAiToolName, e.name)
          .put("duration_ms", e.duration.toMillis)
          .put("success", e.success)
        if (captureContent) {
          builder.put("tool.input", content(e.input))
          builder.put("tool.output", content(e.output))
        }
        (s"${TraceAttributes.ExecuteTool} ${e.name}", builder.build())

      case e: TraceEvent.ErrorOccurred if e.operation.isDefined =>
        val operation = e.operation.getOrElse(TraceAttributes.Chat)
        val model     = e.model.getOrElse("")
        val builder = Attributes
          .builder()
          .put(TraceAttributes.EventType, "event-create")
          .put(TraceAttributes.OperationName, operation)
          .put(TraceAttributes.ErrorType, e.errorType.getOrElse(e.error.getClass.getName))
        if (model.nonEmpty) builder.put(TraceAttributes.RequestModel, model)
        e.provider.foreach(p => builder.put(TraceAttributes.ProviderName, TraceAttributes.providerName(p.asString)))
        (TraceAttributes.spanName(operation, model), builder.build())

      case e: TraceEvent.ErrorOccurred =>
        (
          "Error",
          Attributes
            .builder()
            .put(TraceAttributes.EventType, "event-create")
            .put("error.message", e.error.getMessage)
            .put("error.type", e.error.getClass.getSimpleName)
            .put("context", e.context)
            .build()
        )

      case e: TraceEvent.TokenUsageRecorded =>
        (
          "Token Usage - " + e.operation,
          Attributes
            .builder()
            .put(TraceAttributes.EventType, "event-create")
            .put("gen_ai.request.model", e.model)
            .put("operation", e.operation)
            .put("gen_ai.usage.input_tokens", e.usage.promptTokens.toLong)
            .put("gen_ai.usage.output_tokens", e.usage.completionTokens.toLong)
            .put("gen_ai.usage.total_tokens", e.usage.totalTokens.toLong)
            .build()
        )

      case e: TraceEvent.CustomEvent if e.name == TraceAttributes.AgentToolExecuted =>
        val payload = e.data.objOpt.flatMap(_.get("payload")).flatMap(_.objOpt)
        def text(fields: Option[collection.Map[String, ujson.Value]], key: String): String =
          fields.flatMap(_.get(key)).flatMap(_.strOpt).getOrElse("")
        val tool = text(payload, "tool")
        (
          s"${TraceAttributes.ExecuteTool} $tool",
          Attributes
            .builder()
            .put(TraceAttributes.EventType, "custom")
            .put(TraceAttributes.OperationName, TraceAttributes.ExecuteTool)
            .put(TraceAttributes.ToolName, tool)
            .put(TraceAttributes.GenAiToolName, tool)
            .put(TraceAttributes.GenAiToolCallId, text(payload, "toolCallId"))
            .put(TraceAttributes.AgentName, text(payload, "agent"))
            .put(TraceAttributes.ConversationId, text(e.data.objOpt, "threadId"))
            .put("data", e.data.toString())
            .build()
        )

      case e: TraceEvent.CustomEvent if e.name == TraceAttributes.AgentModelCallCompleted =>
        val payload                           = TraceAttributes.payload(e)
        def text(key: String): String         = payload.flatMap(_.get(key)).flatMap(_.strOpt).getOrElse("")
        def number(key: String): Option[Long] = payload.flatMap(_.get(key)).flatMap(_.numOpt).map(_.toLong)
        val usage                             = payload.flatMap(_.get("usage")).flatMap(_.objOpt)
        def tokens(key: String): Option[Long] = usage.flatMap(_.get(key)).flatMap(_.numOpt).map(_.toLong)
        val model                             = text("model")
        val builder = Attributes
          .builder()
          .put(TraceAttributes.EventType, "custom")
          .put(TraceAttributes.OperationName, TraceAttributes.Chat)
          .put(TraceAttributes.RequestModel, model)
          .put(TraceAttributes.AgentName, text("agent"))
          .put(
            TraceAttributes.ConversationId,
            e.data.objOpt.flatMap(_.get("threadId")).flatMap(_.strOpt).getOrElse("")
          )
        number("attempts").foreach(n => builder.put(TraceAttributes.Attempt, n))
        number("toolCalls").foreach(n => builder.put(TraceAttributes.ToolCalls, n))
        tokens("promptTokens").foreach(n => builder.put(TraceAttributes.InputTokens, n))
        tokens("completionTokens").foreach(n => builder.put(TraceAttributes.OutputTokens, n))
        payload.flatMap(_.get("estimatedCost")).flatMap(_.numOpt).foreach(c => builder.put(TraceAttributes.CostUsd, c))
        payload
          .flatMap(_.get("provider"))
          .flatMap(_.strOpt)
          .foreach(p => builder.put(TraceAttributes.ProviderName, TraceAttributes.providerName(p)))
        (s"${TraceAttributes.Chat} $model", builder.build())

      case e: TraceEvent.CustomEvent =>
        (
          e.name,
          Attributes
            .builder()
            .put(TraceAttributes.EventType, "custom")
            .put("data", e.data.toString())
            .build()
        )

      case e: TraceEvent.AgentRunEnded =>
        val attributes = Attributes
          .builder()
          .put(TraceAttributes.EventType, "agent-run")
          .put(TraceAttributes.OperationName, TraceAttributes.InvokeAgent)
          .put(TraceAttributes.AgentName, e.agent)
          .put(TraceAttributes.ConversationId, e.threadId)
          .put("thread_id", e.threadId)
          .put("run_id", e.runId)
          .put("agent", e.agent)
          .put("status", e.status)
          .put("message_count", e.messages.size.toLong)
          .put("gen_ai.usage.input_tokens", e.usage.inputTokens)
          .put("gen_ai.usage.output_tokens", e.usage.outputTokens)
        e.parentThreadId.foreach(id => attributes.put(TraceAttributes.ParentThreadId, id))
        e.parentRunId.foreach(id => attributes.put(TraceAttributes.ParentRunId, id))
        e.provider.foreach(p => attributes.put(TraceAttributes.ProviderName, TraceAttributes.providerName(p.asString)))
        (s"${TraceAttributes.InvokeAgent} ${e.agent}", attributes.build())

      case e: TraceEvent.EmbeddingUsageRecorded =>
        val builder = Attributes
          .builder()
          .put(TraceAttributes.EventType, "embedding-usage")
          .put(TraceAttributes.OperationName, TraceAttributes.Embeddings)
          .put(TraceAttributes.RequestModel, e.model)
          .put("operation", e.operation)
          .put("input_count", e.inputCount.toLong)
          .put(TraceAttributes.InputTokens, e.usage.promptTokens.toLong)
          .put("gen_ai.usage.total_tokens", e.usage.totalTokens.toLong)
        e.provider.foreach(p => builder.put(TraceAttributes.ProviderName, TraceAttributes.providerName(p.asString)))
        (TraceAttributes.spanName(TraceAttributes.Embeddings, e.model), builder.build())

      case e: TraceEvent.CostRecorded =>
        (
          "Cost - " + e.operation,
          Attributes
            .builder()
            .put(TraceAttributes.EventType, "cost")
            .put("gen_ai.request.model", e.model)
            .put("operation", e.operation)
            .put("token_count", e.tokenCount.toLong)
            .put("cost.usd", e.costUsd)
            .put("cost.type", e.costType)
            .build()
        )

      case e: TraceEvent.RAGOperationCompleted =>
        (
          "RAG " + e.operation,
          Attributes
            .builder()
            .put(TraceAttributes.EventType, "rag-operation")
            .put("operation", e.operation)
            .put("duration_ms", e.duration.toMillis)
            .put("embedding_tokens", e.embeddingTokens.map(_.toLong).getOrElse(0L))
            .put("llm_prompt_tokens", e.llmPromptTokens.map(_.toLong).getOrElse(0L))
            .put("llm_completion_tokens", e.llmCompletionTokens.map(_.toLong).getOrElse(0L))
            .put("total_cost_usd", e.totalCostUsd.getOrElse(0.0))
            .build()
        )

      case e: TraceEvent.CacheHit =>
        (
          "Cache Hit",
          Attributes
            .builder()
            .put(TraceAttributes.EventType, "cache-hit")
            .put("similarity", e.similarity)
            .put("threshold", e.threshold)
            .put("timestamp", e.timestamp.toString)
            .build()
        )

      case e: TraceEvent.CacheMiss =>
        (
          "Cache Miss",
          Attributes
            .builder()
            .put(TraceAttributes.EventType, "cache-miss")
            .put("reason", e.reason.value)
            .put("timestamp", e.timestamp.toString)
            .build()
        )

      case e: TraceEvent.ImageGenerationCompleted =>
        val builder = Attributes
          .builder()
          .put(TraceAttributes.EventType, "image-generation")
          .put("gen_ai.request.model", e.model)
          .put("provider", e.provider)
          .put("operation", e.operation)
          .put("image_count", e.imageCount.toLong)
          .put("size", e.size)
          .put("quality", e.quality)
          .put("duration_ms", e.duration.toMillis)
          .put("success", e.success)
        e.costUsd.foreach(c => builder.put("cost.usd", c))
        e.errorMessage.foreach(m => builder.put("error.message", m))
        (
          "Image Generation - " + e.operation,
          builder.build()
        )
    }
  }

  /** Message text as a span records it: secrets redacted, then cut to [[TraceAttributes.ContentLimit]] characters. */
  private def content(text: String): String =
    Redaction.redact(Option(text).getOrElse("")).take(TraceAttributes.ContentLimit)

  /** The request parameters the GenAI conventions name, from the options a call was sent with. */
  private def requestParameters(builder: io.opentelemetry.api.common.AttributesBuilder, o: CompletionOptions): Unit = {
    builder.put(TraceAttributes.Temperature, o.temperature)
    builder.put(TraceAttributes.TopP, o.topP)
    o.maxTokens.foreach(n => builder.put(TraceAttributes.MaxTokens, n.toLong))
    builder.put(TraceAttributes.PresencePenalty, o.presencePenalty)
    builder.put(TraceAttributes.FrequencyPenalty, o.frequencyPenalty): Unit
  }

  /** `gen_ai.input.messages`: the request's messages, as the conventions' JSON, their text redacted. */
  private def inputMessages(messages: Seq[Message]): String =
    ujson
      .Arr(messages.map { m =>
        val parts: Seq[ujson.Value] = m match {
          case a: AssistantMessage =>
            val text = if (a.content.isEmpty) Seq.empty else Seq(textPart(a.content))
            text ++ a.toolCalls.map(c =>
              ujson.Obj(
                "type"      -> "tool_call",
                "id"        -> c.id,
                "name"      -> c.name,
                "arguments" -> content(c.arguments.render())
              )
            )
          case t: ToolMessage =>
            Seq(ujson.Obj("type" -> "tool_call_response", "id" -> t.toolCallId, "response" -> content(t.content)))
          case other => Seq(textPart(other.content))
        }
        ujson.Obj("role" -> m.role.name, "parts" -> ujson.Arr(parts*))
      }*)
      .render()

  /** `gen_ai.output.messages`: the completion's text as one assistant message, redacted. */
  private def outputMessage(text: String, finishReason: Option[String]): String = {
    val message = ujson.Obj("role" -> "assistant", "parts" -> ujson.Arr(textPart(text)))
    finishReason.foreach(r => message("finish_reason") = r)
    ujson.Arr(message).render()
  }

  private def textPart(text: String): ujson.Value = ujson.Obj("type" -> "text", "content" -> content(text))

  override def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = {
    val event = TraceEvent.ToolExecuted(toolName, input, output, scala.concurrent.duration.Duration.Zero, true)
    traceEvent(event)
  }

  override def traceError(error: Throwable, context: String): Result[Unit] = {
    val event = TraceEvent.ErrorOccurred(error, context)
    traceEvent(event)
  }

  override def traceCompletion(completion: Completion, model: String): Result[Unit] = {
    val event = TraceEvent.CompletionReceived(
      id = completion.id,
      model = model,
      toolCalls = completion.message.toolCalls.size,
      content = completion.message.content
    )
    traceEvent(event)
  }

  override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = {
    val event = TraceEvent.TokenUsageRecorded(usage, model, operation)
    traceEvent(event)
  }
}

private[trace] object TraceAttributes {
  val EventType        = AttributeKey.stringKey("event.type")
  val ToolName         = AttributeKey.stringKey("tool.name")
  val OperationName    = AttributeKey.stringKey("gen_ai.operation.name")
  val AgentName        = AttributeKey.stringKey("gen_ai.agent.name")
  val ConversationId   = AttributeKey.stringKey("gen_ai.conversation.id")
  val GenAiToolName    = AttributeKey.stringKey("gen_ai.tool.name")
  val GenAiToolCallId  = AttributeKey.stringKey("gen_ai.tool.call.id")
  val ParentRunId      = AttributeKey.stringKey("llm4s.parent.run_id")
  val ParentThreadId   = AttributeKey.stringKey("llm4s.parent.thread_id")
  val RequestModel     = AttributeKey.stringKey("gen_ai.request.model")
  val InputTokens      = AttributeKey.longKey("gen_ai.usage.input_tokens")
  val OutputTokens     = AttributeKey.longKey("gen_ai.usage.output_tokens")
  val Attempt          = AttributeKey.longKey("llm4s.model_call.attempt")
  val ToolCalls        = AttributeKey.longKey("llm4s.model_call.tool_calls")
  val CostUsd          = AttributeKey.doubleKey("cost.usd")
  val ProviderName     = AttributeKey.stringKey("gen_ai.provider.name")
  val ResponseModel    = AttributeKey.stringKey("gen_ai.response.model")
  val ResponseId       = AttributeKey.stringKey("gen_ai.response.id")
  val FinishReasons    = AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")
  val Temperature      = AttributeKey.doubleKey("gen_ai.request.temperature")
  val TopP             = AttributeKey.doubleKey("gen_ai.request.top_p")
  val MaxTokens        = AttributeKey.longKey("gen_ai.request.max_tokens")
  val PresencePenalty  = AttributeKey.doubleKey("gen_ai.request.presence_penalty")
  val FrequencyPenalty = AttributeKey.doubleKey("gen_ai.request.frequency_penalty")
  val ServerAddress    = AttributeKey.stringKey("server.address")
  val ErrorType        = AttributeKey.stringKey("error.type")
  val InputMessages    = AttributeKey.stringKey("gen_ai.input.messages")
  val OutputMessages   = AttributeKey.stringKey("gen_ai.output.messages")
  val Streamed         = AttributeKey.booleanKey("llm4s.model_call.streamed")

  /** The most characters of one piece of message content a span records, with `captureContent`. */
  val ContentLimit = 1000

  /**
   * `gen_ai.provider.name` for an llm4s provider id: the conventions' well-known value where there is one
   * (`gemini` is `gcp.gemini`, `bedrock` is `aws.bedrock`), else the id itself.
   */
  def providerName(id: String): String = WellKnownProviders.getOrElse(id, id)

  private val WellKnownProviders: Map[String, String] = Map(
    "azure"    -> "azure.ai.openai",
    "gemini"   -> "gcp.gemini",
    "vertexai" -> "gcp.vertex_ai",
    "bedrock"  -> "aws.bedrock",
    "watsonx"  -> "ibm.watsonx.ai",
    "mistral"  -> "mistral_ai",
    "xai"      -> "x_ai"
  )

  /** A model call's span name, `{operation} {model}`, or the operation alone when the model is unknown. */
  def spanName(operation: String, model: String): String =
    if (Option(model).forall(_.trim.isEmpty)) operation else s"$operation $model"

  /** An agent event's payload: the `payload` object of its custom event's data. */
  def payload(e: TraceEvent.CustomEvent): Option[collection.Map[String, ujson.Value]] =
    e.data.objOpt.flatMap(_.get("payload")).flatMap(_.objOpt)

  /** The GenAI semantic conventions' operation names, which also begin the span names. */
  val InvokeAgent = "invoke_agent"
  val ExecuteTool = "execute_tool"
  val Chat        = "chat"
  val Embeddings  = "embeddings"

  /** The custom event an agent's tracing sends for each tool call (`AgentEvents.ToolExecuted`). */
  val AgentToolExecuted = "agent.tool_executed"

  /** The custom event an agent's tracing sends for each model call (`AgentEvents.ModelCallCompleted`). */
  val AgentModelCallCompleted = "agent.model_call_completed"
}

/**
 * The SDK's id generator, handing out a placed span's own ids (design 4.58): the SDK draws a span id - and, for a root
 * span, a trace id - from it as a span starts, so [[startingAs]] sets them for that one start, on this thread. Every
 * other span gets random ids, as from `IdGenerator.random()`.
 */
final private[trace] class PlacedIds extends IdGenerator {
  private val random = IdGenerator.random()
  private val placed = new ThreadLocal[Option[TraceContext]] {
    override def initialValue(): Option[TraceContext] = None
  }

  def generateSpanId(): String  = placed.get.fold(random.generateSpanId())(_.spanId)
  def generateTraceId(): String = placed.get.fold(random.generateTraceId())(_.traceId)

  def startingAs[A](context: Option[TraceContext])(start: => A): A =
    if (context.isEmpty) start
    else {
      placed.set(context)
      try start
      finally placed.remove()
    }
}

object OpenTelemetryTracing {

  /**
   * The version of the OpenTelemetry semantic conventions the spans follow. The GenAI conventions are still in
   * Development status, so attribute names may change in a later version; the tracer reports this one as its schema.
   */
  val SemconvVersion: String = "1.37.0"

  /** The schema URL of [[SemconvVersion]], set on the tracer. */
  val SemconvSchemaUrl: String = s"https://opentelemetry.io/schemas/$SemconvVersion"

  /** A tracing that hands each span, as it ends, to `exporter`: for tests that read the spans back. */
  private[trace] def exportingTo(
    serviceName: String,
    exporter: SpanExporter,
    captureContent: Boolean = false
  ): OpenTelemetryTracing =
    new OpenTelemetryTracing(serviceName, () => SimpleSpanProcessor.create(exporter), captureContent)

  def from(config: OpenTelemetryConfig): OpenTelemetryTracing =
    new OpenTelemetryTracing(
      serviceName = config.serviceName,
      endpoint = config.endpoint,
      headers = config.headers,
      captureContent = config.captureContent
    )
}
