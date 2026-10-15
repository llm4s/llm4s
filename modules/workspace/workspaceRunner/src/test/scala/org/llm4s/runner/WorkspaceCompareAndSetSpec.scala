package org.llm4s.runner

import org.llm4s.shared._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * The runner's side of the harness's `Verifiable` workspace backend (design §4.33): snapshots whose hash is
 * C4's `StateCheck` format, compare-and-set writes and deletes that change nothing when they lose, atomic
 * replacement, and the sandbox's `allowedPaths`, `readOnlyPaths` and size limits enforced in the runner (#870).
 */
class WorkspaceCompareAndSetSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val isWindowsHost = System.getProperty("os.name").startsWith("Windows")
  private var root: Path    = _

  override def beforeEach(): Unit = root = Files.createTempDirectory("workspace-cas")

  override def afterEach(): Unit = {
    def deleteRecursively(path: Path): Unit = {
      if (Files.isDirectory(path))
        Using.resource(Files.list(path))(_.iterator().asScala.toList.foreach(deleteRecursively))
      Files.deleteIfExists(path)
    }
    deleteRecursively(root)
  }

  private def workspace(config: WorkspaceSandboxConfig = WorkspaceSandboxConfig()) =
    new WorkspaceAgentInterfaceImpl(root.toString, isWindowsHost, Some(config))

  private def code(body: => Any): String =
    intercept[WorkspaceAgentException](body).code

  private def hashOf(text: String) = FileSnapshotResponse.hashOf(text.getBytes(StandardCharsets.UTF_8))

  "fileSnapshot" should "tell nothing, a directory and a file apart, with the file's exact text and its hash" in {
    val ws = workspace()
    ws.fileSnapshot("missing").kind shouldBe "absent"
    ws.fileSnapshot("missing").hash shouldBe "absent"
    Files.createDirectories(root.resolve("dir"))
    ws.fileSnapshot("dir").hash shouldBe "directory"
    // a trailing newline and a carriage return survive, as readFile's lines would not keep them
    Files.write(root.resolve("dir/a.txt"), "one\r\ntwo\n".getBytes(StandardCharsets.UTF_8))
    val snapshot = ws.fileSnapshot("dir/a.txt")
    snapshot.kind shouldBe "file"
    snapshot.content shouldBe Some("one\r\ntwo\n")
    snapshot.hash shouldBe hashOf("one\r\ntwo\n")
    snapshot.size shouldBe 9L
    ws.fileSnapshot("dir/a.txt", includeContent = false) shouldBe snapshot.copy(content = None)
  }

  it should "give the hash but no text for a file that is not UTF-8 or is over the size limit" in {
    Files.write(root.resolve("bin"), Array[Byte](0xff.toByte, 0xfe.toByte))
    val binary = workspace().fileSnapshot("bin")
    binary.content shouldBe None
    binary.textError.get should include("UTF-8")
    binary.hash shouldBe FileSnapshotResponse.hashOf(Array[Byte](0xff.toByte, 0xfe.toByte))

    Files.write(root.resolve("big"), ("x" * 20).getBytes(StandardCharsets.UTF_8))
    val small = workspace(WorkspaceSandboxConfig(limits = WorkspaceSandboxConfig.DefaultLimits.copy(maxFileSize = 10)))
    val big   = small.fileSnapshot("big")
    big.content shouldBe None
    big.textError.get should include("larger")
    big.hash shouldBe hashOf("x" * 20)
  }

  "A compare-and-set write" should "write when the hash matches, creating the file and its parents" in {
    val ws = workspace()
    ws.writeFile("d/e/a.txt", "hello", createDirectories = Some(true), expectedHash = Some("absent"))
    ws.fileSnapshot("d/e/a.txt").content shouldBe Some("hello")
    ws.writeFile("d/e/a.txt", "again", expectedHash = Some(hashOf("hello")))
    ws.fileSnapshot("d/e/a.txt").content shouldBe Some("again")
    // no temporary file is left beside it
    Using.resource(Files.list(root.resolve("d/e")))(_.iterator().asScala.map(_.getFileName.toString).toList) shouldBe
      List("a.txt")
  }

  it should "change nothing, not even a parent directory, when the hash does not match" in {
    val ws = workspace()
    code(ws.writeFile("new/a.txt", "x", createDirectories = Some(true), expectedHash = Some(hashOf("y")))) shouldBe
      "HASH_MISMATCH"
    Files.exists(root.resolve("new")) shouldBe false
    Files.write(root.resolve("a.txt"), "one".getBytes(StandardCharsets.UTF_8))
    code(ws.writeFile("a.txt", "two", expectedHash = Some("absent"))) shouldBe "HASH_MISMATCH"
    ws.fileSnapshot("a.txt").content shouldBe Some("one")
  }

  it should "keep the file's permissions" in {
    assume(!isWindowsHost)
    val file = root.resolve("run.sh")
    Files.write(file, "echo".getBytes(StandardCharsets.UTF_8))
    Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---"))
    workspace().writeFile("run.sh", "echo hi", expectedHash = Some(hashOf("echo")))
    java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rwxr-x---"
  }

  it should "refuse a mode other than overwrite, and a directory" in {
    val ws = workspace()
    code(ws.writeFile("a.txt", "x", mode = Some("append"), expectedHash = Some("absent"))) shouldBe "INVALID_ARGUMENT"
    Files.createDirectories(root.resolve("dir"))
    code(ws.writeFile("dir", "x", expectedHash = Some("directory"))) shouldBe "NOT_A_FILE"
  }

  it should "refuse parents that cannot be created because a file is in the way" in {
    Files.write(root.resolve("f"), "x".getBytes(StandardCharsets.UTF_8))
    code(workspace().writeFile("f/a.txt", "x", createDirectories = Some(true), expectedHash = Some("absent"))) shouldBe
      "PARENT_NOT_A_DIRECTORY"
  }

  "A compare-and-set delete" should "delete a file or an empty directory whose hash matches, and nothing else" in {
    val ws = workspace()
    Files.write(root.resolve("a.txt"), "one".getBytes(StandardCharsets.UTF_8))
    code(ws.deleteFile("a.txt", Some(hashOf("two")))) shouldBe "HASH_MISMATCH"
    Files.exists(root.resolve("a.txt")) shouldBe true
    ws.deleteFile("a.txt", Some(hashOf("one"))).wasDirectory shouldBe false
    Files.exists(root.resolve("a.txt")) shouldBe false
    code(ws.deleteFile("a.txt")) shouldBe "FILE_NOT_FOUND"

    Files.createDirectories(root.resolve("d/e"))
    code(ws.deleteFile("d", Some("directory"))) shouldBe "DIRECTORY_NOT_EMPTY"
    ws.deleteFile("d/e", Some("directory")).wasDirectory shouldBe true
    code(ws.deleteFile(".")) shouldBe "INVALID_ARGUMENT"
  }

  "The sandbox's paths (#870)" should "refuse a mutation of a read-only path, and any operation outside the allowed paths" in {
    Files.createDirectories(root.resolve("docs"))
    Files.createDirectories(root.resolve("src"))
    Files.write(root.resolve("docs/a.md"), "x".getBytes(StandardCharsets.UTF_8))
    val readOnly = workspace(WorkspaceSandboxConfig(readOnlyPaths = List("docs")))
    code(readOnly.writeFile("docs/a.md", "y")) shouldBe "PATH_READ_ONLY"
    code(readOnly.writeFile("docs/b.md", "y", expectedHash = Some("absent"))) shouldBe "PATH_READ_ONLY"
    code(readOnly.deleteFile("docs/a.md")) shouldBe "PATH_READ_ONLY"
    code(readOnly.modifyFile("docs/a.md", List(InsertOperation(afterLine = 0, newContent = "z")))) shouldBe
      "PATH_READ_ONLY"
    readOnly.fileSnapshot("docs/a.md").content shouldBe Some("x")
    readOnly.writeFile("src/a.scala", "ok").success shouldBe true

    val confined = workspace(WorkspaceSandboxConfig(allowedPaths = List("src")))
    confined.fileSnapshot("src/a.scala").content shouldBe Some("ok")
    code(confined.fileSnapshot("docs/a.md")) shouldBe "PATH_NOT_ALLOWED"
    code(confined.readFile("docs/a.md")) shouldBe "PATH_NOT_ALLOWED"
    code(confined.exploreFiles(".")) shouldBe "PATH_NOT_ALLOWED"
    code(confined.writeFile("srcx/a", "y", createDirectories = Some(true))) shouldBe "PATH_NOT_ALLOWED"
    code(confined.executeCommand("ls", workingDirectory = Some("docs"))) shouldBe "PATH_NOT_ALLOWED"
  }

  "A write" should "refuse content over the sandbox's file size limit, writing nothing" in {
    val small = workspace(WorkspaceSandboxConfig(limits = WorkspaceSandboxConfig.DefaultLimits.copy(maxFileSize = 4)))
    code(small.writeFile("a.txt", "12345")) shouldBe "SIZE_LIMIT_EXCEEDED"
    // four characters, eight bytes
    code(small.writeFile("a.txt", "éééé", expectedHash = Some("absent"))) shouldBe "SIZE_LIMIT_EXCEEDED"
    Files.exists(root.resolve("a.txt")) shouldBe false
  }

  "A command's output" should "be kept to the characters asked for, the rest read and discarded" in {
    assume(!isWindowsHost)
    Files.write(root.resolve("long.txt"), ("y" * 50000).getBytes(StandardCharsets.UTF_8)) // one line, no newline
    val response = workspace().executeCommand("cat long.txt", maxOutputChars = Some(10L))
    response.stdout shouldBe "y" * 10
    response.isOutputTruncated shouldBe true
    WebSocketCommandExecutor.outputLimit(Some(10L), 5L) shouldBe 5L
    WebSocketCommandExecutor.outputLimit(Some(-1L), 5L) shouldBe 0L
    WebSocketCommandExecutor.outputLimit(None, 5L) shouldBe 5L
  }
}
