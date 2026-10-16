package org.llm4s.shared

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues
import scala.concurrent.duration.*

class WorkspaceAgentInterfaceTest extends AnyFlatSpec with Matchers with OptionValues {

  "WorkspaceAgentInterfaceRemote" should "convert method calls to commands and responses" in {
    // Create a handler that returns predefined responses based on command type
    val handler: WorkspaceAgentCommand => WorkspaceAgentResponse = {
      case cmd: GetWorkspaceInfoCommand =>
        GetWorkspaceInfoResponse(
          commandId = cmd.commandId,
          root = "/test",
          defaultExclusions = List("**/node_modules/**"),
          limits = WorkspaceLimits(1000, 100, 50, 1000)
        )

      case cmd: ReadFileCommand =>
        ReadFileResponse(
          commandId = cmd.commandId,
          content = s"Content of ${cmd.path}",
          metadata = FileMetadata(
            path = cmd.path,
            size = 100,
            isDirectory = false,
            lastModified = "2023-01-01T00:00:00Z"
          ),
          isTruncated = false,
          totalLines = 10,
          returnedLines = 10
        )

      case cmd: WriteFileCommand =>
        WriteFileResponse(
          commandId = cmd.commandId,
          success = true,
          path = cmd.path,
          bytesWritten = cmd.content.length
        )

      case cmd: ExecuteCommandCommand =>
        ExecuteCommandResponse(
          commandId = cmd.commandId,
          stdout = s"Executed: ${cmd.command}",
          stderr = "",
          exitCode = 0,
          isOutputTruncated = false,
          duration = 100.millis
        )

      case cmd: ExploreFilesCommand =>
        ExploreFilesResponse(
          commandId = cmd.commandId,
          files = List(
            FileEntry(path = "subdir/nested.txt", isDirectory = false),
            FileEntry(path = "test.txt", isDirectory = false)
          ),
          isTruncated = false,
          totalFound = 2
        )

      case cmd: ModifyFileCommand =>
        ModifyFileResponse(
          commandId = cmd.commandId,
          success = true,
          path = cmd.path
        )

      case cmd: FileSnapshotCommand =>
        FileSnapshotResponse(cmd.commandId, cmd.path, "file", "sha256:00", 3, Option.when(cmd.includeContent)("abc"))

      case cmd: DeleteFileCommand =>
        DeleteFileResponse(cmd.commandId, cmd.path + cmd.expectedHash.getOrElse(""), wasDirectory = false)

      case cmd: SearchFilesCommand =>
        SearchFilesResponse(
          commandId = cmd.commandId,
          matches = List(
            SearchMatch(
              path = "test.txt",
              line = 1,
              matchText = "Line containing the matched content", // Example match text
              contextBefore = List(),
              contextAfter = List()
            )
          ),
          isTruncated = false,
          totalMatches = 1
        )

    }

    val interface: WorkspaceAgentInterface = new WorkspaceAgentInterfaceRemote(handler)

    // Test getWorkspaceInfo
    val infoResponse = interface.getWorkspaceInfo()
    infoResponse.root shouldBe "/test"
    infoResponse.defaultExclusions should contain("**/node_modules/**")

    // Test readFile
    val readResponse = interface.readFile("test.txt")
    readResponse.content shouldBe "Content of test.txt"
    readResponse.metadata.path shouldBe "test.txt"

    // Test writeFile
    val writeResponse = interface.writeFile("output.txt", "Hello, world!")
    writeResponse.success shouldBe true
    writeResponse.path shouldBe "output.txt"
    writeResponse.bytesWritten shouldBe 13

    // Test executeCommand
    val execResponse = interface.executeCommand("ls -la")
    execResponse.stdout shouldBe "Executed: ls -la"
    execResponse.exitCode shouldBe 0

    // the compare-and-set calls carry their arguments
    interface.fileSnapshot("a.txt").content shouldBe Some("abc")
    interface.fileSnapshot("a.txt", includeContent = false).content shouldBe None
    interface.deleteFile("a.txt", Some("sha256:00")).path shouldBe "a.txtsha256:00"
  }

  "The protocol's new fields" should "read from a message an older peer sent, without them" in {
    import upickle.default.{ read, write }
    // each written with the new field set, then the field removed, as an older peer would send it
    def without(command: WorkspaceAgentCommand, field: String): WorkspaceAgentCommand = {
      val json = ujson.read(write[WorkspaceAgentCommand](command))
      json.obj.remove(field)
      read[WorkspaceAgentCommand](json)
    }
    without(WriteFileCommand("1", "a", "x", expectedHash = Some("absent")), "expectedHash") shouldBe
      WriteFileCommand("1", "a", "x")
    without(FileSnapshotCommand("1", "a", includeContent = false), "includeContent") shouldBe
      FileSnapshotCommand("1", "a", includeContent = true)
    without(ExecuteCommandCommand("1", "ls", maxOutputBytes = Some(5L)), "maxOutputBytes") shouldBe
      ExecuteCommandCommand("1", "ls")
    val snapshot = FileSnapshotResponse("1", "a", "file", FileSnapshotResponse.hashOf("x".getBytes("UTF-8")), 1L)
    read[WorkspaceAgentResponse](upickle.default.write[WorkspaceAgentResponse](snapshot)) shouldBe snapshot
    snapshot.hash shouldBe "sha256:2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881"
  }

  it should "throw WorkspaceAgentException when receiving ErrorResponse" in {
    // Create a handler that returns an error for a specific command
    val handler: WorkspaceAgentCommand => WorkspaceAgentResponse = {
      case cmd: ReadFileCommand if cmd.path == "missing.txt" =>
        WorkspaceAgentErrorResponse(
          commandId = cmd.commandId,
          error = "File not found",
          code = "FILE_NOT_FOUND",
          details = Some("The file missing.txt does not exist")
        )

      case cmd: ReadFileCommand =>
        ReadFileResponse(
          commandId = cmd.commandId,
          content = "Content",
          metadata = FileMetadata(
            path = cmd.path,
            size = 100,
            isDirectory = false,
            lastModified = "2023-01-01T00:00:00Z"
          ),
          isTruncated = false,
          totalLines = 1,
          returnedLines = 1
        )
      case cmd =>
        throw new Exception(s"Unexpected command: ${cmd.commandId}")
    }

    val interface: WorkspaceAgentInterface = new WorkspaceAgentInterfaceRemote(handler)

    // Test successful read
    val readResponse = interface.readFile("existing.txt")
    readResponse.content shouldBe "Content"

    // Test error case
    val exception = intercept[WorkspaceAgentException] {
      interface.readFile("missing.txt")
    }

    exception.error shouldBe "File not found"
    exception.code shouldBe "FILE_NOT_FOUND"
    exception.details.value shouldBe "The file missing.txt does not exist"
  }

  it should "throw exception for unexpected response types" in {
    // Create a handler that returns the wrong response type
    val handler: WorkspaceAgentCommand => WorkspaceAgentResponse = { case _ =>
      // Return a wrong response type
      WriteFileResponse(
        commandId = "n/a",
        success = true,
        path = "test.txt",
        bytesWritten = 100
      )
    }

    val interface: WorkspaceAgentInterface = new WorkspaceAgentInterfaceRemote(handler)

    val exception = intercept[WorkspaceAgentException] {
      interface.readFile("test.txt")
    }

    exception.error should include("Unexpected response type")
    exception.code shouldBe "UNEXPECTED_RESPONSE_TYPE"
  }
}
