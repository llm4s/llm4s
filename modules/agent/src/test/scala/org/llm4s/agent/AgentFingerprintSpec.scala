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

  "The graph version" should "not cover timeouts, resources or lock functions: a batch keeps its recorded schedule" in {
    import scala.concurrent.duration._
    def agentWith(tool: org.llm4s.agent.graph.tool.AgentTool[?]) =
      Agent.builder("root", client).withTools(SpecTools.set(tool))
    version(agentWith(echoWith(SpecTools.spec("echo").withTimeout(1.second)))) shouldBe version(agentWith(echo))
    version(agentWith(echoWith(SpecTools.spec("echo").withLocks(_ => Set.empty)))) shouldBe version(agentWith(echo))
  }

  /** `step` writes the counter (read from its snapshot, plus one), locked by `policy`; the root asks approval for c1. */
  private def stepAgent(
    runtime: GraphRuntime,
    policy: SpecTools.Text => Set[org.llm4s.agent.graph.tool.LockKey],
    replies: Completion*
  ) = {
    val counter = org.llm4s.agent.graph.StateKey.replace[Int]("counter", 0)
    val step = org.llm4s.agent.graph.tool.AgentTool(SpecTools.spec("step").withLocks(policy), writes = Set(counter)) {
      (_, context) =>
        val next = context.state.get(counter).fold(e => fail(e.message), identity) + 1
        ToolOutcome.Success(ujson.Str(next.toString), org.llm4s.agent.graph.StateUpdate.update(counter, next))
    }
    built(
      Agent
        .builder("root", ScriptedLLMClient.of(replies*))
        .withTools(SpecTools.set(step))
        .withMiddleware(new ApprovalMiddleware(r => Option.when(r.call.id == "c1")("review")))
        .withRuntime(runtime)
    )
  }

  private def approveAll(parked: AgentResult) = parked.status match {
    case AgentStatus.Suspended(pending, _, _, _) => pending.map(p => parked.approve(p._1)).toMap
    case other                                   => fail(s"expected Suspended, got $other")
  }

  private def stepResults(result: AgentResult) = result.messages.collect { case t: ToolMessage => t.content }.toVector

  "A batch parked across a rebuild" should "keep the lock schedule it was made with when the lock functions change" in {
    import org.llm4s.agent.graph.tool.LockKey
    val runtime = GraphRuntime.inMemory()
    val thread  = ThreadId("policy-change")
    val calling = SpecTools.calling(
      SpecTools.call("c1", "step", "a"),
      SpecTools.call("c2", "step", "a"),
      SpecTools.call("c3", "step", "a")
    )
    // scheduled with every call on lock "a": c2 and c3 wait in turn behind c1, which waits for approval
    val parked = stepAgent(runtime, t => Set(LockKey(t.text)), calling).run(thread, "go").value
    // rebuilt with lock functions that would let c2 and c3 run side by side
    val done =
      stepAgent(runtime, _ => Set(LockKey(java.util.UUID.randomUUID().toString)), CompletionFixture.simple("done"))
        .resume(thread, approveAll(parked))
        .value
    done.answer shouldBe Some("done")
    // c2 and c3 still ran one after the other, each seeing the last write; recomputed, they would share a round
    stepResults(done) shouldBe Vector("1", "2", "3")
  }

  it should "not reorder calls a changed lock function would now make conflict" in {
    import org.llm4s.agent.graph.tool.LockKey
    val runtime = GraphRuntime.inMemory()
    val thread  = ThreadId("policy-merge")
    val calling = SpecTools.calling(SpecTools.call("c1", "step", "a"), SpecTools.call("c2", "step", "b"))
    // scheduled apart: c2 commits beside c1, which waits for approval
    val parked = stepAgent(runtime, t => Set(LockKey(t.text)), calling).run(thread, "go").value
    // rebuilt so both would share one lock: c1 is not refused or reordered, it runs as scheduled
    val done = stepAgent(runtime, _ => Set(LockKey("shared")), CompletionFixture.simple("done"))
      .resume(thread, approveAll(parked))
      .value
    done.answer shouldBe Some("done")
    stepResults(done) shouldBe Vector("2", "1") // c2 ran first, as its schedule allowed; c1 saw its write
  }
}
