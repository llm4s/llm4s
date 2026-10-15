package org.llm4s.runner

import org.llm4s.shared._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.util.concurrent.{ CompletableFuture, ConcurrentLinkedQueue, TimeUnit }
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * The process registry's invariant (design §4.33): a process the runner started is tracked from spawn until it has
 * really exited, whichever way it is stopped - a cancellation, a disconnect, shutdown, or a failed start announcement -
 * and holds its gate permit, so no file operation runs, until then. Each case uses a process that survives its kill
 * until the test lets it exit.
 */
class ProcessRegistrySpec extends AnyFlatSpec with Matchers {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  /** A process that ignores kills until [[die]]: what a stuck child, or one slow to die, looks like to the runner. */
  final private class StubbornProcess extends Process {
    private val exit                                             = new CompletableFuture[Process]()
    @volatile var kills                                          = 0
    def die(): Unit                                              = exit.complete(this): Unit
    override def getOutputStream: java.io.OutputStream           = java.io.OutputStream.nullOutputStream()
    override def getInputStream: java.io.InputStream             = java.io.InputStream.nullInputStream()
    override def getErrorStream: java.io.InputStream             = java.io.InputStream.nullInputStream()
    override def waitFor(): Int                                  = { exit.get(); 137 }
    override def waitFor(timeout: Long, unit: TimeUnit): Boolean = exit.isDone
    override def exitValue(): Int           = if (exit.isDone) 137 else throw new IllegalThreadStateException()
    override def destroy(): Unit            = kills += 1
    override def destroyForcibly(): Process = { kills += 1; this }
    override def isAlive: Boolean           = !exit.isDone
    override def onExit(): CompletableFuture[Process] = exit
  }

  private val isWindowsHost = System.getProperty("os.name").startsWith("Windows")

  /** A workspace whose every process is `stub`, and whose gate refuses a file operation after 200 ms. */
  private def workspaceWith(stub: StubbornProcess) =
    new WorkspaceAgentInterfaceImpl(
      Files.createTempDirectory("process-registry").toString,
      isWindowsHost,
      Some(WorkspaceSandboxConfig.Permissive),
      gateWait = 200.millis,
      startProcess = _ => stub
    )

  private def command(id: String) =
    ExecuteCommandCommand(commandId = id, command = "echo hi", timeout = Some(60.seconds))

  private def eventually(cond: => Boolean): Boolean = {
    val deadline = System.nanoTime() + 10.seconds.toNanos
    while (!cond && System.nanoTime() < deadline) Thread.sleep(20)
    cond
  }

  /** The process is tracked and holds the gate until it exits, then neither. */
  private def assertTrackedUntilExit(
    workspace: WorkspaceAgentInterfaceImpl,
    stub: StubbornProcess,
    owner: AnyRef,
    id: String
  ): Unit = {
    stub.kills should be > 0
    workspace.processes.tracked(owner, id) shouldBe true
    // its permit is still held: a file operation cannot run beside it
    Try(workspace.writeFile("a.txt", "x")).failed.toOption.collect { case e: WorkspaceAgentException =>
      e.code
    } shouldBe
      Some("WORKSPACE_BUSY")
    stub.die()
    eventually(!workspace.processes.tracked(owner, id)) shouldBe true
    workspace.writeFile("a.txt", "x").success shouldBe true
  }

  private def started(
    workspace: WorkspaceAgentInterfaceImpl,
    owner: AnyRef,
    id: String
  ): (WebSocketCommandExecutor, ConcurrentLinkedQueue[WebSocketMessage]) = {
    val executor = new WebSocketCommandExecutor(workspace, 1024L * 1024L)
    val sent     = new ConcurrentLinkedQueue[WebSocketMessage]()
    executor.execute(command(id), owner, m => sent.add(m): Unit)
    eventually(workspace.processes.tracked(owner, id)) shouldBe true
    (executor, sent)
  }

