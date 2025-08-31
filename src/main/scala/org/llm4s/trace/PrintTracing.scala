package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.llmconnect.model.MessageRole.{ Assistant, System, Tool, User }
import org.llm4s.llmconnect.model.{ AssistantMessage, ToolMessage }

import java.time.Instant
import java.time.format.DateTimeFormatter

class PrintTracing extends Tracing {
  private def timestamp: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

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

  private def printHeader(title: String, color: String = CYAN): Unit = {
    val separator = "=" * 60
    println(s"$color$BOLD$separator$RESET")
    println(s"$color$BOLD$title$RESET")
    println(s"$color$BOLD$separator$RESET")
  }

  private def printSubHeader(title: String, color: String): Unit =
    println(s"$color$BOLD--- $title ---$RESET")

  private def formatJson(json: String, maxLength: Int): String =
    if (json.length > maxLength) {
      json.take(maxLength) + "..."
    } else {
      json
    }

  override def traceEvent(event: String): Unit = {
    println()
    printSubHeader("EVENT", GREEN)
    println(s"${GRAY}Timestamp: $timestamp$RESET")
    println(s"${GREEN}Event: $event$RESET")
    println()
  }

  override def traceAgentState(state: AgentState): Unit = {
    println()
    printHeader("AGENT STATE TRACE")
    println(s"${GRAY}Timestamp: $timestamp$RESET")
    println()

    // Basic info
    printSubHeader("Agent Overview", BLUE)
    println(s"${BLUE}Status: ${state.status}$RESET")
    println(s"${BLUE}User Query: ${state.userQuery}$RESET")
    println(s"${BLUE}Total Messages: ${state.conversation.messages.length}$RESET")
    println(s"${BLUE}Available Tools: ${state.tools.tools.map(_.name).mkString(", ")}$RESET")
    println()

    // Conversation history
    printSubHeader("Conversation History", MAGENTA)
    state.conversation.messages.zipWithIndex.foreach { case (msg, idx) =>
      val roleColor = msg.role match {
        case User      => BLUE
        case Assistant => GREEN
        case System    => YELLOW
        case Tool      => CYAN
      }

      println(s"${BOLD}Message ${idx + 1} (${roleColor}${msg.role}${RESET}${BOLD}):$RESET")

      msg match {
        case am: AssistantMessage if am.toolCalls.nonEmpty =>
          println(s"  ${GREEN}Content: ${am.content}$RESET")
          println(s"  ${YELLOW}Tool Calls: ${am.toolCalls.length}$RESET")
          am.toolCalls.zipWithIndex.foreach { case (tc, tcIdx) =>
            println(s"    ${YELLOW}${tcIdx + 1}. ${tc.name}(${formatJson(tc.arguments.render(), 100)})$RESET")
          }
        case tm: ToolMessage =>
          println(s"  ${CYAN}Tool Call ID: ${tm.toolCallId}$RESET")
          println(s"  ${CYAN}Content: ${formatJson(tm.content, 150)}$RESET")
        case _ =>
          println(s"  ${roleColor}Content: ${msg.content}$RESET")
      }
      println()
    }

    // Execution logs
    if (state.logs.nonEmpty) {
      printSubHeader("Execution Logs", YELLOW)
      state.logs.zipWithIndex.foreach { case (log, idx) =>
        val logColor = log match {
          case l if l.startsWith("[assistant]") => GREEN
          case l if l.startsWith("[tool]")      => CYAN
          case l if l.startsWith("[tools]")     => YELLOW
          case l if l.startsWith("[system]")    => RED
          case _                                => GRAY
        }
        println(s"${BOLD}${idx + 1}.${RESET} ${logColor}$log$RESET")
      }
      println()
    }

    println(s"${GRAY}${"=" * 60}$RESET")
    println()
  }

  override def traceToolCall(toolName: String, input: String, output: String): Unit = {
    println()
    printSubHeader("TOOL CALL", CYAN)
    println(s"${GRAY}Timestamp: $timestamp$RESET")
    println(s"${CYAN}${BOLD}Tool: $toolName$RESET")
    println()

    println(s"${YELLOW}Input:$RESET")
    println(s"  ${formatJson(input, 300)}")
    println()

    println(s"${GREEN}Output:$RESET")
    println(s"  ${formatJson(output, 300)}")
    println()
  }

