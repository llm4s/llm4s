package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.{ GraphError, GraphRuntime, ThreadId }
import org.llm4s.agent.graph.middleware.{ ApprovalMiddleware, MiddlewareId }
import org.llm4s.agent.graph.tool.ToolOutcome
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The graph version is the structural fingerprint of the agent family: what changes it, and what a changed one refuses. */
class AgentFingerprintSpec extends AnyFlatSpec with Matchers {

  private val client = ScriptedLLMClient.of()

  private val echo  = SpecTools.tool("echo")((a, _) => ToolOutcome.Success(ujson.Str(a.text)))
  private val other = SpecTools.tool("other")((a, _) => ToolOutcome.Success(ujson.Str(a.text)))

  private def base: AgentBuilder =
    Agent
      .builder("root", client)
      .withSystemPrompt("prompt")
      .withTools(SpecTools.set(echo))
      .withMiddleware(new ApprovalMiddleware(_ => None))
      .withHandoffs(Handoff.to("helper", Agent.builder("helper", client)))

  private def version(builder: AgentBuilder): String = built(builder).loop.graph.version

  "The graph version" should "be the same for the same builders across two builds" in {
    version(base) shouldBe version(base)
  }

  it should "change with the system prompt" in {
    version(base.withSystemPrompt("another")) should not be version(base)
  }

  it should "change with a tool" in {
    val changed = Agent
      .builder("root", client)
      .withSystemPrompt("prompt")
      .withTools(SpecTools.set(echo, other))
      .withMiddleware(new ApprovalMiddleware(_ => None))
      .withHandoffs(Handoff.to("helper", Agent.builder("helper", client)))
    version(changed) should not be version(base)
  }

  it should "change with a middleware id" in {
    val changed = Agent
      .builder("root", client)
      .withSystemPrompt("prompt")
      .withTools(SpecTools.set(echo))
      .withMiddleware(new ApprovalMiddleware(_ => None, MiddlewareId("review")))
      .withHandoffs(Handoff.to("helper", Agent.builder("helper", client)))
    version(changed) should not be version(base)
  }

