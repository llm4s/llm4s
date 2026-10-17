package org.llm4s.agent.harness.workspace

import org.llm4s.agent.Agent
import org.llm4s.agent.graph.{ GraphError, ThreadId }
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, LockKey, ToolContent, ToolOutcome, ToolSet }
import org.llm4s.agent.harness.*
import org.llm4s.llmconnect.model.{ ToolCall, ToolMessage }
import org.llm4s.shared.{ ExecuteCommandResponse, WorkspaceAgentException }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The workspace backends' own behaviour (design §4.33), over a fake workspace. */
class WorkspaceBackendsSpec extends AnyFlatSpec with Matchers with EitherValues {

  import WorkspaceFixtures.p

  final private case class Edit(path: String, old: String, next: String) derives ReadWriter

  private def runs(batch: Seq[(String, ujson.Obj)], tools: ToolSet): Vector[String] = {
    val replies = Seq(
      Reply.toolCalls(batch.zipWithIndex.map { case ((name, args), i) => ToolCall(s"c$i", name, args) }*),
      Reply.text("done")
    )
    val agent = Agent.builder("ws", ScriptedLLMClient.sequence(replies*)).withTools(tools).build().value
    agent
      .run(ThreadId(s"ws-${System.nanoTime()}"), "go")
      .value
      .messages
      .collect { case t: ToolMessage =>
        t.content
      }
      .toVector
  }

  private def editTool(backend: WorkspaceFileBackend): AgentTool[Edit] =
    AgentTool(
      AgentToolSpec[Edit](
        "edit",
        "edit",
        Schema
          .`object`[Edit]("edit")
          .withRequiredField("path", Schema.string("path"))
          .withRequiredField("old", Schema.string("old"))
          .withRequiredField("next", Schema.string("next"))
      ).withLocks(e => backend.locks(p(e.path)))
    ) { (e, context) =>
      backend
        .edit(p(e.path), e.old, e.next, replaceAll = false, FileContext(context))
        .fold(BackendRefused.toolOutcome, _ => ToolOutcome.Success(ToolContent.Json(ujson.Str("edited"))))
    }

  /** `body` with the context of one tool call of a real run. */
  private def inCall[A](body: FileContext => A): A = {
    val out = new AtomicReference[A]()
    final case class Go(x: String) derives ReadWriter
    val tool = AgentTool(
      AgentToolSpec[Go]("go", "go", Schema.`object`[Go]("go").withRequiredField("x", Schema.string("x")))
    ) { (_, context) =>
      out.set(body(FileContext(context)))
      ToolOutcome.Success(ToolContent.Json(ujson.Str("ok")))
    }
    runs(Seq("go" -> ujson.Obj("x" -> "")), ToolSet.of(tool).value)
    out.get
  }

  "A batch's edit and command in one workspace" should "apply in call order, the command seeing the edit" in {
    val seen = new CopyOnWriteArrayList[String]()
    val workspace: FakeWorkspace = new FakeWorkspace(exec =
      (_, _, _, _) => ExecuteCommandResponse("local", "", "", 0, isOutputTruncated = false, duration = 1.milli)
    )
    // the command reads the workspace as it runs
    lazy val reading = new FakeWorkspace(exec = (_, _, _, _) => {
      seen.add(workspace.text("a.txt")): Unit
      ExecuteCommandResponse("local", "", "", 0, isOutputTruncated = false, duration = 1.milli)
    })
    workspace.seed("a.txt", "one")
    val files   = WorkspaceFixtures.files(workspace)
    val sandbox = SandboxBackend(WorkspaceSandboxRunner("ws", reading).value)
    val tools   = ToolSet.of(editTool(files), ExecuteTool(sandbox)).value
    runs(
      Seq(
        "edit"    -> ujson.Obj("path" -> "/a.txt", "old" -> "one", "next" -> "two"),
        "execute" -> ujson.Obj("command" -> "cat a.txt")
      ),
      tools
    )
    runs(
      Seq(
        "execute" -> ujson.Obj("command" -> "cat a.txt"),
        "edit"    -> ujson.Obj("path" -> "/a.txt", "old" -> "two", "next" -> "three")
      ),
      tools
    )
    seen.asScala.toVector shouldBe Vector("two", "two")
    workspace.text("a.txt") shouldBe "three"
  }

  "A harness agent over a workspace" should "write a file with the file tools and run a command that sees it" in {
    lazy val workspace: FakeWorkspace = new FakeWorkspace(exec =
      (_, _, _, _) =>
        ExecuteCommandResponse(
          "local",
          workspace.text("notes.txt"),
          "",
          0,
          isOutputTruncated = false,
          duration = 1.milli
        )
    )
    val files   = WorkspaceFixtures.files(workspace)
    val sandbox = WorkspaceSandboxBackend("ws", workspace).value
    val replies = Seq(
      Reply.toolCalls(ToolCall("w", "write_file", ujson.Obj("path" -> "/notes.txt", "content" -> "from the harness"))),
      Reply.toolCalls(ToolCall("x", "execute", ujson.Obj("command" -> "cat notes.txt"))),
      Reply.text("done")
    )
    val agent = Agent
      .builder("harness", ScriptedLLMClient.sequence(replies*))
      .withHarness(HarnessProfile().withBackend(files).withSandbox(sandbox))
      .build()
      .value
    val messages = agent.run(ThreadId(s"harness-${System.nanoTime()}"), "go").value.messages.collect {
      case t: ToolMessage => t.content
    }
    workspace.text("notes.txt") shouldBe "from the harness"
    messages.last should include("from the harness")
    messages.last should include("exit code: 0")
  }

