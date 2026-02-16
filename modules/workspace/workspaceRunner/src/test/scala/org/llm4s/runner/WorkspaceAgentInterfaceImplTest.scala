package org.llm4s.runner

import org.llm4s.shared._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class WorkspaceAgentInterfaceImplTest extends AnyFlatSpec with Matchers with org.scalatest.BeforeAndAfterAll {

  // Create a temporary workspace for testing
  val tempDir               = Files.createTempDirectory("workspace-test")
  val workspacePath         = tempDir.toString
  private val isWindowsHost = System.getProperty("os.name").startsWith("Windows")
  val interface             = new WorkspaceAgentInterfaceImpl(workspacePath, isWindowsHost)

  // Create some test files
  val testFile1 = tempDir.resolve("test1.txt")
  val testFile2 = tempDir.resolve("test2.txt")
  val testDir   = tempDir.resolve("subdir")

  Files.write(testFile1, "Hello, world!\nThis is a test file.\nLine 3".getBytes(StandardCharsets.UTF_8))
  Files.write(testFile2, "Another test file.\nWith multiple lines.\nFor testing.".getBytes(StandardCharsets.UTF_8))
  Files.createDirectory(testDir)
  Files.write(testDir.resolve("nested.txt"), "Nested file content".getBytes(StandardCharsets.UTF_8))

  "WorkspaceAgentInterfaceImpl" should "get workspace info" in {
    val info = interface.getWorkspaceInfo()

    info.root should include("workspace-test")
    info.defaultExclusions should not be empty
    info.limits.maxFileSize should be > 0L
  }

  it should "explore files in the workspace" in {
    val response = interface.exploreFiles(".")

    val normalizedPaths = response.files.map(f => f.path.replace("\\", "/"))
    (normalizedPaths should contain).allOf("test1.txt", "test2.txt", "subdir")
    response.isTruncated shouldBe false

    // Test recursive exploration
    val recursiveResponse = interface.exploreFiles(".", recursive = Some(true))
    recursiveResponse.files.map(f => f.path.replace("\\", "/")) should contain("subdir/nested.txt")
  }

  it should "read file content" in {
    val response = interface.readFile("test1.txt")

    response.content.replace("\r\n", "\n") shouldBe "Hello, world!\nThis is a test file.\nLine 3"
    response.metadata.path shouldBe "test1.txt"
    response.totalLines shouldBe 3

    // Test with line range
    val rangeResponse = interface.readFile("test1.txt", startLine = Some(2), endLine = Some(3))
    rangeResponse.content shouldBe "This is a test file.\nLine 3"
    rangeResponse.returnedLines shouldBe 2
  }

  it should "write file content" in {
    val response = interface.writeFile("new-file.txt", "New content")

    response.success shouldBe true
    response.bytesWritten shouldBe 11

    // Verify file was written
    Files.exists(tempDir.resolve("new-file.txt")) shouldBe true
    new String(Files.readAllBytes(tempDir.resolve("new-file.txt")), StandardCharsets.UTF_8) shouldBe "New content"
  }

  it should "modify file content" in {
    // First create a file to modify
    interface.writeFile("modify-test.txt", "Line 1\nLine 2\nOld Line 3\nLine 4\nLine 5")

    // Test replace operation
    val replaceOp = ReplaceOperation(
      startLine = 2,
      endLine = 4,
      newContent = "Modified Line 2\nModified Line 3"
    )

    val response = interface.modifyFile("modify-test.txt", List(replaceOp))
    response.success shouldBe true

    // Verify modification
    val content = new String(Files.readAllBytes(tempDir.resolve("modify-test.txt")), StandardCharsets.UTF_8)

    println("----- DEBUG [TEST]: Content read after modifyFile -----")
    println(s"```\n${content}\n```") // Print the content clearly delimited
    println("----- END DEBUG [TEST] -----")

    content should include("Modified Line 2")
    content should include("Modified Line 3")
    // (content should not).include("Line 3")

    val lineSep = System.lineSeparator()

// Construct the exact expected string. Note: writer.println adds a newline AFTER EACH line.
    val expectedContent = Seq(
      "Line 1",
      "Modified Line 2",
      "Modified Line 3",
      "Line 5"
    ).mkString(lineSep) + lineSep // Add the trailing newline added by the last println

// Perform the precise comparison
    content shouldBe expectedContent

  }

  it should "search files for content" in {
    val response = interface.searchFiles(
      paths = List("."),
      query = "test",
      searchType = "literal",
      recursive = Some(true)
    )

    response.matches should not be empty
    response.matches.exists(_.path == "test1.txt") shouldBe true
    response.matches.exists(_.path == "test2.txt") shouldBe true
  }

  it should "execute commands" in {
    // This test is platform-dependent, so we'll use a simple command
    val testCommand = if (System.getProperty("os.name").startsWith("Windows")) {
      "echo test command"
    } else {
      "echo 'test command'"
    }
    val response = interface.executeCommand(testCommand)
    response.exitCode shouldBe 0
    response.stdout should include("test command")
  }

  it should "reject executeCommand when sandbox has shellAllowed=false" in {
    val lockedInterface = new WorkspaceAgentInterfaceImpl(
      workspacePath,
      isWindowsHost,
      Some(WorkspaceSandboxConfig.LockedDown)
    )
    val ex = the[WorkspaceAgentException] thrownBy lockedInterface.executeCommand("echo hello")
    ex.code shouldBe "SHELL_DISABLED"
    ex.error should include("shellAllowed")
  }

  // Clean up after tests
  override def afterAll(): Unit = {
    // Delete temporary directory recursively
    def deleteRecursively(path: java.nio.file.Path): Unit = {
      if (Files.isDirectory(path)) {
        Files.list(path).forEach(p => deleteRecursively(p))
      }
      Files.deleteIfExists(path)
    }

    deleteRecursively(tempDir)
    super.afterAll()
  }
}
