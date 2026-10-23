package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.types.{ SessionId, FilePath }

/**
 * Assistant-specific error types with rich context and formatting.
 *
 * == Growth safety ==
 * Each case is a `final case class` with a private constructor and `copy`:
 * build one with its companion `apply` (or a smart constructor below) and
 * change a field with its `with*` setter (an `Option` field's setter takes the
 * value or an `Option`), so a field can be added later without breaking binary
 * compatibility.
 */
@Stable
sealed abstract class AssistantError extends Product with Serializable {

  /** Human-readable error message */
  def message: String

  /** Additional context information */
  def context: Map[String, String] = Map.empty

  /** Converts to a formatted error message with context */
  def formatted: String = {
    val contextStr = if (context.nonEmpty) {
      s" [${context.map { case (k, v) => s"$k: $v" }.mkString(", ")}]"
    } else ""
    s"${getClass.getSimpleName}: $message$contextStr"
  }
}

object AssistantError {

  // Console errors
  final case class IOError private (
    override val message: String,
    operation: String, // "read" or "write"
    cause: Option[Throwable]
  ) extends AssistantError {
    def withMessage(message: String): IOError        = copy(message = message)
    def withOperation(operation: String): IOError    = copy(operation = operation)
    def withCause(cause: Throwable): IOError         = copy(cause = Some(cause))
    def withCause(cause: Option[Throwable]): IOError = copy(cause = cause)

    override val context: Map[String, String] = Map(
      "component" -> "console",
      "operation" -> operation
    ) ++ cause.map(ex => "cause" -> ex.getClass.getSimpleName)
  }

  object IOError {
    def apply(message: String, operation: String, cause: Option[Throwable] = None): IOError =
      new IOError(message, operation, cause)
  }

  final case class EOFError private (
    override val message: String,
    operation: String
  ) extends AssistantError {
    def withMessage(message: String): EOFError     = copy(message = message)
    def withOperation(operation: String): EOFError = copy(operation = operation)

    override val context: Map[String, String] = Map(
      "component" -> "console",
      "operation" -> operation,
      "reason"    -> "end-of-file"
    )
  }

  object EOFError {
    def apply(message: String, operation: String = "read"): EOFError =
      new EOFError(message, operation)
  }

  final case class DisplayError private (
    override val message: String,
    displayType: String, // "welcome", "message", "error", etc.
    cause: Option[Throwable]
  ) extends AssistantError {
    def withMessage(message: String): DisplayError         = copy(message = message)
    def withDisplayType(displayType: String): DisplayError = copy(displayType = displayType)
    def withCause(cause: Throwable): DisplayError          = copy(cause = Some(cause))
    def withCause(cause: Option[Throwable]): DisplayError  = copy(cause = cause)

    override val context: Map[String, String] = Map(
      "component"   -> "console",
      "displayType" -> displayType
    ) ++ cause.map(ex => "cause" -> ex.getClass.getSimpleName)
  }

  object DisplayError {
    def apply(message: String, displayType: String, cause: Option[Throwable] = None): DisplayError =
      new DisplayError(message, displayType, cause)
  }

  // Session management errors
  final case class SessionError private (
    override val message: String,
    sessionId: SessionId,
    operation: String, // "load", "save", "create", "list", etc.
    cause: Option[Throwable],
    llmCause: Option[LLMError]
  ) extends AssistantError {
    def withMessage(message: String): SessionError             = copy(message = message)
    def withSessionId(sessionId: SessionId): SessionError      = copy(sessionId = sessionId)
    def withOperation(operation: String): SessionError         = copy(operation = operation)
    def withCause(cause: Throwable): SessionError              = copy(cause = Some(cause))
    def withCause(cause: Option[Throwable]): SessionError      = copy(cause = cause)
    def withLlmCause(llmCause: LLMError): SessionError         = copy(llmCause = Some(llmCause))
    def withLlmCause(llmCause: Option[LLMError]): SessionError = copy(llmCause = llmCause)

    override val context: Map[String, String] = Map(
      "component" -> "session-manager",
      "sessionId" -> sessionId.value,
      "operation" -> operation
    ) ++ cause.map(ex => "cause" -> ex.getClass.getSimpleName) ++
      llmCause.map(e => "llmErrorType" -> e.getClass.getSimpleName)
  }

