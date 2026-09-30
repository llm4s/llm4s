package org.llm4s.assistant

import org.llm4s.agent.AgentState
import org.llm4s.types.{ SessionId, DirectoryPath, FilePath }
import java.time.LocalDateTime
import java.util.UUID
import upickle.default.{ ReadWriter => RW, macroRW, ReadWriter, readwriter }

/**
 * Represents the state of an interactive assistant session.
 *
 * Encapsulates the agent state, session identity, and metadata
 * for persistence and session management.
 *
 * @param agentState Optional underlying agent state with conversation history
 * @param sessionId Unique identifier for this session
 * @param sessionDir Directory path for session file storage
 * @param created Timestamp when the session was created
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
 * Information about a saved session file.
 *
 * Contains metadata about a persisted session including file location
 * and statistics about the conversation.
 *
 * @param id Unique session identifier
 * @param title Human-readable session title
 * @param filePath Path to the session JSON file
 * @param created Timestamp when the session was created
 * @param messageCount Number of messages in the conversation
 * @param fileSize Size of the session file in bytes
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
 * Summary of a session for listing and display purposes.
 *
 * Lightweight representation of a session containing only
 * essential information for session selection UI.
 *
 * @param id Unique session identifier
 * @param title Human-readable session title
 * @param filename Base filename of the session file
 * @param created Timestamp when the session was created
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
