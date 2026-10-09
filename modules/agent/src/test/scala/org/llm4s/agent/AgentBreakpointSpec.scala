package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.AgentMiddlewareQuestionSpec.{ Info, Need, Review }
import org.llm4s.agent.SpecTools.{ call, calling, Text }
import org.llm4s.agent.graph.{
  BreakpointPhase,
  GraphError,
  GraphRuntime,
  InterruptId,
  NodeId,
  RunConfig,
  RunContext,
  ThreadId
}
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  ApprovalMiddleware,
  MiddlewareId,
  ModelRequest,
  ToolCallRequest
}
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContext, ToolOutcome }
import org.llm4s.agent.graph.toolloop.{ BreakpointRequest, ToolLoop }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
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

  /** The one interrupt `result` waits for: a middleware question. */
  private def onlyQuestion(result: AgentResult): InterruptId = result.status match {
    case AgentStatus.Suspended(Vector(), Vector(), Vector((id, _)), Vector()) => id
    case other => fail(s"expected one middleware question, got $other")
  }

  /** Asks before every model call, and lets it go ahead once answered. */
  final private class SpendGate extends AgentMiddleware.Asking[Need, Boolean] {
    val id: MiddlewareId = MiddlewareId("spend")
    override def wrapModelCall(request: ModelRequest, context: RunContext)(
      next: ModelRequest => Result[Completion]
    ): Result[Completion] = answered(context).fold(ask(Need("spend?")))(_ => next(request))
  }

  /** Asks which environment each tool call is for, and runs it once answered. */
  final private class EnvGate extends AgentMiddleware.Asking[Need, Info] {
    val id: MiddlewareId = MiddlewareId("env")
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      answered(context).fold(askAbout(Need("env?")))(_ => next())
  }

  private def builder(client: ScriptedLLMClient): AgentBuilder =
    Agent.builder("assistant", client).withTools(SpecTools.set(deploy))

  private val spendNode  = NodeId("assistant/asked/spend/wrapModelCall")
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
    // both answered at once: c2's result is released, and c1 runs at the approval node - and is held after it
    // ran there, as a call run at the tool-call node is
    val approved = agent.resume(parked.threadId, Map(parked.approve(approval), parked.proceed(held))).value
    ran.asScala.toVector shouldBe Vector("two", "one")
    val again = breakpointsOf(approved)
    again.map(_._2) shouldBe Vector(BreakpointRequest(NodeId("assistant/approval"), BreakpointPhase.After, None))
    client.callCount shouldBe 1
    agent.resume(parked.threadId, Map(approved.proceed(again.head._1))).value.answer shouldBe Some("ok")
  }

  "A breakpoint after the model" should "hold a model call continued after a wrapModelCall question" in {
    ran.clear()
    val client = ScriptedLLMClient.of(calling(call("c1", "deploy", "prod")), CompletionFixture.simple("done"))
    val agent  = built(builder(client).withMiddleware(new SpendGate).withInterruptAfter(AgentNode.Model))
    val parked = agent.run("deploy").value
    client.callCount shouldBe 0

    // answered, the gate calls the model at its question's node: the message is stored, and its tool call waits
    val answered = agent.resume(parked.threadId, Map(parked.reply(onlyQuestion(parked), true))).value
    val held     = breakpointsOf(answered)
    held.map(_._2) shouldBe Vector(BreakpointRequest(spendNode, BreakpointPhase.After, None))
    answered.messages.last shouldBe AssistantMessage(None, Seq(call("c1", "deploy", "prod")))
    ran.size shouldBe 0

    // released, the call runs; the next model call is gated, and held after once answered, like the first
    val next = agent.resume(parked.threadId, Map(answered.proceed(held.head._1))).value
    ran.asScala.toVector shouldBe Vector("prod")
    val second = agent.resume(parked.threadId, Map(next.reply(onlyQuestion(next), true))).value
    breakpointsOf(second).map(_._2.node) shouldBe Vector(spendNode)
    second.answer shouldBe None
    agent.resume(parked.threadId, Map(second.proceed(breakpointsOf(second).head._1))).value.answer shouldBe
      Some("done")
  }

  "A breakpoint before the model" should "hold the call once, not again after the gate's question is answered" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("done"))
    val agent =
      built(Agent.builder("assistant", client).withMiddleware(new SpendGate).withInterruptBefore(AgentNode.Model))
    val parked = agent.run("q").value
    breakpointsOf(parked).map(_._2) shouldBe Vector(BreakpointRequest(modelNode, BreakpointPhase.Before, None))

    val asked = agent.resume(parked.threadId, Map(parked.proceed(breakpointsOf(parked).head._1))).value
    client.callCount shouldBe 0
    val done = agent.resume(parked.threadId, Map(asked.reply(onlyQuestion(asked), true))).value
    done.answer shouldBe Some("done")
    client.callCount shouldBe 1
  }

  "A breakpoint after tool calls" should "hold a result recorded after a tool wrapper's question" in {
    ran.clear()
    val client = ScriptedLLMClient.of(calling(call("c1", "deploy", "prod")), CompletionFixture.simple("done"))
    val agent  = built(builder(client).withMiddleware(new EnvGate).withInterruptAfter(AgentNode.Tool))
    val parked = agent.run("deploy").value
    ran.size shouldBe 0

    val answered = agent.resume(parked.threadId, Map(parked.reply(onlyQuestion(parked), Info("prod")))).value
    ran.asScala.toVector shouldBe Vector("prod")
    val held = breakpointsOf(answered)
    held.map(_._2) shouldBe Vector(
      BreakpointRequest(NodeId("assistant/asked/env/wrapToolCall"), BreakpointPhase.After, None)
    )
    client.callCount shouldBe 1 // the model waits for the held result

    agent.resume(parked.threadId, Map(answered.proceed(held.head._1))).value.answer shouldBe Some("done")
  }

  it should "hold a result recorded after the tool's own question" in {
    val confirm = new AgentTool.Asking[Text, Need, Boolean](SpecTools.spec("confirm")) {
      def execute(args: Text, context: ToolContext): ToolOutcome = ask(Need("sure?"))
      def resume(args: Text, question: Need, answer: Boolean, context: ToolContext): ToolOutcome =
        ToolOutcome.Success(ujson.Str(s"confirmed $answer"))
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "confirm")), CompletionFixture.simple("done"))
    val agent =
      built(Agent.builder("assistant", client).withTools(SpecTools.set(confirm)).withInterruptAfter(AgentNode.Tool))
    val parked = agent.run("go").value
    val question = parked.status match {
      case AgentStatus.Suspended(Vector(), Vector((id, _)), Vector(), Vector()) => id
      case other => fail(s"expected a tool question, got $other")
    }
    val answered = agent.resume(parked.threadId, Map(parked.reply(question, true))).value
    breakpointsOf(answered).map(_._2.node) shouldBe Vector(NodeId("assistant/ask/confirm"))
    client.callCount shouldBe 1
    agent.resume(parked.threadId, Map(answered.proceed(breakpointsOf(answered).head._1))).value.answer shouldBe
      Some("done")
  }

  "A breakpoint after the final answer" should "hold an answer guarded again after an afterAgent question" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("draft"))
    val agent =
      built(Agent.builder("assistant", client).withMiddleware(new Review).withInterruptAfter(AgentNode.Finish))
    val parked   = agent.run("write").value
    val answered = agent.resume(parked.threadId, Map(parked.reply(onlyQuestion(parked), Info("final")))).value
    breakpointsOf(answered).map(_._2) shouldBe Vector(
      BreakpointRequest(NodeId("assistant/asked/review/afterAgent"), BreakpointPhase.After, None)
    )
    answered.answer shouldBe None
    answered.messages.last.content shouldBe "final"
    agent.resume(parked.threadId, Map(answered.proceed(breakpointsOf(answered).head._1))).value.answer shouldBe
      Some("final")
  }

  "ToolLoop.breakpointNodes" should "map an agent's nodes to the nodes that continue them after a review" in {
    val client = ScriptedLLMClient.of()
    val expert = Agent.builder("expert", client).withMiddleware(new SpendGate)
    val agent = built(
      Agent
        .builder("assistant", client)
        .withTools(SpecTools.set(deploy))
        .withMiddleware(new Review, new EnvGate)
        .withHandoffs(Handoff.to("expert", expert))
    )
    val (root, target)      = (AgentId.unsafe("assistant"), AgentId.unsafe("expert"))
    def nodes(ids: String*) = ids.map(NodeId(_)).toSet
    import BreakpointPhase.{ After, Before }
    // every asking middleware has a question node per hook
    agent.loop.breakpointNodes(root, AgentNode.Model, Before) shouldBe nodes("assistant/model")
    agent.loop.breakpointNodes(root, AgentNode.Model, After) shouldBe
      nodes("assistant/model", "assistant/asked/review/wrapModelCall", "assistant/asked/env/wrapModelCall")
    agent.loop.breakpointNodes(root, AgentNode.Tool, Before) shouldBe nodes("assistant/call-tool")
    agent.loop.breakpointNodes(root, AgentNode.Tool, After) shouldBe nodes(
      "assistant/call-tool",
      "assistant/approval",
      "assistant/asked/review/wrapToolCall",
      "assistant/asked/env/wrapToolCall"
    )
    agent.loop.breakpointNodes(root, AgentNode.Finish, After) shouldBe
      nodes("assistant/finish", "assistant/asked/review/afterAgent", "assistant/asked/env/afterAgent")
    agent.loop.breakpointNodes(target, AgentNode.Model, After) shouldBe
      nodes("expert/model", "expert/asked/spend/wrapModelCall")
    // the root's afterAgent questions about the target's answer continue at the root's nodes
    agent.loop.breakpointNodes(target, AgentNode.Finish, After) shouldBe nodes(
      "expert/finish",
      "expert/asked/spend/afterAgent",
      "assistant/asked/review/afterAgent",
      "assistant/asked/env/afterAgent"
    )
    agent.loop.breakpointNodes(target, AgentNode.Finish, Before) shouldBe nodes("expert/finish")
  }

  "BreakpointRequest" should "be built with apply and changed with its with* setters" in {
    val request = BreakpointRequest(modelNode, BreakpointPhase.Before)
    request.call shouldBe None
    request.withNode(toolNode).node shouldBe toolNode
    request.withPhase(BreakpointPhase.After).phase shouldBe BreakpointPhase.After
    request.withCall(call("c1", "deploy")).call shouldBe Some(call("c1", "deploy"))
    request.withCall(None).call shouldBe None
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
