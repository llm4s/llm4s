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
 * The command strings that the `@Workspace` integration suites and the workspace samples send to a runner container,
 * run here through the runner's own WebSocket executor with the sandbox config the container uses.
 *
 * Since #1756 a WebSocket command is one program and its arguments, checked by the allowlist and the command policy,
 * never shell text. The `@Workspace` tier needs Docker and a built image and runs only on pushes to main, so a command
 * a suite sends that the runner refuses, or that behaves differently from what the suite asserts, would first fail
 * there. This spec runs each of those exact strings on every PR and asserts what the suite asserts:
 *
 *   - `WorkspaceAgentIntegrationSpec` (modules/it): through `WorkspaceTools`' `execute_command`, which sends the
 *     working directory `/workspace` (the workspace root) and the tool's `timeout` in seconds;
 *   - `ContainerisedWorkspaceTest` (modules/it): `ContainerisedWorkspace.executeCommand` with no working directory;
 *   - `ContainerisedWorkspaceDemo`, `LockedDownSandboxDemo` (workspaceSamples) and `CodeGenExample` (workspaceClient).
 *
 * The container runs `RunnerMain` with no `WORKSPACE_SANDBOX_PROFILE`, so `WorkspaceAgentInterfaceImpl` gets no
 * sandbox config and applies `Permissive`; that is what [[containerDefault]] builds. Keep this spec in step with
 * those suites: change a command there, change it here.
 */
class WorkspaceCommandsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private val isWindowsHost = System.getProperty("os.name").startsWith("Windows")
  private val root: Path    = Files.createTempDirectory("workspace-commands")

  override def afterAll(): Unit = {
    Try(Files.walk(root).sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p)))
    super.afterAll()
  }

  /** What `RunnerMain` builds in the container when `WORKSPACE_SANDBOX_PROFILE` is unset. */
  private def containerDefault = new WorkspaceAgentInterfaceImpl(root.toString, isWindowsHost, None)

  private def write(name: String, content: String): Unit =
    Files.write(root.resolve(name), content.getBytes(StandardCharsets.UTF_8))

  /** `WorkspaceTools.defaultExecuteHandler`'s working directory: `/workspace`, the container's workspace root. */
  private def toolWorkingDirectory: Option[String] = Some(root.toString)

  final private class Channel {
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

    def all: Seq[WebSocketMessage] = messages.asScala.toSeq
    def stdoutChunks: String =
      all.collect { case m: StreamingOutputMessage if m.outputType == "stdout" => m.content }.mkString
    def errors: Seq[WorkspaceAgentErrorResponse] = all.collect { case ResponseMessage(e: WorkspaceAgentErrorResponse) =>
      e
    }
    def responses: Seq[ExecuteCommandResponse] = all.collect { case ResponseMessage(r: ExecuteCommandResponse) => r }
  }

  final private case class Outcome(channel: Channel, response: ExecuteCommandResponse, elapsed: FiniteDuration)

  /** Send `command` as the client does and wait for the final response; the command must have been accepted. */
  private def run(
    command: String,
    workingDirectory: Option[String] = None,
    timeout: Option[FiniteDuration] = None,
    workspace: WorkspaceAgentInterfaceImpl = containerDefault
  ): Outcome = {
    assume(!isWindowsHost, "the workspace-runner container is Linux")
    val channel = new Channel
    val cmd = ExecuteCommandCommand(
      commandId = java.util.UUID.randomUUID().toString,
      command = command,
      workingDirectory = workingDirectory,
      timeout = timeout,
      environment = None
    )
    val started = System.nanoTime()
    new WebSocketCommandExecutor(workspace, 1024L * 1024L).execute(cmd, channel.processes, channel.send)
    val done    = Await.result(channel.completed.future, 60.seconds)
    val elapsed = (System.nanoTime() - started).nanos
    withClue(s"'$command' messages ${channel.all}: ") {
      channel.errors shouldBe empty
      channel.responses should have size 1
      done.exitCode shouldBe channel.responses.head.exitCode
    }
    Outcome(channel, channel.responses.head, elapsed)
  }

  /** The code `command` is refused with, over the WebSocket path, before any process starts. */
  private def refusedCode(
    command: String,
    environment: Option[Map[String, String]] = None,
    workspace: WorkspaceAgentInterfaceImpl = containerDefault
  ): String = {
    assume(!isWindowsHost, "the workspace-runner container is Linux")
    val channel = new Channel
    val cmd = ExecuteCommandCommand(java.util.UUID.randomUUID().toString, command, None, Some(5.seconds), environment)
    new WebSocketCommandExecutor(workspace, 1024L * 1024L).execute(cmd, channel.processes, channel.send)
    Await.result(channel.completed.future, 30.seconds).exitCode shouldBe 1
    withClue(s"'$command' messages ${channel.all}: ") {
      channel.responses shouldBe empty
      channel.errors should have size 1
    }
    channel.errors.head.code
  }

  // --- WorkspaceAgentIntegrationSpec ---------------------------------------------------------------------------

  "The commands WorkspaceAgentIntegrationSpec sends" should "echo to stdout with exit code 0" in {
    val out = run("echo hello_from_workspace", toolWorkingDirectory)
    out.response.exitCode shouldBe 0
    out.response.stdout.trim shouldBe "hello_from_workspace"
  }

  it should "report grep's exit code 1 for a file with no match" in {
    write("exit_code.txt", "alpha\n")
    run("grep zzz exit_code.txt", toolWorkingDirectory).response.exitCode shouldBe 1
  }

  it should "capture a missing file's complaint on stderr and the present file on stdout" in {
    write("out_marker.txt", "out_marker\n")
    val r = run("cat out_marker.txt err_marker.txt", toolWorkingDirectory).response
    r.exitCode shouldBe 1
    r.stdout should include("out_marker")
    (r.stdout should not).include("err_marker")
    r.stderr should include("err_marker")
  }

  it should "read back a file the agent wrote" in {
    write("visible.txt", "42")
    run("cat visible.txt", toolWorkingDirectory).response.stdout.trim shouldBe "42"
  }

  it should "stop tail -f at the tool's 2 second timeout with exit code -1" in {
    write("timeout.txt", "waiting\n")
    val out = run("tail -f timeout.txt", toolWorkingDirectory, Some(2.seconds))
    out.response.exitCode shouldBe -1
    out.response.stdout should include("waiting")
    out.elapsed should be >= 2.seconds
    out.elapsed should be < 30.seconds
  }

  // --- ContainerisedWorkspaceTest ----------------------------------------------------------------------------------

  "The commands ContainerisedWorkspaceTest sends" should "stop tail -f at its 3 second timeout, after its output" in {
    write("heartbeat.txt", "Starting long command\n")
    val out = run("tail -f heartbeat.txt", timeout = Some(3.seconds))
    out.response.exitCode shouldBe -1
    out.response.stdout should include("Starting long command")
    out.elapsed should be >= 3.seconds
    out.elapsed should be < 15.seconds

    val after = run("echo Command completed", timeout = Some(10.seconds)).response
    after.exitCode shouldBe 0
    after.stdout should include("Command completed")
  }

  it should "echo a quoted argument with the default timeout" in {
    for (i <- 1 to 3) {
      val r = run(s"echo 'Operation $i completed'").response
      r.exitCode shouldBe 0
      r.stdout should include(s"Operation $i completed")
    }
  }

  it should "stream each quoted argument as one word" in {
    val out = run("echo 'Step 1' 'Step 2' 'Step 3'", timeout = Some(10.seconds))
    out.response.exitCode shouldBe 0
    out.response.stdout shouldBe "Step 1 Step 2 Step 3\n"
    out.channel.stdoutChunks shouldBe "Step 1 Step 2 Step 3\n"
  }

  it should "report grep's exit code 1 for errors.txt" in {
    write("errors.txt", "alpha\n")
    run("grep zzz errors.txt", timeout = Some(5.seconds)).response.exitCode shouldBe 1
  }

  it should "be refused with the codes the suite expects" in {
    write("errors.txt", "alpha\n")
    refusedCode("echo one ; touch chained.txt") shouldBe "FORBIDDEN_CHARACTERS"
    refusedCode("cat errors.txt | sh") shouldBe "FORBIDDEN_CHARACTERS"
    refusedCode("echo $(id)") shouldBe "FORBIDDEN_CHARACTERS"
    refusedCode("echo pwned > redirected.txt") shouldBe "FORBIDDEN_CHARACTERS"
    refusedCode("sh -c id") shouldBe "EXECUTABLE_NOT_ALLOWED"
    refusedCode("find . -delete") shouldBe "ARGUMENT_NOT_ALLOWED"
    refusedCode("cat /etc/passwd") shouldBe "PATH_ESCAPE_ATTEMPT"
    refusedCode("ls", Some(Map("LD_PRELOAD" -> "/tmp/evil.so"))) shouldBe "ENVIRONMENT_NOT_ALLOWED"
    Files.exists(root.resolve("chained.txt")) shouldBe false
    Files.exists(root.resolve("redirected.txt")) shouldBe false
  }

  it should "end cat with no operands at once" in {
    val out = run("cat", timeout = Some(20.seconds))
    out.response.exitCode shouldBe 0
    out.elapsed should be < 10.seconds
  }

  it should "accept the demonstrateThreadingFix commands" in {
    write("long-operation.txt", "")
    containerDefault.prepareCommand("tail -f long-operation.txt", None, Some(8.seconds), None).isRight shouldBe true
    run("echo still-connected").response.exitCode shouldBe 0
  }

  // --- Samples -----------------------------------------------------------------------------------------------------

  "The commands ContainerisedWorkspaceDemo sends" should "run with exit code 0" in {
    run("echo 'Hello from workspace'").response.stdout shouldBe "Hello from workspace\n"
    // The demo names the file by its absolute path in the container, `/workspace/test_file.txt`.
    run(s"touch ${root.resolve("test_file.txt")}").response.exitCode shouldBe 0
    Files.exists(root.resolve("test_file.txt")) shouldBe true
    val ls = run("ls -la").response
    ls.exitCode shouldBe 0
    ls.stdout should include("test_file.txt")
  }

  "The command LockedDownSandboxDemo sends" should "be refused by the locked profile" in {
    val locked = new WorkspaceAgentInterfaceImpl(root.toString, isWindowsHost, Some(WorkspaceSandboxConfig.LockedDown))
    refusedCode("echo hello", workspace = locked) shouldBe "SHELL_DISABLED"
  }

  "The commands CodeGenExample asks for" should "be accepted only with sbt added to the allowlist" in {
    assume(!isWindowsHost, "the workspace-runner container is Linux")
    containerDefault.prepareCommand("sbt compile", toolWorkingDirectory, None, None).left.map(_.code) shouldBe
      Left("EXECUTABLE_NOT_ALLOWED")

    // CodeGenExample.ExtraAllowedCommands, which the container receives as WORKSPACE_EXTRA_COMMANDS=sbt.
    val withSbt = WorkspaceSandboxConfig.Permissive.withExtraCommands("sbt").fold(fail(_), identity)
    val codegen = new WorkspaceAgentInterfaceImpl(root.toString, isWindowsHost, Some(withSbt))
    for (command <- Seq("sbt compile", "sbt run", "mkdir -p src/main/scala"))
      withClue(command) {
        codegen.prepareCommand(command, toolWorkingDirectory, None, None).isRight shouldBe true
      }
    // Adding sbt opens nothing else: the shell forms stay refused.
    codegen.prepareCommand("sbt compile && sbt run", toolWorkingDirectory, None, None).left.map(_.code) shouldBe
      Left("FORBIDDEN_CHARACTERS")
    codegen.prepareCommand("sh -c id", toolWorkingDirectory, None, None).left.map(_.code) shouldBe
      Left("EXECUTABLE_NOT_ALLOWED")
  }
}
