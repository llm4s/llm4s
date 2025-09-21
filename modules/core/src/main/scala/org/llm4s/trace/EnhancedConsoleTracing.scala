package org.llm4s.trace

import org.llm4s.error.UnknownError
import org.llm4s.agent.AgentState
import org.llm4s.llmconnect.model.{ TokenUsage, Completion }
import org.llm4s.types.Result
import scala.util.Try

/**
 * Enhanced console tracing with type-safe events and better formatting
 */
class EnhancedConsoleTracing extends EnhancedTracing {
  // ANSI color codes for better readability
  private val RESET   = "\u001b[0m"
  private val BLUE    = "\u001b[34m"
  private val GREEN   = "\u001b[32m"
  private val YELLOW  = "\u001b[33m"
  private val RED     = "\u001b[31m"
  private val CYAN    = "\u001b[36m"
  private val MAGENTA = "\u001b[35m"
  private val GRAY    = "\u001b[90m"
  private val BOLD    = "\u001b[1m"

  private def printHeader(title: String): Unit = {
    val separator = "=" * 60
    println(s"$CYAN$BOLD$separator$RESET")
    println(s"$CYAN$BOLD$title$RESET")
    println(s"$CYAN$BOLD$separator$RESET")
  }

  private def printSubHeader(title: String, color: String): Unit =
    println(s"$color$BOLD--- $title ---$RESET")

  private def formatJson(json: String, maxLength: Int): String =
    if (json.length > maxLength) {
      json.take(maxLength) + "..."
    } else {
      json
    }

  def traceEvent(event: TraceEvent): Result[Unit] =
    Try {
      event match {
        case e: TraceEvent.AgentInitialized =>
          println()
          printSubHeader("AGENT INITIALIZED", GREEN)
          println(s"${GRAY}Timestamp: ${e.timestamp}$RESET")
          println(s"${GREEN}Query: ${e.query}$RESET")
          println(s"${GREEN}Tools: ${e.tools.mkString(", ")}$RESET")
          println()

        case e: TraceEvent.CompletionReceived =>
          println()
          printHeader("COMPLETION RECEIVED")
          println(s"${GRAY}Timestamp: ${e.timestamp}$RESET")
          println(s"${GREEN}Model: ${e.model}$RESET")
          println(s"${GREEN}ID: ${e.id}$RESET")
          println(s"${GREEN}Tool Calls: ${e.toolCalls}$RESET")
          println(s"${GREEN}Content: ${formatJson(e.content, 200)}$RESET")
          println()

        case e: TraceEvent.ToolExecuted =>
          println()
          printSubHeader("TOOL EXECUTED", CYAN)
          println(s"${GRAY}Timestamp: ${e.timestamp}$RESET")
          println(s"${CYAN}Tool: ${e.name}$RESET")
          println(s"${CYAN}Success: ${e.success}$RESET")
          println(s"${CYAN}Duration: ${e.duration}ms$RESET")
          println(s"${YELLOW}Input: ${formatJson(e.input, 100)}$RESET")
          println(s"${GREEN}Output: ${formatJson(e.output, 100)}$RESET")
          println()

        case e: TraceEvent.ErrorOccurred =>
          println()
          printHeader("ERROR OCCURRED")
          println(s"${GRAY}Timestamp: ${e.timestamp}$RESET")
          println(s"${RED}Type: ${e.error.getClass.getSimpleName}$RESET")
          println(s"${RED}Message: ${e.error.getMessage}$RESET")
          println(s"${RED}Context: ${e.context}$RESET")
          println()

        case e: TraceEvent.TokenUsageRecorded =>
          println()
          printSubHeader("TOKEN USAGE", MAGENTA)
          println(s"${GRAY}Timestamp: ${e.timestamp}$RESET")
          println(s"${MAGENTA}Model: ${e.model}$RESET")
          println(s"${MAGENTA}Operation: ${e.operation}$RESET")
          println(s"${MAGENTA}Prompt Tokens: ${e.usage.promptTokens}$RESET")
          println(s"${MAGENTA}Completion Tokens: ${e.usage.completionTokens}$RESET")
          println(s"${MAGENTA}Total Tokens: ${e.usage.totalTokens}$RESET")
          println()

        case e: TraceEvent.AgentStateUpdated =>
          println()
          printSubHeader("AGENT STATE UPDATED", BLUE)
          println(s"${GRAY}Timestamp: ${e.timestamp}$RESET")
          println(s"${BLUE}Status: ${e.status}$RESET")
          println(s"${BLUE}Messages: ${e.messageCount}$RESET")
          println(s"${BLUE}Logs: ${e.logCount}$RESET")
          println()

        case e: TraceEvent.CustomEvent =>
          println()
          printSubHeader("CUSTOM EVENT", YELLOW)
          println(s"${GRAY}Timestamp: ${e.timestamp}$RESET")
          println(s"${YELLOW}Name: ${e.name}$RESET")
          println(s"${YELLOW}Data: ${e.data}$RESET")
          println()
      }
    }.toEither.left.map(error => UnknownError(error.getMessage, error))

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