  "The locks" should "put a command above every file of its workspace, and every workspace under the resolver's root" in {
    val files  = WorkspaceFixtures.files(new FakeWorkspace())
    val runner = WorkspaceSandboxRunner("ws", new FakeWorkspace()).value
    val file   = files.locks(p("/src/a.txt")).head
    file shouldBe LockKey("files/workspace/ws")
    // two paths that may name one file through a link share it: their edits are ordered
    files.locks(p("/alias")).head.conflictsWith(files.locks(p("/real")).head) shouldBe true
    runner.locks(p("/src")).head.conflictsWith(file) shouldBe true
    WorkspaceLocks.workspaceLock("other").conflictsWith(file) shouldBe false
    WorkspaceLocks.AllWorkspaces.conflictsWith(runner.locks(p("/")).head) shouldBe true
    // under C3's root, so a resolved file backend's lock orders it too
    FileBackend.LockRoot.conflictsWith(file) shouldBe true
  }

  "A resolved sandbox" should "run each tenant's commands in its own workspace" in {
    val ran = new CopyOnWriteArrayList[String]()
    def ws(name: String) = new FakeWorkspace(exec = (c, _, _, _) => {
      ran.add(s"$name:$c"): Unit
      ExecuteCommandResponse("local", name, "", 0, isOutputTruncated = false, duration = 1.milli)
    })
    val (a, b) = (WorkspaceSandboxRunner("a", ws("a")).value, WorkspaceSandboxRunner("b", ws("b")).value)
    val sandbox = SandboxBackend.resolved(_ => Set(WorkspaceLocks.AllWorkspaces))(ctx =>
      Right(if ctx.run.config.tenantId.exists(_.value == "a") then a else b)
    )
    inCall(ctx => sandbox.execute("ls", p("/"), 1.second, ctx)).value.stdout shouldBe "b"
    ran.asScala.toVector shouldBe Vector("b:ls")
  }

  "The file backend" should "refuse to read a directory or a file that is not text, and skip one in a search" in {
    val workspace = new FakeWorkspace()
    workspace.mkdir("d")
    workspace.seedBytes("bin", Array[Byte](0xff.toByte))
    workspace.seed("t.txt", "hit")
    val files = WorkspaceFixtures.files(workspace)
    inCall(files.read(p("/d"), 0, 10, _)).left.value.message should include("directory")
    inCall(files.read(p("/bin"), 0, 10, _)).left.value.message should include("UTF-8")
    val found = inCall(files.grep("hit|x", p("/"), None, 10, _)).value
    found.items.map(_.path.value) shouldBe Vector("/t.txt")
    found.truncated shouldBe true
  }

  it should "skip an entry the workspace will not read as a file, such as a socket, in a search" in {
    val workspace = new FakeWorkspace()
    workspace.special("app.sock")
    workspace.seed("t.txt", "hit")
    val found = inCall(WorkspaceFixtures.files(workspace).grep("hit", p("/"), None, 10, _)).value
    found.items.map(_.path.value) shouldBe Vector("/t.txt")
    found.truncated shouldBe true
  }

  it should "stop a search, rather than skip entries, when the whole workspace is busy" in {
    val workspace = new FakeWorkspace()
    workspace.seed("t.txt", "hit")
    workspace.busy = true
    val refused = inCall(WorkspaceFixtures.files(workspace).grep("hit", p("/"), None, 10, _)).left.value
    refused shouldBe a[BackendRefused]
    refused.asInstanceOf[BackendRefused].path shouldBe None
    refused.message should include("in progress")
    // and a glob of one file, whose snapshot is refused for the whole workspace, says so rather than finding nothing
    val globbed = inCall(WorkspaceFixtures.files(workspace).find(p("/t.txt"), _ => true, 10, _)).left.value
    globbed.asInstanceOf[BackendRefused].path shouldBe None
  }

  it should "mark a listing the workspace cut as truncated, and keep search at its limit" in {
    val workspace = new FakeWorkspace(maxEntries = 2)
    (1 to 3).foreach(i => workspace.seed(s"f$i.txt", "x\nx"))
    val files   = WorkspaceFixtures.files(workspace)
    val listing = inCall(files.ls(p("/"), 10, _)).value
    listing.items.size shouldBe 2
    listing.truncated shouldBe true
    val wide = WorkspaceFixtures.files(new FakeWorkspace())
    val matches = inCall { ctx =>
      (1 to 3).foreach(i => wide.write(p(s"/f$i.txt"), "x\nx", ctx))
      wide.grep("x", p("/"), None, 3, ctx)
    }.value
    matches.items.size shouldBe 3
    matches.truncated shouldBe true
  }

