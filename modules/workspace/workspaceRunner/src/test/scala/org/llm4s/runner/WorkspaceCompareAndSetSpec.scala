package org.llm4s.runner

import org.llm4s.shared._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

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

  it should "walk a link to a directory, used as the start of a walk, as the directory, under the link's name" in {
    assume(!isWindowsHost)
    Files.createDirectories(root.resolve("real/sub"))
    Files.write(root.resolve("real/sub/a.txt"), "needle".getBytes(StandardCharsets.UTF_8))
    Files.createSymbolicLink(root.resolve("link"), root.resolve("real"))
    val ws = workspace()
    ws.exploreFiles("link", recursive = Some(true)).files.map(_.path.replace('\\', '/')) should contain(
      "link/sub/a.txt"
    )
    ws.searchFiles(List("link"), "needle", "literal", recursive = Some(true)).matches.map(_.path) should not be empty
  }

  it should "tell a link to nothing from nothing, so it can be deleted, or written through" in {
    assume(!isWindowsHost)
    Files.createSymbolicLink(root.resolve("gone"), root.resolve("missing-target"))
    Files.createSymbolicLink(root.resolve("new"), root.resolve("new-target"))
    val ws = workspace()
    ws.fileSnapshot("gone").kind shouldBe "dangling"
    ws.fileSnapshot("gone").hash shouldBe
      FileSnapshotResponse.Kind.danglingHash(root.resolve("missing-target").toString)
    // a delete compares the link as itself
    ws.deleteFile("gone", Some(ws.fileSnapshot("gone", followLinks = false).hash)).wasDirectory shouldBe false
    Files.exists(root.resolve("gone"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
    // a write through a link to nothing compares where it points: retargeted, it is refused
    val planned = ws.fileSnapshot("new").hash
    Files.delete(root.resolve("new"))
    Files.createSymbolicLink(root.resolve("new"), root.resolve("other-target"))
    code(ws.writeFile("new", "x", expectedHash = Some(planned))) shouldBe "HASH_MISMATCH"
    Files.exists(root.resolve("other-target")) shouldBe false
    Files.delete(root.resolve("new"))
    Files.createSymbolicLink(root.resolve("new"), root.resolve("new-target"))
    ws.writeFile("new", "x", expectedHash = Some(planned)).success shouldBe true
    Files.isSymbolicLink(root.resolve("new")) shouldBe true
    ws.fileSnapshot("new-target").content shouldBe Some("x")
    // creating directories creates the link target's, where the write lands
    Files.createSymbolicLink(root.resolve("deeplink"), root.resolve("made/for/file"))
    ws.writeFile("deeplink", "y", createDirectories = Some(true)).success shouldBe true
    ws.fileSnapshot("made/for/file").content shouldBe Some("y")
  }

  it should "delete the entry the path physically names, compared where it is" in {
    assume(!isWindowsHost)
    Files.createDirectories(root.resolve("one/x"))
    Files.createDirectories(root.resolve("two/x"))
    Files.createSymbolicLink(root.resolve("alias"), root.resolve("one"))
    val ws = workspace()
    ws.deleteFile("alias/x", Some(ws.fileSnapshot("alias/x", followLinks = false).hash)).wasDirectory shouldBe true
    Files.exists(root.resolve("one/x")) shouldBe false
    Files.exists(root.resolve("two/x")) shouldBe true
    Files.isSymbolicLink(root.resolve("alias")) shouldBe true
  }

  it should "describe a link as itself when asked, so a link to a big file or a FIFO is deleted without touching it" in {
    assume(!isWindowsHost)
    Files.write(root.resolve("big"), ("x" * 20).getBytes(StandardCharsets.UTF_8))
    Files.createSymbolicLink(root.resolve("to-big"), root.resolve("big"))
    val small = workspace(WorkspaceSandboxConfig(limits = WorkspaceSandboxConfig.DefaultLimits.copy(maxFileSize = 10)))
    val link  = small.fileSnapshot("to-big", includeContent = false, followLinks = false)
    link.kind shouldBe "link"
    link.hash shouldBe FileSnapshotResponse.Kind.linkHash(root.resolve("big").toString)
    small.fileSnapshot("to-big", includeContent = false).hash shouldBe "oversized"
    code(small.deleteFile("to-big", Some(hashOf("other")))) shouldBe "HASH_MISMATCH"
    small.deleteFile("to-big", Some(link.hash)).wasDirectory shouldBe false
    Files.exists(root.resolve("to-big"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
    Files.size(root.resolve("big")) shouldBe 20L
  }

  it should "delete a link whose target is outside the workspace or the allowed paths, never following it" in {
    assume(!isWindowsHost)
    val outside = Files.createTempDirectory("workspace-outside-target")
    Files.write(outside.resolve("secret"), "s".getBytes(StandardCharsets.UTF_8))
    Files.createDirectories(root.resolve("src"))
    Files.createSymbolicLink(root.resolve("src/out"), outside.resolve("secret"))
    val confined = workspace(WorkspaceSandboxConfig(allowedPaths = List("src")))
    code(confined.fileSnapshot("src/out")) shouldBe "PATH_ESCAPE_ATTEMPT"
    val link = confined.fileSnapshot("src/out", includeContent = false, followLinks = false)
    link.kind shouldBe "link"
    confined.deleteFile("src/out", Some(link.hash)).wasDirectory shouldBe false
    Files.exists(root.resolve("src/out"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
    Files.exists(outside.resolve("secret")) shouldBe true
    // a link the allowed paths name exactly is its own entry: snapshotted as itself and deleted
    Files.createSymbolicLink(root.resolve("only-link"), outside.resolve("secret"))
    val exact = workspace(WorkspaceSandboxConfig(allowedPaths = List("only-link")))
    val entry = exact.fileSnapshot("only-link", includeContent = false, followLinks = false)
    exact.deleteFile("only-link", Some(entry.hash)).wasDirectory shouldBe false
    Files.delete(outside.resolve("secret"))
    Files.delete(outside)
  }

  it should "refuse a FIFO without opening it, as every file operation does" in {
    assume(!isWindowsHost)
    val fifo = root.resolve("pipe")
    assume(new ProcessBuilder("mkfifo", fifo.toString).start().waitFor() == 0)
    val ws = workspace()
    code(ws.fileSnapshot("pipe")) shouldBe "NOT_A_FILE"
    code(ws.fileSnapshot("pipe", includeContent = false)) shouldBe "NOT_A_FILE"
    code(ws.readFile("pipe")) shouldBe "NOT_A_FILE"
    code(ws.searchFiles(List("pipe"), "x", "literal")) shouldBe "NOT_A_FILE"
    // and a listing never offers it as a file
    ws.exploreFiles(".").files.map(_.path) should not contain "pipe"
    ws.exploreFiles(".", recursive = Some(true)).files.map(_.path) should not contain "pipe"
    code(ws.writeFile("pipe", "x")) shouldBe "NOT_A_FILE"
    code(ws.writeFile("pipe", "x", expectedHash = Some("absent"))) shouldBe "NOT_A_FILE"
    code(ws.modifyFile("pipe", List(InsertOperation(afterLine = 0, newContent = "z")))) shouldBe "NOT_A_FILE"
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
    big.hash shouldBe "oversized"
  }

  "A walk" should "leave out the default exclusions at the workspace root as well as beneath it" in {
    Files.createDirectories(root.resolve(".git"))
    Files.write(root.resolve(".git/config"), "x".getBytes(StandardCharsets.UTF_8))
    Files.createDirectories(root.resolve("node_modules/pkg"))
    Files.write(root.resolve("node_modules/pkg/index.js"), "x".getBytes(StandardCharsets.UTF_8))
    Files.createDirectories(root.resolve("src/.git"))
    Files.write(root.resolve("src/.git/config"), "x".getBytes(StandardCharsets.UTF_8))
    Files.write(root.resolve("src/a.scala"), "x".getBytes(StandardCharsets.UTF_8))
    val listed = workspace().exploreFiles(".", recursive = Some(true), maxDepth = Some(10)).files.map(_.path)
    listed should contain("src/a.scala")
    listed.filter(p => p.contains(".git") || p.contains("node_modules")) shouldBe empty
  }

  "A compare-and-set write" should "write through a link to the file it names, keeping the link" in {
    assume(!isWindowsHost)
    Files.write(root.resolve("real.txt"), "one".getBytes(StandardCharsets.UTF_8))
    Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("real.txt"))
    val ws = workspace()
    ws.fileSnapshot("link.txt").hash shouldBe hashOf("one")
    ws.writeFile("link.txt", "two", expectedHash = Some(hashOf("one")))
    Files.isSymbolicLink(root.resolve("link.txt")) shouldBe true
    ws.fileSnapshot("real.txt").content shouldBe Some("two")
  }

  it should "never run beside a command this runner started, and be refused if the command outlasts its wait" in {
    assume(!isWindowsHost)
    Files.write(root.resolve("follow.txt"), "x\n".getBytes(StandardCharsets.UTF_8))
    val ws = new WorkspaceAgentInterfaceImpl(
      root.toString,
      isWindowsHost,
      Some(WorkspaceSandboxConfig()),
      gateWait = scala.concurrent.duration.FiniteDuration(200, java.util.concurrent.TimeUnit.MILLISECONDS)
    )
    // `tail -f` runs until its 3 s timeout kills it
    val running = new Thread(() =>
      Try(
        ws.executeCommand(
          "tail -f follow.txt",
          timeout = Some(
            scala.concurrent.duration.FiniteDuration(3, java.util.concurrent.TimeUnit.SECONDS)
          )
        )
      ): Unit
    )
    running.start()
    Thread.sleep(500)
    code(ws.writeFile("cas.txt", "x", expectedHash = Some("absent"))) shouldBe "WORKSPACE_BUSY"
    Files.exists(root.resolve("cas.txt")) shouldBe false
    running.join()
    ws.writeFile("cas.txt", "x", expectedHash = Some("absent")).success shouldBe true
  }

  it should "give a new file the mode an ordinary write gives it" in {
    assume(!isWindowsHost)
    val ws = workspace()
    ws.writeFile("plain.txt", "x")
    ws.writeFile("cas-new.txt", "x", expectedHash = Some("absent"))
    Files.getPosixFilePermissions(root.resolve("cas-new.txt")) shouldBe
      Files.getPosixFilePermissions(root.resolve("plain.txt"))
  }

  it should "be refused, changing nothing, once its client would have given up on it" in {
    Files.write(root.resolve("late.txt"), "one".getBytes(StandardCharsets.UTF_8))
    val zero = scala.concurrent.duration.Duration.Zero
    // no time at all to commit in: every change is past its deadline when it would commit
    val late = new WorkspaceAgentInterfaceImpl(
      root.toString,
      isWindowsHost,
      Some(WorkspaceSandboxConfig()),
      gateWait = zero,
      commitAfterGate = zero
    )

    code(late.writeFile("late.txt", "two", expectedHash = Some(hashOf("one")))) shouldBe "WORKSPACE_BUSY"
    code(late.deleteFile("late.txt", Some(hashOf("one")))) shouldBe "WORKSPACE_BUSY"
    // every kind of change is held to it: an append, a create and a line edit too
    code(late.writeFile("late.txt", "more", mode = Some("append"))) shouldBe "WORKSPACE_BUSY"
    code(late.writeFile("new.txt", "x", mode = Some("create"))) shouldBe "WORKSPACE_BUSY"
    code(late.modifyFile("late.txt", List(InsertOperation(afterLine = 0, newContent = "z")))) shouldBe "WORKSPACE_BUSY"
    Files.exists(root.resolve("new.txt")) shouldBe false
    // and the directories it created for the write are removed with it
    code(late.writeFile("deep/er/new.txt", "x", createDirectories = Some(true))) shouldBe "WORKSPACE_BUSY"
    Files.exists(root.resolve("deep")) shouldBe false
    workspace().fileSnapshot("late.txt").content shouldBe Some("one")
  }

  it should "refuse to compare with a file over the size limit, which is never read" in {
    Files.write(root.resolve("big"), ("x" * 20).getBytes(StandardCharsets.UTF_8))
    val small = workspace(WorkspaceSandboxConfig(limits = WorkspaceSandboxConfig.DefaultLimits.copy(maxFileSize = 10)))
    small.fileSnapshot("big", includeContent = false).hash shouldBe "oversized"
    code(small.writeFile("big", "y", expectedHash = Some("oversized"))) shouldBe "SIZE_LIMIT_EXCEEDED"
    code(small.deleteFile("big", Some("oversized"))) shouldBe "SIZE_LIMIT_EXCEEDED"
    Files.size(root.resolve("big")) shouldBe 20L
  }

  it should "write when the hash matches, creating the file and its parents" in {
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
    // and its owner and group: copied from the file onto the replacement before it is moved
    val attributes = classOf[java.nio.file.attribute.PosixFileAttributes]
    val before     = Files.readAttributes(file, attributes)
    workspace().writeFile("run.sh", "echo again", expectedHash = Some(hashOf("echo hi")))
    val after = Files.readAttributes(file, attributes)
    (after.owner(), after.group()) shouldBe ((before.owner(), before.group()))
    // the copy itself: onto a file of another owner or group it sets them where the runner may, and never throws
    val other = Files.createTempFile(root, "other", ".txt")
    WorkspaceAgentInterfaceImpl.copyOwnership(file, other)
    Files.readAttributes(other, attributes).group() shouldBe before.group()
  }

  it should "copy an ACL only with getfacl and setfacl from trusted directories, never ones planted in the workspace" in {
    assume(!isWindowsHost)
    Seq("getfacl", "setfacl").foreach { tool =>
      val planted = Files.write(root.resolve(tool), "#!/bin/sh\ntouch pwned\n".getBytes(StandardCharsets.UTF_8))
      planted.toFile.setExecutable(true): Unit
    }
    // the workspace on PATH, as an entry or relatively: neither is ever used
    Seq(root.toString, ".", "").foreach { entry =>
      val search = ProgramSearch(windows = false, Nil, Some(entry), Some(root), None)
      WorkspaceAgentInterfaceImpl.aclTools(search, Seq(root, root.toRealPath())) shouldBe None
    }
  }

  it should "keep the file's user extended attributes" in {
    import java.nio.file.attribute.UserDefinedFileAttributeView
    val file = root.resolve("tagged.txt")
    Files.write(file, "one".getBytes(StandardCharsets.UTF_8))
    val view = Option(Files.getFileAttributeView(file, classOf[UserDefinedFileAttributeView]))
    assume(view.exists(v => Try(v.write("llm4s.tag", StandardCharsets.UTF_8.encode("kept"))).isSuccess))
    workspace().writeFile("tagged.txt", "two", expectedHash = Some(hashOf("one")))
    val after  = Files.getFileAttributeView(file, classOf[UserDefinedFileAttributeView])
    val buffer = java.nio.ByteBuffer.allocate(after.size("llm4s.tag"))
    after.read("llm4s.tag", buffer)
    buffer.flip()
    StandardCharsets.UTF_8.decode(buffer).toString shouldBe "kept"
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
    // the default directory, the root, is held to the same policy
    code(confined.executeCommand("ls")) shouldBe "PATH_NOT_ALLOWED"
  }

  they should "be checked on what a path names through a link, not only on its text" in {
    assume(!isWindowsHost)
    Files.createDirectories(root.resolve("src"))
    Files.createDirectories(root.resolve("private"))
    Files.createDirectories(root.resolve("docs"))
    Files.write(root.resolve("private/secret"), "s".getBytes(StandardCharsets.UTF_8))
    Files.createSymbolicLink(root.resolve("src/link"), root.resolve("private"))
    Files.createSymbolicLink(root.resolve("src/docs"), root.resolve("docs"))
    val outside = Files.createTempDirectory("workspace-outside")
    Files.createSymbolicLink(root.resolve("out"), outside)

    val confined = workspace(WorkspaceSandboxConfig(allowedPaths = List("src")))
    code(confined.fileSnapshot("src/link/secret")) shouldBe "PATH_NOT_ALLOWED"
    code(confined.writeFile("src/link/new", "x", createDirectories = Some(true))) shouldBe "PATH_NOT_ALLOWED"
    Files.exists(root.resolve("private/new")) shouldBe false

    val readOnly = workspace(WorkspaceSandboxConfig(readOnlyPaths = List("docs")))
    code(readOnly.writeFile("src/docs/a.md", "x")) shouldBe "PATH_READ_ONLY"
    // the link is an entry outside the read-only path: deleting it is allowed, and leaves the read-only directory
    readOnly.deleteFile("src/docs").wasDirectory shouldBe false
    Files.isDirectory(root.resolve("docs")) shouldBe true
    // an entry reached through an aliased directory is judged where it really is: docs/x, read-only
    Files.createDirectories(root.resolve("public"))
    Files.createSymbolicLink(root.resolve("alias"), root.resolve("docs"))
    Files.createSymbolicLink(root.resolve("docs/x"), root.resolve("public"))
    code(readOnly.deleteFile("alias/x")) shouldBe "PATH_READ_ONLY"
    Files.isSymbolicLink(root.resolve("docs/x")) shouldBe true

    code(workspace().writeFile("out/a", "x")) shouldBe "PATH_ESCAPE_ATTEMPT"
    code(workspace().fileSnapshot("out")) shouldBe "PATH_ESCAPE_ATTEMPT"
    Files.list(outside).count() shouldBe 0L
    // a dangling link: the write would create its target, outside the workspace or the allowed paths
    Files.createSymbolicLink(root.resolve("dangling"), outside.resolve("new-file"))
    Files.createSymbolicLink(root.resolve("src/dangling"), root.resolve("private/new-file"))
    code(workspace().writeFile("dangling", "x")) shouldBe "PATH_ESCAPE_ATTEMPT"
    code(workspace().writeFile("dangling", "x", expectedHash = Some("absent"))) shouldBe "PATH_ESCAPE_ATTEMPT"
    code(confined.writeFile("src/dangling", "x")) shouldBe "PATH_NOT_ALLOWED"
    Files.exists(outside.resolve("new-file")) shouldBe false
    // a walk leaves out a link that leads outside the allowed paths, before it follows it
    Files.write(root.resolve("src/ok.txt"), "secret too".getBytes(StandardCharsets.UTF_8))
    confined.exploreFiles("src", returnMetadata = Some(true)).files.map(_.path) should contain("src/ok.txt")
    confined.exploreFiles("src").files.map(_.path) should not contain "src/link"
    confined.exploreFiles("src", recursive = Some(true)).files.map(_.path) should not contain "src/link"
    confined
      .searchFiles(List("src"), "s", "literal", recursive = Some(true))
      .matches
      .map(_.path)
      .exists(_.contains("link")) shouldBe false
    Files.exists(root.resolve("private/new-file")) shouldBe false
    Files.delete(root.resolve("dangling"))
    Files.delete(root.resolve("out"))
    Files.delete(outside)
  }

  "A write" should "refuse content over the sandbox's file size limit, writing nothing" in {
    val small = workspace(WorkspaceSandboxConfig(limits = WorkspaceSandboxConfig.DefaultLimits.copy(maxFileSize = 4)))
    code(small.writeFile("a.txt", "12345")) shouldBe "SIZE_LIMIT_EXCEEDED"
    // four characters, eight bytes
    code(small.writeFile("a.txt", "éééé", expectedHash = Some("absent"))) shouldBe "SIZE_LIMIT_EXCEEDED"
    Files.exists(root.resolve("a.txt")) shouldBe false
    // more UTF-8 bytes than the limit in no more characters than it: refused before any directory is created
    code(small.writeFile("new/dir/a.txt", "€€", createDirectories = Some(true))) shouldBe "SIZE_LIMIT_EXCEEDED"
    Files.exists(root.resolve("new")) shouldBe false
  }

  it should "bound the file it leaves: an append or a line edit past the limit is refused" in {
    val small = workspace(WorkspaceSandboxConfig(limits = WorkspaceSandboxConfig.DefaultLimits.copy(maxFileSize = 4)))
    small.writeFile("a.txt", "abcd").success shouldBe true
    code(small.writeFile("a.txt", "e", mode = Some("append"))) shouldBe "SIZE_LIMIT_EXCEEDED"
    code(small.modifyFile("a.txt", List(InsertOperation(afterLine = 1, newContent = "more")))) shouldBe
      "SIZE_LIMIT_EXCEEDED"
    small.fileSnapshot("a.txt").content shouldBe Some("abcd")
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