  override def traceError(error: Throwable): Unit = {
    println()
    printHeader("ERROR TRACE", RED)
    println(s"${GRAY}Timestamp: $timestamp$RESET")
    println()

    println(s"${RED}${BOLD}Error Type: ${error.getClass.getSimpleName}$RESET")
    println(s"${RED}${BOLD}Message: ${error.getMessage}$RESET")
    println()

    println(s"${RED}Stack Trace:$RESET")
    error.getStackTrace.take(10).foreach(frame => println(s"  ${GRAY}at ${frame.toString}$RESET"))

    if (error.getStackTrace.length > 10) {
      println(s"  ${GRAY}... ${error.getStackTrace.length - 10} more frames$RESET")
    }

    println()
    println(s"${RED}${"=" * 60}$RESET")
    println()
  }

  override def traceCompletion(completion: org.llm4s.llmconnect.model.Completion, model: String): Unit = {
    println()
    printHeader("LLM COMPLETION", GREEN)
    println(s"${GRAY}Timestamp: $timestamp$RESET")
    println()

    println(s"${GREEN}${BOLD}Model: $model$RESET")
    println(s"${GREEN}${BOLD}Completion ID: ${completion.id}$RESET")
    println(s"${GREEN}${BOLD}Created: ${completion.created}$RESET")
    println()

    // Show response content
    printSubHeader("Response", GREEN)
    println(s"${GREEN}Content: ${formatJson(completion.message.content, 300)}$RESET")

    if (completion.message.toolCalls.nonEmpty) {
      println(s"${YELLOW}Tool Calls: ${completion.message.toolCalls.length}$RESET")
      completion.message.toolCalls.zipWithIndex.foreach { case (tc, idx) =>
        println(s"  ${YELLOW}${idx + 1}. ${tc.name}(${formatJson(tc.arguments.render(), 100)})$RESET")
      }
    }
    println()

    // Show token usage if available
    completion.usage.foreach { usage =>
      printSubHeader("Token Usage", CYAN)
      println(s"${CYAN}${BOLD}Prompt Tokens: ${usage.promptTokens}$RESET")
      println(s"${CYAN}${BOLD}Completion Tokens: ${usage.completionTokens}$RESET")
      println(s"${CYAN}${BOLD}Total Tokens: ${usage.totalTokens}$RESET")

      // Calculate rough cost estimate (these are example rates, adjust as needed)
      val promptCost     = usage.promptTokens * 0.00001     // $0.01 per 1K tokens (example)
      val completionCost = usage.completionTokens * 0.00003 // $0.03 per 1K tokens (example)
      val totalCost      = promptCost + completionCost

      println(f"${CYAN}Estimated Cost: $$${totalCost}%.6f USD (approx.)$RESET")
      println()
    }

    println(s"${GREEN}${"=" * 60}$RESET")
    println()
  }

  override def traceTokenUsage(usage: org.llm4s.llmconnect.model.TokenUsage, model: String, operation: String): Unit = {
    println()
    printSubHeader("TOKEN USAGE", CYAN)
    println(s"${GRAY}Timestamp: $timestamp$RESET")
    println(s"${CYAN}${BOLD}Operation: $operation$RESET")
    println(s"${CYAN}${BOLD}Model: $model$RESET")
    println()

    // Create a visual token usage bar
    val maxTokens       = usage.totalTokens
    val promptRatio     = if (maxTokens > 0) (usage.promptTokens.toDouble / maxTokens * 40).toInt else 0
    val completionRatio = if (maxTokens > 0) (usage.completionTokens.toDouble / maxTokens * 40).toInt else 0

    println(s"${CYAN}Token Breakdown:$RESET")
    println(s"  ${BLUE}Prompt:     ${usage.promptTokens} tokens$RESET")
    println(s"  ${GREEN}Completion: ${usage.completionTokens} tokens$RESET")
    println(s"  ${CYAN}${BOLD}Total:      ${usage.totalTokens} tokens$RESET")
    println()

    // Visual bar representation
    println(s"${CYAN}Token Distribution:$RESET")
    val promptBar     = "█" * promptRatio
    val completionBar = "█" * completionRatio
    println(s"  ${BLUE}Prompt    : $promptBar$RESET")
    println(s"  ${GREEN}Completion: $completionBar$RESET")
    println()
  }
}
