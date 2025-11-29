package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.llmconnect.config.TracingSettings

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

  /** Bridge helper: wrap an EnhancedTracing into legacy Tracing */
  def createFromEnhanced(enhanced: EnhancedTracing): Tracing = new TracingBridge(enhanced)

  /**
   * Create a Tracing instance from typed settings (no ConfigReader required).
   * This is the preferred API when you already resolved config into types.
   */
  def create(settings: TracingSettings): Tracing = {
    val enhanced = EnhancedTracing.create(settings)
    new TracingBridge(enhanced)
  }
}