  it should "refuse content over its bound, the root, and a write beneath a file, before or without writing" in {
    val workspace = new FakeWorkspace()
    workspace.seed("f", "x")
    val files = WorkspaceFileBackend("ws", workspace, maxFileBytes = 4).value
    inCall(files.write(p("/a"), "éééé", _)).left.value.message should include("larger")
    inCall(files.write(p("/"), "x", _)).left.value.message should include("root")
    inCall(files.delete(p("/"), _)).left.value.message should include("root")
    inCall(files.write(p("/f/a"), "x", _)).left.value shouldBe a[BackendRefused]
    workspace.exists("a") shouldBe false
  }

  it should "delete a file, then the directory it leaves empty" in {
    val workspace = new FakeWorkspace()
    workspace.seed("d/a.txt", "x")
    val files = WorkspaceFixtures.files(workspace)
    inCall(ctx => (files.delete(p("/d"), ctx), files.delete(p("/d/a.txt"), ctx), files.delete(p("/d"), ctx))) match {
      case (first, second, third) =>
        first.left.value.message should include("not empty")
        second.value._1 shouldBe FileDeleted(wasDirectory = false)
        third.value._1 shouldBe FileDeleted(wasDirectory = true)
    }
    workspace.exists("d") shouldBe false
  }

  "A workspace call" should "sort the runner's codes into refusals, a lost compare-and-set and failures" in {
    def failing(code: String): Result[Unit] =
      WorkspaceCalls.call("write", p("/a"))(throw new WorkspaceAgentException("no", code, None))
    failing("FILE_NOT_FOUND").left.value.asInstanceOf[BackendRefused].kind shouldBe BackendRefused.Kind.NotFound
    failing("NOT_A_DIRECTORY").left.value.asInstanceOf[BackendRefused].kind shouldBe BackendRefused.Kind.NotADirectory
    failing("PATH_READ_ONLY").left.value shouldBe a[BackendRefused]
    failing("HASH_MISMATCH").left.value shouldBe a[GraphError.EffectReplayRefused]
    BackendRefused.toolOutcome(failing("EXECUTION_FAILED").left.value) shouldBe a[ToolOutcome.Fatal]
    BackendRefused.toolOutcome(
      WorkspaceCalls.call("write", p("/a"))(throw new RuntimeException("socket")).left.value
    ) shouldBe a[ToolOutcome.Fatal]
    WorkspaceCalls.stateCheck("sha256:xyz").isLeft shouldBe true
    WorkspaceCalls.stateCheck("absent").value shouldBe org.llm4s.agent.graph.StateCheck.absent
  }

  "The sandbox runner" should "run in the workspace path, bound the output, and record a timeout as an outcome" in {
    val asked = new AtomicReference[(Option[String], Option[FiniteDuration], Option[Long])]()
    val workspace = new FakeWorkspace(exec = (_, dir, timeout, max) => {
      asked.set((dir, timeout, max))
      throw new WorkspaceAgentException("Command execution timed out after 1000ms", "TIMEOUT", None)
    })
    val sandbox = WorkspaceSandboxBackend("ws", workspace, maxOutputBytes = 100).value
    val result  = inCall(sandbox.execute("sleep 9", p("/src"), 1.second, _)).value
    result.exitCode shouldBe -1
    result.stderr should include("timed out")
    asked.get shouldBe ((Some("src"), Some(1.second), Some(100L)))
  }

  it should "give a policy refusal to the model and fail on an outcome it cannot know" in {
    def sandbox(code: String) = WorkspaceSandboxBackend(
      "ws",
      new FakeWorkspace(exec = (_, _, _, _) => throw new WorkspaceAgentException("refused", code, None))
    ).value
    BackendRefused.toolOutcome(
      inCall(sandbox("CONNECTION_CLOSED").execute("ls", p("/"), 1.second, _)).left.value
    ) shouldBe a[ToolOutcome.Fatal]
    inCall(sandbox("EXECUTABLE_NOT_ALLOWED").execute("curl x", p("/"), 1.second, _)).left.value shouldBe a[
      BackendRefused
    ]
    BackendRefused.toolOutcome(
      inCall(sandbox("EXECUTION_FAILED").execute("ls", p("/"), 1.second, _)).left.value
    ) shouldBe a[ToolOutcome.Fatal]
  }

  "The builders" should "refuse an id that cannot name a lock, and bounds out of range" in {
    val workspace = new FakeWorkspace()
    WorkspaceFileBackend("a/b", workspace).isLeft shouldBe true
    WorkspaceFileBackend("", workspace).isLeft shouldBe true
    WorkspaceFileBackend("ok", workspace, maxFileBytes = 0).isLeft shouldBe true
    WorkspaceSandboxBackend("ok", workspace, maxOutputBytes = 0).isLeft shouldBe true
    WorkspaceSandboxBackend("ok", workspace, maxOutputBytes = SandboxBackend.MaxOutputBytes + 1).isLeft shouldBe true
    WorkspaceFileBackend(
      "ws-1.a_b",
      workspace
    ).value.replaySupport shouldBe org.llm4s.agent.graph.ReplaySupport.Verifiable
  }
}
