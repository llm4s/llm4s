package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.llmconnect.model.{ Completion, EmbeddingUsage, TokenUsage }
import org.llm4s.types.Result

/**
 * Type-safe tracing interface for observability and debugging.
 *
 * Provides a functional approach to tracing with `Result[Unit]` return types
 * for proper error handling and composition. Supports multiple backends
 * including console output, Langfuse, and custom implementations.
 *
 * == Implementations ==
 *
 *  - [[LangfuseTracing]] - Production observability via Langfuse
 *  - [[ConsoleTracing]] - Colored console output for development
 *  - [[NoOpTracing]] - Silent implementation for testing/disabled tracing
 *
 * == Usage ==
 *
 * {{{
 * // Create from settings
 * val tracing = Tracing.create(settings)
 *
 * // Or use directly
 * val tracing: Tracing = new ConsoleTracing()
 *
 * // Trace events functionally
 * for {
 *   _ <- tracing.traceEvent(TraceEvent.AgentInitialized("query", tools))
 *   _ <- tracing.traceTokenUsage(usage, "gpt-4", "completion")
 * } yield ()
 * }}}
 *
 * == Composition ==
 *
 * Tracers can be composed using [[TracingComposer]]:
 *
 * {{{
 * val combined = TracingComposer.combine(consoleTracer, langfuseTracer)
 * val filtered = TracingComposer.filter(tracer)(_.eventType == "error_occurred")
 * }}}
 *
 * @see [[TraceEvent]] for available event types
 * @see [[TracingComposer]] for composition utilities
 */
trait Tracing {
  def traceEvent(event: TraceEvent): Result[Unit]
  def traceAgentState(state: AgentState): Result[Unit]
  def traceToolCall(toolName: String, input: String, output: String): Result[Unit]
  def traceError(error: Throwable, context: String = ""): Result[Unit]
  def traceCompletion(completion: Completion, model: String): Result[Unit]
  def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit]

  // Convenience methods that delegate to traceEvent
  final def traceEvent(event: String): Result[Unit] = {
    val customEvent = TraceEvent.CustomEvent(event, ujson.Obj())
    this.traceEvent(customEvent)
  }

  /**
   * Trace embedding token usage for cost tracking.
   *
   * @param usage Token usage from embedding operation
   * @param model Embedding model name
   * @param operation Type: "indexing", "query", "evaluation"
   * @param inputCount Number of texts embedded
   */
  final def traceEmbeddingUsage(
    usage: EmbeddingUsage,
    model: String,
    operation: String,
    inputCount: Int
  ): Result[Unit] = {
    val event = TraceEvent.EmbeddingUsageRecorded(usage, model, operation, inputCount)
    this.traceEvent(event)
  }

  /**
   * Trace cost in USD for any operation.
   *
   * @param costUsd Cost in US dollars
   * @param model Model name
   * @param operation Type: "embedding", "completion", "evaluation"
   * @param tokenCount Total tokens used
   * @param costType Category: "embedding", "completion", "total"
   */
  final def traceCost(
    costUsd: Double,
    model: String,
    operation: String,
    tokenCount: Int,
    costType: String
  ): Result[Unit] = {
    val event = TraceEvent.CostRecorded(costUsd, model, operation, tokenCount, costType)
    this.traceEvent(event)
  }

  /**
   * Trace completion of a RAG operation with metrics.
   *
   * @param operation Type: "index", "search", "answer", "evaluate"
   * @param durationMs Duration in milliseconds
   * @param embeddingTokens Optional embedding token count
   * @param llmPromptTokens Optional LLM prompt tokens
   * @param llmCompletionTokens Optional LLM completion tokens
   * @param totalCostUsd Optional total cost in USD
   */
  final def traceRAGOperation(
    operation: String,
    durationMs: Long,
    embeddingTokens: Option[Int] = None,
    llmPromptTokens: Option[Int] = None,
    llmCompletionTokens: Option[Int] = None,
    totalCostUsd: Option[Double] = None
  ): Result[Unit] = {
    val event = TraceEvent.RAGOperationCompleted(
      operation,
      durationMs,
      embeddingTokens,
      llmPromptTokens,
      llmCompletionTokens,
      totalCostUsd
    )
    this.traceEvent(event)
  }

  /**
   * Shutdown the tracing backend.
   * Alias for close() to maintain terminology consistency.
   */
  def shutdown(): Unit = {}
}

