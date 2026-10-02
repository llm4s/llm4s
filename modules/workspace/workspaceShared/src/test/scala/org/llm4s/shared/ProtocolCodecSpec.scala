package org.llm4s.shared

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for ProtocolCodec JSON encoding/decoding.
 */
class ProtocolCodecSpec extends AnyFlatSpec with Matchers {

  // ============ Command Encoding/Decoding ============

  "ProtocolCodec.encodeAgentCommand" should "encode ReadFileCommand" in {
    val command = ReadFileCommand("cmd-1", "/test/file.txt", Some(1), Some(10))
    val json    = ProtocolCodec.encodeAgentCommand(command)

    json should include("cmd-1")
    json should include("/test/file.txt")
  }

  it should "encode WriteFileCommand" in {
    val command = WriteFileCommand("cmd-2", "/output.txt", "content", Some("overwrite"), Some(true))
    val json    = ProtocolCodec.encodeAgentCommand(command)

    json should include("cmd-2")
    json should include("content")
    json should include("overwrite")
  }

  it should "encode ExploreFilesCommand" in {
    val command = ExploreFilesCommand("cmd-3", "/workspace", Some(true), None, Some(5), None)
    val json    = ProtocolCodec.encodeAgentCommand(command)

    json should include("cmd-3")
    json should include("/workspace")
  }

  it should "encode GetWorkspaceInfoCommand" in {
    val command = GetWorkspaceInfoCommand("cmd-4")
    val json    = ProtocolCodec.encodeAgentCommand(command)

    json should include("cmd-4")
  }

  "ProtocolCodec.decodeAgentCommand" should "decode ReadFileCommand" in {
    val command = ReadFileCommand("cmd-1", "/test.txt", None, None)
    val json    = ProtocolCodec.encodeAgentCommand(command)
    val decoded = ProtocolCodec.decodeAgentCommand(json)

    decoded shouldBe a[ReadFileCommand]
    decoded.commandId shouldBe "cmd-1"
    decoded.asInstanceOf[ReadFileCommand].path shouldBe "/test.txt"
  }

  it should "decode ExecuteCommandCommand" in {
    val command = ExecuteCommandCommand("exec-1", "ls -la", Some("/home"), Some(5.seconds), None)
    val json    = ProtocolCodec.encodeAgentCommand(command)
    val decoded = ProtocolCodec.decodeAgentCommand(json)

    decoded shouldBe a[ExecuteCommandCommand]
    val exec = decoded.asInstanceOf[ExecuteCommandCommand]
    exec.command shouldBe "ls -la"
    exec.workingDirectory shouldBe Some("/home")
    exec.timeout shouldBe Some(5.seconds)
  }

  it should "decode ModifyFileCommand with operations" in {
    val command = ModifyFileCommand(
      "mod-1",
      "/file.txt",
      List(
        ReplaceOperation(startLine = 1, endLine = 5, newContent = "new"),
        InsertOperation(afterLine = 10, newContent = "inserted")
      )
    )
    val json    = ProtocolCodec.encodeAgentCommand(command)
    val decoded = ProtocolCodec.decodeAgentCommand(json)

    decoded shouldBe a[ModifyFileCommand]
    val mod = decoded.asInstanceOf[ModifyFileCommand]
    mod.operations should have size 2
    mod.operations.head shouldBe a[ReplaceOperation]
    mod.operations(1) shouldBe a[InsertOperation]
  }

  // ============ Response Encoding/Decoding ============

  "ProtocolCodec.encodeAgentResponse" should "encode ReadFileResponse" in {
    val metadata = FileMetadata("/test.txt", 100L, isDirectory = false, "2024-01-01")
    val response = ReadFileResponse("cmd-1", "file content", metadata, isTruncated = false, 10, 10)
    val json     = ProtocolCodec.encodeAgentResponse(response)

    json should include("cmd-1")
    json should include("file content")
  }

  it should "encode ErrorResponse" in {
    val response = WorkspaceAgentErrorResponse("err-1", "Not found", "NOT_FOUND", Some("details"))
    val json     = ProtocolCodec.encodeAgentResponse(response)

    json should include("err-1")
    json should include("Not found")
    json should include("NOT_FOUND")
  }

