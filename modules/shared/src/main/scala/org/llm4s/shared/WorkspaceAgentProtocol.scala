package org.llm4s.shared

import upickle.default.{ ReadWriter, macroRW }

// Core trait for all workspace commands
sealed trait WorkspaceAgentCommand {
  def commandId: String
}

object WorkspaceAgentCommand {
  implicit val rw: ReadWriter[WorkspaceAgentCommand] = ReadWriter.merge(
    macroRW[ExploreFilesCommand],
    macroRW[ReadFileCommand],
    macroRW[WriteFileCommand],
    macroRW[ModifyFileCommand],
    macroRW[SearchFilesCommand],
    macroRW[ExecuteCommandCommand],
    macroRW[GetWorkspaceInfoCommand]
  )
}

// Core trait for all workspace responses
sealed trait WorkspaceAgentResponse {
  def commandId: String
}

object WorkspaceAgentResponse {
  implicit val rw: ReadWriter[WorkspaceAgentResponse] = ReadWriter.merge(
    macroRW[ExploreFilesResponse],
    macroRW[ReadFileResponse],
    macroRW[WriteFileResponse],
    macroRW[ModifyFileResponse],
    macroRW[SearchFilesResponse],
    macroRW[ExecuteCommandResponse],
    macroRW[GetWorkspaceInfoResponse],
    macroRW[WorkspaceAgentErrorResponse]
  )
}

// Error response for any command
case class WorkspaceAgentErrorResponse(
  commandId: String,
  error: String,
  code: String,
  details: Option[String] = None
) extends WorkspaceAgentResponse

object WorkspaceAgentErrorResponse {
  implicit val rw: ReadWriter[WorkspaceAgentErrorResponse] = macroRW
}

// File metadata representation
case class FileMetadata(
  path: String,
  size: Long,
  isDirectory: Boolean,
  lastModified: String
)

object FileMetadata {
  implicit val rw: ReadWriter[FileMetadata] = macroRW
}

// 1. ExploreFiles Command and Response
case class ExploreFilesCommand(
  commandId: String,
  path: String,
  recursive: Option[Boolean] = None,
  excludePatterns: Option[List[String]] = None,
  maxDepth: Option[Int] = None,
  returnMetadata: Option[Boolean] = None
) extends WorkspaceAgentCommand

object ExploreFilesCommand {
  implicit val rw: ReadWriter[ExploreFilesCommand] = macroRW
}

case class FileEntry(
  path: String,
  isDirectory: Boolean,
  metadata: Option[FileMetadata] = None
)

object FileEntry {
  implicit val rw: ReadWriter[FileEntry] = macroRW
}

case class ExploreFilesResponse(
  commandId: String,
  files: List[FileEntry],
  isTruncated: Boolean,
  totalFound: Int
) extends WorkspaceAgentResponse

object ExploreFilesResponse {
  implicit val rw: ReadWriter[ExploreFilesResponse] = macroRW
}

// 2. ReadFile Command and Response
case class ReadFileCommand(
  commandId: String,
  path: String,
  startLine: Option[Int] = None,
  endLine: Option[Int] = None
) extends WorkspaceAgentCommand

object ReadFileCommand {
  implicit val rw: ReadWriter[ReadFileCommand] = macroRW
}

case class ReadFileResponse(
  commandId: String,
  content: String,
  metadata: FileMetadata,
  isTruncated: Boolean,
  totalLines: Int,
  returnedLines: Int
) extends WorkspaceAgentResponse

object ReadFileResponse {
  implicit val rw: ReadWriter[ReadFileResponse] = macroRW
}

// 3. WriteFile Command and Response
case class WriteFileCommand(
  commandId: String,
  path: String,
  content: String,
  mode: Option[String] = None, // "create", "overwrite", or "append"
  createDirectories: Option[Boolean] = None
) extends WorkspaceAgentCommand

object WriteFileCommand {
  implicit val rw: ReadWriter[WriteFileCommand] = macroRW
}

case class WriteFileResponse(
  commandId: String,
  success: Boolean,
  path: String,
  bytesWritten: Long
) extends WorkspaceAgentResponse

object WriteFileResponse {
  implicit val rw: ReadWriter[WriteFileResponse] = macroRW
}

// 4. ModifyFile Command and Response
sealed trait FileOperation

object FileOperation {
  implicit val rw: ReadWriter[FileOperation] = ReadWriter.merge(
    macroRW[ReplaceOperation],
    macroRW[InsertOperation],
    macroRW[DeleteOperation],
    macroRW[RegexReplaceOperation]
  )
}

case class ReplaceOperation(
  `type`: String = "replace",
  startLine: Int,
  endLine: Int,
  newContent: String
) extends FileOperation

object ReplaceOperation {
  implicit val rw: ReadWriter[ReplaceOperation] = macroRW
}

case class InsertOperation(
  `type`: String = "insert",
  afterLine: Int,
  newContent: String
) extends FileOperation

object InsertOperation {
  implicit val rw: ReadWriter[InsertOperation] = macroRW
}

case class DeleteOperation(
  `type`: String = "delete",
  startLine: Int,
  endLine: Int
) extends FileOperation

object DeleteOperation {
  implicit val rw: ReadWriter[DeleteOperation] = macroRW
}

case class RegexReplaceOperation(
  `type`: String = "regexReplace",
  pattern: String,
  replacement: String,
  flags: Option[String] = None
) extends FileOperation

object RegexReplaceOperation {
  implicit val rw: ReadWriter[RegexReplaceOperation] = macroRW
}

case class ModifyFileCommand(
  commandId: String,
  path: String,
  operations: List[FileOperation]
) extends WorkspaceAgentCommand

