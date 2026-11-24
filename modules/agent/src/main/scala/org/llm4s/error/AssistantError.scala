package org.llm4s.error

import org.llm4s.annotation.Experimental
import org.llm4s.types.{ SessionId, FilePath }

/**
 * The errors of the interactive assistant (`org.llm4s.assistant`): console I/O, session files and commands.
 *
 * An `AssistantError` is an [[LLMError]], so an assistant operation's `Either[AssistantError, A]` is a
 * `Result[A]`. None is retried by the library: each is a [[NonRecoverableError]].
 *
 * == Growth safety ==
 * Each case is a `final case class` with a private constructor and `copy`:
 * build one with its companion `apply` (or a smart constructor below) and
 * change a field with its `with*` setter (an `Option` field's setter takes the
 * value or an `Option`), so a field can be added later without breaking binary
 * compatibility. Each case's `unapply` is explicit, with a fixed arity: `message`, then the fields in
 * constructor order.
 */
@Experimental
sealed abstract class AssistantError extends LLMError with NonRecoverableError

@Experimental
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

    /** Extracts the message, the operation and the cause; it always matches. */
    def unapply(error: IOError): Some[(String, String, Option[Throwable])] =
      Some((error.message, error.operation, error.cause))
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

    /** Extracts the message and the operation; it always matches. */
    def unapply(error: EOFError): Some[(String, String)] = Some((error.message, error.operation))
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

    /** Extracts the message, the display type and the cause; it always matches. */
    def unapply(error: DisplayError): Some[(String, String, Option[Throwable])] =
      Some((error.message, error.displayType, error.cause))
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

    /** Extracts the message, the session id, the operation, the cause and the LLM cause; it always matches. */
    def unapply(
      error: SessionError
    ): Some[(String, SessionId, String, Option[Throwable], Option[LLMError])] =
      Some((error.message, error.sessionId, error.operation, error.cause, error.llmCause))
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

    /** Extracts the message, the path, the operation and the cause; it always matches. */
    def unapply(error: FileError): Some[(String, FilePath, String, Option[Throwable])] =
      Some((error.message, error.path, error.operation, error.cause))
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

    /** Extracts the message, the data type, the operation and the cause; it always matches. */
    def unapply(error: SerializationError): Some[(String, String, String, Option[Throwable])] =
      Some((error.message, error.dataType, error.operation, error.cause))
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

    /** Extracts the message, the command, the parse operation and the cause; it always matches. */
    def unapply(error: CommandParseError): Some[(String, String, String, Option[Throwable])] =
      Some((error.message, error.command, error.parseOperation, error.cause))
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
}