  "ProtocolCodec.decodeAgentResponse" should "decode WriteFileResponse" in {
    val response = WriteFileResponse("cmd-1", success = true, "/output.txt", 1024L)
    val json     = ProtocolCodec.encodeAgentResponse(response)
    val decoded  = ProtocolCodec.decodeAgentResponse(json)

    decoded shouldBe a[WriteFileResponse]
    val write = decoded.asInstanceOf[WriteFileResponse]
    write.success shouldBe true
    write.bytesWritten shouldBe 1024L
  }

  it should "decode ExecuteCommandResponse" in {
    val response = ExecuteCommandResponse(
      "exec-1",
      stdout = "output",
      stderr = "error",
      exitCode = 1,
      isOutputTruncated = true,
      duration = 500.millis
    )
    val json    = ProtocolCodec.encodeAgentResponse(response)
    val decoded = ProtocolCodec.decodeAgentResponse(json)

    decoded shouldBe a[ExecuteCommandResponse]
    val exec = decoded.asInstanceOf[ExecuteCommandResponse]
    exec.stdout shouldBe "output"
    exec.exitCode shouldBe 1
    exec.duration shouldBe 500.millis
  }

  it should "decode GetWorkspaceInfoResponse" in {
    val limits   = WorkspaceLimits(10000L, 1000, 500, 50000L)
    val response = GetWorkspaceInfoResponse("info-1", "/workspace", List("node_modules", ".git"), limits)
    val json     = ProtocolCodec.encodeAgentResponse(response)
    val decoded  = ProtocolCodec.decodeAgentResponse(json)

    decoded shouldBe a[GetWorkspaceInfoResponse]
    val info = decoded.asInstanceOf[GetWorkspaceInfoResponse]
    info.root shouldBe "/workspace"
    info.defaultExclusions should contain("node_modules")
    info.limits.maxFileSize shouldBe 10000L
  }

  // ============ Round-trip Tests ============

  "ProtocolCodec" should "round-trip all command types" in {
    val commands: Seq[WorkspaceAgentCommand] = Seq(
      ReadFileCommand("1", "/file.txt", Some(1), Some(10)),
      WriteFileCommand("2", "/out.txt", "content", None, None),
      ExploreFilesCommand("3", "/dir", Some(true), None, None, None),
      ExecuteCommandCommand("4", "echo test", None, None, None),
      GetWorkspaceInfoCommand("5"),
      ModifyFileCommand("6", "/f.txt", List(DeleteOperation(startLine = 1, endLine = 2))),
      SearchFilesCommand("7", List("/src"), "pattern", "regex", None, None, None)
    )

    commands.foreach { cmd =>
      val json    = ProtocolCodec.encodeAgentCommand(cmd)
      val decoded = ProtocolCodec.decodeAgentCommand(json)
      decoded.commandId shouldBe cmd.commandId
    }
  }

  it should "round-trip all response types" in {
    val metadata = FileMetadata("/path", 0L, isDirectory = false, "2024")
    val limits   = WorkspaceLimits(1000L, 100, 50, 5000L)

    val responses: Seq[WorkspaceAgentResponse] = Seq(
      ReadFileResponse("1", "content", metadata, isTruncated = false, 1, 1),
      WriteFileResponse("2", success = true, "/path", 100L),
      ExploreFilesResponse("3", List(), isTruncated = false, 0),
      ExecuteCommandResponse("4", "out", "err", 0, isOutputTruncated = false, 100.millis),
      GetWorkspaceInfoResponse("5", "/root", List(), limits),
      ModifyFileResponse("6", success = true, "/file"),
      SearchFilesResponse("7", List(), isTruncated = false, 0),
      WorkspaceAgentErrorResponse("8", "error", "CODE", None)
    )

    responses.foreach { resp =>
      val json    = ProtocolCodec.encodeAgentResponse(resp)
      val decoded = ProtocolCodec.decodeAgentResponse(json)
      decoded.commandId shouldBe resp.commandId
    }
  }

  // ============ Error Handling ============

  "ProtocolCodec.decodeAgentCommand" should "throw on invalid JSON" in {
    an[Exception] should be thrownBy {
      ProtocolCodec.decodeAgentCommand("not valid json")
    }
  }

  it should "throw on empty JSON" in {
    an[Exception] should be thrownBy {
      ProtocolCodec.decodeAgentCommand("{}")
    }
  }

  "ProtocolCodec.decodeAgentResponse" should "throw on invalid JSON" in {
    an[Exception] should be thrownBy {
      ProtocolCodec.decodeAgentResponse("invalid")
    }
  }
}