  /** A middleware contributing one tool, `lookup`, whose description is `description`. */
  final private class ToolMiddleware(description: String) extends org.llm4s.agent.graph.middleware.AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("lookup-provider")
    private val lookup = org.llm4s.agent.graph.tool.AgentTool(
      org.llm4s.agent.graph.tool.AgentToolSpec[SpecTools.Text](
        "lookup",
        description,
        org.llm4s.toolapi.Schema
          .`object`[SpecTools.Text]("lookup")
          .withRequiredField("text", org.llm4s.toolapi.Schema.string("Text"))
      )
    )((a, _) => ToolOutcome.Success(ujson.Str(a.text)))
    override def tools: Vector[org.llm4s.agent.graph.tool.AgentTool[?]] = Vector(lookup)
  }

  it should "change with a middleware-contributed tool's schema, the middleware id unchanged" in {
    def withLookup(description: String) =
      Agent.builder("root", client).withMiddleware(new ToolMiddleware(description))
    version(withLookup("Looks things up")) shouldBe version(withLookup("Looks things up"))
    version(withLookup("Looks other things up")) should not be version(withLookup("Looks things up"))
  }

  it should "change with a handoff" in {
    val changed = Agent
      .builder("root", client)
      .withSystemPrompt("prompt")
      .withTools(SpecTools.set(echo))
      .withMiddleware(new ApprovalMiddleware(_ => None))
    version(changed) should not be version(base)
  }

  "A thread that handed off" should "be refused by the root rebuilt without that handoff, not mis-routed" in {
    val runtime = GraphRuntime.inMemory()
    val thread  = ThreadId("handed-off")
    val rootClient = ScriptedLLMClient.of(
      CompletionFixture.withMessage(
        AssistantMessage(None, Seq(ToolCall("h1", "handoff_to_helper", ujson.Obj("reason" -> "needs help"))))
      )
    )
    val helperClient = ScriptedLLMClient.of(CompletionFixture.simple("helped"), CompletionFixture.simple("never"))
    val withHandoff = built(
      Agent
        .builder("root", rootClient)
        .withHandoffs(Handoff.to("helper", Agent.builder("helper", helperClient)))
        .withRuntime(runtime)
    )
    withHandoff.run(thread, "q1").value.activeAgent.value shouldBe "helper"

    val without = built(Agent.builder("root", rootClient).withRuntime(runtime))
    val refused = without.run(thread, "q2")

    refused.error shouldBe a[GraphError.RestoreRejected]
    helperClient.callCount shouldBe 1
    rootClient.callCount shouldBe 1
  }

  private def fingerprint(tool: org.llm4s.agent.graph.tool.AgentTool[?]): String =
    built(Agent.builder("root", client).withTools(SpecTools.set(tool))).loop.graph.fingerprint

  private def echoWith(spec: org.llm4s.agent.graph.tool.AgentToolSpec[SpecTools.Text]) =
    org.llm4s.agent.graph.tool.AgentTool(spec)((a, _) => ToolOutcome.Success(ujson.Str(a.text)))

  "The structural fingerprint" should "not change with timeouts or resources, nor without locks: no tool-queue key" in {
    import scala.concurrent.duration._
    import org.llm4s.agent.graph.tool.{ Operation, ToolResource }
    val plain = fingerprint(echoWith(SpecTools.spec("echo")))
    fingerprint(
      echoWith(
        SpecTools.spec("echo").withTimeout(1.second).withResources(t => Vector(ToolResource(Operation.Read, t.text)))
      )
    ) shouldBe plain
    // a tool with locks adds the loop's tool-queue key, a change of structure
    fingerprint(echoWith(SpecTools.spec("echo").withLocks(_ => Set.empty))) should not be plain
  }

  "A thread checkpointed without locks" should "resume in a rebuild with timeouts, and be refused by one with locks" in {
    import scala.concurrent.duration._
    val runtime = GraphRuntime.inMemory()
    val thread  = ThreadId("unlocked")
    def agent(spec: org.llm4s.agent.graph.tool.AgentToolSpec[SpecTools.Text], replies: Completion*) =
      built(
        Agent
          .builder("root", ScriptedLLMClient.of(replies*))
          .withTools(SpecTools.set(echoWith(spec)))
          .withMiddleware(new ApprovalMiddleware(_ => Some("review")))
          .withRuntime(runtime)
      )
    val parked =
      agent(SpecTools.spec("echo"), SpecTools.calling(SpecTools.call("c1", "echo"))).run(thread, "go").value
    val approve = parked.status match {
      case AgentStatus.Suspended(approvals, _, _, _) => parked.approve(approvals.head._1)
      case other                                     => fail(s"expected Suspended, got $other")
    }

    agent(SpecTools.spec("echo").withLocks(_ => Set.empty)).resume(thread, Map(approve)).error shouldBe
      a[GraphError.RestoreRejected]
    agent(SpecTools.spec("echo").withTimeout(5.seconds), CompletionFixture.simple("done"))
      .resume(thread, Map(approve))
      .value
      .answer shouldBe Some("done")
  }

  "The graph version" should "change when any tool adopts locks, even where the queue key is already registered" in {
    val lockedEcho = echoWith(SpecTools.spec("echo").withLocks(_ => Set.empty))
    val lockedOther = org.llm4s.agent.graph.tool.AgentTool(SpecTools.spec("other").withLocks(_ => Set.empty))((a, _) =>
      ToolOutcome.Success(ujson.Str(a.text))
    )
    def agentWith(tools: org.llm4s.agent.graph.tool.AgentTool[?]*) =
      Agent.builder("root", client).withTools(SpecTools.set(tools*))
    // a second tool of the same agent adopting locks
    version(agentWith(lockedEcho, lockedOther)) should not be version(agentWith(lockedEcho, other))
    // an agent adopting locks while another agent of the family already has them
    def family(root: org.llm4s.agent.graph.tool.AgentTool[?]) =
      agentWith(root).withHandoffs(
        Handoff.to("helper", Agent.builder("helper", client).withTools(SpecTools.set(lockedOther)))
      )
    version(family(lockedEcho)) should not be version(family(echo))
    // timeouts and resources are not part of it
    import scala.concurrent.duration._
    version(agentWith(echoWith(SpecTools.spec("echo").withTimeout(1.second)))) shouldBe version(agentWith(echo))
  }

  "A thread parked in a batch" should "be refused once another tool of its agent adopts locks" in {
    val runtime    = GraphRuntime.inMemory()
    val thread     = ThreadId("adopting")
    val lockedEcho = echoWith(SpecTools.spec("echo").withLocks(_ => Set.empty))
    def agent(otherTool: org.llm4s.agent.graph.tool.AgentTool[?], replies: Completion*) =
      built(
        Agent
          .builder("root", ScriptedLLMClient.of(replies*))
          .withTools(SpecTools.set(lockedEcho, otherTool))
          .withMiddleware(new ApprovalMiddleware(_ => Some("review")))
          .withRuntime(runtime)
      )
    val parked = agent(other, SpecTools.calling(SpecTools.call("c1", "other"), SpecTools.call("c2", "echo")))
      .run(thread, "go")
      .value
    val approvals = parked.status match {
      case AgentStatus.Suspended(pending, _, _, _) => pending.map(p => parked.approve(p._1)).toMap
      case other                                   => fail(s"expected Suspended, got $other")
    }
    val lockedOther = org.llm4s.agent.graph.tool.AgentTool(SpecTools.spec("other").withLocks(_ => Set.empty))((a, _) =>
      ToolOutcome.Success(ujson.Str(a.text))
    )
    agent(lockedOther).resume(thread, approvals).error shouldBe a[GraphError.RestoreRejected]
  }
}
