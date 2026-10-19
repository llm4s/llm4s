// scalafix:off DisableSyntax.NoKeywordFinally
package org.llm4s.trace

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import org.llm4s.error.UnknownError
import org.llm4s.llmconnect.config.OpenTelemetryConfig
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.types.Result

import java.util.concurrent.TimeUnit
import scala.util.Try

class OpenTelemetryTracing(
  serviceName: String,
  endpoint: String,
  headers: Map[String, String]
) extends Tracing {

  // Initialize OpenTelemetry SDK and Tracer, deferring errors to usage
  private val initializationResult: Either[Throwable, (OpenTelemetrySdk, Tracer)] = Try {
    val resource = Resource.getDefault.toBuilder
      .put(AttributeKey.stringKey("service.name"), serviceName)
      .build()

    val spanExporterBuilder = OtlpGrpcSpanExporter
      .builder()
      .setEndpoint(endpoint)

    headers.foreach { case (k, v) =>
      spanExporterBuilder.addHeader(k, v)
    }

    val spanProcessor = BatchSpanProcessor
      .builder(spanExporterBuilder.build())
      .setMaxExportBatchSize(512)
      .setScheduleDelay(5, TimeUnit.SECONDS)
      .build()

    val tracerProvider = SdkTracerProvider
      .builder()
      .setResource(resource)
      .addSpanProcessor(spanProcessor)
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

        val span = spanBuilder.startSpan()

        try
          if (spanName == "Error") {
            event match {
              case e: TraceEvent.ErrorOccurred => span.recordException(e.error)
              case _                           =>
            }
          }
        finally
          span.end()
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
    case _ =>
      SpanKind.CLIENT
  }

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

  /** The GenAI semantic conventions' operation names, which also begin the span names. */
  val InvokeAgent = "invoke_agent"
  val ExecuteTool = "execute_tool"

  /** The custom event an agent's tracing sends for each tool call (`AgentEvents.ToolExecuted`). */
  val AgentToolExecuted = "agent.tool_executed"
}

object OpenTelemetryTracing {
  def from(config: OpenTelemetryConfig): OpenTelemetryTracing =
    new OpenTelemetryTracing(
      serviceName = config.serviceName,
      endpoint = config.endpoint,
      headers = config.headers
    )
}
