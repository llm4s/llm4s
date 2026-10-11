package org.llm4s.runner

import org.llm4s.shared._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

class WorkspaceAgentInterfaceImplTest extends AnyFlatSpec with Matchers with org.scalatest.BeforeAndAfterAll {

  // Create a temporary workspace for testing
  val tempDir               = Files.createTempDirectory("workspace-test")
  val workspacePath         = tempDir.toString
  private val isWindowsHost = System.getProperty("os.name").startsWith("Windows")
  val interface             = new WorkspaceAgentInterfaceImpl(workspacePath, isWindowsHost)

  /** Creates a fresh interface with the given sandbox config for isolation. */
  private def newInterface(config: WorkspaceSandboxConfig) =
    new WorkspaceAgentInterfaceImpl(workspacePath, isWindowsHost, Some(config))

  // #1728: the command's standard input is the null device, so a program that reads it gets end-of-file at once.
  // Before the fix stdin was an open pipe nobody wrote to or closed, and each of these ran until the timeout, which
  // `executeCommand` reports as a TIMEOUT failure. That failure is the signal, so there is no wall-clock bound here
  // to trip on a slow runner.
  private val stdinTimeout = 30.seconds

  private def runReadingStdin(command: String): ExecuteCommandResponse = {
    val stdinInterface = newInterface(WorkspaceSandboxConfig(defaultCommandTimeout = stdinTimeout))
    try stdinInterface.executeCommand(command)
    catch {
      case e: WorkspaceAgentException =>
        fail(s"'$command' failed with ${e.code}: ${e.error}")
    }
  }

  /**
   * A regex test asserts what the search or replace did, which tells whether the dangerous regex ran. A regression
   * that does run it unbounded would hang instead, so the body runs on its own daemon thread and fails after a
   * generous backstop rather than blocking the suite. The backstop is not the assertion: a healthy run takes well
   * under a second, and catastrophic backtracking on these inputs takes hours.
   */
  private val regexBackstop = 60.seconds

  private def withinBackstop[A](what: String)(body: => A): A = {
    val outcome = new AtomicReference[Try[A]]()
    val thread  = new Thread(() => outcome.set(Try(body)), s"regex-backstop: $what")
    thread.setDaemon(true)
    thread.start()
    thread.join(regexBackstop.toMillis)
    Option(outcome.get()).getOrElse(fail(s"$what did not return within the $regexBackstop backstop")).get
  }

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

  it should "fallback to literal matching for unsafe regex in searchFiles" in {
    interface.writeFile("redos-search.txt", "payload ((a+)+)+b marker")

    val response = interface.searchFiles(
      paths = List("."),
      query = "((a+)+)+b",
      searchType = "regex",
      recursive = Some(true)
    )

    response.matches.exists(_.path == "redos-search.txt") shouldBe true
  }

  it should "fallback to literal matching for overlapping alternation regex" in {
    interface.writeFile("redos-alt-search.txt", "token (a|aa)+X token")

    val response = interface.searchFiles(
      paths = List("."),
      query = "(a|aa)+X",
      searchType = "regex",
      recursive = Some(true)
    )

    response.matches.exists(_.path == "redos-alt-search.txt") shouldBe true
  }

  it should "search a blocklisted regex as a literal, never running the regex" in {
    // ((a+)+)+b is rejected by the shape blocklist, so the search degrades to a literal substring match and never
    // runs the dangerous regex. Line 1 tells the two paths apart: the regex matches "aaab", the literal does not.
    // Line 2 is what would make the regex hang without the step budget. Only line 3 holds the literal text.
    WorkspaceRegexSafetyManager.safeCompile("((a+)+)+b").isLeft shouldBe true
    interface.writeFile("redos-time.txt", Seq("aaab", "a" * 28 + "X", "literal ((a+)+)+b here").mkString("\n"))

    val response = withinBackstop("blocklisted regex search") {
      interface.searchFiles(
        paths = List("redos-time.txt"),
        query = "((a+)+)+b",
        searchType = "regex",
        recursive = Some(false)
      )
    }
    response.matches.map(_.line) shouldBe List(3)
  }

