package org.llm4s.shared

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

/**
 * Tests for WorkspaceAgentProtocol types and JSON serialization.
 */
class WorkspaceAgentProtocolSpec extends AnyFlatSpec with Matchers {

  // ============ FileMetadata ============

  "FileMetadata" should "serialize to JSON" in {
    val metadata = FileMetadata(
      path = "/test/file.txt",
      size = 1024L,
      isDirectory = false,
      lastModified = "2024-01-15T10:30:00Z"
    )
    val json   = write(metadata)
    val parsed = read[FileMetadata](json)

    parsed.path shouldBe "/test/file.txt"
    parsed.size shouldBe 1024L
    parsed.isDirectory shouldBe false
    parsed.lastModified shouldBe "2024-01-15T10:30:00Z"
  }

  // ============ ExploreFilesCommand ============

  "ExploreFilesCommand" should "serialize with all fields" in {
    val cmd = ExploreFilesCommand(
      commandId = "cmd-1",
      path = "/workspace",
      recursive = Some(true),
      excludePatterns = Some(List("*.tmp", "node_modules")),
      maxDepth = Some(3),
      returnMetadata = Some(true)
    )
    val json   = write(cmd)
    val parsed = read[ExploreFilesCommand](json)

    parsed.commandId shouldBe "cmd-1"
    parsed.path shouldBe "/workspace"
    parsed.recursive shouldBe Some(true)
    parsed.excludePatterns shouldBe Some(List("*.tmp", "node_modules"))
    parsed.maxDepth shouldBe Some(3)
  }

  it should "serialize with minimal fields" in {
    val cmd    = ExploreFilesCommand(commandId = "cmd-2", path = "/src")
    val json   = write(cmd)
    val parsed = read[ExploreFilesCommand](json)

    parsed.commandId shouldBe "cmd-2"
    parsed.path shouldBe "/src"
    parsed.recursive shouldBe None
  }

  // ============ ReadFileCommand ============

  "ReadFileCommand" should "serialize with line range" in {
    val cmd = ReadFileCommand(
      commandId = "read-1",
      path = "/src/main.scala",
      startLine = Some(10),
      endLine = Some(20)
    )
    val json   = write(cmd)
    val parsed = read[ReadFileCommand](json)

    parsed.commandId shouldBe "read-1"
    parsed.startLine shouldBe Some(10)
    parsed.endLine shouldBe Some(20)
  }

  // ============ WriteFileCommand ============

  "WriteFileCommand" should "serialize with all options" in {
    val cmd = WriteFileCommand(
      commandId = "write-1",
      path = "/output/result.txt",
      content = "Hello, World!",
      mode = Some("overwrite"),
      createDirectories = Some(true)
    )
    val json   = write(cmd)
    val parsed = read[WriteFileCommand](json)

    parsed.commandId shouldBe "write-1"
    parsed.content shouldBe "Hello, World!"
    parsed.mode shouldBe Some("overwrite")
    parsed.createDirectories shouldBe Some(true)
  }

  // ============ ModifyFileCommand ============

  "ModifyFileCommand" should "serialize replace operation" in {
    val cmd = ModifyFileCommand(
      commandId = "mod-1",
      path = "/src/file.txt",
      operations = List(
        ReplaceOperation(startLine = 5, endLine = 10, newContent = "new content")
      )
    )
    val json   = write(cmd)
    val parsed = read[ModifyFileCommand](json)

    parsed.operations should have size 1
    parsed.operations.head shouldBe a[ReplaceOperation]
  }

  it should "serialize insert operation" in {
    val cmd = ModifyFileCommand(
      commandId = "mod-2",
      path = "/src/file.txt",
      operations = List(
        InsertOperation(afterLine = 5, newContent = "inserted line")
      )
    )
    val json   = write(cmd)
    val parsed = read[ModifyFileCommand](json)

    parsed.operations.head shouldBe a[InsertOperation]
  }

  it should "serialize delete operation" in {
    val cmd = ModifyFileCommand(
      commandId = "mod-3",
      path = "/src/file.txt",
      operations = List(
        DeleteOperation(startLine = 1, endLine = 3)
      )
    )
    val json   = write(cmd)
    val parsed = read[ModifyFileCommand](json)

    parsed.operations.head shouldBe a[DeleteOperation]
  }

  it should "serialize regex replace operation" in {
    val cmd = ModifyFileCommand(
      commandId = "mod-4",
      path = "/src/file.txt",
      operations = List(
        RegexReplaceOperation(pattern = "foo", replacement = "bar", flags = Some("gi"))
      )
    )
    val json   = write(cmd)
    val parsed = read[ModifyFileCommand](json)

    val op = parsed.operations.head.asInstanceOf[RegexReplaceOperation]
    op.pattern shouldBe "foo"
    op.replacement shouldBe "bar"
    op.flags shouldBe Some("gi")
  }

  // ============ SearchFilesCommand ============

  "SearchFilesCommand" should "serialize with all options" in {
    val cmd = SearchFilesCommand(
      commandId = "search-1",
      paths = List("/src", "/test"),
      query = "def main",
      `type` = "literal",
      recursive = Some(true),
      excludePatterns = Some(List("*.class")),
      contextLines = Some(3)
    )
    val json   = write(cmd)
    val parsed = read[SearchFilesCommand](json)

    parsed.paths shouldBe List("/src", "/test")
    parsed.query shouldBe "def main"
    parsed.`type` shouldBe "literal"
    parsed.contextLines shouldBe Some(3)
  }

  // ============ ExecuteCommandCommand ============