/**
 * Utilities for composing multiple tracers.
 *
 * Provides functional composition patterns for combining, filtering,
 * and transforming trace events across multiple tracing backends.
 *
 * == Combining Tracers ==
 *
 * Send events to multiple backends simultaneously:
 *
 * {{{
 * val combined = TracingComposer.combine(consoleTracer, langfuseTracer)
 * combined.traceEvent(event) // Sends to both
 * }}}
 *
 * == Filtering Events ==
 *
 * Only trace events matching a predicate:
 *
 * {{{
 * val errorsOnly = TracingComposer.filter(tracer)(_.eventType == "error_occurred")
 * }}}
 *
 * == Transforming Events ==
 *
 * Modify events before tracing:
 *
 * {{{
 * val enriched = TracingComposer.transform(tracer) {
 *   case e: TraceEvent.CustomEvent => e.copy(name = "prefix_" + e.name)
 *   case other => other
 * }
 * }}}
 */
trait TracingComposer {

  /** Combine multiple tracers into one that sends events to all backends. */
  def combine(tracers: Tracing*): Tracing = new CompositeTracing(tracers.toVector)

  /** Filter events before sending to the underlying tracer. */
  def filter(tracer: Tracing)(predicate: TraceEvent => Boolean): Tracing =
    new FilteredTracing(tracer, predicate)

  /** Transform events before sending to the underlying tracer. */
  def transform(tracer: Tracing)(f: TraceEvent => TraceEvent): Tracing =
    new TransformedTracing(tracer, f)
}

object TracingComposer extends TracingComposer

/** Internal composite tracing implementation. */
private class CompositeTracing(tracers: Vector[Tracing]) extends Tracing {
  def traceEvent(event: TraceEvent): Result[Unit] = {
    val results = tracers.map(_.traceEvent(event))
    val errors  = results.collect { case Left(error) => error }
    if (errors.size == results.size) Left(errors.head) else Right(())
  }

  def traceAgentState(state: AgentState): Result[Unit] = {
    val event = TraceEvent.AgentStateUpdated(
      status = state.status.toString,
      messageCount = state.conversation.messages.length,
      logCount = state.logs.length
    )
    traceEvent(event)
  }

  def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = {
    val event = TraceEvent.ToolExecuted(toolName, input, output, 0, true)
    traceEvent(event)
  }

  def traceError(error: Throwable, context: String): Result[Unit] = {
    val event = TraceEvent.ErrorOccurred(error, context)
    traceEvent(event)
  }

  def traceCompletion(completion: Completion, model: String): Result[Unit] = {
    val event = TraceEvent.CompletionReceived(
      id = completion.id,
      model = model,
      toolCalls = completion.message.toolCalls.size,
      content = completion.message.content
    )
    traceEvent(event)
  }

  def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = {
    val event = TraceEvent.TokenUsageRecorded(usage, model, operation)
    traceEvent(event)
  }

  override def shutdown(): Unit = tracers.foreach(_.shutdown())
}

private class FilteredTracing(underlying: Tracing, predicate: TraceEvent => Boolean) extends Tracing {
  def traceEvent(event: TraceEvent): Result[Unit] =
    if (predicate(event)) underlying.traceEvent(event) else Right(())

  def traceAgentState(state: AgentState): Result[Unit] = underlying.traceAgentState(state)
  def traceToolCall(toolName: String, input: String, output: String): Result[Unit] =
    underlying.traceToolCall(toolName, input, output)
  def traceError(error: Throwable, context: String): Result[Unit] = underlying.traceError(error, context)
  def traceCompletion(completion: Completion, model: String): Result[Unit] =
    underlying.traceCompletion(completion, model)
  def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] =
    underlying.traceTokenUsage(usage, model, operation)

  override def shutdown(): Unit = underlying.shutdown()
}

