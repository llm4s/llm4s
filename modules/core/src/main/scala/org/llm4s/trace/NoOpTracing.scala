package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.llmconnect.model.{ TokenUsage, Completion }

class NoOpTracing extends Tracing {
  override def traceEvent(event: String): Unit                                            = ()
  override def traceAgentState(state: AgentState): Unit                                   = ()
  override def traceToolCall(toolName: String, input: String, output: String): Unit       = ()
  override def traceError(error: Throwable): Unit                                         = ()
  override def traceCompletion(completion: Completion, model: String): Unit               = ()
  override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Unit = ()
}
