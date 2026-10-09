package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph.{ BreakpointPhase, GraphError, GraphRuntime, InterruptId, NodeId, RunConfig, ThreadId }
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.tool.ToolOutcome
import org.llm4s.agent.graph.toolloop.{ BreakpointRequest, ToolLoop }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import scala.jdk.CollectionConverters._

/** Static breakpoints on an agent's nodes (#1704): `withInterruptBefore`/`withInterruptAfter` and a run's `RunConfig`. */
class AgentBreakpointSpec extends AnyFlatSpec with Matchers {

  private val ran = new CopyOnWriteArrayList[String]()

  private val deploy = SpecTools.tool("deploy") { (a, _) =>
    ran.add(a.text)
    ToolOutcome.Success(ujson.Str(s"deployed ${a.text}"))
  }

  private def breakpointsOf(result: AgentResult): Vector[(InterruptId, BreakpointRequest)] = result.status match {
    case AgentStatus.Suspended(approvals, questions, asked, held) =>
      (approvals ++ questions ++ asked).size shouldBe 0
      held
    case other => fail(s"expected Suspended, got $other")
  }

  private def builder(client: ScriptedLLMClient): AgentBuilder =
    Agent.builder("assistant", client).withTools(SpecTools.set(deploy))

  private val toolNode   = NodeId("assistant/call-tool")
  private val modelNode  = NodeId("assistant/model")
  private val finishNode = NodeId("assistant/finish")

  "A breakpoint before tool calls" should "hold each call on its own, answered one at a time" in {
    ran.clear()
    val client = ScriptedLLMClient.of(
      calling(call("c1", "deploy", "one"), call("c2", "deploy", "two")),
      CompletionFixture.simple("both deployed")
    )
    val agent  = built(builder(client).withInterruptBefore(AgentNode.Tool))
    val parked = agent.run("deploy both").value
    val held   = breakpointsOf(parked)
    held.map(_._2) shouldBe Vector(
      BreakpointRequest(toolNode, BreakpointPhase.Before, Some(call("c1", "deploy", "one"))),
      BreakpointRequest(toolNode, BreakpointPhase.Before, Some(call("c2", "deploy", "two")))
    )
    ran.size shouldBe 0

    val partly = agent.resume(parked.threadId, Map(parked.proceed(held(1)._1))).value
    breakpointsOf(partly).map(_._1) shouldBe Vector(held.head._1)
    ran.asScala.toVector shouldBe Vector("two")
    client.callCount shouldBe 1 // the model waits for the whole batch

    val done = agent.resume(parked.threadId, Map(partly.proceed(held.head._1))).value
    done.answer shouldBe Some("both deployed")
    ran.asScala.toVector shouldBe Vector("two", "one")
  }

  "A breakpoint after the model" should "store its message and hold its tool calls until answered" in {
    ran.clear()
    val client = ScriptedLLMClient.of(calling(call("c1", "deploy", "prod")), CompletionFixture.simple("done"))
    val agent  = built(builder(client).withInterruptAfter(AgentNode.Model))
    val parked = agent.run("deploy").value
    val held   = breakpointsOf(parked)
    held.map(_._2) shouldBe Vector(BreakpointRequest(modelNode, BreakpointPhase.After, None))
    parked.messages.last shouldBe AssistantMessage(None, Seq(call("c1", "deploy", "prod")))
    ran.size shouldBe 0

    // the model's second message is held too, before its answer is guarded and the turn ends
    val second = agent.resume(parked.threadId, Map(parked.proceed(held.head._1))).value
    ran.asScala.toVector shouldBe Vector("prod")
    val again = breakpointsOf(second)
    second.messages.last shouldBe AssistantMessage("done", Seq.empty)

    agent.resume(parked.threadId, Map(second.proceed(again.head._1))).value.answer shouldBe Some("done")
  }