  it should "bound regex search for catastrophic patterns the blocklist misses" in {
    // (.*a){25}b is genuinely catastrophic in the JDK engine but slips past the shape blocklist (the group is
    // quantified with {25}, not +/*), so it compiles as a real regex. The per-line step budget must abort the match
    // and fall back to a literal match for that line. The regex never matches this line (no 'a' is followed by 'b'),
    // the literal does, so a match proves the budget aborted it; without the budget the search never returns.
    WorkspaceRegexSafetyManager.safeCompile("(.*a){25}b").isRight shouldBe true
    interface.writeFile("redos-brace-search.txt", "a" * 40 + " (.*a){25}b")

    val response = withinBackstop("catastrophic regex search") {
      interface.searchFiles(
        paths = List("redos-brace-search.txt"),
        query = "(.*a){25}b",
        searchType = "regex",
        recursive = Some(false)
      )
    }
    response.matches.map(_.line) shouldBe List(1)
  }

  it should "bound regexReplace for catastrophic patterns the blocklist misses" in {
    // As above: the regex never matches this line, so a replacement proves the step budget aborted it and the
    // literal fallback ran.
    interface.writeFile("redos-brace-replace.txt", "a" * 40 + " (.*a){25}b")

    val op = RegexReplaceOperation(
      pattern = "(.*a){25}b",
      replacement = "SAFE",
      flags = Some("g")
    )

    val result = withinBackstop("catastrophic regexReplace")(interface.modifyFile("redos-brace-replace.txt", List(op)))
    result.success shouldBe true
    interface.readFile("redos-brace-replace.txt").content.trim shouldBe "a" * 40 + " SAFE"
  }

  it should "fallback to literal replacement for unsafe regexReplace operations" in {
    interface.writeFile("redos-replace.txt", "prefix ((a+)+)+b suffix ((a+)+)+b")

    val op = RegexReplaceOperation(
      pattern = "((a+)+)+b",
      replacement = "SAFE",
      flags = Some("g")
    )

    interface.modifyFile("redos-replace.txt", List(op)).success shouldBe true
    val content = interface.readFile("redos-replace.txt").content
    content should include("prefix SAFE suffix SAFE")
  }

  it should "set isTruncated when result cap is reached" in {
    // prepare a file with multiple matches
    val bigFile = tempDir.resolve("big.txt")
    val content = List.fill(5)("needle").mkString("\n")
    Files.write(bigFile, content.getBytes(StandardCharsets.UTF_8))

    // create an interface with a very small search limit so we hit truncation
    val tinyLimits = WorkspaceSandboxConfig.DefaultLimits.copy(maxSearchResults = 2)
    val smallInterface =
      new WorkspaceAgentInterfaceImpl(workspacePath, isWindowsHost, Some(WorkspaceSandboxConfig(limits = tinyLimits)))

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
    // Use 'git --version': a real cross-platform executable that is in
    // AllowedExecutables and does not rely on shell built-ins.
    val response = interface.executeCommand("git --version")
    response.exitCode shouldBe 0
    response.stdout should include("git")
  }

  it should "execute built-in echo command on Windows via cmd.exe /c" in {
    // On Windows, echo is a cmd.exe built-in. WorkspaceAgentInterfaceImpl should
    // detect this and prepend "cmd.exe /c" automatically.
    // On Unix, echo is a real executable on PATH and works directly.
    val response = interface.executeCommand("echo hello world")
    response.exitCode shouldBe 0
    response.stdout should include("hello world")
  }

