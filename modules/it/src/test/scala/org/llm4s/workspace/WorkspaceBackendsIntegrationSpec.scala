package org.llm4s.workspace

import org.llm4s.agent.graph.{ ReplaySupport, RunContext }
import org.llm4s.agent.harness.{ BackendRefused, FileContext, FileWritten, VPath }
import org.llm4s.agent.harness.workspace.{ WorkspaceFileBackend, WorkspaceSandboxBackend }
import org.llm4s.agent.testkit.{ FileBackendContract, ReplayContract }
import org.llm4s.it.Tier
import org.llm4s.it.tags.Workspace
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files
import scala.concurrent.duration.*
import scala.util.Try

/**
 * One workspace-runner container for the backend suites below, started on first use and stopped when the JVM
 * exits. Needs Docker and the locally built image: `sbt testWorkspace`.
 */
private object LiveWorkspace {

  private val EnableDockerEnvVar = "LLM4S_DOCKER_TESTS"

  // apart from ContainerisedWorkspaceTest (8080) and WorkspaceAgentIntegrationSpec (8081)
  private val HostPort = 8082

  private def isDockerAvailable: Boolean =
    sys.env.get(EnableDockerEnvVar).exists(_.equalsIgnoreCase("true")) &&
      Try(Runtime.getRuntime.exec(Array("docker", "--version")).waitFor() == 0).getOrElse(false)

  private lazy val started: ContainerisedWorkspace = {
    val dir       = Files.createTempDirectory("workspace-backends-it").toString
    val workspace = new ContainerisedWorkspace(dir, ContainerisedWorkspaceTest.image, HostPort)
    if (!workspace.startContainer()) throw new IllegalStateException("Failed to start the workspace container")
    Thread.sleep(2000)
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      workspace.stopContainer(): Unit
      def deleteRecursively(f: File): Unit = {
        if (f.isDirectory) f.listFiles().foreach(deleteRecursively)
        f.delete(): Unit
      }
      Try(deleteRecursively(new File(dir))): Unit
    }))
    workspace
  }

  /** The live workspace; the test is skipped (or failed, under the strict tier) without Docker. */
  def get: ContainerisedWorkspace = {
    Tier.require(isDockerAvailable, s"Docker not available or $EnableDockerEnvVar!=true")
    started
  }

  def p(s: String): VPath = VPath(s).fold(e => throw new IllegalArgumentException(e.message), identity)

  def files: WorkspaceFileBackend =
    WorkspaceFileBackend("it", get).fold(e => throw new IllegalStateException(e.message), identity)

  final case class Go(x: String) derives upickle.default.ReadWriter

  /** One tool call of a real run, for a backend operation that needs a call's context. */
  def inCall[A](body: FileContext => A): A = {
    import org.llm4s.agent.Agent
    import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, ToolContent, ToolOutcome, ToolSet }
    import org.llm4s.llmconnect.model.ToolCall
    import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
    import org.llm4s.toolapi.Schema
    val out = new java.util.concurrent.atomic.AtomicReference[A]()
    val tool = AgentTool(
      AgentToolSpec[Go]("go", "go", Schema.`object`[Go]("go").withRequiredField("x", Schema.string("x")))
    ) { (_, context) =>
      out.set(body(FileContext(context)))
      ToolOutcome.Success(ToolContent.Json(ujson.Str("ok")))
    }
    val client =
      ScriptedLLMClient.sequence(Reply.toolCalls(ToolCall("c", "go", ujson.Obj("x" -> ""))), Reply.text("done"))
    Agent
      .builder("it", client)
      .withTools(ToolSet.of(tool).fold(e => throw new IllegalStateException(e.message), identity))
      .build()
      .flatMap(_.run("go"))
      .fold(e => throw new IllegalStateException(e.message), _ => out.get)
  }
}

