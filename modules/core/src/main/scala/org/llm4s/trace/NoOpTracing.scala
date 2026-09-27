package org.llm4s.trace

import org.llm4s.llmconnect.model.{ TokenUsage, Completion }
import org.llm4s.types.Result

/**
 * No-operation [[Tracing]] implementation that silently discards all events.
 *
 * All methods return `Right(())` immediately without performing any operations.
 * Use this implementation when tracing is disabled or not needed.
 *
 * == Usage ==
 *
 * {{{
 * val tracing: Tracing = new NoOpTracing()
 *
 * // All operations succeed silently
 * tracing.traceEvent(TraceEvent.CustomEvent("test", ujson.Obj())) // Returns Right(())
 * tracing.traceError(new Exception("ignored")) // Returns Right(())
 * }}}
 *
 * == Use Cases ==
 *
 *  - Production environments where tracing overhead is undesirable
 *  - Unit tests that don't need trace output
 *  - Default fallback when no tracing is configured
 *
 * @see [[ConsoleTracing]] for development/debugging
 * @see [[LangfuseTracing]] for production observability
 */
class NoOpTracing extends Tracing {

  /** Always returns `Right(())` without side effects. */
  def traceEvent(event: TraceEvent): Result[Unit] = Right(())

  /** Always returns `Right(())` without side effects. */
  def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = Right(())

  /** Always returns `Right(())` without side effects. */
  def traceError(error: Throwable, context: String): Result[Unit] = Right(())

  /** Always returns `Right(())` without side effects. */
  def traceCompletion(completion: Completion, model: String): Result[Unit] = Right(())

  /** Always returns `Right(())` without side effects. */
  def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
}
