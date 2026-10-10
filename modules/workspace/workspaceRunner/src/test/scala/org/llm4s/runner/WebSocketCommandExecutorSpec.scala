package org.llm4s.runner

import org.llm4s.shared._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.util.Comparator
import java.util.concurrent.{ ConcurrentHashMap, ConcurrentLinkedQueue }
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Promise }
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * The WebSocket protocol's `ExecuteCommandCommand` (#1756): the runner used to hand the raw string to `sh -c`
 * (`cmd.exe /c` on Windows) with the client's environment copied in unfiltered, so none of the direct path's checks
 * applied. These specs drive [[WebSocketCommandExecutor]], which `RunnerMain.handleExecuteCommand` delegates to, with
 * a fake channel that records every message the client would receive.
 */
class WebSocketCommandExecutorSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private val isWindowsHost = System.getProperty("os.name").startsWith("Windows")
  private val root: Path    = Files.createTempDirectory("ws-command-executor")
  Files.write(root.resolve("notes.txt"), "alpha\nbeta\n".getBytes(StandardCharsets.UTF_8))

  override def afterAll(): Unit = {
    Try(Files.walk(root).sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p)))
    super.afterAll()
  }

  private val Permissive = WorkspaceSandboxConfig.Permissive
  private val ReadOnly   = WorkspaceSandboxConfig(allowedCommands = WorkspaceSandboxConfig.ReadOnlyCommands)

  private def interfaceFor(config: WorkspaceSandboxConfig) =
    new WorkspaceAgentInterfaceImpl(root.toString, isWindowsHost, Some(config))

  /** One WebSocket client: the messages it was sent, and its running commands. */
  final private class FakeChannel {
    val messages  = new ConcurrentLinkedQueue[WebSocketMessage]()
    val processes = new ConcurrentHashMap[String, RunningCommand]()
    val completed = Promise[CommandCompletedMessage]()

    def send(message: WebSocketMessage): Unit = {
      messages.add(message)
      message match {
        case done: CommandCompletedMessage => completed.trySuccess(done)
        case _                             => ()
      }
    }

    def all: Seq[WebSocketMessage]          = messages.asScala.toSeq
    def started: Seq[CommandStartedMessage] = all.collect { case m: CommandStartedMessage => m }
    def chunks: Seq[StreamingOutputMessage] = all.collect { case m: StreamingOutputMessage => m }
    def stdout: String                      = chunks.filter(_.outputType == "stdout").map(_.content).mkString
    def errors: Seq[WorkspaceAgentErrorResponse] = all.collect { case ResponseMessage(e: WorkspaceAgentErrorResponse) =>
      e
    }
    def responses: Seq[ExecuteCommandResponse] = all.collect { case ResponseMessage(r: ExecuteCommandResponse) => r }
  }

  private def command(
    text: String,
    environment: Option[Map[String, String]] = None,
    workingDirectory: Option[String] = None,
    timeout: Option[FiniteDuration] = Some(20.seconds)
  ) =
    ExecuteCommandCommand(
      commandId = java.util.UUID.randomUUID().toString,
      command = text,
      workingDirectory = workingDirectory,
      timeout = timeout,
      environment = environment
    )

  /** Run one command over the fake channel and wait for its `CommandCompletedMessage`. */
  private def run(
    cmd: ExecuteCommandCommand,
    config: WorkspaceSandboxConfig = Permissive
  ): (FakeChannel, CommandCompletedMessage) = {
    val channel  = new FakeChannel
    val executor = new WebSocketCommandExecutor(interfaceFor(config), 1024L * 1024L)
    executor.execute(cmd, channel.processes, channel.send)
    val done = Await.result(channel.completed.future, 30.seconds)
    // The final response is sent before CommandCompletedMessage, so everything the client will see is in.
    (channel, done)
  }

  /** The command is refused with `code`, before any process starts, exactly as a direct `executeCommand` is. */
  private def assertRefused(
    cmd: ExecuteCommandCommand,
    code: String,
    config: WorkspaceSandboxConfig = Permissive
  ): Unit = {
    val (channel, done) = run(cmd, config)
    withClue(s"'${cmd.command}' messages ${channel.all}: ") {
      channel.errors.map(_.code) shouldBe Seq(code)
      channel.errors.head.commandId shouldBe cmd.commandId
      channel.started shouldBe empty
      channel.chunks shouldBe empty
      channel.responses shouldBe empty
      done.commandId shouldBe cmd.commandId
      done.exitCode shouldBe 1
    }
    val direct = Try(
      interfaceFor(config).executeCommand(cmd.command, cmd.workingDirectory, cmd.timeout, cmd.environment)
    ).failed.toOption
    withClue(s"direct executeCommand('${cmd.command}'): ") {
      direct.collect { case e: WorkspaceAgentException => e.code } shouldBe Some(code)
    }
  }

  // --- Refusals: each of these ran through `sh -c` before #1756 -------------------------------------------------

  "WebSocketCommandExecutor" should "refuse `;` command chaining instead of running both commands" in {
    // With no shell, `ls;` is one argv token: the name of a program, which is not on the allowlist.
    assertRefused(command("ls; cat /etc/passwd"), "EXECUTABLE_NOT_ALLOWED")
    assertRefused(command("ls ; touch chained.txt"), "FORBIDDEN_CHARACTERS")
    assertRefused(command("echo ok && touch chained.txt"), "FORBIDDEN_CHARACTERS")
    Files.exists(root.resolve("chained.txt")) shouldBe false
  }

  it should "refuse a pipe into a shell" in {
    assertRefused(command("cat notes.txt | sh"), "FORBIDDEN_CHARACTERS")
    assertRefused(command("cat a | sh"), "FORBIDDEN_CHARACTERS")
  }

  it should "refuse command substitution and redirection" in {
    assertRefused(command("echo $(id)"), "FORBIDDEN_CHARACTERS")
    assertRefused(command("echo `id`"), "FORBIDDEN_CHARACTERS")
    assertRefused(command("echo pwned > redirected.txt"), "FORBIDDEN_CHARACTERS")
    Files.exists(root.resolve("redirected.txt")) shouldBe false
  }

  it should "refuse rm under the read-only allowlist" in {
    val canary = Files.createDirectories(root.resolve("canary-dir"))
    assertRefused(command("rm -rf /"), "EXECUTABLE_NOT_ALLOWED", ReadOnly)
    assertRefused(command("rm -rf canary-dir"), "EXECUTABLE_NOT_ALLOWED", ReadOnly)
    Files.isDirectory(canary) shouldBe true
  }

  it should "refuse a program that is not on the allowlist" in {
    assertRefused(command("sh -c id"), "EXECUTABLE_NOT_ALLOWED")
    assertRefused(command("python3 --version"), "EXECUTABLE_NOT_ALLOWED")
    assertRefused(command("curl http://example.com"), "EXECUTABLE_NOT_ALLOWED")
  }

  it should "refuse an executable given as a path" in {
    assertRefused(command("/bin/sh -c id"), "EXECUTABLE_PATH_NOT_ALLOWED")
  }

  it should "refuse an empty command" in {
    assertRefused(command("   "), "EMPTY_COMMAND")
  }

  it should "refuse an option the command policy forbids" in {
    Files.write(root.resolve("keep.txt"), "keep".getBytes(StandardCharsets.UTF_8))
    assertRefused(command("find . -name keep.txt -delete"), "ARGUMENT_NOT_ALLOWED")
    assertRefused(command("git -c core.pager=id log"), "ARGUMENT_NOT_ALLOWED")
    Files.exists(root.resolve("keep.txt")) shouldBe true
  }

  it should "refuse a path argument outside the workspace" in {
    assume(!isWindowsHost, "a POSIX absolute path")
    assertRefused(command("cat /etc/passwd"), "PATH_ESCAPE_ATTEMPT")
    assertRefused(command("ls ../"), "PATH_ESCAPE_ATTEMPT")
  }

  it should "remove or rename a link that points out of the workspace, and refuse the forms that follow it (#1730)" in {
    assume(!isWindowsHost, "POSIX rm and mv; Windows keeps refusing such a link")
    val outside = Files.createTempDirectory("ws-command-executor-outside")
    try {
      Files.write(outside.resolve("secret.txt"), "secret\n".getBytes(StandardCharsets.UTF_8))
      val made = Try {
        Files.createSymbolicLink(root.resolve("ws-outlink"), outside)
        Files.createSymbolicLink(root.resolve("ws-outlink2"), outside)
      }
      assume(made.isSuccess, "cannot create a symbolic link here")

      assertRefused(command("rm ws-outlink/"), "PATH_ESCAPE_ATTEMPT")
      assertRefused(command("rm -r ws-outlink"), "PATH_ESCAPE_ATTEMPT")
      assertRefused(command("mv ws-outlink/ moved"), "PATH_ESCAPE_ATTEMPT")
      assertRefused(command("mv notes.txt ws-outlink"), "PATH_ESCAPE_ATTEMPT")
      Files.isSymbolicLink(root.resolve("ws-outlink")) shouldBe true

      val (removed, removedDone) = run(command("rm ws-outlink"))
      assertRan(removed, removedDone, 0)
      Files.exists(root.resolve("ws-outlink"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false

      val (renamed, renamedDone) = run(command("mv ws-outlink2 ws-renamed"))
      assertRan(renamed, renamedDone, 0)
      Files.readSymbolicLink(root.resolve("ws-renamed")) shouldBe outside
      Files.delete(root.resolve("ws-renamed"))

      Files.isDirectory(outside) shouldBe true
      Files.exists(outside.resolve("secret.txt")) shouldBe true
      Files.exists(root.resolve("notes.txt")) shouldBe true
    } finally {
      Seq("ws-outlink", "ws-outlink2", "ws-renamed").foreach(n => Files.deleteIfExists(root.resolve(n)))
      Files.deleteIfExists(outside.resolve("secret.txt"))
      Files.deleteIfExists(outside)
    }
  }

  it should "refuse a sort input file that follows a -t an earlier option took as its value (#1763)" in {
    assume(!isWindowsHost, "a POSIX absolute path; a Windows runner refuses sort -T and -t as options")
    val outside = Files.createTempFile("ws-command-executor-secret", ".txt")
    try {
      Files.write(outside, "secret\n".getBytes(StandardCharsets.UTF_8))
      assertRefused(command(s"sort -T -t '$outside'"), "PATH_ESCAPE_ATTEMPT", ReadOnly)
      assertRefused(command(s"sort --random-source -t '$outside'"), "PATH_ESCAPE_ATTEMPT", ReadOnly)
      assertRefused(command(s"sort --random-source --field-separator '$outside'"), "PATH_ESCAPE_ATTEMPT", ReadOnly)
    } finally Files.deleteIfExists(outside)
  }

  it should "still sort with a separator given as -t's next argument" in {
    assume(!isWindowsHost, "POSIX sort")
    val (channel, done) = run(command("sort -t / -k1 notes.txt"), ReadOnly)
    assertRan(channel, done, 0)
    channel.stdout shouldBe "alpha\nbeta\n"
  }

  it should "refuse a working directory outside the workspace with the direct path's code" in {
    assertRefused(command("ls", workingDirectory = Some("../")), "PATH_ESCAPE_ATTEMPT")
  }

  it should "refuse an environment variable outside the locale allowlist" in {
    assertRefused(command("ls", environment = Some(Map("LD_PRELOAD" -> "/tmp/evil.so"))), "ENVIRONMENT_NOT_ALLOWED")
    assertRefused(command("ls", environment = Some(Map("PATH" -> "/tmp"))), "ENVIRONMENT_NOT_ALLOWED")
  }

  it should "refuse every command when the sandbox disables shell execution" in {
    assertRefused(command("ls"), "SHELL_DISABLED", WorkspaceSandboxConfig.LockedDown)
    assertRefused(command("echo hi"), "SHELL_DISABLED", WorkspaceSandboxConfig.LockedDown)
  }

  // --- Commands that pass still stream, report exit codes and can be cancelled ----------------------------------

  private def assertRan(channel: FakeChannel, done: CommandCompletedMessage, exitCode: Int): ExecuteCommandResponse = {
    withClue(s"messages ${channel.all}: ") {
      channel.errors shouldBe empty
      channel.started.map(_.commandId) shouldBe Seq(done.commandId)
      channel.chunks.filter(_.isComplete).map(_.outputType).toSet shouldBe Set("stdout", "stderr")
      channel.responses should have size 1
      done.exitCode shouldBe exitCode
      channel.responses.head.exitCode shouldBe exitCode
    }
    channel.responses.head
  }

  it should "stream the output of echo and report its exit code" in {
    assume(!isWindowsHost, "POSIX echo")
    val (channel, done) = run(command("echo hi"))
    val response        = assertRan(channel, done, 0)
    channel.stdout shouldBe "hi\n"
    response.stdout shouldBe "hi\n"
  }

  it should "pass quoted arguments as single argv entries, not shell words" in {
    assume(!isWindowsHost, "POSIX echo")
    val (channel, done) = run(command("echo 'a  b' \"c d\""))
    assertRan(channel, done, 0)
    channel.stdout shouldBe "a  b c d\n"
  }

  it should "stream ls and cat output from the workspace" in {
    assume(!isWindowsHost, "POSIX ls and cat")
    val (lsChannel, lsDone) = run(command("ls"))
    assertRan(lsChannel, lsDone, 0)
    lsChannel.stdout should include("notes.txt")

    val (catChannel, catDone) = run(command("cat notes.txt"))
    assertRan(catChannel, catDone, 0)
    catChannel.stdout shouldBe "alpha\nbeta\n"
  }

  it should "report grep's exit codes for a match and for no match" in {
    assume(!isWindowsHost, "POSIX grep")
    val (hit, hitDone) = run(command("grep beta notes.txt"))
    assertRan(hit, hitDone, 0)
    hit.stdout shouldBe "beta\n"

    val (miss, missDone) = run(command("grep zzz notes.txt"))
    assertRan(miss, missDone, 1)
    miss.stdout shouldBe empty
  }

  it should "run in a working directory inside the workspace and accept a locale variable" in {
    assume(!isWindowsHost, "POSIX ls")
    Files.createDirectories(root.resolve("sub"))
    Files.write(root.resolve("sub").resolve("inner.txt"), "x".getBytes(StandardCharsets.UTF_8))
    val (channel, done) = run(command("ls", workingDirectory = Some("sub"), environment = Some(Map("LANG" -> "C"))))
    assertRan(channel, done, 0)
    channel.stdout.trim shouldBe "inner.txt"
  }

  it should "give the command the null device as stdin, so cat with no operands ends at once" in {
    assume(!isWindowsHost, "POSIX cat")
    // With an open stdin pipe, cat would run until the timeout and report exit -1; exit 0 is the signal.
    val (channel, done) = run(command("cat", timeout = Some(20.seconds)))
    assertRan(channel, done, 0)
    channel.stdout shouldBe empty
  }

  it should "apply the sandbox's default timeout when the client sends none" in {
    assume(!isWindowsHost, "POSIX tail")
    val config          = Permissive.copy(defaultCommandTimeout = 1.second)
    val started         = System.nanoTime()
    val (channel, done) = run(command("tail -f notes.txt", timeout = None), config)
    val elapsed         = (System.nanoTime() - started).nanos
    done.exitCode shouldBe -1
    channel.responses.map(_.exitCode) shouldBe Seq(-1)
    elapsed should be < 15.seconds
  }

  it should "cancel a running command and acknowledge it as exit 143" in {
    assume(!isWindowsHost, "POSIX tail")
    val channel  = new FakeChannel
    val executor = new WebSocketCommandExecutor(interfaceFor(Permissive), 1024L * 1024L)
    val cmd      = command("tail -f notes.txt", timeout = Some(60.seconds))
    executor.execute(cmd, channel.processes, channel.send)

    val deadline = System.nanoTime() + 30.seconds.toNanos
    while (!channel.processes.containsKey(cmd.commandId) && System.nanoTime() < deadline) Thread.sleep(20)
    withClue(s"messages ${channel.all}: ") {
      channel.processes.containsKey(cmd.commandId) shouldBe true
    }
    val process = channel.processes.get(cmd.commandId).process

    executor.cancel(cmd.commandId, Some(channel.processes), channel.send)
    // A cancellation that did not stop it would end at the 60 s timeout as exit -1, after this wait gives up;
    // exit 143 is the signal, so the wait is only a backstop.
    val done = Await.result(channel.completed.future, 30.seconds)
    done.exitCode shouldBe 143
    process.isAlive shouldBe false
  }

  it should "answer a cancellation for an unknown command with UNKNOWN_COMMAND_ID" in {
    val channel  = new FakeChannel
    val executor = new WebSocketCommandExecutor(interfaceFor(Permissive), 1024L * 1024L)
    executor.cancel("no-such-command", Some(channel.processes), channel.send)
    executor.cancel("no-such-command", None, channel.send)
    channel.all shouldBe Seq.fill(2)(
      ErrorMessage("No running command found for id no-such-command", "UNKNOWN_COMMAND_ID", Some("no-such-command"))
    )
  }
}