  it should "block shell metacharacters before execution, not merely treat them as literal args" in {
    // Previously this test verified that 'git --version ; echo INJECTED' ran
    // git with ';' and 'echo' as literal args.  With the FORBIDDEN_CHARACTERS
    // layer the command is now rejected outright before any process is
    // launched – a strictly stronger guarantee.
    val ex = the[WorkspaceAgentException] thrownBy
      interface.executeCommand("git --version ; echo INJECTED")
    ex.code shouldBe "FORBIDDEN_CHARACTERS"
    ex.error should include(";")
  }

  it should "reject executeCommand for executables not in the allowlist" in {
    val ex = the[WorkspaceAgentException] thrownBy interface.executeCommand("curl https://evil.example")
    ex.code shouldBe "EXECUTABLE_NOT_ALLOWED"
    ex.error should include("curl")
  }

  it should "reject executeCommand for executables specified with a path separator" in {
    val ex = the[WorkspaceAgentException] thrownBy interface.executeCommand("/usr/bin/ls -la")
    ex.code shouldBe "EXECUTABLE_PATH_NOT_ALLOWED"
    ex.error should include("/usr/bin/ls")
  }

  it should "treat an unclosed double-quote as consuming the rest of the string into the token" in {
    // "git --version" with an unclosed leading quote on the flag:
    // tokenizeCommand("git \"--version") => Seq("git", "--version")
    // git receives "--version" as a literal arg and succeeds normally.
    val response = interface.executeCommand("git \"--version")
    response.exitCode shouldBe 0
    response.stdout should include("git")
  }

  it should "treat an unclosed single-quote as consuming the rest of the string into the token" in {
    // tokenizeCommand("git '--version") => Seq("git", "--version")
    val response = interface.executeCommand("git '--version")
    response.exitCode shouldBe 0
    response.stdout should include("git")
  }

  it should "block echo hello & whoami due to forbidden character '&'" in {
    val ex = the[WorkspaceAgentException] thrownBy interface.executeCommand("echo hello & whoami")
    ex.code shouldBe "FORBIDDEN_CHARACTERS"
    ex.error should include("&")
  }

  it should "block git --version ; echo pwned due to forbidden character ';'" in {
    val ex = the[WorkspaceAgentException] thrownBy interface.executeCommand("git --version ; echo pwned")
    ex.code shouldBe "FORBIDDEN_CHARACTERS"
    ex.error should include(";")
  }

  it should "block commands containing pipe character '|'" in {
    val ex = the[WorkspaceAgentException] thrownBy interface.executeCommand("echo foo | grep foo")
    ex.code shouldBe "FORBIDDEN_CHARACTERS"
    ex.error should include("|")
  }

  it should "block commands containing percent expansion '%'" in {
    val ex = the[WorkspaceAgentException] thrownBy interface.executeCommand("echo %PATH%")
    ex.code shouldBe "FORBIDDEN_CHARACTERS"
    ex.error should include("%")
  }

  it should "reject executeCommand when sandbox has shellAllowed=false" in {
    val ex = the[WorkspaceAgentException] thrownBy
      newInterface(WorkspaceSandboxConfig.LockedDown).executeCommand("echo hello")
    ex.code shouldBe "SHELL_DISABLED"
    ex.error should include("shellAllowed")
  }

  it should "forcibly terminate timed-out commands" in {
    // Use a command that ignores SIGTERM (trap '' TERM) and sleeps
    // The fix should escalate to destroyForcibly after destroy() fails
    if (!isWindowsHost) {
      val shortTimeoutConfig = WorkspaceSandboxConfig(
        defaultCommandTimeout = 1.second,
        allowedCommands = WorkspaceSandboxConfig.ReadOnlyCommands + "sleep"
      )
      val timedInterface = new WorkspaceAgentInterfaceImpl(
        workspacePath,
        isWindowsHost,
        Some(shortTimeoutConfig)
      )

      val ex = the[WorkspaceAgentException] thrownBy {
        timedInterface.executeCommand("sleep 60")
      }
      ex.code shouldBe "TIMEOUT"
      ex.error should include("timed out")
    }
  }

