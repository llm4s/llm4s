package org.llm4s.trace

import org.llm4s.error.{ ConfigurationError, UnknownError }
import org.llm4s.llmconnect.config.TracingSettings
import org.llm4s.llmconnect.model.{ Completion, EmbeddingUsage, TokenUsage }
import org.llm4s.trace.spi.{ TracingBackend, TracingBackends }
import org.llm4s.types.Result

import java.util.Locale
import scala.util.control.NonFatal

/**
 * Type-safe tracing interface for observability and debugging.
 *
 * Provides a functional approach to tracing with `Result[Unit]` return types
 * for proper error handling and composition. Supports multiple backends
 * including console output, Langfuse, OpenTelemetry and custom implementations.
 *
 * == Implementations ==
 *
 *  - [[ConsoleTracing]] - Colored console output for development (core)
 *  - [[NoOpTracing]] - Silent implementation for testing/disabled tracing (core)
 *  - `LangfuseTracing` - Production observability via Langfuse (`llm4s-observability`)
 *  - `OpenTelemetryTracing` - OTLP export (`llm4s-observability-otel`)
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
 * == Agent state ==
 *
 * There is no `traceAgentState(AgentState)`: agent state is traced as an ordinary
 * [[TraceEvent.AgentStateUpdated]], built with `AgentState#toTraceEvent`, so the
 * tracing contract does not depend on the agent runtime (D5, #1133).
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
  def traceToolCall(toolName: String, input: String, output: String): Result[Unit]
  def traceError(error: Throwable, context: String = ""): Result[Unit]
  def traceCompletion(completion: Completion, model: String): Result[Unit]
  def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit]

  /**
   * Emits a named `CustomEvent` with no additional data payload.
   *
   * Wraps the string in a `TraceEvent.CustomEvent` with an empty JSON object
   * as its data field before delegating to `traceEvent(TraceEvent)`.
   * Use the typed overload for events that carry structured data.
   *
   * @param event Human-readable event name forwarded as `CustomEvent.name`
   */
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

/**
 * Fans a single event out to multiple [[Tracing]] backends.
 *
 * `traceEvent` returns `Right(())` as long as at least one backend succeeds.
 * Only when every backend returns a `Left` does this implementation propagate
 * a failure (the first error in the list).  Use this soft-failure behaviour to
 * prevent a single broken backend from silencing all observability.
 */
