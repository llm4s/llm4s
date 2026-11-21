package org.llm4s.workspace

import org.llm4s.it.Tier
import org.llm4s.it.tags.Workspace
import org.llm4s.shared.{ WorkspaceAgentException, WorkspaceSandboxConfig }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.concurrent.duration.*
import scala.util.{ Failure, Success, Try }

/**
 * A cgroup per command (design §4.39, #2008) in the workspace-runner image: the issue's acceptance. A command starts a
 * daemon (`setsid -f`, a new session, out of the command's process group) that appends to a file in the workspace
 * every 100 ms; the daemon is alive while the file grows.
 *
 * Two containers:
 *   - A, started for a cgroup per command (`commandCgroups = true`): the daemon is killed when its command ends, when
 *     it times out and when it is cancelled;
 *   - B, a default `docker run`: the runner has no writable cgroup, behaves as before, and the daemon outlives its
 *     command. This is the control: it shows the daemon really leaves the process group, so A's cases cannot pass for
 *     want of a daemon.
 *
 * `sh` is not an extra command (it runs anything): the whole config names it, only to start the daemon.
 */
@Workspace
class WorkspaceCgroupIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val EnableDockerEnvVar = "LLM4S_DOCKER_TESTS"

  // apart from the other workspace suites' 8080-8086
  private val HostPortA = 8087
  private val HostPortB = 8088

  private def isDockerAvailable: Boolean =
    sys.env.get(EnableDockerEnvVar).exists(_.equalsIgnoreCase("true")) &&
      Try(Runtime.getRuntime.exec(Array("docker", "--version")).waitFor() == 0).getOrElse(false)

  private val dirA: Path = Files.createTempDirectory("workspace-cgroup-a")
  private val dirB: Path = Files.createTempDirectory("workspace-cgroup-b")

  private var started: List[ContainerisedWorkspace] = Nil

  /**
   * The scripts, in each workspace: `beat.sh F` appends to F every 100 ms, forever; `daemon.sh F` starts it as a
   * daemon (its own session, every standard stream elsewhere, so it holds none of the command's pipes) and returns
   * once it has beaten; `hang.sh F` does that and then waits a minute.
   */
  private def scripts(dir: Path): Unit = {
    def write(name: String, content: String): Unit =
      Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8)): Unit
    write("beat.sh", "while :; do echo beat >> \"$1\"; sleep 0.1; done\n")
    write(
      "daemon.sh",
      "setsid -f sh beat.sh \"$1\" < /dev/null > /dev/null 2>&1\n" +
        "i=0; while [ ! -s \"$1\" ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done\n" +
        "[ -s \"$1\" ] || { echo 'the daemon never beat' >&2; exit 3; }\n"
    )
    write("hang.sh", "sh daemon.sh \"$1\" || exit $?\nsleep 60\n")
  }

  private val config = WorkspaceSandboxConfig.Permissive.copy(
    allowedCommands = WorkspaceSandboxConfig.Permissive.allowedCommands + "sh"
  )

  private def start(dir: Path, port: Int, commandCgroups: Boolean): ContainerisedWorkspace = {
    scripts(dir)
    val workspace = new ContainerisedWorkspace(
      dir.toString,
      ContainerisedWorkspaceTest.image,
      port,
      sandboxConfig = Some(config),
      commandCgroups = commandCgroups
    )
    if (!workspace.startContainer()) fail(s"Failed to start the workspace container on port $port")
    started = workspace :: started
    Thread.sleep(2000)
    workspace
  }

  private lazy val workspaceA: ContainerisedWorkspace = start(dirA, HostPortA, commandCgroups = true)
  private lazy val workspaceB: ContainerisedWorkspace = start(dirB, HostPortB, commandCgroups = false)

  private def live(workspace: => ContainerisedWorkspace): ContainerisedWorkspace = {
    Tier.require(isDockerAvailable, s"Docker not available or $EnableDockerEnvVar!=true")
    workspace
  }

  private def size(f: Path): Long = if (Files.exists(f)) Files.size(f) else 0L

  /** Whether `f` grows over a second and a half, read from the host through the bind mount. */
  private def growing(f: Path): Boolean = {
    Thread.sleep(1000) // a write in flight, or a cancellation on its way, lands first
    val before = size(f)
    Thread.sleep(1500)
    size(f) > before
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

  "A daemon a command starts" should "outlive its command in a default container (the control)" in {
    val ws = live(workspaceB)
    val r  = ws.executeCommand("sh daemon.sh beat-b", None, Some(30.seconds))
    withClue(s"daemon.sh: $r") {
      r.exitCode shouldBe 0
    }
    withClue("the daemon left the command's process group, so the runner's kill cannot reach it") {
      growing(dirB.resolve("beat-b")) shouldBe true
    }
  }

  it should "be killed when its command ends, in a container started for a cgroup per command" in {
    val ws = live(workspaceA)
    val r  = ws.executeCommand("sh daemon.sh beat-end", None, Some(30.seconds))
    withClue(s"daemon.sh: $r") {
      r.exitCode shouldBe 0
    }
    size(dirA.resolve("beat-end")) should be > 0L
    growing(dirA.resolve("beat-end")) shouldBe false
  }

  it should "be killed when its command times out" in {
    val ws = live(workspaceA)
    // a timeout is a response with the marker over the WebSocket, or a TIMEOUT refusal: either way it ended
    val outcome = Try(ws.executeCommand("sh hang.sh beat-timeout", None, Some(5.seconds)))
    outcome match {
      case Success(r)                          => withClue(s"hang.sh: $r")(r.stderr should include("timed out"))
      case Failure(e: WorkspaceAgentException) => withClue(s"hang.sh: ${e.code}: ${e.error}")(e.code shouldBe "TIMEOUT")
      case Failure(e)                          => fail(s"hang.sh failed: $e")
    }
    size(dirA.resolve("beat-timeout")) should be > 0L
    growing(dirA.resolve("beat-timeout")) shouldBe false
  }

  it should "be killed when its command is cancelled" in {
    val ws   = live(workspaceA)
    val beat = dirA.resolve("beat-cancel")
    // the client cancels the command it is waiting for when its thread is interrupted
    val caller = new Thread(() => Try(ws.executeCommand("sh hang.sh beat-cancel", None, Some(60.seconds))): Unit)
    caller.start()
    val deadline = System.nanoTime() + 30.seconds.toNanos
    while (size(beat) == 0L && System.nanoTime() < deadline) Thread.sleep(100)
    withClue("the daemon never started")(size(beat) should be > 0L)
    caller.interrupt()
    caller.join(30000)
    caller.isAlive shouldBe false
    growing(beat) shouldBe false
  }
}
