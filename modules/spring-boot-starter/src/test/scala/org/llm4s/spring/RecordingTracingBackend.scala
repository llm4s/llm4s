package org.llm4s.spring

import org.llm4s.llmconnect.config.TracingSettings
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.trace.{ TraceEvent, Tracing, TracingMode }
import org.llm4s.trace.spi.TracingBackend
import org.llm4s.types.Result

import java.util.concurrent.CopyOnWriteArrayList

/** A tracer that keeps the events it is given, and the settings its backend was configured with. */
final class RecordingTracing(val extras: Map[String, String]) extends Tracing {
  val events: CopyOnWriteArrayList[TraceEvent] = new CopyOnWriteArrayList[TraceEvent]()
  @volatile var shutDown: Boolean              = false

  override def traceEvent(event: TraceEvent): Result[Unit] = { events.add(event); Right(()) }
  override def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
  override def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
  override def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
  override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
  override def shutdown(): Unit                                                                   = shutDown = true
}

/**
 * The `spring-recording` tracing mode, registered by this module's test `META-INF/services`: what the starter's
 * `llm4s.tracing.*` properties configure when a `TracingBackend` such as `llm4s-observability-otel`'s is present (#2279).
 */
final class RecordingTracingBackend extends TracingBackend {
  override def mode: TracingMode = TracingMode.Named(RecordingTracingBackend.Mode)

  override def create(settings: TracingSettings): Result[Tracing] = Right(new RecordingTracing(settings.extras))
}

object RecordingTracingBackend {
  final val Mode = "spring-recording"
}