  "A breakpoint before the final answer" should "hold the stored answer before its afterAgent hooks run" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("the answer"))
    val agent  = built(Agent.builder("assistant", client).withInterruptBefore(AgentNode.Finish))
    val parked = agent.run("question").value
    breakpointsOf(parked).map(_._2) shouldBe Vector(BreakpointRequest(finishNode, BreakpointPhase.Before, None))
    parked.answer shouldBe None
    parked.messages.last.content shouldBe "the answer"
    agent.resume(parked.threadId, Map(parked.proceed(breakpointsOf(parked).head._1))).value.answer shouldBe
      Some("the answer")
  }

  "A run's own RunConfig" should "add breakpoints to the agent's, by node id" in {
    val client   = ScriptedLLMClient.of(CompletionFixture.simple("hi"))
    val agent    = plain(client)
    val threadId = ThreadId("bp-config")
    val parked   = agent.run(threadId, "hello", RunConfig(interruptBefore = Set(modelNode))).value
    breakpointsOf(parked).map(_._2.node) shouldBe Vector(modelNode)
    client.callCount shouldBe 0
    agent.resume(threadId, Map(parked.proceed(breakpointsOf(parked).head._1))).value.answer shouldBe Some("hi")
  }

  it should "be refused for a node the agent does not have" in {
    val agent = plain(ScriptedLLMClient.of())
    agent.run(ThreadId("bad"), "hello", RunConfig(interruptAfter = Set(NodeId("assistant/nope")))).error shouldBe
      ValidationError(
        "breakpoints",
        List("interruptAfter names node 'assistant/nope', which graph 'assistant' does not have")
      )
  }

  "A breakpoint and an approval" should "be pending together, and answered together" in {
    ran.clear()
    val client = ScriptedLLMClient.of(
      calling(call("c1", "deploy", "one"), call("c2", "deploy", "two")),
      CompletionFixture.simple("ok")
    )
    val agent = built(
      builder(client)
        .withMiddleware(new ApprovalMiddleware(r => Option.when(r.call.id == "c1")("check c1")))
        .withInterruptAfter(AgentNode.Tool)
    )
    val parked = agent.run("deploy").value
    val (approval, held) = parked.status match {
      case AgentStatus.Suspended(approvals, Vector(), Vector(), breakpoints) =>
        approvals.map(_._2.call.id) shouldBe Vector("c1")
        breakpoints.map(_._2) shouldBe Vector(BreakpointRequest(toolNode, BreakpointPhase.After, None))
        (approvals.head._1, breakpoints.head._1)
      case other => fail(s"expected an approval and a breakpoint, got $other")
    }
    ran.asScala.toVector shouldBe Vector("two")
    // both answered at once: c2's result is released, and c1 runs at the approval node, which the
    // breakpoint on tool calls does not hold
    val done = agent.resume(parked.threadId, Map(parked.approve(approval), parked.proceed(held))).value
    done.answer shouldBe Some("ok")
    ran.asScala.toVector shouldBe Vector("two", "one")
  }

  "A thread held by an agent" should "be continued by one built without the breakpoints, since they are not part of its version" in {
    val runtime = GraphRuntime.inMemory()
    val client  = ScriptedLLMClient.of(CompletionFixture.simple("answer"))
    val holding = built(Agent.builder("assistant", client).withRuntime(runtime).withInterruptBefore(AgentNode.Model))
    val free    = built(Agent.builder("assistant", client).withRuntime(runtime))
    holding.loop.graph.version shouldBe free.loop.graph.version

    val parked = holding.run(ThreadId("shared"), "q").value
    free.resume(parked.threadId, Map(parked.proceed(breakpointsOf(parked).head._1))).value.answer shouldBe Some(
      "answer"
    )
  }

  "Resuming a breakpoint" should "refuse an answer that is not null, leaving it pending" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("x"))
    val agent  = built(Agent.builder("assistant", client).withInterruptBefore(AgentNode.Model))
    val parked = agent.run("q").value
    val id     = breakpointsOf(parked).head._1
    agent.resume(parked.threadId, Map(parked.approve(id))).error shouldBe a[GraphError.InvalidResume]
    agent.resume(parked.threadId, Map(parked.proceed(id))).value.answer shouldBe Some("x")
  }

  "A continuation that fails" should "be recovered without being held again" in {
    val failOnce = new AtomicBoolean(true)
    val runs     = new AtomicInteger(0)
    val flaky = SpecTools.tool("deploy") { (_, _) =>
      runs.incrementAndGet()
      if failOnce.getAndSet(false) then ToolOutcome.Fatal(ValidationError("deploy", "store down"))
      else ToolOutcome.Success(ujson.Str("ok"))
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "deploy")), CompletionFixture.simple("done"))
    val agent =
      built(Agent.builder("assistant", client).withTools(SpecTools.set(flaky)).withInterruptBefore(AgentNode.Tool))
    val parked = agent.run("go").value
    agent.resume(parked.threadId, Map(parked.proceed(breakpointsOf(parked).head._1))).error shouldBe a[GraphError]
    agent.recover(parked.threadId).value.answer shouldBe Some("done")
    runs.get shouldBe 2
  }

  "Cancelling a held call's continuation" should "leave it for recover, which runs it without holding it again" in {
    val entered  = new CountDownLatch(1)
    val blocking = new AtomicBoolean(true)
    val slow = SpecTools.tool("deploy") { (_, _) =>
      if blocking.get then {
        entered.countDown()
        Thread.sleep(60_000) // interrupted by cancel
      }
      ToolOutcome.Success(ujson.Str("ok"))
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "deploy")), CompletionFixture.simple("done"))
    val agent =
      built(Agent.builder("assistant", client).withTools(SpecTools.set(slow)).withInterruptBefore(AgentNode.Tool))
    val parked = agent.run("go").value

    val run = agent
      .startResume(parked.threadId, Map(parked.proceed(breakpointsOf(parked).head._1)))
      .fold(e => fail(e.message), identity)
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    run.cancel()
    run.await().isLeft shouldBe true

    blocking.set(false)
    agent.recover(parked.threadId).value.answer shouldBe Some("done")
  }

  "Every agent of a family" should "hold its own nodes" in {
    val client = ScriptedLLMClient.of(
      calling(call("h1", "handoff_to_expert")),
      CompletionFixture.simple("expert answer")
    )
    val expert = Agent.builder("expert", client).withInterruptBefore(AgentNode.Model)
    val agent  = built(Agent.builder("assistant", client).withHandoffs(Handoff.to("expert", expert)))
    val parked = agent.run("hard question").value
    breakpointsOf(parked).map(_._2.node) shouldBe Vector(ToolLoop.modelNode(AgentId.unsafe("expert")))
    parked.activeAgent shouldBe AgentId.unsafe("expert")
    agent.resume(parked.threadId, Map(parked.proceed(breakpointsOf(parked).head._1))).value.answer shouldBe
      Some("expert answer")
  }

  "ToolLoop's node ids" should "name an agent's model, tool-call and finish nodes" in {
    val id = AgentId.unsafe("a")
    (ToolLoop.modelNode(id), ToolLoop.callToolNode(id), ToolLoop.finishNode(id)) shouldBe
      ((NodeId("a/model"), NodeId("a/call-tool"), NodeId("a/finish")))
  }
}
