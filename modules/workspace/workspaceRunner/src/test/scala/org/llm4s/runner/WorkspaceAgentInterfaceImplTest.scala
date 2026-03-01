package org.llm4s.runner

import org.llm4s.shared._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters._
import scala.util.Using

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

    content should include("Modified Line 2")
    content should include("Modified Line 3")

    val lineSep = System.lineSeparator()
    val expectedContent = Seq(
      "Line 1",
      "Modified Line 2",
      "Modified Line 3",
      "Line 5"
    ).mkString(lineSep) + lineSep
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
    // truncated should be false when number of hits is small
    response.isTruncated shouldBe false
  }

  it should "set isTruncated when result cap is reached" in {
    // prepare a file with multiple matches
    val bigFile = tempDir.resolve("big.txt")
    val content = List.fill(5)("needle").mkString("\n")
    Files.write(bigFile, content.getBytes(StandardCharsets.UTF_8))

    // create an interface with a very small search limit so we hit truncation
    val tinyLimits = WorkspaceSandboxConfig.DefaultLimits.copy(maxSearchResults = 2)
    val smallInterface = new WorkspaceAgentInterfaceImpl(workspacePath, isWindowsHost, Some(WorkspaceSandboxConfig(limits = tinyLimits)))

    val resp = smallInterface.searchFiles(
      paths = List("."),
      query = "needle",
      searchType = "literal",
      recursive = Some(true)
    )

    resp.matches.size shouldBe 2
    resp.isTruncated shouldBe true
    // totalMatches is only guaranteed to go one past the cap; we don't scan the
    // whole workspace for performance reasons.
    resp.totalMatches shouldBe 3
  }

  it should "exclude default patterns consistently with Windows path separators" in {
    val issueDir      = tempDir.resolve("issue-718")
    val projectDir    = issueDir.resolve("project")
    val gitDir        = projectDir.resolve(".git")
    val gitConfigFile = gitDir.resolve("config")
    val targetDir     = projectDir.resolve("target")
    val classFile     = targetDir.resolve("App.class")
    val srcDir        = projectDir.resolve("src")
    val srcFile       = srcDir.resolve("Main.scala")

    Files.createDirectories(projectDir)
    Files.createDirectories(gitDir)
    Files.createDirectories(targetDir)
    Files.createDirectories(srcDir)
    Files.write(gitConfigFile, "[core]\nrepositoryformatversion = 0".getBytes(StandardCharsets.UTF_8))
    Files.write(classFile, "bytecode".getBytes(StandardCharsets.UTF_8))
    Files.write(srcFile, "object Main".getBytes(StandardCharsets.UTF_8))

    val response = interface.exploreFiles(
      "issue-718",
      recursive = Some(true),
      excludePatterns = Some(List("**/.git/**", "**/target/**"))
    )

    val normalizedPaths = response.files.map(_.path.replace("\\", "/"))
    normalizedPaths should not contain "issue-718/project/.git"
    normalizedPaths should not contain "issue-718/project/.git/config"
    normalizedPaths should not contain "issue-718/project/target"
    normalizedPaths should not contain "issue-718/project/target/App.class"
    normalizedPaths should contain("issue-718/project/src/Main.scala")
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
        Using.resource(Files.list(path))(stream => stream.iterator().asScala.foreach(deleteRecursively))
      }
      Files.deleteIfExists(path)
    }

    deleteRecursively(tempDir)
    super.afterAll()
  }
}