  "ExecuteCommandCommand" should "serialize with environment" in {
    val cmd = ExecuteCommandCommand(
      commandId = "exec-1",
      command = "ls -la",
      workingDirectory = Some("/home"),
      timeout = Some(30000),
      environment = Some(Map("PATH" -> "/usr/bin", "HOME" -> "/home"))
    )
    val json   = write(cmd)
    val parsed = read[ExecuteCommandCommand](json)

    parsed.command shouldBe "ls -la"
    parsed.workingDirectory shouldBe Some("/home")
    parsed.environment.get("PATH") shouldBe "/usr/bin"
  }

  // ============ Response Types ============

  "ExploreFilesResponse" should "serialize correctly" in {
    val response = ExploreFilesResponse(
      commandId = "cmd-1",
      files = List(
        FileEntry("file1.txt", isDirectory = false, None),
        FileEntry("subdir", isDirectory = true, None)
      ),
      isTruncated = false,
      totalFound = 2
    )
    val json   = write(response)
    val parsed = read[ExploreFilesResponse](json)

    parsed.files should have size 2
    parsed.totalFound shouldBe 2
  }

  "ReadFileResponse" should "serialize correctly" in {
    val response = ReadFileResponse(
      commandId = "read-1",
      content = "file content here",
      metadata = FileMetadata("/test.txt", 17L, isDirectory = false, "2024-01-01"),
      isTruncated = false,
      totalLines = 1,
      returnedLines = 1
    )
    val json   = write(response)
    val parsed = read[ReadFileResponse](json)

    parsed.content shouldBe "file content here"
    parsed.metadata.size shouldBe 17L
  }

  "ExecuteCommandResponse" should "serialize correctly" in {
    val response = ExecuteCommandResponse(
      commandId = "exec-1",
      stdout = "output",
      stderr = "",
      exitCode = 0,
      isOutputTruncated = false,
      durationMs = 150L
    )
    val json   = write(response)
    val parsed = read[ExecuteCommandResponse](json)

    parsed.stdout shouldBe "output"
    parsed.exitCode shouldBe 0
    parsed.durationMs shouldBe 150L
  }

  "WorkspaceAgentErrorResponse" should "serialize with details" in {
    val response = WorkspaceAgentErrorResponse(
      commandId = "err-1",
      error = "File not found",
      code = "FILE_NOT_FOUND",
      details = Some("Path /missing.txt does not exist")
    )
    val json   = write(response)
    val parsed = read[WorkspaceAgentErrorResponse](json)

    parsed.error shouldBe "File not found"
    parsed.code shouldBe "FILE_NOT_FOUND"
    parsed.details shouldBe Some("Path /missing.txt does not exist")
  }

  // ============ WebSocket Messages ============

  "CommandMessage" should "wrap command correctly" in {
    val cmd = ReadFileCommand("cmd-1", "/test.txt", None, None)
    val msg = CommandMessage(cmd)

    val json   = write(msg)
    val parsed = read[CommandMessage](json)

    parsed.command.commandId shouldBe "cmd-1"
  }

  "HeartbeatMessage" should "have timestamp" in {
    val msg    = HeartbeatMessage()
    val json   = write(msg)
    val parsed = read[HeartbeatMessage](json)

    parsed.timestamp should be > 0L
  }

  "StreamingOutputMessage" should "serialize correctly" in {
    val msg = StreamingOutputMessage(
      commandId = "stream-1",
      outputType = "stdout",
      content = "partial output",
      isComplete = false
    )
    val json   = write(msg)
    val parsed = read[StreamingOutputMessage](json)

    parsed.outputType shouldBe "stdout"
    parsed.isComplete shouldBe false
  }

  "CancelCommandMessage" should "serialize correctly" in {
    val msg    = CancelCommandMessage("cancel-1")
    val json   = write(msg)
    val parsed = read[CancelCommandMessage](json)

    parsed.commandId shouldBe "cancel-1"
  }

  "WebSocketMessage" should "round-trip CancelCommandMessage polymorphically" in {
    val msg    = CancelCommandMessage("cancel-2")
    val json   = write[WebSocketMessage](msg)
    val parsed = read[WebSocketMessage](json)

    parsed shouldBe msg
  }

  // ============ Polymorphic Serialization ============

  "WorkspaceAgentCommand" should "serialize polymorphically" in {
    val commands: Seq[WorkspaceAgentCommand] = Seq(
      ExploreFilesCommand("1", "/"),
      ReadFileCommand("2", "/file.txt", None, None),
      WriteFileCommand("3", "/out.txt", "content", None, None),
      GetWorkspaceInfoCommand("4")
    )

    commands.foreach { cmd =>
      val json   = write[WorkspaceAgentCommand](cmd)
      val parsed = read[WorkspaceAgentCommand](json)
      parsed.commandId shouldBe cmd.commandId
    }
  }

  "WorkspaceAgentResponse" should "serialize polymorphically" in {
    val metadata = FileMetadata("/test", 0L, isDirectory = false, "2024")
    val responses: Seq[WorkspaceAgentResponse] = Seq(
      ExploreFilesResponse("1", List(), isTruncated = false, 0),
      ReadFileResponse("2", "", metadata, isTruncated = false, 0, 0),
      WriteFileResponse("3", success = true, "/test", 0L),
      WorkspaceAgentErrorResponse("4", "error", "CODE", None)
    )

    responses.foreach { resp =>
      val json   = write[WorkspaceAgentResponse](resp)
      val parsed = read[WorkspaceAgentResponse](json)
      parsed.commandId shouldBe resp.commandId
    }
  }
}
