package org.llm4s.workspace

import org.llm4s.it.Tier
import org.llm4s.it.tags.Workspace
import org.llm4s.shared.{ ExecuteCommandResponse, WorkspaceAgentException, WorkspaceSandboxConfig }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.concurrent.duration.*
import scala.util.{ Failure, Success, Try }

/**
 * Kernel confinement of `execute` commands (design §4.39, #1983) in the workspace-runner image: the issue's acceptance.
 *
 * Two containers, since a container's sandbox config is fixed when it starts:
 *   - A: `readOnlyPaths = ["ro"]`, `networkAllowed = false`, with `jcmd` and `java` allowed for the `/tmp` cases, and a
 *     git repository;
 *   - B: `allowedPaths = ["src"]`, `networkAllowed = true`.
 *
 * Each command here must be accepted by the runner and fail (or succeed) by itself, so a command refused before it runs,
 * `SANDBOX_UNAVAILABLE` above all, fails the case rather than passing it. `WorkspaceCommandsSpec` runs the same command
 * strings through the runner's preparation on every PR; keep the two in step.
 */
@Workspace
class WorkspaceConfinementIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val EnableDockerEnvVar = "LLM4S_DOCKER_TESTS"

  // apart from ContainerisedWorkspaceTest (8080), WorkspaceAgentIntegrationSpec (8081),
  // WorkspaceBackendsIntegrationSpec (8082) and CodingWorkspaceIntegrationSpec (8083)
  private val HostPortA = 8084
  private val HostPortB = 8085

  /** The runner's own HTTP port inside the container, so the network cases need no internet. */
  private val CurlRunner = "curl -sS --max-time 5 http://127.0.0.1:8080/"

  private def isDockerAvailable: Boolean =
    sys.env.get(EnableDockerEnvVar).exists(_.equalsIgnoreCase("true")) &&
      Try(Runtime.getRuntime.exec(Array("docker", "--version")).waitFor() == 0).getOrElse(false)

  private val dirA: Path = Files.createTempDirectory("workspace-confinement-a")
  private val dirB: Path = Files.createTempDirectory("workspace-confinement-b")

  private var started: List[ContainerisedWorkspace] = Nil

  private def write(dir: Path, name: String, content: String): Unit = {
    val p = dir.resolve(name)
    Files.createDirectories(p.getParent)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8)): Unit
  }

  private def start(
    dir: Path,
    port: Int,
    config: WorkspaceSandboxConfig,
    extra: Set[String] = Set("curl")
  ): ContainerisedWorkspace = {
    val workspace = new ContainerisedWorkspace(
      dir.toString,
      ContainerisedWorkspaceTest.image,
      port,
      extraAllowedCommands = extra,
      sandboxConfig = Some(config)
    )
    if (!workspace.startContainer()) fail(s"Failed to start the workspace container on port $port")
    started = workspace :: started
    Thread.sleep(2000)
    workspace
  }

  private lazy val workspaceA: ContainerisedWorkspace = {
    write(dirA, "ro/a", "original")
    write(dirA, "b", "replacement")
    write(
      dirA,
      "T.java",
      "public class T { public static void main(String[] a) throws Exception { " +
        "java.io.File f = java.io.File.createTempFile(\"llm4s\", \".tmp\"); System.out.print(f); f.delete(); } }"
    )
    // java is never an extra command (it runs any code): the whole config names it, only to have a JVM to run
    val config = WorkspaceSandboxConfig.Permissive.copy(
      allowedCommands = WorkspaceSandboxConfig.Permissive.allowedCommands + "java",
      readOnlyPaths = List("ro"),
      networkAllowed = false
    )
    // a repository, for the git case (git's own files are the runner's, written on the host here)
    if (new ProcessBuilder("git", "init", "-q", dirA.toString).inheritIO().start().waitFor() != 0)
      fail("git init failed on the host")
    start(dirA, HostPortA, config, extra = Set("curl", "jcmd"))
  }

  private lazy val workspaceB: ContainerisedWorkspace = {
    write(dirB, "src/x", "x")
    write(dirB, "secret/s", "TOP-SECRET-1983")
    start(dirB, HostPortB, WorkspaceSandboxConfig.Permissive.copy(allowedPaths = List("src"), networkAllowed = true))
  }

  private def live(workspace: => ContainerisedWorkspace): ContainerisedWorkspace = {
    Tier.require(isDockerAvailable, s"Docker not available or $EnableDockerEnvVar!=true")
    workspace
  }

  /** The command's response; a refusal (`SANDBOX_UNAVAILABLE`, or the policy's) fails the case with its code. */
  private def runCommand(
    workspace: ContainerisedWorkspace,
    command: String,
    workingDirectory: Option[String] = None
  ): ExecuteCommandResponse =
    Try(workspace.executeCommand(command, workingDirectory, Some(20.seconds))) match {
      case Success(response)                   => response
      case Failure(e: WorkspaceAgentException) => fail(s"'$command' was refused, not run: ${e.code}: ${e.error}")
      case Failure(e)                          => fail(s"'$command' failed: $e")
    }

  override def afterAll(): Unit = {
    started.foreach(w => Try(w.stopContainer()))
    def deleteRecursively(f: File): Unit = {
      if (f.isDirectory) f.listFiles().foreach(deleteRecursively)
      f.delete(): Unit
    }
    Try(deleteRecursively(dirA.toFile))
    Try(deleteRecursively(dirB.toFile))
    super.afterAll()
  }

  "A confined command" should "run, so the cases below fail by the kernel's refusal and not the runner's" in {
    val ws = live(workspaceA)
    val r  = runCommand(ws, "cat b")
    r.exitCode shouldBe 0
    r.stdout shouldBe "replacement"
  }

  it should "not delete or overwrite a file under readOnlyPaths" in {
    val ws = live(workspaceA)
    val rm = runCommand(ws, "rm ro/a")
    withClue(s"rm: $rm") {
      rm.exitCode should not be 0
    }
    val cp = runCommand(ws, "cp b ro/a")
    withClue(s"cp: $cp") {
      cp.exitCode should not be 0
    }
    new String(Files.readAllBytes(dirA.resolve("ro/a")), StandardCharsets.UTF_8) shouldBe "original"
  }

  it should "have no network when networkAllowed = false" in {
    val ws = live(workspaceA)
    val r  = runCommand(ws, CurlRunner)
    withClue(s"curl: $r") {
      r.exitCode should not be 0
      (r.stdout should not).include("LLM4S WebSocket Runner")
    }
  }

  it should "not read a workspace path outside allowedPaths" in {
    val ws = live(workspaceB)
    val r  = runCommand(ws, "cat ../secret/s", Some("src"))
    withClue(s"cat: $r") {
      r.exitCode should not be 0
      (r.stdout should not).include("TOP-SECRET-1983")
      (r.stderr should not).include("TOP-SECRET-1983")
    }
  }

  it should "reach the runner's own port when networkAllowed = true" in {
    val ws = live(workspaceB)
    val r  = runCommand(ws, CurlRunner, Some("src"))
    withClue(s"curl: $r") {
      r.exitCode shouldBe 0
      r.stdout should include("LLM4S WebSocket Runner")
    }
  }

  it should "let a JVM write its temporary files in /tmp, since the image's runner keeps nothing there" in {
    val ws = live(workspaceA)
    val r  = runCommand(ws, "java T.java")
    withClue(s"java: $r") {
      r.exitCode shouldBe 0
      r.stdout should startWith("/tmp/")
    }
  }

  it should "run git with its empty directory (core.hooksPath) in the runner's private java.io.tmpdir, not /tmp" in {
    val ws = live(workspaceA)
    val r  = runCommand(ws, "git rev-parse --git-path hooks")
    withClue(s"git: $r") {
      r.exitCode shouldBe 0
      r.stdout should startWith("/var/tmp/llm4s-runner/llm4s-git-")
    }
  }

  it should "not attach to the runner's JVM, though /tmp is writable" in {
    val ws = live(workspaceA)
    val r  = runCommand(ws, "jcmd 1 VM.version")
    withClue(s"jcmd: $r") {
      r.exitCode should not be 0
      (r.stdout should not).include("JDK")
    }
  }
}
