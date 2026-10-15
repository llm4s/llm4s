package org.llm4s.workspace

import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.it.Tier
import org.llm4s.it.tags.Workspace
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.agent.graph.tool.ToolSet
import org.llm4s.agent.harness.{ ExecuteTool, FileTools }
import org.llm4s.agent.harness.workspace.{ WorkspaceFileBackend, WorkspaceSandboxBackend }
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files
import scala.util.Try

/**
 * An [[Agent]] driving the harness tools over a workspace (`FileTools` over `WorkspaceFileBackend`, `ExecuteTool` over `WorkspaceSandboxBackend`)
 * against a real workspace-runner container: agent -> tool -> `ContainerisedWorkspace` ->
 * container -> `ToolMessage`.
 *
 * Needs Docker plus the locally built workspace-runner image (`sbt workspaceRunner/Docker/publishLocal`),
 * i.e. the Workspace tier: run it with `sbt testWorkspace`. The LLM is scripted - it requests
 * fixed tool calls - so every assertion is on what the container computed, never on model text.
 */
@Workspace
class WorkspaceAgentIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val EnableDockerEnvVar = "LLM4S_DOCKER_TESTS"

  // A different host port from ContainerisedWorkspaceTest (8080) so the two suites cannot clash.
  private val HostPort = 8081

  private val tempDir                           = Files.createTempDirectory("workspace-agent-integration-test").toString
  private var workspace: ContainerisedWorkspace = _

  private def isDockerAvailable: Boolean =
    sys.env.get(EnableDockerEnvVar).exists(_.equalsIgnoreCase("true")) &&
      Try(Runtime.getRuntime.exec(Array("docker", "--version")).waitFor() == 0).getOrElse(false)

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (isDockerAvailable) {
      workspace = new ContainerisedWorkspace(tempDir, ContainerisedWorkspaceTest.image, HostPort)
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
      deleteRecursively(new File(tempDir))
    }
    ()
  }

  /** Requests the scripted tool calls one per turn, then answers with plain text. */
  private class ScriptedToolCallLLM(script: Seq[(String, ujson.Value)]) extends LLMClient {
    private var turn = 0

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      turn += 1
      val toolCalls =
        script.lift(turn - 1).map { case (name, args) => ToolCall(s"call-$turn", name, args) }.toList
      val message =
        if (toolCalls.nonEmpty) AssistantMessage(contentOpt = None, toolCalls = toolCalls)
        else AssistantMessage("done")
      Right(
        Completion(
          id = s"mock-$turn",
          created = 0L,
          content = message.content,
          model = "mock-model",
          message = message,
          toolCalls = toolCalls,
          usage = None
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  /** Runs the agent over the real workspace tools and returns the final state's tool messages. */
  private def runScript(script: (String, ujson.Value)*): (AgentStatus, Seq[ToolMessage]) = {
    Tier.require(isDockerAvailable, s"Docker not available or $EnableDockerEnvVar!=true")
    val result = (for {
      files   <- WorkspaceFileBackend("it-agent", workspace)
      sandbox <- WorkspaceSandboxBackend("it-agent", workspace)
      tools   <- ToolSet.of((FileTools(files) :+ ExecuteTool(sandbox))*)
      agent <- Agent
        .builder("workspace", new ScriptedToolCallLLM(script))
        .withTools(tools)
        .withMaxSteps(10)
        .build()
      done <- agent.run("go")
    } yield done).fold(e => fail(s"agent run failed: ${e.formatted}"), identity)
    (result.status, result.messages.collect { case m: ToolMessage => m })
  }

  /** An `execute` result's parts: `exit code: N`, then `stdout:` and `stderr:` sections. */
  final private case class Executed(exitCode: Int, stdout: String, stderr: String)

  private def executed(m: ToolMessage): Executed = {
    val Pattern = """(?s)exit code: (-?\d+)\nstdout:\n(.*)\nstderr:\n(.*?)(\n\(output cut.*)?""".r
    m.content match {
      case Pattern(code, out, err, _) =>
        def text(s: String) = if (s == "(empty)") "" else s
        Executed(code.toInt, text(out), text(err))
      case other => fail(s"not an execute result: $other")
    }
  }

  "An Agent with the workspace tools" should "run a command in the container and receive its stdout and exit code" in {
    val (status, tms) = runScript("execute" -> ujson.Obj("command" -> "echo hello_from_workspace"))

    status shouldBe a[AgentStatus.Completed]
    tms should have size 1
    val r = executed(tms.head)
    r.exitCode shouldBe 0
    r.stdout.trim shouldBe "hello_from_workspace"
  }

  // Commands are one program and its arguments, run without a shell, and only allowlisted programs run (#1756), so
  // `exit 42`, `;`, `1>&2` and `sleep` are gone. `WorkspaceCommandsSpec` in workspaceRunner runs these exact command
  // strings through the runner's WebSocket executor on every PR, pinning what this suite expects of them.

  it should "report the real non-zero exit code of a failing command" in {
    val (_, tms) = runScript(
      "write_file" -> ujson.Obj("path" -> "/exit_code.txt", "content" -> "alpha\n"),
      "execute"    -> ujson.Obj("command" -> "grep zzz exit_code.txt")
    )

    tms should have size 2
    // grep exits 1 when nothing matches.
    executed(tms(1)).exitCode shouldBe 1
  }

  it should "capture stderr separately from stdout" in {
    val (_, tms) = runScript(
      "write_file" -> ujson.Obj("path" -> "/out_marker.txt", "content" -> "out_marker\n"),
      "execute"    -> ujson.Obj("command" -> "cat out_marker.txt err_marker.txt")
    )

    tms should have size 2
    // cat prints the file that exists to stdout and its complaint about the missing one to stderr.
    val r = executed(tms(1))
    r.exitCode shouldBe 1
    r.stdout should include("out_marker")
    (r.stdout should not).include("err_marker")
    r.stderr should include("err_marker")
  }

  it should "write a file in one turn and read the same content back in the next" in {
    val (status, tms) = runScript(
      "write_file" -> ujson.Obj("path" -> "/agent_roundtrip.txt", "content" -> "round-trip payload"),
      "read_file"  -> ujson.Obj("path" -> "/agent_roundtrip.txt")
    )

    status shouldBe a[AgentStatus.Completed]
    tms should have size 2
    tms(1).content should include("round-trip payload")
    // The write really landed on the mounted host directory, not just in a fake.
    new File(tempDir, "agent_roundtrip.txt").exists() shouldBe true
  }

  it should "see files it wrote when the next command runs in the container" in {
    val (_, tms) = runScript(
      "write_file" -> ujson.Obj("path" -> "/visible.txt", "content" -> "42"),
      "execute"    -> ujson.Obj("command" -> "cat visible.txt")
    )

    tms should have size 2
    executed(tms(1)).stdout.trim shouldBe "42"
  }

  it should "surface a tool error for a file that does not exist" in {
    val (status, tms) = runScript("read_file" -> ujson.Obj("path" -> "/no_such_file.txt"))

    status shouldBe a[AgentStatus.Completed]
    tms should have size 1
    // A failed read must not look like a successful one: it is an error result, with no numbered lines.
    tms.head.content should include("not found")
    (tms.head.content should not).include("     1\t")
  }

  it should "return control within the deadline for a command that exceeds its timeout" in {
    val start = System.currentTimeMillis()
    // `tail -f` never ends by itself, so only the 2 s timeout stops it.
    val (_, tms) = runScript(
      "write_file" -> ujson.Obj("path" -> "/timeout.txt", "content" -> "waiting\n"),
      "execute"    -> ujson.Obj("command" -> "tail -f timeout.txt", "timeout_seconds" -> 2)
    )
    val elapsedMs = System.currentTimeMillis() - start

    elapsedMs should be < 30000L
    tms should have size 2
    // The runner reports a command it stopped at its timeout with exit code -1.
    val r = executed(tms(1))
    r.exitCode shouldBe -1
    r.stdout should include("waiting")
  }
}
