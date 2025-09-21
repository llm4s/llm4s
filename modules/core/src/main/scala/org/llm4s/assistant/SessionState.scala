package org.llm4s.assistant

import org.llm4s.agent.AgentState
import org.llm4s.types.{ SessionId, DirectoryPath, FilePath }
import java.time.LocalDateTime
import java.util.UUID
import upickle.default.{ ReadWriter => RW, macroRW, ReadWriter, readwriter }

/**
 * Represents the state of an interactive assistant session
 */
case class SessionState(
  agentState: Option[AgentState],
  sessionId: SessionId,
  sessionDir: DirectoryPath,
  created: LocalDateTime = LocalDateTime.now()
) {
  def withAgentState(newState: AgentState): SessionState =
    copy(agentState = Some(newState))

  def withNewSession(): SessionState =
    copy(
      agentState = None,
      sessionId = SessionId(UUID.randomUUID().toString),
      created = LocalDateTime.now()
    )
}

object SessionState {
  // Custom ReadWriter for LocalDateTime
  implicit val localDateTimeRW: ReadWriter[LocalDateTime] =
    readwriter[String].bimap[LocalDateTime](_.toString, LocalDateTime.parse(_))

  // We can't automatically serialize SessionState because it contains AgentState with ToolRegistry
  // Serialization is handled manually in SessionManager
}

/**
 * Information about a saved session
 */
case class SessionInfo(
  id: SessionId,
  title: String,
  filePath: FilePath,
  created: LocalDateTime,
  messageCount: Int,
  fileSize: Long
)

object SessionInfo {
  import SessionState.localDateTimeRW // Import the LocalDateTime ReadWriter
  implicit val rw: RW[SessionInfo] = macroRW
}

/**
 * Summary of a session for listing purposes
 */
case class SessionSummary(
  id: SessionId,
  title: String,
  filename: String,
  created: LocalDateTime
)

object SessionSummary {
  import SessionState.localDateTimeRW // Import the LocalDateTime ReadWriter
  implicit val rw: RW[SessionSummary] = macroRW
}
