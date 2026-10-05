package org.llm4s.workspace

import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.it.Tier
import org.llm4s.it.tags.Workspace
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ ToolRegistry, WorkspaceTools }
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files
import scala.util.Try

/**
 * An [[Agent]] driving the real workspace tools ([[WorkspaceTools.createDefaultWorkspaceTools]])
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
      toolSeq <- WorkspaceTools.createDefaultWorkspaceTools(workspace)
      agent <- Agent
        .builder("workspace", new ScriptedToolCallLLM(script))
        .withTools(new ToolRegistry(toolSeq))
        .withMaxSteps(10)
        .build()
      done <- agent.run("go")
    } yield done).fold(e => fail(s"agent run failed: ${e.formatted}"), identity)
    (result.status, result.messages.collect { case m: ToolMessage => m })
  }

  private def resultOf(m: ToolMessage): ujson.Value = ujson.read(m.content)

  "An Agent with the workspace tools" should "run a command in the container and receive its stdout and exit code" in {
    val (status, tms) = runScript("execute_command" -> ujson.Obj("command" -> "echo hello_from_workspace"))

    status shouldBe a[AgentStatus.Completed]
    tms should have size 1
    val r = resultOf(tms.head)
    r("exit_code").num.toInt shouldBe 0
    r("stdout").str.trim shouldBe "hello_from_workspace"
  }

  it should "report the real non-zero exit code of a failing command" in {
    val (_, tms) = runScript("execute_command" -> ujson.Obj("command" -> "exit 42"))

    tms should have size 1
    resultOf(tms.head)("exit_code").num.toInt shouldBe 42
  }

  it should "capture stderr separately from stdout" in {
    val (_, tms) = runScript("execute_command" -> ujson.Obj("command" -> "echo out_marker; echo err_marker 1>&2"))

    val r = resultOf(tms.head)
    r("stdout").str should include("out_marker")
    (r("stdout").str should not).include("err_marker")
    r("stderr").str should include("err_marker")
  }

  it should "write a file in one turn and read the same content back in the next" in {
    val (status, tms) = runScript(
      "write_file" -> ujson.Obj("path" -> "agent_roundtrip.txt", "content" -> "round-trip payload"),
      "read_file"  -> ujson.Obj("path" -> "agent_roundtrip.txt")
    )

    status shouldBe a[AgentStatus.Completed]
    tms should have size 2
    resultOf(tms(1))("content").str shouldBe "round-trip payload"
    // The write really landed on the mounted host directory, not just in a fake.
    new File(tempDir, "agent_roundtrip.txt").exists() shouldBe true
  }

  it should "see files it wrote when the next command runs in the container" in {
    val (_, tms) = runScript(
      "write_file"      -> ujson.Obj("path" -> "visible.txt", "content" -> "42"),
      "execute_command" -> ujson.Obj("command" -> "cat visible.txt")
    )

    tms should have size 2
    resultOf(tms(1))("stdout").str.trim shouldBe "42"
  }

  it should "surface a tool error for a file that does not exist" in {
    val (status, tms) = runScript("read_file" -> ujson.Obj("path" -> "no_such_file.txt"))

    status shouldBe a[AgentStatus.Completed]
    tms should have size 1
    // A failed read must not look like a successful one: there is no "content" field.
    Try(resultOf(tms.head)).toOption.flatMap(_.objOpt).forall(o => !o.value.contains("content")) shouldBe true
  }

  it should "return control within the deadline for a command that exceeds its timeout" in {
    val start = System.currentTimeMillis()
    val (_, tms) =
      runScript("execute_command" -> ujson.Obj("command" -> "sleep 60", "timeout" -> 2))
    val elapsedMs = System.currentTimeMillis() - start

    elapsedMs should be < 30000L
    tms should have size 1
    // Whether surfaced as a tool error or a non-zero exit code, it must not read as success.
    val succeeded = Try(resultOf(tms.head)("exit_code").num.toInt).toOption.contains(0)
    succeeded shouldBe false
  }
}
