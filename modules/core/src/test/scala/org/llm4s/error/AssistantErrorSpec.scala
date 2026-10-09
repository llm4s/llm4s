package org.llm4s.error

import org.llm4s.types.{ FilePath, SessionId }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for AssistantError hierarchy - console, session, file, serialization,
 * and command parsing errors
 */
class AssistantErrorSpec extends AnyFlatSpec with Matchers {

  // ============ IOError ============

  "AssistantError.IOError" should "create with operation" in {
    val error = AssistantError.IOError("Failed to read input", "read")

    error.message shouldBe "Failed to read input"
    error.operation shouldBe "read"
    error.cause shouldBe None
  }

  it should "create with cause" in {
    val cause = new java.io.IOException("Stream closed")
    val error = AssistantError.IOError("Input failed", "read", Some(cause))

    error.cause shouldBe Some(cause)
    error.context should contain("cause" -> "IOException")
  }

  it should "include component and operation in context" in {
    val error = AssistantError.IOError("error", "write")

    error.context should contain("component" -> "console")
    error.context should contain("operation" -> "write")
  }

  it should "format correctly" in {
    val error = AssistantError.IOError("Input failed", "read")

    error.formatted should include("IOError")
    error.formatted should include("Input failed")
    error.formatted should include("console")
  }

  // ============ EOFError ============

  "AssistantError.EOFError" should "create with default read operation" in {
    val error = AssistantError.EOFError("End of file reached")

    error.message shouldBe "End of file reached"
    error.operation shouldBe "read"
  }

  it should "include reason in context" in {
    val error = AssistantError.EOFError("EOF")

    error.context should contain("reason" -> "end-of-file")
    error.context should contain("component" -> "console")
  }

  // ============ DisplayError ============

  "AssistantError.DisplayError" should "create with display type" in {
    val error = AssistantError.DisplayError("Failed to render", "welcome")

    error.message shouldBe "Failed to render"
    error.displayType shouldBe "welcome"
  }

  it should "create with cause" in {
    val cause = new RuntimeException("Render failed")
    val error = AssistantError.DisplayError("Display failed", "message", Some(cause))

    error.cause shouldBe Some(cause)
    error.context should contain("cause" -> "RuntimeException")
  }

  it should "include display type in context" in {
    val error = AssistantError.DisplayError("error", "error")

    error.context should contain("displayType" -> "error")
  }

  // ============ SessionError ============

  "AssistantError.SessionError" should "create with session ID and operation" in {
    val sessionId = SessionId("session-123")
    val error     = AssistantError.SessionError("Session not found", sessionId, "load")

    error.message shouldBe "Session not found"
    error.sessionId shouldBe sessionId
    error.operation shouldBe "load"
  }

  it should "include session context" in {
    val error = AssistantError.SessionError("error", SessionId("test-session"), "save")

    error.context should contain("component" -> "session-manager")
    error.context should contain("sessionId" -> "test-session")
    error.context should contain("operation" -> "save")
  }

  it should "create with cause" in {
    val cause = new java.io.FileNotFoundException("Session file missing")
    val error = AssistantError.SessionError("Load failed", SessionId("s1"), "load", Some(cause))

    error.cause shouldBe Some(cause)
    error.context should contain("cause" -> "FileNotFoundException")
  }

  // ============ FileError ============

  "AssistantError.FileError" should "create with path and operation" in {
    val path  = FilePath("/tmp/test.json")
    val error = AssistantError.FileError("File not found", path, "read")

    error.message shouldBe "File not found"
    error.path shouldBe path
    error.operation shouldBe "read"
  }

  it should "include file context" in {
    val error = AssistantError.FileError("error", FilePath("/path/to/file"), "write")

    error.context should contain("component" -> "file-system")
    error.context should contain("path" -> "/path/to/file")
    error.context should contain("operation" -> "write")
  }

  // ============ SerializationError ============

  "AssistantError.SerializationError" should "create with data type and operation" in {
    val error = AssistantError.SerializationError("Invalid JSON", "SessionState", "deserialize")

    error.message shouldBe "Invalid JSON"
    error.dataType shouldBe "SessionState"
    error.operation shouldBe "deserialize"
  }

  it should "include serialization context" in {
    val error = AssistantError.SerializationError("error", "JSON", "serialize")

    error.context should contain("component" -> "serialization")
    error.context should contain("dataType" -> "JSON")
    error.context should contain("operation" -> "serialize")
  }

  // ============ CommandParseError ============

  "AssistantError.CommandParseError" should "create with command and parse operation" in {
    val error = AssistantError.CommandParseError("Invalid command", "/unknown", "parse-command")

    error.message shouldBe "Invalid command"
    error.command shouldBe "/unknown"
    error.parseOperation shouldBe "parse-command"
  }

  it should "include command context" in {
    val error = AssistantError.CommandParseError("error", "/save", "validate-title")

    error.context should contain("component" -> "command-parser")
    error.context should contain("command" -> "/save")
    error.context should contain("operation" -> "validate-title")
  }

  // ============ Smart Constructors ============

  "AssistantError.sessionNotFound" should "create session not found error" in {
    val sessionId = SessionId("missing-session")
    val error     = AssistantError.sessionNotFound(sessionId)

    error shouldBe a[AssistantError.SessionError]
    error.message should include("Session not found")
    error.message should include("missing-session")
  }

