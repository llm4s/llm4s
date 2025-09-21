package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.llmconnect.model.{ TokenUsage, Completion }
import org.llm4s.types.Result

/**
 * Enhanced NoOp tracing implementation - does nothing but implements the interface
 */
class EnhancedNoOpTracing extends EnhancedTracing {
  def traceEvent(event: TraceEvent): Result[Unit]                                        = Right(())
  def traceAgentState(state: AgentState): Result[Unit]                                   = Right(())
  def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
  def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
  def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
  def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
}