object ModifyFileCommand {
  implicit val rw: ReadWriter[ModifyFileCommand] = macroRW
}

case class ModifyFileResponse(
  commandId: String,
  success: Boolean,
  path: String
) extends WorkspaceAgentResponse

object ModifyFileResponse {
  implicit val rw: ReadWriter[ModifyFileResponse] = macroRW
}

// 5. SearchFiles Command and Response
case class SearchFilesCommand(
  commandId: String,
  paths: List[String],
  query: String,
  `type`: String, // "regex" or "literal"
  recursive: Option[Boolean] = None,
  excludePatterns: Option[List[String]] = None,
  contextLines: Option[Int] = None
) extends WorkspaceAgentCommand

object SearchFilesCommand {
  implicit val rw: ReadWriter[SearchFilesCommand] = macroRW
}

case class SearchMatch(
  path: String,
  line: Int,
  matchText: String,
  contextBefore: List[String],
  contextAfter: List[String]
)

object SearchMatch {
  implicit val rw: ReadWriter[SearchMatch] = macroRW
}

case class SearchFilesResponse(
  commandId: String,
  matches: List[SearchMatch],
  isTruncated: Boolean,
  totalMatches: Int
) extends WorkspaceAgentResponse

object SearchFilesResponse {
  implicit val rw: ReadWriter[SearchFilesResponse] = macroRW
}

// 6. ExecuteCommand Command and Response
case class ExecuteCommandCommand(
  commandId: String,
  command: String,
  workingDirectory: Option[String] = None,
  timeout: Option[Int] = None,
  environment: Option[Map[String, String]] = None
) extends WorkspaceAgentCommand

object ExecuteCommandCommand {
  implicit val rw: ReadWriter[ExecuteCommandCommand] = macroRW
}

case class ExecuteCommandResponse(
  commandId: String,
  stdout: String,
  stderr: String,
  exitCode: Int,
  isOutputTruncated: Boolean,
  durationMs: Long
) extends WorkspaceAgentResponse

object ExecuteCommandResponse {
  implicit val rw: ReadWriter[ExecuteCommandResponse] = macroRW
}

// 7. GetWorkspaceInfo Command and Response
case class GetWorkspaceInfoCommand(
  commandId: String
) extends WorkspaceAgentCommand

object GetWorkspaceInfoCommand {
  implicit val rw: ReadWriter[GetWorkspaceInfoCommand] = macroRW
}

case class WorkspaceLimits(
  maxFileSize: Long,
  maxDirectoryEntries: Int,
  maxSearchResults: Int,
  maxOutputSize: Long
)

object WorkspaceLimits {
  implicit val rw: ReadWriter[WorkspaceLimits] = macroRW
}

case class GetWorkspaceInfoResponse(
  commandId: String,
  root: String,
  defaultExclusions: List[String],
  limits: WorkspaceLimits
) extends WorkspaceAgentResponse

object GetWorkspaceInfoResponse {
  implicit val rw: ReadWriter[GetWorkspaceInfoResponse] = macroRW
}

// WebSocket Protocol Messages
sealed trait WebSocketMessage

object WebSocketMessage {
  implicit val rw: ReadWriter[WebSocketMessage] = ReadWriter.merge(
    macroRW[CommandMessage],
    macroRW[ResponseMessage],
    macroRW[HeartbeatMessage],
    macroRW[HeartbeatResponseMessage],
    macroRW[StreamingOutputMessage],
    macroRW[CommandStartedMessage],
    macroRW[CommandCompletedMessage],
    macroRW[ErrorMessage]
  )
}

// Client to Server Messages
case class CommandMessage(
  command: WorkspaceAgentCommand
) extends WebSocketMessage

object CommandMessage {
  implicit val rw: ReadWriter[CommandMessage] = macroRW
}

case class HeartbeatMessage(
  timestamp: Long = System.currentTimeMillis()
) extends WebSocketMessage

object HeartbeatMessage {
  implicit val rw: ReadWriter[HeartbeatMessage] = macroRW
}

// Server to Client Messages
case class ResponseMessage(
  response: WorkspaceAgentResponse
) extends WebSocketMessage

object ResponseMessage {
  implicit val rw: ReadWriter[ResponseMessage] = macroRW
}

case class HeartbeatResponseMessage(
  timestamp: Long = System.currentTimeMillis()
) extends WebSocketMessage

object HeartbeatResponseMessage {
  implicit val rw: ReadWriter[HeartbeatResponseMessage] = macroRW
}

// Streaming command output (for long-running commands)
case class StreamingOutputMessage(
  commandId: String,
  outputType: String, // "stdout" or "stderr"
  content: String,
  isComplete: Boolean = false
) extends WebSocketMessage

object StreamingOutputMessage {
  implicit val rw: ReadWriter[StreamingOutputMessage] = macroRW
}

// Command lifecycle messages
case class CommandStartedMessage(
  commandId: String,
  command: String
) extends WebSocketMessage

object CommandStartedMessage {
  implicit val rw: ReadWriter[CommandStartedMessage] = macroRW
}

case class CommandCompletedMessage(
  commandId: String,
  exitCode: Int,
  durationMs: Long
) extends WebSocketMessage

object CommandCompletedMessage {
  implicit val rw: ReadWriter[CommandCompletedMessage] = macroRW
}

// Generic error message
case class ErrorMessage(
  error: String,
  code: String,
  commandId: Option[String] = None
) extends WebSocketMessage

object ErrorMessage {
  implicit val rw: ReadWriter[ErrorMessage] = macroRW
}
