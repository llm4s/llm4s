package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.config.EnvLoader
import org.llm4s.llmconnect.model.{ TokenUsage, Completion }

trait Tracing {
  def traceEvent(event: String): Unit
  def traceAgentState(state: AgentState): Unit
  def traceToolCall(toolName: String, input: String, output: String): Unit
  def traceError(error: Throwable): Unit
  def traceCompletion(completion: Completion, model: String): Unit
  def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Unit
}

object Tracing {

  /**
   * Creates a Tracing instance based on the TRACING_MODE environment variable.
   *
   * Supported modes:
   * - "langfuse" (default): Uses LangfuseTracing to send traces to Langfuse
   * - "print": Uses PrintTracing to print traces to console
   * - "none": Uses NoOpTracing (no tracing)
   */
  def create(): Tracing = {
    val mode = EnvLoader.getOrElse("TRACING_MODE", "langfuse").toLowerCase

    mode match {
      case "langfuse" => new LangfuseTracing()
      case "print"    => new PrintTracing()
      case "none"     => new NoOpTracing()
      case other =>
        throw new IllegalArgumentException(
          s"Unknown TRACING_MODE: '$other'. Valid options: langfuse, print, none"
        )
    }
  }
}
