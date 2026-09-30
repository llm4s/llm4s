package org.llm4s.agent

import org.llm4s.core.safety.Safety
import org.llm4s.llmconnect.model.{ AssistantMessage, MessageRole, ToolMessage }
import org.slf4j.{ Logger, LoggerFactory }

import scala.util.Try

/**
 * Renders [[AgentState]] as a human-readable markdown document and optionally
 * persists it to a file.
 *
 * == Format stability ==
 *
 * The output format is intentionally '''unstable''' across library versions.
 * Do not parse the markdown programmatically — use the structured [[AgentState]]
 * directly.  The unstable contract lets us improve the trace output without
 * treating every change as a breaking API change.
 *
 * == Why write errors are swallowed ==
 *
 * Trace logging is a diagnostic aid, not part of the agent's control flow.
 * A failed write (permission error, disk full, path not found) must never
 * cause the agent run to fail — the end-user cares about the answer, not the
 * trace file.  Errors are logged at ERROR level via SLF4J so operators can
 * investigate without the agent surfacing them to callers.
 *
 * == Why system messages are excluded from the conversation section ==
 *
 * System messages are stored separately from conversation history in
 * [[AgentState]] (injected at API call time so they can be updated without
 * re-running the full history).  Including them in the trace would suggest
 * they are part of the mutable conversation, which is misleading.
 */
private[agent] object AgentTraceFormatter {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * Renders `state` as a markdown document.
   *
   * Covers the conversation transcript, tool call arguments/results, and
   * execution log entries.  System messages stored in
   * [[AgentState.systemMessage]] are intentionally omitted from the
   * conversation section (see class-level note).
   *
   * @param state Agent state to render; may be in any status.
   * @return A markdown string suitable for human inspection.
   */
  def formatStateAsMarkdown(state: AgentState): String = {
    val sb = new StringBuilder()

    sb.append("# Agent Execution Trace\n\n")
    state.initialQuery.foreach(q => sb.append(s"**Initial Query:** $q\n"))
    sb.append(s"**Status:** ${state.status}\n")
    sb.append(s"**Tools Available:** ${state.tools.tools.map(_.name).mkString(", ")}\n\n")

    sb.append("## Conversation Flow\n\n")

    state.conversation.messages.zipWithIndex.foreach { case (message, index) =>
      val step = index + 1

      message.role match {
        case MessageRole.System =>
          sb.append(s"### Step $step: System Message\n\n")
          sb.append("```\n")
          sb.append(message.content)
          sb.append("\n```\n\n")

        case MessageRole.User =>
          sb.append(s"### Step $step: User Message\n\n")
          sb.append(message.content)
          sb.append("\n\n")

        case MessageRole.Assistant =>
          sb.append(s"### Step $step: Assistant Message\n\n")

          message match {
            case msg: AssistantMessage if msg.toolCalls.nonEmpty =>
              if (msg.content != null)
                if (msg.content.trim.nonEmpty) {
                  sb.append(msg.content)
                  sb.append("\n\n")
                } else
                  sb.append("--NO CONTENT--\n\n")

              sb.append("**Tool Calls:**\n\n")

              msg.toolCalls.foreach { tc =>
                sb.append(s"Tool: **${tc.name}**\n\n")
                sb.append("Arguments:\n")
                sb.append("```json\n")
                sb.append(tc.arguments)
                sb.append("\n```\n\n")
              }

            case _ =>
              sb.append(message.content)
              sb.append("\n\n")
          }

        case MessageRole.Tool =>
          message match {
            case msg: ToolMessage =>
              sb.append(s"### Step $step: Tool Response\n\n")
              sb.append(s"Tool Call ID: `${msg.toolCallId}`\n\n")
              sb.append("Result:\n")
              sb.append("```json\n")
              sb.append(msg.content)
              sb.append("\n```\n\n")

            case _ =>
              sb.append(s"### Step $step: Tool Response\n\n")
              sb.append("```\n")
              sb.append(message.content)
              sb.append("\n```\n\n")
          }
      }
    }

    if (state.logs.nonEmpty) {
      sb.append("## Execution Logs\n\n")

      state.logs.zipWithIndex.foreach { case (log, index) =>
        sb.append(s"${index + 1}. ")

        log match {
          case l if l.startsWith("[assistant]") =>
            sb.append(s"**Assistant:** ${l.stripPrefix("[assistant] ")}\n")

          case l if l.startsWith("[tool]") =>
            val content = l.stripPrefix("[tool] ")
            sb.append(s"**Tool Output:** ${content}\n")

          case l if l.startsWith("[tools]") =>
            sb.append(s"**Tools:** ${l.stripPrefix("[tools] ")}\n")

          case l if l.startsWith("[system]") =>
            sb.append(s"**System:** ${l.stripPrefix("[system] ")}\n")

          case _ =>
            sb.append(s"$log\n")
        }
      }
    }

    sb.toString
  }

  /**
   * Writes a markdown trace of `state` to `traceLogPath`.
   *
   * The file is created or truncated on each call.  Write failures are
   * swallowed: errors are logged at ERROR level but never propagated so that
   * trace logging never affects agent control flow.
   *
   * @param state        Agent state to render and persist.
   * @param traceLogPath Absolute or relative path to the output file.
   */
  def writeTraceLog(state: AgentState, traceLogPath: String): Unit = {
    import java.nio.charset.StandardCharsets
    import java.nio.file.{ Files, Paths }

    Safety
      .fromTry(Try {
        val content = formatStateAsMarkdown(state)
        Files.write(Paths.get(traceLogPath), content.getBytes(StandardCharsets.UTF_8))
      })
      .left
      .foreach(err => logger.error("Failed to write trace log: {}", err.message))
  }
}
