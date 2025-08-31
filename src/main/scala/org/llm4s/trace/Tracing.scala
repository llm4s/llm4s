package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }

/**
 * Legacy Tracing interface for backward compatibility
 *
 * @deprecated Use EnhancedTracing for new code
 */
trait Tracing {
  def traceEvent(event: String): Unit
  def traceAgentState(state: AgentState): Unit
  def traceToolCall(toolName: String, input: String, output: String): Unit
  def traceError(error: Throwable): Unit
  def traceCompletion(completion: Completion, model: String): Unit
  def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Unit
}

/**
 * Bridge adapter to convert EnhancedTracing to legacy Tracing
 */
private class TracingBridge(enhanced: EnhancedTracing) extends Tracing {
  def traceEvent(event: String): Unit =
    enhanced.traceEvent(event).left.foreach(error => throw new RuntimeException(s"Tracing error: ${error.message}"))

  def traceAgentState(state: AgentState): Unit =
    enhanced
      .traceAgentState(state)
      .left
      .foreach(error => throw new RuntimeException(s"Tracing error: ${error.message}"))

  def traceToolCall(toolName: String, input: String, output: String): Unit =
    enhanced
      .traceToolCall(toolName, input, output)
      .left
      .foreach(error => throw new RuntimeException(s"Tracing error: ${error.message}"))

  def traceError(error: Throwable): Unit =
    enhanced
      .traceError(error)
      .left
      .foreach(traceError => throw new RuntimeException(s"Tracing error: ${traceError.message}"))

  def traceCompletion(completion: Completion, model: String): Unit =
    enhanced
      .traceCompletion(completion, model)
      .left
      .foreach(error => throw new RuntimeException(s"Tracing error: ${error.message}"))

  def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Unit =
    enhanced
      .traceTokenUsage(usage, model, operation)
      .left
      .foreach(error => throw new RuntimeException(s"Tracing error: ${error.message}"))
}

object Tracing {

  /**
   * Creates a Tracing instance based on the TRACING_MODE environment variable.
   *
   * Supported modes:
   * - "langfuse" (default): Uses LangfuseTracing to send traces to Langfuse
   * - "print": Uses PrintTracing to print traces to console
   * - "none": Uses NoOpTracing (no tracing)
   *
   * @deprecated Use EnhancedTracing.create() for new code
   */
  def create()(config: ConfigReader): Tracing = {
    val enhanced = EnhancedTracing.create()(config)
    new TracingBridge(enhanced)
  }

  /**
   * Creates a Tracing instance with the specified mode
   *
   * @deprecated Use EnhancedTracing.create(mode) for new code
   */
  def create(mode: String)(config: ConfigReader): Tracing = {
    val enhanced = EnhancedTracing.create(mode)(config)
    new TracingBridge(enhanced)
  }
}
