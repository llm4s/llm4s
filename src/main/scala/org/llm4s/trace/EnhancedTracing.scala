package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.types.Result

/**
 * Enhanced type-safe tracing interface using functional composition
 */
trait EnhancedTracing {
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
}

/**
 * Composable tracing using functional composition
 */
trait TracingComposer {
  def combine(tracers: EnhancedTracing*): EnhancedTracing = new CompositeTracing(tracers.toVector)
  def filter(tracer: EnhancedTracing)(predicate: TraceEvent => Boolean): EnhancedTracing =
    new FilteredTracing(tracer, predicate)
  def transform(tracer: EnhancedTracing)(f: TraceEvent => TraceEvent): EnhancedTracing =
    new TransformedTracing(tracer, f)
}

object TracingComposer extends TracingComposer

private class CompositeTracing(tracers: Vector[EnhancedTracing]) extends EnhancedTracing {
  def traceEvent(event: TraceEvent): Result[Unit] = {
    val results = tracers.map(_.traceEvent(event))
    // Collect all errors, succeed if at least one succeeds
    val errors = results.collect { case Left(error) => error }
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
}

private class FilteredTracing(underlying: EnhancedTracing, predicate: TraceEvent => Boolean) extends EnhancedTracing {
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
}

private class TransformedTracing(underlying: EnhancedTracing, transform: TraceEvent => TraceEvent)
    extends EnhancedTracing {
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
}

/**
 * Type-safe tracing modes
 */
sealed trait TracingMode extends Product with Serializable

object TracingMode {
  case object Langfuse extends TracingMode
  case object Console  extends TracingMode
  case object NoOp     extends TracingMode

  def fromString(mode: String): TracingMode = mode.toLowerCase match {
    case "langfuse"          => Langfuse
    case "console" | "print" => Console
    case "noop" | "none"     => NoOp
    case _                   => NoOp
  }
}

/**
 * Enhanced factory for creating tracing instances
 */
object EnhancedTracing {
  def create(mode: TracingMode)(config: ConfigReader): EnhancedTracing = mode match {
    case TracingMode.Langfuse => EnhancedLangfuseTracing(config)
    case TracingMode.Console  => new EnhancedConsoleTracing()
    case TracingMode.NoOp     => new EnhancedNoOpTracing()
  }

  def create()(config: ConfigReader): EnhancedTracing = {
    val mode = sys.env
      .get("TRACING_MODE")
      .map(TracingMode.fromString)
      .getOrElse(TracingMode.Console)
    create(mode)(config)
  }

  def create(mode: String)(config: ConfigReader): EnhancedTracing = create(TracingMode.fromString(mode))(config)
}
