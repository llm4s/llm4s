package org.llm4s.workspace

import org.llm4s.agent.{ Agent, AgentResult, AgentStatus }
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.middleware.PermissionRule
import org.llm4s.agent.graph.sqlite.SqliteCheckpointer
import org.llm4s.agent.graph.tool.{ LockKey, Operation }
import org.llm4s.agent.harness._
import org.llm4s.agent.harness.workspace.{ WorkspaceFileBackend, WorkspaceSandboxRunner }
import org.llm4s.it.Tier
import org.llm4s.it.tags.Workspace
import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result
import org.scalatest.{ BeforeAndAfterAll, EitherValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.{ Files, Path }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * Stage 3's coding exit example (#1916, design §4.38) against a real workspace-runner container: the harness over
 * `WorkspaceFileBackend` and the workspace's sandbox, under rules that allow writes under `/src` and ask on `execute`,
 * checkpointed to SQLite. The run suspends at `execute`; a fresh runtime over the same file resumes it approved; the
 * command runs exactly once in the container, and the edited files are in the workspace.
 *
 * It builds the profile of `CodingWorkspace` (`modules/samples`) itself: `modules/it` cannot depend on the samples,
 * whose `application.conf` would reach every integration suite. `CodingWorkspaceAcceptanceSpec` there runs the whole
 * example, on an in-memory workspace, on every PR.
 *
 * Needs Docker plus the locally built workspace-runner image (`sbt workspaceRunner/Docker/publishLocal`): run it with
 * `sbt testWorkspace`. The model is scripted.
 */
@Workspace
class CodingWorkspaceIntegrationSpec extends AnyFlatSpec with Matchers with EitherValues with BeforeAndAfterAll {

  private val EnableDockerEnvVar = "LLM4S_DOCKER_TESTS"

  // a host port of its own, so this suite cannot clash with the other workspace suites (8080, 8081)
  private val HostPort = 8083

  private val tempDir: Path                     = Files.createTempDirectory("coding-workspace-integration")
  private val root: Path                        = Files.createDirectories(tempDir.resolve("workspace"))
  private var workspace: ContainerisedWorkspace = _

  private val Broken  = "object Greeter {\n  def greeting(name: String): String = s\"Helo, $name\"\n}\n"
  private val Fixed   = Broken.replace("Helo,", "Hello,")
  private val Test    = "object GreeterSpec { assert(Greeter.greeting(\"world\") == \"Hello, world\") }\n"
  private val Check   = "grep -c Hello src/Greeter.scala"
  private val Query   = "Fix the misspelt greeting in /src/Greeter.scala, add a test for it, and run the check."
  private val Answer  = "Fixed, and the check passed."
  private val thread  = ThreadId("fix-greeting")
  private val runs    = new AtomicInteger()
  private val outputs = new java.util.concurrent.CopyOnWriteArrayList[ExecResult]()

  private def isDockerAvailable: Boolean =
    sys.env.get(EnableDockerEnvVar).exists(_.equalsIgnoreCase("true")) &&
      Try(Runtime.getRuntime.exec(Array("docker", "--version")).waitFor() == 0).getOrElse(false)

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (isDockerAvailable) {
      Files.createDirectories(root.resolve("src"))
      Files.writeString(root.resolve("src/Greeter.scala"), Broken)
      workspace = new ContainerisedWorkspace(root.toString, ContainerisedWorkspaceTest.image, HostPort)
      if (!workspace.startContainer()) fail("Failed to start the workspace container")
      Thread.sleep(2000)
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
    if (workspace != null) workspace.stopContainer()
    Try {
      def deleteRecursively(f: File): Unit = {
        if (f.isDirectory) f.listFiles().foreach(deleteRecursively)
        f.delete()
      }
      deleteRecursively(tempDir.toFile)
    }
    ()
  }

  /** The workspace's runner, counting each command the container runs and keeping its result. */
  final private class Counting(underlying: SandboxRunner) extends SandboxRunner {
    def locks(workdir: VPath): Set[LockKey] = underlying.locks(workdir)
    def run(command: String, workdir: VPath, timeout: FiniteDuration, maxOutputBytes: Int, ctx: FileContext) = {
      runs.incrementAndGet()
      underlying.run(command, workdir, timeout, maxOutputBytes, ctx).map { r =>
        outputs.add(r); r
      }
    }
  }

  /** The scripted model: the reply to the last message of the conversation. */
  private def model(): ScriptedLLMClient = ScriptedLLMClient.respondingTo(Function.unlift { (c: Conversation) =>
    def call(id: String, name: String, args: (String, ujson.Value)*) = ToolCall(id, name, ujson.Obj.from(args))
    c.messages.lastOption.collect {
      case u: UserMessage if u.content == Query =>
        Reply.toolCalls(
          call(
            "plan",
            "write_todos",
            "todos" -> ujson.Arr(ujson.Obj("content" -> "Fix and check", "status" -> "in_progress"))
          )
        )
      case t: ToolMessage if t.toolCallId == "plan" =>
        Reply.toolCalls(
          call("fix", "edit_file", "path" -> "/src/Greeter.scala", "old_string" -> "Helo,", "new_string" -> "Hello,"),
          call("test", "write_file", "path"   -> "/src/GreeterSpec.scala", "content" -> Test),
          call("readme", "write_file", "path" -> "/README.md", "content"             -> "Fixed.")
        )
      case t: ToolMessage if t.toolCallId == "readme" => Reply.toolCalls(call("check", "execute", "command" -> Check))
      case t: ToolMessage if t.toolCallId == "check"  => Reply.text(Answer)
    }
  })

  private def agent(store: Checkpointer): Result[Agent] =
    for {
      files   <- WorkspaceFileBackend("coding-it", workspace)
      runner  <- WorkspaceSandboxRunner("coding-it", workspace)
      notes   <- VPath("/notes")
      backend <- CompositeBackend(files, notes -> StateFileBackend())
      agent <- Agent
        .builder("coder", model())
        .withHarness(
          HarnessProfile()
            .withBackend(backend)
            .withSandbox(SandboxBackend(new Counting(runner)))
            .withTodos(TodoConfig())
            .withPermissions(
              Vector(
                PermissionRule.allow(Operation.Read, "/**"),
                PermissionRule.allow(Operation.Write, "/src/**"),
                PermissionRule.allow(Operation.Write, "/notes/**"),
                PermissionRule.ask(Operation.Execute, "**"),
                PermissionRule.allow(Operation.Invoke, "write_todos")
              )
            )
        )
        .withRuntime(GraphRuntime(store))
        .build()
    } yield agent

  private def approvals(result: AgentResult): Map[InterruptId, ujson.Value] = result.status match {
    case AgentStatus.Suspended(approvals, _, _, _, _) => approvals.map((id, _) => result.approve(id)).toMap
    case other                                        => fail(s"not suspended: $other")
  }

  "The coding example" should "suspend at execute, resume approved in a fresh runtime, and run the command once" in {
    Tier.require(isDockerAvailable, s"Docker not available or $EnableDockerEnvVar!=true")
    val db = tempDir.resolve("coding.db")

    val first     = SqliteCheckpointer.open(db).value
    val suspended = agent(first).flatMap(_.run(thread, Query)).value
    first.close()
    val asked = approvals(suspended)
    asked should have size 1
    runs.get shouldBe 0
    // the batch's edits are in the container's workspace; the write outside /src was denied
    Files.readString(root.resolve("src/Greeter.scala")) shouldBe Fixed
    Files.readString(root.resolve("src/GreeterSpec.scala")) shouldBe Test
    Files.exists(root.resolve("README.md")) shouldBe false
    suspended.messages.collect { case t: ToolMessage if t.toolCallId == "readme" => t.content }.mkString should
      include("Denied: write /README.md")

    val second = SqliteCheckpointer.open(db).value
    val done   = agent(second).flatMap(_.resume(thread, asked)).value
    second.close()
    done.answer shouldBe Some(Answer)
    runs.get shouldBe 1
    // the container ran the check on the fixed file: one match, exit code 0
    outputs.get(0).exitCode shouldBe 0
    outputs.get(0).stdout.trim shouldBe "1"
    Files.readString(root.resolve("src/Greeter.scala")) shouldBe Fixed
  }
}