  object SessionError {
    def apply(
      message: String,
      sessionId: SessionId,
      operation: String,
      cause: Option[Throwable] = None,
      llmCause: Option[LLMError] = None
    ): SessionError =
      new SessionError(message, sessionId, operation, cause, llmCause)
  }

  final case class FileError private (
    override val message: String,
    path: FilePath,
    operation: String, // "read", "write", "create", "delete", etc.
    cause: Option[Throwable]
  ) extends AssistantError {
    def withMessage(message: String): FileError        = copy(message = message)
    def withPath(path: FilePath): FileError            = copy(path = path)
    def withOperation(operation: String): FileError    = copy(operation = operation)
    def withCause(cause: Throwable): FileError         = copy(cause = Some(cause))
    def withCause(cause: Option[Throwable]): FileError = copy(cause = cause)

    override val context: Map[String, String] = Map(
      "component" -> "file-system",
      "path"      -> path.value,
      "operation" -> operation
    ) ++ cause.map(ex => "cause" -> ex.getClass.getSimpleName)
  }

  object FileError {
    def apply(message: String, path: FilePath, operation: String, cause: Option[Throwable] = None): FileError =
      new FileError(message, path, operation, cause)
  }

  final case class SerializationError private (
    override val message: String,
    dataType: String,  // "SessionState", "JSON", etc.
    operation: String, // "serialize", "deserialize"
    cause: Option[Throwable]
  ) extends AssistantError {
    def withMessage(message: String): SerializationError        = copy(message = message)
    def withDataType(dataType: String): SerializationError      = copy(dataType = dataType)
    def withOperation(operation: String): SerializationError    = copy(operation = operation)
    def withCause(cause: Throwable): SerializationError         = copy(cause = Some(cause))
    def withCause(cause: Option[Throwable]): SerializationError = copy(cause = cause)

    override val context: Map[String, String] = Map(
      "component" -> "serialization",
      "dataType"  -> dataType,
      "operation" -> operation
    ) ++ cause.map(ex => "cause" -> ex.getClass.getSimpleName)
  }

  object SerializationError {
    def apply(
      message: String,
      dataType: String,
      operation: String,
      cause: Option[Throwable] = None
    ): SerializationError =
      new SerializationError(message, dataType, operation, cause)
  }

  // Command parsing errors
  final case class CommandParseError private (
    override val message: String,
    command: String,
    parseOperation: String, // "validate-title", "parse-command", etc.
    cause: Option[Throwable]
  ) extends AssistantError {
    def withMessage(message: String): CommandParseError               = copy(message = message)
    def withCommand(command: String): CommandParseError               = copy(command = command)
    def withParseOperation(parseOperation: String): CommandParseError = copy(parseOperation = parseOperation)
    def withCause(cause: Throwable): CommandParseError                = copy(cause = Some(cause))
    def withCause(cause: Option[Throwable]): CommandParseError        = copy(cause = cause)

    override val context: Map[String, String] = Map(
      "component" -> "command-parser",
      "command"   -> command,
      "operation" -> parseOperation
    ) ++ cause.map(ex => "cause" -> ex.getClass.getSimpleName)
  }

  object CommandParseError {
    def apply(
      message: String,
      command: String,
      parseOperation: String,
      cause: Option[Throwable] = None
    ): CommandParseError =
      new CommandParseError(message, command, parseOperation, cause)
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
    IOError(s"Failed to read user input: ${LLMError.describeCause(cause)}", "read", Some(cause))

  def consoleOutputFailed(displayType: String, cause: Throwable): AssistantError =
    DisplayError(s"Failed to display $displayType: ${LLMError.describeCause(cause)}", displayType, Some(cause))

  // Command parsing constructors
  def emptyCommandTitle(command: String): AssistantError =
    CommandParseError(s"Please specify a session title: $command \"Session Name\"", command, "validate-title")

  def unknownCommand(command: String): AssistantError =
    CommandParseError(s"Unknown command: $command. Type /help for available commands.", command, "parse-command")

  // No need for LLMError wrapper - just use the core LLMError directly in Either types
  // The calling code can use Either[LLMError, T] when dealing with core LLM operations
}