  "A cancelled process" should "stay tracked, and unacknowledged, until it has exited" in {
    val stub             = new StubbornProcess
    val workspace        = workspaceWith(stub)
    val owner            = new Object
    val (executor, sent) = started(workspace, owner, "c1")
    executor.cancel("c1", owner, m => sent.add(m): Unit)
    // a cancellation is acknowledged only when the process is gone
    sent.asScala.collect { case c: CommandCompletedMessage => c.exitCode } should not contain 143
    assertTrackedUntilExit(workspace, stub, owner, "c1")
  }

  "A disconnected client's process" should "stay tracked until it has exited" in {
    val stub      = new StubbornProcess
    val workspace = workspaceWith(stub)
    val owner     = new Object
    started(workspace, owner, "d1")
    workspace.processes.killOwner(owner)
    assertTrackedUntilExit(workspace, stub, owner, "d1")
  }

  "A process at shutdown" should "stay tracked until it has exited" in {
    val stub      = new StubbornProcess
    val workspace = workspaceWith(stub)
    val owner     = new Object
    started(workspace, owner, "s1")
    workspace.processes.killAll()
    assertTrackedUntilExit(workspace, stub, owner, "s1")
  }

  "A killed command" should "take the processes it started with it" in {
    assume(!isWindowsHost)
    val workspace = new WorkspaceAgentInterfaceImpl(
      Files.createTempDirectory("process-tree").toString,
      isWindowsHost,
      Some(WorkspaceSandboxConfig.Permissive)
    )
    val entry = workspace.processes
      .spawn(new Object, "tree", new java.lang.ProcessBuilder("sh", "-c", "sleep 60 & echo $!; wait"))
      .toOption
      .get
    val child =
      new java.io.BufferedReader(new java.io.InputStreamReader(entry.process.getInputStream)).readLine().toLong
    ProcessHandle.of(child).map[Boolean](_.isAlive).orElse(false) shouldBe true
    workspace.processes.kill(entry) shouldBe true
    eventually(!ProcessHandle.of(child).map[Boolean](_.isAlive).orElse(false)) shouldBe true
  }

  "A command waiting to start when its client leaves" should "never start" in {
    val workspace = workspaceWith(new StubbornProcess)
    val owner     = new Object
    workspace.processes.killOwner(owner)
    workspace.processes.spawn(owner, "late", new java.lang.ProcessBuilder("true")).left.map(_.code) shouldBe
      Left(ProcessRegistry.NotStarted)
    workspace.processes.all shouldBe empty
  }

  "A command waiting to start at shutdown" should "never start" in {
    val stub      = new StubbornProcess
    val workspace = workspaceWith(stub)
    workspace.processes.killAll()
    workspace.processes.spawn(new Object, "late", new java.lang.ProcessBuilder("true")).left.map(_.code) shouldBe
      Left("EXECUTION_FAILED")
    workspace.processes.all shouldBe empty
  }

  "A process whose start cannot be announced" should "be killed, and stay tracked until it has exited" in {
    val stub      = new StubbornProcess
    val workspace = workspaceWith(stub)
    val owner     = new Object
    val executor  = new WebSocketCommandExecutor(workspace, 1024L * 1024L)
    executor.execute(
      command("n1"),
      owner,
      {
        case _: CommandStartedMessage => throw new java.io.IOException("socket closed")
        case _                        => ()
      }
    )
    assertTrackedUntilExit(workspace, stub, owner, "n1")
  }

  "A process another client owns" should "not be cancelled or killed by this one" in {
    val stub             = new StubbornProcess
    val workspace        = workspaceWith(stub)
    val (mine, theirs)   = (new Object, new Object)
    val (executor, sent) = started(workspace, theirs, "o1")
    executor.cancel("o1", mine, m => sent.add(m): Unit)
    workspace.processes.killOwner(mine)
    stub.kills shouldBe 0
    workspace.processes.tracked(theirs, "o1") shouldBe true
    workspace.processes.killOwner(theirs)
    assertTrackedUntilExit(workspace, stub, theirs, "o1")
  }
}
