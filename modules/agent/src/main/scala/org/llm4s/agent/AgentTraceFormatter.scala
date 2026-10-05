package org.llm4s.agent

import org.llm4s.core.safety.Safety
import org.llm4s.llmconnect.model.{ AssistantMessage, MessageRole, ToolMessage }
import org.slf4j.{ Logger, LoggerFactory }

import scala.util.Try

/**
 * Renders an [[AgentThread]] as a human-readable markdown document and optionally
 * persists it to a file.
 *
 * == Format stability ==
 *
 * The output format is intentionally '''unstable''' across library versions.
 * Do not parse the markdown programmatically — use the structured [[AgentThread]]
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
 * [[AgentThread]] (injected at API call time so they can be updated without
 * re-running the full history).  Including them in the trace would suggest
 * they are part of the mutable conversation, which is misleading.
 */
private[agent] object AgentTraceFormatter {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * Renders `thread` as a markdown document.
   *
   * Covers the conversation transcript and tool call arguments/results.  The system message stored in
   * [[AgentThread.systemMessage]] is intentionally omitted from the conversation section (see class-level
   * note).  A thread has no execution log: the run events carry what it did.
   *
   * @param thread Agent thread to render; may be in any status.
   * @return A markdown string suitable for human inspection.
   */
  def formatThreadAsMarkdown(thread: AgentThread): String = {
    val sb = new StringBuilder()

    sb.append("# Agent Execution Trace\n\n")
    thread.messages.collectFirst { case u if u.role == MessageRole.User => u.content }.foreach { q =>
      sb.append(s"**Initial Query:** $q\n")
    }
    sb.append(s"**Status:** ${thread.status}\n\n")

    sb.append("## Conversation Flow\n\n")

    thread.messages.zipWithIndex.foreach { case (message, index) =>
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

    sb.toString
  }

  /**
   * Writes a markdown trace of `thread` to `traceLogPath`.
   *
   * The file is created or truncated on each call.  Write failures are
   * swallowed: errors are logged at ERROR level but never propagated so that
   * trace logging never affects agent control flow.
   *
   * @param thread       Agent thread to render and persist.
   * @param traceLogPath Absolute or relative path to the output file.
   */
  def writeTraceLog(thread: AgentThread, traceLogPath: String): Unit = {
    import java.nio.charset.StandardCharsets
    import java.nio.file.{ Files, Paths }

    Safety
      .fromTry(Try {
        val content = formatThreadAsMarkdown(thread)
        Files.write(Paths.get(traceLogPath), content.getBytes(StandardCharsets.UTF_8))
      })
      .left
      .foreach(err => logger.error("Failed to write trace log: {}", err.message))
  }
}
