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
 * The coreutils of the workspace-runner image, and the `sort` and `cp` rules of the command policy inside it (#1774).
 *
 * The policy's option tables model GNU (and BSD) `sort` and `cp`; the image's base, Ubuntu 26.04, ships uutils for most
 * of coreutils, so the image installs GNU coreutils (`project/WorkspaceRunnerDocker.scala`). These cases check the
 * built image really runs GNU, and that the refusals `ExtraCommandRulesSpec` and `WorkspaceCommandArgumentsSpec` pin on
 * every PR hold over the WebSocket against the real programs.
 */
@Workspace
class WorkspaceCoreutilsIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val EnableDockerEnvVar = "LLM4S_DOCKER_TESTS"

  // apart from the other workspace suites' ports (8080 to 8085)
  private val HostPort = 8086

  private def isDockerAvailable: Boolean =
    sys.env.get(EnableDockerEnvVar).exists(_.equalsIgnoreCase("true")) &&
      Try(Runtime.getRuntime.exec(Array("docker", "--version")).waitFor() == 0).getOrElse(false)

  private val dir: Path = Files.createTempDirectory("workspace-coreutils")

  private var started: Option[ContainerisedWorkspace] = None

  private def write(name: String, content: String): Unit = {
    val p = dir.resolve(name)
    Files.createDirectories(p.getParent)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8)): Unit
  }

  private lazy val workspace: ContainerisedWorkspace = {
    write("a.txt", "b,2\na,1\n")
    write("-tNAME", "z\ny\n")
    write("x/n.txt", "x\n")
    write("y/n.txt", "y\n")
    Files.createDirectories(dir.resolve("d"))
    val ws = new ContainerisedWorkspace(
      dir.toString,
      ContainerisedWorkspaceTest.image,
      HostPort,
      sandboxConfig = Some(WorkspaceSandboxConfig.Permissive)
    )
    if (!ws.startContainer()) fail(s"Failed to start the workspace container on port $HostPort")
    started = Some(ws)
    Thread.sleep(2000)
    ws
  }

  private def live: ContainerisedWorkspace = {
    Tier.require(isDockerAvailable, s"Docker not available or $EnableDockerEnvVar!=true")
    workspace
  }

  /** The command's response; a refusal fails the case with its code. */
  private def runs(command: String): ExecuteCommandResponse =
    Try(live.executeCommand(command, None, Some(20.seconds))) match {
      case Success(response)                   => response
      case Failure(e: WorkspaceAgentException) => fail(s"'$command' was refused, not run: ${e.code}: ${e.error}")
      case Failure(e)                          => fail(s"'$command' failed: $e")
    }

  /** The runner's refusal code for the command, which must not run. */
  private def refusedCode(command: String): String =
    Try(live.executeCommand(command, None, Some(20.seconds))) match {
      case Failure(e: WorkspaceAgentException) => e.code
      case Failure(e)                          => fail(s"'$command' failed: $e")
      case Success(response)                   => fail(s"'$command' ran, not refused: $response")
    }

  override def afterAll(): Unit = {
    started.foreach(w => Try(w.stopContainer()))
    def deleteRecursively(f: File): Unit = {
      if (f.isDirectory) f.listFiles().foreach(deleteRecursively)
      f.delete(): Unit
    }
    Try(deleteRecursively(dir.toFile))
    super.afterAll()
  }

  "The workspace-runner image" should "run GNU coreutils, which the command policy models, not uutils" in {
    // every coreutils program of the permissive allowlist
    Seq("sort", "cp", "mv", "rm", "mkdir", "touch", "chmod", "ls", "cat", "head", "tail", "wc", "uniq")
      .foreach { program =>
        val r = runs(s"$program --version")
        withClue(s"$program --version: $r") {
          r.exitCode shouldBe 0
          r.stdout.linesIterator.nextOption().getOrElse("") should include("(GNU coreutils)")
        }
      }
  }

  "sort in the image" should "refuse an operand after '--' starting with -t" in {
    refusedCode("sort -- -tNAME") shouldBe "ARGUMENT_NOT_ALLOWED"
    refusedCode("sort -r -- a.txt -tNAME") shouldBe "ARGUMENT_NOT_ALLOWED"
  }

  it should "refuse its writing options, abbreviated too, and a file outside the workspace" in {
    refusedCode("sort -o out.txt a.txt") shouldBe "ARGUMENT_NOT_ALLOWED"
    refusedCode("sort --outp=out.txt a.txt") shouldBe "ARGUMENT_NOT_ALLOWED"
    refusedCode("sort --compress-program=id a.txt") shouldBe "ARGUMENT_NOT_ALLOWED"
    refusedCode("sort -- -o out.txt a.txt") shouldBe "ARGUMENT_NOT_ALLOWED"
    refusedCode("sort -- /etc/passwd") shouldBe "PATH_ESCAPE_ATTEMPT"
    Files.exists(dir.resolve("out.txt")) shouldBe false
  }

  it should "still sort with a separator, and a file named -tNAME written as ./-tNAME" in {
    val bySeparator = runs("sort -t, -k2 a.txt")
    withClue(s"sort -t, -k2: $bySeparator") {
      bySeparator.exitCode shouldBe 0
      bySeparator.stdout shouldBe "a,1\nb,2\n"
    }
    val named = runs("sort -- ./-tNAME")
    withClue(s"sort -- ./-tNAME: $named") {
      named.exitCode shouldBe 0
      named.stdout shouldBe "y\nz\n"
    }
  }

  "cp in the image" should "refuse the options that follow or make a link, and a destination outside the workspace" in {
    refusedCode("cp -L a.txt d/") shouldBe "ARGUMENT_NOT_ALLOWED"
    refusedCode("cp --dereference a.txt d/") shouldBe "ARGUMENT_NOT_ALLOWED"
    refusedCode("cp -H a.txt d/") shouldBe "ARGUMENT_NOT_ALLOWED"
    refusedCode("cp -s a.txt d/s.txt") shouldBe "ARGUMENT_NOT_ALLOWED"
    refusedCode("cp --symbolic-link a.txt d/s.txt") shouldBe "ARGUMENT_NOT_ALLOWED"
    refusedCode("cp a.txt ../escaped.txt") shouldBe "PATH_ESCAPE_ATTEMPT"
  }

  it should "refuse a recursive copy of two sources with the same name" in {
    refusedCode("cp -R x/n.txt y/n.txt d") shouldBe "ARGUMENT_NOT_ALLOWED"
    Files.exists(dir.resolve("d/n.txt")) shouldBe false
  }

  it should "still copy a file in the workspace" in {
    val r = runs("cp a.txt d/copy.txt")
    withClue(s"cp: $r") {
      r.exitCode shouldBe 0
    }
    new String(Files.readAllBytes(dir.resolve("d/copy.txt")), StandardCharsets.UTF_8) shouldBe "b,2\na,1\n"
  }
}