/** The live subject: it reaches the container only when a case uses it, never while the suite is built. */
final private class LiveEditSubject extends ReplayContract.Subject {
  private lazy val subject = {
    val workspace = LiveWorkspace.get
    FileBackendContract.EditSubject(
      "a live workspace file backend",
      LiveWorkspace.files,
      ReplaySupport.Verifiable,
      (path, text) => { workspace.writeFile(path.value.drop(1), text, createDirectories = Some(true)); () },
      path => Right(workspace.fileSnapshot(path.value.drop(1)).content.getOrElse(""))
    )
  }
  def name: String                                                 = "a live workspace file backend"
  def support: ReplaySupport                                       = ReplaySupport.Verifiable
  def perform(context: RunContext, slot: Int): Result[ujson.Value] = subject.perform(context, slot)
  def observe(slot: Int): Result[String]                           = subject.observe(slot)
  def initial(slot: Int): String                                   = "x"
  def once(slot: Int): String                                      = "xy"
  def interfere(slot: Int): Unit                                   = subject.interfere(slot)
}

/**
 * C4's replay contract for the `Verifiable` workspace file backend (design §4.33) against a live runner, whose
 * compare-and-set is what fences a stale claimant.
 */
@Workspace
class WorkspaceFileBackendLiveReplaySpec extends AnyFlatSpec with ReplayContract {
  protected def newSubject(): ReplayContract.Subject = new LiveEditSubject
}

/** The runner's side of the workspace backends (design §4.33) against a live container. */
@Workspace
class WorkspaceBackendsIntegrationSpec extends AnyFlatSpec with Matchers {

  import LiveWorkspace.{ files, inCall, p }

  "A live workspace" should "apply a compare-and-set only against the hash it read" in {
    val workspace = LiveWorkspace.get
    workspace.writeFile("cas.txt", "one")
    val before = workspace.fileSnapshot("cas.txt")
    workspace.writeFile("cas.txt", "two", expectedHash = Some(before.hash)).success shouldBe true
    val lost = intercept[org.llm4s.shared.WorkspaceAgentException](
      workspace.writeFile("cas.txt", "three", expectedHash = Some(before.hash))
    )
    lost.code shouldBe "HASH_MISMATCH"
    workspace.fileSnapshot("cas.txt").content shouldBe Some("two")
    workspace.deleteFile("cas.txt", Some(workspace.fileSnapshot("cas.txt").hash)).wasDirectory shouldBe false
  }

  it should "keep each stream of a command to the sandbox's bound" in {
    val workspace = LiveWorkspace.get
    workspace.writeFile("long.txt", "z" * 10000)
    val sandbox =
      WorkspaceSandboxBackend("it", workspace, maxOutputBytes = 64).fold(e => fail(e.message), identity)
    val result = inCall(ctx => sandbox.execute("cat long.txt", p("/"), 30.seconds, ctx))
    result.map(_.stdout.length) shouldBe Right(64)
    result.map(_.truncated) shouldBe Right(true)
  }

  it should "write, edit, list and search through the file backend, refusing what the sandbox forbids" in {
    LiveWorkspace.get
    val backend = files
    inCall(ctx => backend.write(p("/it/ok.txt"), "alpha\nbeta\n", ctx)).map(_._1) shouldBe
      Right(FileWritten(created = true, bytes = 11))
    inCall(ctx => backend.edit(p("/it/ok.txt"), "beta", "gamma", replaceAll = false, ctx)).isRight shouldBe true
    inCall(ctx => backend.read(p("/it/ok.txt"), 0, 10, ctx)).map(_.lines) shouldBe Right(Vector("alpha", "gamma"))
    inCall(ctx => backend.ls(p("/it"), 10, ctx)).map(_.items.map(_.path.value)) shouldBe Right(Vector("/it/ok.txt"))
    inCall(ctx => backend.grep("gam", p("/it"), None, 10, ctx)).map(_.items.map(_.line)) shouldBe Right(Vector(2))
    inCall(ctx => backend.edit(p("/it/ok.txt"), "zzz", "q", replaceAll = false, ctx)).left.toOption.get shouldBe
      a[BackendRefused]
    // the runner refuses a write into .git, and the model sees it
    inCall(ctx => backend.write(p("/.git/config"), "x", ctx)).left.toOption.get shouldBe a[BackendRefused]
    inCall(ctx => backend.delete(p("/it/ok.txt"), ctx)).isRight shouldBe true
  }
}
