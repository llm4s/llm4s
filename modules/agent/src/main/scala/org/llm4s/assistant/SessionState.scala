package org.llm4s.assistant

import org.llm4s.agent.AgentResult
import org.llm4s.agent.graph.ThreadId
import org.llm4s.llmconnect.model.Message
import org.llm4s.types.{ SessionId, DirectoryPath, FilePath }
import java.time.LocalDateTime
import java.util.UUID
import upickle.default.{ ReadWriter => RW, macroRW, ReadWriter, readwriter }

/**
 * Represents the state of an interactive assistant session.
 *
 * The conversation lives on a thread of the assistant's agent: `threadId` names it once a turn has
 * run, and `last` is that turn's result. A loaded session has neither yet: its saved messages are
 * in `history`, and the next turn imports them into a new thread.
 *
 * @param threadId The agent thread holding the conversation, once a turn has run on it
 * @param last The result of the latest turn on `threadId`
 * @param sessionId Unique identifier for this session
 * @param sessionDir Directory path for session file storage
 * @param created Timestamp when the session was created
 * @param history Messages loaded from a saved session, imported by the next turn
 */
case class SessionState(
  threadId: Option[ThreadId],
  last: Option[AgentResult],
  sessionId: SessionId,
  sessionDir: DirectoryPath,
  created: LocalDateTime = LocalDateTime.now(),
  history: Vector[Message] = Vector.empty
) {

  /** The conversation so far: the latest turn's messages, or the loaded history before any turn. */
  def messages: Vector[Message] = last.fold(history)(_.messages)

  /** The state after `result`, a turn on its thread: the loaded history is now part of the thread. */
  def withResult(result: AgentResult): SessionState =
    copy(threadId = Some(result.threadId), last = Some(result), history = Vector.empty)

  /** A fresh session with no thread. The caller forgets this session's thread, which nothing continues. */
  def withNewSession(): SessionState =
    copy(
      threadId = None,
      last = None,
      sessionId = SessionId(UUID.randomUUID().toString),
      created = LocalDateTime.now(),
      history = Vector.empty
    )
}

object SessionState {
  // Custom ReadWriter for LocalDateTime
  implicit private[assistant] val localDateTimeRW: ReadWriter[LocalDateTime] =
    readwriter[String].bimap[LocalDateTime](_.toString, LocalDateTime.parse(_))

  // SessionState is not serialized as a whole: SessionManager saves its messages
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