private class CompositeTracing(tracers: Vector[Tracing]) extends Tracing {
  def traceEvent(event: TraceEvent): Result[Unit] = {
    val results = tracers.map(_.traceEvent(event))
    val errors  = results.collect { case Left(error) => error }
    if (errors.size == results.size) Left(errors.head) else Right(())
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
 * Selects a tracing backend.
 *
 * `NoOp` and `Console` are built by core itself, and are the only modes core has a
 * case for. Every other mode is a [[TracingMode.Named]] served by a
 * [[org.llm4s.trace.spi.TracingBackend]] found on the classpath: `Named("langfuse")`
 * by `llm4s-observability`, `Named("opentelemetry")` by `llm4s-observability-otel`,
 * and any third-party backend the same way (D2, #1133). `TracingMode.Langfuse` and
 * `TracingMode.OpenTelemetry` were removed when their backends left core.
 *
 * The `TRACING_MODE` environment variable (`llm4s.tracing.mode`) is the standard
 * way to select a mode; see `Llm4sConfig.tracing`.
 */
sealed trait TracingMode extends Product with Serializable {

  /** The canonical config value for this mode, as [[TracingMode.fromString]] reads it. */
  def name: String
}

object TracingMode {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  case object Console extends TracingMode { val name = "console" }
  case object NoOp    extends TracingMode { val name = "noop"    }

  /**
   * A mode served by a [[org.llm4s.trace.spi.TracingBackend]] rather than by core.
   *
   * This is what makes the set of modes open: a backend declares
   * `TracingMode.Named("datadog")` and `TRACING_MODE=datadog` selects it, with no
   * edit to core. Langfuse (`Named("langfuse")`, in `llm4s-observability`) and
   * OpenTelemetry (`Named("opentelemetry")`, in `llm4s-observability-otel`) are
   * registered exactly this way. Matching against a backend is case-insensitive.
   *
   * @param name the mode's config value; [[fromString]] produces it lower-cased
   */
  final case class Named(name: String) extends TracingMode

  /**
   * Parses a mode string into a `TracingMode`, case-insensitively.
   *
   * `"console"` and `"print"` give `Console`; `"noop"` and `"none"` give `NoOp`.
   * `"otel"` is kept as an alias for `Named("opentelemetry")`. Any other non-blank
   * value, `"langfuse"` and `"opentelemetry"` included, becomes [[Named]], lower-cased,
   * for a backend outside core to claim; whether one does is decided when the
   * tracer is built, by `Tracing.fromSettings`. A blank value logs a warning and
   * returns `NoOp`.
   *
   * @param mode mode string, typically the value of the `TRACING_MODE` environment variable
   * @return the matching `TracingMode`
   */
  def fromString(mode: String): TracingMode = mode.trim.toLowerCase(Locale.ROOT) match {
    case "console" | "print" => Console
    case "noop" | "none"     => NoOp
    case "otel"              => Named("opentelemetry")
    case "" =>
      logger.warn("Blank tracing mode, falling back to NoOp")
      NoOp
    case other => Named(other)
  }
}

/**
 * Factory for creating [[Tracing]] instances.
 *
 * {{{
 * // From TracingSettings: falls back to NoOp, with an error logged, if the backend is missing
 * val tracing = Tracing.create(settings)
 *
 * // The same, but a missing or failing backend is an error the caller sees
 * val checked: Result[Tracing] = Tracing.fromSettings(settings)
 *
 * // Direct instantiation
 * val console = new ConsoleTracing()
 * val noop = new NoOpTracing()
 * }}}
 *
 * @see [[TracingMode]] for available modes
 * @see [[org.llm4s.trace.spi.TracingBackend]] for adding a backend
 */
object Tracing {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  // The llm4s artifact that registers each well-known mode, named in the missing-backend
  // error. A hint only: dispatch never consults it, and a third-party mode needs no entry.
  private val knownArtifacts: Map[String, String] = Map(
    "langfuse"      -> "llm4s-observability",
    "opentelemetry" -> "llm4s-observability-otel"
  )

  /**
   * Create a tracing instance from configuration settings.
   *
   * Never fails: when the configured backend is not on the classpath, or cannot
   * start, this logs the reason at error level and returns a [[NoOpTracing]], so a
   * tracing problem cannot stop the application. Use [[fromSettings]] to see the
   * error instead.
   *
   * @param settings Tracing configuration including mode and backend-specific options
   * @return Configured tracing instance, or `NoOpTracing`
   */
  def create(settings: TracingSettings): Tracing =
    fromSettings(settings) match {
      case Right(tracing) => tracing
      case Left(error) =>
        logger.error(s"${error.message} Falling back to NoOpTracing.")
        new NoOpTracing()
    }

  /**
   * Builds the tracer `settings` select, reporting a missing or failing backend as an error.
   *
   * `NoOp` and `Console` are built directly. Any other mode is dispatched to the
   * [[org.llm4s.trace.spi.TracingBackend]] discovered for it on the classpath.
   *
   * @return the tracer, or a [[org.llm4s.error.ConfigurationError]] when no backend
   *         is registered for the mode, or the backend's own error when it cannot start
   */
  def fromSettings(settings: TracingSettings): Result[Tracing] =
    resolve(settings, TracingBackends.discover())

  /**
   * As [[fromSettings(settings:*]], dispatching to `backends` rather than to what
   * is discovered - for an explicitly registered backend, or a shaded jar whose
   * services files did not survive.
   */
  def fromSettings(settings: TracingSettings, backends: TracingBackends): Result[Tracing] =
    resolve(settings, backends)

  // `backends` is by-name so NoOp and Console never pay for a classpath scan.
  private def resolve(settings: TracingSettings, backends: => TracingBackends): Result[Tracing] =
    canonical(settings.mode) match {
      case TracingMode.NoOp    => Right(new NoOpTracing())
      case TracingMode.Console => Right(new ConsoleTracing())
      case mode =>
        val available = backends
        available.find(mode) match {
          case Some(backend) => createWith(backend, settings)
          case None          => Left(missingBackend(mode, available))
        }
    }

  // A hand-built `Named("console")` means the built-in mode, as `fromString("console")` does.
  private def canonical(mode: TracingMode): TracingMode = mode match {
    case TracingMode.Named(name) => TracingMode.fromString(name)
    case other                   => other
  }

  // scalafix:off DisableSyntax.NoKeywordCatch
  private def createWith(backend: TracingBackend, settings: TracingSettings): Result[Tracing] =
    try backend.create(settings)
    catch {
      // A LinkageError here is a backend jar built against another llm4s, or missing its own
      // dependency; either way it is this backend's failure, not the application's.
      case error: LinkageError =>
        Left(UnknownError(s"Tracing backend ${backend.getClass.getName} failed to start: $error", error))
      case NonFatal(error) =>
        Left(UnknownError(s"Tracing backend ${backend.getClass.getName} failed to start: $error", error))
    }
  // scalafix:on DisableSyntax.NoKeywordCatch

  private def missingBackend(mode: TracingMode, backends: TracingBackends): ConfigurationError = {
    val hint = knownArtifacts
      .get(mode.name.toLowerCase(Locale.ROOT))
      .fold("")(artifact => s" Add the 'org.llm4s' %% '$artifact' dependency.")
    val registered = backends.modes.map(_.name) ++ Seq(TracingMode.Console.name, TracingMode.NoOp.name)
    val failures =
      if (backends.failures.isEmpty) ""
      else s" Discovery reported: ${backends.failures.mkString("; ")}."
    ConfigurationError(
      s"Tracing mode '${mode.name}' is configured but no TracingBackend for it is on the classpath.$hint " +
        s"Available modes: ${registered.mkString(", ")}.$failures"
    )
  }
}
