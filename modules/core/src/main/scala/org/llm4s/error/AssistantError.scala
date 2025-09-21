package org.llm4s.error

import org.llm4s.types.{ SessionId, FilePath }

/**
 * Assistant-specific error types with rich context and formatting
 */
sealed abstract class AssistantError extends Product with Serializable {

  /** Human-readable error message */
  def message: String

  /** Additional context information */
  def context: Map[String, String] = Map.empty

  /** Converts to a formatted error message with context */
  def formatted: String = {
    val contextStr = if (context.nonEmpty) {
      " [" + context.map { case (k, v) => s"$k: $v" }.mkString(", ") + "]"
    } else ""
    s"${getClass.getSimpleName}: $message$contextStr"
  }
}

object AssistantError {

  // Console errors
  final case class IOError(
    override val message: String,
    operation: String, // "read" or "write"
    cause: Option[Throwable] = None
  ) extends AssistantError {
    override val context: Map[String, String] = Map(
      "component" -> "console",
      "operation" -> operation
    ) ++ cause.map(ex => "cause" -> ex.getClass.getSimpleName)
  }

  final case class EOFError(
    override val message: String,
    operation: String = "read"
  ) extends AssistantError {
    override val context: Map[String, String] = Map(
      "component" -> "console",
      "operation" -> operation,
      "reason"    -> "end-of-file"
    )
  }

  final case class DisplayError(
    override val message: String,
    displayType: String, // "welcome", "message", "error", etc.
    cause: Option[Throwable] = None
  ) extends AssistantError {
    override val context: Map[String, String] = Map(
      "component"   -> "console",
      "displayType" -> displayType
    ) ++ cause.map(ex => "cause" -> ex.getClass.getSimpleName)
  }

  // Session management errors
  final case class SessionError(
    override val message: String,
    sessionId: SessionId,
    operation: String, // "load", "save", "create", "list", etc.
    cause: Option[Throwable] = None
  ) extends AssistantError {
    override val context: Map[String, String] = Map(
      "component" -> "session-manager",
      "sessionId" -> sessionId.value,
      "operation" -> operation
    ) ++ cause.map(ex => "cause" -> ex.getClass.getSimpleName)
  }

  final case class FileError(
    override val message: String,
    path: FilePath,
    operation: String, // "read", "write", "create", "delete", etc.
    cause: Option[Throwable] = None
  ) extends AssistantError {
    override val context: Map[String, String] = Map(
      "component" -> "file-system",
      "path"      -> path.value,
      "operation" -> operation
    ) ++ cause.map(ex => "cause" -> ex.getClass.getSimpleName)
  }

  final case class SerializationError(
    override val message: String,
    dataType: String,  // "SessionState", "JSON", etc.
    operation: String, // "serialize", "deserialize"
    cause: Option[Throwable] = None
  ) extends AssistantError {
    override val context: Map[String, String] = Map(
      "component" -> "serialization",
      "dataType"  -> dataType,
      "operation" -> operation
    ) ++ cause.map(ex => "cause" -> ex.getClass.getSimpleName)
  }

  // Command parsing errors
  final case class CommandParseError(
    override val message: String,
    command: String,
    parseOperation: String, // "validate-title", "parse-command", etc.
    cause: Option[Throwable] = None
  ) extends AssistantError {
    override val context: Map[String, String] = Map(
      "component" -> "command-parser",
      "command"   -> command,
      "operation" -> parseOperation
    ) ++ cause.map(ex => "cause" -> ex.getClass.getSimpleName)
  }

  // Smart constructors with rich context
  def sessionNotFound(sessionId: SessionId): AssistantError =
    SessionError(s"Session not found: $sessionId", sessionId, "load")

  def sessionTitleNotFound(sessionTitle: String): AssistantError =
    SessionError(s"Session not found: $sessionTitle", SessionId(sessionTitle), "load")

  def fileReadFailed(path: FilePath, cause: Throwable): AssistantError =
    FileError(s"Failed to read file: $path", path, "read", Some(cause))

  def fileWriteFailed(path: FilePath, cause: Throwable): AssistantError =
    FileError(s"Failed to write file: $path", path, "write", Some(cause))

  def jsonSerializationFailed(dataType: String, cause: Throwable): AssistantError =
    SerializationError(s"Failed to serialize $dataType to JSON", dataType, "serialize", Some(cause))

  def jsonDeserializationFailed(dataType: String, cause: Throwable): AssistantError =
    SerializationError(s"Failed to deserialize JSON to $dataType", dataType, "deserialize", Some(cause))

  // Console-specific constructors
  def consoleInputFailed(cause: Throwable): AssistantError =
    IOError(s"Failed to read user input: ${cause.getMessage}", "read", Some(cause))

  def consoleOutputFailed(displayType: String, cause: Throwable): AssistantError =
    DisplayError(s"Failed to display $displayType: ${cause.getMessage}", displayType, Some(cause))

  // Command parsing constructors
  def emptyCommandTitle(command: String): AssistantError =
    CommandParseError(s"Please specify a session title: $command \"Session Name\"", command, "validate-title")

  def unknownCommand(command: String): AssistantError =
    CommandParseError(s"Unknown command: $command. Type /help for available commands.", command, "parse-command")

  // No need for LLMError wrapper - just use the core LLMError directly in Either types
  // The calling code can use Either[LLMError, T] when dealing with core LLM operations
}
