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
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.types.Result

import java.util.concurrent.TimeUnit
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
 */
class OpenTelemetryTracing private[trace] (
  serviceName: String,
  newProcessor: () => SpanProcessor
) extends Tracing {

  def this(serviceName: String, endpoint: String, headers: Map[String, String]) =
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
      }
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

    val tracer = openTelemetry.getTracer("org.llm4s")
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

        val span = ids.startingAs(event.span.map(_.context))(spanBuilder.startSpan())

        try annotate(span, spanName, event)
        finally if (event.span.isDefined) span.end(event.timestamp) else span.end()
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

  /** Records an error event's exception on its span, and an `ERROR` status for what [[failed]] names. */
  private def annotate(span: Span, spanName: String, event: TraceEvent): Unit = {
    if (spanName == "Error") {
      event match {
        case e: TraceEvent.ErrorOccurred => span.recordException(e.error): Unit
        case _                           =>
      }
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
        (
          "LLM4S Agent Run",
          Attributes
            .builder()
            .put(TraceAttributes.EventType, "trace-create")
            .put("input", e.query.take(1000))
            .put("tools", e.tools.mkString(", "))
            .build()
        )

      case e: TraceEvent.CompletionReceived =>
        (
          "LLM Completion",
          Attributes
            .builder()
            .put(TraceAttributes.EventType, "generation-create")
            .put("gen_ai.request.model", e.model)
            .put("completion_id", e.id)
            .put("tool_calls", e.toolCalls.toLong)
            .put("content", e.content.take(1000))
            .build()
        )

      case e: TraceEvent.ToolExecuted =>
        (
          s"${TraceAttributes.ExecuteTool} ${e.name}",
          Attributes
            .builder()
            .put(TraceAttributes.EventType, "span-create")
            .put(TraceAttributes.OperationName, TraceAttributes.ExecuteTool)
            .put(TraceAttributes.ToolName, e.name)
            .put(TraceAttributes.GenAiToolName, e.name)
            .put("tool.input", e.input.take(1000))
            .put("tool.output", e.output.take(1000))
            .put("duration_ms", e.duration.toMillis)
            .put("success", e.success)
            .build()
        )

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
        (s"${TraceAttributes.InvokeAgent} ${e.agent}", attributes.build())

      case e: TraceEvent.EmbeddingUsageRecorded =>
        (
          "Embedding Usage - " + e.operation,
          Attributes
            .builder()
            .put(TraceAttributes.EventType, "embedding-usage")
            .put("gen_ai.request.model", e.model)
            .put("operation", e.operation)
            .put("input_count", e.inputCount.toLong)
            .put("gen_ai.usage.input_tokens", e.usage.promptTokens.toLong)
            .put("gen_ai.usage.total_tokens", e.usage.totalTokens.toLong)
            .build()
        )

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
  val EventType       = AttributeKey.stringKey("event.type")
  val ToolName        = AttributeKey.stringKey("tool.name")
  val OperationName   = AttributeKey.stringKey("gen_ai.operation.name")
  val AgentName       = AttributeKey.stringKey("gen_ai.agent.name")
  val ConversationId  = AttributeKey.stringKey("gen_ai.conversation.id")
  val GenAiToolName   = AttributeKey.stringKey("gen_ai.tool.name")
  val GenAiToolCallId = AttributeKey.stringKey("gen_ai.tool.call.id")
  val ParentRunId     = AttributeKey.stringKey("llm4s.parent.run_id")
  val ParentThreadId  = AttributeKey.stringKey("llm4s.parent.thread_id")
  val RequestModel    = AttributeKey.stringKey("gen_ai.request.model")
  val InputTokens     = AttributeKey.longKey("gen_ai.usage.input_tokens")
  val OutputTokens    = AttributeKey.longKey("gen_ai.usage.output_tokens")
  val Attempt         = AttributeKey.longKey("llm4s.model_call.attempt")
  val ToolCalls       = AttributeKey.longKey("llm4s.model_call.tool_calls")
  val CostUsd         = AttributeKey.doubleKey("cost.usd")

  /** An agent event's payload: the `payload` object of its custom event's data. */
  def payload(e: TraceEvent.CustomEvent): Option[collection.Map[String, ujson.Value]] =
    e.data.objOpt.flatMap(_.get("payload")).flatMap(_.objOpt)

  /** The GenAI semantic conventions' operation names, which also begin the span names. */
  val InvokeAgent = "invoke_agent"
  val ExecuteTool = "execute_tool"
  val Chat        = "chat"

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

  /** A tracing that hands each span, as it ends, to `exporter`: for tests that read the spans back. */
  private[trace] def exportingTo(serviceName: String, exporter: SpanExporter): OpenTelemetryTracing =
    new OpenTelemetryTracing(serviceName, () => SimpleSpanProcessor.create(exporter))

  def from(config: OpenTelemetryConfig): OpenTelemetryTracing =
    new OpenTelemetryTracing(
      serviceName = config.serviceName,
      endpoint = config.endpoint,
      headers = config.headers
    )
}