  it should "give a command with no operands end-of-file on stdin instead of hanging (cat)" in {
    assume(!isWindowsHost, "POSIX cat")
    val response = runReadingStdin("cat")
    response.exitCode shouldBe 0
    response.stdout shouldBe empty
  }

  it should "give `cat -` end-of-file on stdin instead of hanging" in {
    assume(!isWindowsHost, "POSIX cat")
    val response = runReadingStdin("cat -")
    response.exitCode shouldBe 0
    response.stdout shouldBe empty
  }

  it should "give sort, uniq and head with no file end-of-file on stdin" in {
    assume(!isWindowsHost, "POSIX sort, uniq and head")
    Seq("sort", "uniq", "head", "tail").foreach { command =>
      val response = runReadingStdin(command)
      withClue(s"$command: ") {
        response.exitCode shouldBe 0
        response.stdout shouldBe empty
      }
    }
  }

  it should "give wc with no file end-of-file on stdin, counting nothing" in {
    assume(!isWindowsHost, "POSIX wc")
    val response = runReadingStdin("wc")
    response.exitCode shouldBe 0
    response.stdout.trim.split("\\s+").toSeq shouldBe Seq("0", "0", "0")
  }

  it should "give grep with no file end-of-file on stdin, finding no match" in {
    assume(!isWindowsHost, "POSIX grep")
    val response = runReadingStdin("grep x")
    response.exitCode shouldBe 1
    response.stdout shouldBe empty
  }

  it should "give Windows sort and findstr with no file end-of-file on stdin" in {
    assume(isWindowsHost, "Windows sort.exe and findstr.exe")
    val sorted = runReadingStdin("sort")
    sorted.exitCode shouldBe 0
    sorted.stdout.trim shouldBe empty
    val found = runReadingStdin("findstr x")
    found.exitCode shouldBe 1
    found.stdout.trim shouldBe empty
  }

  it should "reject destructive commands under a read-only sandbox configuration" in {
    // ReadOnlyCommands excludes write-capable commands like 'rm'. Verify that
    // 'rm test.txt' is blocked at the allowlist check with EXECUTABLE_NOT_ALLOWED.
    val ex = the[WorkspaceAgentException] thrownBy
      newInterface(WorkspaceSandboxConfig(allowedCommands = WorkspaceSandboxConfig.ReadOnlyCommands))
        .executeCommand("rm test.txt")
    ex.code shouldBe "EXECUTABLE_NOT_ALLOWED"
    ex.error should include("rm")
  }

  it should "reject destructive commands under default sandbox policy" in {
    // WorkspaceSandboxConfig() defaults to shellAllowed=true and ReadOnlyCommands.
    val ex = the[WorkspaceAgentException] thrownBy
      newInterface(WorkspaceSandboxConfig())
        .executeCommand("rm test.txt")
    ex.code shouldBe "EXECUTABLE_NOT_ALLOWED"
    ex.error should include("rm")
  }

  it should "allow write commands under a read-write sandbox configuration" in {
    // ReadWriteCommands must pass the allowlist gate for write-capable commands.
    // The command may fail at the OS level but must NOT throw EXECUTABLE_NOT_ALLOWED.
    val cmd =
      if (isWindowsHost) "mkdir test-write-sandbox-check"
      else "rm nonexistent-file-12345.txt"
    try {
      newInterface(WorkspaceSandboxConfig(allowedCommands = WorkspaceSandboxConfig.ReadWriteCommands))
        .executeCommand(cmd)
      succeed
    } catch {
      case e: WorkspaceAgentException if e.code == "EXECUTABLE_NOT_ALLOWED" =>
        fail(s"Write command was incorrectly rejected by the allowlist: ${e.error}")
      case _: WorkspaceAgentException =>
        succeed // EXECUTION_FAILED / TIMEOUT means allowlist check passed
    }
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