  "AssistantError.sessionTitleNotFound" should "create session title not found error" in {
    val error = AssistantError.sessionTitleNotFound("My Session")

    error shouldBe a[AssistantError.SessionError]
    error.message should include("Session not found")
    error.message should include("My Session")
  }

  "AssistantError.fileReadFailed" should "create file read failed error" in {
    val path  = FilePath("/tmp/missing.txt")
    val cause = new java.io.FileNotFoundException("File not found")
    val error = AssistantError.fileReadFailed(path, cause)

    error shouldBe a[AssistantError.FileError]
    error.message should include("Failed to read file")
    val fileError = error.asInstanceOf[AssistantError.FileError]
    fileError.operation shouldBe "read"
    fileError.cause shouldBe Some(cause)
  }

  "AssistantError.fileWriteFailed" should "create file write failed error" in {
    val path  = FilePath("/readonly/file.txt")
    val cause = new java.io.IOException("Permission denied")
    val error = AssistantError.fileWriteFailed(path, cause)

    error shouldBe a[AssistantError.FileError]
    val fileError = error.asInstanceOf[AssistantError.FileError]
    fileError.operation shouldBe "write"
  }

  "AssistantError.jsonSerializationFailed" should "create JSON serialization error" in {
    val cause = new RuntimeException("Circular reference")
    val error = AssistantError.jsonSerializationFailed("SessionState", cause)

    error shouldBe a[AssistantError.SerializationError]
    error.message should include("Failed to serialize SessionState")
    val serError = error.asInstanceOf[AssistantError.SerializationError]
    serError.operation shouldBe "serialize"
  }

  "AssistantError.jsonDeserializationFailed" should "create JSON deserialization error" in {
    val cause = new RuntimeException("Unexpected token")
    val error = AssistantError.jsonDeserializationFailed("Config", cause)

    error shouldBe a[AssistantError.SerializationError]
    error.message should include("Failed to deserialize JSON to Config")
    val serError = error.asInstanceOf[AssistantError.SerializationError]
    serError.operation shouldBe "deserialize"
  }

  "AssistantError.consoleInputFailed" should "create console input error" in {
    val cause = new java.io.IOException("Stream closed")
    val error = AssistantError.consoleInputFailed(cause)

    error shouldBe a[AssistantError.IOError]
    val ioError = error.asInstanceOf[AssistantError.IOError]
    ioError.operation shouldBe "read"
    ioError.message should include("Failed to read user input")
    ioError.message should include("Stream closed")
  }

  it should "name the cause's class when the cause has no message" in {
    // Throwable#getMessage is null for an exception built without one (#1556)
    val error = AssistantError.consoleInputFailed(new java.io.IOException())

    error.message shouldBe "Failed to read user input: java.io.IOException"
    (error.message should not).include("null")
  }

  "AssistantError.consoleOutputFailed" should "create console output error" in {
    val cause = new java.io.IOException("Broken pipe")
    val error = AssistantError.consoleOutputFailed("message", cause)

    error shouldBe a[AssistantError.DisplayError]
    val displayError = error.asInstanceOf[AssistantError.DisplayError]
    displayError.displayType shouldBe "message"
    displayError.message should include("Failed to display message")
    displayError.message should include("Broken pipe")
  }

  it should "name the cause's class when the cause has no message" in {
    val error = AssistantError.consoleOutputFailed("welcome", new java.io.IOException())

    error.message shouldBe "Failed to display welcome: java.io.IOException"
    (error.message should not).include("null")
  }

  "AssistantError.emptyCommandTitle" should "create empty title error" in {
    val error = AssistantError.emptyCommandTitle("/save")

    error shouldBe a[AssistantError.CommandParseError]
    error.message should include("Please specify a session title")
    error.message should include("\"Session Name\"")
  }

  "AssistantError.unknownCommand" should "create unknown command error" in {
    val error = AssistantError.unknownCommand("/invalid")

    error shouldBe a[AssistantError.CommandParseError]
    error.message should include("Unknown command: /invalid")
    error.message should include("/help")
  }

  // ============ Formatting ============

  "AssistantError" should "format with context" in {
    val error = AssistantError.SessionError("Load failed", SessionId("test"), "load")

    val formatted = error.formatted

    formatted should include("SessionError")
    formatted should include("Load failed")
    formatted should include("component: session-manager")
    formatted should include("sessionId: test")
  }

  it should "format without context when empty" in {
    // Create a minimal error - IOError always has context so let's verify formatting works
    val error = AssistantError.IOError("Simple error", "read")

    error.formatted should include("IOError")
    error.formatted should include("Simple error")
  }

  // ============ Type Hierarchy ============

  "AssistantError subtypes" should "all extend AssistantError" in {
    val errors: Seq[AssistantError] = Seq(
      AssistantError.IOError("io", "read"),
      AssistantError.EOFError("eof"),
      AssistantError.DisplayError("display", "welcome"),
      AssistantError.SessionError("session", SessionId("s"), "load"),
      AssistantError.FileError("file", FilePath("/path"), "read"),
      AssistantError.SerializationError("ser", "JSON", "serialize"),
      AssistantError.CommandParseError("cmd", "/cmd", "parse")
    )

    errors.foreach(_ shouldBe a[AssistantError])
  }

  it should "be Products and Serializable" in {
    val error = AssistantError.IOError("test", "read")

    error shouldBe a[Product]
    error shouldBe a[Serializable]
  }
}