private class TransformedTracing(underlying: Tracing, transform: TraceEvent => TraceEvent) extends Tracing {
  def traceEvent(event: TraceEvent): Result[Unit] =
    underlying.traceEvent(transform(event))

  def traceAgentState(state: AgentState): Result[Unit] = underlying.traceAgentState(state)
  def traceToolCall(toolName: String, input: String, output: String): Result[Unit] =
    underlying.traceToolCall(toolName, input, output)
  def traceError(error: Throwable, context: String): Result[Unit] = underlying.traceError(error, context)
  def traceCompletion(completion: Completion, model: String): Result[Unit] =
    underlying.traceCompletion(completion, model)
  def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] =
    underlying.traceTokenUsage(usage, model, operation)

  override def shutdown(): Unit = underlying.shutdown()
}

/**
 * Type-safe tracing modes
 */
sealed trait TracingMode extends Product with Serializable

object TracingMode {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  case object Langfuse      extends TracingMode
  case object Console       extends TracingMode
  case object OpenTelemetry extends TracingMode
  case object NoOp          extends TracingMode

  def fromString(mode: String): TracingMode = mode.toLowerCase match {
    case "langfuse"               => Langfuse
    case "console" | "print"      => Console
    case "opentelemetry" | "otel" => OpenTelemetry
    case "noop" | "none"          => NoOp
    case other =>
      logger.warn(s"Unknown tracing mode '$other', falling back to NoOp")
      NoOp
  }
}

/**
 * Factory for creating [[Tracing]] instances.
 *
 * Creates the appropriate tracing implementation based on configuration settings.
 *
 * {{{
 * // From TracingSettings
 * val tracing = Tracing.create(settings)
 *
 * // Direct instantiation
 * val console = new ConsoleTracing()
 * val noop = new NoOpTracing()
 * }}}
 *
 * @see [[TracingMode]] for available modes
 */
object Tracing {

  /**
   * Create a tracing instance from configuration settings.
   *
   * @param settings Tracing configuration including mode and backend-specific options
   * @return Configured tracing instance
   */
  def create(settings: org.llm4s.llmconnect.config.TracingSettings): Tracing = settings.mode match {
    case TracingMode.Langfuse =>
      val lf = settings.langfuse
      new LangfuseTracing(
        lf.url,
        lf.publicKey.getOrElse(""),
        lf.secretKey.getOrElse(""),
        lf.env,
        lf.release,
        lf.version
      )
    case TracingMode.Console => new ConsoleTracing()
    case TracingMode.OpenTelemetry =>
      val ot = settings.openTelemetry
      try {
        val clazz = Class.forName("org.llm4s.trace.OpenTelemetryTracing")
        val ctor  = clazz.getConstructor(classOf[String], classOf[String], classOf[Map[String, String]])
        ctor.newInstance(ot.serviceName, ot.endpoint, ot.headers).asInstanceOf[Tracing]
      } catch {
        case _: ClassNotFoundException | _: NoClassDefFoundError =>
          val logger = org.slf4j.LoggerFactory.getLogger(getClass)
          logger.error(
            "OpenTelemetry tracing configured but 'trace-opentelemetry' module not found on classpath. " +
              "Please add 'org.llm4s' %% 'llm4s-trace-opentelemetry' dependency. Falling back to NoOpTracing."
          )
          new NoOpTracing()
        case e: java.lang.reflect.InvocationTargetException =>
          val logger = org.slf4j.LoggerFactory.getLogger(getClass)
          logger.error("OpenTelemetry tracing initialization failed", e.getCause)
          new NoOpTracing()
        case e: Throwable =>
          val logger = org.slf4j.LoggerFactory.getLogger(getClass)
          logger.error("Failed to initialize OpenTelemetry tracing. Falling back to NoOpTracing.", e)
          new NoOpTracing()
      }
    case TracingMode.NoOp => new NoOpTracing()
  }
}
